# AGENTS.md — the one rulebook

Loaded into every agent's context, primary and subagents alike (verified). An
agent file says only who you are and how you report. A command says only which
steps to take. Every rule lives HERE, once. There is nothing else to read to
understand the rules: `OPENCODE_*.md` files are the humans' trace of this
project, not documentation for you — the guard refuses to open them.

## 1. Lanes — who writes what

| agent | writes | never | answer |
| --- | --- | --- | --- |
| `lead` | `tasks/<XXX>/<CARD>.md`, `milestones.tsv`, `module.conf`, the commit | java, xml, pom, sh, `TASKS-*`, `STATUS-*` M0 rows | decides, delegates |
| `impl` | `src/main/**`; and the TCK **runner** module when `/tck` asks (pom, resources, run script — never java there) | `src/test/**`, the TCK suite, delivered modules | 3 lines |
| `tdd` | `src/test/**` | `src/main/**`, `*-tck/` | 3 lines |
| `noter` | `docs/spec-notes/<XXX>/` | anything else | 2 lines |
| `planner` | `docs/spec-notes/<XXX>/.fragments/` | anything else | 2 lines |
| `recon` `verify` `thinker` `tck-runner` | nothing — write tools are off | — | 3 · 3 · 6 · 4 lines |

- **Delivered modules are frozen**: `mansart-jakarta-data`, `mansart-transactions`,
  `mansart-pool` and everything under them. The guard refuses the write. A new
  spec gets ONE new parent module and ONE line in the root `pom.xml`.
- **The official TCK suite is read-only, forever.** Our runner
  (`<parent>/<name>-tck/`) is ours: `/tck` builds it through `impl`.

## 2. Files a script owns — never typed by anyone

| file | owner | you |
| --- | --- | --- |
| `TASKS-XXX.md` | `scripts/spec-tasks.sh` | never edit — guard refuses |
| `STATUS-XXX.md`: M0 rows and ALL counts | `scripts/verify-m0.sh` | never; M1+ rows: append ONE, only when its done-when exited 0 |
| `docs/spec-src/XXX/spec-meta.json` | `spec-fetch.py [--refresh-tck]` | never |
| the runner's path | `scripts/tck-module.py` → `module.conf` | never compose it |
| a card brief's path | `scripts/task-file.sh XXX CARD` | never compose it |
| M0 verdict + suite run | `scripts/verify-m0.sh XXX` | the only judge of M0 |

## 3. Hard rules

- **BUILD** never `mvn`/`mvnw`; only `./scripts/build.sh`, `./scripts/sonar.sh`.
  Red = not done. No exception.
- **CONTEXT** no `cat` over 200 lines (`sed -n`/`grep -n`); never re-read an
  unchanged file; a third identical search is denied; never paste a log — give
  its path; answer within your line budget.
- **EVIDENCE** a card is done when a script says PASS about THAT card's own
  artifact. A green build of something else is nothing. A row without a PASS
  is not a row.
- **TESTS** never weaken a test. During a sonar fix, tests are read-only for all.
- **DELEGATE** the lead types nothing. `Delegate to @impl: <one artifact>`. One
  call per artifact; run the verifier after each.
- **COMMIT** English, Conventional Commits + card id, `git commit -S -s`,
  `Co-Authored-By` naming the model. Signature fails = not done. Never push.
- **PROJECT** TDD, failing test first. Strict Java Modules, no unjustified
  `opens`. No runtime reflection on entities; no ASM/ByteBuddy — Class-File API
  or APT. Virtual threads for IO. No new dependency without asking. English
  everywhere.

## 4. Commands — and when each one is done

| command | does | done when |
| --- | --- | --- |
| `/spec-add <url> <XXX>` | `STEP010` → `STEP060` through the scripts | `TASKS`/`STATUS` generated |
| `/tck <XXX>` | four `@impl` calls, then verify-and-fix loop | `verify-m0.sh` is ALL PASS |
| `/refresh <XXX>` | `verify-m0.sh` | four report lines |
| `/next <XXX>` | one M1+ card: recon → tdd → impl → verify → sonar → commit | verify numbers, one STATUS row, a sha |
| `/fixbug <XXX-Bnnn>` | reproduce first, then the `/next` engine | `BUG.md` updated |
| `/status <XXX>` | read-only numbers | — |
| `/push <XXX>` | the only publish; a human runs it | — |

One command, then STOP. Never start the next thing on your own.

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
