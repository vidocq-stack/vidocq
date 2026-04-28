package io.vidocq.mpserver.ext.chappe;

import java.util.Objects;

/**
 * Description d'un listener Chappe (un couple host:port, avec ou sans TLS).
 * <p>
 * Chappe alloue une instance {@link io.vidocq.chappe.api.Server} par listener.
 * Les extensions contributrices identifient le listener par son {@link #name()}.
 * </p>
 *
 * @param name  nom logique (ex. {@code default}, {@code admin})
 * @param host  hôte d'écoute
 * @param port  port d'écoute
 * @param tls   {@code true} pour activer TLS (réservé — non implémenté dans ce jalon)
 */
public record ChappeListener(String name, String host, int port, boolean tls) {

    /** Nom du listener par défaut. */
    public static final String DEFAULT = "default";

    public ChappeListener {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        if (name.isBlank()) {
            throw new IllegalArgumentException("listener name must not be blank");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
    }

    /** Listener HTTP simple. */
    public static ChappeListener http(String name, String host, int port) {
        return new ChappeListener(name, host, port, false);
    }
}
