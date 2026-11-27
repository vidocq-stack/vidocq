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

import java.io.IOException;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLContext;

import io.vidocq.chappe.api.GrpcStatus;

/**
 * A gRPC client for unary calls, over one HTTP/2 connection it opens on the first call and reopens when the server
 * closes it.
 * <p>
 * An {@code http://host:port} target is reached in cleartext HTTP/2 with prior knowledge (h2c, no upgrade), an
 * {@code https://host:port} target over TLS, with ALPN {@code h2} and the host name checked against the server's
 * certificate. The messages are opaque bytes: the caller encodes and decodes them (protobuf for OTLP); the client
 * writes the five-byte gRPC prefix, sends them uncompressed and refuses a compressed response.
 * <p>
 * A call never throws for what happens on the network: it returns a {@link GrpcResponse} whose status is the
 * server's, or {@link GrpcStatus#UNAVAILABLE} when the server cannot be reached or the connection drops, or
 * {@link GrpcStatus#DEADLINE_EXCEEDED} when the deadline expires first. The deadline is sent to the server as
 * {@code grpc-timeout}. Calls may run concurrently from many threads; they share the connection, within the server's
 * SETTINGS_MAX_CONCURRENT_STREAMS. Each runs on virtual threads, so a blocked call holds no platform thread.
 *
 * <pre>{@code
 * try (var client = GrpcClient.builder(URI.create("http://collector:4317"))
 *         .deadline(Duration.ofSeconds(10))
 *         .build()) {
 *     GrpcResponse response = client.unary(
 *             "opentelemetry.proto.collector.trace.v1.TraceService", "Export", exportRequestBytes);
 *     if (!response.isOk()) {
 *         log(response.status() + " " + response.statusMessage());
 *     }
 * }
 * }</pre>
 */
public final class GrpcClient implements AutoCloseable {

    /** The largest response message accepted by default, as most gRPC implementations do: 4 MiB. */
    public static final int DEFAULT_MAX_RESPONSE_MESSAGE_SIZE = 4 * 1024 * 1024;

    private final URI target;
    private final SSLContext sslContext;
    private final Duration connectTimeout;
    private final Duration deadline;
    private final int maxResponseMessageSize;

    private final ReentrantLock lock = new ReentrantLock();
    private ClientConnection connection;
    private boolean closed;

    private GrpcClient(Builder builder) {
        this.target = builder.target;
        this.sslContext = builder.sslContext;
        this.connectTimeout = builder.connectTimeout;
        this.deadline = builder.deadline;
        this.maxResponseMessageSize = builder.maxResponseMessageSize;
    }

    /**
     * Starts a client of {@code target}, {@code http://host:port} or {@code https://host:port}.
     *
     * @throws IllegalArgumentException if the scheme is neither {@code http} nor {@code https}, the host is missing,
     *     or the URI has a path, a query, a fragment or user information
     */
    public static Builder builder(URI target) {
        return new Builder(target);
    }

    /** The server this client calls. */
    public URI target() {
        return target;
    }

    /**
     * Calls {@code method} of {@code service} with {@code message}, under the client's default deadline.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits; the call is then cancelled
     * @throws IllegalStateException if the client is closed
     */
    public GrpcResponse unary(String service, String method, byte[] message) throws InterruptedException {
        return unary(GrpcRequest.builder(service, method).message(message).build());
    }

    /**
     * Runs a unary call: sends its message, waits for the response message and the status, until the request's
     * deadline, or the client's default one.
     *
     * @throws InterruptedException if the calling thread is interrupted while it waits; the call is then cancelled
     * @throws IllegalStateException if the client is closed
     */
    public GrpcResponse unary(GrpcRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        Deadline callDeadline = Deadline.after(request.deadline().orElse(deadline));
        // A connection that turns a call away before sending anything (it received GOAWAY in between) is replaced
        // once: the call is then safe to run again.
        for (int attempt = 0; attempt < 2; attempt++) {
            ClientConnection current;
            try {
                current = connection(callDeadline);
            } catch (IOException e) {
                if (callDeadline.expired()) {
                    return GrpcResponses.failure(
                            GrpcStatus.DEADLINE_EXCEEDED,
                            "The deadline expired while connecting to " + target + ": " + e.getMessage(),
                            e);
                }
                return GrpcResponses.failure(
                        GrpcStatus.UNAVAILABLE, "Cannot reach the gRPC server " + target + ": " + e.getMessage(), e);
            }
            GrpcResponse response = current.call(request, callDeadline, maxResponseMessageSize);
            if (response != null) return response;
        }
        return GrpcResponses.failure(
                GrpcStatus.UNAVAILABLE, "The gRPC server " + target + " accepted no new call", null);
    }

