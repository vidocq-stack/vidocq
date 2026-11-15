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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.module.FindException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The application layer cannot be created — what the failure says")
class VidocqAppLayerFailureTest {

    /**
     * A JDK module only the application requires cannot be defined in the application layer: the failure names it
     * and the {@code --add-modules} that fixes a hand-written launch (vidocq#254).
     */
    @Test
    @DisplayName("a JDK module missing from the boot layer is named, with --add-modules")
    void aMissingJdkModuleNamesTheFix() {
        var failure = VidocqAppLayer.layerFailure("vidocq.app.path",
                new FindException("Module java.sql not found, required by flow.data"));

        assertTrue(failure.getMessage().contains("--add-modules java.sql"), failure.getMessage());
        assertTrue(failure.getMessage().contains("flow.data"), failure.getMessage());
    }

    /**
     * An encrypted jar without its key fails the layer before any boot phase: the failure says it as a failed boot
     * does, with what to do, instead of leaving a bare stack trace from {@code main} (tutorial, 2026-11-15).
     */
    @Test
    @DisplayName("an encrypted jar without its key fails the start with the message of the key provider")
    void aMissingKeyFailsTheStartWithItsMessage() {
        var failure = VidocqAppLayer.layerFailure("vidocq.app.module",
                new SecurityException("No SJAR key found. Set VAUBAN_SJAR_KEY env var or vauban.sjar.keystore"
                        + " system property."));

        assertEquals("Vidocq startup failed in application layer (vidocq.app.module): No SJAR key found. Set"
                + " VAUBAN_SJAR_KEY env var or vauban.sjar.keystore system property.",
                VidocqAppLayer.failureLine(failure));
        assertTrue(failure.getCause() instanceof SecurityException, failure::toString);
    }

    @Test
    @DisplayName("any other module missing keeps the plain failure")
    void anotherMissingModuleKeepsThePlainFailure() {
        var failure = VidocqAppLayer.layerFailure("vidocq.app.path",
                new FindException("Module org.acme not found, required by flow.data"));

        assertFalse(failure.getMessage().contains("--add-modules"), failure.getMessage());
        assertTrue(failure.getMessage().contains("org.acme"), failure.getMessage());
    }
}
