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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The encrypted jars (sjar) among the modules a jlink image would link (Vidocq/vidocq#580).
 *
 * <p>An encrypted jar keeps its internal classes and resources under opaque names, and only Vauban's class loader
 * decrypts them, as it defines the application layer. An image has one layer, the JVM's own, whose loader would
 * find none of those classes: the build refuses the jar, and names the distribution that runs it.
 */
final class EncryptedJars {

    /** The clear entry every encrypted jar carries, which names its key ({@code SjarHeader.HEADER_ENTRY}). */
    static final String HEADER_ENTRY = "META-INF/vauban.header";

    private EncryptedJars() {}

    /** The file names of the encrypted jars directly under {@code stage}, sorted. */
    static List<String> find(Path stage) throws IOException {
        List<Path> jars;
        try (Stream<Path> files = Files.list(stage)) {
            jars = files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".jar"))
                    .sorted().toList();
        }
        List<String> encrypted = new ArrayList<>();
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                if (file.getEntry(HEADER_ENTRY) != null) {
                    encrypted.add(jar.getFileName().toString());
                }
            }
        }
        return encrypted;
    }

    /** The failure of an image build that would link {@code jars}. */
    static String failureMessage(List<String> jars) {
        return "An encrypted jar cannot go into a jlink image: " + String.join(", ", jars) + ". Only Vauban's class"
                + " loader decrypts it, in the application layer, and an image has one layer, the JVM's own. Ship"
                + " this application as a vidocq:package distribution, which keeps the application layer (for a"
                + " container: vidocq:docker with -Dvidocq.docker.kind=jdk).";
    }
}
