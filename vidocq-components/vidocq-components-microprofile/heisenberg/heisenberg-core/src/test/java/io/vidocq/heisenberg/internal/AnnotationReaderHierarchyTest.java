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

/**
 * MP FT 4.1 §2: the annotations of the bean class hierarchy apply to a method intercepted through a
 * superclass or interface declaration.
 */
class AnnotationReaderHierarchyTest {

    private static final String CALL = "call";

    @Test
    void overridingMethodAnnotationOfTheBeanClassApplies() throws Exception {
        Method declared = Base.class.getDeclaredMethod(CALL);

        AnnotationReader.FaultToleranceAnnotations read = AnnotationReader.read(declared, Overriding.class);

        assertEquals(7, read.retry().maxRetries());
        assertNull(read.timeout());
    }

    @Test
    void inheritedMethodIsLookedUpInTheSuperclasses() throws Exception {
        Method declared = Base.class.getDeclaredMethod(CALL);

        AnnotationReader.FaultToleranceAnnotations read = AnnotationReader.read(declared, Inheriting.class);

        assertNotNull(read.timeout(), "class-level @Timeout of the bean class");
        assertNull(read.retry());
    }

    @Test
    void interfaceMethodWithoutImplementationFallsBackToClassAnnotations() throws Exception {
        Method declared = Contract.class.getDeclaredMethod("contract");

        AnnotationReader.FaultToleranceAnnotations read = AnnotationReader.read(declared, Inheriting.class);

        assertEquals(250L, read.timeout().value());
    }

    @Test
    void noAnnotationAnywhereReadsNothing() throws Exception {
        Method declared = Base.class.getDeclaredMethod(CALL);

        AnnotationReader.FaultToleranceAnnotations read = AnnotationReader.read(declared);

        assertNull(read.retry());
        assertNull(read.timeout());
        assertNull(read.circuitBreaker());
        assertNull(read.bulkhead());
        assertNull(read.fallback());
        assertNull(read.asynchronous());
    }

    interface Contract {
        default String contract() {
            return CALL;
        }
    }

    static class Base {
        String call() {
            return CALL;
        }
    }

    static class Overriding extends Base {
        @Override
        @Retry(maxRetries = 7)
        String call() {
            return "overridden";
        }
    }

    @Timeout(250)
    static class Inheriting extends Base implements Contract {
    }
}
