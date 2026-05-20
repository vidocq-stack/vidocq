package io.vidocq.humboldt.sdk.trace;

import io.opentelemetry.api.trace.SpanContext;
import io.vidocq.humboldt.sdk.trace.data.SpanData;

/**
 * Vue lecture d'un span pendant et après sa vie — utilisée par les
 * {@link io.vidocq.humboldt.sdk.trace.export.SpanProcessor SpanProcessor}
 * lors du callback {@code onStart}/{@code onEnd}.
 */
public interface ReadableSpan {

    SpanContext getSpanContext();

    SpanContext getParentSpanContext();

    String getName();

    boolean hasEnded();

    /**
     * @return un snapshot immutable de l'état actuel du span.
     */
    SpanData toSpanData();
}
