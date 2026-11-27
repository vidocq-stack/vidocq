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

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;

import io.vidocq.chappe.api.Headers;

/**
 * One call on a {@link ClientConnection}: what the sending side needs (its id, its send window) and what the
 * reading side gathers (status, headers, body, trailers) until the response is complete.
 * <p>
 * The id, the send window and {@code released} are guarded by the connection's lock; the response state is
 * touched by the connection's reader thread only. {@link #result} is completed once, by whichever side ends the
 * call first: the response, a reset, the deadline, or the end of the connection.
 */
final class ClientStream {

    final GrpcRequest request;
    final Deadline deadline;
    final int maxMessageSize;
    final CompletableFuture<GrpcResponse> result = new CompletableFuture<>();

    // Guarded by the connection's lock.
    int id;
    long sendWindow;
    boolean released;

    // Reader thread only.
    int httpStatus = -1;
    Headers headers;
    /** The DATA of the stream, gRPC prefixes included. */
    final ByteArrayOutputStream body = new ByteArrayOutputStream(256);

    int unacknowledged;

    ClientStream(GrpcRequest request, Deadline deadline, int maxMessageSize) {
        this.request = request;
        this.deadline = deadline;
        this.maxMessageSize = maxMessageSize;
    }

    boolean isDone() {
        return result.isDone();
    }
}
