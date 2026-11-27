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

import java.util.Locale;

/**
 * The OTLP/HTTP encodings the exporters speak, named as {@code otel.exporter.otlp.protocol} names them
 * (OpenTelemetry SDK configuration).
 *
 * <p>OTLP over gRPC ({@code grpc}) is not supported yet: {@link #fromConfigValue(String)} refuses it, rather than
 * exporting with another protocol than the one configured.</p>
 */
public enum OtlpProtocol {

    /** {@code http/json}: OTLP/JSON, {@code Content-Type: application/json}. */
    HTTP_JSON("http/json", "application/json"),

    /** {@code http/protobuf}: the protobuf binary encoding, {@code Content-Type: application/x-protobuf}. */
    HTTP_PROTOBUF("http/protobuf", "application/x-protobuf");

    private final String configValue;
    private final String contentType;

    OtlpProtocol(String configValue, String contentType) {
        this.configValue = configValue;
        this.contentType = contentType;
    }

    /** The value of {@code otel.exporter.otlp.protocol} that selects this protocol. */
    public String configValue() {
        return configValue;
    }

    /** The {@code Content-Type} of the requests. */
    public String contentType() {
        return contentType;
    }

    /**
     * The protocol a value of {@code otel.exporter.otlp.protocol} names, ignoring case and surrounding blanks.
     *
     * @throws IllegalArgumentException for {@code grpc}, not supported yet, and for any other value than
     *                                  {@code http/json} and {@code http/protobuf}
     */
    public static OtlpProtocol fromConfigValue(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (OtlpProtocol protocol : values()) {
            if (protocol.configValue.equals(normalized)) {
                return protocol;
            }
        }
        if (normalized.equals("grpc")) {
            throw new IllegalArgumentException("OTLP protocol 'grpc': OTLP over gRPC is not supported yet."
                    + " Humboldt exports over OTLP/HTTP: set otel.exporter.otlp.protocol to http/protobuf (or"
                    + " http/json) and point otel.exporter.otlp.endpoint at the collector's OTLP/HTTP port,"
                    + " 4318 by default");
        }
        throw new IllegalArgumentException("OTLP protocol '" + value + "' is not supported: use http/protobuf"
                + " or http/json");
    }
}
