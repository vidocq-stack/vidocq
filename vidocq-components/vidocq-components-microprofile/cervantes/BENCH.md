# BENCH.md — Cervantes

JMH performance numbers. Entry format: `date · hardware/JVM · exact command ·
raw results · delta vs previous run`. No performance numbers elsewhere (README, commit)
without a corresponding entry here.

---

## 2026-05-27 — JWT validation throughput (cervantes-core vs SmallRye JWT)

First measurement (M7). Measures the hot-path `DefaultJwtValidator.validate` end-to-end:
Base64url decoding → JSON header+claims parsing (Champollion JSON-P) → RSA signature verification
(`java.security`) → claims validation (iss/aud/exp/nbf/iat). The token (RS256/RS512,
RSA-2048) is forged once at `@Setup`; each invocation validates it in full.
SmallRye JWT 4.6.1 baseline (`DefaultJWTParser`) on the **same token, same key, same build and
same machine** (jar built with `-Pcompare-smallrye`).

### Environment

| | |
|---|---|
| **Hardware** | Apple M4 Max (arm64) |
| **OS** | macOS (Darwin 25.5.0) |
| **JVM** | OpenJDK Temurin 25+36-LTS (HotSpot, Compiler Blackholes enabled) |
| **JMH** | 1.37 — Fork 1, Warmup 3×2 s, Measurement 5×2 s |
| **Baseline** | SmallRye JWT 4.6.1 (pulls jose4j; forbidden in production, isolated in the `-Pcompare-smallrye` profile) |

### Exact command

```bash
cd cervantes && sdk env
./mvnw -ntp -pl cervantes-bench -am -Pcompare-smallrye package -DskipTests
java -jar cervantes-bench/target/benchmarks.jar
```

### Raw results

```
Benchmark                                              Mode  Cnt   Score    Error   Units
JwtValidationBenchmark.validateRs256                  thrpt    5   0,022 ±  0,001  ops/us
JwtValidationBenchmark.validateRs512                  thrpt    5   0,022 ±  0,001  ops/us
SmallRyeJwtValidationBenchmark.validateRs256SmallRye  thrpt    5   0,034 ±  0,001  ops/us
JwtValidationBenchmark.validateRs256                   avgt    5  45,125 ±  0,432   us/op
JwtValidationBenchmark.validateRs512                   avgt    5  45,839 ±  1,637   us/op
SmallRyeJwtValidationBenchmark.validateRs256SmallRye   avgt    5  29,046 ±  0,388   us/op
```

### Honest analysis

- **Cervantes RS256 ≈ 45 µs/op (~22 000/s); SmallRye RS256 ≈ 29 µs/op (~34 000/s).**
  **SmallRye is ~1.55× faster** on this validation. This is acknowledged: Cervantes is not (yet)
  the fastest.
- Both pay the **same** incompressible RSA-2048 verification (`Signature.verify` from the JDK). The
  ~16 µs gap therefore comes from **the rest**: Cervantes materialises a full `JsonObject` via Champollion
  JSON-P (header + payload) then validates the claims, whereas SmallRye/jose4j has a specialised
  parsing path with fewer allocations.
- RS256 ≈ RS512 on the Cervantes side: the SHA-256/SHA-512 difference is negligible next to the RSA modexp.
- **The accepted trade-off**: Cervantes trades ~1.5× throughput for **zero third-party dependencies**
  (no jose4j/jackson/bouncycastle), native JPMS and AOT compatibility (GraalVM/Leyden). Same order
  of magnitude, no abyss. The optimisation path is clear and identified (see below).

### TODO (next measurements)

- **Optimise parsing**: avoid materialising the entire payload as a `JsonObject` (lazy
  reading / streaming of required claims), reduce allocations — this is where the gap lies.
- Break down parse-only vs verify-only vs claims-only to quantify the incompressible crypto share.
- Add ES256 (requires DER→JOSE signature transcoding, internal to cervantes-core).
- Publishable run `-f 5 -wi 5 -i 5` to tighten confidence intervals.
