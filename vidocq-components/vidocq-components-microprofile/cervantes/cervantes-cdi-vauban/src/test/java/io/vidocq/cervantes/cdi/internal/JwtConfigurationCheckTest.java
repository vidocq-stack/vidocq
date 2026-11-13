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
package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.cdi.CdiTestSupport;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.DeploymentException;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtConfigurationCheck}, the {@code Startup} observer that refuses an invalid
 * {@code mp.jwt.*} configuration (cervantes#52), called directly: what it throws, and when it has
 * nothing to check. {@code FailFastStartupTest} covers the container start.
 */
class JwtConfigurationCheckTest {

    private static final String PREFIX = "invalid MicroProfile JWT configuration (mp.jwt.verify.* / mp.jwt.decrypt.*): ";

    private final JwtConfigurationCheck check = new JwtConfigurationCheck();
    private final Startup startup = new Startup();

    @AfterEach
    void cleanup() {
        CdiTestSupport.releaseGlobalConfig();
    }

    @Test
    void invalidConfigurationThrowsADeploymentExceptionWithTheMessageOfTheValidator() throws Exception {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(CdiTestSupport.rsaKeyPair().getPublic()),
                "mp.jwt.verify.publickey.algorithm", "rs256"));

        DeploymentException ex = assertThrows(DeploymentException.class, () -> check.checkAtStart(startup));

        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(ex.getCause().getMessage(), ex.getMessage());
        assertTrue(ex.getMessage().startsWith(PREFIX), ex.getMessage());
        assertTrue(ex.getMessage().contains("mp.jwt.verify.publickey.algorithm"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'rs256'"), ex.getMessage());
    }

    @Test
    void validConfigurationPasses() throws Exception {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(CdiTestSupport.rsaKeyPair().getPublic()),
                "mp.jwt.verify.publickey.algorithm", "RS256"));

        assertDoesNotThrow(() -> check.checkAtStart(startup));
    }

    /** No verification key: MP-JWT is off, the other {@code mp.jwt.*} properties are not read. */
    @Test
    void noKeyConfiguredPasses() {
        CdiTestSupport.registerGlobalConfig(Map.of("mp.jwt.verify.publickey.algorithm", "rs256"));

        assertDoesNotThrow(() -> check.checkAtStart(startup));
    }

    @Test
    void aConfigImplementationIsAvailableInTheTests() {
        assertTrue(JwtConfigurationCheck.isConfigImplementationAvailable());
    }

    /** No MicroProfile Config implementation at all: nothing can configure a key, nothing to check. */
    @Test
    void noConfigImplementationMeansNothingToCheck() {
        Supplier<ConfigProviderResolver> noResolver = () -> {
            throw new IllegalStateException("No ConfigProviderResolver implementation found!");
        };
        ClassLoader noProvider = new ClassLoader(null) {};

        assertFalse(JwtConfigurationCheck.isConfigImplementationAvailable(noResolver, noProvider));
    }

    /** An implementation is present but does not boot: that failure is reported, not taken for "absent". */
    @Test
    void aConfigImplementationThatFailsToBootIsReported() {
        IllegalStateException bootFailure = new IllegalStateException("the Config implementation failed to boot");
        Supplier<ConfigProviderResolver> failingResolver = () -> {
            throw bootFailure;
        };
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> JwtConfigurationCheck.isConfigImplementationAvailable(failingResolver, loader));

        assertSame(bootFailure, ex);
    }
}
