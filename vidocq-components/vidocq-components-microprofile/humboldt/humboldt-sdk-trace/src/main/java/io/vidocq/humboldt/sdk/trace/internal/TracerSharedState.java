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
package io.vidocq.humboldt.sdk.trace.internal;

import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.trace.export.SpanProcessor;

import java.util.List;

/**
 * What every span of one tracer shares: its resource, instrumentation scope, clock and processors. Built once per
 * {@link SdkTracer}, so that starting a span allocates nothing for it.
 *
 * @param resource   resource of the tracer provider
 * @param scope      instrumentation scope of the tracer
 * @param clock      time source of the span timestamps
 * @param processors processors notified when a span starts and ends
 */
record TracerSharedState(
        Resource resource,
        InstrumentationScope scope,
        Clock clock,
        List<SpanProcessor> processors) {
}
