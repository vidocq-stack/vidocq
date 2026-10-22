# Bodoni — Design plan

> Status: **B0 scaffold**. This document is the design of record for Bodoni, the
> Jakarta Standard Tag Library 3.0 (JSTL) implementation of the Vidocq ecosystem,
> required by Jakarta EE Web Profile 11 and tracked by
> [Vidocq/vidocq#266](https://codefloe.com/Vidocq/vidocq/issues/266) (see
> `vidocq/WEB-PROFILE.md`). Only the reactor and the `bodoni-core` module descriptor
> exist; no tag is implemented.
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
  Servlet and EL) and JDK modules (`java.xml`, `java.sql`, `java.net.http`) only — no
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

Out of scope until the open questions below are settled: the pre-3.0 URIs
(`http://java.sun.com/jsp/jstl/core`, `…/jstl/core_rt`, 1.0 EL TLDs). The GlassFish/WildFly
implementation (`org.glassfish.web:jakarta.servlet.jsp.jstl:3.0.1-jbossorg-1`, inspected
2026-10-22) still ships them alongside the `jakarta.tags.*` TLDs; spec Appendix B.1 only
records the rename (jstl-api#144). Whether the TCK exercises the old URIs is unverified.

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
| `bodoni-core` | `io.vidocq.bodoni.core` | runtime | `transitive jakarta.servlet.jsp.jstl` (brings `jakarta.servlet.jsp`, `jakarta.servlet`, `jakarta.el`, `java.sql`, `java.desktop` → `java.xml`); later `java.net.http` if `c:import` uses it, `java.naming` for `sql:` | the tag-handler and function packages (unqualified: generated pages and other containers instantiate handlers); `…core.internal.*` not exported |
| `bodoni-ibarra` (planned) | `io.vidocq.bodoni.ibarra` | build only | `io.vidocq.bodoni.core`, Ibarra's compiler-side SPI module | nothing — `provides` the `TagInliner` SPI |
| `bodoni-tck` (planned) | — | test, `-Ptck` | Ibarra + Peano + Foy (test scope), official TCK | — |
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
2. a **JDBC parameter string** `url[,[driver][,[user][,password]]]` (`\` escapes `,`) for
   `DriverManager`.

Bodoni does exactly what any implementation does on any server: `new InitialContext()`
and `lookup("java:comp/env/" + path)`; on lookup failure, fall back to the JDBC parameter
form; `DriverManager` for the latter (the `driver` attribute loads a class by name: the
one reflective act the spec mandates, recorded in the Reference page). Bodoni **does not
provide** a naming implementation.

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
default context node and the `x:forEach` context. Secure-processing defaults (external
entities, `systemId` resolution via `ServletContext.getResource`, spec §11.1.7) need a
decision on XXE hardening vs TCK expectations (open question 6).

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
context class loader's unnamed module for WARs?) is unverified (open question 4). No
`opens` may be required from applications without a documented reason.

### Reflection, threads

No reflection beyond what the EL implementation already does when evaluating attribute
expressions (Peano under Vidocq, any EL elsewhere) and the spec-mandated JDBC `driver`
class loading. No `synchronized`, no `ThreadLocal` (virtual threads): handler state is
per instance (the Pages container may pool classic handlers; Ibarra does not), locale /
bundle / time-zone state lives in scoped attributes via `Config`.

## TCK plan

- Runner: `bodoni-tck`, **in the reactor only under `-Ptck`** (same wiring as
  `peano-tck` / `ibarra-tck`); the official suite resolves only under
  `-Ptck,tck-official`. Script `run-official-tck-tags-3.0.sh` (`smoke` / `all` /
  `-Dtest=`), modelled on `run-official-tck-pages-4.0.sh` (Ibarra) and
  `run-official-tck-el-6.0.sh` (Peano).
- The suite deploys WARs with JSP pages, so it needs a Pages container: Ibarra + Foy +
  Peano behind the Arquillian adapter Ibarra already built for the Pages TCK
  (`ibarra-tck`), at **test scope only**. How to reuse that adapter without copying it
  (published test-jar? shared module?) is a cross-repo item.
- **Which artefact (open question 1).** Two candidates, both seen 2026-10-22:
  - `jakarta-tags-tck-3.0.0.zip` on <https://download.eclipse.org/jakartaee/tags/3.0/>,
    the TCK linked from the Tags 3.0 spec page (Jakarta EE 10 era; GlassFish 7.0.0-M4
    certification summary reports 542 tests). Harness not inspected.
  - `jakarta.tck:tags-tck` 11.0.0–11.0.3 on Maven Central: "Jakarta Tags TCK", Arquillian
    JUnit 5, depending on `jakarta.servlet.jsp.jstl-api` 3.0.2, Pages 4.0.0, Servlet 6.1.0,
    EL 6.0.1 and `jakarta.tck:common` 11.1.1 (POM read 2026-10-22) — the EE 11 levels.
  Which one a Web Profile 11 compatibility claim must cite is unverified.
- Gate: 100 % PASS is the merge gate once green; challenges, if filed, go to a `TCK.md`.

## Milestones (TDD — test first, every milestone)

| # | Content | Done when |
|---|---|---|
| B0 | Scaffold: reactor, `bodoni-core` module descriptor, docs, this plan | `./mvnw -ntp clean install` green (needs the first commit, or `-Dmaven.gitcommitid.skip=true`) |
| B1 | `fn:` functions + TLD; `c:` core (§4–§7) handlers + TLD, `Config`, scoped-variable rules (§2.2) | unit tests per tag on `PageContext` doubles; pages render on Ibarra in an IT |
| B2 | `fmt:` i18n and formatting (§8–§9): locale negotiation, bundles, time zones, number/date formatting and parsing | unit tests; open question 4 settled |
| B3 | `x:` (§11–§13): JDK parser/XSLT/XPath, scope variable resolver, `x:forEach` context | unit tests; open question 6 settled |
| B4 | `sql:` (§10): data-source resolution (object / JNDI / JDBC string), query, update, transaction, params, `Result` | unit tests on an in-memory `DataSource` double; JNDI path tested with a test-only `InitialContextFactory` |
| B5 | `bodoni-tck` + `run-official-tck-tags-3.0.sh`, behind `-Ptck` | official suite green with standard handlers (open question 1 settled) |
| B6 | Ibarra `TagInliner` SPI (Ibarra PR) + `bodoni-ibarra` inliners for the eight core tags | differential tests vs handlers; TCK green with inlining on; any speed claim in `BENCH.md` |
| B7 | `bodoni-it-other-containers/bodoni-it-openliberty` + Reference "Deploying on an application server" block | WAR with the documented dependencies passes on Open Liberty |

The Vidocq runtime extension (vidocq repository) follows B5.

## Open questions

1. **TCK artefact** — `jakarta-tags-tck-3.0.0.zip` (spec page, EE 10) or
   `jakarta.tck:tags-tck:11.0.x` (Maven Central, EE 11 levels)? Which is authoritative for
   a Web Profile 11 claim, and which harness does each use?
2. **Legacy URIs** — ship TLDs for the pre-3.0 URIs (`http://java.sun.com/jsp/jstl/*`,
   `_rt`, 1.0) as the GlassFish implementation does, or `jakarta.tags.*` only? Depends on
   what the TCK and migration needs require. (to validate)
3. **`java.desktop` via the API module** — accept that the upstream descriptor
   `requires transitive java.desktop` (apparently for `java.xml`), or report it upstream
   (jstl-api) and ask for `requires transitive java.xml` instead? Effect on jlink images
   and on Peano's "no `java.desktop`" stance to be assessed.
4. **Resource bundles under Java Modules** — which `Module` / `ClassLoader` `fmt:` uses to
   find application bundles in a named application module and in a WAR, without
   requiring `opens`.
5. **Open Liberty precedence** — `pages-4.0` exposes the JSTL API and presumably its own
   implementation for the `jakarta.tags.*` URIs; does a WAR-bundled Bodoni win (Pages
   allows a container to provide alternate implementations for well-known URIs)? What does
   the WARNING block tell users to exclude? To be settled by `bodoni-it-openliberty`.
6. **XML hardening** — secure-processing / external-entity defaults for `x:parse` and
   `x:transform` vs spec §11.1.7 and TCK expectations.
7. ~~**JNDI `java:comp` under Vidocq**~~ — decided: `vidocq-runtime-naming-extension`,
   [vidocq#297](https://codefloe.com/Vidocq/vidocq/issues/297) (see "`sql:` — DataSource
   resolution"). Not a Bodoni design item.
8. **Module split** — one `bodoni-core` jar (all five libraries, like the reference
   implementation) or separate `bodoni-sql` / `bodoni-xml` artefacts? The API module
   already reads `java.sql` and `java.xml`, so a split would not shrink the module graph.
   Default: one jar. (to validate)
9. **`TagInliner` SPI shape** — owned by Ibarra; to be designed there (cross-repo item).
