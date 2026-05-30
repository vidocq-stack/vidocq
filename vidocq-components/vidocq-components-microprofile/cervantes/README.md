# Cervantes — MicroProfile JWT 2.1

Implémentation **MicroProfile JWT 2.1** (« JWT RBAC for MicroProfile ») de l'écosystème
[Vidocq](https://codeberg.org/Vidocq), à **zéro dépendance d'implémentation** : vérification de
signature via `java.security`, parsing JSON via [Champollion](https://codeberg.org/Vidocq/champollion)
(JSON-P), configuration via [Ravel](https://codeberg.org/Vidocq/ravel) (MP Config), injection CDI
via [Vauban](https://codeberg.org/Vidocq/vauban), sécurité JAX-RS via
[Cassini](https://codeberg.org/Vidocq/cassini).

> Don Quichotte *revendique* une identité que le monde doit tenir pour vraie. Cervantes vérifie
> réellement les *claims* d'un bearer token — signature, émetteur, audience, expiration — avant
> d'accorder le moindre rôle.

## Ce que fournit Cervantes

- Validation de **bearer tokens JWT signés** : `RS256/384/512` (RSA), `ES256/384/512` (ECDSA).
- Validation des claims standard : `iss`, `aud`, `exp`, `nbf`, `iat` (avec tolérance de clock-skew).
- Chargement des clés publiques : **PEM**, clé inline, **JWKS** (URL ou fichier, résolution par `kid`).
- Principal **`JsonWebToken`** injectable en CDI (`@RequestScoped`) et injection **`@Claim`**.
- Application de **`@RolesAllowed` / `@PermitAll` / `@DenyAll`** sur les endpoints JAX-RS, avec un
  `SecurityContext` adossé aux groupes du token.
- (Optionnel) Déchiffrement des **tokens chiffrés (JWE)** : `RSA-OAEP` + `A256GCM`.
- Configuration via les propriétés MP-Config standard : `mp.jwt.verify.publickey.location`,
  `mp.jwt.verify.issuer`, `mp.jwt.verify.audiences`, `mp.jwt.token.header`, …

## Prérequis

**Java 25** (Temurin) + **Maven 3.9.16** — pinés via `.sdkmanrc` : `sdk env`.

## Build

```bash
sdk env
./mvnw -ntp install -DskipTests   # build complet
./mvnw test                        # tests unitaires
./run-official-tck-mp-jwt-2.1.sh   # TCK officiel (artefact non-public à installer, voir cervantes-tck/README.md)
```

## Modules

| Module | Rôle |
|--------|------|
| `cervantes-mp-jwt-api` | Spec MP JWT 2.1 repackagée en module nommé (jlink-compatible) |
| `cervantes-api` | SPI publique stable + ré-exposition de la spec |
| `cervantes-core` | Moteur de validation pur (signature, claims, clés) — sans HTTP ni CDI |
| `cervantes-cdi-vauban` | Producteur `JsonWebToken`, injection `@Claim` (BCE Vauban) |
| `cervantes-cassini` | Sécurité JAX-RS (`@RolesAllowed`, filtre d'auth, `SecurityContext`) |
| `cervantes-bench` | Benchmarks JMH |
| `cervantes-examples` | Exemples d'utilisation |
| `cervantes-tck` | Runner TCK officiel (hors reactor — Model 4.0.0) |

## Statut

En cours de développement (jalons M0–M8 dans [`ROADMAP.md`](ROADMAP.md)). Licence : Apache 2.0.
