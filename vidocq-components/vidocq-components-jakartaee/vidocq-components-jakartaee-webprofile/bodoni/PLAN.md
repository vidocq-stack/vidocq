# Bodoni — Design plan

> Status: **B5 delivered (2026-10-24)** -- the official Tags TCK runner (`bodoni-tck`,
> `run-official-tck-tags-3.0.sh`) and its first full run: **452/541 (83.5 %)**, every failure
> triaged in `bodoni-tck/BASELINE.md`; the fixes follow in later pull requests until 100 %.
> The `fn:` functions, the `c:` core tags, the `fmt:` i18n and formatting tags, the `x:` XML
> tags and the `sql:` database tags are implemented in `bodoni-core` (B1–B4), with unit tests
> and an Ibarra integration test (`bodoni-it-ibarra`, `it-ibarra` profile). This document is the design of record for Bodoni, the
> Jakarta Standard Tag Library 3.0 (JSTL) implementation of the Vidocq ecosystem,
> required by Jakarta EE Web Profile 11 and tracked by
> [Vidocq/vidocq#266](https://codefloe.com/Vidocq/vidocq/issues/266) (see
> `vidocq/WEB-PROFILE.md`). A TCK result is a compatibility measurement, not a
> certification (open question 1).
> Decisions marked **(to validate)** need the maintainer's sign-off before the
> milestone that depends on them starts. Facts marked **(verified YYYY-MM-DD)** were
> checked against the artefact or the spec named next to them; everything else is a
> plan or an open question.

## Vision

Bodoni implements the five JSTL 3.0 tag libraries as **standard, portable tag
handlers** — the same jar runs on Ibarra, on Jasper or on Open Liberty — and, when the
pages are precompiled by Ibarra, lets the most common core tags be **inlined at build
time** through an optional module:

- **zero third-party dependency** at run time: the JSTL API jar (which brings Pages,
  Servlet and EL) and JDK modules (`java.xml`, `java.sql`, `java.naming`, `java.net.http`) only — no
  Xalan, no Apache Taglibs, no Commons;
- **strict Java Modules**: one `module-info.java` per artefact, no `opens`;
- **a brick depends on APIs**: no Ibarra, Peano or Foy dependency in the runtime jar;
  the Vidocq runtime extension assembles Bodoni + Ibarra + Peano;
- **static code generation where it pays**: the handlers are the reference; inlining is a
  build-time optimisation, never a different semantics.

The name: Giambattista Bodoni (1740–1813), printer to the Duke of Parma, contemporary
and rival of Joaquín Ibarra. His *Manuale Tipografico* (1788; posthumous 1818 edition)
is a catalogue of standard, ready-made type that any printer composes pages with. JSTL
is the standard catalogue of tags every page composes with: Ibarra composes and prints
the pages, Bodoni supplies the type.

```
   build time (application build)                    run time (any Pages container)
 ┌────────────────────────────────────┐        ┌──────────────────────────────────┐
 │ ibarra-maven-plugin                │        │ page servlet (Ibarra / Jasper /  │
 │  ibarra-compiler reads Bodoni TLDs │        │ Open Liberty)                    │
 │  ServiceLoader<TagInliner>  ──┐    │        │   ├─ inlined c:if / c:forEach …  │
 │                               ▼    │        │   │  (Ibarra + bodoni-ibarra)    │
 │  bodoni-ibarra (optional):         │        │   └─ new ForEachTag() … standard │
 │   c:if/choose/forEach/set/out …    │ ─────► │      handler lifecycle           │
 │   → inline generated code          │        │            │                     │
 └────────────────────────────────────┘        │            ▼                     │
                                               │   bodoni-core handlers, fn:,     │
                                               │   fmt:, x: (java.xml), sql:      │
                                               │   (java.sql, JNDI InitialContext)│
                                               └──────────────────────────────────┘
```

## Specification scope (Jakarta Standard Tag Library 3.0)

Spec: <https://jakarta.ee/specifications/tags/3.0/> (final, 2022-04-11). Tag library
URIs (verified 2026-10-22, spec §1.2):

| Library | URI | Prefix | Spec chapters |
|---|---|---|---|
| Core | `jakarta.tags.core` | `c` | §4 general purpose (`out`, `set`, `remove`, `catch`), §5 conditionals (`if`, `choose`, `when`, `otherwise`), §6 iterators (`forEach`, `forTokens`), §7 URL (`import`, `url`, `redirect`, `param`) |
| I18N / formatting | `jakarta.tags.fmt` | `fmt` | §8 i18n (`setLocale`, `requestEncoding`, `bundle`, `setBundle`, `message`, `param`), §9 formatting (`formatNumber`, `parseNumber`, `formatDate`, `parseDate`, `setTimeZone`, `timeZone`) |
| SQL | `jakarta.tags.sql` | `sql` | §10 (`setDataSource`, `query`, `update`, `transaction`, `param`, `dateParam`) |
| XML | `jakarta.tags.xml` | `x` | §11 core (`parse`, `out`, `set`), §12 flow control (`if`, `choose`, `when`, `otherwise`, `forEach`), §13 transformation (`transform`, `param`) |
| Functions | `jakarta.tags.functions` | `fn` | §15 (`contains`, `containsIgnoreCase`, `endsWith`, `escapeXml`, `indexOf`, `join`, `length`, `replace`, `split`, `startsWith`, `substring`, `substringAfter`, `substringBefore`, `toLowerCase`, `toUpperCase`, `trim`) |

Plus the §14 tag library validators (`ScriptFreeTLV`, `PermittedTaglibsTLV`) and §2.8 /
`Config` configuration data (`jakarta.servlet.jsp.jstl.fmt.locale`, `.fallbackLocale`,
`.localizationContext`, `.timeZone`, `jakarta.servlet.jsp.jstl.sql.dataSource`,
`.maxRows`).

**API vs implementation split.** The API jar
`jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:3.0.2` (verified 2026-10-22 on
the jar) contains only the `jakarta.servlet.jsp.jstl.core` (`Config`, `LoopTag`,
`LoopTagSupport`, `ConditionalTagSupport`, `LoopTagStatus`, `IteratedExpression`, …),
`.fmt` (`LocaleSupport`, `LocalizationContext`), `.sql` (`Result`, `ResultSupport`,
`SQLExecutionTag`) and `.tlv` (`ScriptFreeTLV`, `PermittedTaglibsTLV`) packages. It ships
**no TLD and no tag handler**: Bodoni ships the TLDs (`META-INF/*.tld` of
`bodoni-core`), every handler, the `fn:` static functions and the internal support
(scoped-variable helpers, locale/bundle resolution, XPath engine glue, data-source
resolution).

Legacy URIs (decided in B1, 2026-10-22, closing open question 2): the JSTL 1.2 URIs
(`http://java.sun.com/jsp/jstl/core`, `…/functions`) are shipped, as `c-1_2.tld` and
`fn-1_2.tld` mapped to the same classes, so that existing pages keep working; the JSTL 1.0
URIs (`_rt` variants, 1.0 EL TLDs) are not shipped, as no maintained page targets them.
For reference, the GlassFish/WildFly
implementation (`org.glassfish.web:jakarta.servlet.jsp.jstl:3.0.1-jbossorg-1`, inspected
2026-10-22) still ships them alongside the `jakarta.tags.*` TLDs; spec Appendix B.1 only
records the rename (jstl-api#144). The TCK does use the JSTL 1.0 URIs (verified in B5, open
question 2): the four `_rt` TLDs ship in a later pull request (maintainer decision,
2026-10-23).

**Spec text vs EE 11 level.** Spec §1.3 still says "requires a Jakarta Server Pages 3.0
web container" and "Jakarta Expression Language 4.0"; Web Profile 11 pairs Tags 3.0 with
Pages 4.0 / EL 6.0 / Servlet 6.1. Bodoni targets the EE 11 levels (`provided` scope).
A Tags 3.1 line exists as milestones (`jakarta.servlet.jsp.jstl-api` 3.1.0-M1/M2 on
Maven Central, on Servlet 6.2 / Pages 4.1 / EL 6.1 milestones): out of scope.

## Brick rule: API at compile, container APIs provided

