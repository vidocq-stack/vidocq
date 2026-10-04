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
package io.vidocq.cervantes.cdi;

import io.vidocq.cervantes.cdi.internal.JwtAuthConfigProducer;
import io.vidocq.vauban.core.container.VaubanContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invalid {@code mp.jwt.*} configuration must fail when the container starts, not at the
 * first injection of the {@code @Dependent} validator.
 */
class FailFastStartupTest {

    @AfterEach
    void cleanup() {
        CdiTestSupport.releaseGlobalConfig();
    }

    private static VaubanContainer start() {
        return VaubanContainer.builder()
                .addBeanClass(CervantesClaimExtension.class)
                .addBeanClass(JwtAuthConfigProducer.class)
                .build();
    }

    @Test
    void unrecognisedAlgorithmFailsContainerStartWithTheClearMessage() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic()),
                "mp.jwt.verify.publickey.algorithm", "rs256"));

        RuntimeException ex = assertThrows(RuntimeException.class, FailFastStartupTest::start);

        String message = fullMessage(ex);
        assertTrue(message.contains("mp.jwt.verify.publickey.algorithm"), message);
        assertTrue(message.contains("'rs256'"), message);
        assertTrue(message.contains("RS256") && message.contains("ES512"), message);
    }

    @Test
    void unreadableKeyFailsContainerStart() {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"));

        RuntimeException ex = assertThrows(RuntimeException.class, FailFastStartupTest::start);

        assertTrue(fullMessage(ex).contains("invalid PEM public key"), fullMessage(ex));
    }

    @Test
    void ecKeyWithRsaAlgorithmFailsContainerStart() throws Exception {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.ecPublicKeyPem(),
                "mp.jwt.verify.publickey.algorithm", "RS256"));

        assertThrows(RuntimeException.class, FailFastStartupTest::start);
    }

    @Test
    void noKeyConfiguredStartsSilently() {
        CdiTestSupport.registerGlobalConfig(Map.of("mp.jwt.verify.publickey.algorithm", "rs256"));

        assertDoesNotThrow(() -> start().close());
    }

    @Test
    void validConfigurationStarts() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic()),
                "mp.jwt.verify.publickey.algorithm", "RS256"));

        assertDoesNotThrow(() -> start().close());
    }

    private static String fullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append(" | ");
        }
        return sb.toString();
    }
}
