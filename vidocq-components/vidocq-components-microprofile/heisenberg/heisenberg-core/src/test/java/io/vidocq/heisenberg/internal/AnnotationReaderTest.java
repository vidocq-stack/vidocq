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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.junit.jupiter.api.Test;

class AnnotationReaderTest {

    @Test
    void usesMethodAnnotationOverClassAnnotation() throws Exception {
        // MP FT 4.1 §2: method > class precedence.
        Method method = MethodAnnotatedService.class.getDeclaredMethod("call");

        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method);

        assertNotNull(annotations.retry());
        assertEquals(1, annotations.retry().maxRetries());
    }

    @Test
    void fallsBackToClassAnnotationWhenMethodHasNone() throws Exception {
        // MP FT 4.1 §2: class annotations apply when the method declares none.
        Method method = ClassAnnotatedService.class.getDeclaredMethod("call");

        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method);

        assertNotNull(annotations.timeout());
        assertEquals(250, annotations.timeout().value());
    }

    @Test
    void returnsNullForPoliciesThatAreNotDeclared() throws Exception {
        Method method = NoAnnotationService.class.getDeclaredMethod("call");

        AnnotationReader.FaultToleranceAnnotations annotations = AnnotationReader.read(method);

        assertNull(annotations.retry());
        assertNull(annotations.timeout());
        assertNull(annotations.circuitBreaker());
        assertNull(annotations.bulkhead());
        assertNull(annotations.fallback());
        assertNull(annotations.asynchronous());
    }

    @Retry(maxRetries = 5)
    static class MethodAnnotatedService {
        @Retry(maxRetries = 1)
        void call() {}
    }

    @Timeout(250)
    static class ClassAnnotatedService {
        void call() {}
    }

    static class NoAnnotationService {
        void call() {}
    }
}

