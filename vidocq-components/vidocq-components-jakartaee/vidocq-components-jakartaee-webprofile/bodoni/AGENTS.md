# AGENTS.md

Single source of agent instructions for this repository (Codex, Claude Code and any other
coding agent). `CLAUDE.md` only imports this file (`@AGENTS.md`): edit the rules here.

> Giambattista Bodoni (1740–1813), printer to the Duke of Parma and rival of Joaquín
> Ibarra, catalogued standard, ready-made type in his *Manuale Tipografico* (1788; the
> posthumous 1818 edition) for any printer to compose pages with. The Bodoni project
> implements **Jakarta Standard Tag Library 3.0**: the standard catalogue of tags every
> page composes with. Ibarra (Jakarta Pages 4.0) composes and prints the pages; Bodoni
> supplies the type.

**Status: B5 delivered — official Tags TCK 541/541 on Ibarra + Foy + Peano (not a certification); `fn:`, `c:`, `fmt:`, `x:` and `sql:` implemented; B6 (inlining) on standby after a measurement (BENCH.md); B7 delivered — the unchanged `bodoni-core` jar in a WAR is the JSTL Open Liberty 26.0.0.10 runs (nine tests).** Read [PLAN.md](PLAN.md) before any
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
  `pr-validate` / `governance-checks` checks of the monorepo. Work on a branch, open a PR, never push to
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

## Build compatible extensions work at build only

