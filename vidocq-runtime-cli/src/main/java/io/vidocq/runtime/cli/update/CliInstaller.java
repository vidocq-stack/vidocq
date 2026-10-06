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
package io.vidocq.runtime.cli.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a CLI distribution zip as {@code <root>/<version>} — the layout of
 * {@code install.sh} — and retargets the {@code vidocq} launcher it put on the PATH.
 *
 * <p>The zip is unpacked beside the target and swapped in by renaming, so a version that
 * is running (a SNAPSHOT updating itself) is replaced in one step: on Unix the running JVM
 * keeps reading the files it already opened.</p>
 */
public final class CliInstaller {

    /** Records which timestamped SNAPSHOT build a directory holds. */
    static final String BUILD_MARKER = ".vidocq-build";

    private static final Pattern WRAPPER = Pattern.compile("exec \"([^\"]+)/bin/vidocq\" \"\\$@\"");

    private CliInstaller() {}

    /**
     * Installs {@code zip} as {@code root/version}, replacing what was there.
     *
     * @return the installed directory
     * @throws IOException when the archive is unsafe, has no launcher, or cannot be written;
     *                     the previous installation is then left as it was
     */
    public static Path install(Path zip, Path root, String version, Optional<String> build) throws IOException {
        Files.createDirectories(root);
        Path target = root.resolve(version);
        Path staging = Files.createTempDirectory(root, "." + version + ".new-");
        try {
            unzip(zip, staging);
            Path launcher = staging.resolve("bin").resolve("vidocq");
            if (!Files.isRegularFile(launcher)) {
                throw new IOException("Not a Vidocq CLI distribution: no bin/vidocq in " + zip.getFileName());
            }
            launcher.toFile().setExecutable(true, false);
            Path cmd = staging.resolve("bin").resolve("vidocq.cmd");
            if (Files.isRegularFile(cmd)) {
                cmd.toFile().setExecutable(true, false);
            }
            if (build.isPresent()) {
                Files.writeString(staging.resolve(BUILD_MARKER), build.get() + "\n");
            }
            Path previous = null;
            if (Files.exists(target)) {
                previous = root.resolve("." + version + ".old-" + System.nanoTime());
                Files.move(target, previous, StandardCopyOption.ATOMIC_MOVE);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            if (previous != null) {
                deleteRecursively(previous);
            }
            return target;
        } finally {
            if (Files.exists(staging)) {
                deleteRecursively(staging);
            }
        }
    }

    /** The SNAPSHOT build recorded in {@code installed}, empty for a release or an older install. */
    public static Optional<String> installedBuild(Path installed) {
        try {
            Path marker = installed.resolve(BUILD_MARKER);
            return Files.isRegularFile(marker)
                    ? Optional.of(Files.readString(marker).strip()).filter(s -> !s.isEmpty())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The launcher pointed at {@code installed}, when {@code launcher} is the wrapper
     * {@code install.sh} writes ({@code exec "<dir>/bin/vidocq" "$@"}); empty otherwise.
     */
    public static Optional<String> retargetLauncher(String launcher, Path installed) {
        Matcher m = WRAPPER.matcher(launcher);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(launcher.substring(0, m.start(1)) + installed + launcher.substring(m.end(1)));
    }

    private static void unzip(Path zip, Path into) throws IOException {
        Path base = into.toAbsolutePath().normalize();
        try (InputStream in = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path out = base.resolve(entry.getName()).normalize();
                if (!out.startsWith(base) || out.equals(base)) {
                    throw new IOException("Unsafe entry in " + zip.getFileName() + ": " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
