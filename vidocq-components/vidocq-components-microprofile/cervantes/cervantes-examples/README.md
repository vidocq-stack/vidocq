# Cervantes :: Examples

Demonstrates securing a JAX-RS resource with **MicroProfile JWT 2.2** via Cervantes — both halves of
the spec in one resource ([`ProtectedResource`](src/main/java/io/vidocq/cervantes/examples/ProtectedResource.java)):

- **Authorization** — `@RolesAllowed` / `@PermitAll` / `@DenyAll`, enforced by Cervantes'
  `RolesAllowedDynamicFeature` (cervantes-cassini): the verified token's `groups` claim becomes the
  caller's roles.
- **Identity** — `@Inject @Claim` pulls typed claims of the current request's token into the
  request-scoped resource (cervantes-cdi-vauban).

```java
@Path("/api")
@RequestScoped
public class ProtectedResource {

    @Inject @Claim(standard = Claims.upn) String upn;        // standard claim, typed
    @Inject @Claim("groups")              Set<String> groups; // the caller's roles
    @Inject @Claim("upn")                 ClaimValue<String> upnValue; // lazy, request-bound

    @GET @Path("/public") @PermitAll          public String publicInfo() { ... }   // no token
    @GET @Path("/me")     @RolesAllowed({"user","admin"}) public String whoAmI() { ... }
    @GET @Path("/admin")  @RolesAllowed("admin") public String adminOnly() { ... } // groups must contain "admin"
}
```

## What the test shows

[`ProtectedResourceExampleTest`](src/test/java/io/vidocq/cervantes/examples/ProtectedResourceExampleTest.java)
boots an **embedded Vauban container** (no HTTP server), sets a request token, and asserts the
`@Inject @Claim` fields resolve into the resource — the identity layer the secured methods rely on.
Full HTTP-level authorization (the `@RolesAllowed` enforcement and a 401/403 round-trip) is covered
end-to-end by the Vidocq integration test `vidocq-runtime-it-cervantes-jwt`.

```bash
cd cervantes && sdk env
./mvnw -ntp -pl cervantes-examples -am test
```

## Wiring it into a real Chappe deployment

In a Vidocq runtime (or any Cassini + Vauban + Chappe stack), add the Cervantes modules and configure
the issuer / verification key (MP Config, e.g. `mp.jwt.verify.publickey.location`,
`mp.jwt.verify.issuer`). Cervantes then, per request:

1. `JwtAuthenticationFilter` (cervantes-cassini) extracts the `Authorization: Bearer …` token,
   validates it (`DefaultJwtValidator`: signature + iss/aud/exp), and installs a `SecurityContext`
   backed by the `JsonWebToken`.
2. `RolesAllowedDynamicFeature` enforces `@RolesAllowed` against the token's `groups`.
3. The CDI integration exposes the token's claims for `@Inject @Claim`.

No third-party JWT library is involved — verification is `java.security`, JSON is Champollion.
