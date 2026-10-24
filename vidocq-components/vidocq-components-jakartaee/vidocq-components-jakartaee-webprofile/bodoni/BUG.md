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

## BOD-006 · 2026-10-24 · Translation-time constraints are not enforced: `c:choose` / `x:choose` structure and `scope` without `var`

- Symptom: pages that the spec rejects run and answer HTTP 200. A `c:choose` (or `x:choose`)
  with no `when`, or with a `when` after the `otherwise`, renders its body; a `scope` without
  `var` on `x:if`, `x:parse`, `fmt:formatDate` and `fmt:formatNumber` is ignored (the action
  writes its result to the page). Tags 3.0 §2.7: "Constraints, as defined in the constraints
  section of each action, must also be reported at translation time"; §5.4 (`c:choose` "can
  only contain" white space, one or more `c:when` before an optional, last `c:otherwise`),
  §12.3, §9.7, §9.9 and §12.2 ("If scope is specified, var must also be specified"), §11.2
  (syntax `{var [scope] | varDom [scopeDom]}`). Found by the B5 baseline (official Tags TCK,
  9 tests).
- Minimal repro (official TCK, `jakarta.tck:tags-tck:11.0.3`, each `…JSTLClientIT`):
  `spec.core.conditional.cwo` and `spec.xml.xconditional.xcwo`
  `#negativeCWONoWhenActionsTest` (a `choose` holding only a comment) and
  `#negativeCWOWhenBeforeOtherwiseTest` (`otherwise` before `when`; the page prints
  "Body improperly processed."); `spec.xml.xconditional.xif#negativeIfScopeVarTest`
  (`<x:if select="$doc//a" scope="page"/>`); `spec.xml.xmlcore.parse#negativeParseScopeVarSyntaxTest`
  and `#negativeParseScopeVarDomSyntaxTest` (`<x:parse scope="page">`; the second client method
  requests the same page as the first); `spec.fmt.format.fmtdate#negativeFDScopeNoVarTest`
  (`<fmt:formatDate value='<%= new java.util.Date() %>' scope="page"/>` prints the date) and
  `spec.fmt.format.fmtnum#negativeFNScopeNoVarTest` (`<fmt:formatNumber value="1234" scope="page"/>`
  prints `1234`). Each expects HTTP 500 and gets 200. Run with
  `./run-official-tck-tags-3.0.sh -Dtest=<class>`.
- Cause: Bodoni's TLDs declare no `<validator>` and no `<tei-class>` (B1 decision, PLAN.md
  "Open interpretations"), and the handlers of these actions do not check the combination at
  run time either. Where a handler checks at run time (the `sql:` actions, `c:when` /
  `c:otherwise` outside `choose`, an invalid `scope`), the negative test passes through the
  run-time `JspTagException`. `fmt:parseDate` and `fmt:parseNumber` have the same gap: their
  scope-without-var tests pass only because the page sets no locale and the action fails with
  "no formatting locale". Ibarra runs TLD validators at page `init()` (IBARRA-087), so a
  `TagLibraryValidator`, or a run-time check in each handler, would turn the 9 tests into
  HTTP 500.
- Status: fixed (2026-10-24, branch `fix/tck-baseline`), by tag library validators rather than run-time checks
  (PLAN.md, "Decisions recorded in the TCK fixes", Ruling 4): a run-time check cannot report a `c:when` after
  `c:otherwise` before the `otherwise` body is written, cannot see template text in a `choose`, and misses
  branches that do not run. Every `c`, `fmt`, `sql` and `x` TLD (3.0, `-1_2`, `-1_0-rt`) names a public validator
  of its handler package (`CoreTagLibraryValidator`, `FmtTagLibraryValidator`, `SqlTagLibraryValidator`,
  `XmlTagLibraryValidator`) over the shared `internal.PageConstraints`, a SAX walk of the XML view: `choose`
  structure, `when` / `otherwise` parent, `scope` without `var`, `x:parse` `var`/`varDom`/`scopeDom`, `dataSource`
  under `sql:transaction`, a literal invalid `scope` and an empty `var` (§2.7). Ibarra runs them at page `init()`
  (IBARRA-087). Unit tests: `TagLibraryValidatorsTest`. TCK: `cwo` 7/9 → 9/9, `xcwo` 6/8 → 8/8, `xif` 6/7 → 7/7,
  `parse` 13/15 → 15/15, `fmtdate` 13/14 → 14/14, `fmtnum` 18/19 → 19/19; each of the 9 pages now fails with
  "TagLibraryValidator validation failed for jakarta.tags.…". `parsedate#negativePDScopeNoVarTest` and
  `parsenum#negativePNScopeNoVarTest` now fail through the validator. The run-time checks stay as defence in depth.

