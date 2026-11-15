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
package io.vidocq.runtime.tooling;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The packages below a resource package an extension reads, such as the folder of Liquibase's
 * {@code includeAll path="db/changelog/changes/"} below {@code db.changelog}: the tool reads them as it reads the
 * package, and an {@code opens} is never inherited by a sub-package (BUG-20261115-06). The build check
 * ({@code vidocq:check-module-info}, on the compiled output) and {@code vidocq doctor} (on {@code src/main/resources})
 * ask for the same ones (BUG-20261115-10).
 */
public final class ResourcePackages {

    private ResourcePackages() {
    }

    /**
     * The packages below {@code pkg} under {@code root} that hold resources and no code: a folder with no file is no
     * package, and one with a class or a source file is code, left to the checks that read the classes. Sorted; empty
     * when {@code root} is {@code null} or the folder is missing or unreadable.
     */
    public static List<String> below(Path root, String pkg) {
        if (root == null) {
            return List.of();
        }
        Path top = root.resolve(pkg.replace('.', '/'));
        if (!Files.isDirectory(top)) {
            return List.of();
        }
        List<String> packages = new ArrayList<>();
        try (var dirs = Files.walk(top)) {
            for (Path dir : dirs.filter(Files::isDirectory).filter(d -> !d.equals(top)).sorted().toList()) {
                if (holdsResourcesOnly(dir)) {
                    packages.add(root.relativize(dir).toString().replace(File.separatorChar, '.'));
                }
            }
        } catch (IOException _) {
            // An unreadable folder adds no requirement: the package itself is still checked.
        }
        return List.copyOf(packages);
    }

    /** Whether {@code dir} holds at least one file and no class or source file. */
    private static boolean holdsResourcesOnly(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            List<String> files = entries.filter(Files::isRegularFile).map(f -> f.getFileName().toString()).toList();
            return !files.isEmpty() && files.stream().noneMatch(f -> f.endsWith(".class") || f.endsWith(".java"));
        }
    }
}
