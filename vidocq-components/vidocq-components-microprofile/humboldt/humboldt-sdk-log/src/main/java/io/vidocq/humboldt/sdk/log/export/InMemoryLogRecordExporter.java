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
package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InMemoryExporterBase;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.util.Collection;

/**
 * Exporter that accumulates LogRecord instances in memory — for tests.
 * Delegates the shared skeleton to {@link InMemoryExporterBase}.
 */
public final class InMemoryLogRecordExporter extends InMemoryExporterBase<LogRecordData> implements LogRecordExporter {

    public static InMemoryLogRecordExporter create() {
        return new InMemoryLogRecordExporter();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
        return addAll(records);
    }

    @Override
    public CompletableResultCode flush() {
        return flushBase();
    }

    @Override
    public CompletableResultCode shutdown() {
        return shutdownBase();
    }
}
