# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Werner Heisenberg (1901–1976) a formulé le principe d'incertitude — on ne peut pas mesurer
> simultanément la position et la vitesse d'une particule avec précision arbitraire. Heisenberg
> le projet implémente **MicroProfile Fault Tolerance 4.1** : il encapsule l'incertitude du réseau
> et des services distants, et y répond avec ordre, mesure et résilience.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le TCK officiel `org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1`
  doit être installé dans le M2 local (artefact non-public — voir `heisenberg-tck/README.md`)

## Commandes essentielles

```bash
# Environnement SDK
sdk env

# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test
./run-official-tck-mp-fault-tolerance-4.1.sh

# TCK — suite complète
./run-official-tck-mp-fault-tolerance-4.1.sh all

# TCK — test ciblé
./run-official-tck-mp-fault-tolerance-4.1.sh -Dtest=NomDuTest
```

> `heisenberg-tck` est **hors reactor** (pom.xml en Model 4.0.0 standalone) pour contourner
> une incompatibilité ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0. Ne pas changer ce modèle.
> Voir `CLAUDE.md` racine Vidocq § *Contrainte d'architecture critique : TCK runners hors reactor*.

## Architecture

Heisenberg est une implémentation **MicroProfile Fault Tolerance 4.1** à zéro dépendance
d'implémentation : seules les APIs Jakarta EE et MicroProfile spécifiées sont utilisées.

```
heisenberg-api          ← Wrapping de la spec MP FT 4.1 + SPI Vidocq (StateRegistry, PolicyContext)
heisenberg-core         ← Moteurs de politiques purs Java 25, sans CDI (RetryEngine, TimeoutEngine,
                          CircuitBreakerEngine, BulkheadEngine, FallbackResolver, PolicyComposer)
heisenberg-cdi-vauban   ← Intercepteur CDI + BCE Vauban (HeisenbergExtension), délègue au core
heisenberg-bench        ← Benchmarks JMH vs SmallRye Fault Tolerance
heisenberg-tck          ← Runner TestNG+Arquillian TCK officiel (hors reactor — Model 4.0.0)
heisenberg-examples     ← Exemples d'utilisation
```

**Séparation fondamentale :** `heisenberg-core` contient les automates purs (logique de retry,
état du circuit breaker, sémaphores de bulkhead). `heisenberg-cdi-vauban` porte l'unique
`@Interceptor` CDI qui orchestre ces moteurs. Ainsi, les politiques sont testables unitairement
sans container CDI.

**Flux d'une invocation :** CDI intercepte l'appel via `FaultToleranceInterceptor.around()` →
`PolicyComposer` construit la chaîne de politiques dans l'ordre défini par la spec →
chaque moteur exécute sa logique (retry, timeout, circuit breaker, bulkhead, fallback) →
résultat ou exception remontés selon les règles de composition.

**Ordre de composition des politiques** (spec MP FT §2.5, de l'extérieur vers l'intérieur) :
`@Fallback` → `@CircuitBreaker` → `@Bulkhead` → `@Timeout` → `@Retry` → méthode réelle.

**Gestion d'état :** `StateRegistry` (singleton CDI `@ApplicationScoped`) maintient l'état
des `CircuitBreaker` et `Bulkhead` identifiés par `(BeanClass, Method)` — les beans
`@RequestScoped` créent de nouvelles instances mais l'état FT est partagé conformément à la spec.

## Contraintes d'architecture à ne pas violer

1. **Zéro import de librairie d'implémentation dans `heisenberg-core`** — uniquement les API
   specs : `microprofile-fault-tolerance-api`, `jakarta.interceptor-api`, `microprofile-config-api`.
   Pas de SmallRye, Hystrix, Resilience4j.
2. **Pas de `synchronized`, pas de `ThreadLocal`** — virtual-thread-friendly obligatoire.
   Utiliser `ReentrantLock` avec `tryLock`, `Semaphore`, `ConcurrentHashMap`, `ScopedValue`.
3. **Pas de `setAccessible(true)` en production** — utiliser `MethodHandles.privateLookupIn`
   si un accès interne est nécessaire ; documenter tout `opens` dans le `module-info`.
4. **Pas de `java.lang.reflect.Proxy`** pour les fallbacks — résolution par `MethodHandle`.
5. **`heisenberg-tck/pom.xml` reste en Model 4.0.0** — ne pas passer en 4.1.0 tant que
   ShrinkWrap n'est pas mis à jour (contrainte commune à tout l'écosystème Vidocq).
