# Intégration Knock ↔ Cassini

> Comment exposer les endpoints MicroProfile Health 4.0 d'une application Cassini
> (Jakarta REST 4.0) via le module `knock-cassini`.

## Vue d'ensemble

`knock-cassini` fournit une unique ressource JAX-RS, `KnockHealthResource`, annotée
`@ApplicationScoped` + `@Path("/health")` (avec sous-chemins `/live`, `/ready`,
`/started`). Aucune classe interne Cassini n'est importée — uniquement l'API
`jakarta.ws.rs` standard.

```
┌────────────────────────────────────────────────┐
│  Cassini (Jakarta REST 4.0)                    │
│   └─ scanne les @Path beans CDI                │
│      └─ KnockHealthResource (knock-cassini)    │
│         └─ @Inject HealthCheckRegistry         │
│            └─ KnockCdiHealthCheckRegistry      │
│               (knock-cdi-vauban)               │
│               └─ délègue à KnockHealthService  │
│                  (knock-core, JSON-P/champollion) │
└────────────────────────────────────────────────┘
```

## Dépendances Maven

```xml
<!-- compile : ressource JAX-RS, BCE, registry -->
<dependency>
    <groupId>io.vidocq.knock</groupId>
    <artifactId>knock-cassini</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.vidocq.knock</groupId>
    <artifactId>knock-cdi-vauban</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- runtime : implémentation Jakarta JSON-P utilisée par knock-core -->
<dependency>
    <groupId>io.vidocq.champollion</groupId>
    <artifactId>champollion-jsonp</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>runtime</scope>
</dependency>
```

`knock-cassini` tire transitivement `knock-core` et `knock-api` (qui ré-exporte la
spec MP Health 4.0). Aucune dépendance vers Cassini *runtime* : Knock dépend
uniquement de `jakarta.ws.rs` (API) et fonctionne avec n'importe quelle implémentation
JAX-RS 4.0 conforme.

## JPMS

```java
module my.app {
    requires io.vidocq.knock.cassini;     // tire knock-core via transitive
    requires io.vidocq.knock.cdi.vauban;  // BCE de découverte des @Liveness/@Readiness/@Startup
    requires io.vidocq.cassini.api;       // bootstrap CassiniStack (côté hôte)
    requires jakarta.cdi;
    requires jakarta.ws.rs;
}
```

L'extension CDI Build-Compatible (`HealthCheckCdiExtension`) est exposée via
`provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension`
dans `module-info` de `knock-cdi-vauban`, et également déclarée en
`META-INF/services/...BuildCompatibleExtension` (pour les ClassLoader-based
`ServiceLoader`).

## Découverte / déploiement

Sur Cassini bootstrappé via `CassiniStack.builder().beanProvider(vaubanBeanProvider)` :

1. Vauban scanne le classpath et instancie `KnockHealthResource` comme
   `@ApplicationScoped` (CDI 4.1 mode `annotated` — pas besoin de `beans.xml`).
2. Cassini interroge `BeanProvider.getResourceClasses()` et y trouve
   `KnockHealthResource`.
3. Au premier `GET /health/live`, Cassini résout l'instance via Vauban, déclenche
   `@Inject HealthCheckRegistry`, et invoque la méthode JAX-RS.
4. `KnockHealthService` (façade SPI runtime de `knock-core`) appelle tous les
   `HealthCheck` enregistrés en parallèle (virtual threads), agrège les statuts
   selon la spec MP Health 4.0 §3 (DOWN si ≥ 1 DOWN), sérialise en JSON-P via
   champollion, et la ressource construit la réponse `Response` avec HTTP 200/503.

## Endpoints

| Verbe | Chemin             | Probes                | HTTP UP | HTTP DOWN |
|-------|--------------------|-----------------------|---------|-----------|
| GET   | `/health`          | tous (`ProbeType.ALL`) | 200     | 503       |
| GET   | `/health/live`     | `@Liveness`            | 200     | 503       |
| GET   | `/health/ready`    | `@Readiness`           | 200     | 503       |
| GET   | `/health/started`  | `@Startup`             | 200     | 503       |

## Exemple : déclarer un check applicatif

```java
package com.example.app.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

@Liveness
@ApplicationScoped
public class DatabaseLivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        // ... ping JDBC, etc.
        return HealthCheckResponse.named("database")
                .status(true)
                .withData("responseTime", "12ms")
                .build();
    }
}
```

Aucun enregistrement manuel : `HealthCheckCdiExtension` (BCE) découvre tous les beans
qualifiés `@Liveness` / `@Readiness` / `@Startup` et les enregistre dans le registry
au phase `Validation` du conteneur.

## Configuration

Knock est *zero-config* : aucune propriété MP Config n'est requise. Si l'application
veut isoler les endpoints derrière un préfixe, c'est l'hôte JAX-RS (Cassini ou autre)
qui décide via son `ApplicationPath` ou le mount Chappe (`vidocq.rest.context-path`
côté `vidocq`).

## Vérification

Un smoke test de la ressource (sans container HTTP) est fourni dans
`KnockHealthResourceTest` (7/7 PASS) — il utilise un `TestRuntimeDelegate` minimal
local pour bypasser tout import interne Cassini. Pour une vérification end-to-end
contre Cassini réel, voir l'extension `vidocq-runtime-knock-extension`
(`docs/integration-vidocq-runtime.md`).

## TCK MicroProfile Health 4.0

`./run-official-tck-mp-health-4.0.sh all` exécute le TCK officiel
`microprofile-health-tck:4.0` contre la pile complète Knock + Cassini :
**28/28 PASS** (cf. `knock-tck/target/tck-report.txt`).

