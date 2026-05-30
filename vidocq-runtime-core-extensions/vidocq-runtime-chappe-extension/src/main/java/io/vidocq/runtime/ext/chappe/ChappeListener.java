package io.vidocq.runtime.ext.chappe;

import java.util.Objects;

/**
 * Description of a Chappe listener (a host:port pair, with or without TLS).
 * <p>
 * Chappe allocates one {@link io.vidocq.chappe.api.Server} instance per listener.
 * Contributing extensions identify the listener by its {@link #name()}.
 * </p>
 *
 * @param name logical name (e.g. {@code default}, {@code admin})
 * @param host listening host
 * @param port listening port
 * @param tls {@code true} to enable TLS (reserved — not implemented in this milestone)
 */
public record ChappeListener(String name, String host, int port, boolean tls) {

    /** Default listener name. */
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

    /** Simple HTTP listener. */
    public static ChappeListener http(String name, String host, int port) {
        return new ChappeListener(name, host, port, false);
    }
}
