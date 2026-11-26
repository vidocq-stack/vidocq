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
package io.vidocq.runtime.extensions.essentials.chappe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code .path} declares a mount: keys of a mount without it used to be ignored without a word, so a
 * mount written with a guessed key ({@code .prefix}) served nothing, and a {@code restful} one also turned
 * the automatic REST mount off.
 */
class ChappeMountConfigExtensionTest {

    @Test
    void aMountWithoutPathFailsTheStartAndNamesTheMissingKey() {
        var config = TestConfig.of(Map.of(
                "vidocq.http.mount.web.type", "static",
                "vidocq.http.mount.web.classpath", "META-INF/resources",
                "vidocq.http.mount.web.prefix", "/"));

        var extension = new ChappeMountConfigExtension();
        var context = new FakeExtensionContext(config);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> extension.onStart(context));

        assertTrue(e.getMessage().contains("vidocq.http.mount.web.path"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.http.mount.web.prefix"), e.getMessage());
    }

    @Test
    void aMountNameWithADotFailsTheStart() {
        var config = TestConfig.of(Map.of(
                "vidocq.http.mount.my.ui.path", "/",
                "vidocq.http.mount.my.ui.type", "static"));

        var extension = new ChappeMountConfigExtension();
        var context = new FakeExtensionContext(config);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> extension.onStart(context));

        assertTrue(e.getMessage().contains("vidocq.http.mount.my.path"), e.getMessage());
    }

    @Test
    void noMountKeyStartsWithNothingMounted() {
        var config = TestConfig.of(Map.of("vidocq.http.port", "0"));

        assertDoesNotThrow(() -> new ChappeMountConfigExtension().onStart(new FakeExtensionContext(config)));
    }

    /** A quote around a placeholder of a log pattern quotes the placeholder itself: the log said {@code '{0}'}. */
    @Test
    void theMountLineOfTheLogNamesTheMount() {
        var config = TestConfig.of(Map.of(
                "vidocq.http.mount.ui.path", "/",
                "vidocq.http.mount.ui.type", "static",
                "vidocq.http.mount.ui.classpath", "shared-a"));
        new ChappeEngineExtension().configure(null);
        try (LogRecords logs = new LogRecords(ChappeMountConfigExtension.class.getName())) {
            new ChappeMountConfigExtension().onStart(new FakeExtensionContext(config));

            List<String> lines = logs.messages(Level.INFO);
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("Mounted 'ui' (type=static)")), lines.toString());
        } finally {
            ChappeMountPoint.uninstall();
        }
    }
}
