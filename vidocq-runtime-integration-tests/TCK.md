# MicroProfile TCKs on the assembled Vidocq runtime

This directory hosts the official MicroProfile TCK runners (`vidocq-runtime-tck-*`).
Unlike the per-brick TCK runners that live in each implementation repository
(knock-tck, dirac-tck, …) and certify one implementation in isolation, these runners
certify **the assembled Vidocq runtime**: the exact boot path a real application uses.

Status: **1780 official TCK tests green** (2026-07-13), all 8 runners on the
embedded Vidocq container.

## How the TCKs run

All runners share the same Arquillian harness,
[`vidocq-runtime-arquillian`](vidocq-runtime-arquillian/):

1. **Materialization** — each ShrinkWrap archive the TCK produces is exploded to disk
   (`MaterializedDeployment`): classes, `WEB-INF/lib` jars (bean archives per the CDI
   spec — their classes and `microprofile-config.properties` are honored),
   `META-INF/services` files. A dedicated `URLClassLoader` is created per deployment
   and installed as the thread context class loader.
2. **Real boot** — `VidocqEmbeddedContainer` boots `VidocqBootstrap` exactly like a
   production application: extensions are discovered through the `ServiceLoader` SPI
   (`VidocqExtension`), lifecycle `configure → beforeStart → onStart → onStop`. The
   runtime under test is **not modified or bypassed** — each runner only declares the
   extension(s) an application would declare (e.g. `vidocq-runtime-knock-health-extension`).
3. **BCE discovery** — `BuildCompatibleExtension` service files found in the deployment
   are prepended to the bean class names so Vauban runs their **full** lifecycle
   (`@Discovery → @Synthesis`), not just the `@Enhancement` replay that plain
   `ServiceLoader` registration would give.
4. **Test enrichment** — `VidocqCdiTestEnricher` injects TCK test instances (fields and
   method parameters) from the running Vauban container, and recycles the CDI request
   context around each test.
5. **In-JVM protocol** — the Arquillian `Local` protocol: tests execute in the same JVM
   as the booted runtime; HTTP-facing TCKs hit the real Chappe server on pinned ports.

### Running them

The runners are gated behind the `tck` Maven profile — a normal `mvn install`
neither downloads nor runs anything TCK-related.

```bash
# from the vidocq repo root, after ./mvnw install -DskipTests
./mvnw -Ptck -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-knock-health test
# or all runners:
./mvnw -Ptck -pl vidocq-runtime-integration-tests -amd test
```

## Results (2026-07-13)

| Runner | Spec (MicroProfile 7.1) | Tests | Container |
|---|---|---:|---|
| `vidocq-runtime-tck-knock-health` | Health 4.0 | 28 | Vidocq embedded |
| `vidocq-runtime-tck-dirac-metrics` | Metrics 5.1 | 127 | Vidocq embedded |
| `vidocq-runtime-tck-cyrano-restclient` | Rest Client 4.0 | 168 | Vidocq embedded |
| `vidocq-runtime-tck-heisenberg-faulttolerance` | Fault Tolerance 4.1 | 463 | Vidocq embedded |
| `vidocq-runtime-tck-grimm-openapi` | OpenAPI 4.1 | 344 | Vidocq embedded |
| `vidocq-runtime-tck-cervantes-jwt` | JWT Auth 2.1 | 206 | Vidocq embedded |
| `vidocq-runtime-tck-humboldt-telemetry` | Telemetry 2.1 | 85 | Vidocq embedded |
| `vidocq-runtime-tck-ravel-config` | Config 3.1 | 349 | Vidocq embedded |
| **Total** | | **1780** | |

All suites run with **zero test exclusions**. Notably, the OpenAPI suite previously
passed 307 tests with 2 excluded classes under the old ad hoc harness; the assembled
runtime passes the full 344.

## TCK glue vs. runtime code

Each runner ships only the glue the TCK's own porting SPI requires — never patches to
the runtime:

| Runner | Glue | Why |
|---|---|---|
| cyrano | WireMock Arquillian listener | The Rest Client TCK mandates a WireMock endpoint |
| heisenberg | `FtMetricsRegistryProxyProducer`, `FtTckArchiveProcessor` | The FT TCK's `MetricGetter` resolves its own `MetricRegistryProxy` type via CDI; OTel state reset between deployments |
| cervantes | `JwtTckArchiveProcessor`, `mp.jwt.tck.jwks.baseURL` | Rewrites the TCK's hardcoded `http://localhost:8080/` to the pinned test port |
| grimm | pinned port + `test.url` | The OpenAPI TCK reads the server URL from system properties |
| humboldt | `HumboldtTckExecutor` | Required by the Telemetry TCK porting SPI (`telemetry.tck.executor`) |

