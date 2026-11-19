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

import io.smallrye.jwt.auth.principal.DefaultJWTParser;
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo;
import io.smallrye.jwt.auth.principal.JWTParser;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in baseline: the SmallRye JWT counterpart of {@link JwtValidationBenchmark}, validating an
 * identical RS256 token with the same RSA public key. Compiled and run ONLY under the
 * {@code -Pcompare-smallrye} profile (this source dir is added by build-helper there) so the default
 * cervantes-bench build stays zero-dep.
 *
 * <p>Run: {@code ./mvnw -pl cervantes-bench -am -Pcompare-smallrye package -DskipTests}
 * then {@code java -jar cervantes-bench/target/benchmarks.jar SmallRye}, and compare with the
 * Cervantes numbers from the same machine (record both in {@code BENCH.md}).</p>
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class SmallRyeJwtValidationBenchmark {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final String ISS = "https://issuer.vidocq.dev";
    private static final String AUD = "cervantes-bench";

    private JWTParser parser;
    private String rs256Token;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();

        rs256Token = signRs256(kp.getPrivate());

        JWTAuthContextInfo info = new JWTAuthContextInfo((PublicKey) kp.getPublic(), ISS);
        info.setExpectedAudience(Set.of(AUD));
        parser = new DefaultJWTParser(info);

        if (parser.parse(rs256Token) == null) {
            throw new IllegalStateException("SmallRye fixture token failed to parse");
        }
    }

    private static String signRs256(PrivateKey key) throws Exception {
        JsonObject header = Json.createObjectBuilder().add("alg", "RS256").add("typ", "JWT").build();
        long now = System.currentTimeMillis() / 1000L;
        JsonObject claims = Json.createObjectBuilder()
                .add("iss", ISS)
                .add("aud", AUD)
                .add("sub", "1234567890")
                .add("upn", "alice@vidocq.dev")
                .add("jti", "bench-token")
                .add("iat", now - 60)
                .add("exp", now + 3_153_600_000L)
                .add("groups", Json.createArrayBuilder().add("admin").add("user"))
                .build();
        String h = B64URL.encodeToString(toJson(header));
        String p = B64URL.encodeToString(toJson(claims));
        byte[] signingInput = (h + '.' + p).getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(signingInput);
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
    public JsonWebToken validateRs256SmallRye() throws Exception {
        return parser.parse(rs256Token);
    }
}
