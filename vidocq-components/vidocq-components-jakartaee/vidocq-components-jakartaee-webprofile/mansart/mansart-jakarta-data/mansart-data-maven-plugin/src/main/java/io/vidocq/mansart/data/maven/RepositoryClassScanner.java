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
package io.vidocq.mansart.data.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Zero-dependency scanner that reads compiled {@code .class} files on the application's compile
 * classpath with the JDK {@link java.lang.classfile Class-File API} (JEP 484) to discover
 * {@code @jakarta.data.repository.Repository} interfaces, and reads any
 * {@code META-INF/mansart-repositories*.list} resources to know which interfaces already have a
 * generated implementation (so the plugin only generates what is missing).
 */
final class RepositoryClassScanner {

    private static final String REPOSITORY_DESC = "Ljakarta/data/repository/Repository;";
    private static final List<String> INDEX_RESOURCES = List.of(
            "META-INF/mansart-repositories.list",
            "META-INF/mansart-repositories-external.list");

    private RepositoryClassScanner() {}

    /** @return canonical FQNs of every {@code @Repository} interface found across {@code classpath}. */
    static List<String> findRepositoryInterfaces(List<String> classpath) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        for (String element : classpath) {
            Path p = Path.of(element);
            if (!Files.exists(p)) continue;
            if (Files.isDirectory(p)) scanDirectory(p, out);
            else if (element.endsWith(".jar")) scanJar(p, out);
        }
        return new ArrayList<>(out);
    }

    /** @return interface FQNs already mapped in a {@code mansart-repositories*.list} on {@code classpath}. */
    static Set<String> alreadyGenerated(List<String> classpath) throws IOException {
        Set<String> covered = new LinkedHashSet<>();
        for (String element : classpath) {
            Path p = Path.of(element);
            if (!Files.exists(p)) continue;
            if (Files.isDirectory(p)) {
                for (String res : INDEX_RESOURCES) {
                    Path list = p.resolve(res);
                    if (Files.exists(list)) {
                        try (BufferedReader r = Files.newBufferedReader(list, StandardCharsets.UTF_8)) {
                            readIndex(r, covered);
                        }
                    }
                }
            } else if (element.endsWith(".jar")) {
                try (JarFile jar = new JarFile(p.toFile())) {
                    for (String res : INDEX_RESOURCES) {
                        JarEntry e = jar.getJarEntry(res);
                        if (e == null) continue;
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(jar.getInputStream(e), StandardCharsets.UTF_8))) {
                            readIndex(r, covered);
                        }
                    }
                }
            }
        }
        return covered;
    }

    private static void readIndex(BufferedReader r, Set<String> out) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) out.add(line.substring(0, eq).trim());
        }
    }

    private static void scanJar(Path jarPath, Set<String> out) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                if (e.getName().endsWith("module-info.class") || e.getName().endsWith("package-info.class")) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    inspect(in.readAllBytes(), out);
                }
            }
        }
    }

    private static void scanDirectory(Path root, Set<String> out) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                if (!name.endsWith(".class") || name.equals("module-info.class") || name.equals("package-info.class")) {
                    continue;
                }
                inspect(Files.readAllBytes(p), out);
            }
        }
    }

    private static void inspect(byte[] classBytes, Set<String> out) {
        ClassModel cm;
        try {
            cm = ClassFile.of().parse(classBytes);
        } catch (RuntimeException malformed) {
            return; // not a parseable class file — skip
        }
        if (!cm.flags().has(AccessFlag.INTERFACE)) return;
        var attr = cm.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations());
        if (attr.isEmpty()) return;
        for (Annotation a : ((RuntimeVisibleAnnotationsAttribute) attr.get()).annotations()) {
            if (REPOSITORY_DESC.equals(a.className().stringValue())) {
                // Internal name com/acme/Outer$Inner -> canonical com.acme.Outer.Inner
                String internal = cm.thisClass().asInternalName();
                out.add(internal.replace('/', '.').replace('$', '.'));
                return;
            }
        }
    }
}
