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
package io.vidocq.runtime.maven.modularize;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Works out which jar of a dependency closure must declare which {@code uses} directives, by
 * locating the {@code ServiceLoader} lookups in the bytecode with the JDK Class-File API
 * (JEP 484).
 *
 * <p>Needed because {@code jdeps} does not report service uses, and ModiTect's own scanner runs
 * on a shaded ASM that refuses class files newer than its own release.
 *
 * <h2>Which module needs the directive</h2>
 *
 * {@code ServiceLoader.load} checks {@code Reflection.getCallerClass()} — the module that must
 * declare {@code uses} is the one holding the class that <em>calls</em> {@code ServiceLoader},
 * not the one that names the service. For the direct shape,
 * {@code ServiceLoader.load(Foo.class)}, they are the same class. For the indirect shape every
 * library eventually grows — a helper taking the service type as a {@code Class} parameter and
 * forwarding it — they are not, and may not even be the same jar: langchain4j names
 * {@code AiServiceContextFactory} in {@code langchain4j} but reaches {@code ServiceLoader}
 * through {@code ServiceHelper} in {@code langchain4j-core}, and it is
 * {@code langchain4j-core} that fails without the directive.
 *
 * <p>So the scanner works over the whole closure at once: it finds the forwarding helpers by
 * fixed point over the closure's call graph (any depth of forwarding), then attributes each
 * service both to the jar that names it and to every jar on the forwarding chain down to
 * {@code ServiceLoader}.
 *
 * <h2>Deliberate over-approximation</h2>
 *
 * The service type is taken to be the last class constant pushed before the lookup call, which
 * can pick up an unrelated constant. That is the right way to be wrong here — a superfluous
 * {@code uses} is inert (nothing ever resolves it), a missing one turns the jar's own lookup into
 * a {@code ServiceConfigurationError}. A service type held in a variable rather than written as a
 * class literal cannot be seen at all; no bytecode scanner can, ModiTect included.
 */
final class ServiceUsesScanner {

    /** Owner of the lookups we seed the call graph with, in internal form. */
    private static final String SERVICE_LOADER = "java/util/ServiceLoader";

    /** The parameter type that makes a method a candidate for forwarding a service type. */
    private static final ClassDesc CLASS = ClassDesc.of("java.lang.Class");

    /** Identity of a method across the closure: owner, name and descriptor. */
    private record MethodKey(String owner, String name, String descriptor) {}

    /** Service binary names per jar, ready to be turned into {@code uses} directives. */
    private final Map<Path, Set<String>> servicesByJar;

    private ServiceUsesScanner(Map<Path, Set<String>> servicesByJar) {
        this.servicesByJar = servicesByJar;
    }

    /**
     * Analyses {@code closure} and returns a scanner able to answer for each of its jars.
     *
     * @param closure every jar of the dependency closure — a helper is regularly in another jar
     *                than the code naming the service, so a per-jar analysis sees neither whole
     * @throws IOException if a jar cannot be opened
     */
    static ServiceUsesScanner over(Iterable<Path> closure) throws IOException {
        // Pass 1: the call graph, restricted to the methods that could forward a service type.
        Map<MethodKey, Set<MethodKey>> callees = new HashMap<>();
        Map<String, Path> jarOfClass = new HashMap<>();
        for (Path jar : closure) {
            forEachClass(jar, model -> {
                String owner = model.thisClass().asInternalName();
                jarOfClass.put(owner, jar);
                for (MethodModel method : model.methods()) {
                    MethodTypeDesc type = method.methodTypeSymbol();
                    // Without a Class parameter a method has no service type to forward; whatever
                    // it looks up is already caught where the class constant is written.
                    if (!type.parameterList().contains(CLASS)) {
                        continue;
                    }
                    Set<MethodKey> invoked = new HashSet<>();
                    eachInstruction(method, element -> {
                        if (element instanceof InvokeInstruction invoke) {
                            invoked.add(keyOf(invoke));
                        }
                    });
                    callees.put(new MethodKey(owner, method.methodName().stringValue(),
                            type.descriptorString()), invoked);
                }
            });
        }

        // Pass 2: fixed point — a helper calling a helper is the common case
        // (ServiceHelper.loadFactory → loadFactories → loadFactories(Class, ClassLoader)).
        Set<MethodKey> lookups = new HashSet<>();
        boolean growing = true;
        while (growing) {
            growing = false;
            for (Map.Entry<MethodKey, Set<MethodKey>> entry : callees.entrySet()) {
                if (lookups.contains(entry.getKey())) {
                    continue;
                }
                for (MethodKey callee : entry.getValue()) {
                    if (isServiceLoaderLookup(callee) || lookups.contains(callee)) {
                        lookups.add(entry.getKey());
                        growing = true;
                        break;
                    }
                }
            }
        }

        // Pass 3: harvest the class constants and spread each service along its forwarding chain.
        Map<Path, Set<String>> servicesByJar = new HashMap<>();
        Map<MethodKey, Set<String>> chainCache = new HashMap<>();
        for (Path jar : closure) {
            forEachClass(jar, model -> harvest(model, jar, callees, lookups, jarOfClass, chainCache,
                    servicesByJar));
        }
        return new ServiceUsesScanner(servicesByJar);
    }

