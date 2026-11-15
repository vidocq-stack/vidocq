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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The folders below a resource package that need their own {@code opens} (BUG-20261115-06, BUG-20261115-10). */
class ResourcePackagesTest {

    @Test
    void theFoldersHoldingResourcesAreThePackagesBelow(@TempDir Path root) throws IOException {
        file(root, "db/changelog/db.changelog-master.xml");
        file(root, "db/changelog/changes/001-things.sql");
        file(root, "db/changelog/changes/2026/002-more.sql");
        Files.createDirectories(root.resolve("db/changelog/empty"));
        file(root, "db/changelog/code/Generated.class");
        file(root, "db/changelog/sources/Script.java");

        assertEquals(List.of("db.changelog.changes", "db.changelog.changes.2026"),
                ResourcePackages.below(root, "db.changelog"));
    }

    @Test
    void noRootOrNoFolderGivesNone(@TempDir Path root) {
        assertEquals(List.of(), ResourcePackages.below(null, "db.changelog"));
        assertEquals(List.of(), ResourcePackages.below(root, "db.changelog"));
    }

    private static void file(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }
}
