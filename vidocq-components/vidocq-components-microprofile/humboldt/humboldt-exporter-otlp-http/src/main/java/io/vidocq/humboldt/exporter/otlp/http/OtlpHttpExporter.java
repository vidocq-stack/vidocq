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
package io.vidocq.humboldt.exporter.otlp.http;

import io.vidocq.humboldt.exporter.otlp.http.internal.OtlpHttpJsonSender;
import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.common.Releasable;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the three OTLP exporters share: the {@link OtlpHttpJsonSender} they send through, their shutdown, and the
 * release of its connections for a checkpoint ({@link Releasable}).
 */
abstract sealed class OtlpHttpExporter implements Releasable
        permits OtlpHttpSpanExporter, OtlpHttpMetricExporter, OtlpHttpLogExporter {

    final OtlpHttpJsonSender sender;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    OtlpHttpExporter(OtlpHttpJsonSender sender) {
        this.sender = sender;
    }

    /** Whether {@link #shutdown()} was called: an export then fails. */
    final boolean stopped() {
        return stopped.get();
    }

    /** Completes once every request sent so far is answered. */
    public CompletableResultCode flush() {
        return sender.flush();
    }

    /** Refuses the exports that follow, and completes once every request sent so far is answered. */
    public CompletableResultCode shutdown() {
        stopped.set(true);
        return sender.flush();
    }

    /** Lets the connections to the collector go, for a checkpoint: see {@link Releasable}. */
    @Override
    public void release() {
        sender.release();
    }

    @Override
    public void reopen() {
        sender.reopen();
    }
}