Applied from the workspace `AGENTS.md` ("A brick depends on APIs; the Vidocq runtime
extension assembles"):

| Dependency | Scope | Why |
|---|---|---|
| `jakarta.servlet.jsp.jstl-api` 3.0.2 | `compile` | the spec Bodoni implements; reaches applications transitively |
| `jakarta.servlet.jsp-api` 4.0.0 | `provided` | the Pages container brings it |
| `jakarta.servlet-api` 6.1.0 | `provided` | idem |
| `jakarta.el-api` 6.0.1 | `provided` | idem |

The upstream API POM declares `jakarta.servlet-api` 6.0.0 and `jakarta.el-api` 5.0.0 at
**compile** scope (verified 2026-10-22): the root POM excludes both so they never reach
an application WAR, and pins the EE 11 levels as `provided`.

Never another brick's implementation: no `ibarra-core`, no `peano-core`, no `foy-*` in
`bodoni-core`, not even at `test` scope for unit tests (hand-written `PageContext`
doubles). Integration tests that need a real container run in dedicated modules
(`bodoni-tck`, `bodoni-it-openliberty`), which are not published.

**Portability is tested.** `bodoni-it-other-containers/bodoni-it-openliberty` (pattern of
`champollion-it-openliberty`, Liberty Maven plugin) builds a WAR with exactly the
dependencies the Reference page tells users to declare, deploys it on Open Liberty and
checks each library. The Reference page carries the usual `WARNING` block "Deploying on
an application server". Open Liberty's `pages-4.0` feature itself exposes the
`jakarta.servlet.jsp.jstl.*` packages (Open Liberty feature docs, checked 2026-10-22), so
there is no "server JSTL feature" to switch off: see open question 5.

The **Vidocq runtime extension** (in the `vidocq` repository, later) assembles Bodoni +
Ibarra + Peano and, optionally, puts `bodoni-ibarra` on the precompilation path.

## Java Modules layout

| Module (artefact) | Java module | Kind | Requires | Exports |
|---|---|---|---|---|
| `bodoni-core` | `io.vidocq.bodoni.core` | runtime | `transitive jakarta.servlet.jsp.jstl` (brings `jakarta.servlet.jsp`, `jakarta.servlet`, `jakarta.el`, `java.sql`, `java.desktop` → `java.xml`); `transitive java.xml` (B3); `transitive java.sql` (B4: pages hand `javax.sql.DataSource` objects to the actions and read `java.sql` values from `Result` rows); `java.naming` (B4, not transitive: only `internal.DataSources` uses it); `java.net.http` (`c:import`, B1); `uses java.sql.Driver` (B4: the JDBC driver of a JDBC parameter string is found through the `ServiceLoader` of the context class loader) | the tag-handler packages `tags.core`, `tags.fmt`, `tags.xml`, `tags.sql` and the `functions` package (unqualified: generated pages and other containers instantiate handlers); `…core.internal.*` not exported |
| `bodoni-peano` | `io.vidocq.bodoni.peano` | runtime, optional engine adapter | `jakarta.servlet.jsp.jstl`, `io.vidocq.peano.api` | nothing — `provides` Peano's `AccessorProvider` SPI (`LoopTagStatus` table, B1; `Result` table, B4) |
| `bodoni-ibarra` (planned) | `io.vidocq.bodoni.ibarra` | build only | `io.vidocq.bodoni.core`, Ibarra's compiler-side SPI module | nothing — `provides` the `TagInliner` SPI |
| `bodoni-tck` (B5) | — (class path: Arquillian, ShrinkWrap and the TCK clients are not Java modules; the deployments stay modular) | test, `-Ptck`, not published | `ibarra-arquillian` (brings Ibarra, Foy, Peano, Chappe), H2, `jakarta.tck:tags-tck:11.0.3` (`tck-official`), all test scope | — |
| `bodoni-it-openliberty` (planned) | — (WAR) | test | `bodoni-core` | — |

Facts on the API module (verified 2026-10-22, `jar --describe-module` on 3.0.0 and
3.0.2): name `jakarta.servlet.jsp.jstl`, exports the four API packages, and
`requires transitive jakarta.el, jakarta.servlet, jakarta.servlet.jsp, java.sql,
java.desktop`. The API sources import nothing from `java.desktop`; the TLVs use SAX
(`java.xml`), which `java.desktop` re-exports transitively. Consequence: **every Bodoni
user reads `java.desktop`**, which Peano deliberately avoids (Peano AGENTS.md rule 2).
Recorded as open question 3; Bodoni itself must not use `java.beans`.

TLDs live in `META-INF/` of the `bodoni-core` jar — not a package, so not encapsulated —
which is where Pages containers scan library jars and where Ibarra's build-time TLD
discovery looks (Ibarra AGENTS.md rule 11: libraries are reachable named modules).

## Ibarra build-time inlining

Ibarra precompiles pages during the application build and already calls `new` and the
setters of tag handlers directly (Ibarra AGENTS.md rule 4). For the hot core tags a
standard handler still costs an allocation, a lifecycle (`setPageContext`, `setParent`,
`doStartTag`, `doAfterBody`, `doEndTag`, `release`) and, for iteration, `LoopTagSupport`
status bookkeeping. Jasper solves this with its `TagPlugin` mechanism
(`org.apache.jasper.compiler.tagplugin.TagPlugin`; Tomcat ships plugins for `c:if`,
`c:choose`, `c:when`, `c:otherwise`, `c:forEach` and other core tags in
`org.apache.jasper.tagplugins.jstl.core`).

Plan:

- **In Ibarra (cross-repo item, not done here):** a translation-time SPI, working name
  **`TagInliner`**, discovered with `ServiceLoader` **in the build JVM** by
  `ibarra-compiler`. For a tag occurrence whose TLD URI and name it claims, an inliner
  receives the tag's attributes (literal / compiled EL), its body and the generation
  context, and emits generated code instead of the standard handler call — or declines,
  in which case the standard handler path is used. Ibarra holds **only the contract**;
  Ibarra AGENTS.md rule 6 ("JSTL is out of scope") stays true. The SPI shape (Java
  source fragments today, Class-File API later; how scoped variables, `varStatus` and
  `NESTED`/`AT_END` visibility are exposed) is to be designed in Ibarra's PLAN.md with
  its maintainer.
- **In Bodoni:** an optional module **`bodoni-ibarra`** implementing `TagInliner` for
  `c:if`, `c:choose` / `c:when` / `c:otherwise`, `c:forEach`, `c:forTokens`, `c:set`,
  `c:remove`, `c:out` and `c:catch`. It is a build-time artefact: it never reaches the
  application runtime.
- **The standard handlers stay the portable reference and the TCK baseline.** The TCK is
  run first with inlining off (B5), then again with `bodoni-ibarra` on (B6); every inliner
  has differential tests against its handler (same page, same model, same output and
  scoped variables).
- Inlining is skipped (handler path) when a tag uses a feature the inliner does not
  model — e.g. `c:forEach` with a deferred `items` expression (`IteratedValueExpression`
  semantics), `varStatus` consumed by custom tags, or nesting under a custom tag that
  calls `findAncestorWithClass`.

## Ecosystem-philosophy friction points

### `sql:` — DataSource resolution

Spec §10.1.1 / §10.6 (verified 2026-10-22): the `dataSource` attribute (or the
`jakarta.servlet.jsp.jstl.sql.dataSource` configuration setting, `Config.SQL_DATA_SOURCE`,
settable as a context parameter or scoped attribute) is either a `javax.sql.DataSource`
object, or a `String` that is
1. a **JNDI relative path** under `java:comp/env` (e.g. `jdbc/myDatabase`), or
2. a **JDBC parameter string** `url[,[driver][,[user][,password]]]` (`\` escapes `,` and
   itself) for a `DriverManager`-style connection.

Order implemented in B4 (`internal.DataSources`, `tags.sql.SqlSupport`):

1. the `dataSource` attribute when the page specified it (specified as `null`: the action
   fails), else `Config.find(pc, Config.SQL_DATA_SOURCE)` (page, request, session and
   application scope, then the context parameter); neither: the action fails;
2. a `javax.sql.DataSource` is used as is;
3. a `String` that is blank fails; any other string is looked up as `java:comp/env/<string>`
   through `new InitialContext()` (closed after the lookup); a `DataSource` bound there is
   used, anything else bound there fails (a wrong binding is a configuration error);
4. when the lookup throws any `NamingException` (no naming provider, name not found) or
   answers `null`, the string is parsed as a JDBC parameter string and wrapped in Bodoni's
   internal, unpooled `DriverManagerDataSource`;
5. any other type fails.

Each action resolves its data source on every invocation: no cache, no pool.

Driver discovery for a JDBC parameter string (rule and evidence in "Decisions recorded in
B4"): the data source captures the thread context class loader (else Bodoni's own) when it is
created; the named `driver` class, if any, is loaded there with
`Class.forName(driver, true, loader)`, the one reflective act the spec mandates (§10.2,
"ensure it is loaded"); a connection comes from the first driver of
`ServiceLoader.load(Driver.class, loader)` that accepts the URL and connects, else from the
first such driver of `DriverManager.drivers()`. A pre-JDBC 4 driver without
`META-INF/services/java.sql.Driver` is found only when Bodoni's own class loader can see it.
Bodoni **does not provide** a naming implementation.

**Decided (2026-10-22):** under Vidocq, JNDI `java:comp` is provided by the runtime, in
`vidocq-runtime-naming-extension` — a read-only `java:` context backed by CDI, where a
`@Named("x") DataSource` answers `java:comp/env/jdbc/x`
([vidocq#297](https://codefloe.com/Vidocq/vidocq/issues/297)). Bodoni keeps the standard
`InitialContext` lookup and is not designed around it. Until #297 lands, `sql:` with a JNDI
string fails under Vidocq; the object and JDBC-string forms work without it, and the Bodoni
runtime extension can set the default CDI `DataSource` through `Config.SQL_DATA_SOURCE`.

`sql:transaction` uses `Connection.setAutoCommit(false)` / `commit` / `rollback` on a
connection it owns; interplay with Jakarta Transactions (Mansart) is not a JSTL
requirement and is out of scope.

### `x:` — XML with the JDK only

`x:parse` / `x:transform` use `javax.xml.parsers` and `javax.xml.transform` (module
`java.xml`); XPath uses `javax.xml.xpath`. No Xalan. The hard part is spec §11.1.2:
XPath **variable bindings over page scopes** (`$foo`, `$param:foo`, `$header:foo`,
`$cookie:foo`, `$initParam:foo`, `$pageScope:foo`, …) — implemented with an
`XPathVariableResolver` backed by the `PageContext`, plus the §11.1.6 empty-document
default context node and the `x:forEach` context. External references (entities, DTD,
`xsl:import`, `xsl:include`, `document()`, spec §11.1.7) follow the same-origin policy
decided in B3 (open question 6, "Decisions recorded in B3").

### `c:import` — no HTTP library

Absolute URLs use `java.net.http.HttpClient` (virtual-thread friendly) or
`URLConnection` (to validate in B1 — `HttpClient` brings module `java.net.http`);
context-relative and foreign-context URLs use `RequestDispatcher.include` with a
response wrapper, as the spec requires. The exported `Reader` (syntax 2) is closed in
`doFinally`.

### `fmt:` — resource bundles under Java Modules

`fmt:setBundle` / `fmt:bundle` / `fmt:message` resolve `ResourceBundle`s by base name.
From a named module, `ResourceBundle.getBundle(String, Locale, ClassLoader)` does not see
bundles encapsulated in other named modules; `getBundle(String, Locale, Module)` can
load on behalf of the application module, but which module to use (page class's module?
context class loader's unnamed module for WARs?) was unverified; settled in B2 (open
question 4, evidence table there). `opens` of the bundle package is one of three documented
placements, never the only one.

### Reflection, threads

No reflection beyond what the EL implementation already does when evaluating attribute
expressions (Peano under Vidocq, any EL elsewhere) and the spec-mandated JDBC `driver`
class loading. No `synchronized`, no `ThreadLocal` (virtual threads): handler state is
per instance (the Pages container may pool classic handlers; Ibarra does not), locale /
bundle / time-zone state lives in scoped attributes via `Config`.

## TCK plan

As built in B5 (decisions and rulings in "Decisions recorded in B5"; implementation plan
`docs/superpowers/plans/2026-10-24-b5-tck.md`):

- **Suite**: `jakarta.tck:tags-tck:11.0.3` from Maven Central, the Tags tests of the Jakarta EE
  11 Platform TCK: 50 `JSTLClientIT` classes, 541 `@Test` methods, on Arquillian and JUnit 5.
  The formal `jakarta-tags-tck-3.0.0.zip` (JavaTest/Ant) is the reference only and is not run
  (open question 1).
- **Runner**: `bodoni-tck`, a module of the root `tck` profile only, not published (deploy,
  install, Javadoc, sources and GPG skipped). `-Ptck` runs its own tests (Arquillian
  configuration, database, script); `-Ptck,tck-official` adds the official jar and scans its
  `**/*IT.class` (`dependenciesToScan`, `failIfNoTests`). No `module-info`: Arquillian,
  ShrinkWrap and the TCK clients are not Java modules, so Surefire runs on the class path
  (`useModulePath` `false`), as in `ibarra-tck`. No parallel JUnit execution: the adapter keeps
  its deployments in one unsynchronised map.
- **Adapter**: the published `io.vidocq.ibarra:ibarra-arquillian`, test scope, with the
  Arquillian BOM 1.9.4.Final imported in the module's own `dependencyManagement` and
  `arquillian-junit5-container`. It precompiles each WAR with Ibarra and runs it on modular Foy
  over Chappe in its own process (`tck.application` and `tck.host` named modules,
  `--limit-modules`, no compiler, `-Dpeano.reflection=deny`). Foy, Peano and Chappe come on the
  versions Ibarra resolves; `bodoni-tck` declares none of them. `ibarra.version` moved to the
  root `<properties>` (used by the `it-ibarra` and `tck` profiles).
- **Adapter properties** (`arquillian.xml`): `applicationLibraries` =
  `io.vidocq.bodoni.core,io.vidocq.bodoni.peano,jakarta.servlet.jsp.jstl,com.h2database` (one
  application class loader, so the TCK's `Class.forName("org.h2.Driver")` and `DriverManager`
  see H2); `jdkModules` = `java.sql,java.naming,jdk.localedata` (not reached by the host boot
  layer; `jdk.localedata` is a service provider no `requires` reaches);
  `applicationOpens` = `com.sun.ts.tests.jstl.common.resources` (the TCK common jar's bundles,
  loaded by name by `fmt:`; present in every WAR).
- **Database**: `TckDatabaseListener` (a JUnit Platform `LauncherSessionListener`, registered
  through `META-INF/services`) acts only when `bodoni.tck.ddl` is set, which only
  `tck-official` does. Once per test JVM it loads the Derby DDL unchanged into the in-memory H2
  2.3.232 database `jdbc:h2:mem:jstl` (no compatibility mode), serves it with an H2 TCP server
  on an ephemeral port bound to `127.0.0.1` (system property `h2.bindAddress`, no
  `-tcpAllowOthers`, no `-ifNotExists`), sets `jstl.db.url`
  (`jdbc:h2:tcp://127.0.0.1:<port>/mem:jstl`), `jstl.db.user` and `jstl.db.password` (`jstl`)
  and `jstl.db.driver` (`org.h2.Driver`), and closes the database in a shutdown hook. One
  database per run, as the formal harness's single `initdb`.
- **DDL source**: `tags-tck/sql/derby/derby.ddl.jstl.sql` of the official zip, which the script
  downloads (or takes from `TAGS_TCK_ZIP`), checks against its pinned SHA-256
  (`996c9abc…70cb5a`) and extracts alone into `bodoni-tck/target/tags-tck-distribution/`. No
  TCK file is committed (EFTL).
- **Script**: `run-official-tck-tags-3.0.sh [smoke|all|-Dtest=…] [--no-prepare] [--no-install]
  [Maven arguments]`. It requires Java 25, builds Bodoni (`./mvnw -ntp clean install`) unless
  `--no-install`, which refuses a missing or stale `bodoni-core` / `bodoni-peano` snapshot;
  `--no-prepare` refuses a missing DDL. It deletes `target/surefire-reports` and
  `target/pages-deployments` of the module, runs the runner's tests, then the selection.
- **Smoke and CI**: `smoke` runs six whole classes at 100 % in the baseline (91 tests), one per
  library plus the URI check: `spec.core.general.remove`, `spec.etu.functions`,
  `spec.etu.uri`, `spec.fmt.i18n.param`, `spec.sql.setdatasource`,
  `spec.xml.xmlcore.bindings`. The `official-tags-tck-smoke` check
  (`.forgejo/workflows/tck-tags.yml`) runs it on pull requests, pushes to `main` and on
  demand. The full run is not in CI.
- **Gate**: 100 % PASS is the merge gate once green; challenges, if filed, go to a `TCK.md`.

## Milestones (TDD — test first, every milestone)

| # | Content | Done when |
|---|---|---|
| B0 | Scaffold: reactor, `bodoni-core` module descriptor, docs, this plan | `./mvnw -ntp clean install` green (needs the first commit, or `-Dmaven.gitcommitid.skip=true`) |
| B1 | **Done (2026-10-22).** `fn:` functions + TLD; `c:` core (§4–§7) handlers + TLD, `Config`, scoped-variable rules (§2.2) | unit tests per tag on `PageContext` doubles; pages render on Ibarra in an IT (`bodoni-it-ibarra`, `-Pit-ibarra`). BOD-001 fixed by `bodoni-peano` |
| B2 | **Done (2026-10-22).** `fmt:` i18n and formatting (§8–§9): 12 handlers, `fmt.tld` and `fmt-1_2.tld`, locale negotiation, bundles, time zones, number/date formatting and parsing | unit tests; pages render on Ibarra in an IT (BOD-002: fixed in Vidocq/foy, Foy BUG-20261022-06; Foy builds that predate it leave the request thread's context class loader unset, so `fmt:` cannot find application bundles; the `it-ibarra` ITs need a Foy snapshot that contains the fix); open question 4 settled |
| B3 | **Done (2026-10-23).** `x:` (§11–§13): 10 handlers in `tags.xml` (`parse`, `out`, `set`, `if`, `choose`/`when`/`otherwise`, `forEach`, `transform`, `param`), `x.tld` and `x-1_2.tld`, JDK parser/XSLT/XPath, XPath variable bindings over the page scopes, same-origin resolution of external references, `RangeLoopTagSupport` shared with `c:forEach` and `c:forTokens` | unit tests; pages render on Ibarra in an IT (external references included); open question 6 settled; `requires transitive java.xml` settles the Bodoni side of open question 3 |
| B4 | **Done (2026-10-23).** `sql:` (§10): 6 handlers in `tags.sql` (`query`, `update`, `transaction`, `param`, `dateParam`, `setDataSource`), `sql.tld` and `sql-1_2.tld`, data-source resolution (object / JNDI / JDBC parameter string) with driver discovery through the context class loader and credentials kept out of messages, a disconnected `Result` (`internal.QueryResult`), the `Result` accessor table in `bodoni-peano` | unit tests against H2 in memory (test scope); JNDI path tested with a test-only `InitialContextFactory`; pages render on Ibarra in an IT against H2 (BOD-005 found and fixed); open question 8 settled |
| B5 | **Done (2026-10-24).** `bodoni-tck` (in-reactor under `-Ptck` only, not published) on the published `ibarra-arquillian` adapter, `jakarta.tck:tags-tck:11.0.3` under `-Ptck,tck-official`, the official Derby DDL loaded into H2 served over TCP, `run-official-tck-tags-3.0.sh` (`smoke` / `all` / `-Dtest=`, `--no-prepare`, `--no-install`), the `official-tags-tck-smoke` CI check; first full run with the standard handlers: **452/541 (83.5 %)**, 33/50 classes at 100 %, 0 failures, 89 errors, 0 skipped, every failure triaged (BOD-006 to BOD-009 opened) | runner + first full run + `bodoni-tck/BASELINE.md` (maintainer, 2026-10-23); 100 % reached by later PRs, each test-first; open question 1 settled |
| B6 | Ibarra `TagInliner` SPI (Ibarra PR) + `bodoni-ibarra` inliners for the eight core tags | differential tests vs handlers; TCK green with inlining on; any speed claim in `BENCH.md` |
| B7 | `bodoni-it-other-containers/bodoni-it-openliberty` + Reference "Deploying on an application server" block | WAR with the documented dependencies passes on Open Liberty |

The Vidocq runtime extension (vidocq repository) follows B5.

## Open questions

1. ~~**TCK artefact**~~ — decided (maintainer, 2026-10-23): **`jakarta.tck:tags-tck:11.0.3`**
   from Maven Central, the Tags tests of the Jakarta EE 11 Platform TCK (Arquillian, JUnit 5,
   on `jakarta.servlet.jsp.jstl-api` 3.0.2, Pages 4.0.0, Servlet 6.1.0, EL 6.0.1). The
   `jakarta-tags-tck-3.0.0.zip` linked from the spec page (JavaTest/Ant) is the formal
   reference only: it is not run, and the runner reads one file from it, the Derby DDL of the
   `sql` tests. What a result means: a compatibility measurement on Bodoni + Ibarra + Foy +
   Peano, **not a certification**; a certification follows the Jakarta EE process with the
   formal TCK named by the specification. No page claims certification.
2. ~~**Legacy URIs**~~ — decided in B1 (2026-10-22): the JSTL 1.2 URIs
   (`http://java.sun.com/jsp/jstl/core`, `…/functions`) are shipped alongside
   `jakarta.tags.*`, because pages written for JSTL 1.2 keep working unchanged; the JSTL 1.0
   URIs (`_rt`, 1.0 EL TLDs) are not shipped. What the suite uses (verified in B5, on the
   11.0.3 jar): the `_rt` URIs in `compat/onedotzero` (38 pages), `etu/tlv/scrfree`
   (`core_rt`, 5 pages) and `sql/update` (`sql_rt`); the 1.0 EL URIs
   `http://java.sun.com/jstl/core` and `http://java.sun.com/jstl/sql` in two `sql/update`
   pages (`negativeUpdateVarAttributeTest`, `negativeUpdateScopeVarAttributeTest`). Maintainer
   decision 4 (2026-10-23): the four `_rt` TLDs ship, with the same handlers, in a later Bodoni
   pull request; the baseline triages their 40 tests as "known: _rt TLDs pending". **Open
   point:** whether the 1.0 EL URIs ship too. Decision 4 covers the `_rt` TLDs only; the two
   `sql/update` pages pass today because their URI is not mapped, and would reach the actions
   once it is.
3. **`java.desktop` via the API module** — accept that the upstream descriptor
   `requires transitive java.desktop` (apparently for `java.xml`), or report it upstream
   (jstl-api) and ask for `requires transitive java.xml` instead? Effect on jlink images
   and on Peano's "no `java.desktop`" stance to be assessed.
4. ~~**Resource bundles under Java Modules**~~ -- decided in B2 (2026-10-22): `fmt:` calls
   `ResourceBundle.getBundle(basename, locale, loader)` with the thread context class loader
   (the system class loader when it is `null`), the loader the API's `LocaleSupport` uses.
   The first-level semantics of §8.3.1 are enforced by Bodoni (the JDK refuses a
   `ResourceBundle.Control` from a named module, so Bodoni filters the candidates itself;
   the JVM default locale is never used). Evidence (JDK 25, 2026-10-22), from a library
   module, with a named application module in the same class loader:

   | Bundle location | Named application module | Application on the class path (WAR) |
   |---|---|---|
   | encapsulated package | not found (every `getBundle` overload, including on behalf of the page's module) | found |
   | `opens`ed package | found | found |
   | jar root or non-package directory (e.g. `i18n-bundles/`) | found | found |
   | class-based bundle in a non-exported package | not found | found |

   For a named application module the Reference page documents three placements: `opens`
   the bundle package, bundles at the jar root or in a directory whose name is not a Java
   package, or a `LocalizationContext` built in application code and set as
   `jakarta.servlet.jsp.jstl.fmt.localizationContext` (no `opens`). A module-aware lookup is
   deferred to `bodoni-ibarra` (B6), where generated code runs inside the application module.
   The lookup requires the container to run requests with the application class loader as
   thread context class loader. BOD-002: fixed in Vidocq/foy (Foy BUG-20261022-06, [Vidocq/foy#76](https://codefloe.com/Vidocq/foy/pulls/76)). Foy builds
   that predate it leave the request thread's context class loader unset, so `fmt:` cannot find
   application bundles; the `it-ibarra` ITs need a Foy snapshot that contains the fix.
5. **Open Liberty precedence** — `pages-4.0` exposes the JSTL API and presumably its own
   implementation for the `jakarta.tags.*` URIs; does a WAR-bundled Bodoni win (Pages
   allows a container to provide alternate implementations for well-known URIs)? What does
   the WARNING block tell users to exclude? To be settled by `bodoni-it-openliberty`.
6. ~~**XML hardening**~~ -- decided in B3 (2026-10-23): same origin; see "Decisions recorded
   in B3". Secure-processing / external-entity defaults for `x:parse` and `x:transform` vs
   spec §11.1.7 and TCK expectations.
   Evidence (JDK 25, 2026-10-23), `JdkXmlFactsTest` (raw JAXP from `newDefaultInstance()`,
   `FEATURE_SECURE_PROCESSING` true, access properties `""`, kept as a regression test):
   - `doctypeWithInternalSubsetParses` — holds: the TCK `positiveParseNoDTDValidationTest`
     document parses, `//docs` is `DATA`.
   - `resolverStreamPassesUnderEmptyAccess` — holds: an `InputSource` byte stream from the
     `EntityResolver2` passes under `""`; the access properties stay `""` (no fallback).
   - `nullResolverResultIsRefusedUnderEmptyAccess` — holds: `SAXException` naming
     `accessExternalDTD`, the `file:` target is not read.
   - `entityResolver2SeesTheLiteralAndTheBase` — holds for the literal and the base:
     `parts/p.xml` with base `bodoni-webapp:/dir/doc.xml`; a declaration inside the returned
     entity (system id `bodoni-webapp:/dir/parts/p.xml`) gets that base; the external DTD
     subset arrives as `r.dtd` with the document base. Differs on the name: the JDK passes
     `null` as the entity name for every call (DOM and SAX), never `[dtd]` or `%p`, so the
     name cannot tell the external subset from an entity.
   - `uriResolverServesImportsUnderEmptyStylesheetAccess` — holds: `resolve("common.xsl",
     "bodoni-webapp:/xsl/main.xsl")`; a byte-stream `SAXSource` passes under `""`.
   - `nullUriResolverResultIsRefusedOnlyForAFetchableScheme` — differs from the plan: a
     `null` answer is refused (`TransformerException` naming `accessExternalStylesheet`)
     only under a `file:` base. Under a `bodoni-webapp:` base the JDK's access check fails
     on the unknown scheme, prints the `MalformedURLException` stack trace on `System.err`
     and drops the `xsl:import` without an error. A resolver that throws a
     `TransformerException` fails `newTransformer` under any base: Bodoni's resolvers
     refuse by throwing, never by returning `null`.
   - `documentFunctionGoesThroughTheTransformerResolver` — holds: `document('data.xml')`
     reaches the `Transformer`'s `URIResolver` with the stylesheet's system id as base, not
     the factory's. The `Transformer` does not inherit the factory's `ErrorListener`.
   - `saxSourceReaderKeepsItsEntityResolverDuringTransform` — holds: a `SAXSource`
     document keeps its reader's entity resolver (no fallback to a `DOMSource`).
   - `filterNeedsTheResolverOnItself` — holds: without a resolver on the `XMLFilterImpl`
     the entity is refused (under a `bodoni-webapp:` base as a `MalformedURLException`
     from the JDK's access check, nothing opened); with it, resolved through the
     2-argument `resolveEntity`, which receives the expanded system id
     (`bodoni-webapp:/dir/parts/p.xml`) and no base.
   - `xpathKeepsVariablesAndRefusesExtensionFunctions` — holds: `$p:x` resolves, `$y`
     answered `null` and `java:java.lang.System.nanoTime()` throw
     `XPathExpressionException`; `evaluateExpression` types `BOOLEAN`, `NUMBER`, `STRING`,
     `NODESET`.
7. ~~**JNDI `java:comp` under Vidocq**~~ — decided: `vidocq-runtime-naming-extension`,
   [vidocq#297](https://codefloe.com/Vidocq/vidocq/issues/297) (see "`sql:` — DataSource
   resolution"). Not a Bodoni design item.
8. ~~**Module split**~~ — decided (maintainer, 2026-10-23): one jar. `bodoni-core` holds
   all five libraries, like the reference implementation; `sql:` lives in the exported
   package `io.vidocq.bodoni.core.tags.sql`. The API module already reads `java.sql` and
   `java.xml`, so separate `bodoni-sql` / `bodoni-xml` artefacts would not have shrunk the
   module graph.
9. **`TagInliner` SPI shape** — owned by Ibarra; to be designed there (cross-repo item).

## Decisions recorded in B1

- **`c:import` HTTP client** (validated 2026-10-22): `java.net.http.HttpClient` for `http`
  and `https`, `URLConnection` for the other absolute schemes; no public client SPI. The
  client is created lazily, runs on virtual threads and has a 10 second connect timeout
  and a 30 second request timeout; `URLConnection` gets 10 s connect / 30 s read timeouts.
  Relative URLs go through the `RequestDispatcher`.
- **Packages**: `io.vidocq.bodoni.core.tags.core` (handlers, exported),
  `io.vidocq.bodoni.core.functions` (exported), `io.vidocq.bodoni.core.internal` (not exported).

## Decisions recorded in B2

- **Bundle lookup** (maintainer decision, 2026-10-22): thread context class loader, system
  class loader when `null`; first-level semantics of §8.3.1 enforced by Bodoni; module-aware
  lookup deferred to B6. See open question 4 for the evidence table.
- **Dates**: `fmt:formatDate` and `fmt:parseDate` handle `java.util.Date` only (the spec
  type); no `java.time` support in B2.
- **JSTL 1.2 URI**: `http://java.sun.com/jsp/jstl/fmt` is shipped next to `jakarta.tags.fmt`
  (`fmt-1_2.tld`), as for `c:` and `fn:`.
- **Bundle prefix**: the `prefix` of an enclosing `fmt:bundle` applies to every nested
  `fmt:message`, including one with an explicit `bundle` attribute (§8.6, "any nested
  `<fmt:message>` actions").

## Decisions recorded in B3

Decided with the maintainer on 2026-10-23.

**Same-origin policy for XML external references** (open question 6). Every `x:parse` and
`x:transform` runs with `XMLConstants.FEATURE_SECURE_PROCESSING` true on each
`DocumentBuilderFactory`, `SAXParserFactory`, `TransformerFactory` and `XPathFactory` Bodoni
creates (JDK expansion limits), and `jdk.xml.enableExtensionFunctions` set to `false` on the
`TransformerFactory` and the `XPathFactory`, which the system property of the same name could
otherwise turn back on (final review of B3). Bodoni resolves every
external reference itself, the JDK never fetches on its own:

- **Resolvers.** An `EntityResolver2` for external entities and the external DTD subset, a
  `URIResolver` for `xsl:import`, `xsl:include` and `document()`; both hand the JDK an opened
  stream (`internal.SystemIdPolicy`).
- **No system id**: every external reference is refused.
- **Absolute system id**: a target must have the same scheme, host and port (scheme and host
  compared case-insensitively, default port normalised). Targets open with
  `URL.openConnection()`, connect and read timeouts set, HTTP redirects off, a 3xx or any other
  non-2xx status is a failure. Under a `file:` system id, an empty-authority UNC target
  (`file:////host/share`) is refused as not the same origin.
- **Relative system id** (§11.1.7): targets stay inside the web application, read with
  `ServletContext.getResourceAsStream`; absolute targets are refused. A path with a leading `/`
  is context-relative, anything else is relative to the directory of the current page (the
  request attribute `jakarta.servlet.include.servlet_path` when set, else
  `HttpServletRequest.getServletPath()`), like `c:import`. The reference is resolved first (`./x.xml` and `../x.xml` are normalised
  against the base): it is refused when the result leaves the web application (above the context
  root) or keeps an encoded dot segment (`%2e%2e`, `..%2f`, `..%5c`). A page `systemId` that is
  not a valid URI, or a relative one that climbs above the context root, ends the action with a
  `JspTagException` before anything is read.
- **Opaque system id** (`jar:` and the like): every reference is refused.
- **The origin is what the system id names as a whole.** Under a relative system id the web
  application is the origin, except `/WEB-INF/` and `/META-INF/` (below). Under an absolute
  `file:` system id it is the whole local filesystem, under an `http(s):` one the whole origin
  server. The Reference page advises giving no system id for an untrusted document.
- **Protected directories (maintainer decision 2026-10-23):** under a relative system id, a
  target may enter `/WEB-INF/` or `/META-INF/` only when the page's system id is already inside
  that same directory; otherwise it is refused with the reason
  `inside /WEB-INF/ or /META-INF/ while the system id is not`. The rule is per policy, so in
  `x:transform` a stylesheet under `/WEB-INF/` imports its siblings while a public document
  cannot reach into `/WEB-INF/`. "Inside" is decided by one function for the target and the
  origin (`SystemIdPolicy.protectedDirectory`): the first non-empty segment of the path,
  decoded again until no escape is left, split on `/` and `\`, cut at `;` or `:`, without
  trailing dots, spaces or control characters, compared ignoring case. A target whose path,
  decoded again, has a dot segment, or still has an escape after the last of 4 rounds, is
  refused as outside the web application; such an origin counts as public. Absolute system ids
  and the no-system-id mode are unchanged. Two consequences the Reference page spells out: a
  system id without a leading `/` inherits the page's directory, so in a view under `/WEB-INF/`
  (a forwarded `/WEB-INF/views/list.jsp` with `systemId="catalog.xml"`) it is inside `/WEB-INF/`
  (use a context-relative public path or no system id there); and `document()` is resolved by
  the stylesheet's policy, so a stylesheet under `/WEB-INF/` that calls `document()` on URIs taken
  from its input document lends its access to them.
- **The origin is fixed when the policy is built**, from the page's system id. The base a
  parser or an XSLT processor passes only resolves the reference, so an `xml:base` in an
  untrusted document cannot widen it.
- **Failures.** A refused or failed reference ends the action with a `JspException` that names
  the reference and the system id, cause kept. Formats:
  `<action>: external reference '<r>' refused, system id '<id or (none)>': <reason>` with the
  reasons `no system id`, `not the same origin`, `outside the web application`,
  `inside /WEB-INF/ or /META-INF/ while the system id is not`,
  `the system id is not hierarchical` and `not a valid URI`;
  `<action>: external reference '<r>' cannot be read, system id '<id>': <cause>`. Other parse
  and transform failures end with `, line <n>, column <m>, system id '<id>'`, line and column
  when a SAX or XSLT location knows them. The JDK's XSLT processor wraps the parse failure of
  the document it transforms without keeping it as a cause: the reader of that document, an
  `XMLFilterImpl` created per source, records it.
- **DTD.** Validation is off. A DOCTYPE with an internal subset is accepted
  (`disallow-doctype-decl` is not set): TCK `positiveParseNoDTDValidationTest`. Must pass, B5:
  `positiveParseSystemIdTest`, `positiveTransformDocSystemIdTest`,
  `positiveTransformXmlAndXmlSystemIdTest`, `positiveTransformXsltSystemIdTest`: all four,
  and `positiveParseNoDTDValidationTest`, pass in the B5 baseline.

**Evidence from the Task 1 probe** (open question 6, `JdkXmlFactsTest`) and what it led to:
the JDK access properties `ACCESS_EXTERNAL_DTD`, `ACCESS_EXTERNAL_SCHEMA` (parsers) and
`ACCESS_EXTERNAL_STYLESHEET` (XSLT) are set to `""` explicitly, because a stream supplied by
Bodoni's resolvers still passes under `""`, and a system property or `jaxp.properties` then
cannot reopen JDK-side fetching. Bodoni's resolvers never answer `null`: under a non-fetchable
base the JDK silently drops an `xsl:import` that got a `null`, so they always refuse by
throwing. The JDK passes a `null` entity name, never `[dtd]`, so the name cannot tell the
external subset from an entity.

**Private parser bases.** The parser is given `bodoni-webapp:<context-relative path>` for a
relative system id and `bodoni-none:/doc/` when the page gives none, so the JDK never expands a
reference against the JVM working directory. Messages that a page can see map them back to what
the page wrote; JDK exceptions kept in the cause chain, and the output of
`unparsed-entity-uri()`, may still show the private names (not sensitive). An exported
`Document` has a `null` document URI when the page gave no system id.

**Rulings of the B3 plan.**

| Topic | Ruling |
|---|---|
| `java.xml` | `requires transitive java.xml`: Bodoni uses it directly and exported signatures expose `org.xml.sax.XMLFilter` and `javax.xml.transform.Result`; no reliance on the API module's `java.desktop` chain (open question 3) |
| URIs | `jakarta.tags.xml` and `http://java.sun.com/jsp/jstl/xml`, same classes (`x.tld`, `x-1_2.tld`); not the 1.0 `xml` / `xml_rt` URIs; no `<validator>` (B1 precedent) |
| Package | `io.vidocq.bodoni.core.tags.xml`, exported |
| Parsing | Namespace-aware, XInclude off, error handlers that throw on errors and drop warnings (no `System.err` output); factories, builders, transformers and `XPath` objects created per use, none shared, no `Transformer` cache |
| `select` | XPath, never EL (§2.3, §11.1.5): `rtexprvalue` false |
| Exported types | `x:parse` `var` and `varDom`: the `org.w3c.dom.Document`; `x:set` of a node-set: an immutable `internal.NodeSet implements NodeList`; `x:forEach` `var`: the current `Node` |
| Java to XPath | `Boolean`, `String`, `Node`, `NodeList` as is; `Number` to `Double`; any other object `String.valueOf` (§11.1.3 leaves it open) |
| `$sessionScope:` without a session | Unbound, the evaluation fails, no session is created (as the i18n actions of B2) |
| `x:choose` / `x:when` / `x:otherwise` | Own classes in `tags.xml`: `x:when` must check for an `x:choose` parent, and `c:choose`'s branch state is package-private |
| `begin` / `end` / `step` | Public abstract `tags.core.RangeLoopTagSupport extends LoopTagSupport`, used by `c:forEach`, `c:forTokens` and `x:forEach` |
| `x:transform` | `result` when set, else `var`, else the page; a page-supplied `Source` is passed as is; a `null` `x:param` value sets no parameter |
| Readers | A page-supplied `Reader` is never closed by Bodoni |

**Known limitation (BOD-003).** Inside `x:forEach`, `position()` and `last()` at the top level
of a nested `select` evaluate against the context node alone (1 and 1): the JDK's
`javax.xml.xpath` cannot set the context position and size of §12.6. `varStatus` (`count`,
`last`) gives the position. Tracked in `BUG.md`.

**Open interpretations, to settle with the TCK (B5).** An empty `x:forEach` `select` throws, per
§12.6, while a TCK page that is packaged but not run expects no iteration. The translation-time
checks (`x:choose` without `x:when`, and so on) are not done, since there is no TLV (B1
precedent). What the TCK settled: "Decisions recorded in B5", B3 and B4 interpretations.

## Decisions recorded in B4

Decided with the maintainer on 2026-10-23.

| Topic | Decision |
|---|---|
| Module split (open question 8) | **One jar.** `sql:` lives in `bodoni-core`, exported package `io.vidocq.bodoni.core.tags.sql`; the shared machinery is in `io.vidocq.bodoni.core.internal` (not exported) or package-private in `tags.sql` |
| Data-source resolution | Spec §10.1.1 / §10.2, in the order of "`sql:` — DataSource resolution" above. Bodoni provides no naming implementation. Under Vidocq the JNDI form fails until [vidocq#297](https://codefloe.com/Vidocq/vidocq/issues/297) lands (open question 7); the object and JDBC-string forms work |
| JDBC driver loading | Settled by the probe below; the JDBC-string `DataSource` is internal and unpooled |
| Tests | H2 in memory at test scope (`bodoni-core` unit tests and `bodoni-it-ibarra`); JNDI tested with a test-only `InitialContextFactory` |
| URIs | `jakarta.tags.sql` **and** `http://java.sun.com/jsp/jstl/sql`, same classes (`sql.tld`, `sql-1_2.tld`); no `<validator>` (B1 precedent) |
| EL under `peano.reflection=deny` | `Result` is an API interface: `bodoni-peano` ships its accessor table, as for `LoopTagStatus` (BOD-001) |
| Security | Statements are `PreparedStatement`s bound by `sql:param` / `sql:dateParam`; the Reference page states that values concatenated into the SQL text are an injection risk and that JDBC credentials in context parameters are readable by the application |

**Driver discovery for a JDBC parameter string** (§10.1.1, §10.2). `internal.DriverManagerDataSource`
is unpooled: every `getConnection` opens a new connection. It captures a class loader at
construction, the thread context class loader, else its own. A named driver class is loaded
there with `Class.forName(driver, true, loader)` (`JDBC driver class '<driver>' not found`,
or `cannot be loaded` for a `LinkageError` such as a failed static initialiser; SQLState
`08001`, cause kept: a class-loading error carries no URL). `getConnection` takes the first
driver of `ServiceLoader.load(Driver.class, loader)` that accepts the URL and whose `connect`
answers a connection, else iterates `DriverManager.drivers()` the same way (drivers
registered with `DriverManager` and loadable by Bodoni's class loader: `DriverManager.drivers()`
filters every registered driver by its caller's class loader): not
`DriverManager.getConnection`, whose own "No suitable driver" (SQLState `08001`, full URL in
the message) cannot be told from a driver failure. A provider that fails to load is skipped.
No driver: Bodoni's own `SQLException`, SQLState `08001`, without cause, naming the URL
redacted (`JdbcParameters.redactedUrl()`: without the user information, found on the whole
URL and ending at the last `@` (`//user:password@` when a `//` precedes it, else the Oracle
form `user/password@` from after the last `:` before the first `/`, the `@` kept, so a
password may hold `:`, `/`, `@`, `;` or `?`), then cut at the first `;` or `?` of what
follows; when that `@` falls in a property value, whatever its key, cut where the user information would
start; it may cut more, never less). A driver failure is Bodoni's
own `SQLException`, also **without cause** (a driver exception may repeat the password
anywhere in its chain: H2 2.3.232 puts `s3cret@nohost.invalid` there for
`jdbc:h2:tcp://u:s3cret@nohost.invalid/db`), message
`JDBC driver <class> cannot connect to '<redactedUrl>': <driver message, scrubbed>`, the
driver's SQLState and vendor code kept. Scrubbing replaces the full URL, and the URL cut at its
first `;` or `?`, by the redacted one, then every known secret by `***`: the password field and
the password given to `getConnection(user, password)`, the password of the URL's user information, and the value
of every URL property whose key contains `password` or `pwd` whatever its case (`sslpassword`,
`trustStorePassword`, `PWD`; a `{…}` value with and without its braces). A `RuntimeException`
of a driver's `acceptsURL` or `connect` is handled the same way, SQLState `08001`. Known
limit: masking is literal, so a driver that quotes, escapes or encodes the secret in its
message is not masked; the Reference page repeats it. The module `uses java.sql.Driver` and
`requires transitive java.sql`. A pre-JDBC 4 driver without
`META-INF/services/java.sql.Driver`, in a class loader Bodoni cannot see, is not supported.

**Evidence (JDK 25, 2026-10-23)**, `JdbcDriverFactsTest` with H2 2.3.232, split-loader layout
`platform <- bodoni <- app (H2)`:

- `h2IsOnTheClassPathOfTheTestRun`: holds, H2 is in an unnamed module of the test run.
- `platformLoaderHandsBootLayerModulePackagesToTheAppLoader`: holds, a parent-first loader
  under the platform class loader never defines its own copy of a class of a boot-layer module
  package; the test loaders are child first.
- `driverManagerRefusesADriverItsCallerCannotSee`: holds, `DriverManager.getConnection` called
  from the `bodoni` loader refuses the H2 driver of `app` (even registered, even with `app` as
  the context loader): `SQLException`, SQLState `08001`, its message repeating the full URL.
- `serviceLoaderOnTheContextLoaderFindsTheDriver`: holds, `ServiceLoader.load(Driver.class, app)`
  called from `bodoni` yields `org.h2.Driver`, which connects.
- `connectAnswersNullForAForeignUrl`: holds, H2's `acceptsURL` is false and `connect` answers
  `null` for a URL it does not handle.
- `driverManagerSeesADriverOfItsOwnLoader`: holds, `DriverManager` connects when its caller's
  loader sees the driver.
- `namedModuleServiceLoaderFindsTheDriver`: fails with `ServiceConfigurationError` ("does not
  declare `uses`") until the module `uses java.sql.Driver`, then holds.

The rule it led to: `DriverManager` alone breaks a web application whose driver sits in the
WAR while Bodoni sits in the server's lib, so the context loader's `ServiceLoader` comes first
and `DriverManager` second, the same rule as Mansart's pool (mansart#42). No reflective
instantiation: the providers instantiate the drivers.

**Rulings of the B4 plan.**

| Topic | Ruling |
|---|---|
| H2 in `bodoni-core` unit tests | H2 2.3.232 at test scope rather than hand-written `ResultSet` / `ResultSetMetaData` doubles, which would be large and would not check real JDBC behaviour; small hand-written doubles stay where a double is the better test (`CountingDataSource`, `RecordingConnection`, `ManualDriver`) |
| `java.sql` | `requires transitive java.sql`: pages and application code hand `javax.sql.DataSource` objects to the actions and read `java.sql.Date` / `Time` / `Timestamp` values from `Result` rows; as for `java.xml` in B3, Bodoni does not rely on the API descriptor (open question 3) |
| `java.naming` | `requires java.naming`, not transitive: only `internal.DataSources` uses `javax.naming`, and no exported signature exposes a `javax.naming` type (the `dataSource` setters take `Object`) |
| Driver discovery | `uses java.sql.Driver`; context class loader `ServiceLoader` first, then `DriverManager.drivers()`, as stated above |
| No suitable driver | Bodoni's own `SQLException`, SQLState `08001`, **no cause**: `DriverManager`'s own message repeats the full URL, which may carry `;PASSWORD=` |
| Driver failures | Bodoni's own `SQLException`, **no cause**, the driver's message scrubbed, its SQLState and vendor code kept (above). `JdbcParameters.redactedUrl()` may hide more than the credentials, never less: an `@` in a URL path can shorten what a message shows |
| String resolution | JNDI first for every string, as §10.2 orders; any `NamingException` falls back to the JDBC form; a name bound to something that is not a `DataSource` fails; a blank string fails before any lookup; another type fails |
| `sql:setDataSource` | Resolves at once (JNDI lookup, or driver class loaded; no connection opened) and exports the resolved `DataSource`, as `var` or as `Config.SQL_DATA_SOURCE` in `scope`. `dataSource` wins over `url`; neither → `JspException`. With `url`, no JNDI lookup: `url`, `driver` (empty means none), `user` and `password` make the JDBC data source directly. A `DataSource` export never exposes the password as a string |
| `Result` | Own `internal.QueryResult`, not `ResultSupport.toResult`: `ResultSupport` has no `startRow`, and how it moves the cursor is not documented |
| Columns | Names from `ResultSetMetaData.getColumnLabel` (an `AS` alias wins); values from `getObject`; rows are unmodifiable `TreeMap`s ordered by `String.CASE_INSENSITIVE_ORDER` (Javadoc of `Result.getRows`); a duplicated label keeps the first column in the row map, `rowsByIndex` keeps every column; the three arrays and each `rowsByIndex` row are copied on every call, the cell values (a `byte[]`, a `java.util.Date`) are not |
| `maxRows` / `startRow` | `maxRows` attribute, else `Config.SQL_MAX_ROWS`, else `-1`. `Statement.setMaxRows(startRow + maxRows + 1)` only when `maxRows != -1` and the `long` sum is at most `Integer.MAX_VALUE`; `startRow` rows are skipped on the cursor; at most `maxRows` rows are kept, and one more row is read only to learn whether the limit cut the result. `limitedByMaxRows` is true when `maxRows != -1` and such an extra row exists: a result of exactly `maxRows` rows is **not** limited (controller ruling, 2026-10-23: the spec's "limited by a maximum row setting" read literally; tested both ways). `maxRows < -1` and `startRow < 0` → `JspTagException`, checked in `doStartTag` before any connection is opened |
| `maxRows` setting conversion (§2.8) | `Integer` / other `Number` → `intValue()`; `String` → `Integer.parseInt` of the trimmed text, blank → no limit; any other type, a non-integer string or a value below `-1` → `JspTagException` naming the setting and the value |
| Statement text | The `sql` attribute when set (a body then only runs the nested parameter actions, its text is ignored), else the trimmed body; `null` or blank → `JspTagException`. Passed to `Connection.prepareStatement` exactly as given, never concatenated with anything |
| `null` parameter | `setNull(index, type)`, `type` from `getParameterMetaData().getParameterType(index)`, `Types.NULL` when that call throws `SQLException` (§10.7) |
| Empty `sql:param` body | An evaluated but empty body binds `""`, not NULL: §10.7 makes NULL only of a `null` value (execution ruling, Task 5). Without `value` and without body content (`<sql:param/>`), the value is `null` |
| Dates, times and timestamps | `java.sql.Date`, `Time` and `Timestamp` parameters, from `sql:dateParam` or `sql:param`, are bound with `setDate` / `setTime` / `setTimestamp` and a fresh default-zone `Calendar`, which JDBC defines as equivalent to the plain setters; any other value with `setObject` (BOD-005, below) |
| Translation-time constraints | Checked at run time (no TLV, B1 precedent): `scope` without `var`, an empty `var`, a missing `var` on `sql:query`, and a `dataSource` attribute on `sql:query` / `sql:update` inside `sql:transaction` → `JspTagException` |
| `isolation`, `type` values | Exact lowercase as the spec lists them; an invalid `isolation` → `JspTagException` before any connection is opened, an invalid `type` → `JspTagException` before the value is handed to the enclosing action |
| `sql:transaction` | `doStartTag` refuses a connection whose isolation is `TRANSACTION_NONE`, then switches auto-commit off and sets the isolation only when it differs from the current one. `doCatch` rolls back only once auto-commit was switched off (rolling back a connection still in auto-commit means nothing; a rollback failure is added as suppressed) and rethrows. `doFinally` restores the isolation, then the auto-commit mode, then closes, each step in its own `try`; a failure there goes to `ServletContext.log(message, cause)`, never thrown; the connection is always closed |
| Exceptions | Data-source and SQL failures: `JspException`, cause kept when there is one: `<action>: dataSource is null`, `<action>: no data source: …`, `<action>: <resolution message>` (`SqlSupport.resolve`), `<action>: cannot get a connection from <source> to execute "<statement>": …` (`sql:query`, `sql:update`: §10.3 and §10.4 ask the message to include the statement) or `sql:transaction: cannot get a connection from <source>: …`, `<action>: cannot execute "<statement>": …`, `sql:transaction: cannot start the transaction: …`, `sql:transaction: commit failed: …`; a failure to close an owned connection: `<action>: cannot close the connection to <source> after "<statement>": …`, suppressed on the statement failure when both happen; attribute errors: `JspTagException` (§10.3–§10.6 "Null & Error Handling"). The no-password guarantee covers resolution and connecting; after that, a statement failure keeps the driver's exception as cause and the `sql:transaction` cleanup logs it through `ServletContext.log`, unscrubbed. `<source>` is the redacted string, `DriverManagerDataSource[<redacted URL>]` for a data source exported by `sql:setDataSource` with `url` or a JDBC string, else the class name of the `DataSource` object |
| JNDI in the IT | Not in `bodoni-it-ibarra`: unit tests cover it with the test `InitialContextFactory`. Foy has no naming implementation, and a host-side one would have to reach the driver of the application layer and prove less than the unit tests; Vidocq's naming extension (#297) brings its own IT |
| IT wiring | The host runs with `-Duser.timezone=Europe/Paris` (it guards BOD-005); H2, an automatic module (`com.h2database`), sits in the application layer, like a driver in `WEB-INF/lib`, and is bound through Bodoni's `uses java.sql.Driver`; the host's JDK modules gain `java.naming`; `fmt:parseDate` in `sql.jsp` takes `parseLocale="en"` because the IT client sends no `Accept-Language` |

**Bug found and fixed (BOD-005).** In a JVM whose default zone used local mean time before
1891 (`Europe/Paris`), H2 shifted a pre-1891 `java.sql.Date` or `Timestamp` bound with
`setObject` by the local-mean-time offset; Bodoni now binds those types with a default-zone
`Calendar`. Details in `BUG.md`.

**Open interpretations, to settle with the TCK (B5).** `startRow < 0` is an error
(`JspTagException`). A blank `jakarta.servlet.jsp.jstl.sql.maxRows` setting means no limit.
Whether the TCK expects either is unverified. What the TCK settled: "Decisions recorded in
B5", B3 and B4 interpretations.

## Decisions recorded in B5

Decided with the maintainer on 2026-10-23. Implementation plan:
`docs/superpowers/plans/2026-10-24-b5-tck.md`. Scope: the runner, one full run and
`bodoni-tck/BASELINE.md`; no change to `bodoni-core` or `bodoni-peano` production code and none
in another repository. Each failure is triaged, not fixed; the fixes follow in later pull
requests, each test-first, until 100 %.

| # | Decision |
|---|---|
| 1 | Open question 1: **`jakarta.tck:tags-tck:11.0.3`** from Central (the Tags tests of the Jakarta EE 11 Platform TCK). `jakarta-tags-tck-3.0.0.zip` (JavaTest/Ant) is the formal reference only. A result is a compatibility measurement, not a certification |
| 2 | Adapter: the published **`ibarra-arquillian`**, test scope; no copy, no test-jar |
| 3 | B5 merges with **the runner, a first full run and `bodoni-tck/BASELINE.md`** (classes, tests, failures, errors, skipped, per-class status, the triage of every failing class); fixes follow until 100 % |
| 4 | The **JSTL 1.0 `_rt` URIs** ship (four TLDs, same handlers) in a **later** Bodoni pull request; the baseline triages their tests as "known: _rt TLDs pending (decided 2026-10-23)" |
| 5 | **TLV** (revised 2026-10-23): Ibarra already runs TLD validators at page `init()` and on a tag file's first invocation, not at translation time (IBARRA-087, Vidocq/ibarra#62). Measure first: the baseline triages `etu/tlv/*` from the actual result. Outcome: `etu/tlv/permitted` passes 2/2; the 4 `etu/tlv/scrfree` failures use `core_rt` only; no test fails because validation runs at `init()` |

**Evidence gathered for the plan** (2026-10-23, on `tags-tck-11.0.3.jar` and the zip):

- **The suite**: 50 `JSTLClientIT` classes, 541 `@Test` methods (`javap -v`, none
  `@Disabled`), 579 JSPs. Its embedded POM brings Arquillian 1.9.4.Final, ShrinkWrap API 1.2.6,
  `jakarta.tck:common` 11.1.1, `commons-httpclient` 3.1, `ant` 1.10.15 and `junit-jupiter-api`
  5.11.4 (overridden by `vidocq-parent`'s JUnit 6.0.3, as in `ibarra-tck`).
- **Every WAR** carries the TCK common jar (`getCommonJarArchive()`), so the package
  `com.sun.ts.tests.jstl.common.resources` exists in all 50 and can be opened. Its
  `JstlDbFilter` and `SQLContextListener` get direct factories once Ibarra expands the WAR
  libraries.
- **JDK modules**: the host boot layer reaches `java.desktop`, `java.xml` and `java.net.http`;
  `java.sql`, `java.naming` and `jdk.localedata` must be added.
- **Database properties**: the `sql` and `compat` clients assert non-empty `jstl.db.url`,
  `jstl.db.user`, `jstl.db.password` and `jstl.db.driver`, and send them as request headers;
  without them the 155 tests of `sql/*` and `compat/onedotzero` fail in `@BeforeEach`. The
  JDBC-string pages join `url,driver,user,password` unescaped, so the URL holds no comma.
- **DDL**: the zip's `tags-tck/sql/<db>/<db>.ddl.jstl.sql` holds the schema and the rows; the
  jar's `tssql.stmt` is `derby.dml.jstl.sql` byte for byte plus a licence header, so the pages
  speak Derby SQL. An H2 2.3.232 probe loads the Derby DDL unchanged in the default mode (only
  the 3 `drop table` fail on a fresh database) and serves it across JVMs over TCP
  (`READ_COMMITTED`).
- **Zip**: 15,320,600 bytes, SHA-256
  `996c9abc6561134cea986107da0648c2a33277d2f765475994ff9b16da70cb5a` (matches the published
  `.sha256`), EFTL 1.0. The Central jar ships an EFTL 1.1 `LICENSE.md`; its POM declares EPL-2.0 /
  GPL-2.0 with Classpath Exception.
- **Time zone**: no TCK page prints a date in the server's default zone, all SQL dates are 1961
  or later (BOD-005 does not occur), and the forked host inherits `TZ`.
- **JSTL 1.0 URIs**: see open question 2.

**Rulings of the B5 plan** (as built):

| Topic | Ruling |
|---|---|
| DDL source | The script downloads the official zip (or takes `TAGS_TCK_ZIP`), checks the pinned SHA-256, extracts only `tags-tck/sql/derby/derby.ddl.jstl.sql` into `bodoni-tck/target/tags-tck-distribution/`; the runner reads it from `bodoni.tck.ddl`. No TCK file in Bodoni (EFTL) |
| Compatibility mode | None: plain H2 2.3.232 |
| Where the database lives | In memory in the test JVM (`DB_CLOSE_DELAY=-1`, a keeper connection), served by an H2 TCP server on an ephemeral port, bound to `127.0.0.1` (`h2.bindAddress`; no `-tcpAllowOthers`, no `-ifNotExists`); the WARs use `jdbc:h2:tcp://127.0.0.1:<port>/mem:jstl`, `jstl` / `jstl`, `org.h2.Driver` |
| Database lifecycle | Created and filled once per test JVM, as the formal TCK's single `initdb`; state carries from class to class; a single-class rerun starts fresh (in the baseline, every rerun of a failing class gave the same result) |
| Who starts it | `TckDatabaseListener` (`LauncherSessionListener`, `META-INF/services`), only when `bodoni.tck.ddl` is set (`tck-official` only), at most once; closed in a shutdown hook |
| Time zone | No pin; the baseline records the zone and locale of the run; `TZ=UTC ./run-official-tck-tags-3.0.sh -Dtest=…` checks a zone dependence |
| `ibarra.version` | In the root `<properties>` (`it-ibarra` and `tck` profiles); `foy.version` and `chappe.version` stay in `it-ibarra` |
| Arquillian alignment | `arquillian-bom` 1.9.4.Final imported in `bodoni-tck`'s own `dependencyManagement`, plus `arquillian-junit5-container`; ShrinkWrap stays 1.2.6; the root POM stays free of Arquillian |
| `--no-install` | Refuses (exit 2) a missing `bodoni-core` / `bodoni-peano` snapshot, or one older than its `src/main`, its `pom.xml` or the root `pom.xml` |
| `--no-prepare` | Refuses (exit 2) when the extracted DDL is absent |
| Reports | `target/surefire-reports` and `target/pages-deployments` deleted before Maven runs; no module `clean` (the distribution must survive) |
| Selections | `all` → `-Dtest=com.sun.ts.tests.jstl.**.*IT`; `smoke` → six whole classes at 100 % in the baseline (see "TCK plan") |
| CI | `.forgejo/workflows/tck-tags.yml`, `official-tags-tck-smoke`, on pull requests and pushes to `main` plus `workflow_dispatch`. Whether it becomes a required check is the maintainer's call |
| Upstream causes | Triaged in `BASELINE.md` with a proposed issue title; only Bodoni defects get `BUG.md` entries; B5 opens nothing in other repositories |

**Baseline** (2026-10-24, Temurin 25, macOS arm64, `Europe/Paris`, `fr_FR`): 50 classes, 541
tests, 452 passed, 0 failures, 89 errors, 0 skipped, **83.5 %**, 33/50 classes at 100 %, no
deployment error. Causes: `_rt` TLDs pending 40 (decision 4); Peano COMP-010 32 (a constant
expression lacked the `(ELContext, Class)` constructor that Ibarra's deferred-value attributes
call; fixed in Vidocq/peano#61 (merged 2026-10-24), Ibarra regression test IBARRA-156 on branch
`fix/deferred-constant-ctor`); Bodoni BOD-006 9 (translation-time
constraints), BOD-007 3 (`LocalizationContext` under `deny`), BOD-008 2 (`fmt:message bundle` and
the response locale), BOD-009 1 (`c:forTokens` deferred `items`); TCK input on JDK 20+ CLDR
locale data, two `parsedate` pages, 2.

**B3 and B4 interpretations against the TCK.** Each ruling stays a Bodoni ruling until the
maintainer decides otherwise.

| Interpretation | Settling TCK test | Baseline outcome |
|---|---|---|
| Empty `x:forEach` `select` throws (§12.6) (B3) | None: `positiveForEachSelectEmptyTest.jsp` is packaged in the `xforeach` WAR, but the 11.0.3 class has no method that requests it | Not exercised; `spec.xml.xconditional.xforeach` passes 9/9. Bodoni follows the spec text; the page's reading (no iteration) would be a change of interpretation, not a fix |
| Translation-time constraints checked at run time, no TLV (B1, B3, B4) | The `negative…` tests of `c:choose`, `x:choose`, `x:if`, `x:parse`, `fmt:formatDate`, `fmt:formatNumber` | Settled against Bodoni: 9 tests expect a translation error and get `200` (BOD-006). Other negative tests pass through a run-time `JspTagException` |
| `startRow < 0` is an error (B4) | None: the one `startRow` page uses `1` | Not exercised |
| A blank `maxRows` setting means no limit (B4) | None: the configuration pages set `invalid`, `-5`, `5` and an `Integer` | Not exercised |
| `limitedByMaxRows` is false for exactly `maxRows` rows (B4) | None: `positiveResultIsLimitedByMaxRowsTest` uses `-1` and `5` against 10 rows | Not exercised |

**Next steps** (each a later pull request, test first, `BASELINE.md` updated with the new
result):

1. The four JSTL 1.0 `_rt` TLDs (decision 4): 40 tests, which may then meet the other causes.
2. BOD-006 to BOD-009 (`BUG.md`): 15 tests.
3. Peano COMP-010: a Peano snapshot with the fixed in Vidocq/peano#61 (merged 2026-10-24) (32 tests);
   nothing to change in Bodoni.
4. `parseDate`: the two pre-CLDR `parsedate` pages, a TCK issue for the maintainer to file
   ("`positivePDVarTest` and `positivePDScopeTest` parse 'Nov 21, 2000 3:45:02 AM', which the
   JDK 20+ `en_US` default style no longer accepts (no Java20Plus variant)"); a challenge in a
   `TCK.md` if it is filed.
5. Open points for the maintainer: whether the JSTL 1.0 EL URIs ship (open question 2), the
   `x:forEach` empty-`select` ruling, and whether `official-tags-tck-smoke` becomes a required
   check.

## Cross-repo item: BOD-001 (fixed)

Under Peano with `peano.reflection=deny`, a precompiled page could not read `${s.index}` (or any
property) of the `varStatus` variable of `c:forEach` / `c:forTokens`, nor `${e.key}` /
`${e.value}` of the `Map.Entry` items of a `c:forEach` over a `Map`. Resolution, in two parts:
Peano (`Vidocq/peano`, branch `feat/accessor-supertype-lookup`) looks tables up through
superclasses and interfaces and ships the `Map.Entry` table; `bodoni-peano`, an optional engine
adapter (engine-adapter exception of the brick/extension rule; `bodoni-core` stays free of
Peano), ships the `LoopTagStatus` table (and, since B4, the `Result` table of `sql:query`). The Bodoni Vidocq runtime extension will declare
`bodoni-peano`. Tracked in `BUG.md` (BOD-001).
