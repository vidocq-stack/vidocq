# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Miguel de Cervantes a écrit *Don Quichotte*, un hidalgo qui se *proclame* chevalier et somme
> le monde de tenir ses titres pour vrais. Cervantes le projet implémente **MicroProfile JWT 2.1** :
> il reçoit les *claims* d'identité revendiqués par un porteur de token et, contrairement aux
> moulins de La Manche, les vérifie réellement — signature, émetteur, audience, expiration — avant
> de leur accorder le moindre rôle.

## Prérequis

- **Java 25** + **Maven 4.0.0-rc-5** (`.sdkmanrc` fourni — utiliser `sdk env`)
- Le TCK officiel `org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1` doit être installé
  dans le M2 local (artefact non-public — voir `cervantes-tck/README.md`)

## Commandes essentielles

```bash
# Environnement SDK
sdk env

# Build du reactor (sans TCK)
./mvnw -ntp install -DskipTests

# Tests unitaires
./mvnw test

# TCK — smoke test / suite complète / test ciblé
./run-official-tck-mp-jwt-2.1.sh
./run-official-tck-mp-jwt-2.1.sh all
./run-official-tck-mp-jwt-2.1.sh -Dtest=NomDuTest
```

> `cervantes-tck` est **hors reactor** (pom.xml en Model 4.0.0 standalone, sans `<parent>`) pour
> contourner une incompatibilité ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0. Ne pas changer ce
> modèle. Voir `CLAUDE.md` racine Vidocq § *Contrainte d'architecture critique : TCK runners hors reactor*.

## Architecture

Cervantes est une implémentation **MicroProfile JWT 2.1** à zéro dépendance d'implémentation :
crypto via `java.security`, JSON via Champollion (JSON-P), config via Ravel (MP Config), CDI via
Vauban, sécurité JAX-RS via Cassini.

```
cervantes-mp-jwt-api   ← spec MP JWT 2.1 repackagée en module nommé (org.eclipse.microprofile.jwt)
cervantes-api          ← SPI publique stable (JwtValidator, KeyResolver, TokenHolder, JwtConfig)
cervantes-core         ← moteur pur : parsing JWT, vérif signature (RS/ES 256/384/512), validation
                         des claims (iss/aud/exp/nbf/iat + clock-skew), chargement clés (PEM, JWKS).
                         AUCUN cassini, AUCUN CDI — testable hors HTTP.
cervantes-cdi-vauban   ← producteur @RequestScoped JsonWebToken, injection @Claim (BCE Vauban)
cervantes-cassini      ← sécurité JAX-RS : JwtAuthenticationFilter, RolesAllowedDynamicFeature
                         (@RolesAllowed/@PermitAll/@DenyAll), SecurityContext adossé au JsonWebToken
cervantes-bench        ← benchmarks JMH (vs SmallRye JWT)
cervantes-examples     ← exemples (ressource @RolesAllowed + @Inject @Claim)
cervantes-tck          ← runner Arquillian TCK officiel (hors reactor — Model 4.0.0)
```

**Séparation fondamentale :** `cervantes-core` ne connaît ni HTTP ni CDI (validation pure,
testable unitairement avec des paires de clés générées). `cervantes-cassini` y branche la
sécurité JAX-RS ; `cervantes-cdi-vauban` y branche l'injection CDI. Un `TokenHolder` per-request
(dans `cervantes-api`) relie le filtre d'auth (cassini) au producteur CDI (cdi-vauban) sans
couplage direct.

## Contraintes d'architecture à ne pas violer

1. **Zéro librairie crypto/JWT tierce** (pas de Bouncy Castle, jose4j, Nimbus, SmallRye). Tout
   passe par `java.security` (`Signature`, `KeyFactory`, `X509EncodedKeySpec`), `javax.crypto`
   (JWE), `java.util.Base64.getUrlDecoder()`, et `jakarta.json` via Champollion.
2. **`cervantes-core` ne dépend NI de cassini NI de CDI.**
3. **Virtual-thread-friendly** : pas de `synchronized` autour d'I/O (fetch JWKS), `HttpClient` JDK.
   Préférer remplacer le `SecurityContext` JAX-RS (`setSecurityContext`) plutôt qu'un `ThreadLocal`
   (risque de pinning).
4. **Pas de réflexion à chaud / pas de proxy dynamique** : injection `@Claim` et application
   `@RolesAllowed` par BCE Vauban + `MethodHandle`, pas de `setAccessible(true)` en production.
5. **`cervantes-tck/pom.xml` reste en Model 4.0.0**, hors `<subprojects>`.
6. **TCK 100 % PASS est un contrat** une fois atteint (M6).

## Conventions

- **Java modules explicites** : `src/main/java/module-info.java`, ou `src/main/module-info/` +
  workaround clean/compile/surefire pour les modules ayant des tests à dépendances sans
  Automatic-Module-Name (cf. `cervantes-core`, pattern repris de heisenberg).
- **Packages** : `io.vidocq.cervantes.api.*` = SPI publique stable ;
  `io.vidocq.cervantes.internal.*` = code interne ; `io.vidocq.cervantes.cdi.*` = intégration CDI ;
  `io.vidocq.cervantes.cassini.*` = intégration JAX-RS.
- **Maven groupId** : `io.vidocq.cervantes`. Version : `0.1.0-SNAPSHOT` (parent `vidocq-parent:1.0.0-SNAPSHOT`).
- **Records immuables** pour les configs (JwtConfig, claims), **sealed interfaces** pour les
  résultats de validation.

## Méthodologie TDD

- **Red → Green → Refactor** — aucune ligne de production sans test préalable.
- Citer la section de la spec MicroProfile JWT 2.1 (et RFC 7515/7519/7517) dans le Javadoc des tests.
- Pas de Mockito — doubles manuels, paires de clés générées à la volée (`KeyPairGenerator`).
- Tests d'intégration CDI via Vauban embarqué (sans Arquillian) dans `cervantes-cdi-vauban`.

## Agents disponibles

- `jpms-guardian` — après toute modification de `module-info.java` ou ajout de package
- `virtual-threads-reviewer` — pour le fetch JWKS (HttpClient + cache) et le filtre d'auth
- `dependency-gatekeeper` — avant tout ajout de dépendance (philosophie zéro-dep)
- `tck-runner` — pour diagnostiquer les échecs TCK MP JWT 2.1
- `classfile-codegen` — si l'injection `@Claim` nécessite de la génération statique

## Dépendances spec autorisées

```
org.eclipse.microprofile.jwt:microprofile-jwt-auth-api:2.1   (repackagée via cervantes-mp-jwt-api)
jakarta.json (via io.vidocq.champollion:champollion-api)
jakarta.enterprise:jakarta.enterprise.cdi-api:4.1            (provided, cdi-vauban)
jakarta.ws.rs (via io.vidocq.cassini:cassini-api)            (provided, cassini)
io.vidocq.ravel:ravel-mp-config-api                          (config)
org.junit:junit-bom                                          (test)
```

Toute nouvelle dépendance `compile`/`runtime` doit passer le `dependency-gatekeeper` et être
justifiée dans la PR.

## État

Voir `ROADMAP.md` (source de vérité des jalons M0–M8 et du score TCK).