## BOD-007 · 2026-10-24 · `LocalizationContext` properties are unreadable in Ibarra pages with Peano reflection denied

- Symptom: under `-Dpeano.reflection=deny`, a page that reads `${ctx.resourceBundle}` (or
  `${ctx.locale}`) of a `LocalizationContext` exported by `fmt:setBundle` answers HTTP 500:
  `jakarta.el.ELException: Reflection fallback denied for jakarta.servlet.jsp.jstl.fmt.LocalizationContext; register a generated accessor provider or a custom resolver`.
  Found by the B5 baseline (official Tags TCK, 3 tests); same family as BOD-001.
- Minimal repro:
  ```jsp
  <%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
  <fmt:setBundle basename="Resources" var="b"/>${b.resourceBundle == null}
  ```
  Official TCK: `spec.fmt.i18n.setbundle.JSTLClientIT#positiveSetBundleFallbackLocaleTest`,
  `#positiveSetBundleLocaleConfigurationTest` and `#positiveSetBundleBasenameNullEmptyTest`.
- Cause: `bodoni-peano` registers accessor tables for `LoopTagStatus` and `Result` only
  (BOD-001); `jakarta.servlet.jsp.jstl.fmt.LocalizationContext` (Tags 3.0 §8.1.1, properties
  `resourceBundle` and `locale`) has none, so Peano falls back to reflection, which `deny`
  refuses. Not a handler defect (the `allow` mode was not run by the B5 baseline).
- Status: open — found by the B5 baseline; fix in a follow-up PR, test-first (a
  `LocalizationContext` table in `bodoni-peano`).

## BOD-008 · 2026-10-24 · `fmt:message` with a `bundle` attribute does not set the response locale

- Symptom: `<fmt:message key="k" bundle="${ctx}"/>` prints the right message, but never calls
  `ServletResponse.setLocale` with the locale of `ctx`, so the response locale and the
  `jakarta.servlet.jsp.jstl.fmt.request.charset` session attribute are not set. Tags 3.0 §8.4:
  "Any i18n action that establishes a localization context is responsible for setting the
  response's locale of its page", including "Any <fmt:message> action that establishes an i18n
  localization context". Found by the B5 baseline (official Tags TCK, 2 tests).
- Minimal repro: official TCK `spec.fmt.i18n.responseencoding.JSTLClientIT`
  `#positiveResponseEncodingTest` and `#positiveResponseSetCharEncodingAttrTest`, fourth request
  (`?action=message`): the page passes the `LocalizationContext` that an earlier `fmt:setBundle`
  stored in application scope to `fmt:message bundle='<%= … %>'`. The response body is right
  (`en message`), but the expected `setlocale: en` header (respectively `charset: attribute set`)
  is missing. The `locale`, `bundle` and `setbundle` requests of the same tests pass.
- Cause: `MessageTag.resolve()` takes the `bundle` attribute's context as is; only the enclosing
  `fmt:bundle` and the default-context path (`FmtSupport.defaultContext`) call
  `I18n.setResponseLocale`.
- Status: open — found by the B5 baseline; fix in a follow-up PR, test-first.

## BOD-009 · 2026-10-24 · `c:forTokens` does not accept a deferred `items`

- Symptom: `<c:forTokens items="#{golf}" delims="[]" var="item">` fails to translate:
  `Page translation failed: /positiveForTokensDeferredValueTest.jsp:29:4: Deferred action attributes require tag-handler support`.
  Tags 3.0 §6.3 "Deferred Values": "See Section "Deferred Values" for <c:forEach>. Same comments
  apply here" (§6.2: a deferred `items` makes the iteration variable available as a deferred
  value through the `VariableMapper`). Found by the B5 baseline (official Tags TCK, 1 test).
- Minimal repro: official TCK
  `spec.core.iteration.fortokens.JSTLClientIT#positiveForTokensDeferredValueTest`
  (`<c:set var="golf" value="#{'[Tiger Woods][Robert Trent Jones][Pebble Beach]'}"/>`, then
  `c:forTokens items="#{golf}"` with `<tck:save attr="#{item}"/>` in the body).
- Cause: Bodoni's `c.tld` declares `c:forTokens` `items` as `rtexprvalue` of type
  `java.lang.String` without `<deferred-value>` (`c:forEach` `items` and `c:set` `value` have it),
  and `ForTokensTag.setItems(String)` takes no `ValueExpression`, so Ibarra rejects the `#{…}`
  value at translation time.
- Status: fixed (2026-10-24, branch `fix/tck-baseline`): `items` is `deferred-value` `java.lang.String` in `c.tld` and `c-1_2.tld`; `ForTokensTag.setItems(Object)`. A non-String value is coerced as EL coerces to `String`, a null one gives no iteration. TCK `spec.core.iteration.fortokens`: 14/15 before, 15/15 after.
