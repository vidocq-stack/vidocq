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
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * What {@code vidocq:jlink} leaves in {@code target/jlink-image/} so that the same image can be linked again on
 * another platform: the jlink arguments that do not depend on the build host, the {@code <module>/<class>} the
 * image starts, and the application's configuration files. With the modules staged in {@code target/jlink-mods/},
 * {@code vidocq:docker} links a Linux image inside a Linux JDK when the build host is macOS or Windows
 * (BUG-20261030-17).
 *
 * <pre>
 *   target/jlink-image/
 *     jlink.args     — an argument file for {@code jlink @jlink.args}, without --module-path nor --output
 *     entry-point    — the {@code <module>/<class>} the image starts
 *     conf/          — the files {@code vidocq:jlink} copies to the image's {@code conf/}
 * </pre>
 */
final class PortableImage {

    /** The recipe's directory, relative to {@code target/}. */
    static final String DIRECTORY = "jlink-image";
    /** The jlink argument file in {@link #DIRECTORY}. */
    static final String ARGUMENTS = "jlink.args";
    /** The file naming the image's entry point in {@link #DIRECTORY}. */
    static final String ENTRY_POINT = "entry-point";
    /** The configuration directory in {@link #DIRECTORY}. */
    static final String CONF = "conf";

    private PortableImage() {
    }

    /**
     * Writes the recipe into {@code buildDir/jlink-image/}, replacing a previous one.
     *
     * @param buildDir   the project's {@code target/}
     * @param arguments  the jlink arguments that do not name a path of the build host
     * @param entryPoint the {@code <module>/<class>} the image starts
     * @param conf       the configuration files to copy into the image's {@code conf/}
     */
    static void write(Path buildDir, List<String> arguments, String entryPoint, List<Path> conf) throws IOException {
        Path dir = buildDir.resolve(DIRECTORY);
        Path confDir = Files.createDirectories(dir.resolve(CONF));
        Files.writeString(dir.resolve(ARGUMENTS), argumentFile(arguments));
        Files.writeString(dir.resolve(ENTRY_POINT), entryPoint);
        for (Path file : conf) {
            Files.copy(file, confDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The entry point of the image {@code vidocq:jlink} linked in {@code buildDir}, when it left a recipe. */
    static Optional<String> entryPoint(Path buildDir) {
        Path file = buildDir.resolve(DIRECTORY).resolve(ENTRY_POINT);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readString(file).strip()) : Optional.empty();
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    /** Whether {@code buildDir} holds a recipe. */
    static boolean exists(Path buildDir) {
        return Files.isRegularFile(buildDir.resolve(DIRECTORY).resolve(ARGUMENTS));
    }

    /** One argument per line, in double quotes when it holds a space, as jlink reads an {@code @file}. */
    static String argumentFile(List<String> arguments) {
        var out = new StringBuilder();
        for (String argument : arguments) {
            out.append(argument.indexOf(' ') < 0 ? argument : '"' + argument + '"').append('\n');
        }
        return out.toString();
    }
}
