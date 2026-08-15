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

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/** Builds real (compilable) fixture jars for the modularize tests. */
final class TestJars {
    private TestJars() {}

    /** Compiles {@code source} (a full compilation unit) into {@code classesDir}. */
    static void compileClass(Path classesDir, String fqcn, String source) throws IOException {
        Path src = classesDir.resolveSibling(classesDir.getFileName() + "-src")
                .resolve(fqcn.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Files.createDirectories(classesDir);
        var javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", classesDir.toString(), "--release", "25", src.toString());
        if (rc != 0) throw new IllegalStateException("javac failed for " + fqcn);
    }

    /** Packs {@code classesDir} (+ optional text entries) into a jar with the given manifest attributes. */
    static Path jar(Path out, Path classesDir, Map<String, String> manifestAttrs,
                    Map<String, String> extraTextEntries) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestAttrs.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out); JarOutputStream jos = new JarOutputStream(os, mf)) {
            if (classesDir != null && Files.isDirectory(classesDir)) {
                try (Stream<Path> walk = Files.walk(classesDir)) {
                    for (Path p : walk.filter(Files::isRegularFile).toList()) {
                        jos.putNextEntry(new JarEntry(classesDir.relativize(p).toString().replace('\\', '/')));
                        jos.write(Files.readAllBytes(p));
                        jos.closeEntry();
                    }
                }
            }
            for (var e : extraTextEntries.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue().getBytes());
                jos.closeEntry();
            }
        }
        return out;
    }
}
