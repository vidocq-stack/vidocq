# Cervantes — MicroProfile JWT 2.2

**MicroProfile JWT 2.2** ("JWT RBAC for MicroProfile") implementation for the
[Vidocq](https://codefloe.com/Vidocq) ecosystem, with **zero implementation dependencies**:
signature verification via `java.security`, JSON parsing via [Champollion](https://codefloe.com/Vidocq/champollion)
(JSON-P), configuration via [Ravel](https://codefloe.com/Vidocq/ravel) (MP Config), CDI injection
via [Vauban](https://codefloe.com/Vidocq/vauban), JAX-RS security via
[Cassini](https://codefloe.com/Vidocq/cassini).

> Don Quixote *claims* an identity the world must accept as true. Cervantes actually verifies
> the *claims* of a bearer token — signature, issuer, audience, expiration — before
> granting any role.

## What Cervantes provides

- Validation of **signed JWT bearer tokens**: `RS256/384/512` (RSA), `ES256/384/512` (ECDSA).
- Standard claim validation: `iss`, `aud`, `exp`, `nbf`, `iat` (with clock-skew tolerance).
- Public key loading: **PEM**, inline key, **JWKS** (URL or file, resolution by `kid`).
- CDI-injectable **`JsonWebToken`** principal (`@RequestScoped`) and **`@Claim`** injection.
- **`@RolesAllowed` / `@PermitAll` / `@DenyAll`** enforcement on JAX-RS endpoints, with a
  `SecurityContext` backed by the token's groups.
- (Optional) Decryption of **encrypted tokens (JWE)**: `RSA-OAEP` + `A256GCM`.
- Configuration via standard MP-Config properties: `mp.jwt.verify.publickey.location`,
  `mp.jwt.verify.issuer`, `mp.jwt.verify.audiences`, `mp.jwt.token.header`, …

## Prerequisites

**Java 25** (Temurin) + **Maven 3.9.16** — pinned via `.sdkmanrc`: `sdk env`.

## Build

```bash
sdk env
./mvnw -ntp install -DskipTests   # full build
./mvnw test                        # unit tests
./run-official-tck-mp-jwt-2.2.sh   # official TCK (microprofile-jwt-auth-tck:2.2 from Maven Central; `all` runs the full suite)
```

## Modules

| Module | Role |
|--------|------|
| `cervantes-mp-jwt-api` | MP JWT 2.2 spec repackaged as a named module (jlink-compatible) |
| `cervantes-api` | Stable public SPI + spec re-export |
| `cervantes-core` | Pure validation engine (signature, claims, keys) — no HTTP, no CDI |
| `cervantes-cdi-vauban` | `JsonWebToken` producer, `@Claim` injection (Vauban BCE) |
| `cervantes-jaxrs` | JAX-RS security (`@RolesAllowed`, auth filter, `SecurityContext`) |
| `cervantes-bench` | JMH benchmarks |
| `cervantes-examples` | Usage examples |
| `cervantes-tck` | Official TCK runner (in-reactor, built only under the `tck` Maven profile) |

## Status

**MicroProfile JWT 2.2 TCK: 208/208 PASS (2026-10-04).** See [`TCK.md`](TCK.md).

Under active development (milestones M0–M8 in [`ROADMAP.md`](ROADMAP.md)). License: Apache 2.0.
