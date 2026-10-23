# BUG.md — Bodoni

Reproducible bugs (internal issue, regression, incorrect behavior not yet fixed).
Entry format: `short id · date · symptom · minimal repro · cause hypothesis · status`.

## BOD-001 · 2026-10-22 · `varStatus` and `Map.Entry` properties are unreadable in Ibarra pages with Peano reflection denied

- Symptom: a page precompiled by Ibarra and run on Foy with `-Dpeano.reflection=deny`
  answers HTTP 500 as soon as it reads a property of the `c:forEach` (or `c:forTokens`)
  status variable:
  `jakarta.el.ELException: Reflection fallback denied for jakarta.servlet.jsp.jstl.core.LoopTagSupport$1Status; register a generated accessor provider or a custom resolver`
  (thrown by `io.vidocq.peano.core.internal.evaluation.ReflectionGuard`). Extended
  2026-10-22 (B1 final review): iterating a `Map` fails the same way as soon as the page
  reads `${e.key}` or `${e.value}` of the `Map.Entry` item (Tags 3.0 §6.1.2):
  `Reflection fallback denied for java.util.Collections$UnmodifiableMap$UnmodifiableEntrySet$UnmodifiableEntry`
  for `items="${param}"`. The same pages
  render correctly with `peano.reflection=allow`, under both the `jakarta.tags.*` and the
  `http://java.sun.com/jsp/jstl/*` URIs: TLD discovery, handler construction, functions
  and the tag lifecycle work.
- Minimal repro:
  ```jsp
  <%@ taglib prefix="c" uri="jakarta.tags.core" %>
  <c:forEach items="${['a','b']}" var="x" varStatus="s">${s.index}:${x}</c:forEach>
  ```
  ```jsp
  <c:forEach items="${param}" var="e">${e.key}=${e.value};</c:forEach>
  ```
  In `bodoni-it-ibarra`, `status.jsp` with `CorePagesIT.forEachExposesVarStatusProperties`
  and `entries.jsp` with `CorePagesIT.forEachExposesMapEntryKeyAndValue` (both disabled
  with a reference to this entry until the fix; both pass when the host runs with `allow`).
  `CorePagesIT.denyModeIsActive` (enabled) proves the host really runs under `deny`.
  Every other check of `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify` runs
  under `deny` and passes.
- Cause hypothesis: the status object is the anonymous `LoopTagStatus` implementation
  of the JSTL API's `LoopTagSupport` (Tags 3.0 §6.1, `varStatus` of type
  `jakarta.servlet.jsp.jstl.core.LoopTagStatus`), and the `Map.Entry` items are the JDK's
  entry classes. Peano resolves bean properties without reflection only through registered
  `AccessorProvider` tables; nothing registers one for `LoopTagStatus` or `Map.Entry`. `bodoni-core` must not depend on Peano (AGENTS.md constraint 1). Not a
  Bodoni handler defect.
- Candidate owners (the maintainer decides): (1) Peano, with built-in accessors for
  Jakarta API types such as `LoopTagStatus` and JDK types such as `Map.Entry`; (2) the Ibarra precompiler; (3) an optional
  Bodoni adapter module depending on Peano (engine-adapter exception of the brick/extension
  rule) that registers the `LoopTagStatus` and `Map.Entry` accessor tables; (4) the Bodoni Vidocq runtime
  extension. Cross-repo item in `tasks/todo.md` and `PLAN.md`.
- Status: fixed (2026-10-22), in two parts. (1) `Vidocq/peano`, branch
  `feat/accessor-supertype-lookup`: Peano resolves an accessor table by the runtime class, then
  its superclasses, then its interfaces, and ships a built-in `java.util.Map.Entry` table
  (`${e.key}`, `${e.value}`). (2) `bodoni-peano`, the optional engine adapter (engine-adapter
  exception of the brick/extension rule; `bodoni-core` stays free of Peano): it provides the
  read-only `LoopTagStatus` table (`current`, `index`, `count`, `first`, `last`, `begin`, `end`,
  `step`) through an `AccessorProvider`. `CorePagesIT.forEachExposesVarStatusProperties` and
  `forEachExposesMapEntryKeyAndValue` are enabled and pass under `deny`.

## BOD-002 · 2026-10-22 · `fmt:message` finds no bundle of the application module: the request thread's context class loader is the system class loader

- Symptom: on the `bodoni-it-ibarra` host (Foy over Chappe, application as a named module in its
  own layer), `fmt:setBundle basename="io.vidocq.bodoni.it.ibarra.i18n.Labels" var="labels"`
  then `fmt:message key="greeting" bundle="${labels}"` renders `???greeting???` (no
  exception). The same for `fmt:bundle basename="it-bundles.Plain"`. The bundle exists, its
  package is `opens`ed, and `Module.getResourceAsStream("it-bundles/Plain.properties")` from the
  page finds it.
- Minimal repro: `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify` with the two
  `@Disabled` tests of `CorePagesIT` (`fmtMessagesResolveBundlesOfTheApplicationModule`,
  `fmtLegacyUriResolvesAMessage`) enabled. Diagnosis page (not kept): a scriptlet printing
  `Thread.currentThread().getContextClassLoader()` answers
  `jdk.internal.loader.ClassLoaders$AppClassLoader`, while `getClass().getClassLoader()` of the
  precompiled page is `jdk.internal.loader.Loader` (the layer loader defining the application module).