    /** The connection to run a call on: the current one, or a new one if there is none or it takes no new call. */
    private ClientConnection connection(Deadline callDeadline) throws IOException {
        lock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("The gRPC client of " + target + " is closed");
            }
            if (connection != null && connection.acceptsCalls()) {
                return connection;
            }
            if (connection != null) {
                connection.retire();
            }
            long connectMillis = Math.clamp(connectTimeout.toMillis(), 1L, Integer.MAX_VALUE);
            int timeout = (int) Math.clamp(callDeadline.remainingNanos() / 1_000_000L, 1L, connectMillis);
            connection = ClientConnection.open(target, sslContext, timeout);
            return connection;
        } finally {
            lock.unlock();
        }
    }

    /** Closes the connection; the calls in flight end with {@link GrpcStatus#UNAVAILABLE}. */
    @Override
    public void close() {
        ClientConnection current;
        lock.lock();
        try {
            closed = true;
            current = connection;
            connection = null;
        } finally {
            lock.unlock();
        }
        if (current != null) current.close();
    }

    @Override
    public String toString() {
        return "GrpcClient[" + target + "]";
    }

    /** Builds a {@link GrpcClient}. */
    public static final class Builder {

        private final URI target;
        private SSLContext sslContext;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration deadline;
        private int maxResponseMessageSize = DEFAULT_MAX_RESPONSE_MESSAGE_SIZE;

        private Builder(URI target) {
            Objects.requireNonNull(target, "target");
            String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException(
                        "A gRPC target is http://host:port or https://host:port, not " + target);
            }
            if (target.getHost() == null) {
                throw new IllegalArgumentException("The gRPC target " + target + " names no host");
            }
            String path = target.getRawPath();
            if ((path != null && !path.isEmpty() && !path.equals("/"))
                    || target.getRawQuery() != null
                    || target.getRawFragment() != null
                    || target.getRawUserInfo() != null) {
                throw new IllegalArgumentException(
                        "The gRPC target " + target + " must be scheme://host:port only: the call names the method");
            }
            this.target = URI.create(scheme + "://" + target.getRawAuthority());
        }

        /** The TLS context of an {@code https} target; the JVM's default context if not given. */
        public Builder sslContext(SSLContext sslContext) {
            this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
            return this;
        }

        /** Bounds the TCP connect, the TLS handshake and the HTTP/2 preface of a new connection; 10 s by default. */
        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = requirePositive("connectTimeout", connectTimeout);
            return this;
        }

        /**
         * The deadline of a call whose request names none; none by default, as in gRPC, so a call without a deadline
         * waits for as long as the server takes.
         */
        public Builder deadline(Duration deadline) {
            this.deadline = requirePositive("deadline", deadline);
            return this;
        }

        /** The largest response message accepted; a larger one ends the call with {@code RESOURCE_EXHAUSTED}. */
        public Builder maxResponseMessageSize(int maxResponseMessageSize) {
            if (maxResponseMessageSize < 0 || maxResponseMessageSize > Integer.MAX_VALUE - 5) {
                throw new IllegalArgumentException("Invalid maxResponseMessageSize: " + maxResponseMessageSize);
            }
            this.maxResponseMessageSize = maxResponseMessageSize;
            return this;
        }

        /**
         * Builds the client; it connects on its first call.
         *
         * @throws IllegalStateException if an {@code https} target has no TLS context and the JVM has no default one
         */
        public GrpcClient build() {
            if (target.getScheme().equals("https") && sslContext == null) {
                try {
                    sslContext = SSLContext.getDefault();
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException("No default TLS context for " + target, e);
                }
            }
            return new GrpcClient(this);
        }

        private static Duration requirePositive(String what, Duration value) {
            Objects.requireNonNull(value, what);
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(what + " must be positive, not " + value);
            }
            return value;
        }
    }
}
