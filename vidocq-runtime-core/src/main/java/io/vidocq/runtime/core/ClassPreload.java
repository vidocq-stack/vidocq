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
package io.vidocq.runtime.core;

import io.vidocq.vauban.classloader.VaubanClassLoader;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * The classes of the application's directories, loaded as soon as the dev loop installs an application layer
 * (BUG-20261117-09).
 *
 * <p>Under {@code vidocq:dev} the application layer reads its classes from the output of the build, each one when it
 * is first used. The compiler plugin removes from that output the classes it created before it compiles the module
 * again, and leaves them out while the sources do not compile. A class the application did not load before that is
 * missing when a request needs it: the request fails with a {@code NoClassDefFoundError}, and keeps failing once the
 * class is back, as the JVM records a resolution that failed (JVMS 5.4.3). With every class loaded when the layer
 * starts, nothing is left to read from the output later.
 *
 * <p>Loading is not initializing: no static initializer runs. Nor is it linking: a class the JVM cannot link is
 * loaded all the same, and the JVM refuses to redefine it later, which swaps the layer. A class that cannot be
 * loaded at that time, as one whose supertype comes from an optional dependency that is absent, or the placed client
 * proxy of a type the layer does not define, is left to its first use, as before. The jars of the application path
 * are left alone: no build writes them while the application runs.
 */
final class ClassPreload {

    /**
     * {@code false} leaves each class to its first use; {@code vidocq:dev} sets it for
     * {@code -Dvidocq.dev.preloadClasses=false}.
     */
    static final String PROPERTY = "vidocq.dev.preloadClasses";

    private static final String CLASS_SUFFIX = ".class";
    private static final String MODULE_INFO = "module-info.class";
    private static final Path META_INF = Path.of("META-INF");
    /** Where the build leaves the client proxies the loader defines into another archive's package. */
    private static final Path PLACED = Path.of(VaubanClassLoader.PLACED_PREFIX);
    /** How many classes left to their first use the line names. */
    private static final int NAMED = 5;

    /**
     * @param loaded how many classes are loaded
     * @param missed the binary names of those that could not be, which are left to their first use
     */
    record Outcome(int loaded, List<String> missed) {

        Outcome {
            missed = List.copyOf(missed);
        }

        /** The line the dev loop logs at DEBUG. */
        String line() {
            String line = "Application layer: " + loaded + (loaded == 1 ? " class" : " classes")
                    + " of the application's directories loaded ahead of " + (loaded == 1 ? "its" : "their")
                    + " first use";
            if (missed.isEmpty()) {
                return line;
            }
            String names = missed.stream().limit(NAMED).collect(Collectors.joining(", "));
            return line + "; " + missed.size() + " could not be loaded now and "
                    + (missed.size() == 1 ? "is left to its" : "are left to their") + " first use: " + names
                    + (missed.size() > NAMED ? " and " + (missed.size() - NAMED) + " more" : "");
        }
    }

    private ClassPreload() {
    }

    /** Whether {@code property}, the value of {@link #PROPERTY}, leaves the loading on: anything but {@code false}. */
    static boolean on(String property) {
        return property == null || !"false".equalsIgnoreCase(property.strip());
    }

    /**
     * Loads, without initializing them, the classes of the directories of {@code appPath} through {@code loader}.
     * Never fails: a class that cannot be loaded is named in the outcome.
     */
    static Outcome load(List<Path> appPath, ClassLoader loader) {
        int loaded = 0;
        List<String> missed = new ArrayList<>();
        for (Path archive : appPath) {
            for (String name : classNames(archive)) {
                try {
                    Class.forName(name, false, loader);
                    loaded++;
                } catch (ClassNotFoundException | LinkageError | RuntimeException _) {
                    missed.add(name);
                }
            }
        }
        return new Outcome(loaded, missed);
    }

    /**
     * The binary names of the classes {@code directory} holds, sorted, then those of its placed client proxies. The
     * module descriptor and the rest of {@code META-INF} are not classes of the application; a jar, or a directory
     * that is not there, holds nothing to load.
     */
    static List<String> classNames(Path directory) {
        List<String> own = new ArrayList<>();
        List<String> placed = new ArrayList<>();
        collect(directory, directory, own, placed);
        Collections.sort(own);
        Collections.sort(placed);
        own.addAll(placed);
        return own;
    }

    private static void collect(Path root, Path directory, List<String> own, List<String> placed) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    collect(root, entry, own, placed);
                } else {
                    name(root.relativize(entry), own, placed);
                }
            }
        } catch (IOException | DirectoryIteratorException _) {
            // Not a directory, or one removed while it was listed, as by a recompile that runs: what it holds is
            // left to its first use.
        }
    }

    private static void name(Path file, List<String> own, List<String> placed) {
        String fileName = file.getFileName().toString();
        if (!fileName.endsWith(CLASS_SUFFIX) || fileName.equals(MODULE_INFO)) {
            return;
        }
        if (file.startsWith(PLACED)) {
            placed.add(binaryName(PLACED.relativize(file)));
        } else if (!file.startsWith(META_INF)) {
            own.add(binaryName(file));
        }
    }

    private static String binaryName(Path classFile) {
        String name = StreamSupport.stream(classFile.spliterator(), false).map(Path::toString)
                .collect(Collectors.joining("."));
        return name.substring(0, name.length() - CLASS_SUFFIX.length());
    }
}
