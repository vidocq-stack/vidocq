# BUG.md

Suivi des bugs reproductibles. Conforme aux conventions Vidocq (AGENTS.md § « Bugs reproductibles tracés dans BUG.md »).

| id | date | symptôme | repro | hypothèse | statut |
|---|---|---|---|---|---|
| BUG-001 | 2026-05-15 | **Fuite potentielle de virtual thread** sous `@Asynchronous + @Timeout` avec `CompletionStage` long-running | Méthode `@Asynchronous @Timeout(100ms)` retournant `CompletableFuture.supplyAsync(() -> Thread.sleep(5000))` : `TimeoutEngine` abandonne après 100ms et lève `TimeoutException`, mais le thread de l'unwrap (`stage.toCompletableFuture().get()`) continue à bloquer jusqu'à 5s avant d'être collecté | L'appel `get()` n'est pas interruptible via `Thread.interrupt()` sur tous les `CompletableFuture` (dépend de la chaîne sous-jacente). En virtual thread, la fuite est faible (quelques Ko mémoire) et se résorbe d'elle-même à l'expiration de la tâche. Acceptable jusqu'à une optimisation future avec `StructuredTaskScope` (finalisé Java 25) si les benchmarks la justifient. | ⚠️ accepté — optimisation optionnelle M10+ |
| BUG-002 | 2026-05-25 | **JPMS contourné** : `maven-dependency-plugin` copie les JARs compile-scope dans `target/javamodules/` et le compilateur reçoit `--module-path` manuel plutôt que de s'appuyer sur la résolution JPMS native de Maven | `grep "javamodules" pom.xml` révèle la config ; supprimer la section `maven-dependency-plugin` et recompiler pour observer les erreurs `module not found` | `microprofile-fault-tolerance-api`, `vauban-core`/`vauban-classloader-spi` et `jmh-core` n'ont pas de `module-info.class` reconnu par `maven-compiler-plugin` 4.x pour placement automatique sur le module path | ⚠️ OPEN — workaround actif ; à supprimer module par module quand les dépendances amont seront modularisées ou wrappées (cf. pattern `heisenberg-mp-ft-api`) |

## Notes

- Les bugs marqués ⚠️ sont des comportements connus et tolérés (faibles impacts) jusqu'à une migration architecturale planifiée.
- Les bugs critiques (🔴) doivent être corrigés avant le merge du milestone correspondant.

