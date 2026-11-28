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
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import io.vidocq.chappe.api.GrpcStatus;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.http.h2.HpackDecoder;
import io.vidocq.chappe.http.h2.HpackEncoder;
import io.vidocq.chappe.http.h2.Http2ConnectionException;
import io.vidocq.chappe.http.h2.Http2ErrorCode;
import io.vidocq.chappe.http.h2.Http2Frame;
import io.vidocq.chappe.http.h2.Http2FrameReader;
import io.vidocq.chappe.http.h2.Http2FrameWriter;
import io.vidocq.chappe.http.h2.Http2Settings;

/**
 * One HTTP/2 connection of a {@link GrpcClient} (RFC 9113), on the framing and HPACK codec of
 * {@code io.vidocq.chappe.http.h2}.
 * <p>
 * A virtual thread reads the frames and completes the calls; each call sends its request from a virtual thread of
 * its own, so a caller waits only for its deadline. The connection keeps the flow-control windows of both sides
 * (§6.9): it sends no more DATA than the server's windows allow, and gives back what it reads in WINDOW_UPDATE
 * frames. It honours SETTINGS_MAX_CONCURRENT_STREAMS, answers PING, and after a GOAWAY takes no new call, fails
 * the calls the server will not process with {@code UNAVAILABLE}, and closes once the others are over.
 */
final class ClientConnection implements Closeable {

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    /** The window each side starts with (RFC 9113 §6.9.2). */
    private static final int DEFAULT_WINDOW = 65_535;

    /** The stream window this client advertises, and the connection window it opens to. */
    static final int LOCAL_WINDOW = 1 << 20;

    /** The largest frame this client reads: the default, which it never raises. */
    private static final int MAX_FRAME_SIZE = 16_384;

    /** The largest decoded header list this client accepts from the server. */
    private static final int MAX_HEADER_LIST_SIZE = 64 * 1_024;

    private static final int MAX_WINDOW = Integer.MAX_VALUE;
    private static final int LAST_STREAM_ID = Integer.MAX_VALUE;
    private static final String USER_AGENT = "chappe-grpc-client";
    private static final String HTTPS = "https";

    /** The outcome of a call the connection closed under before anything of it was sent: safe to run elsewhere. */
    private static final GrpcResponse NOT_SENT =
            GrpcResponses.failure(GrpcStatus.UNAVAILABLE, "The connection closed before the call started", null);

    private final Socket socket;
    private final String scheme;
    private final String authority;
    private final Http2FrameReader reader;
    private final Http2FrameWriter writer;
    private final HpackEncoder encoder = new HpackEncoder();
    private final HpackDecoder decoder = new HpackDecoder(4_096, MAX_HEADER_LIST_SIZE);

    /** Guards the streams, the windows, the settings and the life cycle. */
    private final ReentrantLock lock = new ReentrantLock();

    private final Condition changed = lock.newCondition();

    /** Taken to give a stream its id and send its HEADERS: ids must reach the wire in increasing order (§5.1.1). */
    private final ReentrantLock openLock = new ReentrantLock();

    private final Map<Integer, ClientStream> streams = new HashMap<>();
    private int nextStreamId = 1;
    private int reserved;
    private long connectionSendWindow = DEFAULT_WINDOW;
    private Http2Settings peerSettings =
            new Http2Settings(4_096, false, Integer.MAX_VALUE, DEFAULT_WINDOW, MAX_FRAME_SIZE, Integer.MAX_VALUE);
    private boolean draining;
    private boolean closed;

    /** Reader thread only: what was read on the connection and not yet given back. */
    private int connectionUnacknowledged;

    private ClientConnection(Socket socket, String scheme, String authority) throws IOException {
        this.socket = socket;
        this.scheme = scheme;
        this.authority = authority;
        this.reader = new Http2FrameReader(
                ByteBuffer.allocate(Http2FrameReader.FRAME_HEADER_SIZE + MAX_FRAME_SIZE * 2)
                        .flip(),
                Channels.newChannel(socket.getInputStream()));
        this.writer = new Http2FrameWriter(
                ByteBuffer.allocate(Http2FrameReader.FRAME_HEADER_SIZE + MAX_FRAME_SIZE),
                Channels.newChannel(socket.getOutputStream()));
    }

