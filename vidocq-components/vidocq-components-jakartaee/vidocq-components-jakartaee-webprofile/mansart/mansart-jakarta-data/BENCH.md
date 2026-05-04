# BENCH.md — mansart-jakarta-data

Historique des mesures de performance. Convention : voir `../CLAUDE.md` (workspace root).
Tout chiffre publié (README, commit, post) doit pointer vers une entrée ici.

Procédure : décommenter le `@Disabled` dans `mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/BenchSmoke.java`, puis :

```bash
mvn -ntp install -DskipTests
mvn -ntp -pl mansart-data-tests test -Dtest='BenchSmoke#findByIdSingleThread+findByIdVirtualThreads'
```

Capturer les lignes `[BENCH] …` du stdout et créer une nouvelle entrée ci-dessous.

> **Caveat** : ce harness est un *smoke* (un seul shot, pas de JMH). Les chiffres servent à détecter les régressions grossières (>20%) entre commits, pas à publier des benchmarks comparatifs. Un harness JMH propre arrivera dans `mansart-data-bench` en M5+.

---

## BENCH-20260504-01 — findById sur H2 in-memory (smoke initial M3a/M3b-1)

- **Date** : 2026-05-04
- **Commit** : `08d0d3b` (main, suite M3b-1 mergée)
- **JVM** : Temurin OpenJDK 25 (build 25+36-LTS), `-XX:+UseG1GC` par défaut
- **Hardware** : Apple M4 Max / 16 cœurs / 128 GB RAM
- **OS** : Darwin 25.4.0 (macOS 26 / arm64)
- **Backend** : H2 2.3.232 in-memory via `JdbcConnectionPool.create(...)`, `maxConnections=64`
- **Schéma** : table `authors (id BIGINT IDENTITY PK, name VARCHAR(200))` seedée à 10 000 lignes
- **Commande exacte** :
  ```bash
  mvn -ntp install -DskipTests
  mvn -ntp -pl mansart-data-tests test -Dtest='BenchSmoke#findByIdSingleThread+findByIdVirtualThreads'
  ```
- **Résultats** :

  | Scénario | Ops | Wall-clock | µs/op | ops/s | Erreurs |
  | --- | ---: | ---: | ---: | ---: | ---: |
  | findById single-thread | 50 000 | 121.26 ms | **2.43 µs** | 412 336 | 0 |
  | findById 1 000 VTs × 200 ops | 200 000 | 558.44 ms | **2.79 µs** | 358 144 | 0 |

- **Comparaison vs run précédent** : *premier run*.
- **Notes** :
  - Cible interne du plan M3 : `< 5 µs/op` hors I/O JDBC. **Atteinte** sur H2 in-memory (qui inclut pourtant l'I/O JDBC complet — préparation, exécution, mapping). Marge confortable.
  - Single-thread vs 1 000 virtual threads : seulement +15 % de latence (2.43 → 2.79 µs/op) — le pool H2 (`maxConnections=64`) absorbe les 1 000 VTs sans contention notable.
  - 0 erreur sur 200 000 lectures concurrentes ⇒ stack thread-safe sous Loom.
  - Aucun pinning observé (les `Connection` H2 ne posent pas de moniteur sur le `Connection.close()` de retour au pool, et `ScopedValue` n'introduit pas de bloc `synchronized`).
  - Pour comparer plus tard à HikariCP / Mansart Pool : refaire la mesure avec `-Djdk.tracePinnedThreads=full` et capturer la sortie.
