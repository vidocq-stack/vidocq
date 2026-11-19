/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.tooling;

import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.constant.ClassDesc;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The packages of the application that a runtime module reaches by reflection, read from the compiled classes
 * (BUG-20261030-24): the entities (Jakarta Data) Mansart has no model generated at build time for, and the types a REST resource or provider takes or returns, with the types
 * their fields and getters hold, transitively. JSON-B binds those types by reflection, so their package must be
 * opened, or at least exported, or the first request fails with an {@code IllegalAccessException}.
 *
 * <p>The CDI interceptors too (BUG-20261031-04), when one of the methods their container calls is private: Vauban calls
 * the others through the provider generated in their package (VAU-INT-011), but a private one by reflection, which
 * needs the package opened.</p>
 *
 * <p>The resources themselves are not listed: their dispatch is generated at build time. Nor are the types that have
 * a JSON-B binding generated at build time (champollion-codegen-apt, listed in the {@code JsonbBinding} services
 * file): JSON-B maps them without reflection, and the processor generates one only when every type they hold has one
 * too.</p>
 *
 * <p>An entity's package is not listed when Mansart's processor generated its model (MANSART-038): the
 * {@code _MansartComponents} its services file lists names the entity, and hands Mansart the model by plain calls. An
 * entity it does not name is read by reflection, Mansart's fallback, and its package must be opened. Nor when
 * Noether's processor generated its access: the {@code _NoetherJpaAccess} its services file lists names a
 * {@code ManagedAccess} per entity, which Noether calls instead of reflecting.</p>
 */
public final class ReflectedPackages {