The Telemetry runner deserves a note: the TCK's in-memory exporters/samplers/propagators
are OTel SDK autoconfigure SPI providers shipped inside each deployment. Humboldt's
`humboldt-otel-interop` module discovers them on the deployment class loader at boot —
a real interop feature of the runtime, not TCK wiring.

## Fixes the migration surfaced

Running the suites through the real boot path (instead of ad hoc harnesses) exposed
genuine bugs across the ecosystem, all fixed and merged (2026-07-13):

- **vauban** — synthetic beans registered twice by a BCE replay are now deduplicated;
  BCE `@Enhancement` failures carry their typed causes into the `DeploymentException`;
  `DotName` handles array/primitive descriptors.
- **knock** — a `HealthCheck` bean without a probe qualifier is silently ignored per
  spec §4.2 instead of failing deployment (fixed upstream by knock#10).
- **dirac** — the `MetricRegistry` producer is explicitly `@Default` alongside its
  `@RegistryScope` qualifier; lazy `Gauge<T>` producer for injected gauges.
- **heisenberg** — the BCE is registered via `META-INF/services`; FT validation is
  deferred when a class is not loadable (APT context).
- **grimm** — the OpenAPI model scans **all** application classes (spec §4.4), resets
  per boot, and reads static documents TCCL-first.
- **cassini** — a concrete `Application` subclass without an explicit scope defaults
  to `@ApplicationScoped` (JAX-RS §2.3.2 singleton semantics).
- **humboldt** — new `humboldt-otel-interop` module (OTel autoconfigure SPI bridge);
  the JAX-RS tracing filters are CDI-discoverable (`@Dependent` + Vauban APT provider).
- **vidocq** — `CassiniExtension` honors `@ApplicationPath` when
  `vidocq.rest.context-path` is left at its default.

The Config runner was the last to migrate (it initially stayed on Weld SE with
16 failures in 4 families, tracked as **BUG-20260713-01** in `ravel/BUG.md` —
now FIXED). That migration surfaced a second wave of fixes:

- **vauban** — synthetic beans registered with runtime array classes
  (`Boolean[].class`) resolve against `ArrayType` injection points;
  the build-time composite discovery loader exposes `getResources` of every
  source loader **and** of the caller-installed TCCL (deployment
  `microprofile-config.properties` / ServiceLoader `ConfigSource`s were
  invisible during BCE validation); `CDI.current().select(type, qualifiers…)`
  no longer drops its qualifiers and programmatic lookups expose a synthetic
  `InjectionPoint`; observer-method non-event parameters are validated as real
  injection points and exposed to BCEs.
- **ravel** — `ConfigCdiExtension` vetoes type-level `@ConfigProperties`
  classes itself (`@Enhancement` + `@Vetoed` — the portable exclusion extension
  never runs on CDI Lite) and re-validates their required fields in
  `@Validation`; the fallback `@Default Config` synthetic bean is skipped when
  a `Config` producer is already registered.
- **vidocq (this harness)** — the container no longer surfaces deployment
  config entries as system properties: the materialized deployment class
  loader makes them visible to MP Config naturally, with correct ordinals and
  `%profile.` semantics (the hack clobbered `config_ordinal` and broke the
  profile TCK tests).

Removing the system-property hack — and Vauban honoring programmatic-lookup
qualifiers again — surfaced accidental couplings in three more bricks, all
fixed the spec way:

- **cyrano** — `RestClientBuilder.build()` resolves
  `microprofile.rest.client.disable.default.mapper` through MP Config (spec
  §8.1); the Rest Client and Telemetry runners now assemble the Ravel Config
  extension, as a complete MP runtime would (the `mp-rest/url` keys and
  `otel.*` settings the TCK archives ship are MP Config properties).
- **dirac** — deprecated `@RegistryType` selections alias the same-named
  scope registries and never resolve `null` (Metrics TCK asserts
  `getScope()` on the legacy path).
- **heisenberg** — the FT metrics recorder publishes in the **base-scope**
  registry (FT 4.1 §9): the FT TCK reads them back through
  `@RegistryType(BASE)`, which had silently resolved `@Default` while
  qualifiers were dropped.
- **humboldt** — `HumboldtExtension` bridges every `otel.*` key from MP
  Config (reflectively; system properties and env vars remain the fallback on
  Config-less runtimes).

The per-brick runners stay green after these changes: ravel 349/349 (Weld),
cyrano 168/168, dirac 127/127, heisenberg 463/463 — so both CDI paths of the
same extensions are certified.
