# Intégration Knock ↔ vidocq

> Knock est le système de health check par défaut de vidocq. Cette page décrit
> comment l'activer, comment il interagit avec les autres extensions, et comment
> l'étendre dans une application MPS.

## Activation : une seule dépendance

L'agrégat `vidocq-runtime-knock-extension` regroupe les modules `knock-cdi-vauban` +
`knock-cassini` + l'implémentation Jakarta JSON-P (`champollion-jsonp`).
Ajouter cette dépendance suffit à exposer `/health*` :

```xml
<dependency>
    <groupId>io.vidocq.runtime</groupId>
    <artifactId>vidocq-runtime-knock-extension</artifactId>
</dependency>
```

(la version est gérée par le BOM `vidocq-runtime-parent`.)

Cet agrégat dépend transitivement de :

- `vidocq-runtime-cassini-rest-extension` — l'extension qui scanne les `@Path` beans
  via `BeanProvider` et construit le `CassiniStack` ;
- `knock-cassini` — la ressource `KnockHealthResource` (`@ApplicationScoped`,
  `@Path("/health")`) ;
- `knock-cdi-vauban` — la BCE qui auto-découvre les `@Liveness/@Readiness/@Startup` ;
- `knock-core` — registry, agrégateur, sérialisation JSON-P ;
- `champollion-jsonp` — implémentation runtime de `jakarta.json`.

## Cycle de vie au démarrage

```
VidocqBootstrap
 ├─ ChappeEngineExtension   (priorité 100) — moteur HTTP
 ├─ HealthCheckCdiExtension (BCE Knock)    — discover @Liveness/@Readiness/@Startup
 ├─ CassiniExtension        (priorité 500) — scanne @Path beans dont KnockHealthResource
 │     └─ mount(/) sur Chappe
 └─ ChappeServerBootstrap   — démarre le serveur HTTP
```

Au moment où `CassiniExtension.onStart()` interroge
`VaubanBeanProvider.getResourceClasses()`, Vauban a déjà :

1. Instancié les beans `HealthCheck` qualifiés (le bean utilisateur
   `@Liveness DatabaseCheck` et la ressource `KnockHealthResource`) ;
2. Validé le déploiement via `HealthCheckCdiExtension` (§4.2 spec MP Health 4.0) :
   un bean qui implémente `HealthCheck` *sans* qualifier MP provoque une erreur
   de validation (déploiement rejeté) ;
3. Exposé `KnockCdiHealthCheckRegistry` (`@ApplicationScoped`) injectable dans
   `KnockHealthResource`.

`KnockHealthResource` est donc disponible immédiatement après le démarrage du
serveur Chappe, sans extension Vidocq Runtime dédiée — `CassiniExtension` la mount
automatiquement comme n'importe quel `@Path` bean.

## Endpoints exposés

Avec un `vidocq.rest.context-path=/api` :

| Verbe | URL                       | Probes        |
|-------|---------------------------|---------------|
| GET   | `/api/health`             | tous          |
| GET   | `/api/health/live`        | `@Liveness`   |
| GET   | `/api/health/ready`       | `@Readiness`  |
| GET   | `/api/health/started`     | `@Startup`    |

Sans `vidocq.rest.context-path` (défaut `/`), les chemins sont `/health`,
`/health/live`, etc.

## Format de réponse (spec §3.1)

```json
{
  "status": "UP",
  "checks": [
    { "name": "database",          "status": "UP",   "data": { "responseTime": "12ms" } },
    { "name": "external-api-quota", "status": "DOWN", "data": { "remaining": 0 } }
  ]
}
```

HTTP 200 si `status=UP`, HTTP 503 si `status=DOWN`. Si aucun check n'est
enregistré pour une probe donnée, le statut est `UP` avec `checks: []` (cf.
`NoProcedureSuccessfulTest` du TCK).

## Écrire un check applicatif

```java
package com.example.shop.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

@Readiness
@ApplicationScoped
public class StockServiceReadinessCheck implements HealthCheck {

    @Inject StockServiceClient client;

    @Override
    public HealthCheckResponse call() {
        boolean ok = client.ping();
        return HealthCheckResponse.named("stock-service")
                .status(ok)
                .build();
    }
}
```

`@Inject` dans une `HealthCheck` est supporté (cf. `CDIProducedProceduresTest` du
TCK). L'exécution est en virtual thread, donc bloquer sur de l'I/O est sûr.

## Configuration MP Config (optionnelle)

Knock est *zero-config*. Aucune propriété `mp.health.*` n'est requise pour
fonctionner. La spec §6 prévoit `mp.health.disable-default-procedures` (désactiver
les checks par défaut) — Knock n'enregistre **aucun** check par défaut, donc
cette propriété est sans effet (le TCK valide ce comportement via `ConfigTest`).

## Désactivation

Retirer la dépendance `vidocq-runtime-knock-extension` suffit ; Knock n'expose aucun
service `VidocqExtension` dédié, son intégration est purement passive (BCE CDI
+ ressource JAX-RS scannée par Cassini).

## Vérification

```bash
# Démarrage de l'exemple
cd vidocq-runtime-examples/vidocq-runtime-cassini-rest-example
mvn -ntp -DskipTests package
java -p target/modules -m my.app/com.example.Main &

# Probes
curl -i http://localhost:8080/health/live
curl -i http://localhost:8080/health/ready
curl -i http://localhost:8080/health
```

## Référence TCK

`./run-official-tck-mp-health-4.0.sh all` (depuis le repo `knock`) exécute le
TCK officiel `microprofile-health-tck:4.0` contre la pile Knock complète :
**28/28 PASS**. Le runner Arquillian (`KnockDeployableContainer`) reproduit le
même chemin d'intégration que `vidocq-runtime-knock-extension` : Cassini + Vauban
embedded + `KnockHealthResource` montée sur un endpoint Chappe local.

