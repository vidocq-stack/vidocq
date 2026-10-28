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

import java.net.URI;
import java.time.Duration;

/**
 * Settings shared by the builders of the three OTLP/HTTP exporters (spans, metrics, logs): endpoint, headers,
 * timeouts and retry budget. Invalid values (a {@code null} header, a non-positive timeout, a negative retry count)
 * are ignored and the previous value is kept.
 *
 * <p>Each signal's builder fixes its default endpoint and signal label and builds its exporter from
 * {@link #newSender()}.</p>
 *
 * @param <B> the concrete builder type, returned by every setter so that calls chain
 */
public abstract sealed class OtlpHttpExporterBuilder<B extends OtlpHttpExporterBuilder<B>>
        permits OtlpHttpSpanExporter.Builder, OtlpHttpMetricExporter.Builder, OtlpHttpLogExporter.Builder {

    private final OtlpHttpJsonSender.Builder sender = OtlpHttpJsonSender.builder();

    OtlpHttpExporterBuilder(String defaultEndpoint, String signalLabel) {
        sender.setEndpoint(URI.create(defaultEndpoint)).setSignalLabel(signalLabel);
    }

    /** Returns this builder as its concrete type. */
    abstract B self();

    /** Sets the collector URL the exporter posts to. */
    public B setEndpoint(String url) {
        sender.setEndpoint(URI.create(url));
        return self();
    }

    /** Adds a header sent with every request; a {@code null} name or value is ignored. */
    public B addHeader(String name, String value) {
        sender.addHeader(name, value);
        return self();
    }

    /** Sets the per-request timeout; a {@code null}, zero or negative duration is ignored. */
    public B setRequestTimeout(Duration timeout) {
        sender.setRequestTimeout(timeout);
        return self();
    }

    /** Sets the connection timeout; a {@code null}, zero or negative duration is ignored. */
    public B setConnectTimeout(Duration timeout) {
        sender.setConnectTimeout(timeout);
        return self();
    }

    /** Sets how many times a retryable failure is retried; a negative count is ignored. */
    public B setMaxRetries(int n) {
        sender.setMaxRetries(n);
        return self();
    }

    /** Builds the transport configured by this builder. */
    final OtlpHttpJsonSender newSender() {
        return sender.build();
    }
}