    /**
     * Connects to {@code target}, exchanges the connection prefaces (§3.4) and starts reading.
     *
     * @param target an {@code http} URI for cleartext HTTP/2 with prior knowledge, {@code https} for TLS with ALPN
     * @param sslContext the TLS context of an {@code https} target
     * @param timeoutMillis bounds the TCP connect, the TLS handshake and the wait for the server's SETTINGS
     * @throws IOException if the server cannot be reached or does not speak HTTP/2
     */
    static ClientConnection open(URI target, SSLContext sslContext, int timeoutMillis) throws IOException {
        boolean tls = HTTPS.equalsIgnoreCase(target.getScheme());
        String host = target.getHost();
        int defaultPort = tls ? 443 : 80;
        int port = target.getPort() >= 0 ? target.getPort() : defaultPort;
        String authority = (host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
        // The TCP socket under both schemes: TLS wraps it in handshake() for https, and an http target asks for
        // cleartext HTTP/2 with prior knowledge (h2c), as gRPC clients allow for a local or mesh-internal server.
        var raw = new Socket(); // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket
        ClientConnection connection = null;
        try {
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            raw.connect(new InetSocketAddress(host, port), timeoutMillis);
            Socket socket = tls ? handshake(sslContext, raw, host, port, timeoutMillis) : raw;
            connection = new ClientConnection(socket, tls ? HTTPS : "http", authority);
            connection.start(target, timeoutMillis);
            return connection;
        } catch (IOException | RuntimeException e) {
            if (connection != null) connection.close();
            // Closing the plain socket also closes a TLS socket layered on it (autoClose).
            closeQuietly(raw);
            throw e;
        }
    }

    private static SSLSocket handshake(SSLContext sslContext, Socket raw, String host, int port, int timeoutMillis)
            throws IOException {
        var ssl = (SSLSocket) sslContext.getSocketFactory().createSocket(raw, host, port, true);
        boolean negotiated = false;
        try {
            var parameters = ssl.getSSLParameters();
            parameters.setApplicationProtocols(new String[] {"h2"});
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (!isIpLiteral(host)) {
                parameters.setServerNames(List.of(new SNIHostName(host)));
            }
            ssl.setSSLParameters(parameters);
            ssl.setSoTimeout(timeoutMillis);
            ssl.startHandshake();
            if (!"h2".equals(ssl.getApplicationProtocol())) {
                throw new IOException(
                        "The server at " + host + ":" + port + " did not negotiate HTTP/2 (ALPN h2) over TLS");
            }
            negotiated = true;
            return ssl;
        } finally {
            if (!negotiated) closeQuietly(ssl);
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'));
    }

    /** Sends the client preface and SETTINGS, waits for the server's SETTINGS, then starts the reader thread. */
    private void start(URI target, int timeoutMillis) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(PREFACE);
        out.flush();
        writer.writeFrame(Http2Frame.TYPE_SETTINGS, 0, 0, localSettings(), 0, 18);
        writer.writeWindowUpdate(0, LOCAL_WINDOW - DEFAULT_WINDOW);

        socket.setSoTimeout(timeoutMillis);
        Http2Frame first;
        try {
            first = reader.readFrame(MAX_FRAME_SIZE);
        } catch (IOException e) {
            throw new IOException(
                    "The server at " + target + " did not answer the HTTP/2 connection preface ("
                            + (HTTPS.equals(scheme) ? "TLS with ALPN h2" : "cleartext HTTP/2 with prior knowledge")
                            + "): " + e.getMessage(),
                    e);
        }
        if (!(first instanceof Http2Frame.SettingsFrame settings) || settings.ack()) {
            throw new IOException("The server at " + target + " did not open with SETTINGS: it does not speak HTTP/2");
        }
        onSettings(settings);
        socket.setSoTimeout(0);
        Thread.ofVirtual().name("chappe-grpc-client-reader-" + authority).start(this::readLoop);
    }

    /** SETTINGS_ENABLE_PUSH 0, SETTINGS_INITIAL_WINDOW_SIZE, SETTINGS_MAX_HEADER_LIST_SIZE. */
    private static byte[] localSettings() {
        return ByteBuffer.allocate(18)
                .putShort((short) Http2Settings.ENABLE_PUSH)
                .putInt(0)
                .putShort((short) Http2Settings.INITIAL_WINDOW_SIZE)
                .putInt(LOCAL_WINDOW)
                .putShort((short) Http2Settings.MAX_HEADER_LIST_SIZE)
                .putInt(MAX_HEADER_LIST_SIZE)
                .array();
    }

    // -------------------------------------------------------------------------
    // Calls
    // -------------------------------------------------------------------------

    /** Whether a new call may start on this connection. */
    boolean acceptsCalls() {
        lock.lock();
        try {
            return !draining && !closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs one unary call and waits for its outcome until its deadline.
     *
     * @return the outcome, or {@code null} if the connection took no new call before anything was sent: the caller
     *     may then run it on another connection
     */
    GrpcResponse call(GrpcRequest request, Deadline deadline, int maxMessageSize) throws InterruptedException {
        var stream = new ClientStream(request, deadline, maxMessageSize);
        if (!reserve(deadline)) {
            return deadline.expired() ? deadlineExceeded() : null;
        }
        Thread.ofVirtual().name("chappe-grpc-client-send").start(() -> send(stream));
        try {
            GrpcResponse outcome = deadline.set()
                    ? stream.result.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS)
                    : stream.result.get();
            return outcome == NOT_SENT ? null : outcome;
        } catch (TimeoutException _) {
            cancel(stream, deadlineExceeded());
            return stream.result.join();
        } catch (InterruptedException e) {
            cancel(stream, GrpcResponses.failure(GrpcStatus.CANCELLED, "The calling thread was interrupted", e));
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            // Never completed exceptionally; kept for the compiler.
            return GrpcResponses.failure(GrpcStatus.INTERNAL, "The call failed: " + e.getCause(), e.getCause());
        }
    }

    private static GrpcResponse deadlineExceeded() {
        return GrpcResponses.failure(GrpcStatus.DEADLINE_EXCEEDED, "The deadline expired before the call ended", null);
    }

    /**
     * Takes a slot under the server's SETTINGS_MAX_CONCURRENT_STREAMS, waiting until the deadline for one.
     *
     * @return {@code false} if the connection takes no new call, or the deadline expired first
     */
    private boolean reserve(Deadline deadline) throws InterruptedException {
        lock.lock();
        try {
            long remaining = deadline.remainingNanos();
            while (!draining && !closed && reserved >= peerSettings.maxConcurrentStreams()) {
                if (!deadline.set()) {
                    changed.await();
                } else if (remaining <= 0) {
                    return false;
                } else {
                    remaining = changed.awaitNanos(remaining);
                }
            }
            if (draining || closed) return false;
            reserved++;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** Sends the HEADERS and the DATA of a call, within the server's flow-control windows. Its own virtual thread. */
    private void send(ClientStream stream) {
        try {
            if (!openStream(stream)) return;
            byte[] framed = GrpcResponses.frame(stream.request.messageBytes());
            int offset = 0;
            while (offset < framed.length) {
                int length = acquireSendWindow(stream, framed.length - offset);
                if (length <= 0) return;
                writer.writeData(stream.id, framed, offset, length, offset + length == framed.length);
                offset += length;
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            finish(stream, GrpcResponses.failure(GrpcStatus.CANCELLED, "The sending thread was interrupted", null));
        } catch (IOException e) {
            // A failed write leaves the connection unusable (its HPACK context included): end it now, so the next
            // call opens another instead of waiting for the reader to notice.
            finish(
                    stream,
                    GrpcResponses.failure(GrpcStatus.UNAVAILABLE, "Sending the request failed: " + e.getMessage(), e));
            terminate(GrpcResponses.failure(
                    GrpcStatus.UNAVAILABLE, "The connection to the gRPC server was lost: " + e.getMessage(), e));
        } catch (RuntimeException e) {
            finish(
                    stream,
                    GrpcResponses.failure(GrpcStatus.INTERNAL, "Sending the request failed: " + e.getMessage(), e));
        }
    }

    /** Gives the stream its id and sends its HEADERS; {@code false} if the call ended first. */
    private boolean openStream(ClientStream stream) throws IOException {
        Headers fields = requestFields(stream);
        openLock.lock();
        try {
            lock.lock();
            try {
                if (stream.isDone()) {
                    release(stream);
                    return false;
                }
                if (closed || nextStreamId < 0) {
                    stream.result.complete(NOT_SENT);
                    release(stream);
                    return false;
                }
                stream.id = nextStreamId;
                nextStreamId += 2;
                if (nextStreamId < 0 || nextStreamId > LAST_STREAM_ID - 1) {
                    draining = true; // stream ids are spent (§5.1.1): the client opens another connection
                }
                stream.sendWindow = peerSettings.initialWindowSize();
                streams.put(stream.id, stream);
            } finally {
                lock.unlock();
            }
            writer.writeHeaderBlock(
                    stream.id,
                    () -> encoder.encodeRequest("POST", scheme, authority, stream.request.path(), fields),
                    false);
            return true;
        } finally {
            openLock.unlock();
        }
    }

    private static Headers requestFields(ClientStream stream) {
        var fields = Headers.builder()
                .add("content-type", "application/grpc")
                .add("te", "trailers")
                .add("grpc-accept-encoding", "identity")
                .add("user-agent", USER_AGENT);
        if (stream.deadline.set()) {
            fields.add("grpc-timeout", GrpcTimeout.encode(stream.deadline.remainingNanos()));
        }
        for (var entry : stream.request.metadata()) {
            fields.add(entry.name(), entry.value());
        }
        return fields.build();
    }

    /**
     * Waits for room in both send windows and takes up to {@code wanted} octets of it.
     *
     * @return the octets taken, or {@code 0} if the call ended (its deadline included) first
     */
    private int acquireSendWindow(ClientStream stream, int wanted) throws InterruptedException {
        lock.lock();
        try {
            long remaining = stream.deadline.remainingNanos();
            while (!stream.isDone() && !closed && (connectionSendWindow <= 0 || stream.sendWindow <= 0)) {
                if (!stream.deadline.set()) {
                    changed.await();
                } else if (remaining <= 0) {
                    return 0;
                } else {
                    remaining = changed.awaitNanos(remaining);
                }
            }
            if (stream.isDone() || closed) return 0;
            int length = (int) Math.min(
                    Math.min(wanted, peerSettings.maxFrameSize()), Math.min(connectionSendWindow, stream.sendWindow));
            connectionSendWindow -= length;
            stream.sendWindow -= length;
            return length;
        } finally {
            lock.unlock();
        }
    }

    /** Ends a call on the client's side and resets its stream, if it was open, with {@code RST_STREAM(CANCEL)}. */
    private void cancel(ClientStream stream, GrpcResponse outcome) {
        if (!finish(stream, outcome)) return;
        int id;
        lock.lock();
        try {
            id = stream.id;
        } finally {
            lock.unlock();
        }
        if (id > 0) {
            // Off the caller's thread: the write may wait behind a stalled one. Under openLock, so the reset never
            // reaches the wire before the HEADERS of its stream.
            Thread.ofVirtual().name("chappe-grpc-client-cancel").start(() -> {
                openLock.lock();
                try {
                    writer.writeRstStream(id, Http2ErrorCode.CANCEL);
                } catch (IOException _) {
                    // The connection is gone: its reader fails what is left.
                } finally {
                    openLock.unlock();
                }
            });
        }
    }

    /** Completes a call once and gives back its slot; {@code true} if this ended it. */
    private boolean finish(ClientStream stream, GrpcResponse outcome) {
        boolean ended = stream.result.complete(outcome);
        lock.lock();
        try {
            release(stream);
        } finally {
            lock.unlock();
        }
        return ended;
    }

    /** Gives back the slot of a call, once. Under the lock. */
    private void release(ClientStream stream) {
        if (stream.released) return;
        stream.released = true;
        reserved--;
        if (stream.id > 0) streams.remove(stream.id);
        changed.signalAll();
        if (draining && reserved == 0) {
            closeTransport();
        }
    }

    /** Takes no new call, and closes once the calls in flight are over. */
    void retire() {
        lock.lock();
        try {
            draining = true;
            if (reserved == 0) closeTransport();
        } finally {
            lock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------

    private void readLoop() {
        GrpcResponse failure =
                GrpcResponses.failure(GrpcStatus.UNAVAILABLE, "The connection to the gRPC server was closed", null);
        try {
            while (!socket.isClosed()) {
                Http2Frame frame = reader.readFrame(MAX_FRAME_SIZE);
                if (frame != null) onFrame(frame);
            }
        } catch (Http2ConnectionException e) {
            sendGoaway(e.errorCode());
            failure = GrpcResponses.failure(GrpcStatus.INTERNAL, "HTTP/2 protocol error: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            failure = GrpcResponses.failure(
                    GrpcStatus.UNAVAILABLE, "The connection to the gRPC server was lost: " + e.getMessage(), e);
        }
        terminate(failure);
    }

    private void onFrame(Http2Frame frame) throws IOException {
        switch (frame) {
            case Http2Frame.DataFrame data -> onData(data);
            case Http2Frame.HeadersFrame headers -> onHeaders(headers);
            case Http2Frame.RstStreamFrame reset -> onReset(reset);
            case Http2Frame.SettingsFrame settings when !settings.ack() -> onSettings(settings);
            case Http2Frame.SettingsFrame _ -> {
                // the acknowledgement of this client's SETTINGS
            }
            case Http2Frame.PingFrame ping when !ping.ack() -> writer.writePingAck(ping.opaqueData());
            case Http2Frame.PingFrame _ -> {
                // this client sends no PING
            }
            case Http2Frame.GoawayFrame goaway -> onGoaway(goaway);
            case Http2Frame.WindowUpdateFrame update -> onWindowUpdate(update);
            case Http2Frame.ContinuationFrame _ ->
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR, "CONTINUATION frame without a HEADERS frame before it");
            case Http2Frame.UnknownFrame unknown
            when unknown.type() == Http2Frame.TYPE_PUSH_PROMISE ->
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR, "PUSH_PROMISE received although SETTINGS_ENABLE_PUSH is 0");
            case Http2Frame.UnknownFrame _ -> {
                // Any other frame type is ignored (§4.1, §5.5).
            }
        }
    }

    private void onSettings(Http2Frame.SettingsFrame frame) throws IOException {
        ByteBuffer payload = frame.payload();
        int length = payload.remaining();
        if (frame.streamId() != 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "SETTINGS frame on a stream");
        }
        lock.lock();
        try {
            Http2Settings previous = peerSettings;
            Http2Settings next = previous.applyFrom(payload, length);
            if (next.initialWindowSize() < 0) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.FLOW_CONTROL_ERROR, "SETTINGS_INITIAL_WINDOW_SIZE above 2^31-1");
            }
            if (next.maxFrameSize() < MAX_FRAME_SIZE || next.maxFrameSize() > 0xFFFFFF) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR, "SETTINGS_MAX_FRAME_SIZE out of range: " + next.maxFrameSize());
            }
            if (next.maxConcurrentStreams() < 0) {
                // An unsigned value of 2^31 or more: as good as unlimited.
                next = new Http2Settings(
                        next.headerTableSize(),
                        next.enablePush(),
                        Integer.MAX_VALUE,
                        next.initialWindowSize(),
                        next.maxFrameSize(),
                        next.maxHeaderListSize());
            }
            long delta = (long) next.initialWindowSize() - previous.initialWindowSize();
            for (ClientStream stream : streams.values()) {
                stream.sendWindow += delta;
                if (stream.sendWindow > MAX_WINDOW) {
                    throw new Http2ConnectionException(
                            Http2ErrorCode.FLOW_CONTROL_ERROR, "SETTINGS_INITIAL_WINDOW_SIZE overflows a window");
                }
            }
            peerSettings = next;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        encoder.applyPeerMaxTableSize(peerSettings.headerTableSize());
        writer.writeSettingsAck();
    }

    private void onWindowUpdate(Http2Frame.WindowUpdateFrame frame) throws IOException {
        int increment = frame.windowIncrement();
        lock.lock();
        try {
            if (frame.streamId() == 0) {
                if (increment == 0) {
                    throw new Http2ConnectionException(
                            Http2ErrorCode.PROTOCOL_ERROR, "WINDOW_UPDATE of 0 on the connection");
                }
                connectionSendWindow += increment;
                if (connectionSendWindow > MAX_WINDOW) {
                    throw new Http2ConnectionException(
                            Http2ErrorCode.FLOW_CONTROL_ERROR, "The connection window passed 2^31-1");
                }
            } else {
                ClientStream stream = streams.get(frame.streamId());
                if (stream != null) stream.sendWindow += increment;
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private ClientStream stream(int id) {
        lock.lock();
        try {
            return streams.get(id);
        } finally {
            lock.unlock();
        }
    }

    private void onHeaders(Http2Frame.HeadersFrame frame) throws IOException {
        int id = frame.streamId();
        if (id == 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "HEADERS frame on stream 0");
        }
        ByteBuffer block = frame.endHeaders() ? frame.headerBlock() : continued(id, frame.headerBlock());
        // The block is decoded even for a stream that is over: the HPACK context is the connection's (RFC 7541 §2.2).
        var fields = new ArrayList<String[]>();
        decoder.decode(block, (name, value) -> fields.add(new String[] {name, value}));
        ClientStream stream = stream(id);
        if (stream == null) return;

        if (stream.httpStatus < 0) {
            int status = statusOf(fields);
            if (status >= 100 && status < 200) return; // an informational response (§8.1)
            if (status < 0) {
                resetStream(stream, Http2ErrorCode.PROTOCOL_ERROR, "The response carries no valid :status");
                return;
            }
            stream.httpStatus = status;
            stream.headers = regularFields(fields);
            if (frame.endStream()) complete(stream, null);
        } else if (frame.endStream()) {
            complete(stream, regularFields(fields));
        } else {
            resetStream(stream, Http2ErrorCode.PROTOCOL_ERROR, "Trailers without END_STREAM");
        }
    }

    /** The rest of a header block: the CONTINUATION frames that must follow its HEADERS frame (§6.10). */
    private ByteBuffer continued(int id, ByteBuffer first) throws IOException {
        var block = new ByteArrayOutputStream(first.remaining() * 2);
        append(block, first);
        while (true) {
            Http2Frame next = reader.readFrame(MAX_FRAME_SIZE);
            if (!(next instanceof Http2Frame.ContinuationFrame continuation) || continuation.streamId() != id) {
                throw new Http2ConnectionException(
                        Http2ErrorCode.PROTOCOL_ERROR, "A header block was interrupted by another frame");
            }
            append(block, continuation.headerBlock());
            if (block.size() > MAX_HEADER_LIST_SIZE * 2) {
                throw new Http2ConnectionException(Http2ErrorCode.ENHANCE_YOUR_CALM, "Header block too large");
            }
            if (continuation.endHeaders()) return ByteBuffer.wrap(block.toByteArray());
        }
    }

    private static void append(ByteArrayOutputStream out, ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        out.writeBytes(bytes);
    }

    private static int statusOf(List<String[]> fields) {
        for (String[] field : fields) {
            if (":status".equals(field[0])) {
                String value = field[1];
                if (value.length() != 3 || !value.chars().allMatch(Character::isDigit)) return -1;
                return Integer.parseInt(value);
            }
        }
        return -1;
    }

    private static Headers regularFields(List<String[]> fields) {
        var headers = Headers.builder();
        for (String[] field : fields) {
            if (!field[0].startsWith(":")) headers.add(field[0], field[1]);
        }
        return headers.build();
    }

    private void onData(Http2Frame.DataFrame frame) throws IOException {
        int id = frame.streamId();
        if (id == 0) {
            throw new Http2ConnectionException(Http2ErrorCode.PROTOCOL_ERROR, "DATA frame on stream 0");
        }
        int padded = (frame.flags() & Http2Frame.FLAG_PADDED) != 0 ? 1 : 0;
        int flowControlled = frame.data().remaining() + frame.padding() + padded;
        connectionUnacknowledged += flowControlled;
        if (connectionUnacknowledged >= LOCAL_WINDOW / 2) {
            writer.writeWindowUpdate(0, connectionUnacknowledged);
            connectionUnacknowledged = 0;
        }
        ClientStream stream = stream(id);
        if (stream == null) return;
        if (stream.httpStatus < 0) {
            resetStream(stream, Http2ErrorCode.PROTOCOL_ERROR, "DATA before the response headers");
            return;
        }
        ByteBuffer data = frame.data();
        if (stream.body.size() + (long) data.remaining() > (long) stream.maxMessageSize + GrpcResponses.PREFIX_LENGTH) {
            cancelByClient(
                    stream,
                    GrpcResponses.failure(
                            GrpcStatus.RESOURCE_EXHAUSTED,
                            "The response message is larger than the limit of " + stream.maxMessageSize + " bytes",
                            null));
            return;
        }
        append(stream.body, data);
        if (frame.endStream()) {
            complete(stream, Headers.empty());
            return;
        }
        stream.unacknowledged += flowControlled;
        if (stream.unacknowledged >= LOCAL_WINDOW / 2) {
            writer.writeWindowUpdate(id, stream.unacknowledged);
            stream.unacknowledged = 0;
        }
    }

    private void onReset(Http2Frame.RstStreamFrame frame) {
        ClientStream stream = stream(frame.streamId());
        if (stream == null) return;
        Http2ErrorCode code = Http2ErrorCode.of(frame.errorCode());
        String name = code == null ? "0x" + Integer.toHexString(frame.errorCode()) : code.name();
        finish(
                stream,
                GrpcResponses.failure(
                        statusOfReset(code), "The server reset the stream: RST_STREAM(" + name + ")", null));
    }

    /** The gRPC status of a stream the server reset (gRPC over HTTP/2, "Errors"). */
    static int statusOfReset(Http2ErrorCode code) {
        if (code == null) return GrpcStatus.INTERNAL;
        return switch (code) {
            case REFUSED_STREAM -> GrpcStatus.UNAVAILABLE;
            case CANCEL -> GrpcStatus.CANCELLED;
            case ENHANCE_YOUR_CALM -> GrpcStatus.RESOURCE_EXHAUSTED;
            case INADEQUATE_SECURITY -> GrpcStatus.PERMISSION_DENIED;
            default -> GrpcStatus.INTERNAL;
        };
    }

    private void onGoaway(Http2Frame.GoawayFrame frame) {
        var refused = new ArrayList<ClientStream>();
        lock.lock();
        try {
            draining = true;
            for (ClientStream stream : streams.values()) {
                if (stream.id > frame.lastStreamId()) refused.add(stream);
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
        Http2ErrorCode code = Http2ErrorCode.of(frame.errorCode());
        String name = code == null ? "0x" + Integer.toHexString(frame.errorCode()) : code.name();
        for (ClientStream stream : refused) {
            finish(
                    stream,
                    GrpcResponses.failure(
                            GrpcStatus.UNAVAILABLE,
                            "The server is going away (GOAWAY " + name + ") and did not process the call",
                            null));
        }
        lock.lock();
        try {
            if (reserved == 0) closeTransport();
        } finally {
            lock.unlock();
        }
    }

    /** Completes a call from its response; {@code trailers} is {@code null} for a trailers-only response. */
    private void complete(ClientStream stream, Headers trailers) {
        finish(
                stream,
                GrpcResponses.assemble(
                        stream.httpStatus, stream.headers, stream.body.toByteArray(), trailers, stream.maxMessageSize));
    }

    /** Ends a call the server answered wrongly, and resets its stream with {@code code}. */
    private void resetStream(ClientStream stream, Http2ErrorCode code, String why) throws IOException {
        finish(stream, GrpcResponses.failure(GrpcStatus.INTERNAL, why, null));
        writer.writeRstStream(stream.id, code);
    }

    /** Ends a call the client gives up on while reading it, and resets its stream with {@code RST_STREAM(CANCEL)}. */
    private void cancelByClient(ClientStream stream, GrpcResponse outcome) throws IOException {
        finish(stream, outcome);
        writer.writeRstStream(stream.id, Http2ErrorCode.CANCEL);
    }

    // -------------------------------------------------------------------------
    // Life cycle
    // -------------------------------------------------------------------------

    private void sendGoaway(Http2ErrorCode code) {
        try {
            writer.writeGoaway(0, code);
        } catch (IOException _) {
            // The connection is going down anyway.
        }
    }

    /** Closes the transport and fails every call still in flight with {@code failure}. */
    private void terminate(GrpcResponse failure) {
        List<ClientStream> left;
        lock.lock();
        try {
            closeTransport();
            left = new ArrayList<>(streams.values());
        } finally {
            lock.unlock();
        }
        for (ClientStream stream : left) {
            finish(stream, failure);
        }
    }

    /** Under the lock. */
    private void closeTransport() {
        if (closed) return;
        closed = true;
        changed.signalAll();
        closeQuietly(socket);
    }

    /** Closes the connection: the calls in flight end with {@code UNAVAILABLE}. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                draining = true;
            }
        } finally {
            lock.unlock();
        }
        sendGoaway(Http2ErrorCode.NO_ERROR);
        terminate(GrpcResponses.failure(GrpcStatus.UNAVAILABLE, "The gRPC client was closed", null));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException _) {
            // Nothing more to do.
        }
    }
}
