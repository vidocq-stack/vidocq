# mansart-pool — BENCH.md

Toute mesure de performance landée dans la doc/le code/un commit doit avoir une entrée
correspondante ici, datée, avec hardware, JVM, commande exacte et résultats bruts (cf. workspace
`CLAUDE.md`).

---

## 2026-05-06 — MP-C-3 : baseline initiale vs HikariCP

**But** : avoir un point de référence après MP-A/B/C-1/C-2. Run "quick" — chiffres indicatifs,
**pas** publiables. Pour des chiffres publiables, lancer le profil `full` (cf. plus bas).

### Hardware / JVM

- macOS Darwin 25.4.0
- JDK 25 Temurin (`25-tem` via SDKMAN, `25+36-LTS`)
- Maven 3.9.16
- HikariCP 6.2.1
- JMH 1.37
- H2 2.3.232 (in-memory, `jdbc:h2:mem:bench-<UUID>;DB_CLOSE_DELAY=-1`)

### Commande

```bash
cd mansart-pool
mvn -ntp -pl mansart-pool-bench package -DskipTests
java -jar mansart-pool-bench/target/benchmarks.jar -f 1 -wi 2 -w 1s -i 3 -r 1s
```

(Profil `quick` du `BenchRunner` équivalent : 1 fork, 2×1s warmup, 3×1s measurement.)

### Résultats

| Benchmark | pool | Mode | Score | Unit |
|---|---|---|---:|---|
| `BorrowReleaseBench.borrowRelease` | mansart | avgt | **86.6 ± 26.2** | ns/op |
| `BorrowReleaseBench.borrowRelease` | hikari  | avgt | **55.9 ±  1.8** | ns/op |
| `ConcurrentBorrowBench.borrowRelease` (8 threads, maxSize 4) | mansart | thrpt | **3.22 ± 1.60** | ops/µs |
| `ConcurrentBorrowBench.borrowRelease` (8 threads, maxSize 4) | hikari  | thrpt | **4.18 ± 9.45** | ops/µs |

### Analyse rapide

- **Single-thread (no contention)** : HikariCP est ~35% plus rapide. Attendu : leur `ConcurrentBag`
  + state machine sont optimisés au cycle près depuis ~10 ans. Notre `Semaphore + Deque` est
  honnête mais a une indirection en plus (decrement du permit + offerFirst sur deque).
- **Concurrent 8 threads (contention forcée)** : HikariCP ~30% plus rapide. L'écart se resserre
  parce que Hikari et Mansart sont tous deux limités par le contended path (semaphore acquire chez
  nous, internal lock-free chez eux).
- **Note** : JMH ne dispatch pas sur virtual threads ; le terrain où Mansart est conçu pour exceller
  (contention massive sous Loom) n'est **pas** mesuré ici. Bench VT à faire dans un harness séparé
  (Thread.ofVirtual() en boucle hors-JMH) — TODO MP-D.
- Erreurs très larges (especially le ±9.4 sur Hikari concurrent) : conséquence directe du
  3-iteration run. Refaire en `full` pour stabiliser.

### Profil `full` (chiffres publiables)

```bash
java -jar mansart-pool-bench/target/benchmarks.jar -f 5 -wi 5 -w 3s -i 10 -r 3s
```

≈ 10 minutes wall-clock. C'est ce profil qui doit alimenter une nouvelle entrée BENCH.md
avant toute communication externe (README, blog, présentation).

### Hors-scope de cette baseline

- Bench Postgres réel (Testcontainers) — l'overhead JDBC dominerait totalement le pool.
- Bench virtual threads sous contention massive (cible naturelle de Mansart) — harness dédié.
- Profile leak detection enabled vs disabled — overhead du `new Throwable()` à mesurer.
- Comparatif vs Agroal.

Ces mesures viendront en MP-D ou plus tard.