    /** Class annotations that make the runtime reach the class, or the types of its methods, by reflection. */
    private static final Set<String> RESOURCE_ANNOTATIONS = Set.of(
            "Ljakarta/ws/rs/Path;", "Ljakarta/ws/rs/ext/Provider;");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Ljakarta/persistence/Entity;", "Ljakarta/persistence/Embeddable;",
            "Ljakarta/persistence/MappedSuperclass;");
    private static final String INTERCEPTOR = "Ljakarta/interceptor/Interceptor;";
    /** The annotations of the methods the container calls on an interceptor. */
    private static final Set<String> INTERCEPTOR_METHOD_ANNOTATIONS = Set.of(
            "Ljakarta/interceptor/AroundInvoke;", "Ljakarta/interceptor/AroundConstruct;",
            "Ljakarta/interceptor/AroundTimeout;", "Ljakarta/annotation/PostConstruct;",
            "Ljakarta/annotation/PreDestroy;");

    private static final String JSONB_BINDING = "io/vidocq/champollion/jsonb/spi/JsonbBinding";
    private static final String JSONB_BINDING_SERVICES = "META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding";
    private static final String ENTITY = "an entity (Jakarta Data) without a model generated at build time";
    private static final String MANSART_COMPONENTS_SERVICES =
            "META-INF/services/io.vidocq.mansart.data.core.GeneratedComponents";
    private static final String NOETHER_ACCESS_SERVICES =
            "META-INF/services/io.vidocq.noether.jpa.core.spi.ManagedAccessProvider";
    /** The superclass of the per-entity access Noether's processor generates. */
    private static final String NOETHER_MANAGED_ACCESS = "io/vidocq/noether/jpa/core/spi/ManagedAccess";
    /** Where Champollion's processor lists the entities the code gives a JAX-RS {@code Response}. */
    static final String RESPONSE_ENTITIES = "META-INF/champollion/rest-entity-types";
    /** The type argument of {@code JsonbBinding<T>} in a binding's class signature. */
    private static final Pattern BOUND_TYPE = Pattern.compile("L" + JSONB_BINDING + "<L([\\w$/]+);>;");

    /** A class name inside a descriptor or a generic signature: {@code Lcom/acme/Book;}, {@code Lcom/acme/Box<}. */
    private static final Pattern CLASS_REF = Pattern.compile("L([\\w$/]+)[;<]");

    private ReflectedPackages() {}

    /**
     * The packages the runtime reaches by reflection.
     *
     * @param reasons    each package, with the class that needs it and how it is reached, sorted by package
     * @param needsOpens the packages an export does not satisfy: the runtime calls a non-public member there
     */
    public record Reached(Map<String, String> reasons, Set<String> needsOpens) {}

    /**
     * The packages of the module's classes the runtime reaches by reflection.
     *
     * @param classes the module's compiled classes ({@code target/classes})
     */
    public static Reached of(Path classes) throws IOException {
        Map<String, ClassModel> application = read(classes);
        Map<String, String> reasons = new TreeMap<>();
        Set<String> needsOpens = new HashSet<>();
        for (ClassModel model : application.values()) {
            if (annotations(model).contains(INTERCEPTOR)) {
                String name = model.thisClass().asInternalName();
                if (hasPrivateCalledMethod(model)) {
                    reasons.put(packageOf(name), dotted(name)
                            + ", a CDI interceptor with a private method its container calls by reflection");
                    needsOpens.add(packageOf(name));
                }
            }
        }
        Deque<String[]> queue = new ArrayDeque<>();
        for (ClassModel model : application.values()) {
            queue.addAll(roots(model));
        }
        queue.addAll(responseEntities(classes));
        Map<String, List<String>> subtypes = subtypes(application);
        Set<String> bound = boundTypes(classes, application);
        Set<String> modelled = mansartModelled(classes, application);
        modelled.addAll(noetherModelled(classes, application));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String[] next = queue.poll();
            ClassModel model = application.get(next[0]);
            boolean entity = ENTITY.equals(next[1]);
            if (model == null || entity && modelled.contains(next[0]) || bound.contains(next[0]) && !entity
                    || !seen.add(next[0])) {
                continue;
            }
            reasons.putIfAbsent(packageOf(next[0]), dotted(next[0]) + ", " + next[1]);
            queue.addAll(reachedFrom(model, subtypes));
        }
        return new Reached(reasons, needsOpens);
    }

    /**
     * What a reached type leads to, as {@code {class, how it is reached}}: the types its fields and getters hold, its
     * superclass, whose inherited fields and getters JSON-B and Mansart's reflective fallback read too, and, for an interface or an abstract
     * class, the application classes that implement or extend it directly — a field of that type holds one of them.
     */
    private static List<String[]> reachedFrom(ClassModel model, Map<String, List<String>> subtypes) {
        String name = model.thisClass().asInternalName();
        List<String[]> reached = new ArrayList<>();
        for (String type : heldTypes(model)) {
            reached.add(new String[] {type, "reached through " + dotted(name)});
        }
        model.superclass().ifPresent(superclass ->
                reached.add(new String[] {superclass.asInternalName(), "superclass of " + dotted(name)}));
        if (polymorphic(model)) {
            String verb = (model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0 ? "implements " : "extends ";
            for (String subtype : subtypes.getOrDefault(name, List.of())) {
                reached.add(new String[] {subtype, verb + dotted(name)});
            }
        }
        return reached;
    }

    /** Whether a reached type stands for its implementations: an interface or an abstract class. */
    private static boolean polymorphic(ClassModel model) {
        return (model.flags().flagsMask() & (ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)) != 0;
    }

    /** The application classes that directly extend or implement each application type. */
    private static Map<String, List<String>> subtypes(Map<String, ClassModel> application) {
        Map<String, List<String>> subtypes = new HashMap<>();
        for (ClassModel model : application.values()) {
            String name = model.thisClass().asInternalName();
            model.superclass().ifPresent(superclass ->
                    subtypes.computeIfAbsent(superclass.asInternalName(), _ -> new ArrayList<>()).add(name));
            model.interfaces().forEach(implemented ->
                    subtypes.computeIfAbsent(implemented.asInternalName(), _ -> new ArrayList<>()).add(name));
        }
        return subtypes;
    }

    /** Whether a method the container calls on {@code interceptor} is private: no generated provider can call it. */
    private static boolean hasPrivateCalledMethod(ClassModel interceptor) {
        for (MethodModel method : interceptor.methods()) {
            boolean called = method.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                    .flatMap(a -> a.annotations().stream())
                    .anyMatch(an -> INTERCEPTOR_METHOD_ANNOTATIONS.contains(an.className().stringValue()));
            if (called && (method.flags().flagsMask() & ClassFile.ACC_PRIVATE) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every package the compiled classes under {@code classes} actually refer to: the owners in each
     * constant pool plus the field and method descriptor types. Used to decide whether a {@code requires}
     * an extension's catalog states really applies to the module under build -- a module that depends on
     * an extension to extend it, rather than to use the API it leaves to the application, refers to none
     * of those packages and must not be told to require them (BUG-20261109-01).
     *
     * <p>Own packages are included; callers intersect with the exports of the module they are testing,
     * so that costs nothing. Returns an empty set when {@code classes} holds no class files.</p>
     */
    public static Set<String> referencedPackages(Path classes) throws IOException {
        Set<String> packages = new TreeSet<>();
        for (ClassModel model : read(classes).values()) {
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof ClassEntry type) {
                    add(packages, type.asInternalName());
                }
            }
            for (FieldModel field : model.fields()) {
                addDesc(packages, field.fieldTypeSymbol());
            }
            for (MethodModel method : model.methods()) {
                addDesc(packages, method.methodTypeSymbol().returnType());
                method.methodTypeSymbol().parameterList().forEach(p -> addDesc(packages, p));
            }
        }
        return packages;
    }

    /** Records the package of an internal name, unwrapping array descriptors and ignoring primitives. */
    private static void add(Set<String> packages, String internalName) {
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.isEmpty() || !name.contains("/")) {
            return;
        }
        packages.add(packageOf(name));
    }

    private static void addDesc(Set<String> packages, ClassDesc desc) {
        ClassDesc type = desc;
        while (type.isArray()) {
            type = type.componentType();
        }
        if (type.isClassOrInterface()) {
            add(packages, type.descriptorString());
        }
    }

    /**
     * The packages of {@code reached} the module does not open (to anyone), nor export to all when an export is
     * enough; none for an open module.
     */
    public static List<String> missing(ModuleDescriptor descriptor, Reached reached) {
        if (descriptor.isOpen()) {
            return List.of();
        }
        Set<String> opened = new HashSet<>();
        descriptor.opens().forEach(o -> opened.add(o.source()));
        Set<String> exported = new HashSet<>();
        descriptor.exports().stream().filter(e -> !e.isQualified()).forEach(e -> exported.add(e.source()));
        List<String> missing = new ArrayList<>();
        for (String pkg : reached.reasons().keySet()) {
            boolean satisfied = opened.contains(pkg) || exported.contains(pkg) && !reached.needsOpens().contains(pkg);
            if (!satisfied) {
                missing.add(pkg);
            }
        }
        return missing;
    }

    /**
     * The packages of the compiled module in {@code classes} that the runtime reaches by reflection and that neither
     * its {@code module-info.class} nor {@code moduleInfoSource} opens (or exports, when that is enough), each with the
     * reason, sorted; empty when {@code classes} holds no {@code module-info.class} yet. The source is read too,
     * so a directive added since the last compile counts: what {@code vidocq doctor} asks (BUG-20261031-01).
     */
    public static Optional<Map<String, String>> unopened(Path classes, String moduleInfoSource) throws IOException {
        Path moduleInfo = classes.resolve("module-info.class");
        if (!Files.isRegularFile(moduleInfo)) {
            return Optional.empty();
        }
        ModuleDescriptor descriptor;
        try (var in = Files.newInputStream(moduleInfo)) {
            descriptor = ModuleDescriptor.read(in);
        }
        Reached reached = of(classes);
        Map<String, String> unopened = new TreeMap<>();
        for (String pkg : missing(descriptor, reached)) {
            List<String> satisfying = reached.needsOpens().contains(pkg)
                    ? List.of("opens " + pkg) : List.of("opens " + pkg, "exports " + pkg);
            boolean declared = moduleInfoSource != null
                    && ModuleInfoEditor.missing(moduleInfoSource, satisfying).size() < satisfying.size();
            if (!declared) {
                unopened.put(pkg, reached.reasons().get(pkg));
            }
        }
        return Optional.of(unopened);
    }

    /**
     * The starting points {@code model} gives, as {@code {class, how it is reached}}: itself when it is an
     * entity, and the types its public methods take or return when it is a REST resource or provider.
     */
    private static List<String[]> roots(ClassModel model) {
        String name = model.thisClass().asInternalName();
        Set<String> annotations = annotations(model);
        List<String[]> roots = new ArrayList<>();
        if (annotations.stream().anyMatch(ENTITY_ANNOTATIONS::contains)) {
            roots.add(new String[] {name, ENTITY});
        }
        if (annotations.stream().anyMatch(RESOURCE_ANNOTATIONS::contains)) {
            model.methods().stream()
                    .filter(method -> (method.flags().flagsMask() & ClassFile.ACC_PUBLIC) != 0)
                    .flatMap(method -> referenced(signature(method)).stream())
                    .forEach(type -> roots.add(new String[] {type, "reached through " + dotted(name)}));
        }
        return roots;
    }

    /**
     * The entities the code gives a JAX-RS {@code Response}, as Champollion's processor lists them from the method
     * bodies ({@value #RESPONSE_ENTITIES}): JAX-RS hands them to JSON-B at run time, which no signature shows.
     */
    private static List<String[]> responseEntities(Path classes) throws IOException {
        Path listed = classes.resolve(RESPONSE_ENTITIES);
        if (!Files.isRegularFile(listed)) {
            return List.of();
        }
        try (Stream<String> lines = Files.lines(listed)) {
            return lines.map(String::strip).filter(name -> !name.isEmpty())
                    .map(name -> new String[] {name.replace('.', '/'), "the entity of a JAX-RS Response"}).toList();
        }
    }

    /**
     * The types JSON-B maps through a binding generated at build time: those of the classes the {@code JsonbBinding}
     * services file lists, read from their {@code JsonbBinding<T>} signature.
     */
    private static Set<String> boundTypes(Path classes, Map<String, ClassModel> application) throws IOException {
        Path services = classes.resolve(JSONB_BINDING_SERVICES);
        Set<String> bound = new HashSet<>();
        if (!Files.isRegularFile(services)) {
            return bound;
        }
        try (Stream<String> lines = Files.lines(services)) {
            lines.map(String::strip)
                    .filter(binding -> !binding.isEmpty() && !binding.startsWith("#"))
                    .map(binding -> application.get(binding.replace('.', '/')))
                    .filter(java.util.Objects::nonNull)
                    .flatMap(model -> model.findAttribute(Attributes.signature()).stream())
                    .map(signature -> BOUND_TYPE.matcher(signature.signature().stringValue()))
                    .filter(Matcher::find)
                    .forEach(m -> bound.add(m.group(1)));
        }
        return bound;
    }

    /**
     * The entities Mansart's processor generated a model for: the classes the {@code _MansartComponents} its services
     * file lists refer to (MANSART-038).
     */
    private static Set<String> mansartModelled(Path classes, Map<String, ClassModel> application) throws IOException {
        Path services = classes.resolve(MANSART_COMPONENTS_SERVICES);
        Set<String> modelled = new HashSet<>();
        if (!Files.isRegularFile(services)) {
            return modelled;
        }
        try (Stream<String> lines = Files.lines(services)) {
            lines.map(String::strip)
                    .filter(provider -> !provider.isEmpty() && !provider.startsWith("#"))
                    .map(provider -> application.get(provider.replace('.', '/')))
                    .filter(java.util.Objects::nonNull)
                    .flatMap(model -> StreamSupport.stream(model.constantPool().spliterator(), false))
                    .filter(ClassEntry.class::isInstance)
                    .forEach(entry -> modelled.add(((ClassEntry) entry).asInternalName()));
        }
        return modelled;
    }

    /**
     * The entities Noether's processor generated an access for: the classes the {@code ManagedAccess} subclasses the
     * {@code _NoetherJpaAccess} its services file lists refer to. Noether reaches them through that access, by method
     * handles the processor planned; an entity it does not name is read through a private lookup, which needs its
     * package opened.
     */
    private static Set<String> noetherModelled(Path classes, Map<String, ClassModel> application) throws IOException {
        Path services = classes.resolve(NOETHER_ACCESS_SERVICES);
        Set<String> modelled = new HashSet<>();
        if (!Files.isRegularFile(services)) {
            return modelled;
        }
        try (Stream<String> lines = Files.lines(services)) {
            lines.map(String::strip)
                    .filter(provider -> !provider.isEmpty() && !provider.startsWith("#"))
                    .map(provider -> application.get(provider.replace('.', '/')))
                    .filter(java.util.Objects::nonNull)
                    .flatMap(ReflectedPackages::classEntries)
                    .map(application::get)
                    .filter(java.util.Objects::nonNull)
                    .filter(access -> access.superclass()
                            .filter(superclass -> NOETHER_MANAGED_ACCESS.equals(superclass.asInternalName()))
                            .isPresent())
                    .flatMap(ReflectedPackages::classEntries)
                    .forEach(modelled::add);
        }
        return modelled;
    }

    /** The classes the constant pool of {@code model} names. */
    private static Stream<String> classEntries(ClassModel model) {
        return StreamSupport.stream(model.constantPool().spliterator(), false)
                .filter(ClassEntry.class::isInstance)
                .map(entry -> ((ClassEntry) entry).asInternalName());
    }

    /** The types the non-static fields and the getters of {@code model} hold. */
    private static List<String> heldTypes(ClassModel model) {
        List<String> types = new ArrayList<>();
        for (FieldModel field : model.fields()) {
            if ((field.flags().flagsMask() & ClassFile.ACC_STATIC) == 0) {
                types.addAll(referenced(field.findAttribute(Attributes.signature())
                        .map(s -> s.signature().stringValue()).orElse(field.fieldType().stringValue())));
            }
        }
        for (MethodModel method : model.methods()) {
            String name = method.methodName().stringValue();
            boolean getter = (name.startsWith("get") || name.startsWith("is"))
                    && method.methodTypeSymbol().parameterCount() == 0
                    && (method.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) == ClassFile.ACC_PUBLIC;
            if (getter) {
                types.addAll(referenced(signature(method)));
            }
        }
        return types;
    }

    private static String signature(MethodModel method) {
        return method.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue())
                .orElse(method.methodType().stringValue());
    }

    private static List<String> referenced(String descriptor) {
        List<String> names = new ArrayList<>();
        Matcher m = CLASS_REF.matcher(descriptor);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static Set<String> annotations(ClassModel model) {
        Set<String> names = new HashSet<>();
        model.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a ->
                a.annotations().forEach(an -> names.add(an.className().stringValue())));
        return names;
    }

    private static Map<String, ClassModel> read(Path classes) throws IOException {
        Map<String, ClassModel> models = new HashMap<>();
        if (!Files.isDirectory(classes)) {
            return models;
        }
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class")
                    && !f.getFileName().toString().equals("module-info.class"))::iterator) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                models.put(model.thisClass().asInternalName(), model);
            }
        }
        return models;
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash).replace('/', '.');
    }

    private static String dotted(String internalName) {
        return internalName.replace('/', '.');
    }
}
