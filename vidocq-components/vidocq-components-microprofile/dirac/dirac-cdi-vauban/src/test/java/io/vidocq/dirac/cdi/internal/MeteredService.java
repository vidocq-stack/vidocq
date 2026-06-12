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
package io.vidocq.dirac.cdi.internal;

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Gauge;
import org.eclipse.microprofile.metrics.annotation.Timed;

/**
 * Golden fixture for CG-05: every flavour the startup scan resolves —
 * method-level timer with tags, absolute counter, constructor-level counter,
 * instance gauge, static absolute gauge.
 */
public class MeteredService {

    @Counted
    public MeteredService() {
    }

    @Timed(name = "t1", tags = {"region=eu"}, description = "d1", unit = "milliseconds")
    public void process() {
    }

    @Counted(absolute = true, name = "hits")
    public void hit() {
    }

    @Gauge(unit = "celsius", tags = {"room=lab"})
    public int temperature() {
        return 21;
    }

    @Gauge(unit = "seconds", absolute = true, name = "uptime")
    public static long uptime() {
        return 99L;
    }
}
