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
package io.vidocq.chappe.grpc.client;

import java.util.Optional;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;

/**
 * The outcome of a unary gRPC call: a status code from {@link GrpcStatus}, its message, and on success the one
 * response message.
 * <p>
 * Every outcome is a response, a failure of the transport included: a server that cannot be reached or a
 * connection that drops is {@link GrpcStatus#UNAVAILABLE}, an expired deadline {@link GrpcStatus#DEADLINE_EXCEEDED},
 * as gRPC clients report them; {@link #cause()} then holds the exception, when there is one. So a caller can decide
 * on the code alone, for instance to retry what OTLP calls retryable.
 */
public final class GrpcResponse {

    private final int status;
    private final String statusMessage;
    private final byte[] message;
    private final Headers headers;
    private final Headers trailers;
    private final Throwable cause;

    GrpcResponse(int status, String statusMessage, byte[] message, Headers headers, Headers trailers, Throwable cause) {
        this.status = status;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.message = message;
        this.headers = headers == null ? Headers.empty() : headers;
        this.trailers = trailers == null ? Headers.empty() : trailers;
        this.cause = cause;
    }

    /** The gRPC status code, one of the {@link GrpcStatus} constants. */
    public int status() {
        return status;
    }

    /** Whether the status is {@link GrpcStatus#OK}. */
    public boolean isOk() {
        return status == GrpcStatus.OK;
    }

    /** The status message: the decoded {@code grpc-message}, or what the client says went wrong; empty if none. */
    public String statusMessage() {
        return statusMessage;
    }

    /** A copy of the response message, without the gRPC prefix; present exactly when the call is {@link #isOk()}. */
    public Optional<byte[]> message() {
        return message == null ? Optional.empty() : Optional.of(message.clone());
    }

    /** The response header fields (initial metadata), pseudo-header fields left out; empty if none arrived. */
    public Headers headers() {
        return headers;
    }

    /** The trailer fields (trailing metadata), {@code grpc-status} included; empty for a trailers-only response. */
    public Headers trailers() {
        return trailers;
    }

    /** The exception behind a status the client set itself (a connection failure, for instance), if any. */
    public Optional<Throwable> cause() {
        return Optional.ofNullable(cause);
    }

    @Override
    public String toString() {
        return "GrpcResponse[" + GrpcResponses.codeName(status)
                + (statusMessage.isEmpty() ? "" : ": " + statusMessage)
                + (message == null ? "" : ", " + message.length + " bytes")
                + "]";
    }
}
