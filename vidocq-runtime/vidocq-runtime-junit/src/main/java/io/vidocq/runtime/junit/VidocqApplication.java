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
package io.vidocq.runtime.junit;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The application a {@link VidocqTest} class runs against: a JVM of its own, listening on the loopback address.
 * A test method declares it as a parameter for its URL, its output, or to send it requests; {@link VidocqUrl} gives
 * the URL alone.
 *
 * <p>The requests go through this jar because a test that Surefire or the IDE patches into the application module
 * reads what that module reads, and an application module does not read {@code java.net.http}.</p>
 */
public final class VidocqApplication implements AutoCloseable {

    private static final Duration POLL = Duration.ofMillis(100);
    private static final Duration STOP_GRACE = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** How long a failed start waits for the last lines of the process's output. */
    private static final Duration DRAIN = Duration.ofSeconds(5);

    /**
     * {@code rw-------} where the default file system has POSIX permissions, none elsewhere (Windows), where the file
     * system's own defaults apply. NIO already creates a temporary file so; the permissions are spelled out so the
     * shared temporary directory is visibly used safely.
     */
    private static final FileAttribute<?>[] OWNER_ONLY =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? new FileAttribute<?>[] {
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                    : new FileAttribute<?>[0];

    private final Process process;
    private final URI url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final Output output;
    private final Path portFile;

    private VidocqApplication(Process process, URI url, Output output, Path portFile) {
        this.process = process;
        this.url = url;
        this.output = output;
        this.portFile = portFile;
    }

    /**
     * Creates an empty port file, runs the {@code command} that names it, its output in memory, and returns once the
     * application wrote a port to that file: its {@code default} listener listens on that port. The listener binds
     * port {@code 0} ({@link ApplicationLaunch#command}), so the system picks the port as the listener binds it, and
     * no other process can take it first, as it could a port picked here, then closed (BUG-20261114-09). A file, not a
     * line of the log, carries the port: whatever the logging configuration of the application, it starts. Fails with
     * the output when the process exits first, or when {@code timeout} runs out, after stopping it. The file is
     * deleted then, or by {@link #close()}.
     */
    static VidocqApplication start(Function<Path, List<String>> command, Duration timeout) throws IOException {
        Path portFile = Files.createTempFile("vidocq-test-", ".port", OWNER_ONLY);
        Process process;
        try {
            process = new ProcessBuilder(command.apply(portFile)).redirectErrorStream(true).start();
        } catch (IOException | RuntimeException e) {
            delete(portFile);
            throw e;
        }
        Output output = new Output();
        Thread reader = Thread.ofVirtual().name("vidocq-test-output").start(() -> read(process, output));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            OptionalInt port = port(portFile);
            while (port.isEmpty()) {
                if (process.waitFor(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
                    reader.join(DRAIN);
                    throw new IllegalStateException("The application stopped with exit code " + process.exitValue()
                            + " before it listened. Its output:\n" + output.text());
                }
                if (System.nanoTime() > deadline) {
                    throw new IllegalStateException("The application did not listen within " + timeout.toSeconds()
                            + " s: the start waits for its default Chappe listener. Its output:\n" + output.text());
                }
                port = port(portFile);
            }
            return new VidocqApplication(process, URI.create("http://127.0.0.1:" + port.getAsInt()), output,
                    portFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop(process, portFile);
            throw new IllegalStateException("Interrupted while the application started", e);
        } catch (RuntimeException e) {
            stop(process, portFile);
            throw e;
        }
    }

    /**
     * The port the listener wrote to {@code portFile}; empty until then, the file being created empty. The listener
     * replaces the file in one move, so the port is never read in part.
     */
    private static OptionalInt port(Path portFile) {
        try {
            String port = Files.readString(portFile).strip();
            return port.isEmpty() ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(port));
        } catch (IOException _) {
            // not readable while it is replaced, on some systems: the next poll reads it
            return OptionalInt.empty();
        }
    }

    /** {@code http://127.0.0.1:<port>}. */
    public URI url() {
        return url;
    }

    /** {@code path} on the application, with or without its leading {@code /}. */
    public URI url(String path) {
        return url.resolve(path.startsWith("/") ? path : "/" + path);
    }

    /** {@code GET path}. */
    public VidocqResponse get(String path) {
        return request("GET", path, null, null);
    }

    /** {@code GET path} with {@code headers}, an {@code Authorization: Bearer …} for one. */
    public VidocqResponse get(String path, Map<String, String> headers) {
        return request("GET", path, headers, null, null);
    }

    /** {@code POST path} with {@code body} of type {@code contentType}. */
    public VidocqResponse post(String path, String contentType, String body) {
        return request("POST", path, contentType, body);
    }

    /**
     * {@code method path}, with {@code body} of type {@code contentType} when {@code body} is not {@code null}.
     * The body of the answer is read as UTF-8.
     */
    public VidocqResponse request(String method, String path, String contentType, String body) {
        return request(method, path, Map.of(), contentType, body);
    }

    /**
     * {@code method path} with {@code headers}, and {@code body} of type {@code contentType} when {@code body} is not
     * {@code null}. A test cannot send a request itself: patched into the application module, it does not read
     * {@code java.net.http} (BUG-20261030-18).
     *
     * @throws IllegalArgumentException for a header the HTTP client sets itself ({@code Host}, {@code Connection}…)
     */
    public VidocqResponse request(String method, String path, Map<String, String> headers, String contentType,
                                  String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path)).timeout(REQUEST_TIMEOUT)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        if (body != null && contentType != null) {
            request.header("Content-Type", contentType);
        }
        try {
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, List<String>> answered = new TreeMap<>();
            response.headers().map().forEach((name, values) -> answered.put(name.toLowerCase(Locale.ROOT), values));
            return new VidocqResponse(response.statusCode(), response.body(), answered);
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + url(path) + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during " + method + " " + url(path), e);
        }
    }

    /** What the application wrote so far, on its output and error streams. */
    public String log() {
        return output.text();
    }

    private static void read(Process process, Output output) {
        char[] buffer = new char[8192];
        try (Reader in = process.inputReader()) {
            for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
                output.append(buffer, n);
            }
        } catch (IOException _) {
            // the process ended: its output is closed
        }
    }

    /** Whether the process still runs. */
    public boolean isAlive() {
        return process.isAlive();
    }

    /** Stops the application, as Ctrl+C would, then forcibly after a grace period, and deletes its port file. */
    @Override
    public void close() {
        http.close();
        stop(process, portFile);
    }

    private static void stop(Process process, Path portFile) {
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            delete(portFile);
        }
    }

    private static void delete(Path portFile) {
        try {
            Files.deleteIfExists(portFile);
        } catch (IOException _) {
            // left in the temporary directory, where it harms nothing
        }
    }

    /** The process's output and error streams, kept in memory: no temporary file to share or clean. */
    private static final class Output {

        private final StringBuilder text = new StringBuilder();

        synchronized void append(char[] chars, int length) {
            text.append(chars, 0, length);
        }

        synchronized String text() {
            return text.toString();
        }
    }
}
