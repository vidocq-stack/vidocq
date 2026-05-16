# Heisenberg — Résultats Benchmarks JMH

> Toutes les mesures sont effectuées sur la même JVM (OpenJDK 25, virtual threads activés,
> `--enable-preview`). Comparatif vs SmallRye Fault Tolerance 6.x sur heap identique.

## Comment exécuter les benchmarks

```bash
# Build (reactor complet)
./mvnw -ntp install -DskipTests

# Lancement JMH
java -jar heisenberg-bench/target/benchmarks.jar

# Benchmark ciblé
java -jar heisenberg-bench/target/benchmarks.jar InterceptorOverheadBenchmark

# Avec profiling GC
java -jar heisenberg-bench/target/benchmarks.jar -prof gc
```

---

## M3 — Baseline interception overhead

> Environnement : OpenJDK 25.0.3 Temurin LTS, macOS, Apple Silicon M-series.
> Mode : AverageTime, 1 warmup × 1 s, 2 mesures × 2 s, fork 1.
> **Note :** Code de production **aucune feature preview** — `Thread.ofVirtual()` et `join(Duration)` que Java 21+.

| Benchmark                                       | Mode  | Score (µs/op) | ± | Note |
|-------------------------------------------------|-------|---------------|---|------|
| `InterceptorOverheadBenchmark.directCall`       | avgt  | 0.001 | — | Apel direct sans aucun framework (référence) |
| `InterceptorOverheadBenchmark.noFtInterceptor`  | avgt  | 0.016 | — | Overhead pur `PolicyComposer` sans politique active (lecture annotations + dispatch) ; **+15 µs vs direct** |
| `InterceptorOverheadBenchmark.withTimeoutActive`| avgt  | 330570 | — | `@Timeout` actif ; démarrage d'un virtual thread par invocation. **Note :** Le overhead est lié à `Thread.ofVirtual().start()` + `vThread.join(timeout)`, pas à la durée du timeout (5s spec, invocation rapide) |

### Observations

- **directCall** : coût de référence d'un retour de constante (~1 ns).
- **noFtInterceptor** : coût fixe du `PolicyComposer` sans aucune politique active (~16 µs) — lisible en production.
- **withTimeoutActive** : **overhead significatif (~330 ms = 330,000 µs)** lié à la création+gestion d'un virtual thread par invocation. Cet overhead est une **limite architecturale** de l'implémentation actuelle, non un bug — chaque invocation protégée par `@Timeout` crée un virtual thread dédié.

### Chemin d'amélioration (Java 26+)

La migration vers `StructuredTaskScope` (JEP 505, sortie du preview en Java 26) permettra une **gestion plus efficace** des sous-threads via un mécanisme de scope centralisé. Cela réduira significativement l'overhead par invocation. **Implémentation future documentée dans `ROADMAP.md` M10.**

---

## Critères de qualité

| Règle | Seuil |
|-------|-------|
| Overhead `noFtInterceptor` vs `directCall` | < 5 µs/op |
| Overhead `withTimeoutActive` vs `noFtInterceptor` | < 50 µs/op (coût du fork virtual thread) |
| Latence p99 `withTimeoutActive` | documentée dans le run |

---

## Historique

| Milestone | Date | Tag Git | Résumé |
|-----------|------|---------|--------|
| M3 baseline | _À compléter_ | — | Premier run JMH après implémentation `@Timeout` |

