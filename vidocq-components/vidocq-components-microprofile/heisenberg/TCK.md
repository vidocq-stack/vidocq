# TCK — MicroProfile Fault Tolerance 4.1

Score officiel du TCK `microprofile-fault-tolerance-tck:4.1` contre Heisenberg.

## État courant (M9 — 2026-05-22T09:15Z)

L'infrastructure TCK est **opérationnelle** :

- Container Arquillian local (`HeisenbergDeployableContainer`).
- Bootstrap Vauban + Heisenberg (`VaubanTckBootstrap`).
- Activation/réinitialisation du `RequestContext` autour des tests.
- Injection `@Inject` via `HeisenbergTestEnricher`.

- ✅ Smoke run du 2026-05-16T10:24Z : `1/1 PASS` (`HeisenbergTckSmokeTest`).
- ✅ Build reactor + tests unitaires : `140/140 verts` (98 core + 42 cdi-vauban).
- 🚧 Dernier run `all` reproductible (2026-05-16T11:17Z, hors metrics) :
   `424 run / 349 PASS / 61 fail / 14 skip` (~**82 % PASS**).
- 🚧 Run metrics ciblé (2026-05-22T09:15Z, suite Dirac intégrée) :
   `24 run / 1 PASS / 23 fail` — infra CDI opérationnelle, `MetricsDisabledTest` ✅, 23 tests attendent que `FaultToleranceInterceptor` publie ses métriques FT.
- 🚧 M9 améliorations architecturales appliquées :
   - `BulkheadState` convertie d'un record à une interface extensible
   - Tracking des tâches attendantes via `activeWaiters` counter
   - Support d'injection de barrières TCK via `ScopedValue`

## Progression mesurée

| Run | Tests run | PASS | FAIL | SKIP | Notes |
|---|---:|---:|---:|---:|---|
| Baseline initiale | 527 | 266 | 175 | 86 | Première exécution complète du TCK officiel.
| Global après correctifs Retry + CircuitBreaker | 527 | 316 | 125 | 86 | **-50 failures**.
| Global après lot fallback+bootstrap | 501 | 332 | 109 | 60 | **-16 failures / -26 skips**.
| **Run courant (2026-05-16T11:17Z)** | **424** | **349** | **61** | **14** | **+17 PASS / -48 failures** ; total réduit (exclusions metrics/telemetry élargies, validations invalides désormais comptées).
| Metrics run (2026-05-22T09:15Z) | **24** | **1** | **23** | **0** | Intégration Dirac wired — `MetricsDisabledTest` ✅ ; 23 tests bloqués sur publishing FT metrics manquant.

### Vérifications ciblées récentes

| Test class | Tests run | PASS | FAIL | Notes |
|---|---:|---:|---:|---|
| `RetryTest` | 8 | 8 | 0 | Stable.
| `CircuitBreakerLifecycleTest` | 20 | 19 | 1 | 1 scénario d'override class-level restant.
| `FallbackMethodOutOfPackageTest` | 1 | 1 | 0 | Déploiement négatif correctement reconnu (`FaultToleranceDefinitionException`).
| `InvalidRetryDelayTest` | 1 | 1 | 0 | Validation négative au déploiement OK.
| `BulkheadAsynchTest` | 11 | 2 | 9 | **Cluster dominant** : `Timed out while checking task is awaiting`, `testBulkheadCompletionStage`.
| `BulkheadAsynchRetryTest` | 8 | 0 | 8 | Même symptôme (réentrée bulkhead async).
| `BulkheadFutureTest` | — | — | 4 | `Future.isDone()` / cycle d'attente async.
| `CircuitBreakerRetryTest` | 10 | 6 | 4 | Composition CB+Retry **mode async** non encore alignée.
| `TimeoutUninterruptableTest` | — | — | 4 | Timing d'interruption en mode async (mismatch durée mesurée).
| `DisableTest` | — | — | 4 | Désactivation globale Retry/Timeout/Fallback + CB.
| `FallbackMethodGeneric*Test`, `FallbackMethodPrivateTest`, `IncompatibleFallbackTest`, `FallbackMethodWildcardNegativeTest` | — | — | 6 | Validations négatives de déploiement à durcir dans `HeisenbergExtension` (signatures génériques / méthodes privées).
| `RetryConditionTest` | — | — | 3 | À investiguer.
| `AsyncCancellationTest` | — | — | 3 | À investiguer.
| `FaultToleranceInterceptorPriorityChangeAnnotationConfTest` | 1 | 0 | 1 | Bootstrap KO sur re-priorisation pilotée par annotation.

## Correctifs récents apportés

1. **Retry**
   - Ajustement de la logique `maxDuration` et du jitter.
   - Stabilisation de l'isolation de contexte test (`HeisenbergTestEnricher`).
2. **CircuitBreaker (core)**
   - Fenêtre glissante count-based dans `CircuitBreakerEngine`.
   - Évaluation du ratio à chaque requête (après volume minimal).
   - Historique partagé entre invocations (moteur recréé par `PolicyComposer`).
3. **Résolution méthode interceptée (CDI)**
   - Correction des ponts `$$super$...` dans `FaultToleranceInterceptor.resolveMethod(...)`.
   - Fin de l'héritage involontaire des annotations FT sur méthodes override.
4. **Bootstrap TCK (déploiement négatif)**
   - `HeisenbergDeployableContainer` remonte désormais systématiquement les erreurs BCE
     (`@Enhancement error`) en `FaultToleranceDefinitionException`.
   - Corrige les scénarios `@ShouldThrowException(FaultToleranceDefinitionException.class)`.
