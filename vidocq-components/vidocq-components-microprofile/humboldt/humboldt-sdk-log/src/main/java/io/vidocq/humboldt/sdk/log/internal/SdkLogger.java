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
package io.vidocq.humboldt.sdk.log.internal;

import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;
import io.vidocq.humboldt.sdk.common.Clock;
import io.vidocq.humboldt.sdk.common.InstrumentationScope;
import io.vidocq.humboldt.sdk.common.Resource;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.util.List;

/**
 * Humboldt logger — {@link Logger} facade that produces {@link SdkLogRecordBuilder} instances.
 */
public final class SdkLogger implements Logger {

    private final InstrumentationScope scope;
    private final Resource resource;
    private final Clock clock;
    private final List<LogRecordProcessor> processors;

    public SdkLogger(
            InstrumentationScope scope, Resource resource, Clock clock,
            List<LogRecordProcessor> processors) {
        this.scope = scope;
        this.resource = resource;
        this.clock = clock;
        this.processors = processors;
    }

    @Override
    public LogRecordBuilder logRecordBuilder() {
        return new SdkLogRecordBuilder(resource, scope, clock, processors);
    }
}
