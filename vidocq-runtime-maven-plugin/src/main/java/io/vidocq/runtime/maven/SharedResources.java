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

import io.vidocq.runtime.maven.depgen.ModuleDescriptorAugmenter;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

/**
 * Resource directories several staged jars hold. To the module system such a directory is a package of each jar, a
 * split package: no descriptor can declare it and {@code jlink} refuses the jars. Vaadin's {@code flow-server} and
 * {@code flow-react} both ship files under {@code com/vaadin/flow/server/frontend/} (vauban#163, vidocq#256).
 *
 * <p>{@code vidocq:jlink} names them before running {@code jlink}, or, with {@code vidocq.jlink.mergeSharedResources},
 * moves their files into one jar of the stage: the one with classes in that package, else the one holding most files
 * there. Only the staged copies change, never {@code target/vauban-modularized} nor the jars {@code vidocq:package}
 * ships, and code never moves: a package with classes in several jars is a split this class does not handle.
 */
final class SharedResources {

    private static final Pattern VERSIONED = Pattern.compile("META-INF/versions/\\d+/");
    private static final String CLASS_SUFFIX = ".class";
    private static final Pattern SIGNATURE = Pattern.compile("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");

    /** A resource directory, as {@code a/b/}, and the file names of the staged jars holding it, both sorted. */
    record Shared(String directory, List<String> jars) {}

    private SharedResources() {}

    /** The resource directories several jars of {@code stage} hold, at most one of them with classes there. */
    static List<Shared> find(Path stage) throws IOException {
        Map<String, Set<String>> holders = new TreeMap<>();
        Map<String, Set<String>> withClasses = new TreeMap<>();
        for (Path jar : jars(stage)) {
            String name = jar.getFileName().toString();
            try (JarFile in = new JarFile(jar.toFile())) {
                for (JarEntry entry : Collections.list(in.entries())) {
                    String dir = directoryOf(entry);
                    if (dir == null) {
                        continue;
                    }
                    holders.computeIfAbsent(dir, d -> new TreeSet<>()).add(name);
                    if (entry.getName().endsWith(CLASS_SUFFIX)) {
                        withClasses.computeIfAbsent(dir, d -> new TreeSet<>()).add(name);
                    }
                }
            }
        }
        List<Shared> shared = new ArrayList<>();
        holders.forEach((dir, jars) -> {
            if (jars.size() > 1 && withClasses.getOrDefault(dir, Set.of()).size() <= 1) {
                shared.add(new Shared(dir, List.copyOf(jars)));
            }
        });
        return shared;
    }

    /** The one-line failure {@code vidocq:jlink} raises without the merge flag. */
    static String failureMessage(List<Shared> shared) {
        List<String> parts = new ArrayList<>();
        for (Shared s : shared) {
            List<String> jars = s.jars();
            String names = jars.size() == 2 ? jars.get(0) + " and " + jars.get(1)
                    : String.join(", ", jars.subList(0, jars.size() - 1)) + " and " + jars.getLast();
            parts.add(names + (jars.size() == 2 ? " both hold " : " all hold ") + s.directory());
        }
        return "vidocq:jlink: " + String.join("; ", parts) + " (a split package, jlink refuses it)."
                + " Set vidocq.jlink.mergeSharedResources=true to move those files into one module of the image,"
                + " or see vidocq#256.";
    }

