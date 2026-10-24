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

**B5 delivered — official Tags TCK 541/541 on Ibarra + Foy + Peano (first baseline 452/541).** The `c:` (core), `fn:` (functions), `fmt:` (i18n and formatting), `x:` (XML) and `sql:` (relational database access) libraries
are implemented in `bodoni-core`. XML external references follow a same-origin policy; `sql:` binds values through `sql:param`, finds JDBC drivers through the context class loader and keeps database passwords out of its messages (see the Reference page). The official Tags tests
(`jakarta.tck:tags-tck:11.0.3`) run in `bodoni-tck`: after the TCK fixes they pass 541 of 541
(the first baseline passed 452 of 541, 83.5 %), recorded in
[bodoni-tck/BASELINE.md](bodoni-tck/BASELINE.md). It is a compatibility measurement on Bodoni +
Ibarra + Foy + Peano, not a certification. Under Peano with `peano.reflection=deny`, add `bodoni-peano` so that the properties of `varStatus` and of the `sql:query` `Result` can be read (BOD-001, fixed, see [BUG.md](BUG.md)). Bodoni is required by Jakarta EE Web Profile 11 and tracked by
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
| `bodoni-core` | Tag handlers, functions and TLDs of `c:`, `fmt:`, `fn:`, `x:`, `sql:` | B4: all five implemented |
| `bodoni-peano` | Optional engine adapter: the `LoopTagStatus`, `Result` and `LocalizationContext` accessor tables Peano needs under `peano.reflection=deny` | B1 (`Result`: B4; `LocalizationContext`: BOD-007) |
| `bodoni-it-ibarra` | Integration test: pages rendered on Ibarra + Foy + Peano, `sql:` pages against an in-memory H2 database (`it-ibarra` profile, not published) | B1 |
| `bodoni-ibarra` | Optional build-time inliner for Ibarra (`TagInliner` SPI) | planned (B6, on standby: [BENCH.md](BENCH.md)) |
| `bodoni-tck` | Official Tags TCK runner on Ibarra's Arquillian adapter (in-reactor under `-Ptck` only, not published) | B5: 541/541 after the TCK fixes (first baseline 452/541) |
| `bodoni-it-openliberty` | Portability proof on Open Liberty | delivered (B7) |

## Build

```bash
sdk env
./mvnw -ntp clean install
```

Ibarra integration test (needs local snapshots of chappe, foy, peano and ibarra):

```bash
./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify
```

Official Tags TCK (Java 25; the first run downloads the official zip for its Derby DDL, or set
`TAGS_TCK_ZIP`):

```bash
./mvnw -ntp -Ptck -pl bodoni-tck test                   # the runner's own tests
./run-official-tck-tags-3.0.sh smoke|all|-Dtest=…       # official classes; --help for the flags
```

Before the repository has its first commit, add `-Dmaven.gitcommitid.skip=true`
(`git-commit-id-maven-plugin`, from `vidocq-parent`, needs a `HEAD`).

## License

Disjunctive triple license — `EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later`: see
[LICENSE](LICENSE), [NOTICE](NOTICE) and [licenses/](licenses/).
