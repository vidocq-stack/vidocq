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
package io.vidocq.cervantes.it.weld;

import jakarta.enterprise.inject.spi.DeploymentException;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invalid {@code mp.jwt.*} configuration stops Weld SE as it stops Vauban (cervantes#52): the
 * check is a {@code Startup} observer of the Cervantes bean archive, so it needs nothing from the
 * container but the CDI 4.0 {@code Startup} event. Weld SE lets the observer's
 * {@code DeploymentException} out of {@code initialize()} as it is.
 */
class WeldFailFastStartupTest {

    private static final String CONTAINER_ID = "cervantes-invalid-configuration";

    @AfterEach
    void cleanup() {
        System.clearProperty("mp.jwt.verify.publickey");
        System.clearProperty("mp.jwt.verify.publickey.algorithm");
        WeldContainer leftover = WeldContainer.instance(CONTAINER_ID);
        if (leftover != null && leftover.isRunning()) {
            leftover.shutdown();
        }
    }

    @Test
    void unrecognisedAlgorithmStopsTheStartWithTheClearMessage() {
        System.setProperty("mp.jwt.verify.publickey", Tokens.publicKey(Tokens.newKeyPair()));
        System.setProperty("mp.jwt.verify.publickey.algorithm", "rs256");
        Weld weld = new Weld(CONTAINER_ID);

        DeploymentException ex = assertThrows(DeploymentException.class, weld::initialize);

        String message = ex.getMessage();
        assertTrue(message.startsWith("invalid MicroProfile JWT configuration"), message);
        assertTrue(message.contains("mp.jwt.verify.publickey.algorithm"), message);
        assertTrue(message.contains("'rs256'"), message);
    }

    @Test
    void unreadableKeyStopsTheStart() {
        System.setProperty("mp.jwt.verify.publickey", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----");
        Weld weld = new Weld(CONTAINER_ID);

        DeploymentException ex = assertThrows(DeploymentException.class, weld::initialize);

        assertTrue(ex.getMessage().contains("invalid PEM public key"), ex.getMessage());
    }
}
