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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadsTest {

    @Test
    void recognisesSourceClassAndConfigExtensions() {
        assertTrue(Reloads.isReloadable("App.java"));
        assertTrue(Reloads.isReloadable("App.class"));
        assertTrue(Reloads.isReloadable("vidocq.properties"));
        assertTrue(Reloads.isReloadable("beans.xml"));
        assertTrue(Reloads.isReloadable("config.yml"));
        assertTrue(Reloads.isReloadable("config.yaml"));
    }

    @Test
    void extensionMatchIsCaseInsensitive() {
        assertTrue(Reloads.isReloadable("App.JAVA"));
        assertTrue(Reloads.isReloadable("Config.PROPERTIES"));
    }

    @Test
    void ignoresUnrelatedFiles() {
        assertFalse(Reloads.isReloadable("README.md"));
        assertFalse(Reloads.isReloadable("notes.txt"));
        assertFalse(Reloads.isReloadable("Makefile"));   // no extension
        assertFalse(Reloads.isReloadable("trailing."));  // empty extension
        assertFalse(Reloads.isReloadable((String) null));
    }

    @Test
    void worksOnPaths() {
        assertTrue(Reloads.isReloadable(Path.of("src", "main", "java", "App.java")));
        assertFalse(Reloads.isReloadable(Path.of("docs", "guide.md")));
    }
}
