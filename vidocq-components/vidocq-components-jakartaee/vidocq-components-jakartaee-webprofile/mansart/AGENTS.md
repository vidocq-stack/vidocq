# AGENTS.md

Contributor guidance for agents working on this repository. See also the companion `CLAUDE.md` file.

## Persistence implementation

Jakarta Persistence milestones P0–P12 are delivered under a scope-based engineering gate (not a formal certification).
Read `mansart-persistence/AGENTS.md`,
`ROADMAP.md` and `TCK.md` before changes. Criteria and JPQL share the same AST and SQL execution path;
canonical `Entity_` initialization uses application-generated access providers without extra opens.
P9 includes schema generation, JTA/CDI integration, the Vidocq runtime extension, and Arquillian coverage.
P10 maps `orm.xml` through an overlay of the class-file annotations (one mapping engine).
The untouched official standalone score is 2096 / 2135: 10 P11 cache errors, 25 delimited-fixture/unquoted-DDL
incompatibilities and four official skips. The runner applies by default the local fixture patch TCK-BUG-001
(upstream jakartaee/persistence#1175, commit `1fea05e`) to a derived jar: 2131 / 2135, no failures/errors and
four official skips; this is a local result, not an official result or certification. The untouched score remains
2096 / 2135. No formal certification is sought and the Web Profile is not a target.
Inherited and entity-owned embedded annotation/XML association overrides are implemented. Embedded relationships
and collections use dotted execution-state slots composed from generated accesses; unsupported P5 shapes fail explicitly.
The final full run at 20:43:24Z includes embedded execution, embeddable map keys and query identifier fixes.
The clean persistence reactor passes 549 tests and the Vidocq Arquillian vehicle passes six tests.
The runtime checks use the local Vauban snapshot containing the upstream injection fix (pending release).
See `TCK.md` and `BENCH.md` for TCK and P12 evidence. Do not weaken quotation or edit official DDL to
restore a score; the only fixture change allowed is the documented TCK-BUG-001 derived copy.
Jakarta Data remains delivered and frozen: do not overwrite its canonical output or alter its producer
to reconcile plural field kinds without a maintainer decision. A Data bridge and generation mutualisation remain
deferred. P12 comparative JMH dependencies stay isolated in the standalone benchmark project; do not add them to
production modules. Its Leyden AOT smoke does not claim GraalVM native-image support.

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
