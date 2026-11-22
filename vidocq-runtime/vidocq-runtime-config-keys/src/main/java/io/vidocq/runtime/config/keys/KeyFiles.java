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
package io.vidocq.runtime.config.keys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The files of key descriptions a jar ships, read from the jar file or from a directory of classes, which is
 * what a module of the build being run resolves to.
 *
 * <p>Only a file named {@code *.jar} is opened: a class path also holds what is no jar (a pom, a native
 * library), and that is not something wrong.
 */
public final class KeyFiles {

    private static final KeyCatalog.Jar NOTHING = new KeyCatalog.Jar(KeyDescriptions.none(), KeyDescriptions.none());

    private KeyFiles() {}

    /**
     * The two files a jar file or a directory of classes ships.
     *
     * @param location a jar file or a directory of classes
     * @return its two files; a file it has not is {@link KeyDescriptions#none()}. Never throws on what the
     *         location is or holds: a jar file, or a file of a directory, that cannot be read gives one
     *         problem, on line 0, that names it
     */
    public static KeyCatalog.Jar of(Path location) {
        if (Files.isDirectory(location)) {
            return new KeyCatalog.Jar(file(location.resolve(KeyCatalog.WRITTEN)),
                    file(location.resolve(KeyCatalog.GENERATED)));
        }
        if (!isJarFile(location)) {
            return NOTHING;
        }
        try (ZipFile jar = new ZipFile(location.toFile())) {
            return new KeyCatalog.Jar(entry(jar, location, KeyCatalog.WRITTEN),
                    entry(jar, location, KeyCatalog.GENERATED));
        } catch (IOException e) {
            return new KeyCatalog.Jar(unreadable(location, "cannot be read as a jar", e), KeyDescriptions.none());
        }
    }

    /**
     * Whether a jar file or a directory of classes ships one of the two files.
     *
     * @param location a jar file or a directory of classes
     * @return whether it does; false for anything that cannot be read
     */
    public static boolean present(Path location) {
        if (Files.isDirectory(location)) {
            return Files.isRegularFile(location.resolve(KeyCatalog.WRITTEN))
                    || Files.isRegularFile(location.resolve(KeyCatalog.GENERATED));
        }
        if (!isJarFile(location)) {
            return false;
        }
        try (ZipFile jar = new ZipFile(location.toFile())) {
            return jar.getEntry(KeyCatalog.WRITTEN) != null || jar.getEntry(KeyCatalog.GENERATED) != null;
        } catch (IOException _) {
            return false;
        }
    }

    private static boolean isJarFile(Path location) {
        Path name = location.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".jar")
                && Files.isRegularFile(location);
    }

    private static KeyDescriptions file(Path file) {
        if (!Files.isRegularFile(file)) {
            return KeyDescriptions.none();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return KeyDescriptions.read(file.toString(), in);
        } catch (IOException e) {
            return unreadable(file, "cannot be read", e);
        }
    }

    private static KeyDescriptions entry(ZipFile jar, Path location, String name) throws IOException {
        ZipEntry entry = jar.getEntry(name);
        if (entry == null) {
            return KeyDescriptions.none();
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return KeyDescriptions.read(location.getFileName() + "!/" + name, in);
        }
    }

    private static KeyDescriptions unreadable(Path location, String what, IOException why) {
        String message = why.getMessage() == null ? what : what + ": " + why.getMessage();
        return new KeyDescriptions(location.toString(), List.of(), List.of(),
                List.of(new Problem(location.toString(), 0, Problem.Kind.WRONG, message)));
    }
}
