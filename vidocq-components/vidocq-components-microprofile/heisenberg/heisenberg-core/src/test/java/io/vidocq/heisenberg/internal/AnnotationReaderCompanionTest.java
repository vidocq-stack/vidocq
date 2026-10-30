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

import io.vidocq.heisenberg.spi.gen.FaultToleranceCompanion;
import io.vidocq.heisenberg.spi.gen.Literals;
import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A bean class compiled with the Heisenberg processor has a {@code $$HeisenbergPolicies} companion, and the runtime
 * reads its policies from it instead of reflecting on the annotations (vidocq-workspace#38). The companion below is
 * written by hand with values that differ from the annotations, so that the test tells which source was read.
 */
class AnnotationReaderCompanionTest {

    public static class Shop {
        @Retry(maxRetries = 3)
        public String order(String id) {
            return id;
        }

        public String browse() {
            return "browse";
        }
    }

    /** What the processor would generate for {@link Shop}, with another maxRetries. */
    @SuppressWarnings({"java:S101", "unchecked"})
    public static final class Shop$$HeisenbergPolicies implements FaultToleranceCompanion {
        static final Policies ORDER = new Policies(new Literals.RetryLiteral(99, 0L, ChronoUnit.MILLIS, 180_000L,
                ChronoUnit.MILLIS, 200L, ChronoUnit.MILLIS, new Class[] {Exception.class}, new Class[0]),
                null, null, null, null, null);

        @Override
        public Class<?> beanClass() {
            return Shop.class;
        }

        @Override
        public Policies policies(String key) {
            return "order(java.lang.String)".equals(key) ? ORDER : null;
        }
    }

    public static class Unprocessed {
        @Retry(maxRetries = 4)
        public String call() {
            return "call";
        }
    }

    @Test
    void theCompanionIsReadInsteadOfTheAnnotations() throws Exception {
        Method order = Shop.class.getDeclaredMethod("order", String.class);
        AnnotationReader.FaultToleranceAnnotations read = AnnotationReader.read(order, Shop.class);
        assertSame(Shop$$HeisenbergPolicies.ORDER.retry(), read.retry());
        assertEquals(99, read.retry().maxRetries());
    }

    @Test
    void aMethodTheCompanionDoesNotListHasNoPolicies() throws Exception {
        Method browse = Shop.class.getDeclaredMethod("browse");
        AnnotationReader.FaultToleranceAnnotations read = AnnotationReader.read(browse, Shop.class);
        assertNull(read.retry());
        assertNull(read.timeout());
    }

    @Test
    void aClassWithoutCompanionIsStillReadByReflection() throws Exception {
        Method call = Unprocessed.class.getDeclaredMethod("call");
        assertEquals(4, AnnotationReader.read(call, Unprocessed.class).retry().maxRetries());
    }

    @Test
    void theMethodKeyIsTheErasedSignature() {
        assertEquals("m(java.lang.String,int[],java.util.Map$Entry)",
                FaultToleranceCompanion.methodKey("m", String.class, int[].class, java.util.Map.Entry.class));
    }
}
