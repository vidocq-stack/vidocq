# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Miguel de Cervantes wrote *Don Quixote*, a hidalgo who *proclaims* himself a knight and demands
> the world accept his titles as true. The Cervantes project implements **MicroProfile JWT 2.1**:
> it receives identity claims asserted by a token bearer and, unlike the windmills of La Mancha,
> actually verifies them — signature, issuer, audience, expiration — before granting any role.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- The official TCK `org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1` must be installed
  in the local M2 (non-public artifact — see `cervantes-tck/README.md`)

## Essential Commands

```bash
# SDK environment
sdk env

# Build reactor (without TCK)
./mvnw -ntp install -DskipTests

# Unit tests
./mvnw test

# TCK — smoke test / full suite / targeted test
./run-official-tck-mp-jwt-2.1.sh
./run-official-tck-mp-jwt-2.1.sh all
./run-official-tck-mp-jwt-2.1.sh -Dtest=TestName
```

> `cervantes-tck` is **out-of-reactor** (standalone POM Model 4.0.0, without `<parent>`) to work
> around ShrinkWrap Maven Resolver 3.3 vs Model 4.1.0 incompatibility. Do not change this model.
> See workspace root `CLAUDE.md` § *Critical architecture constraint: out-of-reactor TCK runners*.

## Architecture

Cervantes is a **MicroProfile JWT 2.1** implementation with zero implementation dependencies:
crypto via `java.security`, JSON via Champollion (JSON-P), config via Ravel (MP Config), CDI via
Vauban, JAX-RS security via Cassini.

```
cervantes-mp-jwt-api   ← MP JWT 2.1 spec repackaged as named module (org.eclipse.microprofile.jwt)
cervantes-api          ← stable public SPI (JwtValidator, KeyResolver, TokenHolder, JwtConfig)
cervantes-core         ← pure engine: JWT parsing, signature verification (RS/ES 256/384/512),
                         claim validation (iss/aud/exp/nbf/iat + clock-skew), key loading (PEM, JWKS).
                         NO cassini, NO CDI — testable without HTTP.
cervantes-cdi-vauban   ← @RequestScoped JsonWebToken producer, @Claim injection (BCE Vauban)
cervantes-cassini      ← JAX-RS security: JwtAuthenticationFilter, RolesAllowedDynamicFeature
                         (@RolesAllowed/@PermitAll/@DenyAll), SecurityContext backed by JsonWebToken
cervantes-bench        ← JMH benchmarks (vs SmallRye JWT)
cervantes-examples     ← examples (@RolesAllowed resource + @Inject @Claim)
cervantes-tck          ← Arquillian official TCK runner (out-of-reactor — Model 4.0.0)
```

**Fundamental separation:** `cervantes-core` knows neither HTTP nor CDI (pure validation,
unit-testable with generated key pairs). `cervantes-cassini` wires in JAX-RS security;
`cervantes-cdi-vauban` wires in CDI injection. A per-request `TokenHolder` (in `cervantes-api`)
connects the auth filter (cassini) to the CDI producer (cdi-vauban) without direct coupling.

## Architecture Constraints Not to Violate

1. **Zero third-party crypto/JWT library** (no Bouncy Castle, jose4j, Nimbus, SmallRye). Everything
   goes through `java.security` (`Signature`, `KeyFactory`, `X509EncodedKeySpec`), `javax.crypto`
   (JWE), `java.util.Base64.getUrlDecoder()`, and `jakarta.json` via Champollion.
2. **`cervantes-core` depends on NEITHER cassini NOR CDI.**
3. **Virtual-thread-friendly**: no `synchronized` around I/O (JWKS fetch), JDK `HttpClient`.
   Prefer replacing the JAX-RS `SecurityContext` (`setSecurityContext`) rather than a `ThreadLocal`
   (pinning risk).
4. **No runtime reflection / no dynamic proxy**: `@Claim` injection and `@RolesAllowed` enforcement
   via BCE Vauban + `MethodHandle`, no `setAccessible(true)` in production.
5. **`cervantes-tck/pom.xml` stays at Model 4.0.0**, outside `<subprojects>`.
6. **TCK 100% PASS is a hard contract** once reached (M6).

## Conventions

- **Explicit Java modules**: `src/main/java/module-info.java`, or `src/main/module-info/` +
  clean/compile/surefire workaround for modules with test dependencies lacking
  Automatic-Module-Name (see `cervantes-core`, pattern from heisenberg).
- **Packages**: `io.vidocq.cervantes.api.*` = stable public SPI;
  `io.vidocq.cervantes.internal.*` = internal code; `io.vidocq.cervantes.cdi.*` = CDI integration;
  `io.vidocq.cervantes.cassini.*` = JAX-RS integration.
- **Maven groupId**: `io.vidocq.cervantes`. Version: `0.1.0-SNAPSHOT` (parent `vidocq-parent:1.0.0-SNAPSHOT`).
- **Immutable records** for configs (JwtConfig, claims), **sealed interfaces** for validation results.
- **Language** — commit messages, Javadoc, and all `.md` file content must be written in **English**.

## TDD Methodology

- **Red → Green → Refactor** — no production line without prior test.
- Cite the MicroProfile JWT 2.1 spec section (and RFC 7515/7519/7517) in test Javadoc.
- No Mockito — manual doubles, key pairs generated on the fly (`KeyPairGenerator`).
- CDI integration tests via embedded Vauban (without Arquillian) in `cervantes-cdi-vauban`.

## Available Agents

- `jpms-guardian` — after any `module-info.java` modification or package addition
- `virtual-threads-reviewer` — for JWKS fetch (HttpClient + cache) and auth filter
- `dependency-gatekeeper` — before any dependency addition (zero-dep philosophy)
- `tck-runner` — to diagnose MP JWT 2.1 TCK failures
- `classfile-codegen` — if `@Claim` injection requires static generation

## Allowed Spec Dependencies

```
org.eclipse.microprofile.jwt:microprofile-jwt-auth-api:2.1   (repackaged via cervantes-mp-jwt-api)
jakarta.json (via io.vidocq.champollion:champollion-api)
jakarta.enterprise:jakarta.enterprise.cdi-api:4.1            (provided, cdi-vauban)
jakarta.ws.rs (via io.vidocq.cassini:cassini-api)            (provided, cassini)
io.vidocq.ravel:ravel-mp-config-api                          (config)
org.junit:junit-bom                                          (test)
```

Any new `compile`/`runtime` dependency must pass `dependency-gatekeeper` and be
justified in the PR.

## Status

See `ROADMAP.md` (source of truth for milestones M0–M8 and TCK score).
