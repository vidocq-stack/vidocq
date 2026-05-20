package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import io.opentelemetry.context.Context;
import io.vidocq.humboldt.sdk.trace.ReadableSpan;

/**
 * Hook appelé par le SDK à la création et à la fin d'un span enregistré.
 *
 * <p>Implémentations standard :</p>
 * <ul>
 *   <li>{@link io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor} — export synchrone</li>
 *   <li>{@link io.vidocq.humboldt.sdk.trace.BatchSpanProcessor} — batch + virtual thread</li>
 * </ul>
 */
public interface SpanProcessor extends AutoCloseable {

    void onStart(Context parentContext, ReadableSpan span);

    void onEnd(ReadableSpan span);

    boolean isStartRequired();

    boolean isEndRequired();

    default CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
