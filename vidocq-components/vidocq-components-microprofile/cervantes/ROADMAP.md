# ROADMAP — Cervantes (MicroProfile JWT 2.1)

Source de vérité des jalons et du score TCK. TDD à chaque étape (rouge → vert → refactor).

## Jalons

- [x] **M0 — Skeleton & build vert.** Repo, parent pom (Model 4.1.0, `vidocq-parent:1.0.0`,
  `<subprojects>`), `cervantes-mp-jwt-api` (repackaging spec MP JWT 2.1), modules
  `api/core/cdi-vauban/cassini/bench/examples`, CI (`.forgejo/workflows`), docs, `.sdkmanrc`.
  Critère : `./mvnw -ntp install -DskipTests` vert.
- [x] **M1 — Core, tokens signés.** Décodage base64url + parsing JSON (Champollion),
  `JwtSignatureVerifier` (RS/ES 256/384/512 ; transcodage signature ECDSA JOSE R‖S ⇄ DER),
  `JwtClaimsValidator` (iss/aud/exp/nbf/iat + clock-skew), `ConfiguredKeyResolver` + `PemKeys`,
  `DefaultJsonWebToken`. **12 tests verts** (RSA, EC P-256, expiration, nbf, signature falsifiée,
  iss/aud, non-signé, clock-skew). `cervantes-api` + `cervantes-core` sortent du mode stub
  (skip deploy/javadoc retirés).
- [x] **M2 — JWKS & rotation.** `JwkParser` (RSA `n`/`e`, EC `crv`/`x`/`y` via JCA), `JwksSource`
  (HTTP `HttpClient` JDK / fichier), `JwksKeyResolver` (cache, refresh TTL, rotation sur `kid`
  inconnu borné par `minRefreshInterval`, fallback snapshot si fetch échoue, clé unique sans `kid`).
  Concurrence VT-friendly (`ReentrantLock` + `AtomicReference`). **+9 tests (21 au total dans core).**
- [x] **M3 — CDI (`cervantes-cdi-vauban`).** `JsonWebTokenContext` (`@RequestScoped`, posé par le
  filtre M4), `JsonWebTokenProducer` (`@Produces @RequestScoped JsonWebToken`, principal anonyme si
  absent), `JwtAuthConfigProducer` (lit `mp.jwt.verify.*` via Ravel → produit le `JwtValidator`),
  fabrique publique `KeyResolvers` (core : inline PEM / fichier PEM / fichier ou URL JWKS),
  `DefaultJsonWebToken.anonymous()`. **8 tests** (unit + intégration container Vauban). ⏳ **Injection
  `@Claim` typée reportée** — raffinement séparé (synthetic beans BCE, comme `@ConfigProperty` de
  Ravel qui a nécessité VAU-BCE-001) ; sera durcie avec le TCK (M6).
- [x] **M4 — Sécurité JAX-RS (`cervantes-cassini`).** `JwtSecurityContext` (adossé au `JsonWebToken`),
  `JwtAuthenticationFilter` (`@PreMatching` `@Priority(AUTHENTICATION)` : extrait le Bearer, valide,
  `setSecurityContext` + pose le `JsonWebTokenContext` ; 401 si invalide ; anonyme si absent),
  `RolesAllowedDynamicFeature` + `RolesAllowedRequestFilter` (`@RolesAllowed`/`@PermitAll`/`@DenyAll`,
  précédence méthode > classe, 401/403). **13 tests** (doubles JAX-RS). **API JAX-RS standard pure —
  AUCUNE modif cassini requise** (l'enforcement passe par `setSecurityContext`/`getSecurityContext`
  que `CassiniRequestContext` honore déjà). ⏳ `@Context SecurityContext` injecté *dans une ressource*
  reflétant le JWT exigera un patch cassini (`FieldInjector`) — reporté en M6/TCK (branche
  `pr/ybl/jwt-needs` sur cassini à ce moment-là). `@LoginConfig` : marqueur applicatif, non requis ici.
- [x] **M5 — JWE (tokens chiffrés).** `JweDecryptor` (désenveloppe `RSA-OAEP`/`RSA-OAEP-256`,
  déchiffre `A256GCM`, via `javax.crypto`), `PemKeys.privateKeyFromPem` (PKCS#8), façade `Jwe`,
  détection JWE (5 parties) + déchiffrement dans `DefaultJwtValidator`, câblage
  `mp.jwt.decrypt.key`/`.location` dans `JwtAuthConfigProducer`. **+6 tests.** ℹ️ Content-enc
  `A128CBC-HS256` non encore supporté (seul `A256GCM`, le défaut spec) — à ajouter si le TCK l'exige.
- [ ] **M6 — TCK officiel.** `cervantes-tck` hors reactor (Model 4.0.0), container Arquillian
  (chappe+cassini+vauban+cervantes), `run-official-tck-mp-jwt-2.1.sh`. Cible : 100 % PASS.
- [ ] **M7 — Bench & examples.** `cervantes-bench` (JMH vs SmallRye JWT, → `BENCH.md`),
  `cervantes-examples`.
- [ ] **M8 — Wrapper runtime (repo `vidocq`).** `vidocq-runtime-cervantes-jwt-extension`
  (`VidocqExtension`, priorité ~500), ajout au reactor `vidocq-runtime-core-extensions` + jlink +
  IT `vidocq-runtime-it-cervantes-jwt`.

## Score TCK

| Date | Suite | PASS | FAIL | SKIP | Note |
|------|-------|------|------|------|------|
| —    | MP JWT 2.1 | — | — | — | non lancé (M6) |

## Décisions actées

- **2026-05-26** — Modules `core/cdi-vauban/cassini/bench/examples` créés en stubs vides à M0 avec
  `maven.deploy.skip` + `maven.javadoc.skip` (le canari javadoc du profil de deploy échoue sur un
  module sans API publique, cf. heisenberg-examples). Ces flags sont retirés dès que le module
  porte ses premières classes publiques (M1/M3/M4) ; `bench`/`examples` les conservent.
- **2026-05-26** — `cervantes-mp-jwt-api` repackage la spec avec un module-info ne déclarant que
  des `exports` (zéro `requires`) ; les consommateurs déclarent leurs propres `requires`
  (`jakarta.json`, `jakarta.cdi`). Même approche que `heisenberg-mp-ft-api`. Nom de module choisi :
  `org.eclipse.microprofile.jwt`.
- **2026-05-26** — Astuce JPMS de `cervantes-core` : le nettoyage du `module-info.class` est lié à
  `process-sources` (et non `generate-test-sources` comme heisenberg) pour que les builds
  *incrémentaux* sans `clean` ne basculent pas `default-compile` en mode module. No-op sur build propre.
- **2026-05-26** — Défaut Vauban actuel : un bean `@Produces` dont le PRODUIT est normal-scopé
  (`@ApplicationScoped`) est mal résolu (le proxy du producteur est casté vers le type produit →
  `ClassCastException`). Contournement : producteur ET produit en `@Dependent` (pattern
  `RavelConfigProducer`). `JwtAuthConfigProducer.jwtValidator()` repassera en `@ApplicationScoped`
  une fois le défaut Vauban corrigé.
