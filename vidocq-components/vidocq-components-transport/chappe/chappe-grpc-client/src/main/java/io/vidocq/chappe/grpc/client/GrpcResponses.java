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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;

/**
 * Turns a finished HTTP/2 response into a {@link GrpcResponse}: the gRPC status from the trailers (or from the
 * headers of a trailers-only response), the HTTP status mapped as gRPC's {@code http-grpc-status-mapping.md} says
 * when it is not 200, and the one length-prefixed message of a unary call.
 */
final class GrpcResponses {

    private static final String[] CODE_NAMES = {
        "OK",
        "CANCELLED",
        "UNKNOWN",
        "INVALID_ARGUMENT",
        "DEADLINE_EXCEEDED",
        "NOT_FOUND",
        "ALREADY_EXISTS",
        "PERMISSION_DENIED",
        "RESOURCE_EXHAUSTED",
        "FAILED_PRECONDITION",
        "ABORTED",
        "OUT_OF_RANGE",
        "UNIMPLEMENTED",
        "INTERNAL",
        "UNAVAILABLE",
        "DATA_LOSS",
        "UNAUTHENTICATED"
    };

    /** The gRPC message prefix: a compressed flag, then a four-byte big-endian length. */
    static final int PREFIX_LENGTH = 5;

    private GrpcResponses() {}

    /** The name of a status code, such as {@code DEADLINE_EXCEEDED}; {@code CODE_<n>} for a code gRPC does not define. */
    static String codeName(int code) {
        return code >= 0 && code < CODE_NAMES.length ? CODE_NAMES[code] : "CODE_" + code;
    }

    /** A status the client sets itself, without anything from the server. */
    static GrpcResponse failure(int status, String message, Throwable cause) {
        return new GrpcResponse(status, message, null, null, null, cause);
    }

    /** {@code message} with the gRPC prefix in front: uncompressed, its length in four bytes. */
    static byte[] frame(byte[] message) {
        byte[] framed = new byte[PREFIX_LENGTH + message.length];
        int length = message.length;
        framed[1] = (byte) (length >>> 24);
        framed[2] = (byte) (length >>> 16);
        framed[3] = (byte) (length >>> 8);
        framed[4] = (byte) length;
        System.arraycopy(message, 0, framed, PREFIX_LENGTH, length);
        return framed;
    }

    /**
     * The outcome of a response.
     *
     * @param httpStatus the {@code :status} of the response headers
     * @param headers the response header fields, pseudo-header fields left out
     * @param body the DATA of the stream, gRPC prefixes included
     * @param trailers the trailer fields, or {@code null} for a trailers-only response, whose headers then hold
     *     the status
     * @param maxMessageSize the largest response message accepted
     */
    static GrpcResponse assemble(int httpStatus, Headers headers, byte[] body, Headers trailers, int maxMessageSize) {
        Headers statusFields = trailers != null ? trailers : headers;
        if (httpStatus != 200) {
            return new GrpcResponse(
                    fromHttpStatus(httpStatus),
                    "HTTP status " + httpStatus + " from a gRPC endpoint" + detail(statusFields),
                    null,
                    headers,
                    trailers,
                    null);
        }
        String contentType = headers.firstOrNull("content-type");
        if (contentType == null || !isGrpcContentType(contentType)) {
            return new GrpcResponse(
                    GrpcStatus.UNKNOWN,
                    "The response is not gRPC: content-type " + (contentType == null ? "missing" : contentType),
                    null,
                    headers,
                    trailers,
                    null);
        }
        String code = statusFields.firstOrNull("grpc-status");
        if (code == null) {
            return new GrpcResponse(
                    GrpcStatus.UNKNOWN, "The response carries no grpc-status", null, headers, trailers, null);
        }
        int status = parseStatus(code);
        String statusMessage = decodeMessage(statusFields.firstOrNull("grpc-message"));
        if (status < 0) {
            String text = "Unknown grpc-status " + code + (statusMessage.isEmpty() ? "" : ": " + statusMessage);
            return new GrpcResponse(GrpcStatus.UNKNOWN, text, null, headers, trailers, null);
        }
        if (status != GrpcStatus.OK) {
            return new GrpcResponse(status, statusMessage, null, headers, trailers, null);
        }
        return unaryMessage(headers, body, trailers, maxMessageSize);
    }

