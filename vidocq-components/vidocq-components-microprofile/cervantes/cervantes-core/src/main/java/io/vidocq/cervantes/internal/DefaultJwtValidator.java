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

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.security.PublicKey;
import java.time.Clock;
import java.util.Objects;

/**
 * {@link JwtValidator} Reference Implementation: parsing chain → key resolution →
 * signature check → validation of claims, then build the {@link JsonWebToken}.
 *
 * <p>Immutable and thread-safe (employees are unstated). {@link Clock} for injection
 * makes time-based validation testable.</p>
 */
public final class DefaultJwtValidator implements JwtValidator {

    private final JwtParser parser = new JwtParser();
    private final JwtSignatureVerifier verifier = new JwtSignatureVerifier();
    private final JwtClaimsValidator claimsValidator = new JwtClaimsValidator();

    private final KeyResolver keyResolver;
    private final JwtConfig config;
    private final Clock clock;
    private final JweDecryptor decryptor; //null: decryption JWE (M5)

    public DefaultJwtValidator(KeyResolver keyResolver, JwtConfig config) {
        this(keyResolver, config, Clock.systemUTC(), null);
    }

    public DefaultJwtValidator(KeyResolver keyResolver, JwtConfig config, Clock clock) {
        this(keyResolver, config, clock, null);
    }

    public DefaultJwtValidator(KeyResolver keyResolver, JwtConfig config, Clock clock, JweDecryptor decryptor) {
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.decryptor = decryptor;
    }

    @Override
    public JsonWebToken validate(String token) throws JwtValidationException {
        ParsedJwt jwt = parser.parse(decryptIfEncrypted(token));

        String algName = headerString(jwt, "alg");
        SignatureAlgorithm alg = SignatureAlgorithm.fromJoseName(algName)
                .orElseThrow(() -> new JwtValidationException("unsupported or missing 'alg' header: " + algName));

        String kid = headerString(jwt, "kid");
        PublicKey key = keyResolver.resolve(kid, alg)
                .orElseThrow(() -> new JwtValidationException("no verification key found (kid=" + kid + ")"));

        if (!verifier.verify(jwt, key, alg)) {
            throw new JwtValidationException("invalid signature");
        }

        claimsValidator.validate(jwt.claims(), config, clock);
        return new DefaultJsonWebToken(jwt.claims(), jwt.rawToken());
    }

    /**
     * If the token is a compact JWE (5 parts), decipher it in nested JWS sound; if not the
     * returns as is. A JWE received without configured decryption key is rejected.
     * A JWS received while encryption is required ({@code config.encryptionRequired()}) is rejected.
     */
    private String decryptIfEncrypted(String token) throws JwtValidationException {
        if (token == null) {
            return null; //the parser will lift "empty token"
        }
        long dots = token.chars().filter(c -> c == '.').count();
        if (dots == 4) { //5 parts → JWE
            if (decryptor == null) {
                throw new JwtValidationException("received an encrypted JWT (JWE) but no decryption key is configured");
            }
            return decryptor.decryptToCompactJws(token);
        }
        //JWS (3 parts) — reject if encryption is required by the config
        if (config.encryptionRequired()) {
            throw new JwtValidationException("token must be encrypted (JWE) but received a signed-only token (JWS)");
        }
        return token; // le parser valide la forme exacte
    }

    private static String headerString(ParsedJwt jwt, String name) {
        JsonValue v = jwt.header().get(name);
        return (v instanceof JsonString s) ? s.getString() : null;
    }
}
