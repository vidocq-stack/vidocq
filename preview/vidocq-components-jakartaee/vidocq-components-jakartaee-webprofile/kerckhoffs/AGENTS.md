# AGENTS.md

Single source of agent instructions for this repository (Codex, Claude Code and any other
coding agent). `CLAUDE.md` only imports this file (`@AGENTS.md`): edit the rules here.

> Auguste Kerckhoffs (1835–1903) formulated in 1883 the principle that a cryptographic
> system must remain secure even if everything about it, except the key, is public. The
> Kerckhoffs project implements **Jakarta Security 4.0**: authentication mechanisms,
> identity stores and the security context of a Jakarta EE application, built in the open
> and generated at build time, with only the credentials left secret.

**Status: scaffold (K0 in progress) — nothing implemented, no TCK run yet.** Read
[PLAN.md](PLAN.md) before any change: it is the design of record (spec scope, the
Authentication/Authorization question of §3, Java Modules layout, security rules, TCK plan,
milestones K0–K7, open questions). Structural decisions marked "to validate" there need the
maintainer's sign-off first.

## Ecosystem rules

Kerckhoffs is one repository of the Vidocq ecosystem. These rules are shared by every Vidocq
repository; the summary below is self-contained, the **full text lives in
`vidocq-workspace/AGENTS.md`** (<https://codefloe.com/Vidocq/vidocq-workspace>). In case
of local divergence, the brick-specific sections further down take precedence.

- **Branch + pull request.** `main` is protected: it requires signed commits and green
  `pr-validate` / `governance-checks` checks. Work on a branch, open a PR, never push to `main`.
- **Signed and signed-off commits** — `git commit -S -s`: GPG signature (key registered in
  `Vidocq/governance`, CLA signed) and DCO `Signed-off-by` trailer (see `CONTRIBUTING.md`).
- **Conventional Commits in English**, with the project ticket when there is one.
- **AI provenance** — when an AI tool helped, add a `Co-Authored-By: <tool> <email>`
  trailer. It is a provenance record, not a claim of co-authorship: author and committer
  stay the human.
- **Mandatory TDD** — test (or TCK scenario) first; red → green → refactor.
- **Java Modules** — strict `module-info.java`, minimal `exports`, no unjustified `opens`,
  no classpath. Say "Java Modules" / "Java module", **never "JPMS"**.
- **Zero runtime dependency beyond the Jakarta API** this brick implements. Build tooling
  may take a dependency when justified in the PR and named in the module's reference page.
- **A brick depends on APIs; the Vidocq runtime extension assembles** — Kerckhoffs ships the
  Jakarta Security API at `compile` scope; the Authentication, Authorization, Servlet, CDI,
  Interceptor and EL APIs are `provided`. Kerckhoffs never depends on a Foy, Vauban, Peano,
  Mansart or Champollion implementation: the Vidocq runtime extension picks them.
- **A build compatible extension works at build, and only there** — see
  `vidocq-workspace/AGENTS.md` and `docs/writing-a-build-compatible-extension.md`.
- **Static code generation over runtime reflection** — Class-File API (JEP 484) and APT at
  build time; no dynamic proxies, no ASM/Byte Buddy. AOT-compatible.
- **Virtual threads** for I/O; no platform-thread pool without a documented reason.
- **SonarQube before every code push** — run the local branch analysis and fix new issues
  (`vidocq-workspace/AGENTS.md`, "Code quality").
- **`BUG.md` / `BENCH.md`** — every reproducible bug goes in `BUG.md`; every performance figure
  goes in `BENCH.md` before it may appear in a README or a commit message.
- **English everywhere** — code, Javadoc, comments, commits, CI, issues/PRs and every
  Markdown file. Exchanges with the maintainer stay in French.
- **Docs** in `docs/en` as an Antora component, English-only (see below).

## Security-brick rules

This brick decides who is allowed in. On top of the rules above (PLAN.md §6): constant-time
comparison of secrets, wipe `char[]`/`Password` after use, never log a credential, rotate the
session id on login, fail closed, write the negative test before the positive one.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- `jakarta.security.enterprise:jakarta.security.enterprise-api:4.0.0` is already modular
  upstream (module `jakarta.security`; it `requires transitive jakarta.security.auth.message`,
  `jakarta.servlet` and `jakarta.json`) — no Vidocq fork needed.

## Essential Commands

```bash
./mvnw -ntp install -DskipTests        # full build
./mvnw test                            # unit tests
./run-official-tck-security-4.0.sh     # official TCK — scaffold: refuses with exit 2 until K0
```

A build outside CI may need a scratch `settings.xml` for `maven.vidocq.dev` (the SNAPSHOT
repository of the ecosystem).

## Conventions

- **Maven groupId**: `io.vidocq.kerckhoffs`; version `0.4.0-SNAPSHOT` (workspace dev line,
  parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT`). The version property in consumers is
  `kerckhoffs.version`.
- **Java modules**: `io.vidocq.kerckhoffs.core` (artifact `kerckhoffs-core`); planned
  `io.vidocq.kerckhoffs.cdi` (artifact `kerckhoffs-cdi`). See PLAN.md §4.
- **Packages** (to fix in K1): exported API-facing packages under `io.vidocq.kerckhoffs.core.*`;
  `io.vidocq.kerckhoffs.core.internal` = implementation, not exported.
- Licence header from `etc/license-header.txt` on every `.java` file.
- Commits: see "Ecosystem rules" above.

## TDD — Test-Driven Development (mandatory)

Red → green → refactor, one spec chapter at a time, following the PLAN.md milestones. Cite the
spec section in commit messages / PR descriptions (e.g. "Security 4.0 §3.4.3 in-memory store").

## TCK — Technology Compatibility Kit

Target: the Jakarta Security 4.0 TCK (`jakartaee/security`, tag `4.0.0-RELEASE`, directory
`tck/`). The runner (`kerckhoffs-tck`, `-Ptck`) is an empty scaffold; K0 builds it and records a
first baseline in `kerckhoffs-tck/BASELINE.md`. A result is a compatibility measurement:
**never claim certification** from it, nor from a partial or local run. Declare `ci.tck.command`
in the root POM only when the smoke set is green. Do not use the 5.0.0-M* tags: they are
Jakarta EE 12 work.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new SPI, a change to the Foy
  hook, a change of Authentication/Authorization strategy).
- When the workspace review agents are available (`vidocq-workspace/.claude/agents/`), use
  **`java-modules-guardian`**, **`classfile-codegen`**, **`virtual-threads-reviewer`** and
  **`dependency-gatekeeper`** proactively on `module-info.java`, codegen, concurrent code, or
  `pom.xml` changes.

## Documentation (Antora) conventions

The documentation lives in `docs/en` as an Antora component aggregated by **vidocq-docs**, which
provides a shared UI bundle. **Never customise the UI per project.** Mirror **Vauban**'s
`docs/en` layout. English-only (vidocq-docs ADR 0004).

- `docs/en/antora.yml` → `name: kerckhoffs`, `prerelease: true` on `main`, `project-version`.
- Canonical navigation: `index` → `getting-started` → `usage` → `concepts` → `internals` → `tck`
  → `performance` → `reference` → `migration`. The scaffold has `index` only; add the others with
  the first content they carry.
- `performance` carries only figures that have an entry in `BENCH.md`; `tck` comes before it.
- `index.adoc`: `= Kerckhoffs`, `:description:`, `[.lead]`, `== Origin of the name`,
  `== At a glance`, ecosystem links. No logo yet (`tasks/todo.md`).

## Terminology

Use **Java Modules** (or **Java module**) for the Java Platform Module System. Do **not** use
the abbreviation **JPMS** — in prose, identifiers, or documentation.
