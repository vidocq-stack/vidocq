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
package io.vidocq.humboldt.sdk.log.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.log.data.LogRecordData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exporter that writes each {@link LogRecordData} as one text line to an
 * {@link OutputStream} (stdout by default, or a file).
 * <p>
 * Format: {@code <YYYY-MM-DD HH:MM:SS.fffZ> <SEVERITY_TEXT> <body> scopeInfo:<scope>:<version>}
 * — compatible with the MP Telemetry Logs TCK fixture ({@code JulTest})
 * which matches lines via the regex {@code .*INFO.*<msg>.*scopeInfo:.*}.
 *
 * <p>Used when {@code OTEL_LOGS_EXPORTER=logging} is active.
 * The file path may be:
 * <ul>
 *   <li>provided explicitly via {@link #toFile(Path)}</li>
 *   <li>resolved from the system property {@code mptelemetry.tck.log.file.path}
 *       (used by the TCK)</li>
 *   <li>{@code System.out} as a fallback ({@link #toStdout()})</li>
 * </ul>
 *
 * <p>Writes are synchronized with {@link ReentrantLock} to avoid interleaved lines
 * in the presence of multiple threads ({@code SimpleLogRecordProcessor}
 * may be called concurrently from application virtual threads).
 */
public final class LoggingLogRecordExporter implements LogRecordExporter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final Writer writer;
    private final boolean closeOnShutdown;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    private LoggingLogRecordExporter(Writer writer, boolean closeOnShutdown) {
        this.writer = writer;
        this.closeOnShutdown = closeOnShutdown;
    }

    /** Exports to {@link System#out}. */
    public static LoggingLogRecordExporter toStdout() {
        return new LoggingLogRecordExporter(stdoutWriter(), false);
    }

    /**
     * Exports to a file. Creates the file if it does not exist, otherwise opens it in append mode.
     * The parent directory must exist.
     */
    public static LoggingLogRecordExporter toFile(Path path) {
        try {
            OpenOption[] opts = new OpenOption[] {
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
            };
            BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8, opts);
            return new LoggingLogRecordExporter(bw, true);
        } catch (IOException e) {
            throw new RuntimeException("Cannot open log file " + path, e);
        }
    }

    /**
     * Builds the exporter by reading the system property {@code mptelemetry.tck.log.file.path}:
     * if present, writes to that file; otherwise writes to {@link System#out}.
     * <p>
     * This is the factory used by {@code HumboldtAutoConfigure} for the
     * {@code OTEL_LOGS_EXPORTER=logging} case.
     */
    public static LoggingLogRecordExporter create() {
        String path = System.getProperty("mptelemetry.tck.log.file.path");
        if (path != null && !path.isBlank()) {
            return toFile(Path.of(path));
        }
        return toStdout();
    }

    @Override
    public CompletableResultCode export(Collection<LogRecordData> records) {
        if (closed) return CompletableResultCode.ofFailure();
        writeLock.lock();
        try {
            for (LogRecordData r : records) {
                writer.write(formatLine(r));
                writer.write(System.lineSeparator());
            }
            writer.flush();
            return CompletableResultCode.ofSuccess();
        } catch (IOException _) {
            return CompletableResultCode.ofFailure();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public CompletableResultCode flush() {
        if (closed) return CompletableResultCode.ofFailure();
        writeLock.lock();
        try {
            writer.flush();
            return CompletableResultCode.ofSuccess();
        } catch (IOException _) {
            return CompletableResultCode.ofFailure();
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        if (closed) return CompletableResultCode.ofSuccess();
        closed = true;
        if (!closeOnShutdown) return CompletableResultCode.ofSuccess();
        writeLock.lock();
        try {
            writer.flush();
            writer.close();
            return CompletableResultCode.ofSuccess();
        } catch (IOException _) {
            return CompletableResultCode.ofFailure();
        } finally {
            writeLock.unlock();
        }
    }

    private static String formatLine(LogRecordData r) {
        String ts = TIMESTAMP_FMT.format(Instant.ofEpochSecond(0, r.observedEpochNanos()));
        String level = !r.severityText().isEmpty() ? r.severityText() : r.severity().name();
        String scopeName = r.scope() != null ? r.scope().name() : "";
        String scopeVersion = (r.scope() != null && r.scope().version() != null) ? r.scope().version() : "";
        return ts + " " + level + " " + r.body() + " scopeInfo:" + scopeName + ":" + scopeVersion;
    }

    private static Writer stdoutWriter() {
        PrintStream out = System.out;
        return new OutputStreamWriter(new OutputStream() {
            @Override
            public void write(int b) {
                out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                out.write(b, off, len);
            }

            @Override
            public void flush() {
                out.flush();
            }
        }, StandardCharsets.UTF_8);
    }
}
