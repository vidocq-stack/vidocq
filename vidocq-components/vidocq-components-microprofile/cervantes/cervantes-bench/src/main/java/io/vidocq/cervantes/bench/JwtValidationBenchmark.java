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
package io.vidocq.cervantes.bench;

import io.vidocq.cervantes.api.JwtConfig;
import io.vidocq.cervantes.api.JwtValidationException;
import io.vidocq.cervantes.api.KeyResolver;
import io.vidocq.cervantes.api.SignatureAlgorithm;
import io.vidocq.cervantes.internal.DefaultJwtValidator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonWriter;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmark of the Cervantes JWT validation hot path: {@code DefaultJwtValidator.validate} —
 * Base64url decode → JSON parse (header + claims via Champollion JSON-P) → RSA signature verify
 * (java.security) → claims validation (iss/aud/exp/nbf/iat). One full RS256 (and RS512) token is
 * forged once at {@code @Setup}; each invocation validates it end-to-end.
 *
 * <p>Zero third-party JWT library — everything is {@code java.security} + Champollion, matching the
 * Vidocq zero-dep philosophy. The SmallRye JWT baseline lives behind the {@code -Pcompare-smallrye}
 * profile (see {@code src/bench-smallrye}); it is not part of the default build.</p>
 *
 * <p>Run: {@code java -jar cervantes-bench/target/benchmarks.jar JwtValidationBenchmark}</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class JwtValidationBenchmark {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String ISS = "https://issuer.vidocq.dev";
    private static final String AUD = "cervantes-bench";

    private DefaultJwtValidator validatorRs256;
    private DefaultJwtValidator validatorRs512;
    private String rs256Token;
    private String rs512Token;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();

        rs256Token = sign(SignatureAlgorithm.RS256, kp.getPrivate());
        rs512Token = sign(SignatureAlgorithm.RS512, kp.getPrivate());

        validatorRs256 = newValidator(kp.getPublic());
        validatorRs512 = newValidator(kp.getPublic());

        // Fail fast if the fixtures are wrong — a broken benchmark must not report numbers.
        if (validatorRs256.validate(rs256Token) == null || validatorRs512.validate(rs512Token) == null) {
            throw new IllegalStateException("benchmark fixture token failed to validate");
        }
    }

    private static DefaultJwtValidator newValidator(PublicKey pub) {
        KeyResolver resolver = (kid, alg) -> Optional.of(pub);
        return new DefaultJwtValidator(resolver, JwtConfig.of(ISS, Set.of(AUD)));
    }

    private static String sign(SignatureAlgorithm alg, PrivateKey key) throws Exception {
        JsonObject header = Json.createObjectBuilder()
                .add("alg", alg.name()).add("typ", "JWT").build();
        long now = System.currentTimeMillis() / 1000L;
        JsonObject claims = Json.createObjectBuilder()
                .add("iss", ISS)
                .add("aud", AUD)
                .add("sub", "1234567890")
                .add("upn", "alice@vidocq.dev")
                .add("jti", "bench-token")
                .add("iat", now - 60)
                .add("exp", now + 3_153_600_000L) // +100 years → never expires during a run
                .add("groups", Json.createArrayBuilder().add("admin").add("user"))
                .build();
        String h = B64URL.encodeToString(toJson(header));
        String p = B64URL.encodeToString(toJson(claims));
        byte[] signingInput = (h + '.' + p).getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance(alg.jcaName());
        signer.initSign(key);
        signer.update(signingInput);
        // RSA: the raw signature is already the JOSE signature (no DER→JOSE transcoding, unlike EC).
        return h + '.' + p + '.' + B64URL.encodeToString(signer.sign());
    }

    private static byte[] toJson(JsonObject o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonWriter w = Json.createWriter(out)) {
            w.writeObject(o);
        }
        return out.toByteArray();
    }

    @Benchmark
    public JsonWebToken validateRs256() throws JwtValidationException {
        return validatorRs256.validate(rs256Token);
    }

    @Benchmark
    public JsonWebToken validateRs512() throws JwtValidationException {
        return validatorRs512.validate(rs512Token);
    }
}
