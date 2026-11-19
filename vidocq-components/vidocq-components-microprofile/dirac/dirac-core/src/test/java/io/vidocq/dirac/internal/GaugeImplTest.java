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
package io.vidocq.dirac.internal;

import io.vidocq.dirac.api.DiracException;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests M2 — gauge via MethodHandle, spec MicroProfile Metrics 5.1.1 §3.2.
 */
class GaugeImplTest {

    @Test
    void readsInstanceMethodValue() throws Exception {
        var sample = new SampleGaugeSource();
        var lookup = MethodHandles.lookup();
        var handle = lookup.findVirtual(SampleGaugeSource.class, "current", MethodType.methodType(Integer.class));
        var gauge = new GaugeImpl<Integer>(() -> sample, handle);

        assertEquals(1, gauge.getValue());
        sample.setCurrent(9);
        assertEquals(9, gauge.getValue());
    }

    @Test
    void readsStaticMethodValue() throws Exception {
        var lookup = MethodHandles.lookup();
        var handle = lookup.findStatic(SampleGaugeSource.class, "global", MethodType.methodType(Long.class));
        var gauge = new GaugeImpl<Long>(handle);

        assertEquals(7L, gauge.getValue());
    }

    @Test
    void wrapsInvocationFailuresInDiracException() throws Exception {
        var lookup = MethodHandles.lookup();
        var handle = lookup.findVirtual(SampleGaugeSource.class, "current", MethodType.methodType(Integer.class));
        var gauge = new GaugeImpl<Integer>(() -> null, handle);

        assertThrows(DiracException.class, gauge::getValue);
    }

    static final class SampleGaugeSource {
        private int current = 1;

        Integer current() {
            return current;
        }

        void setCurrent(int value) {
            this.current = value;
        }

        static Long global() {
            return 7L;
        }
    }
}