    /** The one message of an OK unary response, or the status that says why there is none. */
    private static GrpcResponse unaryMessage(Headers headers, byte[] body, Headers trailers, int maxMessageSize) {
        if (body.length < PREFIX_LENGTH) {
            String why = body.length == 0
                    ? "The server returned OK without a response message"
                    : "The response ends inside a gRPC message prefix";
            return new GrpcResponse(GrpcStatus.INTERNAL, why, null, headers, trailers, null);
        }
        if (body[0] != 0) {
            String encoding = headers.firstOrNull("grpc-encoding");
            return new GrpcResponse(
                    GrpcStatus.INTERNAL,
                    "The server sent a compressed response message (grpc-encoding: "
                            + (encoding == null ? "missing" : encoding)
                            + "); this client reads uncompressed messages only (grpc-accept-encoding: identity)",
                    null,
                    headers,
                    trailers,
                    null);
        }
        long length = ((body[1] & 0xFFL) << 24) | ((body[2] & 0xFF) << 16) | ((body[3] & 0xFF) << 8) | (body[4] & 0xFF);
        if (length > maxMessageSize) {
            return new GrpcResponse(
                    GrpcStatus.RESOURCE_EXHAUSTED,
                    "The response message of " + length + " bytes is larger than the limit of " + maxMessageSize,
                    null,
                    headers,
                    trailers,
                    null);
        }
        long end = PREFIX_LENGTH + length;
        if (end > body.length) {
            return new GrpcResponse(
                    GrpcStatus.INTERNAL, "The response ends inside a gRPC message", null, headers, trailers, null);
        }
        if (end < body.length) {
            return new GrpcResponse(
                    GrpcStatus.INTERNAL,
                    "The server sent more than one message to a unary call",
                    null,
                    headers,
                    trailers,
                    null);
        }
        byte[] message = Arrays.copyOfRange(body, PREFIX_LENGTH, (int) end);
        return new GrpcResponse(GrpcStatus.OK, "", message, headers, trailers, null);
    }

    /** {@code application/grpc}, alone or with a sub-type ({@code +proto}) or parameters. */
    static boolean isGrpcContentType(String contentType) {
        String type = contentType.trim();
        if (!type.regionMatches(true, 0, "application/grpc", 0, 16)) return false;
        return type.length() == 16 || type.charAt(16) == '+' || type.charAt(16) == ';';
    }

    private static String detail(Headers statusFields) {
        String code = statusFields == null ? null : statusFields.firstOrNull("grpc-status");
        if (code == null) return "";
        String message = decodeMessage(statusFields.firstOrNull("grpc-message"));
        return " (grpc-status " + code + (message.isEmpty() ? "" : ": " + message) + ")";
    }

    /** The gRPC status of an HTTP status other than 200, as {@code http-grpc-status-mapping.md} gives it. */
    static int fromHttpStatus(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> GrpcStatus.INTERNAL;
            case 401 -> GrpcStatus.UNAUTHENTICATED;
            case 403 -> GrpcStatus.PERMISSION_DENIED;
            case 404 -> GrpcStatus.UNIMPLEMENTED;
            case 429, 502, 503, 504 -> GrpcStatus.UNAVAILABLE;
            default -> GrpcStatus.UNKNOWN;
        };
    }

    /** The code of a {@code grpc-status} value, or {@code -1} for one gRPC does not define. */
    private static int parseStatus(String value) {
        String digits = value.trim();
        if (digits.isEmpty() || digits.length() > 2) return -1;
        int code = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') return -1;
            code = code * 10 + (c - '0');
        }
        return code < CODE_NAMES.length ? code : -1;
    }

    /**
     * Decodes a {@code grpc-message}: percent-encoded UTF-8. An escape that is not two hex digits is kept as it
     * stands, as gRPC asks of a client.
     */
    static String decodeMessage(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.indexOf('%') < 0) return value;
        var out = new ByteArrayOutputStream(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            int escaped = c == '%' ? escapedOctet(value, i) : -1;
            if (escaped >= 0) {
                out.write(escaped);
                i += 3;
            } else {
                out.write(c & 0xFF);
                i++;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The octet of the escape {@code %XY} at {@code index}, or {@code -1} if no two hex digits follow. */
    private static int escapedOctet(String value, int index) {
        if (index + 2 >= value.length()) return -1;
        int high = Character.digit(value.charAt(index + 1), 16);
        int low = Character.digit(value.charAt(index + 2), 16);
        return high < 0 || low < 0 ? -1 : (high << 4) | low;
    }
}