A CDI build compatible extension works at build, and only there. `vidocq:generate` runs the extensions of an
application once and records what they did; the packaged application loads that record instead of running them
(Vidocq/vauban#288). An extension that breaks these rules loses work or checks in a packaged application:

- compute from the deployment's classes only: no configuration value, environment variable, outside file or network
  resource in a phase;
- check the start environment in an observer of `jakarta.enterprise.event.Startup` that throws a
  `DeploymentException`, fed with what the build collected (`withParam`); models: Ravel's `ConfigStartCheck`,
  Cervantes' `JwtConfigurationCheck`;
- add annotations, never remove them; carry data in parameters, never in fields of the extension;
- ask `ExtensionPhase.isBuildTime()` only as a last resort, in a class of its own: an extension that asks runs again
  at each start (Heisenberg's `InterceptorPriorityExtension`);
- the brick's Vidocq runtime extension adds its own beans with `VaubanContainerBuilder.addHostBean`, never
  `addBeanClass`, and its `-codegen` bundle brings the brick's CDI jar to the processor path;
- list in `@io.vidocq.vauban.api.ClientProxies` every interface the extension registers as a synthetic bean of a
  normal scope (`@ApplicationScoped`, `@TransactionScoped`, ...). Vauban generates client proxies at build from the
  classes it compiles, and a synthetic bean has none: the freeze records its descriptor, not a proxy, and a deployment
  that runs its extensions at start has no build step. Without the list, the container builds a
  `java.lang.reflect.Proxy` at every start: every call goes through reflection, the boot is slower, nothing runs ahead
  of time, and every application prints the `RUNTIME CODE GENERATION FALLBACK` warning (Vauban BUG-20261113-19:
  Noether's `EntityManager` and `EntityManagerFactory`). Put it on a class of a module compiled with
  `vauban-processor` that provides its `_VaubanComponents`: the `*-cdi-vauban` extension, or, for a
  container-neutral brick, its Vidocq runtime extension (Vidocq's `NoetherPersistenceExtension`). Public interfaces
  only, no class, no `@Dependent` bean;
- test both ways: the extensions run at start (TCK, another container) and frozen (the startup report says
  `cdi extensions  N extensions frozen at build`, and an invalid environment still stops the start).

Guide: `docs/writing-a-build-compatible-extension.md` in `Vidocq/vidocq-workspace`, and Vauban's concepts page,
"Writing an extension the build can freeze" (`docs/en/modules/ROOT/pages/concepts.adoc#writing-a-frozen-extension`).

## Prerequisites

- **Java 25** + **Maven 3.10.0** (`.sdkmanrc` provided — use `sdk env`)
- `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:3.0.2` is already modular
  upstream (module `jakarta.servlet.jsp.jstl`; it `requires transitive`
  `jakarta.servlet.jsp`, `jakarta.servlet`, `jakarta.el`, `java.sql` and `java.desktop`)
  — no Vidocq fork needed. Its POM declares Servlet 6.0 / EL 5.0 at compile scope; the
  root POM excludes them and pins the EE 11 levels as `provided`.

## Essential Commands

```bash
sdk env
./mvnw -ntp clean install                          # default reactor (no TCK; also starts Open Liberty for bodoni-it-openliberty)
./mvnw test
./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify   # Ibarra IT (see note below)
./mvnw -ntp -pl bodoni-it-other-containers/bodoni-it-openliberty -am verify   # Open Liberty IT
./mvnw -ntp -Ptck -pl bodoni-tck test              # TCK runner's own tests
./run-official-tck-tags-3.0.sh smoke|all|-Dtest=…  # official Tags TCK (--help: --no-prepare, --no-install)
```

The `it-ibarra` profile is not part of the default build: it needs **local snapshots** of
chappe, foy, peano and ibarra (`0.4.0-SNAPSHOT`) in `~/.m2`. The IT runs under
`peano.reflection=deny` (`denyModeIsActive` proves it); its `varStatus`, `Map.Entry` and
`sql:query` `Result` tests (BUG.md BOD-001, fixed) need the `bodoni-peano` adapter and a Peano with
supertype lookup. BOD-002 is fixed in Vidocq/foy (Foy BUG-20261022-06, [Vidocq/foy#76](https://codefloe.com/Vidocq/foy/pulls/76)). Foy builds that
predate it leave the request thread's context class loader unset, so `fmt:` cannot find
application bundles; the `it-ibarra` ITs need a Foy snapshot that contains the fix.
The `sql:` pages run against an in-memory H2 database (test scope, from Maven Central); the
host runs with `-Duser.timezone=Europe/Paris`, which guards BOD-005.

`bodoni-tck` is **in-reactor behind the `tck` Maven profile**: a plain `./mvnw install`
neither builds nor downloads anything TCK-related. `-Ptck` runs the runner's own tests;
`-Ptck,tck-official` adds `jakarta.tck:tags-tck:11.0.3` and runs its classes. The script
downloads the official `jakarta-tags-tck-3.0.0.zip` (or takes `TAGS_TCK_ZIP`), checks its
pinned SHA-256 and extracts only the Derby DDL into `bodoni-tck/target/`; never commit a
TCK file (EFTL). The `sql` tests run against H2 served over TCP on `127.0.0.1`.

## Architecture

```
bodoni-core   ← JSTL 3.0 implementation: tag handlers, functions and TLDs of
                c:, fmt:, fn:, x:, sql:  (all five since B4)
bodoni-peano  ← optional engine adapter: LoopTagStatus, Result and LocalizationContext accessor tables for Peano
                `peano.reflection=deny` (BOD-001); the only module allowed to depend on Peano
bodoni-it-ibarra ← integration test on Ibarra + Foy + Peano, `it-ibarra` profile only
bodoni-tck    ← official Tags TCK runner (jakarta.tck:tags-tck:11.0.3) on ibarra-arquillian,
                in-reactor behind `tck`, not published, class path (no module-info)
(planned)     bodoni-ibarra  ← optional build-time inliner for Ibarra (TagInliner SPI)
bodoni-it-other-containers/bodoni-it-openliberty ← portability proof on Open Liberty (WAR, not published)
```

## Architecture Constraints Not to Violate

1. **`bodoni-core` depends on the JSTL API only** (compile scope), plus the `provided`
   Pages / Servlet / EL APIs it brings transitively. No Ibarra, Peano, Foy or Vauban
   dependency in `bodoni-core` — ever. It must run unchanged on any Pages container
   (Ibarra, Jasper, Open Liberty). The one module allowed to depend on Peano is
   `bodoni-peano`, an optional engine adapter (engine-adapter exception of the ecosystem's
   brick/extension rule, like `cassini-chappe`): `peano-api` only, no `bodoni-core`.
2. **Standard tag handlers are the portable reference.** They are what Jasper or Open
   Liberty run and what the TCK measures. Build-time inlining (`bodoni-ibarra`) is an
   optional optimisation that must be behaviourally identical, tested differentially
   against the handlers.
3. **Ibarra holds only the extension contract** (the `TagInliner` SPI, PLAN.md). JSTL code
   never moves into Ibarra (Ibarra's AGENTS.md rule 6). Changes to Ibarra are cross-repo
   items proposed in Ibarra's own repository.
4. **No reflection** beyond what the EL implementation already does when evaluating
   expressions; no `setAccessible`, no `opens`. The one exception is spec-mandated:
   `Class.forName(driver, true, loader)` loads the JDBC driver class a `sql:` data source
   names (Tags 3.0 §10.2), "the one reflective act" of PLAN.md and the Reference page.
5. **No `synchronized`, no `ThreadLocal`** — tag state travels in the handler instance and
   the `PageContext`; caches are `ConcurrentHashMap`.
6. **JDK modules only** for XML (`java.xml`), SQL (`java.sql`, `java.naming`) and HTTP (`java.net.http` or
   `URLConnection`) — no third-party XPath/XSLT/HTTP library.
7. **JNDI is the standard `InitialContext` lookup**, exactly as on any server. Bodoni does
   not provide a naming implementation (PLAN.md open question 7: under Vidocq, `java:comp`
   comes from the runtime, vidocq#297).
8. **JUnit 6** (`junit-bom` managed by `vidocq-parent`), no Mockito — hand-written
   fixtures and `PageContext` / servlet doubles only.
9. **Language** — code, Javadoc, comments, commit messages and every `.md` file are in
   **English**.

**Architecture rules as tests** — **ArchUnit** (`com.tngtech.archunit:archunit`, Apache-2.0, through the shared
`vidocq-archrules` library of `vidocq-parent`) is test scope, used only by the test-only module
`bodoni-architecture-tests` (Vidocq/vidocq#289): its `ArchitectureRules` turn constraints 1, 4, 5 and 6 into tests on
the classes of `bodoni-core` and `bodoni-peano`. The one documented reflective fallback (constraint 4) is listed with
its reason in `ArchitectureRules.REFLECTIVE_FALLBACKS`; never add one without a documented reason there.

## Conventions

- **Maven groupId**: `io.vidocq.bodoni`; version `0.4.0-SNAPSHOT` (workspace dev line,
  parent `io.vidocq:vidocq-parent:0.4.0-SNAPSHOT`).
- **Java modules**: `io.vidocq.bodoni.core` (artifact `bodoni-core`), `io.vidocq.bodoni.peano`
  (artifact `bodoni-peano`); planned
  `io.vidocq.bodoni.ibarra` (artifact `bodoni-ibarra`).
- **Packages** (fixed in B1): `io.vidocq.bodoni.core.tags.<library>` (exported tag
  handlers: `tags.core`, `tags.fmt`, `tags.xml` and `tags.sql`), `io.vidocq.bodoni.core.functions`
  (exported `fn:` functions) and `io.vidocq.bodoni.core.internal` = implementation, not exported.
- Licence header from `etc/license-header.txt` on every `.java` file.
- Commits: see "Ecosystem rules" above.

## TDD — Test-Driven Development (mandatory)

Red → green → refactor, one spec chapter at a time, following the PLAN.md milestones.
Cite the spec section in commit messages / PR descriptions (e.g. "Tags 3.0 §6.2
`<c:forEach>`").

## TCK — Technology Compatibility Kit

B5 delivered the runner (`bodoni-tck`, `run-official-tck-tags-3.0.sh`) and the first full
run: **452/541 (83.5 %)**; the TCK fixes (the `_rt` TLDs, BOD-006 to BOD-011, Peano COMP-010)
bring it to **541/541**, both runs recorded in [bodoni-tck/BASELINE.md](bodoni-tck/BASELINE.md). The suite is `jakarta.tck:tags-tck:11.0.3`
(Tags tests of the Jakarta EE 11 Platform TCK); the formal `jakarta-tags-tck-3.0.0.zip` is the
reference only and is not run. A result is a compatibility measurement on Bodoni + Ibarra +
Foy + Peano: **never claim certification** from it, nor from a partial or local run. A pull
request runs no TCK. The `official-tags-tck-smoke` check of the former repository (a required
check of its `main` since 2026-10-24) did not move into the monorepo; instead `ci.tck.command`
of Bodoni's root POM has the `TCK nightly` workflow run `./run-official-tck-tags-3.0.sh all`
once a day, on demand on a branch (tag `BODONI`) and for a release. The script installs into
a local repository private to the checkout (`.tck-repository/`, `TCK_LOCAL_REPOSITORY`), never
into `~/.m2`. Update `BASELINE.md` when a fix
changes the result. 100 % PASS of the full run is a merge gate; any challenge goes in a
`TCK.md` with spec citation — created only if one is actually filed.

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

The `performance` page carries only figures that have an entry in `BENCH.md`.

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
