/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
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
 * {@link KeyResolver} backed by a remote or local JWK Set, with cache and refreshment.
 *
 * <p>Resolution by {@code kid}. The set is cached and refreshed: (1) periodically when
 * the snapshot exceeds {@code refreshInterval}, and (2) on request when an unknown {@code kid} is
 * presented (key rotation) — bounded by {@code minRefreshInterval} to avoid herd effect
 * on non-existent {@code kid}. A refresh failure when a snapshot already exists is
 * tolerated (the old one is preserved); Only the failure of the very first load is spread. </p>
 *
 * <p>Competition: {@link ReentrantLock} (VT-friendly, no pinning unlike
 * {@code synchronized}) around the cooling, with double verification; The snapshot is
 * published atomicly via ZZPH0ZZ.ZZPH1ZZ
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
            current = refresh(null); //first load: failure spreads
        } else if (age(current).compareTo(refreshInterval) >= 0) {
            current = tryRefresh(current); //TTL exceeded: best effort
        }

        PublicKey key = lookup(current.jwks(), kid);
        if (key == null && kid != null && age(current).compareTo(minRefreshInterval) >= 0) {
            current = tryRefresh(current); //unknown kid → possible rotation
            key = lookup(current.jwks(), kid);
        }
        return Optional.ofNullable(key);
    }

    private PublicKey lookup(Jwks jwks, String kid) {
        if (kid != null) {
            return jwks.byKid().get(kid);
        }
        //Token without kid: allowed only if the set contains only one key.
        return jwks.all().size() == 1 ? jwks.all().get(0) : null;
    }

    private Snapshot tryRefresh(Snapshot stale) {
        try {
            return refresh(stale);
        } catch (JwtValidationException e) {
            return snapshot.get(); //keeps the existing snapshot in case of transient network failure
        }
    }

    private Snapshot refresh(Snapshot stale) throws JwtValidationException {
        lock.lock();
        try {
            Snapshot current = snapshot.get();
            //Another thread may have refreshed while waiting for the lock.
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
