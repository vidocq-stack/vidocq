# BENCH.md — Cervantes

Chiffres de performance (JMH). Format par entrée : `date · hardware/JVM · commande exacte ·
résultats bruts · delta vs run précédent`. Pas de chiffre de perf ailleurs (README, commit)
sans entrée correspondante ici.

---

## 2026-05-27 — Débit de validation JWT (cervantes-core vs SmallRye JWT)

Premier relevé (M7). Mesure le hot-path `DefaultJwtValidator.validate` de bout en bout :
décodage Base64url → parsing JSON header+claims (Champollion JSON-P) → vérification de signature
RSA (`java.security`) → validation des claims (iss/aud/exp/nbf/iat). Le token (RS256/RS512,
RSA-2048) est forgé une seule fois au `@Setup` ; chaque invocation le valide intégralement.
Baseline SmallRye JWT 4.6.1 (`DefaultJWTParser`) sur le **même token, la même clé, le même build et
la même machine** (jar bâti avec `-Pcompare-smallrye`).

### Environnement

| | |
|---|---|
| **Hardware** | Apple M4 Max (arm64) |
| **OS** | macOS (Darwin 25.5.0) |
| **JVM** | OpenJDK Temurin 25+36-LTS (HotSpot, Compiler Blackholes activés) |
| **JMH** | 1.37 — Fork 1, Warmup 3×2 s, Measurement 5×2 s |
| **Baseline** | SmallRye JWT 4.6.1 (tire jose4j ; interdit en prod, isolé dans le profil `-Pcompare-smallrye`) |

### Commande exacte

```bash
cd cervantes && sdk env
./mvnw -ntp -pl cervantes-bench -am -Pcompare-smallrye package -DskipTests
java -jar cervantes-bench/target/benchmarks.jar
```

### Résultats bruts

```
Benchmark                                              Mode  Cnt   Score    Error   Units
JwtValidationBenchmark.validateRs256                  thrpt    5   0,022 ±  0,001  ops/us
JwtValidationBenchmark.validateRs512                  thrpt    5   0,022 ±  0,001  ops/us
SmallRyeJwtValidationBenchmark.validateRs256SmallRye  thrpt    5   0,034 ±  0,001  ops/us
JwtValidationBenchmark.validateRs256                   avgt    5  45,125 ±  0,432   us/op
JwtValidationBenchmark.validateRs512                   avgt    5  45,839 ±  1,637   us/op
SmallRyeJwtValidationBenchmark.validateRs256SmallRye   avgt    5  29,046 ±  0,388   us/op
```

### Lecture (sans complaisance)

- **Cervantes RS256 ≈ 45 µs/op (~22 000/s) ; SmallRye RS256 ≈ 29 µs/op (~34 000/s).**
  **SmallRye est ~1,55× plus rapide** sur cette validation. À assumer : Cervantes n'est pas (encore)
  le plus rapide.
- Les deux paient la **même** vérification RSA-2048 incompressible (`Signature.verify` du JDK). L'écart
  de ~16 µs vient donc du **reste** : Cervantes matérialise un `JsonObject` complet via Champollion
  JSON-P (header + payload) puis valide les claims, là où SmallRye/jose4j a un chemin de parsing
  spécialisé et plus économe en allocations.
- RS256 ≈ RS512 côté Cervantes : la différence SHA-256/SHA-512 est négligeable devant le modexp RSA.
- **Le compromis assumé** : Cervantes échange ~1,5× de débit contre **zéro dépendance tierce**
  (pas de jose4j/jackson/bouncycastle), JPMS natif et compatibilité AOT (GraalVM/Leyden). Même ordre
  de grandeur, pas de gouffre. La piste d'optimisation est claire et identifiée (ci-dessous).

### TODO (prochains relevés)

- **Optimiser le parsing** : éviter de matérialiser tout le payload en `JsonObject` (lecture
  paresseuse / streaming des claims requises), réduire les allocations — c'est là qu'est l'écart.
- Décomposer parse-only vs verify-only vs claims-only pour quantifier la part crypto incompressible.
- Ajouter ES256 (nécessite le transcodage DER→JOSE, interne à cervantes-core).
- Run « publiable » `-f 5 -wi 5 -i 5` pour resserrer les intervalles de confiance.
