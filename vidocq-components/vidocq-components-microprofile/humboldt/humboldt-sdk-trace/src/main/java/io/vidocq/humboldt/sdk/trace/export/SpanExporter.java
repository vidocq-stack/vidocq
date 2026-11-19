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

import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;

/**
 * Exporter of completed spans to a destination (in-memory for tests,
 * stdout for development, OTLP HTTP/protobuf for production in M3, etc.).
 *
 * <p>All methods must be thread-safe — the same exporter may be shared
 * between {@code SimpleSpanProcessor} and {@code BatchSpanProcessor}.</p>
 */
public interface SpanExporter extends AutoCloseable {

    /**
     * Exports a batch of completed spans.
     *
     * @param spans immutable collection
     * @return asynchronous result — successful if all spans were handled
     */
    CompletableResultCode export(Collection<SpanData> spans);

    /**
     * Forces a flush of any internal buffers.
     */
    CompletableResultCode flush();

    /**
     * Releases resources (sockets, threads, files, etc.).
     */
    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
