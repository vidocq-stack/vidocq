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
package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.InMemoryExporterBase;
import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;
import java.util.List;

/**
 * Exporter that accumulates spans in memory — for tests/debugging.
 * Delegates the shared skeleton to {@link InMemoryExporterBase}.
 */
public final class InMemorySpanExporter extends InMemoryExporterBase<SpanData> implements SpanExporter {

    public static InMemorySpanExporter create() {
        return new InMemorySpanExporter();
    }

    /** Historical alias for {@link #getCollected()}, kept for test backward compatibility. */
    public List<SpanData> getFinishedSpans() {
        return getCollected();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return addAll(spans);
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
