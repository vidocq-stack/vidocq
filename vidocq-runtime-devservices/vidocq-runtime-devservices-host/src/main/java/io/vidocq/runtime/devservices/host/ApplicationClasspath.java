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
package io.vidocq.runtime.devservices.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * The application's class path as its launch will see it, for
 * {@link io.vidocq.runtime.devservices.spi.DevServiceContext#onApplicationClasspath}: whether it holds a class,
 * looked up as a {@code .class} file in each directory and as an entry of each jar — never loaded (spec
 * 2026-09-29-devservice-postgres-kind §3). Only the answer for each class asked is remembered, never a jar's listing
 * (#166): a jar is opened for the entry looked up and closed at once. A missing path, or a file that is not a
 * readable jar, holds nothing.
 */
public final class ApplicationClasspath {

    private final List<Path> entries;
    private final Map<String, Boolean> answers = new ConcurrentHashMap<>();

    /**
     * @param entries the jars and directories, in class path order
     */
    public ApplicationClasspath(Collection<Path> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Whether a class of this binary name, such as {@code org.postgresql.Driver}, is on the class path.
     *
     * @param className the binary name
     * @return {@code true} when a directory holds its {@code .class} file or a jar its entry
     */
    public boolean contains(String className) {
        return answers.computeIfAbsent(className, this::lookUp);
    }

    private boolean lookUp(String className) {
        String resource = className.replace('.', '/') + ".class";
        for (Path entry : entries) {
            if (Files.isDirectory(entry)) {
                if (Files.isRegularFile(entry.resolve(resource))) {
                    return true;
                }
            } else if (Files.isRegularFile(entry) && jarHolds(entry, resource)) {
                return true;
            }
        }
        return false;
    }

    private static boolean jarHolds(Path jar, String resource) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(resource) != null;
        } catch (IOException e) {
            return false; // not a readable jar: it holds nothing
        }
    }
}
