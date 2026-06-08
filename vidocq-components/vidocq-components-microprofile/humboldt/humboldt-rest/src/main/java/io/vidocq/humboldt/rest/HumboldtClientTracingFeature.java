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
package io.vidocq.humboldt.rest;

import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * {@link Feature} that registers {@link HumboldtClientRequestFilter} and
 * {@link HumboldtClientResponseFilter} on any JAX-RS {@code Client} — discovered
 * via {@code META-INF/services/jakarta.ws.rs.core.Feature} for auto-instrumentation
 * conformant with MP Telemetry 2.1 §3.2 (the TCK does {@code ClientBuilder.newClient()}
 * without an explicit {@code .register()} and expects CLIENT spans to be set).
 *
 * <p>Convention: returns {@code true} to signal that the Feature has configured
 * itself successfully; the caller (CassiniClientBuilder) currently ignores the
 * return value, but other JAX-RS implementations read it to enable/disable the Feature.</p>
 */
public class HumboldtClientTracingFeature implements Feature {

    @Override
    public boolean configure(FeatureContext context) {
        if (!context.getConfiguration().isRegistered(HumboldtClientRequestFilter.class)) {
            context.register(HumboldtClientRequestFilter.class);
        }
        if (!context.getConfiguration().isRegistered(HumboldtClientResponseFilter.class)) {
            context.register(HumboldtClientResponseFilter.class);
        }
        return true;
    }
}
