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
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.http.h2.HpackDecoder;
import io.vidocq.chappe.http.h2.HpackEncoder;
import io.vidocq.chappe.http.h2.Http2Frame;
import io.vidocq.chappe.http.h2.Http2FrameReader;
import io.vidocq.chappe.http.h2.Http2FrameWriter;
import io.vidocq.chappe.http.h2.Http2Settings;

/**
 * A scripted HTTP/2 server for the cases a real gRPC server does not produce on demand: resets, GOAWAY, split
 * header blocks, oversized or malformed responses. Each accepted connection runs {@link Script} on a virtual thread,
 * after the prefaces are exchanged; built on Chappe's own frame reader, writer and HPACK codec.
 */
final class FakeH2Server implements AutoCloseable {

    /** What the server does on one connection. */
    @FunctionalInterface
    interface Script {
        void run(Conn conn) throws Exception;
    }

    private final ServerSocket serverSocket;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
    final BlockingQueue<Conn> connections = new LinkedBlockingQueue<>();

    FakeH2Server(Script script) throws IOException {
        this(script, Http2Settings.serverDefaults(8_192));
    }

    FakeH2Server(Script script, Http2Settings settings) throws IOException {
        this(script, settings, new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), "http");
    }

    /**
     * A server over TLS, its certificate from {@code tls}; it offers {@code alpn} by ALPN, none when empty.
     */
    static FakeH2Server tls(Script script, SSLContext tls, String... alpn) throws IOException {
        return tls(InetAddress.getLoopbackAddress(), script, tls, alpn);
    }

    /** A server over TLS on {@code address}, as {@link #tls(Script, SSLContext, String...)}. */
    static FakeH2Server tls(InetAddress address, Script script, SSLContext tls, String... alpn) throws IOException {
        var socket = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 50, address);
        var parameters = socket.getSSLParameters();
        parameters.setApplicationProtocols(alpn);
        socket.setSSLParameters(parameters);
        return new FakeH2Server(script, Http2Settings.serverDefaults(8_192), socket, "https");
    }

    private final String scheme;

    private FakeH2Server(Script script, Http2Settings settings, ServerSocket listening, String scheme) {
        this.scheme = scheme;
        serverSocket = listening;
        Thread.ofVirtual().start(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    sockets.add(socket);
                    Thread.ofVirtual().start(() -> {
                        try {
                            var conn = new Conn(socket, settings);
                            connections.add(conn);
                            script.run(conn);
                        } catch (Exception | AssertionError e) {
                            failures.add(e);
                        }
                    });
                } catch (IOException _) {
                    return;
                }
            }
        });
    }

    URI uri() {
        return URI.create(scheme + "://127.0.0.1:" + serverSocket.getLocalPort());
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        for (Socket socket : sockets) socket.close();
    }

    /** One accepted connection. */
    static final class Conn {

        final Socket socket;
        final Http2FrameReader reader;
        final Http2FrameWriter writer;
        final HpackDecoder decoder = new HpackDecoder(4_096, 65_536);
        final HpackEncoder encoder = new HpackEncoder();
        final List<Http2Frame> seen = new CopyOnWriteArrayList<>();
        private final ReentrantLock seenLock = new ReentrantLock();
        private final Condition seenMore = seenLock.newCondition();

        Conn(Socket socket, Http2Settings settings) throws IOException {
            this.socket = socket;
            byte[] preface = socket.getInputStream().readNBytes(24);
            if (!new String(preface, StandardCharsets.US_ASCII).equals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")) {
                throw new IOException("bad preface");
            }
            reader = new Http2FrameReader(
                    ByteBuffer.allocate(64 * 1_024).flip(), Channels.newChannel(socket.getInputStream()));
            writer = new Http2FrameWriter(
                    ByteBuffer.allocate(32 * 1_024), Channels.newChannel(socket.getOutputStream()));
            writer.writeSettings(settings);
        }

        /** The next frame that is neither SETTINGS, WINDOW_UPDATE nor PING; those are acknowledged or kept. */
        Http2Frame next() throws IOException {
            while (true) {
                Http2Frame frame = reader.readFrame(16_384);
                if (frame == null) continue;
                seenLock.lock();
                try {
                    seen.add(frame);
                    seenMore.signalAll();
                } finally {
                    seenLock.unlock();
                }
                switch (frame) {
                    case Http2Frame.SettingsFrame s when !s.ack() -> writer.writeSettingsAck();
                    case Http2Frame.SettingsFrame _, Http2Frame.WindowUpdateFrame _, Http2Frame.PingFrame _ -> {
                        // kept in seen
                    }
                    default -> {
                        return frame;
                    }
                }
            }
        }

        /** Reads a whole request: its header fields (pseudo-header fields included) and its DATA. */
        Request request() throws IOException {
            Http2Frame frame = next();
            while (frame instanceof Http2Frame.RstStreamFrame) {
                frame = next(); // the reset of an earlier stream
            }
            if (!(frame instanceof Http2Frame.HeadersFrame headers)) {
                throw new IOException("expected HEADERS, got " + frame);
            }
            var fields = new LinkedHashMap<String, String>();
            decoder.decode(headers.headerBlock(), fields::put);
            var body = new ByteArrayOutputStream();
            boolean ended = headers.endStream();
            while (!ended) {
                Http2Frame more = next();
                if (more instanceof Http2Frame.DataFrame data) {
                    byte[] bytes = new byte[data.data().remaining()];
                    data.data().get(bytes);
                    body.writeBytes(bytes);
                    ended = data.endStream();
                } else {
                    throw new IOException("expected DATA, got " + more);
                }
            }
            return new Request(headers.streamId(), fields, body.toByteArray());
        }

        void headers(int streamId, int status, Headers fields, boolean endStream) throws IOException {
            writer.writeHeaderBlock(streamId, () -> encoder.encode(status, fields), endStream);
        }

        void trailers(int streamId, Headers fields) throws IOException {
            writer.writeHeaderBlock(streamId, () -> encoder.encodeTrailers(fields), true);
        }

        void data(int streamId, byte[] bytes, boolean endStream) throws IOException {
            writer.writeData(streamId, bytes, 0, bytes.length, endStream);
        }

        /** A whole OK response with one message. */
        void ok(int streamId, byte[] message) throws IOException {
            headers(streamId, 200, Headers.of("content-type", "application/grpc"), false);
            data(streamId, GrpcResponses.frame(message), false);
            trailers(streamId, Headers.of("grpc-status", "0"));
        }

        /** Waits for the client to close the connection, keeping what it sends. */
        void drain() {
            try {
                while (true) next();
            } catch (IOException _) {
                // closed
            }
        }

        /** Waits until a frame that {@code match} accepts has been read, for at most {@code seconds}. */
        boolean awaitSeen(Predicate<Http2Frame> match, int seconds) throws InterruptedException {
            seenLock.lock();
            try {
                long remaining = TimeUnit.SECONDS.toNanos(seconds);
                while (seen.stream().noneMatch(match)) {
                    if (remaining <= 0) return false;
                    remaining = seenMore.awaitNanos(remaining);
                }
                return true;
            } finally {
                seenLock.unlock();
            }
        }

        /**
         * The next frame within {@code millis}, as {@link #next()}, or {@code null} if none arrives. Used to check
         * that the client sends nothing.
         */
        Http2Frame poll(int millis) throws IOException {
            socket.setSoTimeout(millis);
            try {
                return next();
            } catch (java.net.SocketTimeoutException _) {
                return null;
            } finally {
                socket.setSoTimeout(0);
            }
        }

        /** Waits until a frame of {@code type} arrives on {@code streamId}, for at most {@code seconds}. */
        Http2Frame await(Class<? extends Http2Frame> type, int streamId, int seconds) throws IOException {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(seconds));
            while (true) {
                Http2Frame frame = next();
                if (type.isInstance(frame) && frame.streamId() == streamId) return frame;
            }
        }
    }

    record Request(int streamId, Map<String, String> fields, byte[] body) {}
}
