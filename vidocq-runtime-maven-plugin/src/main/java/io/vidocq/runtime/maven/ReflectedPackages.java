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
package io.vidocq.runtime.maven;

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
 * <p>The resources themselves are not listed: their dispatch is generated at build time.</p>
 */
final class ReflectedPackages {

    /** Class annotations that make the runtime reach the class, or the types of its methods, by reflection. */
    private static final Set<String> RESOURCE_ANNOTATIONS = Set.of(
            "Ljakarta/ws/rs/Path;", "Ljakarta/ws/rs/ext/Provider;");
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of(
            "Ljakarta/persistence/Entity;", "Ljakarta/persistence/Embeddable;",
            "Ljakarta/persistence/MappedSuperclass;");

    /** A class name inside a descriptor or a generic signature: {@code Lcom/acme/Book;}, {@code Lcom/acme/Box<}. */
    private static final Pattern CLASS_REF = Pattern.compile("L([\\w$/]+)[;<]");

    private ReflectedPackages() {}

    /**
     * Each package to open, with the class that needs it and how it is reached, sorted by package.
     *
     * @param classes the module's compiled classes ({@code target/classes})
     */
    static Map<String, String> of(Path classes) throws IOException {
        Map<String, ClassModel> application = read(classes);
        Map<String, String> reasons = new TreeMap<>();
        Deque<String[]> queue = new ArrayDeque<>();
        for (ClassModel model : application.values()) {
            queue.addAll(roots(model));
        }
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
        }
        return reasons;
    }

    /**
     * The packages of {@code reached} the module neither opens (to anyone) nor exports to all; none for an open
     * module.
     */
    static List<String> missing(ModuleDescriptor descriptor, Map<String, String> reached) {
        if (descriptor.isOpen()) {
            return List.of();
        }
        Set<String> opened = new HashSet<>();
        descriptor.opens().forEach(o -> opened.add(o.source()));
        descriptor.exports().stream().filter(e -> !e.isQualified()).forEach(e -> opened.add(e.source()));
        List<String> missing = new ArrayList<>();
        for (String pkg : reached.keySet()) {
            if (!opened.contains(pkg)) {
                missing.add(pkg);
            }
        }
        return missing;
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
