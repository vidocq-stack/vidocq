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

import io.vidocq.runtime.maven.depgen.DepgenFixtures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Fixtures of the hot swap tests: classes compiled by the real javac, from source strings. */
final class HotSwapFixtures {

    private HotSwapFixtures() {}

    /** Compiles {@code sources} (relative path → content) and returns the directory of the classes. */
    static Path compile(Path dir, String name, Map<String, String> sources, List<Path> classpath) {
        try {
            DepgenFixtures.jar(dir, name, sources, classpath, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir.resolve(name + "-classes");
    }

    static Path compile(Path dir, String name, Map<String, String> sources) {
        return compile(dir, name, sources, List.of());
    }

    /** The same sources jarred, for a dependency. */
    static Path jar(Path dir, String name, Map<String, String> sources, List<Path> classpath) {
        try {
            return DepgenFixtures.jar(dir, name, sources, classpath, Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The bytes of {@code binaryName} in {@code classes}. */
    static byte[] bytes(Path classes, String binaryName) {
        try {
            return Files.readAllBytes(classes.resolve(binaryName.replace('.', '/') + ".class"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The fingerprint of {@code binaryName}, one source file {@code app/<simple name>.java} compiled alone. */
    static ClassFingerprint fingerprint(Path dir, String name, String binaryName, String source) {
        String path = binaryName.substring(0, binaryName.indexOf('$') < 0 ? binaryName.length()
                : binaryName.indexOf('$')).replace('.', '/') + ".java";
        Path classes = compile(dir, name, Map.of(path, source));
        return ClassFingerprint.of(bytes(classes, binaryName));
    }
}
