/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.humboldt.sdk.metric.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;
import io.vidocq.humboldt.sdk.metric.data.MetricData;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exporter qui écrit chaque {@link MetricData} sur une ligne texte vers un
 * {@link OutputStream} (stdout par défaut, ou un fichier).
 *
 * <p>Format : {@code <name> <description> <unit> <instrumentType>} — compatible
 * avec la fixture MP Telemetry Metrics TCK ({@code JvmMemoryTest}, {@code JvmCpuTest},
 * etc.) qui matche les lignes via {@code String.contains(name+description+unit+type)}.</p>
 *
 * <p>Utilisé quand la config {@code OTEL_METRICS_EXPORTER=logging} est active.
 * Le path du fichier peut être :</p>
 * <ul>
 *   <li>fourni explicitement via {@link #toFile(Path)}</li>
 *   <li>résolu depuis la system property {@code mptelemetry.tck.log.file.path}
 *       (utilisée par le TCK Metrics)</li>
 *   <li>{@code System.out} en fallback ({@link #toStdout()})</li>
 * </ul>
 */
public final class LoggingMetricExporter implements MetricExporter {

    private final Writer writer;
    private final boolean closeOnShutdown;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    private LoggingMetricExporter(Writer writer, boolean closeOnShutdown) {
        this.writer = writer;
        this.closeOnShutdown = closeOnShutdown;
    }

    public static LoggingMetricExporter toStdout() {
        Writer w = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        return new LoggingMetricExporter(w, false);
    }

    public static LoggingMetricExporter toFile(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Writer w = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                    StandardCharsets.UTF_8));
            return new LoggingMetricExporter(w, true);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Fabrique par défaut utilisée par {@code HumboldtAutoConfigure} quand
     * {@code OTEL_METRICS_EXPORTER=logging}. Lit la system property
     * {@code mptelemetry.tck.log.file.path} ; si absente, écrit sur stdout.
     */
    public static LoggingMetricExporter create() {
        String path = System.getProperty("mptelemetry.tck.log.file.path");
        if (path == null || path.isEmpty()) return toStdout();
        return toFile(Path.of(path));
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metrics) {
        if (closed) return CompletableResultCode.ofFailure();
        writeLock.lock();
        try {
            for (MetricData m : metrics) {
                // Format aligné sur l'attente TCK MP Telemetry Metrics
                // (MetricsReader.assertLogMessage cherche
                // "name=X, description=Y, unit=Z, type=W" via String.contains).
                String line = "name=" + m.name()
                        + ", description=" + m.description()
                        + ", unit=" + m.unit()
                        + ", type=" + toMetricDataType(m);
                writer.write(line);
                writer.write('\n');
            }
            writer.flush();
            return CompletableResultCode.ofSuccess();
        } catch (IOException e) {
            return CompletableResultCode.ofFailure();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Mappe humboldt InstrumentType vers le {@code MetricDataType.toString()} attendu
     * par les fixtures TCK. Les noms OTel SDK : LONG_SUM, DOUBLE_SUM, HISTOGRAM,
     * EXPONENTIAL_HISTOGRAM, SUMMARY, LONG_GAUGE, DOUBLE_GAUGE.
     */
    private static String toMetricDataType(MetricData m) {
        boolean isDouble = !m.points().isEmpty()
                && m.points().get(0) instanceof io.vidocq.humboldt.sdk.metric.data.DoublePointData;
        return switch (m.instrumentType()) {
            case COUNTER, UP_DOWN_COUNTER, OBSERVABLE_COUNTER, OBSERVABLE_UP_DOWN_COUNTER ->
                    isDouble ? "DOUBLE_SUM" : "LONG_SUM";
            case HISTOGRAM -> "HISTOGRAM";
            case GAUGE, OBSERVABLE_GAUGE -> isDouble ? "DOUBLE_GAUGE" : "LONG_GAUGE";
        };
    }

    @Override
    public CompletableResultCode flush() {
        if (closed) return CompletableResultCode.ofFailure();
        writeLock.lock();
        try { writer.flush(); return CompletableResultCode.ofSuccess(); }
        catch (IOException e) { return CompletableResultCode.ofFailure(); }
        finally { writeLock.unlock(); }
    }

    @Override
    public CompletableResultCode shutdown() {
        if (closed) return CompletableResultCode.ofSuccess();
        closed = true;
        writeLock.lock();
        try {
            writer.flush();
            if (closeOnShutdown) writer.close();
            return CompletableResultCode.ofSuccess();
        } catch (IOException e) {
            return CompletableResultCode.ofFailure();
        } finally {
            writeLock.unlock();
        }
    }
}
