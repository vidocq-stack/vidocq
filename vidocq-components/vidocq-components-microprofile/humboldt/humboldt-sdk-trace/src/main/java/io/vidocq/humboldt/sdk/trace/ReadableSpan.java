package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.trace.data.SpanData;

/**
 * Read view of a span during and after its lifetime — used by
 * {@link io.vidocq.humboldt.sdk.trace.export.SpanProcessor SpanProcessor}
 * during the {@code onStart}/{@code onEnd} callbacks.
 */
public interface ReadableSpan {

    SpanContext getSpanContext();

    SpanContext getParentSpanContext();

    String getName();

    boolean hasEnded();

    /**
     * @return an immutable snapshot of the span's current state.
     */
    SpanData toSpanData();
}
