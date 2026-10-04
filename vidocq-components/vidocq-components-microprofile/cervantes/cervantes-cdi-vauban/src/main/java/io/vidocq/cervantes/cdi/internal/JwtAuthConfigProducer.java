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
package io.vidocq.cervantes.cdi.internal;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.JwtValidator;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import io.vidocq.cervantes.internal.DefaultJwtValidator;
import io.vidocq.cervantes.internal.Jwe;
import io.vidocq.cervantes.internal.JweDecryptor;
import io.vidocq.cervantes.internal.KeyResolvers;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the application {@link JwtValidator} from the MicroProfile Config properties
 * {@code mp.jwt.verify.*} and {@code mp.jwt.decrypt.*} (read through the MicroProfile Config
 * API, so any implementation works, for instance Ravel):
 *
 * <ul>
 * <li>{@code mp.jwt.verify.issuer}: the expected issuer</li>
 * <li>{@code mp.jwt.verify.audiences}: the accepted audiences, comma separated</li>
 * <li>{@code mp.jwt.verify.publickey}: inline public key (PEM or base64 X.509)</li>
 * <li>{@code mp.jwt.verify.publickey.location}: location of a PEM file, a JWKS file or a JWKS URL</li>
 * <li>{@code mp.jwt.verify.publickey.algorithm}: {@code RS256}, {@code ES256}, and so on; when unset,
 * every RS256/384/512 and ES256/384/512 algorithm is accepted</li>
 * </ul>
 *
 * <p>The producer and its product are {@code @Dependent}, following the Ravel
 * {@code RavelConfigProducer} pattern: a normal-scoped product currently triggers a bad proxy
 * resolution on the Vauban side, so {@code @ApplicationScoped} is deferred until that defect is
 * corrected. The JAX-RS authentication filter injects the validator to check each bearer
 * token.</p>
 *
 * <p>Because the product is {@code @Dependent}, it is built lazily, at its first injection. To
 * fail fast instead, {@link io.vidocq.cervantes.cdi.CervantesClaimExtension} calls
 * {@link #createValidator(Config)} during its {@code @Validation} phase, so an invalid
 * configuration aborts the container start.</p>
 */
@Dependent
public class JwtAuthConfigProducer {

    @Produces
    @Dependent
    public JwtValidator jwtValidator() {
        return createValidator(ConfigProvider.getConfig());
    }

    /**
     * Builds the validator described by {@code config}.
     *
     * @return the validator, or {@code null} when MP-JWT is off (no verification key configured)
     * @throws IllegalStateException if a verification key is configured but the configuration is
     *         invalid (unrecognised algorithm, unreadable key, and so on); the cause carries the
     *         property name, the bad value and the supported values
     */
    public static JwtValidator createValidator(Config config) {
        if (!isVerificationKeyConfigured(config)) {
            // MP-JWT not configured (no verification key at all): produce a null validator so the
            // JAX-RS authentication filter stays inert (requests remain anonymous) instead of failing.
            // This is the documented "ship cervantes but configure no mp.jwt.*" path. A deployment that
            // DOES set a key but gets it wrong still surfaces an IllegalStateException below.
            return null;
        }
        try {
            return new DefaultJwtValidator(
                    buildKeyResolver(config),
                    buildConfig(config),
                    java.time.Clock.systemUTC(),
                    buildDecryptor(config));
        } catch (JwtValidationException e) {
            throw new IllegalStateException("invalid MicroProfile JWT configuration (mp.jwt.verify.* / mp.jwt.decrypt.*): "
                    + e.getMessage(), e);
        }
    }

    /** True if a verification key is configured (inline PEM or location); otherwise MP-JWT is off. */
    private static boolean isVerificationKeyConfigured(Config config) {
        return config.getOptionalValue("mp.jwt.verify.publickey", String.class).isPresent()
                || config.getOptionalValue("mp.jwt.verify.publickey.location", String.class).isPresent();
    }

    /**
     * Optional JWE decryption: {@code mp.jwt.decrypt.key} (inline) or {@code mp.jwt.decrypt.key.location}.
     * {@code null} if absent. Optionally reads {@code mp.jwt.decrypt.key.algorithm} to
     * validate that the algorithm in the JWE corresponds to that configured.
     */
    static JweDecryptor buildDecryptor(Config config) throws JwtValidationException {
        Optional<String> algorithm = config.getOptionalValue("mp.jwt.decrypt.key.algorithm", String.class);
        String requiredAlgorithm = algorithm.orElse(null);
        Optional<String> inline = config.getOptionalValue("mp.jwt.decrypt.key", String.class);
        if (inline.isPresent()) {
            return Jwe.decryptorFromInlinePem(inline.get(), requiredAlgorithm);
        }
        Optional<String> location = config.getOptionalValue("mp.jwt.decrypt.key.location", String.class);
        if (location.isPresent()) {
            return Jwe.decryptorFromLocation(location.get(), requiredAlgorithm);
        }
        return null;
    }

    static JwtConfig buildConfig(Config config) throws JwtValidationException {
        Optional<String> issuer = config.getOptionalValue("mp.jwt.verify.issuer", String.class);
        Set<String> audiences = config.getOptionalValue("mp.jwt.verify.audiences", String.class)
                .map(JwtAuthConfigProducer::splitCsv)
                .orElseGet(Set::of);
        Optional<Long> tokenAge = config.getOptionalValue("mp.jwt.verify.token.age", Long.class);
        // Encryption required when a decryption key is configured (mp.jwt.decrypt.key or .location)
        boolean encryptionRequired = config.getOptionalValue("mp.jwt.decrypt.key", String.class).isPresent()
                || config.getOptionalValue("mp.jwt.decrypt.key.location", String.class).isPresent();
        return new JwtConfig(issuer, audiences, JwtConfig.DEFAULT_CLOCK_SKEW, true, tokenAge,
                encryptionRequired, configuredAlgorithm(config));
    }

    /**
     * Reads {@code mp.jwt.verify.publickey.algorithm}. Unset means every RS256/384/512 and ES256/384/512 algorithm is accepted;
     * a value that is set but is not an exact JOSE name (RFC 7518, case-sensitive: {@code RS256},
     * not {@code rs256}) fails at startup instead of silently widening what is accepted.
     */
    private static Optional<SignatureAlgorithm> configuredAlgorithm(Config config) throws JwtValidationException {
        Optional<String> raw = config.getOptionalValue("mp.jwt.verify.publickey.algorithm", String.class);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Optional<SignatureAlgorithm> algorithm = SignatureAlgorithm.fromJoseName(raw.get());
        if (algorithm.isEmpty()) {
            throw new JwtValidationException("unsupported mp.jwt.verify.publickey.algorithm '" + raw.get()
                    + "': supported values are " + Arrays.stream(SignatureAlgorithm.values())
                            .map(Enum::name).collect(Collectors.joining(", ")));
        }
        return algorithm;
    }

    static KeyResolver buildKeyResolver(Config config) throws JwtValidationException {
        Optional<SignatureAlgorithm.Family> family = configuredAlgorithm(config).map(SignatureAlgorithm::family);

        Optional<String> inline = config.getOptionalValue("mp.jwt.verify.publickey", String.class);
        if (inline.isPresent()) {
            return KeyResolvers.fromInlinePem(inline.get(), family);
        }
        Optional<String> location = config.getOptionalValue("mp.jwt.verify.publickey.location", String.class);
        if (location.isPresent()) {
            return KeyResolvers.fromLocation(location.get(), family);
        }
        throw new JwtValidationException(
                "no verification key configured: set mp.jwt.verify.publickey or mp.jwt.verify.publickey.location");
    }

    private static Set<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