- Cause hypothesis: Foy sets the application loader as the context class loader only around
  WebSocket callbacks (`WebConnectionImpl`, `CallbackSerializer`), not while it invokes a servlet
  or a precompiled page; Ibarra sets it only around EL context creation. Bodoni's bundle lookup
  uses the thread context class loader (maintainer decision, PLAN.md open question 4), like
  `LocaleSupport` of the JSTL API. Not a Bodoni defect. Experiment: with the host launcher
  setting the context class loader of its main thread to the application loader (inherited by
  Chappe's virtual threads), every fmt IT passes unchanged.
- Candidate owners: Foy (set the context class loader to the application loader for the
  duration of a request, as Servlet containers do) or the host launcher.
- Status: fixed (2026-10-22). Fixed in Vidocq/foy (Foy BUG-20261022-06, [Vidocq/foy#76](https://codefloe.com/Vidocq/foy/pulls/76), merged 2026-10-23). Foy builds that
  predate it leave the request thread's context class loader unset, so `fmt:` cannot find
  application bundles; the `it-ibarra` ITs need a Foy snapshot that contains the fix. With it,
  requests run with the application class loader as context class loader and the bundle ITs
  (one per placement) pass without any change to the host or to Bodoni.

## BOD-003 · 2026-10-23 · `position()` and `last()` in the `select` of a nested `x:` action see a context of one node

- Symptom: inside `x:forEach`, `position()` and `last()` at the top level of a nested `select`
  answer `1` and `1` on every iteration, instead of the position and the size of the node-set
  being iterated (Tags 3.0 section 12.6).
- Minimal repro: `<x:parse var="d"><r><b/><b/><b/></r></x:parse>` then
  `<x:forEach select="$d//b"><x:out select="position()"/></x:forEach>` prints `1` three times
  (expected `1`, `2`, `3`).
- Cause: Bodoni evaluates the nested `select` with `javax.xml.xpath`, whose API takes a context
  node but cannot set the context position and size. Bodoni cannot provide them without leaving
  the JDK XPath engine (no Xalan, no third-party library).
- Workaround: read the position from `varStatus` (`count`, `last`), for example
  `<x:forEach select="$d//b" varStatus="s"><c:out value="${s.count}"/></x:forEach>`.
- Status: open (limitation, documented in the Reference page).

## BOD-004 · 2026-10-23 · `c:if` accepts an unknown `scope` and exports its result in page scope

- Symptom: `c:if` with `var` and an unknown `scope` renders its body and stores the test
  result in page scope, where `c:set` with the same `scope` fails with
  `JspTagException: c:set: Invalid scope: invalid` (Tags 3.0 §5.3: `scope` is one of `page`,
  `request`, `session`, `application`). Found by the B3 final review (B1 handler).
- Minimal repro:
  ```jsp
  <%@ taglib prefix="c" uri="jakarta.tags.core" %>
  <c:if test="${true}" var="r" scope="invalid">body</c:if>${r}
  ```
  prints `bodytrue` instead of failing. Unit test:
  `IfTagTest.anInvalidScopeIsATagException` (`tags.core`).
- Cause: the scope name is handled by the JSTL API's `ConditionalTagSupport`, which does
  not validate it (observed: an unknown name exports in page scope); Bodoni's `c:if` did not
  check the name itself. `x:if` was fixed the same way in B3; `c:when` and `x:when` have
  no `scope` attribute.
- Status: fixed (2026-10-23, branch `feat/b3-xml`). `c:if` keeps the name it is given and
  checks it when the action runs: an unknown name is a `JspTagException`
  (`c:if: Invalid scope: invalid`, cause kept) and nothing is exported.

## BOD-005 · 2026-10-23 · `sql:dateParam` binds the day before for a date before 1891 in Europe/Paris

- Symptom: in a JVM whose default zone used local mean time before 1891 (for example
  `Europe/Paris`), a `sql:dateParam` date or timestamp before 1891 bound through H2 shifts
  by the local-mean-time offset: `1788-01-01` reaches the database as `1787-12-31`, and
  `1788-01-01 12:00` as `11:09:21`, so the query matches no row (Tags 3.0 §10.8).
  Found by the B4 Task 8 review.
- Minimal repro (`-Duser.timezone=Europe/Paris`, H2 2.3.232):
  ```jsp
  <fmt:parseDate var="d" value="1788-01-01" pattern="yyyy-MM-dd" parseLocale="en"/>
  <sql:query var="r">SELECT title FROM book WHERE published = ?<sql:dateParam value="${d}" type="date"/></sql:query>
  ```
  finds nothing for a row published `DATE '1788-01-01'`. Unit test: `DateParamZoneTest`
  (`tags.sql`, its own surefire execution `europe-paris-zone`, a JVM started in
  `Europe/Paris`: switching the default zone in a shared JVM leaks into later tests, as H2
  caches the zone it first sees); IT: `CorePagesIT.sqlDateParamBindsADate`, host in `Europe/Paris`.
- Cause: `fmt:parseDate` and `sql:dateParam` build the right instant from the legacy
  `java.util` zone data (CET, +1:00). H2's `setObject` / `setDate` without a `Calendar` convert
  the `java.sql.Date` / `Timestamp` millis with `java.time` zone rules (Paris local mean time,
  +0:09:21), which disagree with the legacy rules before 1891.
- Status: fixed in Bodoni (2026-10-23, branch `feat/b4-sql`, B4). `sql:param` and
  `sql:dateParam` bind `java.sql.Date`, `Time` and `Timestamp` values with
  `setDate` / `setTime` / `setTimestamp` and a fresh default-zone `Calendar`, which JDBC
  defines as equivalent to the plain setter. Other values keep `setObject`.
