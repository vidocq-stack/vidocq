# Ravel

> Maurice Ravel (1875–1937) was a master of orchestration — he knew how to weave disparate
> sources into a coherent whole. This is exactly what a configuration system does.

**Ravel** is a [MicroProfile Config 3.1](https://download.eclipse.org/microprofile/microprofile-config-3.1/microprofile-config-spec-3.1.html)
implementation for the [Vidocq](https://forge.vidocq.dev/vidocq) ecosystem.

## Principles

- **Zero third-party libraries** — only Jakarta EE and MicroProfile specs are allowed
  as compile/runtime dependencies. No Smallrye Config, no Helidon Config, no Apache
  Commons Config.
- **Java 25 + Maven 4** — pinned via `.sdkmanrc`.
- **Strict Java Modules** — each module has its `module-info.java`, `internal.*` packages unexported,
  SPI via `provides/uses`.
- **Virtual threads friendly** — no `synchronized`, no `ThreadLocal`. `ScopedValue` for
  contextual propagation (cycle detection in expressions in particular).
- **Strict TDD** — Red → Green → Refactor, systematic spec citation in test JavaDoc.
- **TCK 100% PASS** — hard contract on `microprofile-config-tck:3.1.1` before any structural merge.

## Modules

| Module | Description |
|---|---|
| `ravel-api` | Re-export of the `org.eclipse.microprofile.config` spec |
| `ravel-core` | Standalone `Config` implementation: built-in sources, converters, profiles, expressions |
| `ravel-cdi-vauban` | CDI Vauban integration: `@ConfigProperty`, BCE, `Optional<T>` injection |
| `ravel-bench` | JMH benchmarks — comparison vs Smallrye Config |
| `ravel-tck` | Official MicroProfile Config 3.1 TCK runner (**out of reactor**, POM Model 4.0.0) |

## Getting started

```bash
cd ravel
sdk env                         # Java 25 + Maven 3.9.16
mvn -ntp install -DskipTests    # reactor build
mvn test                        # unit tests
```

## Official TCK

The `org.eclipse.microprofile.config:microprofile-config-tck:3.1.1` artifact is public on Maven
Central — no manual installation needed (3.1.1 is a Java Modules-friendly re-release of
spec 3.1, identical content).

```bash
./run-official-tck-mp-config-3.1.sh             # smoke
./run-official-tck-mp-config-3.1.sh all         # full suite
./run-official-tck-mp-config-3.1.sh -Dtest=...  # targeted
```

## Documentation

- [`CLAUDE.md`](CLAUDE.md) — development guide (constraints, conventions, TDD)
- [`ROADMAP.md`](ROADMAP.md) — phase plan M0 → M6
- [`BUG.md`](BUG.md) — open bugs (created on first report)
- [`BENCH.md`](BENCH.md) — dated JMH results (created on first run)

## License

EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later — see [`LICENSE`](LICENSE).
