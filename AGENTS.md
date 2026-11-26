# AGENTS.md

Instructions for coding agents (Claude Code, Codex, ...) working in the Vidocq monorepo. `CLAUDE.md` imports this
file. Each component keeps its own `AGENTS.md` in its folder: read it before changing that component; it takes
precedence there.

## Layout

See `README.md`. In short: `vidocq-parent/` (the parent of every module), `vidocq-components/` (stable bricks by
family), `vidocq-preview/` (usable, not complete or not certified: kerckhoffs, noether), `vidocq-incubator/` (built
and tested, never released), `vidocq-runtime/` (the runtime, its extensions, Maven plugin, CLI and BOM).
`components.json` lists every component with its PR title tag, folder and maturity level.

## Build

- Java 25 (Temurin) and Maven 3.10.0, pinned in `.sdkmanrc`. Always the wrapper at the root: `./mvnw`.
- `./mvnw install` builds what the branch changed and what depends on it (gitflow-incremental-builder);
  `-Dgib.disable=true` builds everything; `-pl <path> -am` builds one module and what it needs.
- One version for the whole repository, in the root POM. A brick depends on another at `${project.version}`.
- The release builds with `-P '!incubator'`. A component's official TCK runs behind its `tck` profile, or its
  `run-official-tck-*.sh` script; `ci.tck.modules` / `ci.tck.command` in a component's root POM name what the TCK
  workflow runs: once a day, on demand (`TCK nightly`, dispatched on a branch with the component tags) and for a
  release. A pull request runs no TCK.
- Never install into `~/.m2` from a branch build: `-Dmaven.repo.local=<scratch>/m2
  -Dmaven.repo.local.tail=$HOME/.m2/repository`.
- An IDE that reads the project through m2e (VS Code, Eclipse) compiles into `target-ide/`, never into `target/`:
  profile `ide-output` of `vidocq-parent`. `target/` holds what Maven built, and nothing else.

## Working rules

- **Short-lived branches, small pull requests.** No feature on top of a feature: what another branch needs merges
  first; incomplete is fine if nothing breaks.
- **A shared API change fixes every caller in the same pull request.** CI builds every downstream module of a
  change; there are no companion branches any more.
- **A change under `vauban/` goes in its own pull request, with its own test in `vauban/`** reproducing the problem
  without the module that found it. It must pass the CDI TCK and every stable dependent.
- **Maturity levels**: an unusable module lives on `main` in `vidocq-incubator/`, never on a long-lived branch; a usable
  but incomplete or uncertified one in `vidocq-preview/`. A move to the next level is one commit: move the folder to the
  same group path, update the grouping POMs, `components.json` and the BOM.
- `BUG.md` and `BENCH.md` stay per component, in its folder. A bug id is unique across the monorepo.
- **Releases**: `x.y.0` is a full release of the reactor from `main`; `x.y.Z` (`Z > 0`) is a patch release from
  `maintenance/x.y`, which publishes only the modules changed since the previous release of the line, with the
  modules of `.forgejo/release-patch.json` (header of `.forgejo/workflows/release.yml`). A fix for a patch release
  merges into `main` first, then a pull request cherry-picks it into `maintenance/x.y`. A patch cannot carry a change
  to a parent or grouping pom, nor a change that breaks the binary or source compatibility of a published module:
  those wait for the next `x.y.0`.

## Pull request titles

```
[WIP] [NOETHER] fix(persistence): flush order on nested transactions (#123)
[VAUBAN][CASSINI] refactor(spi): rename BeanSource to BeanOrigin
[RUNTIME] feat(dev): hot reload of static resources
```

`[WIP]` first (Forgejo blocks the merge button on it), then one tag per component touched (`components.json`), plus
`[BOM]`, `[TCK]`, `[BUILD]` (root POM, `.mvn/`, grouping POMs), `[CI]` (`.forgejo/`), `[DOCS]` (documentation only).
Then the Conventional Commits title, in English. The title check warns for now; it will block.

## Ecosystem rules

The rules of the whole ecosystem (Java Modules, static code generation, zero runtime dependency, a brick depends on
APIs and its runtime extension assembles, build compatible extensions work at build only, TDD, virtual threads,
English, signed and signed-off commits with the human as author and AI assistance in `Co-Authored-By:`, SonarQube
before a push) are in `AGENTS.md` of `Vidocq/vidocq-workspace`, which prevails.
