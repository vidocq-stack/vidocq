# BUG.md — Kerckhoffs

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## KERCK-20261118-01 · 2026-11-18 · A BASIC definition loses `@Default` when the language model shows its default `qualifiers`

- **Symptom:** on Vauban with BUG-20261117-55 (the language model shows an annotation's default members), a `@BasicAuthenticationMechanismDefinition` that writes no `qualifiers` gives a mechanism without `@Default`: `select(HttpAuthenticationMechanism.class)` finds nothing (`MechanismCdiTest:60`, `FrozenExtensionTest:160`, `KerckhoffsDeployableContainer:257`). On today's Vauban, a definition that writes `qualifiers = BasicAuthenticationMechanism.class` out loses it the same way.
- **Minimal repro:** `MechanismCdiTest#aBasicDefinitionThatWritesTheDefaultQualifiersStaysTheDefaultMechanism` (`ExplicitDefaultQualifierApplication`), and the two tests above, red against the Vauban lane `stack/l1-batch-b` (acc49e64) in a private repository (`stack-l1b-kerckhoffs-red.log`).
- **Cause:** `KerckhoffsExtension.qualify` made the mechanism `@Default` only when `hasMember("qualifiers")` was false. `qualifiers()` defaults to `{BasicAuthenticationMechanism.class}` (security-api 4.0.0), which the language model now shows.
- **Spec:** Jakarta Security 4.0, section 2.4: the built-in beans "MUST have the qualifiers as set by their qualifiers attribute; either the default (as it appears in the API definition) or those as explicitly set". The default gives `@BasicAuthenticationMechanism`, as Kerckhoffs does; Kerckhoffs adds `@Default` on top, which the spec does not ask (a CDI bean with a qualifier other than `@Named` and `@Any` is not `@Default`), so that an application without a handler of its own injects its one mechanism. The default handler looks the mechanisms up "irrespective of any qualifiers" (section 2.2.1). That addition is kept as it is.
- **Status:** fixed 2026-11-18: a `qualifiers` that names the declared default alone is treated as an absent one, so the mechanism keeps `@BasicAuthenticationMechanism` and `@Default` on both Vaubans (`stack-l1b-kerckhoffs-green.log`; `stack-l1b-kerckhoffs-mainvauban.log` against today's Vauban).

## KERCK-20261115-01 · 2026-11-15 · `getAuthType()` answers `HTTP`, not `BASIC`

- **Symptom:** in a servlet protected by the BASIC mechanism, `HttpServletRequest.getAuthType()` returns `HTTP`; Servlet 6.1 expects `BASIC` (`HttpServletRequest.BASIC_AUTH`) for HTTP Basic.
- **Minimal repro:** Vidocq `vidocq-runtime-it-kerckhoffs-security`, `GET /whoami` with a valid `Authorization: Basic …`; assert `getAuthType()` equals `"BASIC"` (the assertion was removed from the IT, Vidocq#603).
- **Cause:** no built-in mechanism named its type in the message map, so `MechanismRequestAuthentication` (`kerckhoffs-foy`) fell back to its default `HTTP`.
- **Status:** fixed 2026-11-15: `HttpAuthenticationMechanismModule` publishes `BASIC` / `FORM` (`jakarta.servlet.http.authType`) after a successful built-in mechanism; a custom mechanism keeps the `HTTP` default. Vidocq's IT can assert `BASIC` again once this SNAPSHOT is deployed.

## KERCK-20261116-01 · 2026-11-16 · `@Decorator` of an `HttpAuthenticationMechanism` is not applied

- **Symptom:** official Security TCK `app-mem-basic-decorate` passes 1 of 2 tests: the decorator that must wrap the mechanism never runs.
- **Minimal repro:** `run-official-tck-security-4.0.sh app-mem-basic-decorate` (container or assembled runtime).
- **Cause:** Vauban (CDI Lite) applies no `@Decorator`, and the mechanism is a synthetic bean, so a decorator has nothing to attach to.
- **Status:** open, upstream (Vauban). A build-time decorator support is being assessed with the Vauban session.

## KERCK-20261116-02 · 2026-11-16 · REST endpoints are not protected by the Servlet authentication hook

- **Symptom:** official Security TCK `app-jaxrs` passes 4 of 8: requests to the REST resources bypass the authentication and authorization of Foy.
- **Minimal repro:** `run-official-tck-security-4.0.sh app-jaxrs`.
- **Cause:** Cassini mounts on Chappe outside Foy, so neither the context path nor the authentication hook sees its requests; the runtime security runner also has no REST extension.
- **Status:** fixed 2026-11-17. Cassini serves the REST resources as a servlet of Foy (`cassini-servlet`, Vidocq/cassini#148 and #149), the Vidocq REST extension picks that mode when Foy is there (`vidocq.rest.mode`, Vidocq/vidocq#634), and the runtime security runner carries both: `app-jaxrs` passes 8 of 8.
