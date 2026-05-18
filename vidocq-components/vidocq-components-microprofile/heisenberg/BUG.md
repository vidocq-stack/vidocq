# BUG.md

Suivi des bugs reproductibles. Conforme aux conventions Vidocq (AGENTS.md § « Bugs reproductibles tracés dans BUG.md »).

| id | date | symptôme | repro | hypothèse | statut |
|---|---|---|---|---|---|
| BUG-001 | 2026-05-15 | **Fuite potentielle de virtual thread** sous `@Asynchronous + @Timeout` avec `CompletionStage` long-running | Méthode `@Asynchronous @Timeout(100ms)` retournant `CompletableFuture.supplyAsync(() -> Thread.sleep(5000))` : `TimeoutEngine` abandonne après 100ms et lève `TimeoutException`, mais le thread de l'unwrap (`stage.toCompletableFuture().get()`) continue à bloquer jusqu'à 5s avant d'être collecté | L'appel `get()` n'est pas interruptible via `Thread.interrupt()` sur tous les `CompletableFuture` (dépend de la chaîne sous-jacente). En virtual thread, la fuite est faible (quelques Ko mémoire) et se résorbe d'elle-même à l'expiration de la tâche. Acceptable jusqu'à une optimisation future avec `StructuredTaskScope` (finalisé Java 25) si les benchmarks la justifient. | ⚠️ accepté — optimisation optionnelle M10+ |

## Notes

- Les bugs marqués ⚠️ sont des comportements connus et tolérés (faibles impacts) jusqu'à une migration architecturale planifiée.
- Les bugs critiques (🔴) doivent être corrigés avant le merge du milestone correspondant.