5. **Classpath metrics API**
   - Ajout explicite de `microprofile-metrics-api:4.0` en scope test dans `heisenberg-tck/pom.xml`.
   - Supprime le `NoClassDefFoundError: org/eclipse/microprofile/metrics/MetricID`.

## Répartition des échecs restants (run global 2026-05-16T11:17Z, 61 échecs)

1. **Bulkhead asynchrone — cluster dominant (~21 échecs)**
   - `BulkheadAsynchTest` (9), `BulkheadAsynchRetryTest` (8), `BulkheadFutureTest` (4) :
     `Timed out while checking task is awaiting`, `testBulkheadCompletionStage` (Timeout).
   - Symptôme aligné avec `BUG-001` (cycle d'attente sur la barrière de test async vs
     ordonnancement permits/queue côté `BulkheadEngine.executeAsync`).
   - Hypothèse : la file d'attente partagée via `BulkheadStateRegistry.BulkheadState`
     ne libère pas le permit assez tôt pour que le test observe l'état `awaiting`
     avant la barrière TCK.
2. **CircuitBreaker + Retry asynchrone (~4 échecs)**
   - `CircuitBreakerRetryTest` mode async : `Future`/`CompletionStage` ne propagent pas
     l'exception attendue (`CircuitBreakerOpenException` vs `TestException`).
   - Couplage avec l'unwrap async §8.2 dans `PolicyComposer`.
3. **Timeout asynchrone non-interruptible (`TimeoutUninterruptableTest`, 4 échecs)**
   - Durée mesurée < attendue (subtask annulée mais résultat retourné trop vite) ;
     interaction `@Timeout` + `@Asynchronous` + `@Bulkhead` à revoir.
4. **Désactivation globale (`DisableTest`, 4 échecs)**
   - `Retry`, `Timeout`, `Fallback`, `CircuitBreaker` non désactivés correctement
     quand `<Annotation>/enabled=false` via classe de test (vs propriété MP Config globale).
5. **Validations de définitions invalides au déploiement (~6 échecs)**
   - `FallbackMethodGenericTest`, `FallbackMethodGenericDeepTest`,
     `FallbackMethodGenericArrayTest`, `FallbackMethodPrivateTest`,
     `FallbackMethodWildcardNegativeTest`, `IncompatibleFallbackTest`.
   - `HeisenbergExtension` résout incorrectement les `fallbackMethod` avec signatures
     génériques (varargs, wildcards) et n'échoue pas sur méthodes `private` /
     incompatible-types comme l'attend la spec.
6. **Re-priorisation intercepteur par annotation (`FaultToleranceInterceptorPriorityChangeAnnotationConfTest`)**
   - Bootstrap KO : la valeur lue depuis `@FaultToleranceDefinitionAnnotation` n'est
     pas appliquée par `HeisenbergExtension.configureInterceptorPriority`.
7. **Tests divers**
   - `RetryConditionTest` (3), `AsyncCancellationTest` (3), `FallbackConfigTest` (2),
     `CircuitBreakerBulkheadTest` (2), `CircuitBreakerLifecycleTest` (1),
     `ConfigPropertyGlobalVsClassTest` (1), divers (1 chacun).
8. **Métriques / télémétrie** (exclus du profil, voir plus bas)
   - `UnsatisfiedResolutionException` sur `MetricRegistryProxy`.
   - `InMemoryMetricReader has not been registered` sur les tests telemetry.

## Plan de remédiation immédiat

1. **Lot A — Bulkhead async (priorité haute, plus gros gain attendu, ~21 échecs)**
   - Aligner `BulkheadEngine.executeAsync` sur la sémantique TCK : le permit doit être
     pris avant de signaler la fin de l'enqueue, et la file d'attente doit refléter
     les tâches `awaiting` de façon observable.
   - Cible : ramener `BulkheadAsynchTest` + `BulkheadAsynchRetryTest` +
     `BulkheadFutureTest` à 100 % PASS.
2. **Lot B — CB + Retry async + Timeout uninterruptable (~8 échecs)**
   - Vérifier la propagation `CircuitBreakerOpenException` à travers l'unwrap §8.2.
   - Réviser `AsynchronousEngine` × `TimeoutEngine` pour les méthodes non interruptibles.
3. **Lot C — Validations Fallback invalides au déploiement (~6 échecs)**
   - Renforcer `HeisenbergExtension` : refuser fallbackMethod private, signatures
     génériques incompatibles, types de retour mismatchés, varargs/wildcards.
4. **Lot D — Disable/PriorityChange + petits clusters (~12 échecs)**
   - Réviser le chemin de désactivation par classe et la lecture de `@Priority`
     via annotation TCK.
5. **Lot E — Metrics / telemetry**
   - Réactiver les exclusions et ajouter le wiring minimal requis.
6. Rejouer `all` après chaque lot et tracer la progression dans la table ci-dessus.

## Commandes de référence

```bash
./run-official-tck-mp-fault-tolerance-4.1.sh all
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=RetryTest
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=CircuitBreakerLifecycleTest
```

## Tests exclus

Exclusions temporaires actives dans `heisenberg-tck/pom.xml` (profil `tck-official`) :

- `**/telemetry/**`, `**/*Telemetry*Test.class`

Justification : implémentation MP Telemetry non livrée à date.
Réactivation prévue dès que l'implémentation MP Telemetry sera disponible.

Les tests MP Metrics sont **actifs** depuis l'intégration de Dirac (`dirac-cdi-vauban:0.1.0-SNAPSHOT`).