    /**
     * The services {@code jar} must declare.
     *
     * @return their binary names, sorted so two runs on the same closure generate byte-identical
     *         descriptors
     */
    Set<String> scan(Path jar) {
        return new TreeSet<>(servicesByJar.getOrDefault(jar, Set.of()));
    }

    /** Harvests the lookups of one class and records them against every jar that needs them. */
    private static void harvest(ClassModel model, Path jar, Map<MethodKey, Set<MethodKey>> callees,
                                Set<MethodKey> lookups, Map<String, Path> jarOfClass,
                                Map<MethodKey, Set<String>> chainCache,
                                Map<Path, Set<String>> servicesByJar) {
        for (MethodModel method : model.methods()) {
            // `ServiceLoader.load(Foo.class)` compiles to `ldc Foo.class` then `invokestatic`,
            // with any ClassLoader or ModuleLayer argument loaded around it.
            ClassDesc[] pending = new ClassDesc[1];
            eachInstruction(method, element -> {
                if (element instanceof ConstantInstruction ci && ci.constantValue() instanceof ClassDesc cd) {
                    pending[0] = cd;
                } else if (element instanceof InvokeInstruction invoke) {
                    MethodKey key = keyOf(invoke);
                    boolean direct = isServiceLoaderLookup(key);
                    if (!direct && !lookups.contains(key)) {
                        return;
                    }
                    if (pending[0] != null && pending[0].isClassOrInterface()) {
                        String service = binaryNameOf(pending[0]);
                        // The naming jar: correct on its own for the direct shape, and harmless
                        // for the indirect one.
                        servicesByJar.computeIfAbsent(jar, j -> new HashSet<>()).add(service);
                        // The forwarding chain: those are the modules ServiceLoader will see as
                        // the caller, so those are the ones that would fail without the directive.
                        if (!direct) {
                            for (String owner : chainOwners(key, callees, lookups, chainCache)) {
                                Path helperJar = jarOfClass.get(owner);
                                if (helperJar != null) {
                                    servicesByJar.computeIfAbsent(helperJar, j -> new HashSet<>()).add(service);
                                }
                            }
                        }
                    }
                    pending[0] = null;
                }
            });
        }
    }

    /**
     * The classes on the forwarding chain from {@code entry} down to {@code ServiceLoader}: every
     * one of them may be the caller {@code ServiceLoader} sees.
     */
    private static Set<String> chainOwners(MethodKey entry, Map<MethodKey, Set<MethodKey>> callees,
                                           Set<MethodKey> lookups, Map<MethodKey, Set<String>> cache) {
        Set<String> cached = cache.get(entry);
        if (cached != null) {
            return cached;
        }
        Set<String> owners = new HashSet<>();
        Set<MethodKey> seen = new HashSet<>();
        Deque<MethodKey> queue = new ArrayDeque<>();
        queue.add(entry);
        seen.add(entry);
        while (!queue.isEmpty()) {
            MethodKey current = queue.poll();
            owners.add(current.owner());
            for (MethodKey callee : callees.getOrDefault(current, Set.of())) {
                if (lookups.contains(callee) && seen.add(callee)) {
                    queue.add(callee);
                }
            }
        }
        cache.put(entry, owners);
        return owners;
    }

    /** Whether {@code key} is one of the static {@code ServiceLoader} lookup factories. */
    private static boolean isServiceLoaderLookup(MethodKey key) {
        return SERVICE_LOADER.equals(key.owner())
                && ("load".equals(key.name()) || "loadInstalled".equals(key.name()));
    }

    private static MethodKey keyOf(InvokeInstruction invoke) {
        return new MethodKey(invoke.owner().asInternalName(), invoke.name().stringValue(),
                invoke.type().stringValue());
    }

    /** Feeds every instruction of {@code method}'s body to {@code visitor}, if it has one. */
    private static void eachInstruction(MethodModel method, Consumer<CodeElement> visitor) {
        Optional<CodeModel> code = method.code();
        if (code.isPresent()) {
            for (CodeElement element : code.get()) {
                visitor.accept(element);
            }
        }
    }

    /**
     * Parses every class of {@code jar} and hands it to {@code visitor}. A class that cannot be
     * parsed is skipped rather than fatal: a missing {@code uses} costs one runtime code path,
     * an aborted build costs the whole modularization.
     */
    private static void forEachClass(Path jar, Consumer<ClassModel> visitor) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry entry : jf.stream().toList()) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.endsWith("module-info.class")) {
                    continue;
                }
                ClassModel model;
                try (InputStream in = jf.getInputStream(entry)) {
                    model = ClassFile.of().parse(in.readAllBytes());
                } catch (RuntimeException | IOException e) {
                    continue;
                }
                try {
                    visitor.accept(model);
                } catch (RuntimeException e) {
                    // Same reasoning: one unreadable member must not cost the jar its descriptor.
                }
            }
        }
    }

    /** {@code Lcom/acme/Foo;} → {@code com.acme.Foo} (nested types keep their {@code $}). */
    private static String binaryNameOf(ClassDesc desc) {
        String d = desc.descriptorString();
        return d.substring(1, d.length() - 1).replace('/', '.');
    }
}