6. **TCK 100 % PASS est un contrat** — toute modification structurelle de `heisenberg-core`
   ou `heisenberg-cdi-vauban` doit préserver ce score avant merge.

## Conventions

- **Java modules explicites** : tous les sous-modules ont un `module-info.java`.
- **Packages** :
  - `io.vidocq.heisenberg.api.*` — SPI publique stable (PolicyContext, StateRegistry, etc.)
  - `io.vidocq.heisenberg.internal.*` — code interne, non exporté (moteurs, état, composition)
  - `io.vidocq.heisenberg.cdi.*` — intégration CDI (intercepteur, BCE)
- **Maven groupId** : `io.vidocq.heisenberg`
- **Sealed interfaces** : `PolicyResult` est une interface scellée (`Success`, `Failure`, `Fallback`)
- **Records** : privilégier les records immuables pour les configurations de politiques
  (`RetryConfig`, `CircuitBreakerConfig`, `BulkheadConfig`, `TimeoutConfig`)
- **Pattern matching** : utiliser `switch` sur types scellés dans le `PolicyComposer`
- **Virtual threads + `join(Duration)`** (Java 21+, finalisé) pour `@Timeout` — gère les timeouts
  avec annulation best-effort des virtual threads. `StructuredTaskScope` (JEP 505, finalisé Java 25)
  est disponible mais l'implémentation actuelle utilise `Thread.join(Duration)` pour rester compatible
  Java 21+.

## Méthodologie TDD

- **Red → Green → Refactor** — aucune ligne de production sans test préalable.
- Citer la section spec MicroProfile FT 4.1 dans le Javadoc/commentaire des tests.
- Tests unitaires dans le même package que la classe testée, nommés `<Classe>Test`.
- Pas de Mockito — doubles manuels ou intercepteurs JDK (`java.lang.reflect.InvocationHandler`)
  pour simuler les backends.
- Tests d'intégration CDI via Vauban embedded (sans Arquillian) dans `heisenberg-cdi-vauban`.
- Benchmarks JMH dans `heisenberg-bench` — comparatif vs SmallRye Fault Tolerance dès M3.

## Plan mode default

- Entrer en plan mode pour toute tâche non-triviale (ajout d'une politique, refactoring
  du `PolicyComposer`, modification du `StateRegistry`).
- Documenter les décisions d'architecture dans `ROADMAP.md` (section « Décisions actées »).
- Utiliser l'agent `virtual-threads-reviewer` pour toute modification de code concurrent.
- Utiliser l'agent `jpms-guardian` après tout ajout de package ou modification de `module-info.java`.

## Agents disponibles

- `classfile-codegen` — si un fallback nécessite de la génération de bytecode (improbable)
- `virtual-threads-reviewer` — pour `TimeoutEngine` (virtual threads + `join(Duration)`), `BulkheadEngine`
  (Semaphore sous virtual threads), toute modification du code concurrent
- `jpms-guardian` — après modification de `module-info.java` ou ajout de package
- `dependency-gatekeeper` — avant tout ajout de dépendance au `pom.xml`
- `tck-runner` — pour diagnostiquer les échecs TCK MicroProfile FT 4.1

## TCK MicroProfile Fault Tolerance 4.1

- Framework : **TestNG** (pas JUnit — contrainte du TCK officiel)
- Container Arquillian : Vauban embedded + Chappe (transport HTTP si nécessaire)
- Artefact TCK : `org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-tck:4.1`
- Fichier de suite : `heisenberg-tck/src/test/resources/tck-suite.xml`
- Propriété timeout : `org.eclipse.microprofile.fault.tolerance.tck.timeout.multiplier` (défaut 1.0)
- Score cible : **100 % PASS** (tous les tests de la suite)
- Challenges documentés dans `TCK.md` si des tests sont exclus avec justification

## Dépendances spec autorisées

```
org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api:4.1
jakarta.enterprise:jakarta.enterprise.cdi-api:4.1              (provided)
jakarta.interceptor:jakarta.interceptor-api:2.2                 (provided)
org.eclipse.microprofile.config:microprofile-config-api:3.1     (provided)
org.junit:junit-bom:6.0.3                                       (test, BOM)
```

Toute nouvelle dépendance `<scope>compile</scope>` ou `<scope>runtime</scope>` doit passer
le `dependency-gatekeeper` et être explicitement justifiée dans la PR.
