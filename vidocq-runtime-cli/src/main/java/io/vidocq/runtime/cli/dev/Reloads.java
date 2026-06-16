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
package io.vidocq.runtime.cli.dev;

import java.nio.file.Path;
import java.util.Set;

/**
 * Decides whether a filesystem change should trigger a dev-mode reload.
 *
 * <p>Pure helper: only the file name's extension matters, so it is trivially
 * unit-testable without touching the filesystem.
 */
public final class Reloads {

    /** Extensions whose change is worth a reload (sources, compiled classes, config). */
    private static final Set<String> RELOADABLE = Set.of(
            "java", "class", "properties", "xml", "yml", "yaml");

    private Reloads() {}

    public static boolean isReloadable(Path path) {
        return path != null && isReloadable(path.getFileName().toString());
    }

    public static boolean isReloadable(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        return RELOADABLE.contains(fileName.substring(dot + 1).toLowerCase());
    }
}
