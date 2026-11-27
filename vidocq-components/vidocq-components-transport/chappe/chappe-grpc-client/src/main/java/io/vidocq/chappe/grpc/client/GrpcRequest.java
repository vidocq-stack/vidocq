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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.vidocq.chappe.api.Headers;

/**
 * One unary gRPC call: the method it names, the message it sends, its custom metadata and its deadline.
 * <p>
 * The message is opaque bytes: the caller encodes it (protobuf for OTLP), and {@link GrpcClient} only adds the
 * five-byte gRPC prefix. Metadata follows the gRPC grammar: a name of lower-case letters, digits, {@code _},
 * {@code -} and {@code .}, an ASCII value, and a binary value only under a name ending in {@code -bin}, sent in
 * base64. The names gRPC and HTTP/2 reserve ({@code grpc-*}, {@code content-type}, {@code te}, pseudo-header
 * fields, connection-specific fields) are refused: the client writes them itself.
 *
 * <pre>{@code
 * GrpcRequest request = GrpcRequest.builder("opentelemetry.proto.collector.trace.v1.TraceService", "Export")
 *         .message(exportRequestBytes)
 *         .metadata("authorization", "Bearer " + token)
 *         .deadline(Duration.ofSeconds(10))
 *         .build();
 * }</pre>
 */
public final class GrpcRequest {

    private static final Set<String> RESERVED = Set.of(
            "content-type",
            "te",
            "host",
            "connection",
            "keep-alive",
            "proxy-connection",
            "transfer-encoding",
            "upgrade");

    /** Separates the service and the method in the {@code :path} of a call: {@code /service/method}. */
    private static final char PATH_SEPARATOR = '/';

    private final String path;
    private final byte[] message;
    private final Headers metadata;
    private final Duration deadline;

    private GrpcRequest(Builder builder) {
        this.path = PATH_SEPARATOR + builder.service + PATH_SEPARATOR + builder.method;
        this.message = builder.message;
        this.metadata = builder.metadata.build();
        this.deadline = builder.deadline;
    }

    /**
     * Starts a request for {@code method} of {@code service}.
     *
     * @param service the fully qualified service name, such as {@code opentelemetry.proto.collector.trace.v1.TraceService}
     * @param method the method name, such as {@code Export}
     * @throws IllegalArgumentException if either is empty, holds a {@code /} or a character outside visible ASCII
     */
    public static Builder builder(String service, String method) {
        return new Builder(requireName("service", service), requireName("method", method));
    }

    /** The HTTP/2 {@code :path} of the call: {@code /service/method}. */
    public String path() {
        return path;
    }

    /** A copy of the request message, without the gRPC prefix. */
    public byte[] message() {
        return message.clone();
    }

    /** The message itself, for the client to send without a copy. */
    byte[] messageBytes() {
        return message;
    }

    /** The custom metadata, names in lower case, binary values in base64. */
    public Headers metadata() {
        return metadata;
    }

    /** The deadline of this call, which replaces the client's default one; empty to use that default. */
    public Optional<Duration> deadline() {
        return Optional.ofNullable(deadline);
    }

    @Override
    public String toString() {
        return "GrpcRequest[" + path + ", " + message.length + " bytes]";
    }

    private static String requireName(String what, String name) {
        Objects.requireNonNull(name, what);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The gRPC " + what + " name is empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7F || c == PATH_SEPARATOR) {
                throw new IllegalArgumentException(
                        "The gRPC " + what + " name holds a character it cannot carry: '" + name + "'");
            }
        }
        return name;
    }

    /** Builds a {@link GrpcRequest}. */
    public static final class Builder {

        private final String service;
        private final String method;
        private final Headers.Builder metadata = Headers.builder();
        private byte[] message = new byte[0];
        private Duration deadline;

        private Builder(String service, String method) {
            this.service = service;
            this.method = method;
        }

        /** The request message, already encoded by the caller; copied. Empty by default. */
        public Builder message(byte[] message) {
            this.message = Objects.requireNonNull(message, "message").clone();
            return this;
        }

        /**
         * Adds an ASCII metadata entry; a name may be added more than once.
         *
         * @throws IllegalArgumentException if the name is reserved, outside the gRPC grammar or ends in {@code -bin},
         *     or the value holds a character outside printable ASCII
         */
        public Builder metadata(String name, String value) {
            String key = requireMetadataName(name);
            Objects.requireNonNull(value, "value");
            if (key.endsWith("-bin")) {
                throw new IllegalArgumentException(
                        "Metadata '" + key + "' is binary: give its value as bytes, metadata(String, byte[])");
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c > 0x7E) {
                    throw new IllegalArgumentException(
                            "The value of metadata '" + key + "' holds a character outside printable ASCII");
                }
            }
            metadata.add(key, value);
            return this;
        }

        /**
         * Adds a binary metadata entry, sent in base64 without padding.
         *
         * @throws IllegalArgumentException if the name does not end in {@code -bin}, is reserved or outside the gRPC
         *     grammar
         */
        public Builder metadata(String name, byte[] value) {
            String key = requireMetadataName(name);
            Objects.requireNonNull(value, "value");
            if (!key.endsWith("-bin")) {
                throw new IllegalArgumentException("Binary metadata needs a name ending in -bin, not '" + key + "'");
            }
            metadata.add(key, Base64.getEncoder().withoutPadding().encodeToString(value));
            return this;
        }

        /**
         * The deadline of this call, counted from the start of {@link GrpcClient#unary(GrpcRequest)}.
         *
         * @throws IllegalArgumentException if it is zero or negative
         */
        public Builder deadline(Duration deadline) {
            Objects.requireNonNull(deadline, "deadline");
            if (deadline.isZero() || deadline.isNegative()) {
                throw new IllegalArgumentException("A deadline must be positive, not " + deadline);
            }
            this.deadline = deadline;
            return this;
        }

        public GrpcRequest build() {
            return new GrpcRequest(this);
        }

        private static String requireMetadataName(String name) {
            Objects.requireNonNull(name, "name");
            String key = name.toLowerCase(Locale.ROOT);
            if (key.isEmpty() || !isMetadataName(key)) {
                throw new IllegalArgumentException(
                        "Invalid metadata name '" + name + "': lower-case letters, digits, '_', '-' and '.' only");
            }
            if (key.startsWith("grpc-") || RESERVED.contains(key)) {
                throw new IllegalArgumentException("Metadata '" + key + "' is reserved: the client writes it itself");
            }
            return key;
        }

        private static boolean isMetadataName(String key) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            if (bytes.length != key.length()) return false;
            for (byte b : bytes) {
                boolean ok = (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') || b == '_' || b == '-' || b == '.';
                if (!ok) return false;
            }
            return true;
        }
    }
}
