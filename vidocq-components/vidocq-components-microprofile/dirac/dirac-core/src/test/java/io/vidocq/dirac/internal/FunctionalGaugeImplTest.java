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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gauge backed by a compile-time generated functional accessor (CG-05) —
 * the no-reflection counterpart of {@link GaugeImpl}.
 */
class FunctionalGaugeImplTest {

    @Test
    void staticGauge_invokesAccessorWithNullBean() {
        var gauge = new FunctionalGaugeImpl<Number>(bean -> {
            assertNull(bean, "static gauges receive no instance");
            return 12L;
        });
        assertEquals(12L, gauge.getValue());
    }

    @Test
    void instanceGauge_resolvesBeanLazilyOnEachRead() {
        var counter = new AtomicInteger(40);
        var gauge = new FunctionalGaugeImpl<Number>(
                counter::incrementAndGet,
                bean -> (Integer) bean);
        assertEquals(41, gauge.getValue());
        assertEquals(42, gauge.getValue(), "supplier must be consulted on every read");
    }

    @Test
    void instanceGauge_nullBeanFails() {
        var gauge = new FunctionalGaugeImpl<Number>(() -> null, bean -> 1);
        assertThrows(DiracException.class, gauge::getValue);
    }

    @Test
    void accessorFailure_isWrappedInDiracException() {
        var gauge = new FunctionalGaugeImpl<Number>(bean -> {
            throw new IllegalStateException("boom");
        });
        var e = assertThrows(DiracException.class, gauge::getValue);
        assertInstanceOf(IllegalStateException.class, e.getCause());
    }
}
