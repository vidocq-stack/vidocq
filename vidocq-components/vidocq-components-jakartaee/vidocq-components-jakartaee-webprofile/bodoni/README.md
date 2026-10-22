# Bodoni

<!-- TODO: logo (docs/en/modules/ROOT/images/bodoni-logo.png) — see tasks/todo.md -->

**Jakarta Standard Tag Library 3.0** implementation for the
[Vidocq](https://codefloe.com/Vidocq/vidocq) runtime.

> Named after **Giambattista Bodoni** (1740–1813), printer to the Duke of Parma and
> rival of Joaquín Ibarra, whose *Manuale Tipografico* catalogued standard, ready-made
> type for any printer to compose pages with. JSTL is the standard catalogue of tags
> every page composes with: [Ibarra](https://codefloe.com/Vidocq/ibarra) (Jakarta Pages
> 4.0) composes and prints the pages, Bodoni supplies the type.

## Status

**B1 delivered.** The `c:` (core) and `fn:` (functions) libraries are implemented in
`bodoni-core`; `fmt:`, `x:` and `sql:` are not yet (B2 to B4). The official TCK has not been
run. Known limitation: BOD-001 (`varStatus` and `Map.Entry` properties under Peano deny mode, see [BUG.md](BUG.md)). Bodoni is required by Jakarta EE Web Profile 11 and tracked by
[Vidocq/vidocq#266](https://codefloe.com/Vidocq/vidocq/issues/266). The design of record is
[PLAN.md](PLAN.md) (spec scope, brick rule, Java Modules layout, Ibarra build-time
inlining, friction points, TCK plan, milestones B0–B7, open questions).

## Ecosystem rules

Bodoni follows the transverse Vidocq philosophy (see the workspace
[AGENTS.md](https://codefloe.com/Vidocq/vidocq-workspace/src/branch/main/AGENTS.md) conventions):

- **Zero third-party dependency** — only the Jakarta Standard Tag Library 3.0 API (which
  brings the Pages, Servlet and EL APIs, `provided` by the container) and JDK modules.
- **A brick depends on APIs** — no Ibarra, Peano or Foy dependency: the same jar runs on
  Ibarra, Jasper or Open Liberty. The Vidocq runtime extension assembles Bodoni + Ibarra +
  Peano.
- **Strict Java Modules** — proper `module-info.java`, minimal `exports`,
  no unjustified `opens`, no classpath.
- **Static code generation where it pays** — standard handlers are the portable reference;
  an optional `bodoni-ibarra` module lets Ibarra inline the hot core tags at build time.
- **TDD** — test (or TCK scenario) first, then code.
- **Virtual threads** for any I/O; no `synchronized`, no `ThreadLocal`.
- Bugs tracked in [BUG.md](BUG.md), benchmarks in [BENCH.md](BENCH.md).

## Modules

| Module | Role | State |
|---|---|---|
| `bodoni-core` | Tag handlers, functions and TLDs of `c:`, `fmt:`, `fn:`, `x:`, `sql:` | B1: `c:` and `fn:` implemented; `fmt:`, `x:`, `sql:` not yet |
| `bodoni-it-ibarra` | Integration test: pages rendered on Ibarra + Foy + Peano (`it-ibarra` profile, not published) | B1 |
| `bodoni-ibarra` | Optional build-time inliner for Ibarra (`TagInliner` SPI) | planned (B6) |
| `bodoni-tck` | Official Tags TCK runner (`-Ptck` profile only) | planned (B5) |
| `bodoni-it-openliberty` | Portability proof on Open Liberty | planned (B7) |

## Build

```bash
sdk env
./mvnw -ntp clean install
```

Ibarra integration test (needs local snapshots of chappe, foy, peano and ibarra):

```bash
./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify
```

Before the repository has its first commit, add `-Dmaven.gitcommitid.skip=true`
(`git-commit-id-maven-plugin`, from `vidocq-parent`, needs a `HEAD`).

## License

Disjunctive triple license — `EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later`: see
[LICENSE](LICENSE), [NOTICE](NOTICE) and [licenses/](licenses/).
