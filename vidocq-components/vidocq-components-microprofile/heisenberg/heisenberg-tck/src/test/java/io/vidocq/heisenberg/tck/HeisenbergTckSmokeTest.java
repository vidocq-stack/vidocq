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
package io.vidocq.heisenberg.tck;

import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Bulkhead;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Asynchronous;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test M0: verifies that the MicroProfile Fault Tolerance 4.1 spec is indeed
 * on the classpath and that the main annotations are accessible.
 * Executed by the "smoke" profile (active by default) without Arquillian.
 */
class HeisenbergTckSmokeTest {

    @Test
    void mpFaultToleranceApiOnClasspath() {
        // §2 — the six annotations must be accessible
        assertNotNull(Retry.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Retry must be a RUNTIME-retained annotation");
        assertNotNull(Timeout.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Timeout must be a RUNTIME-retained annotation");
        assertNotNull(CircuitBreaker.class.getAnnotation(java.lang.annotation.Retention.class),
                "@CircuitBreaker must be a RUNTIME-retained annotation");
        assertNotNull(Bulkhead.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Bulkhead must be a RUNTIME-retained annotation");
        assertNotNull(Fallback.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Fallback must be a RUNTIME-retained annotation");
        assertNotNull(Asynchronous.class.getAnnotation(java.lang.annotation.Retention.class),
                "@Asynchronous must be a RUNTIME-retained annotation");
    }
}
