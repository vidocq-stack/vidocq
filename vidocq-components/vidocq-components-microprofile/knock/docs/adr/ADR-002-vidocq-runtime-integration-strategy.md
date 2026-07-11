# ADR-002 — Knock Integration Strategy in the Vidocq Ecosystem

- Status: **Accepted** (M5, May 2026)
- Deciders: Knock team + vidocq team
- Related to: `ROADMAP.md` §M5, `docs/integration-cassini.md`, `docs/integration-vidocq-runtime.md`

## Context

Knock is delivered with four artifacts (`knock-api`, `knock-core`, `knock-cdi-vauban`,
`knock-cassini`) and an official TCK at 100% PASS (28/28). The decision now is
*how* Knock lands in Vidocq applications, without imposing boilerplate on the user
or breaking the modular separation (standalone SE `knock-core`, optional CDI/JAX-RS adapters).

## Considered Options

### Option A — Dedicated `VidocqExtension`

Create a `KnockExtension implements VidocqExtension` in
`vidocq-runtime-knock-health-extension`, which explicitly starts/stops the registry and
calls Cassini to mount the resource.

- **+** Explicit lifecycle, dedicated bootstrap log.
- **−** Duplicates what `CassiniExtension` already does (`@Path` bean scanning).
- **−** Couples Knock to a detail of the vidocq SPI API (priority, order).
- **−** Goes against Knock's *zero-config* design (no beans, no services, just standard CDI annotations).

### Option B — Maven/JPMS wrapper only (chosen)

`vidocq-runtime-knock-health-extension` is merely a dependency aggregate + a
`module-info` that `requires transitive` the Knock + champollion modules.
No own Java classes. The integration relies 100% on the **standard** SPIs that already exist:

1. CDI auto-registration (`HealthCheckRegistrar`, exposed to Vauban via the generated
   `VaubanComponentProvider` + JPMS `provides`) — discovers and registers
   `@Liveness/@Readiness/@Startup` beans on `@Initialized(ApplicationScoped.class)`.
   A `HealthCheck` bean without a probe qualifier is not a procedure and is silently
   ignored (spec §4.2) — it is never a deployment error;
2. JAX-RS scanning of CDI `@Path` beans (`CassiniExtension` already queries
   `VaubanBeanProvider.getResourceClasses()`) — mounts `KnockHealthResource`.

- **+** Zero Java code to maintain on the vidocq side.
- **+** Knock remains usable outside vidocq with the exact same deps.
- **+** No coupling with the `VidocqExtension` API (priority, hooks).
- **+** Adding / removing the dependency is enough to enable / disable.
- **−** No dedicated *Knock* bootstrap log (logs come from Cassini:
  "` 1 resource class(es)`"). Mitigable by an `INFO` in `KnockHealthResource`
  on the `@PostConstruct` side if needed.

### Option C — Direct inclusion in `vidocq-runtime-core`

Not modular. Would force `vidocq-runtime-core` to depend on `jakarta.ws.rs` and
Cassini, breaking the *core can run without REST* contract.

## Decision

**Option B** chosen: `vidocq-runtime-knock-health-extension` is a Maven/JPMS
*wrapper* module, without Java code. It lives in
`vidocq/vidocq-runtime-core-extensions/vidocq-runtime-knock-health-extension/` and publishes
the artifact `io.vidocq.runtime:vidocq-runtime-knock-health-extension`.

```
vidocq-runtime-knock-health-extension/
├── pom.xml                         (deps: knock-cdi-vauban, knock-cassini,
│                                    vidocq-runtime-cassini-rest-extension,
│                                    champollion-jsonp runtime)
└── src/main/java/module-info.java  (requires transitive)
```

## Consequences

### Positive

- A vidocq project user activates Knock by adding **a single**
  dependency; the `/health*` endpoints appear at the next startup.
- Knock can be versioned and published independently of vidocq. The wrapper
  only reflects Maven coordinates, not coupled code.
- The Knock TCK remains *self-contained*: the Arquillian runner
  (`KnockDeployableContainer`) reproduces exactly the same integration path
  (Cassini + Vauban embedded + `KnockHealthResource`), so validating M4 also validates
  the M5 path.

### Negative / to monitor

- If `CassiniExtension` changes how it discovers `@Path` beans,
  Knock may be impacted. → covered by Cassini TCK (continuous) and Knock TCK (continuous).
- No *vidocq-runtime-side* extension point to interpose middleware before
  probes (auth, rate limit). → if the need arises, create an optional module
  `vidocq-runtime-knock-secure-extension` that *in addition* to the wrapper exposes
  a `ContainerRequestFilter`. Out of scope for M5.

## Deployment Order

1. `mvn -pl knock-api,knock-core,knock-cdi-vauban,knock-cassini -am install -DskipTests`
   in the `knock` repo (already covered by `run-official-tck-mp-health-4.0.sh`).
2. `mvn -pl vidocq-runtime-core-extensions/vidocq-runtime-knock-health-extension -am install -DskipTests`
   in the `vidocq` repo.
3. Any application that depends on `vidocq-runtime-knock-health-extension` gets Knock
   transitively, without anything else.

## Risks

| Risk                                                                   | Mitigation                                                                                  |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|
| `jakarta.json-api` version conflict between champollion and the host   | Parent vidocq `dependencyManagement` fixes the version (aligned 2.1.x); champollion runtime-only |
| Knock BCE not discovered (ServiceLoader) in strict JPMS                | Double declaration: `META-INF/services/` + JPMS `provides` in `module-info`                |
| Cassini does not scan `KnockHealthResource` if not in `annotated` mode | CDI 4.1 defaults to `annotated` mode; bean explicitly annotated `@ApplicationScoped`       |
| Knock TCK regresses due to a new Cassini version                       | Knock TCK runs via `./run-official-tck-mp-health-4.0.sh all` on every PR                  |

## References

- MicroProfile Health 4.0 §3 (endpoints), §4 (qualifiers), §6 (config)
- `ADR-001-jpms-workaround-microprofile-health.md` (module-info workaround for testCompile)
- `knock-tck/src/test/java/io/vidocq/knock/tck/arquillian/KnockDeployableContainer.java`
  (Arquillian runner — reproduces the M5 integration path)
