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
import io.vidocq.cervantes.cdi.internal.JwtConfigurationCheck;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.interceptor.Interceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An invalid {@code mp.jwt.*} configuration must fail when the container starts, not at the
 * first injection of the {@code @Dependent} validator.
 *
 * <p>The check is a {@code Startup} observer, {@link JwtConfigurationCheck}, not a phase of the
 * build compatible extension: a container that runs its extensions when the application builds and
 * not again when it starts (Vidocq/vauban#288) must still refuse an invalid configuration
 * (cervantes#52). The containers built here therefore hold no extension, unless a test says so.</p>
 */
class FailFastStartupTest {

    @AfterEach
    void cleanup() {
        StartupRecorder.NOTIFICATIONS.set(0);
        CdiTestSupport.releaseGlobalConfig();
    }

    /** The beans of the module alone: no build compatible extension runs at start. */
    private static VaubanContainer start() {
        return VaubanContainer.builder()
                .addBeanClass(JwtConfigurationCheck.class)
                .addBeanClass(JwtAuthConfigProducer.class)
                .build();
    }

    /** The beans of the module and the extension, run again at start. */
    private static VaubanContainer startWithExtension() {
        return VaubanContainer.builder()
                .addBeanClass(CervantesClaimExtension.class)
                .addBeanClass(JwtConfigurationCheck.class)
                .addBeanClass(JwtAuthConfigProducer.class)
                .build();
    }

    /** The extension without the check: what the extension itself does with the configuration. */
    private static VaubanContainer startExtensionWithoutCheck() {
        return VaubanContainer.builder()
                .addBeanClass(CervantesClaimExtension.class)
                .addBeanClass(JwtAuthConfigProducer.class)
                .build();
    }

    /** The check next to an observer of the application. */
    private static VaubanContainer startWithRecorder() {
        return VaubanContainer.builder()
                .addBeanClass(JwtConfigurationCheck.class)
                .addBeanClass(JwtAuthConfigProducer.class)
                .addBeanClass(StartupRecorder.class)
                .build();
    }

    private static void registerUnrecognisedAlgorithm() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic()),
                "mp.jwt.verify.publickey.algorithm", "rs256"));
    }

    private static void registerValidConfiguration() throws Exception {
        KeyPair rsa = CdiTestSupport.rsaKeyPair();
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(rsa.getPublic()),
                "mp.jwt.verify.publickey.algorithm", "RS256"));
    }

    @Test
    void unrecognisedAlgorithmFailsContainerStartWithTheClearMessage() throws Exception {
        registerUnrecognisedAlgorithm();

        DeploymentException ex = assertThrows(DeploymentException.class, FailFastStartupTest::start);

        String message = fullMessage(ex);
        assertTrue(message.contains("mp.jwt.verify.publickey.algorithm"), message);
        assertTrue(message.contains("'rs256'"), message);
        assertTrue(message.contains("RS256") && message.contains("ES512"), message);
        // Vauban names the observer that failed; the exception of the check is its cause.
        DeploymentException thrownByTheCheck = assertInstanceOf(DeploymentException.class, ex.getCause());
        assertTrue(thrownByTheCheck.getMessage().startsWith("invalid MicroProfile JWT configuration"),
                thrownByTheCheck.getMessage());
    }

    @Test
    void unrecognisedDecryptAlgorithmFailsContainerStartWithTheClearMessage() throws Exception {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.publicKeyBase64(CdiTestSupport.rsaKeyPair().getPublic()),
                "mp.jwt.decrypt.key.algorithm", "rsa-oaep"));

        DeploymentException ex = assertThrows(DeploymentException.class, FailFastStartupTest::start);

        String message = fullMessage(ex);
        assertTrue(message.contains("mp.jwt.decrypt.key.algorithm"), message);
        assertTrue(message.contains("'rsa-oaep'"), message);
        assertTrue(message.contains("RSA-OAEP, RSA-OAEP-256"), message);
    }

    @Test
    void unreadableKeyFailsContainerStart() {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"));

        DeploymentException ex = assertThrows(DeploymentException.class, FailFastStartupTest::start);

        assertTrue(fullMessage(ex).contains("invalid PEM public key"), fullMessage(ex));
    }

    @Test
    void ecKeyWithRsaAlgorithmFailsContainerStart() throws Exception {
        CdiTestSupport.registerGlobalConfig(Map.of(
                "mp.jwt.verify.publickey", CdiTestSupport.ecPublicKeyPem(),
                "mp.jwt.verify.publickey.algorithm", "RS256"));

        DeploymentException ex = assertThrows(DeploymentException.class, FailFastStartupTest::start);

        assertTrue(fullMessage(ex).contains("not a valid RSA key"), fullMessage(ex));
    }

    @Test
    void invalidConfigurationFailsContainerStartWhenTheExtensionRunsAtStartToo() throws Exception {
        registerUnrecognisedAlgorithm();

        DeploymentException ex =
                assertThrows(DeploymentException.class, FailFastStartupTest::startWithExtension);

        assertTrue(fullMessage(ex).contains("'rs256'"), fullMessage(ex));
    }

    /**
     * The extension reads no configuration, when the application builds or when it starts: the
     * build machine's configuration is not the one the application runs with, and a container may
     * not run the extension at start at all.
     */
    @Test
    void theExtensionDoesNotCheckTheConfiguration() throws Exception {
        registerUnrecognisedAlgorithm();

        assertDoesNotThrow(() -> startExtensionWithoutCheck().close());
    }

    /**
     * The check runs before the observers of libraries and applications (the start of the
     * {@code Interceptor.Priority} platform range), which are not notified once it has failed
     * (CDI 4.1 §9.5: an observer that throws aborts the notification of the remaining ones).
     */
    @Test
    void theObserversOfTheApplicationAreNotNotifiedWhenTheConfigurationIsInvalid() throws Exception {
        registerUnrecognisedAlgorithm();

        assertThrows(DeploymentException.class, FailFastStartupTest::startWithRecorder);

        assertEquals(0, StartupRecorder.NOTIFICATIONS.get(), "the application observer ran before the check");
    }

    @Test
    void theObserversOfTheApplicationAreNotifiedWhenTheConfigurationIsValid() throws Exception {
        registerValidConfiguration();

        assertDoesNotThrow(() -> startWithRecorder().close());

        assertEquals(1, StartupRecorder.NOTIFICATIONS.get());
    }

    @Test
    void noKeyConfiguredStartsSilently() {
        CdiTestSupport.registerGlobalConfig(Map.of("mp.jwt.verify.publickey.algorithm", "rs256"));

        assertDoesNotThrow(() -> start().close());
    }

    @Test
    void validConfigurationStarts() throws Exception {
        registerValidConfiguration();

        assertDoesNotThrow(() -> start().close());
    }

    /** An observer as early as a library may declare one: later than the check, earlier than an application's. */
    @Dependent
    public static class StartupRecorder {

        static final AtomicInteger NOTIFICATIONS = new AtomicInteger();

        public void onStart(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) Startup event) {
            NOTIFICATIONS.incrementAndGet();
        }
    }

    private static String fullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append(" | ");
        }
        return sb.toString();
    }
}
