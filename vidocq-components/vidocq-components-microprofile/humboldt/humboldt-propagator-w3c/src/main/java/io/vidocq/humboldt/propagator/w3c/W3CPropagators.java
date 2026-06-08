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
package io.vidocq.humboldt.propagator.w3c;

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;

/**
 * Canonical W3C TraceContext + Baggage composite used by Humboldt.
 *
 * <p>Equivalent to:</p>
 * <pre>{@code
 * ContextPropagators.create(
 *     TextMapPropagator.composite(
 *         W3CTraceContextPropagator.getInstance(),
 *         W3CBaggagePropagator.getInstance()));
 * }</pre>
 *
 * <p>Spec: <a href="https://www.w3.org/TR/trace-context/">W3C TraceContext</a>
 * and <a href="https://www.w3.org/TR/baggage/">W3C Baggage</a>.</p>
 */
public final class W3CPropagators {

    private W3CPropagators() {}

    /**
     * @return the composite W3C propagators (traceparent + tracestate + baggage).
     */
    public static ContextPropagators get() {
        return Holder.INSTANCE;
    }

    /**
     * @return the underlying composite {@link TextMapPropagator}, useful for
     *         registering it in another {@code ContextPropagators}.
     */
    public static TextMapPropagator textMap() {
        return Holder.TEXT_MAP;
    }

    private static final class Holder {
        static final TextMapPropagator TEXT_MAP = TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(),
                W3CBaggagePropagator.getInstance());
        static final ContextPropagators INSTANCE = ContextPropagators.create(TEXT_MAP);
    }
}
