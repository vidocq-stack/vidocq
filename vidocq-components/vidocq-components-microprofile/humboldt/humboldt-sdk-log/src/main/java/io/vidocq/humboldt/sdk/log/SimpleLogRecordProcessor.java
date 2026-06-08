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
package io.vidocq.humboldt.sdk.log;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;
import io.vidocq.humboldt.sdk.log.export.LogRecordExporter;
import io.vidocq.humboldt.sdk.log.export.LogRecordProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous export — each {@code onEmit()} immediately triggers
 * {@code exporter.export([record])}.
 *
 * <p>Suitable for reliable, fast exporters (in-memory, logging stdout).
 * For network exporters, prefer {@link BatchLogRecordProcessor}.</p>
 */
public final class SimpleLogRecordProcessor implements LogRecordProcessor {

    private final LogRecordExporter exporter;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public static SimpleLogRecordProcessor create(LogRecordExporter exporter) {
        return new SimpleLogRecordProcessor(exporter);
    }

    private SimpleLogRecordProcessor(LogRecordExporter exporter) {
        if (exporter == null) throw new NullPointerException("exporter");
        this.exporter = exporter;
    }

    @Override
    public void onEmit(LogRecordData record) {
        if (stopped.get()) return;
        exporter.export(List.of(record));
    }

    @Override
    public CompletableResultCode flush() {
        return exporter.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        if (!stopped.compareAndSet(false, true)) return CompletableResultCode.ofSuccess();
        return exporter.shutdown();
    }
}
