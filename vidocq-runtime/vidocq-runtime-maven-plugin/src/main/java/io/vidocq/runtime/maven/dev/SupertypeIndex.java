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

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/**
 * Whether a class is, extends or implements a type (design §2.1): through the application's classes first, then
 * through the class files of the dependency jars, read when first needed and kept for the session, then through the
 * JDK's. A supertype found nowhere is warned about once, and makes every class above it {@linkplain #unresolved
 * unresolved}: the classifier then counts the class as long-lived, the safe side.
 */
final class SupertypeIndex {

    private final Map<String, ClassFingerprint> application;
    private final Dependencies dependencies;

    private SupertypeIndex(Map<String, ClassFingerprint> application, Dependencies dependencies) {
        this.application = application;
        this.dependencies = dependencies;
    }

    static SupertypeIndex of(ClassesSnapshot application, List<Path> dependencyJars, Consumer<String> warn) {
        return new SupertypeIndex(application.classes(), new Dependencies(dependencyJars, warn));
    }

    /** The index of a new compiled output, keeping what was read of the jars and the JDK, and what was warned. */
    SupertypeIndex withApplication(ClassesSnapshot application) {
        return new SupertypeIndex(application.classes(), dependencies);
    }

    /** {@code true} when {@code binaryName} is {@code type}, or extends or implements it, directly or not. */
    boolean isA(String binaryName, String type) {
        return walk(binaryName, type) == Walk.FOUND;
    }

    /** {@code true} when a supertype of {@code binaryName}, or the class itself, could be read nowhere. */
    boolean unresolved(String binaryName) {
        return walk(binaryName, null) == Walk.UNRESOLVED;
    }

    private enum Walk { FOUND, NOT_FOUND, UNRESOLVED }

    private Walk walk(String binaryName, String type) {
        var seen = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(binaryName);
        boolean unresolved = false;
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!seen.add(name)) {
                continue;
            }
            if (name.equals(type)) {
                return Walk.FOUND;
            }
            Optional<List<String>> supers = supertypes(name);
            if (supers.isEmpty()) {
                unresolved = true;
            } else {
                queue.addAll(supers.get());
            }
        }
        return unresolved ? Walk.UNRESOLVED : Walk.NOT_FOUND;
    }

    private Optional<List<String>> supertypes(String name) {
        ClassFingerprint own = application.get(name);
        return own != null ? Optional.of(supers(own.superclass(), own.interfaces())) : dependencies.supertypes(name);
    }

    private static List<String> supers(String superclass, List<String> interfaces) {
        var all = new ArrayList<String>();
        if (superclass != null) {
            all.add(superclass);
        }
        all.addAll(interfaces);
        return all;
    }

    /** What the session read of the dependency jars and of the JDK, and what it warned about. */
    private static final class Dependencies {

        private final List<Path> jars;
        private final Consumer<String> warn;
        private final Map<String, Optional<List<String>>> supertypes = new HashMap<>();
        private final Set<String> warned = new HashSet<>();
        private Map<String, Path> jarByEntry;

        Dependencies(List<Path> jars, Consumer<String> warn) {
            this.jars = List.copyOf(jars);
            this.warn = warn;
        }

        Optional<List<String>> supertypes(String name) {
            return supertypes.computeIfAbsent(name, this::read);
        }

        private Optional<List<String>> read(String name) {
            try {
                Optional<byte[]> bytes = fromJars(name).or(() -> fromJdk(name));
                if (bytes.isPresent()) {
                    ClassModel model = ClassFile.of().parse(bytes.get());
                    return Optional.of(supers(model.superclass().map(e -> e.asInternalName().replace('/', '.'))
                                    .orElse(null),
                            model.interfaces().stream().map(e -> e.asInternalName().replace('/', '.')).toList()));
                }
            } catch (IOException | IllegalArgumentException e) {
                warnOnce(name, " (" + e.getMessage() + ")");
                return Optional.empty();
            }
            warnOnce(name, "");
            return Optional.empty();
        }

        private void warnOnce(String name, String why) {
            if (warned.add(name)) {
                warn.accept("Hot swap: the class " + name + " is in no dependency" + why
                        + ": the classes extending it count as start-time");
            }
        }

        private Optional<byte[]> fromJars(String name) throws IOException {
            if (jarByEntry == null) {
                jarByEntry = new HashMap<>();
                for (Path jar : jars) {
                    try (var zip = new ZipFile(jar.toFile())) {
                        zip.stream().forEach(e -> jarByEntry.putIfAbsent(e.getName(), jar));
                    } catch (IOException e) {
                        warn.accept("Hot swap: cannot read " + jar.getFileName() + " (" + e.getMessage() + ")");
                    }
                }
            }
            String entry = name.replace('.', '/') + ".class";
            Path jar = jarByEntry.get(entry);
            if (jar == null) {
                return Optional.empty();
            }
            try (var zip = new ZipFile(jar.toFile()); InputStream in = zip.getInputStream(zip.getEntry(entry))) {
                return Optional.of(in.readAllBytes());
            }
        }

        private static Optional<byte[]> fromJdk(String name) {
            if (!(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk."))) {
                return Optional.empty();
            }
            try (InputStream in = Object.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
                return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
            } catch (IOException _) {
                return Optional.empty();
            }
        }
    }
}
