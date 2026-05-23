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
 * Exporter qui écrit chaque {@link LogRecordData} sur une ligne texte vers un
 * {@link OutputStream} (stdout par défaut, ou un fichier).
 * <p>
 * Format : {@code <YYYY-MM-DD HH:MM:SS.fffZ> <SEVERITY_TEXT> <body> scopeInfo:<scope>:<version>}
 * — compatible avec la fixture MP Telemetry Logs TCK ({@code JulTest})
 * qui matche les lignes via regex {@code .*INFO.*<msg>.*scopeInfo:.*}.
 *
 * <p>Utilisé quand la config {@code OTEL_LOGS_EXPORTER=logging} est active.
 * Le path du fichier peut être :
 * <ul>
 *   <li>fourni explicitement via {@link #toFile(Path)}</li>
 *   <li>résolu depuis la system property {@code mptelemetry.tck.log.file.path}
 *       (utilisée par le TCK)</li>
 *   <li>{@code System.out} en fallback ({@link #toStdout()})</li>
 * </ul>
 *
 * <p>Écritures synchronisées par {@link ReentrantLock} pour éviter les lignes
 * entrelacées en présence de plusieurs threads (le {@code SimpleLogRecordProcessor}
 * peut être appelé concurremment depuis des virtual threads applicatifs).
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

    /** Exporte vers {@link System#out}. */
    public static LoggingLogRecordExporter toStdout() {
        return new LoggingLogRecordExporter(stdoutWriter(), false);
    }

    /**
     * Exporte vers un fichier. Crée le fichier s'il n'existe pas, ouvre en append sinon.
     * Le parent doit exister.
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
     * Construit l'exporter en lisant la system property {@code mptelemetry.tck.log.file.path} :
     * si présente, écrit dans ce fichier ; sinon écrit sur {@link System#out}.
     * <p>
     * C'est la factory utilisée par {@code HumboldtAutoConfigure} pour le cas
     * {@code OTEL_LOGS_EXPORTER=logging}.
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
