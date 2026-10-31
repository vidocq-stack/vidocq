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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The packages of the application that a runtime module reaches by reflection, read from the compiled classes
 * (BUG-20261030-24): the JPA entities, and the types a REST resource or provider takes or returns, with the types
 * their fields and getters hold, transitively. JSON-B binds those types by reflection, so their package must be
 * opened, or at least exported, or the first request fails with an {@code IllegalAccessException}.
 *
 * <p>The CDI interceptors too (BUG-20261031-04), when one of the methods their container calls is private: Vauban calls
 * the others through the provider generated in their package (VAU-INT-011), but a private one by reflection, which
 * needs the package opened.</p>
 *
 * <p>The resources themselves are not listed: their dispatch is generated at build time.</p>
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
        Map<String, List<String>> subtypes = subtypes(application);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String[] next = queue.poll();
            ClassModel model = application.get(next[0]);
            if (model == null || !seen.add(next[0])) {
                continue;
            }
            reasons.putIfAbsent(packageOf(next[0]), dotted(next[0]) + ", " + next[1]);
            for (String type : heldTypes(model)) {
                queue.add(new String[] {type, "reached through " + dotted(next[0])});
            }
            // JSON-B and JPA read the inherited fields and getters too.
            model.superclass().ifPresent(superclass -> queue.add(
                    new String[] {superclass.asInternalName(), "superclass of " + dotted(next[0])}));
            // A field typed by an interface or an abstract class holds one of the application's implementations.
            if (polymorphic(model)) {
                String verb = (model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0 ? "implements " : "extends ";
                for (String subtype : subtypes.getOrDefault(next[0], List.of())) {
                    queue.add(new String[] {subtype, verb + dotted(next[0])});
                }
            }
        }
        return new Reached(reasons, needsOpens);
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
     * The starting points {@code model} gives, as {@code {class, how it is reached}}: itself when it is a JPA
     * entity, and the types its public methods take or return when it is a REST resource or provider.
     */
    private static List<String[]> roots(ClassModel model) {
        String name = model.thisClass().asInternalName();
        Set<String> annotations = annotations(model);
        List<String[]> roots = new ArrayList<>();
        if (annotations.stream().anyMatch(ENTITY_ANNOTATIONS::contains)) {
            roots.add(new String[] {name, "a JPA entity"});
        }
        if (annotations.stream().anyMatch(RESOURCE_ANNOTATIONS::contains)) {
            model.methods().stream()
                    .filter(method -> (method.flags().flagsMask() & ClassFile.ACC_PUBLIC) != 0)
                    .flatMap(method -> referenced(signature(method)).stream())
                    .forEach(type -> roots.add(new String[] {type, "reached through " + dotted(name)}));
        }
        return roots;
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
