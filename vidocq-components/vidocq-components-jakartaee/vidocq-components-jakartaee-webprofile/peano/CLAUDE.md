# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> Giuseppe Peano (1858–1932) gave mathematics a rigorous formal notation — the `∈`
> symbol, the Peano axioms — so that expressions could be written without ambiguity and
> evaluated mechanically. The Peano project implements **Jakarta Expression Language
> 6.0**: it parses `${...}` / `#{...}` into an unambiguous tree and evaluates it.

**Status: in design.** Read [PLAN.md](PLAN.md) before any change — it is the design of
record (spec scope, codegen doctrine, Java Modules layout, integration points, TCK plan,
milestones P0–P9, open questions). Structural decisions marked "to validate" there need
the maintainer's sign-off first.

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- `jakarta.el:jakarta.el-api:6.0.1` is already modular upstream (module `jakarta.el`) —
  no Vidocq fork needed.
- The official EL 6.0 TCK coordinates are **not yet confirmed** (see PLAN.md "TCK plan").
- The build uses `git-commit-id-maven-plugin` (from `vidocq-parent`): it needs at least
  one commit in the repository.

## Essential Commands

```bash
sdk env
./mvnw -ntp install                               # default reactor (no TCK)
./mvnw test
./mvnw -Ptck -pl peano-tck test                   # TCK runner skeleton
./mvnw -Ptck,tck-official -pl peano-tck test      # official TCK (coordinates to confirm)
```

`peano-tck` is **in-reactor behind the `tck` Maven profile** from day one: a plain
`./mvnw install` neither builds nor downloads anything TCK-related.

## Architecture

```
peano-core   ← Engine (scaffold today): parser, AST, interpreter, coercions,
               provides jakarta.el.ExpressionFactory
peano-tck    ← Official EL 6.0 TCK runner, in-reactor behind `tck`
(planned)    peano-api (generated-code SPI), peano-processor (APT accessor tables),
             peano-compiler (Class-File API compiled expressions), peano-bench (JMH)
```

## Architecture Constraints Not to Violate

1. **Runtime modules depend on nothing beyond `jakarta.el`** — no CDI, no Pages, no
   Servlet, no third-party parser generator. Peano never depends on Ibarra or Vauban;
   they depend on Peano (or only on the `jakarta.el` API).
2. **No `java.desktop`** — do not use `java.beans` introspection in Peano code; the
   accessor logic (getter/setter/record naming) is Peano's own.
3. **The interpreter is the oracle** — every generated accessor table and every compiled
   expression is tested differentially against the interpreter / reflective path.
4. **No bytecode generation at run time** unless PLAN.md open question 1 is decided
   otherwise. Class-File API generation runs at build time.
5. **Reflection only in the documented fallback** (the API's `BeanELResolver` and
   whatever PLAN.md lists); never `setAccessible`, never `opens`.
6. **No `synchronized`, no `ThreadLocal`** — caches are `ConcurrentHashMap`; evaluation
   state travels in the `ELContext`.
7. **JUnit 6** (`junit-bom` managed by `vidocq-parent`), no Mockito — hand-written
   fixtures and doubles only.
8. **Language** — code, Javadoc, comments, commit messages and every `.md` file are in
   **English**.

## Conventions

- **Maven groupId**: `io.vidocq.peano`; version `0.4.0-SNAPSHOT` (workspace dev line,
  parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT`).
- **Packages**: `io.vidocq.peano.core.internal.*` = implementation, not exported;
  discovery only through `provides jakarta.el.ExpressionFactory`.
- **Records + sealed interfaces** for AST nodes; one test class per grammar production.
- Licence header from `etc/license-header.txt` on every `.java` file.
- Commits: Conventional Commits, `git commit -S -s`, `Co-Authored-By` trailer when AI
  assisted (see workspace `CLAUDE.md`).

## TDD — Test-Driven Development (mandatory)

Red → green → refactor, one spec chapter at a time, following the PLAN.md milestones.
Cite the spec section in commit messages / PR descriptions (e.g. "EL 6.0 §1.23
coercions").

## TCK — Technology Compatibility Kit

Not run yet (milestone P8). Once green, 100 % PASS is a merge gate; any challenge goes
in a `TCK.md` with spec citation — created only if one is actually filed.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new SPI, change to
  the evaluation engine's public shape).
- Use the Java Modules guardian agent and **`classfile-codegen`**, **`virtual-threads-reviewer`**,
  **`dependency-gatekeeper`** proactively on `module-info.java`, codegen, concurrent
  code, or `pom.xml` changes.
- If the rules in this file need updating, keep `AGENTS.md` aligned.

## Documentation (Antora) conventions

The project documentation lives in `docs/en` as an Antora component and is
aggregated by the **vidocq-docs** site, which provides a **shared UI bundle** (banner,
logo, fonts, colours, footer). **Never customise the documentation UI per project** —
all visual harmonisation is centralised in `vidocq-docs/ui-bundle`.

### Gold reference
**Vauban** is the reference implementation for documentation structure. Mirror its
`docs/en` layout when creating or updating docs. **Chappe** (HTTP server)
and **Vidocq** (runtime orchestrator) are *special cases*, not references: they are not
Jakarta EE / MicroProfile spec implementations.

### Repository layout
- `docs/en/antora.yml` → `name: <project>`, `title:`, versioned per branch (`dev` prerelease on `main`, `'<version>'` on `docs/<version>`), `project-version` attribute, `nav:`, `lang: en`.
- Pages in `modules/ROOT/pages/`, navigation in `modules/ROOT/nav.adoc`, images in
  `modules/ROOT/images/`.
- **English-only** (ADR 0004 in vidocq-docs): no French mirror — do not reintroduce one.

### Canonical navigation (section order)
`index` → `getting-started` → `usage` → `concepts` → `internals` → `tck` →
`performance` → `reference` → `migration`

Multi-module projects (e.g. Vidocq, Mansart) may append `modules/*` / `sub-modules/*`
sub-pages after `migration`.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.
- **Chappe** and **Vidocq** do not require a `tck` section (not spec implementations).

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.

> When you change these documentation rules, keep `AGENTS.md` and `CLAUDE.md` in sync.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
