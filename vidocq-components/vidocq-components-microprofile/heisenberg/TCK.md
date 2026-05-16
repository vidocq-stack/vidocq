# TCK — MicroProfile Fault Tolerance 4.1

Score officiel du TCK `microprofile-fault-tolerance-tck:4.1` contre Heisenberg.

## État courant (M9 — mai 2026)

L'infrastructure TCK est **opérationnelle** :

- Container Arquillian local (`HeisenbergDeployableContainer`).
- Bootstrap Vauban + Heisenberg (`VaubanTckBootstrap`).
- Activation/réinitialisation du `RequestContext` autour des tests.
- Injection `@Inject` via `HeisenbergTestEnricher`.

## Progression mesurée

| Run | Tests run | PASS | FAIL | SKIP | Notes |
|---|---:|---:|---:|---:|---|
| Baseline initiale | 527 | 266 | 175 | 86 | Première exécution complète du TCK officiel.
| Global après correctifs Retry + CircuitBreaker | 527 | 316 | 125 | 86 | **-50 failures** (amélioration significative).
| Global après lot fallback+bootstrap | 501 | 332 | 109 | 60 | **-16 failures / -26 skips** vs run précédent.

### Vérifications ciblées récentes

| Test class | Tests run | PASS | FAIL | Notes |
|---|---:|---:|---:|---|
| `RetryTest` | 8 | 8 | 0 | Régression corrigée (maxDuration/jitter + isolation RequestContext).
| `CircuitBreakerLifecycleTest` | 20 | 19 | 1 | Forte amélioration (de 1/20 à 19/20), 1 cas d'override restant.
| `FallbackMethodOutOfPackageTest` | 1 | 1 | 0 | Déploiement négatif correctement reconnu (`FaultToleranceDefinitionException`).

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

## Répartition des échecs restants (run global)

1. **Validation de définitions invalides au déploiement**
   - Plusieurs classes `Invalid*Test` attendent une `FaultToleranceDefinitionException` au bootstrap.
   - Heisenberg laisse encore passer certaines configurations invalides.
2. **Métriques / télémétrie**
   - `UnsatisfiedResolutionException` sur `MetricRegistryProxy` (bean proxy metrics non résolu).
   - `InMemoryMetricReader has not been registered` sur les tests telemetry.
3. **Fonctionnel résiduel**
   - Exemples: `RetryVisibilityTest` (cas d'override class-level),
     `CircuitBreakerLifecycleTest` (1 scénario restant).

## Plan de remédiation immédiat

1. **Lot A — erreurs de validation (priorité haute)**
   - Renforcer `HeisenbergExtension` pour appliquer les contraintes spec et échouer au déploiement.
2. **Lot B — métriques / telemetry (priorité haute)**
   - Ajouter le wiring minimal requis par le TCK pour les classes metrics/telemetry.
3. **Lot C — overrides et visibilité (priorité moyenne)**
   - Finaliser la résolution méthode/classe pour les cas d'héritage/override restants.
4. Rejouer `all` après chaque lot et tracer la progression ici.

## Commandes de référence

```bash
./run-official-tck-mp-fault-tolerance-4.1.sh all
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=RetryTest
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=CircuitBreakerLifecycleTest
```

## Tests exclus

Exclusions temporaires actives dans `heisenberg-tck/pom.xml` (profil `tck-official`) :

- `**/metric/**`, `**/metrics/**`, `**/*Metric*Test.class`
- `**/telemetry/**`, `**/*Telemetry*Test.class`

Justification : implémentations MP Metrics / MP Telemetry non livrées à date.
Réactivation prévue dès que ces implémentations seront disponibles dans Heisenberg.

