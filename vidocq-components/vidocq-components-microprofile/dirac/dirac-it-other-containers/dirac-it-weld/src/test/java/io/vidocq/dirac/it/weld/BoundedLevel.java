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
package io.vidocq.dirac.it.weld;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.Dependent;
import org.eclipse.microprofile.metrics.MetricUnits;
import org.eclipse.microprofile.metrics.annotation.Gauge;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bean whose class has a bounded type variable. Such a bean is {@code @Dependent} (CDI 4.1, managed beans), and
 * its raw class is not one of the types it can be looked up by (CDI 4.1 §5.2.4): a gauge on it must be read through
 * the bean itself. It counts its instances, in every container of this JVM.
 *
 * @param <T> the kind of level, unused by the gauge
 */
@Dependent
public class BoundedLevel<T extends Number> {

    static final AtomicInteger CREATED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    public BoundedLevel() {
        CREATED.incrementAndGet();
    }

    @PreDestroy
    void destroyed() {
        DESTROYED.incrementAndGet();
    }

    @Gauge(name = "orders.level", absolute = true, unit = MetricUnits.NONE)
    public long level() {
        return 3;
    }
}
