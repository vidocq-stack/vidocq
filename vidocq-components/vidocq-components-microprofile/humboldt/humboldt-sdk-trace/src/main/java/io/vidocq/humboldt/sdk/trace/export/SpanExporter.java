package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;

/**
 * Exporter of completed spans to a destination (in-memory for tests,
 * stdout for development, OTLP HTTP/protobuf for production in M3, etc.).
 *
 * <p>All methods must be thread-safe — the same exporter may be shared
 * between {@code SimpleSpanProcessor} and {@code BatchSpanProcessor}.</p>
 */
public interface SpanExporter extends AutoCloseable {

    /**
     * Exports a batch of completed spans.
     *
     * @param spans immutable collection
     * @return asynchronous result — successful if all spans were handled
     */
    CompletableResultCode export(Collection<SpanData> spans);

    /**
     * Forces a flush of any internal buffers.
     */
    CompletableResultCode flush();

    /**
     * Releases resources (sockets, threads, files, etc.).
     */
    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
