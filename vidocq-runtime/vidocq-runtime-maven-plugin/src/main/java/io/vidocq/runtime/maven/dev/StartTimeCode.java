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
package io.vidocq.runtime.maven.dev;

import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The start-time methods of the application (design §2, rule 6): the methods that ran once before a change and will
 * not run again until a swap. The roots are the static initializers, and what the declarations (§2.1) name: the
 * constructors of long-lived classes, the methods with a start annotation, the observers of a start event, the start
 * callbacks. Then every application method a root calls, directly or not: an {@code invokevirtual} or
 * {@code invokeinterface} reaches the target and its overrides in the application. A lambda created in a start-time
 * method is not reached, since creating it does not run it.
 */
final class StartTimeCode {

    private static final String INIT = "<init>";
    private static final String CLINIT = "<clinit>";

    private final Map<String, ClassModel> classes = new HashMap<>();
    private final Map<String, List<String>> declaringClasses = new HashMap<>();
    private final StartTimeDeclarations declarations;
    private final SupertypeIndex supertypes;

    private StartTimeCode(ClassesSnapshot app, StartTimeDeclarations declarations, SupertypeIndex supertypes) {
        this.declarations = declarations;
        this.supertypes = supertypes;
        app.classes().forEach((name, fingerprint) -> {
            classes.put(name, fingerprint.model());
            for (MethodModel m : fingerprint.model().methods()) {
                declaringClasses.computeIfAbsent(key(m), _ -> new ArrayList<>()).add(name);
            }
        });
    }

    /** {@code binaryName#name+descriptor} of every start-time method of the application, call graph included. */
    static Set<String> of(ClassesSnapshot app, StartTimeDeclarations declarations, SupertypeIndex supertypes) {
        return new StartTimeCode(app, declarations, supertypes).all();
    }

    private Set<String> all() {
        Set<String> reached = new LinkedHashSet<>();
        var queue = new ArrayDeque<String>();
        classes.forEach((name, model) -> {
            boolean longLived = longLived(name, model);
            for (MethodModel m : model.methods()) {
                if (isRoot(name, m, longLived)) {
                    queue.add(name + "#" + key(m));
                }
            }
        });
        while (!queue.isEmpty()) {
            String method = queue.poll();
            if (reached.add(method)) {
                calls(method).forEach(queue::add);
            }
        }
        return reached;
    }

    private boolean isRoot(String className, MethodModel method, boolean longLived) {
        String name = method.methodName().stringValue();
        if (name.equals(CLINIT) || (longLived && name.equals(INIT))) {
            return true;
        }
        if (annotations(method).anyMatch(declarations.startAnnotations()::contains) || observesAStartEvent(method)) {
            return true;
        }
        String descriptor = method.methodType().stringValue();
        return declarations.startCallbacks().stream()
                .anyMatch(c -> c.name().equals(name) && c.descriptor().equals(descriptor)
                        && (supertypes.isA(className, c.owner()) || supertypes.unresolved(className)));
    }

    private boolean longLived(String className, ClassModel model) {
        if (annotations(model).anyMatch(declarations.longLivedAnnotations()::contains)) {
            return true;
        }
        if (declarations.longLivedTypes().isEmpty()) {
            return false;
        }
        return declarations.longLivedTypes().stream().anyMatch(t -> supertypes.isA(className, t))
                || supertypes.unresolved(className);
    }

    private boolean observesAStartEvent(MethodModel method) {
        if (declarations.observerAnnotations().isEmpty()) {
            return false;
        }
        List<List<Annotation>> parameters = new ArrayList<>();
        method.findAttribute(Attributes.runtimeVisibleParameterAnnotations())
                .ifPresent(a -> parameters.addAll(a.parameterAnnotations()));
        List<ClassDesc> types = method.methodTypeSymbol().parameterList();
        for (int i = 0; i < parameters.size() && i < types.size(); i++) {
            List<String> names = parameters.get(i).stream().map(StartTimeCode::name).toList();
            if (names.stream().anyMatch(declarations.observerAnnotations()::contains)
                    && (declarations.startEvents().contains(dotted(types.get(i)))
                    || names.stream().anyMatch(declarations.startEvents()::contains))) {
                return true;
            }
        }
        return false;
    }

    private List<String> calls(String method) {
        int hash = method.indexOf('#');
        ClassModel model = classes.get(method.substring(0, hash));
        String key = method.substring(hash + 1);
        var targets = new ArrayList<String>();
        model.methods().stream().filter(m -> key(m).equals(key)).findFirst()
                .flatMap(MethodModel::code)
                .ifPresent(code -> code.forEach(element -> {
                    if (element instanceof InvokeInstruction invoke) {
                        targets.addAll(targets(invoke));
                    }
                }));
        return targets;
    }

    private List<String> targets(InvokeInstruction invoke) {
        String owner = invoke.owner().asInternalName().replace('/', '.');
        String key = invoke.name().stringValue() + invoke.type().stringValue();
        List<String> declaring = declaringClasses.getOrDefault(key, List.of());
        return switch (invoke.opcode()) {
            case INVOKEVIRTUAL, INVOKEINTERFACE -> declaring.stream()
                    .filter(c -> supertypes.isA(c, owner) || supertypes.isA(owner, c))
                    .map(c -> c + "#" + key)
                    .toList();
            default -> declaring.contains(owner) ? List.of(owner + "#" + key)
                    : declaring.stream().filter(c -> supertypes.isA(owner, c)).map(c -> c + "#" + key).toList();
        };
    }

    private static Stream<String> annotations(ClassModel model) {
        return Stream.concat(
                model.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                        .flatMap(a -> a.annotations().stream()),
                model.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                        .flatMap(a -> a.annotations().stream()))
                .map(StartTimeCode::name);
    }

    private static Stream<String> annotations(MethodModel method) {
        return Stream.concat(
                method.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                        .flatMap(a -> a.annotations().stream()),
                method.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                        .flatMap(a -> a.annotations().stream()))
                .map(StartTimeCode::name);
    }

    private static String name(Annotation annotation) {
        return dotted(annotation.classSymbol());
    }

    private static String dotted(ClassDesc type) {
        String descriptor = type.descriptorString();
        return descriptor.startsWith("L") ? descriptor.substring(1, descriptor.length() - 1).replace('/', '.')
                : descriptor;
    }

    private static String key(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }
}
