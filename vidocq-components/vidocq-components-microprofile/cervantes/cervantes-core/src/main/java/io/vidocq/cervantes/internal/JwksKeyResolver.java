package io.vidocq.cervantes.internal;

import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link KeyResolver} adossé à un JWK Set distant ou local, avec cache et rafraîchissement.
 *
 * <p>Résolution par {@code kid}. Le set est mis en cache et rafraîchi : (1) périodiquement quand
 * le snapshot dépasse {@code refreshInterval}, et (2) à la demande quand un {@code kid} inconnu est
 * présenté (rotation de clés) — borné par {@code minRefreshInterval} pour éviter l'effet de troupeau
 * sur des {@code kid} inexistants. Un échec de rafraîchissement quand un snapshot existe déjà est
 * toléré (on conserve l'ancien) ; seul l'échec du tout premier chargement est propagé.</p>
 *
 * <p>Concurrence : {@link ReentrantLock} (VT-friendly, pas de pinning contrairement à
 * {@code synchronized}) autour du rafraîchissement, avec double-vérification ; le snapshot est
 * publié atomiquement via {@link AtomicReference}.</p>
 */
public final class JwksKeyResolver implements KeyResolver {

    private record Snapshot(Jwks jwks, Instant fetchedAt) {}

    private final JwksSource source;
    private final Duration refreshInterval;
    private final Duration minRefreshInterval;
    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public JwksKeyResolver(JwksSource source) {
        this(source, Duration.ofMinutes(5), Duration.ofSeconds(15), Clock.systemUTC());
    }

    public JwksKeyResolver(JwksSource source, Duration refreshInterval, Duration minRefreshInterval, Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval");
        this.minRefreshInterval = Objects.requireNonNull(minRefreshInterval, "minRefreshInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException {
        Snapshot current = snapshot.get();
        if (current == null) {
            current = refresh(null); // premier chargement : un échec est propagé
        } else if (age(current).compareTo(refreshInterval) >= 0) {
            current = tryRefresh(current); // TTL dépassé : best-effort
        }

        PublicKey key = lookup(current.jwks(), kid);
        if (key == null && kid != null && age(current).compareTo(minRefreshInterval) >= 0) {
            current = tryRefresh(current); // kid inconnu → rotation possible
            key = lookup(current.jwks(), kid);
        }
        return Optional.ofNullable(key);
    }

    private PublicKey lookup(Jwks jwks, String kid) {
        if (kid != null) {
            return jwks.byKid().get(kid);
        }
        // Token sans kid : autorisé seulement si le set ne contient qu'une clé.
        return jwks.all().size() == 1 ? jwks.all().get(0) : null;
    }

    private Snapshot tryRefresh(Snapshot stale) {
        try {
            return refresh(stale);
        } catch (JwtValidationException e) {
            return snapshot.get(); // conserve le snapshot existant en cas d'échec réseau transitoire
        }
    }

    private Snapshot refresh(Snapshot stale) throws JwtValidationException {
        lock.lock();
        try {
            Snapshot current = snapshot.get();
            // Un autre thread a peut-être rafraîchi pendant l'attente du lock.
            if (current != null && current != stale && age(current).compareTo(minRefreshInterval) < 0) {
                return current;
            }
            Jwks jwks = JwkParser.parse(source.fetch());
            Snapshot fresh = new Snapshot(jwks, clock.instant());
            snapshot.set(fresh);
            return fresh;
        } finally {
            lock.unlock();
        }
    }

    private Duration age(Snapshot s) {
        return Duration.between(s.fetchedAt(), clock.instant());
    }
}
