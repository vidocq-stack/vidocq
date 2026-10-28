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
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

/**
 * MP FT 4.1 §9 configuration edge cases: invalid class names, global switches and the
 * interceptor priority.
 */
class ConfigResolverValidationTest {

    private static final String PRIORITY_KEY = "mp.fault.tolerance.interceptor.priority";
    private static final String RETRY_ON = "Retry/retryOn";
    private static final String FALLBACK_VALUE = "Fallback/value";

    private static Method method(String name) throws NoSuchMethodException {
        return Service.class.getDeclaredMethod(name);
    }

    private static RetryConfig retry(TestConfig config) throws NoSuchMethodException {
        Method method = method("retried");
        return ConfigResolver.retryConfig(method, method.getAnnotation(Retry.class), config);
    }

    private static FallbackConfig fallback(TestConfig config) throws NoSuchMethodException {
        Method method = method("withFallback");
        return ConfigResolver.fallbackConfig(method, method.getAnnotation(Fallback.class), config);
    }

    @Test
    void throwableListSkipsBlankEntries() throws Exception {
        TestConfig config = new TestConfig().set(RETRY_ON, " java.io.IOException , ,java.lang.IllegalStateException");

        assertArrayEquals(new Class<?>[]{IOException.class, IllegalStateException.class}, retry(config).retryOn());
    }

    @Test
    void throwableListRejectsANonThrowableClass() {
        TestConfig config = new TestConfig().set(RETRY_ON, "java.lang.String");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> retry(config));

        assertTrue(thrown.getMessage().contains("Not a throwable type"), thrown::getMessage);
    }

    @Test
    void throwableListRejectsAnUnknownClass() {
        TestConfig config = new TestConfig().set(RETRY_ON, "io.example.DoesNotExist");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> retry(config));

        assertTrue(thrown.getMessage().contains("Unknown throwable class"), thrown::getMessage);
    }

    @Test
    void fallbackHandlerCanBeConfigured() throws Exception {
        TestConfig config = new TestConfig().set(FALLBACK_VALUE, " " + OtherHandler.class.getName() + " ");

        assertEquals(OtherHandler.class, fallback(config).fallbackHandlerClass());
    }

    @Test
    void configuredFallbackHandlerMustBeAFallbackHandler() {
        TestConfig config = new TestConfig().set(FALLBACK_VALUE, "java.lang.String");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> fallback(config));

        assertTrue(thrown.getMessage().contains("not a FallbackHandler"), thrown::getMessage);
    }

    @Test
    void configuredFallbackHandlerMustExist() {
        TestConfig config = new TestConfig().set(FALLBACK_VALUE, "io.example.MissingHandler");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> fallback(config));

        assertTrue(thrown.getMessage().contains("Unknown fallback handler class"), thrown::getMessage);
    }

    @Test
    void nonFallbackPoliciesCanBeDisabledWithThePropertyStyleKey() throws Exception {
        TestConfig config = new TestConfig().set("mp.fault.tolerance.nonFallback.enabled", "false");
        Method method = method("retried");

        assertFalse(ConfigResolver.isEnabled(method, "Retry", config));
        assertTrue(ConfigResolver.isEnabled(method, "Fallback", config));
    }

    @Test
    void unknownAnnotationNameIsEnabledByDefault() throws Exception {
        assertTrue(ConfigResolver.isEnabled(method("retried"), "Unknown", new TestConfig()));
    }

    @Test
    void explicitEnabledFlagWinsOverTheGlobalSwitch() throws Exception {
        TestConfig config = new TestConfig()
                .set("MP_Fault_Tolerance_NonFallback_Enabled", "false")
                .set("Retry/enabled", " TRUE ");

        assertTrue(ConfigResolver.isEnabled(method("retried"), "Retry", config));
    }

    @Test
    void metricsAreEnabledWithoutConfig() {
        assertTrue(ConfigResolver.isMetricsEnabled(null));
        assertTrue(ConfigResolver.isMetricsEnabled(new TestConfig()));
        assertFalse(ConfigResolver.isMetricsEnabled(
                new TestConfig().set("mp.fault.tolerance.metrics.enabled", "false")));
    }

    @Test
    void interceptorPriorityComesFromConfigFirst() {
        assertEquals(4321, ConfigResolver.interceptorPriority(new TestConfig().set(PRIORITY_KEY, "4321")));
    }

    @Test
    void interceptorPriorityFallsBackToTheClasspathProperties() {
        // src/test/resources/META-INF/microprofile-config.properties sets 4500
        assertEquals(4500, ConfigResolver.interceptorPriority(new TestConfig()));
        assertEquals(4500, ConfigResolver.interceptorPriority(null));
    }

    @Test
    void interceptorPriorityUsesTheOwnLoaderWithoutAContextLoader() {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(null);
        try {
            assertEquals(4500, ConfigResolver.interceptorPriority(null));
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    @Test
    void interceptorPriorityDefaultsWithoutAnyProperties() {
        Thread current = Thread.currentThread();
        ClassLoader previous = current.getContextClassLoader();
        current.setContextClassLoader(new ClassLoader(null) {
        });
        try {
            assertEquals(ConfigResolver.DEFAULT_INTERCEPTOR_PRIORITY, ConfigResolver.interceptorPriority(null));
        } finally {
            current.setContextClassLoader(previous);
        }
    }

    static class Service {
        @Retry
        void retried() {
            // configuration target only
        }

        @Fallback(DefaultHandler.class)
        String withFallback() {
            return "value";
        }
    }

    public static class DefaultHandler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "default";
        }
    }

    public static class OtherHandler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "other";
        }
    }
}
