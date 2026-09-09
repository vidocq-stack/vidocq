# AGENTS.md

Contributor guidance for agents working on this repository. See also the companion `CLAUDE.md` file.

## HARD RULES — read first

Short on purpose. Break one and the guard stops you anyway.

**BUILD**
- NEVER `mvn` / `mvnw`. ONLY `./scripts/build.sh`. Piped mvn returns the pipe exit
  code (always 0) — that is how 38 failed builds got logged green.
- Sonar: ONLY `./scripts/sonar.sh <module>`.
- Build red = card NOT done. No exception.

**CONTEXT — keep it small**
- NEVER `cat` a file over 200 lines. `sed -n '<a>,<b>p'` or `grep -n`.
- NEVER re-read a file you already read and nobody changed. It is still in context.
- Same search twice means you dropped the result. Third time is denied.
- NEVER paste a build log to your parent. Give the log path.
- Subagent answers in 3 LINES. Not 4. Not a summary of a summary.

**WRITE — stay in your lane**
- `tdd` writes `src/test/` only. `impl` writes `src/main/` only.
- `*-tck/` is READ-ONLY. Forever. Change the implementation, not the suite.
- During a sonar fix, tests are READ-ONLY for everyone.
- NEVER weaken a test to go green. That is the one unforgivable move.

**COMMIT**
- English. Conventional Commits + card id. GPG-signed (`-S`). DCO `Signed-off-by`.
  `Co-Authored-By` naming the model. Signature fails = card not done.
- Commit yes. Push NEVER — the human runs `/push`.

**PROJECT**
- TDD: failing test first, always.
- Strict Java Modules. No unjustified `opens`. No new dependency without asking.
- No runtime reflection on entities. No ASM/ByteBuddy. Class-File API or APT.
- Virtual threads for IO.
- English everywhere: code, javadoc, comments, commits, markdown.

Full reasoning behind these: `OPENCODE_CODE_HARNESS-BUILD.md` (humans only).

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
