package io.vidocq.humboldt.sdk.trace.export;

import io.vidocq.humboldt.sdk.common.CompletableResultCode;

import io.vidocq.humboldt.sdk.trace.data.SpanData;

import java.util.Collection;

/**
 * Exporter de spans terminés vers une destination (in-memory pour tests,
 * stdout pour dev, OTLP HTTP/protobuf pour la prod en M3, etc.).
 *
 * <p>Toutes les méthodes doivent être thread-safe — un même exporter peut être
 * partagé entre {@code SimpleSpanProcessor} et {@code BatchSpanProcessor}.</p>
 */
public interface SpanExporter extends AutoCloseable {

    /**
     * Export d'un batch de spans terminés.
     *
     * @param spans collection immutable
     * @return résultat asynchrone — succès si tous les spans ont été pris en charge
     */
    CompletableResultCode export(Collection<SpanData> spans);

    /**
     * Force le flush des buffers internes éventuels.
     */
    CompletableResultCode flush();

    /**
     * Libère les ressources (sockets, threads, fichiers, etc.).
     */
    CompletableResultCode shutdown();

    @Override
    default void close() {
        shutdown().join(10, java.util.concurrent.TimeUnit.SECONDS);
    }
}
