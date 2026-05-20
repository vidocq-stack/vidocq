package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exporter qui logue chaque span via {@link System#getLogger(String)} — destiné
 * au développement et au debug local. Format human-readable, pas de structured logging.
 */
public final class LoggingSpanExporter implements SpanExporter {

    private static final Logger LOG = System.getLogger(LoggingSpanExporter.class.getName());
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    public static LoggingSpanExporter create() {
        return new LoggingSpanExporter();
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (isStopped.get()) return CompletableResultCode.ofFailure();
        for (SpanData s : spans) {
            long durationNs = s.endEpochNanos() - s.startEpochNanos();
            LOG.log(Level.INFO,
                    "Span name={0} kind={1} traceId={2} spanId={3} parentSpanId={4} duration={5} status={6} attrs={7}",
                    s.name(),
                    s.kind(),
                    s.spanContext().getTraceId(),
                    s.spanContext().getSpanId(),
                    s.parentSpanContext() != null ? s.parentSpanContext().getSpanId() : "(root)",
                    Duration.ofNanos(durationNs),
                    s.status().code(),
                    s.attributes());
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        isStopped.set(true);
        return CompletableResultCode.ofSuccess();
    }
}
