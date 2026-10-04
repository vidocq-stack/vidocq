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

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public manufacture of {@link KeyResolver} from the JWT MicroProfile configuration
 * ({@code mp.jwt.verify.publickey} / {@code mp.jwt.verify.publickey.location}). Stable facade for integrations
 * (CDI, JAX-RS) : garde {@code JwksSource}/{@code JwkParser} internes au package core.
 */
public final class KeyResolvers {

    private KeyResolvers() {}

    /**
     * Inline public key from {@code mp.jwt.verify.publickey}.
     * Accepts: PEM (PKIX), JWK JSON ({...}), JWK Set JSON ({"keys":[...]}), or base64-encoded JWK/JWKS.
     * MP JWT spec §9.2.1: the value can be a PEM key, base64 X.509, JWK or JWKS.
     */
    public static KeyResolver fromInlinePem(String value, Optional<SignatureAlgorithm.Family> family) throws JwtValidationException {
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            //JWK or JWKS JSON inline — route to JwksKeyResolver with a static byte begging
            byte[] bytes = trimmed.getBytes(StandardCharsets.UTF_8);
            return new JwksKeyResolver(() -> bytes);
        }
        // Try base64 decode: if the decoded content is JSON (starts with '{'), treat as JWK/JWKS
        if (!trimmed.startsWith("-----")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(trimmed.replaceAll("\\s", ""));
                String decodedStr = new String(decoded, StandardCharsets.UTF_8).trim();
                if (decodedStr.startsWith("{")) {
                    byte[] bytes = decodedStr.getBytes(StandardCharsets.UTF_8);
                    return new JwksKeyResolver(() -> bytes);
                }
            } catch (IllegalArgumentException ignored) {
                //Not valid base64 — fall through to PEM parsing
            }
        }
        return new ConfiguredKeyResolver(parsePem(trimmed, family));
    }

    /**
     * From {@code mp.jwt.verify.publickey.location}: HTTP(S) URL → Remote JWKS or PEM (lazy,
     * first access format detection); JSON file ({}) → local JWKS; Or PEM.
     *
     * <p>For HTTP(S) URLs, loading is <em>lazy</em> so that the CDI producer
     * can be built before the server exposing the key is ready. The format (JWKS JSON
     * or PEM) is detected at the first key resolution. URL is reread since beg
     * at the time of detection to capture rewrites performed by the TCK harness after
     * server startup ({@code mp.jwt.verify.publickey.location} can be updated in
     * system properties between the construction of the solver and the first request).</p>
     */
    public static KeyResolver fromLocation(String location, Optional<SignatureAlgorithm.Family> family) throws JwtValidationException {
        if (location.startsWith("http://") || location.startsWith("https://")) {
            // Lazy auto-detect resolver: URL is re-read from system properties at detection time
            // to capture port rewrites applied after server startup (TCK harness pattern).
            return new LazyHttpKeyResolver(location, family);
        }
        byte[] bytes = JwksSource.fromLocation(location).fetch();
        String content = new String(bytes, StandardCharsets.UTF_8).trim();
        if (content.startsWith("{")) {
            return new JwksKeyResolver(() -> bytes);
        }
        return new ConfiguredKeyResolver(parsePem(content, family));
    }

    /**
     * Lazy solver for HTTP(S) URLs: self-detect JWKS JSON or PEM at first access.
     * Memorizes delegated solver after detection to avoid re-fetchering.
     *
     * <p>The rental is reread via the property system at each new detection (i.e. when the
     * delegate is null) to capture rewrites performed by the harness after boot.</p>
     */
    private static final class LazyHttpKeyResolver implements KeyResolver {

        /** The config key that holds the actual current URL (may change after rewrite). */
        private final String initialLocation;
        private final Optional<SignatureAlgorithm.Family> family;
        /** Initialized on first resolve(); reset on URL change. */
        private final AtomicReference<KeyResolver> delegate = new AtomicReference<>();

        LazyHttpKeyResolver(String initialLocation, Optional<SignatureAlgorithm.Family> family) {
            this.initialLocation = initialLocation;
            this.family = family;
        }

        @Override
        public Optional<PublicKey> resolve(String kid, SignatureAlgorithm algorithm) throws JwtValidationException {
            KeyResolver r = delegate.get();
            if (r == null) {
                r = detect();
                delegate.compareAndSet(null, r);
                r = delegate.get();
            }
            return r.resolve(kid, algorithm);
        }

        private KeyResolver detect() throws JwtValidationException {
            // Re-read location from system property at detection time to capture post-startup rewrites.
            String location = System.getProperty("mp.jwt.verify.publickey.location", initialLocation);
            JwksSource source = JwksSource.fromLocation(location);
            byte[] bytes = source.fetch();
            String content = new String(bytes, StandardCharsets.UTF_8).trim();
            if (content.startsWith("{")) {
                //JWKS JSON — re-use fresh source for each delegation (supports key rotation)
                return new JwksKeyResolver(JwksSource.fromLocation(location));
            }
            // PEM served over HTTP
            return new ConfiguredKeyResolver(parsePem(content, family));
        }
    }

    private static PublicKey parsePem(String pem, Optional<SignatureAlgorithm.Family> family) throws JwtValidationException {
        return family.isPresent() ? PemKeys.fromPem(pem, family.get()) : PemKeys.fromPem(pem);
    }
}