    /**
     * Moves the files of each shared directory into one jar of {@code stage} and adds the package to that jar's
     * {@code ModulePackages}.
     *
     * @return one line per merged directory, for the build log
     * @throws IOException when two jars hold one path with different contents, or a jar losing the files declares the
     *                     package: no merge keeps everything
     */
    static List<String> merge(Path stage, List<Shared> shared) throws IOException {
        List<String> log = new ArrayList<>();
        for (Shared s : shared) {
            Map<String, Map<String, byte[]>> files = new TreeMap<>();
            String owner = null;
            for (String jar : s.jars()) {
                Map<String, byte[]> own = new TreeMap<>();
                boolean classes = false;
                try (JarFile in = new JarFile(stage.resolve(jar).toFile())) {
                    for (JarEntry entry : Collections.list(in.entries())) {
                        if (s.directory().equals(directoryOf(entry))) {
                            if (entry.getName().endsWith(".class")) {
                                classes = true;
                            } else {
                                try (InputStream is = in.getInputStream(entry)) {
                                    own.put(entry.getName(), is.readAllBytes());
                                }
                            }
                        }
                    }
                }
                files.put(jar, own);
                if (classes) {
                    owner = jar;
                }
            }
            if (owner == null) {
                owner = s.jars().stream()
                        .max((a, b) -> files.get(a).size() != files.get(b).size()
                                ? Integer.compare(files.get(a).size(), files.get(b).size()) : b.compareTo(a))
                        .orElseThrow();
            }
            String pkg = s.directory().substring(0, s.directory().length() - 1).replace('/', '.');
            Map<String, byte[]> moved = new LinkedHashMap<>();
            Map<String, String> from = new TreeMap<>(); // path → the donor its moved copy comes from
            List<String> donors = new ArrayList<>();
            int count = 0;
            for (String jar : s.jars()) {
                if (jar.equals(owner)) {
                    continue;
                }
                if (declares(stage.resolve(jar), pkg)) {
                    throw new IOException("vidocq:jlink: cannot merge " + s.directory() + " into " + owner + ": "
                            + jar + " declares the package " + pkg);
                }
                donors.add(jar);
                for (var e : files.get(jar).entrySet()) {
                    byte[] there = files.get(owner).containsKey(e.getKey()) ? files.get(owner).get(e.getKey())
                            : moved.get(e.getKey());
                    if (there != null && !Arrays.equals(there, e.getValue())) {
                        throw new IOException("vidocq:jlink: cannot merge " + s.directory() + ": " + e.getKey()
                                + " differs between " + jar + " and "
                                + (moved.containsKey(e.getKey()) ? from.get(e.getKey()) : owner));
                    }
                    if (there == null) {
                        moved.put(e.getKey(), e.getValue());
                        from.put(e.getKey(), jar);
                        count++;
                    }
                }
                rewrite(stage.resolve(jar), files.get(jar).keySet(), Map.of(), null);
            }
            rewrite(stage.resolve(owner), Set.of(), moved, pkg);
            log.add("merged " + s.directory() + " into " + owner + " (" + count + (count == 1 ? " file" : " files")
                    + " from " + String.join(", ", donors) + ")");
        }
        return log;
    }

    private static List<Path> jars(Path stage) throws IOException {
        try (var files = Files.list(stage)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        }
    }

    /**
     * The directory {@code entry} sits in, as {@code a/b/}, when jlink counts it as a package: outside
     * {@code META-INF} (a versioned class counts for its base directory), with a legal package name. Else
     * {@code null}.
     */
    private static String directoryOf(JarEntry entry) {
        String name = entry.getName();
        if (entry.isDirectory()) {
            return null;
        }
        var versioned = VERSIONED.matcher(name);
        if (versioned.lookingAt()) {
            if (!name.endsWith(CLASS_SUFFIX)) {
                return null;
            }
            name = name.substring(versioned.end());
        } else if (name.startsWith("META-INF/")) {
            return null;
        }
        if (name.endsWith("module-info.class")) {
            return null;
        }
        int slash = name.lastIndexOf('/');
        if (slash <= 0 || !SourceVersion.isName(name.substring(0, slash).replace('/', '.'))) {
            return null;
        }
        return name.substring(0, slash + 1);
    }

    /** Whether a descriptor of {@code jar} lists {@code pkg} in its {@code ModulePackages}. */
    private static boolean declares(Path jar, String pkg) throws IOException {
        try (JarFile in = new JarFile(jar.toFile())) {
            for (JarEntry entry : Collections.list(in.entries())) {
                if (isDescriptor(entry.getName())) {
                    try (InputStream is = in.getInputStream(entry)) {
                        var listed = ClassFile.of().parse(is.readAllBytes()).findAttribute(Attributes.modulePackages());
                        if (listed.isPresent() && listed.get().packages().stream()
                                .anyMatch(p -> p.name().stringValue().replace('/', '.').equals(pkg))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isDescriptor(String name) {
        return name.equals("module-info.class")
                || (VERSIONED.matcher(name).lookingAt() && name.endsWith("/module-info.class"));
    }

    /**
     * Rewrites {@code jar} without {@code removed}, with {@code added}, and with {@code pkg} added to its descriptors
     * when not {@code null}. Signature files go: the content no longer matches them.
     */
    private static void rewrite(Path jar, Set<String> removed, Map<String, byte[]> added, String pkg)
            throws IOException {
        Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
        try (JarFile in = new JarFile(jar.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (JarEntry entry : Collections.list(in.entries())) {
                String name = entry.getName();
                if (removed.contains(name) || SIGNATURE.matcher(name).matches()) {
                    continue;
                }
                byte[] bytes;
                try (InputStream is = in.getInputStream(entry)) {
                    bytes = is.readAllBytes();
                }
                if (pkg != null && isDescriptor(name)) {
                    bytes = ModuleDescriptorAugmenter.augment(bytes, Map.of(), Set.of(), Set.of(pkg));
                }
                out.putNextEntry(new JarEntry(name));
                out.write(bytes);
                out.closeEntry();
            }
            for (var e : added.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }
}
