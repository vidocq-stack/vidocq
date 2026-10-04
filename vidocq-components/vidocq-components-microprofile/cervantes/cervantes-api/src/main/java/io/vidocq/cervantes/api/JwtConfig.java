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
package io.vidocq.cervantes.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * JWT verification configuration (subset of MicroProfile Config properties)
 * {@code mp.jwt.verify.*} relevant for validating claims).
 *
 * <ul>
 * <li>{@code issuer} — {@code mp.jwt.verify.issuer}: expected issuer ({@code iss}). If present,
 * the token's {@code iss} claim must match exactly.
 * <li>{@code audiences} — {@code mp.jwt.verify.audiences}: accepted audiences. If not empty,
 * the intersection with the token's {@code aud} claim must be non-empty.
 * <li>{@code clockSkew} — clock tolerance applied to {@code exp} and {@code nbf}.
 * <li>{@code requireExpiration} — if true, the absence of {@code exp} is a failure (spec default).</li>
 * <li>{@code tokenAge} — {@code mp.jwt.verify.token.age}: maximum token age in seconds
 * ({@code now - iat <= tokenAge}). No age limit if absent.</li>
 * <li>{@code encryptionRequired} — if true (when {@code mp.jwt.decrypt.key*} is configured),
 * unencrypted JWS must be rejected with 401.</li>
 * <li>{@code requiredAlgorithm} — {@code mp.jwt.verify.publickey.algorithm}: if present, only tokens
 * signed with this algorithm family are accepted; empty = RS256 and ES256 both accepted (MP JWT 2.2).</li>
 * </ul>
 *
 * <p>Immutable record; {@code audiences} is defensively copied. Construction from
 * MicroProfile Config (Ravel) is wired at the M3 milestone.
 */
public record JwtConfig(
        Optional<String> issuer,
        Set<String> audiences,
        Duration clockSkew,
        boolean requireExpiration,
        Optional<Long> tokenAge,
        boolean encryptionRequired,
        Optional<SignatureAlgorithm> requiredAlgorithm) {

    /** Default clock skew tolerance (60 s), aligned with common MP JWT implementation behavior. */
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);

    public JwtConfig {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(clockSkew, "clockSkew");
        Objects.requireNonNull(tokenAge, "tokenAge");
        Objects.requireNonNull(requiredAlgorithm, "requiredAlgorithm");
        if (clockSkew.isNegative()) throw new IllegalArgumentException("clockSkew must be >= 0");
        audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
    }

    /** Configuration requiring only an issuer (free audiences, default skew, expiration required). */
    public static JwtConfig forIssuer(String issuer) {
        return new JwtConfig(Optional.ofNullable(issuer), Set.of(), DEFAULT_CLOCK_SKEW, true,
                Optional.empty(), false, Optional.empty());
    }

    /** Configuration with issuer + audiences (default skew, expiration required). */
    public static JwtConfig of(String issuer, Set<String> audiences) {
        return new JwtConfig(Optional.ofNullable(issuer), audiences, DEFAULT_CLOCK_SKEW, true,
                Optional.empty(), false, Optional.empty());
    }
}
