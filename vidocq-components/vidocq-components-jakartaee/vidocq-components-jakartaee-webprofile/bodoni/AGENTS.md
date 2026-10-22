# AGENTS.md

Single source of agent instructions for this repository (Codex, Claude Code and any other
coding agent). `CLAUDE.md` only imports this file (`@AGENTS.md`): edit the rules here.

> Giambattista Bodoni (1740–1813), printer to the Duke of Parma and rival of Joaquín
> Ibarra, catalogued standard, ready-made type in his *Manuale Tipografico* (1788; the
> posthumous 1818 edition) for any printer to compose pages with. The Bodoni project
> implements **Jakarta Standard Tag Library 3.0**: the standard catalogue of tags every
> page composes with. Ibarra (Jakarta Pages 4.0) composes and prints the pages; Bodoni
> supplies the type.

**Status: B1 delivered — `fn:` and `c:` implemented; `fmt:`, `x:`, `sql:` not yet.** Read [PLAN.md](PLAN.md) before any
change — it is the design of record (spec scope, brick/extension rule, Java Modules
layout, Ibarra build-time inlining, ecosystem friction points, TCK plan, milestones
B0–B7, open questions). Structural decisions marked "to validate" there need the
maintainer's sign-off first.

## Ecosystem rules

Bodoni is one repository of the Vidocq ecosystem. These rules are shared by every Vidocq
repository; the summary below is self-contained, the **full text lives in
`vidocq-workspace/AGENTS.md`** (<https://codefloe.com/Vidocq/vidocq-workspace>). In case
of local divergence, the brick-specific sections further down take precedence.

- **Branch + pull request.** `main` is protected: it requires signed commits and green
  `pr-validate` / `governance-checks` checks. Work on a branch, open a PR, never push to
  `main`.
- **Signed and signed-off commits** — `git commit -S -s`: GPG signature (key registered in
  `Vidocq/governance`, CLA signed) and DCO `Signed-off-by` trailer (see `CONTRIBUTING.md`).
- **Conventional Commits in English**, with the project ticket when there is one.
- **AI provenance** — when an AI tool helped, add a `Co-Authored-By: <tool> <email>`
  trailer (e.g. `Co-Authored-By: Claude Opus 4.x <noreply@anthropic.com>`, or the Codex
  equivalent). It is a provenance record, not a claim of co-authorship: author and
  committer stay the human.
- **Mandatory TDD** — test (or TCK scenario) first; red → green → refactor.
- **Java Modules** — strict `module-info.java`, minimal `exports`, no unjustified `opens`,
  no classpath. Say "Java Modules" / "Java module", **never "JPMS"**.
- **Zero runtime dependency beyond the Jakarta API** this brick implements. Build tooling
  may take a dependency when justified in the PR and named in the module's reference page.
- **A brick depends on APIs; the Vidocq runtime extension assembles** — Bodoni ships the
  JSTL API at `compile` scope; the Pages, Servlet and EL APIs are `provided`. Bodoni never
  depends on Ibarra, Peano or Foy implementations: the Vidocq runtime extension picks them.
- **Static code generation over runtime reflection** — Class-File API (JEP 484) and APT at
  build time; no dynamic proxies, no ASM/Byte Buddy. AOT-compatible.
- **Virtual threads** for I/O; no platform-thread pool without a documented reason.
- **`BUG.md` / `BENCH.md`** — every reproducible bug goes in `BUG.md` (id, date, symptom,
  minimal repro, hypothesised cause, status); every performance figure goes in `BENCH.md`
  (date, hardware/JVM, exact command, raw results, delta vs previous run) before it may
  appear in a README or a commit message.
- **English everywhere** — code, Javadoc, comments, commits, CI, issues/PRs and every
  Markdown file. Exchanges with the maintainer stay in French.
- **Docs** in `docs/en` as an Antora component, English-only (see below).

## Prerequisites

- **Java 25** + **Maven 3.9.16** (`.sdkmanrc` provided — use `sdk env`)
- `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:3.0.2` is already modular
  upstream (module `jakarta.servlet.jsp.jstl`; it `requires transitive`
  `jakarta.servlet.jsp`, `jakarta.servlet`, `jakarta.el`, `java.sql` and `java.desktop`)
  — no Vidocq fork needed. Its POM declares Servlet 6.0 / EL 5.0 at compile scope; the
  root POM excludes them and pins the EE 11 levels as `provided`.

## Essential Commands

```bash
sdk env
./mvnw -ntp clean install                          # default reactor (no TCK)
./mvnw test
./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify   # Ibarra IT (see note below)
# planned (B5): ./mvnw -Ptck -pl bodoni-tck test
# planned (B5): ./run-official-tck-tags-3.0.sh smoke|all|-Dtest=…
```

The `it-ibarra` profile is not part of the default build: it needs **local snapshots** of
chappe, foy, peano and ibarra (`0.4.0-SNAPSHOT`) in `~/.m2`. The IT runs under
`peano.reflection=deny` (`denyModeIsActive` proves it); its `varStatus` and `Map.Entry`
tests are `@Disabled` on BUG.md BOD-001.

`bodoni-tck` will be **in-reactor behind the `tck` Maven profile** from day one: a plain
`./mvnw install` will neither build nor download anything TCK-related.

## Architecture

```
bodoni-core   ← JSTL 3.0 implementation: tag handlers, functions and TLDs of
                c:, fmt:, fn:, x:, sql:  (B1: c: and fn:)
bodoni-it-ibarra ← integration test on Ibarra + Foy + Peano, `it-ibarra` profile only
(planned)     bodoni-ibarra  ← optional build-time inliner for Ibarra (TagInliner SPI)
(planned)     bodoni-tck     ← official Tags TCK runner, in-reactor behind `tck`
(planned)     bodoni-it-other-containers/bodoni-it-openliberty ← portability proof
```

## Architecture Constraints Not to Violate

1. **`bodoni-core` depends on the JSTL API only** (compile scope), plus the `provided`
   Pages / Servlet / EL APIs it brings transitively. No Ibarra, Peano, Foy or Vauban
   dependency in `bodoni-core` — ever. It must run unchanged on any Pages container
   (Ibarra, Jasper, Open Liberty).
2. **Standard tag handlers are the portable reference.** They are what Jasper or Open
   Liberty run and what the TCK certifies. Build-time inlining (`bodoni-ibarra`) is an
   optional optimisation that must be behaviourally identical, tested differentially
   against the handlers.
3. **Ibarra holds only the extension contract** (the `TagInliner` SPI, PLAN.md). JSTL code
   never moves into Ibarra (Ibarra's AGENTS.md rule 6). Changes to Ibarra are cross-repo
   items proposed in Ibarra's own repository.
4. **No reflection** beyond what the EL implementation already does when evaluating
   expressions; no `setAccessible`, no `opens`.
5. **No `synchronized`, no `ThreadLocal`** — tag state travels in the handler instance and
   the `PageContext`; caches are `ConcurrentHashMap`.
6. **JDK modules only** for XML (`java.xml`), SQL (`java.sql`) and HTTP (`java.net.http` or
   `URLConnection`) — no third-party XPath/XSLT/HTTP library.
7. **JNDI is the standard `InitialContext` lookup**, exactly as on any server. Bodoni does
   not provide a naming implementation (PLAN.md open question on `java:comp`).
8. **JUnit 6** (`junit-bom` managed by `vidocq-parent`), no Mockito — hand-written
   fixtures and `PageContext` / servlet doubles only.
9. **Language** — code, Javadoc, comments, commit messages and every `.md` file are in
   **English**.

## Conventions

- **Maven groupId**: `io.vidocq.bodoni`; version `0.4.0-SNAPSHOT` (workspace dev line,
  parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT`).
- **Java modules**: `io.vidocq.bodoni.core` (artifact `bodoni-core`); planned
  `io.vidocq.bodoni.ibarra` (artifact `bodoni-ibarra`).
- **Packages** (fixed in B1): `io.vidocq.bodoni.core.tags.<library>` (exported tag
  handlers; `tags.core` today, `fmt`, `xml`, `sql` to come), `io.vidocq.bodoni.core.functions`
  (exported `fn:` functions) and `io.vidocq.bodoni.core.internal` = implementation, not exported.
- Licence header from `etc/license-header.txt` on every `.java` file.
- Commits: see "Ecosystem rules" above.

## TDD — Test-Driven Development (mandatory)

Red → green → refactor, one spec chapter at a time, following the PLAN.md milestones.
Cite the spec section in commit messages / PR descriptions (e.g. "Tags 3.0 §6.2
`<c:forEach>`").

## TCK — Technology Compatibility Kit

Not run yet (B5). Once green, 100 % PASS is a merge gate; any challenge goes in a
`TCK.md` with spec citation — created only if one is actually filed. Never claim
certification from a partial or local run.

## AI Principles — Collaboration on This Repository

- **Plan mode by default** on any structural change (new module, new SPI, change to the
  TLD or handler shape).
- When the workspace review agents are available (Claude Code subagents in
  `vidocq-workspace/.claude/agents/`), use **`java-modules-guardian`**,
  **`classfile-codegen`**, **`virtual-threads-reviewer`** and **`dependency-gatekeeper`**
  proactively on `module-info.java`, codegen, concurrent code, or `pom.xml` changes.

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

Bodoni has no `performance` page yet: it is added with the first `BENCH.md` entry.

### TCK / Performance rule (not mutually exclusive)
- Every **spec implementation** — i.e. **all projects except Chappe and Vidocq** — MUST
  have a **`tck`** section documenting TCK coverage/status.
- Projects with a performance story (e.g. **Chappe**) keep their **`performance`** section.
- When **both** sections exist, order them **TCK first, then Performance**.

### `index.adoc` structure
Follow Vauban's `index.adoc`: page title (`= <Project>`), `:description:`, a centred logo
(`image::<project>-logo.png[...,role=module-logo]`), a `[.lead]` paragraph, then
`== Origin of the name`, an `== At a glance` table, and ecosystem / quick-links sections.

### Logo
Provide `modules/ROOT/images/<project>-logo.png` (PNG), referenced from `index.adoc`.
**Bodoni has no logo yet** (tracked in `tasks/todo.md`): `index.adoc` does not reference
an image until `bodoni-logo.png` exists.

## Terminology

Use **Java Modules** (or **Java module** for a single module) when referring to
the Java Platform Module System. Do **not** use the abbreviation **JPMS** — in
prose, identifiers, or documentation.
