# Bodoni Tags TCK baseline fixes — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recover the 57 Bodoni-owned errors of the first official Jakarta Tags TCK run (452/541), so the suite reaches 508/541 on its own and 541/541 once Peano COMP-010 (Vidocq/peano#61) is in the snapshot.

**Architecture:** Seven tasks, grouped by cause, each test-first in `bodoni-core` or `bodoni-peano` and each closed by a rerun of the affected official classes. The JSTL 1.0 request-time URIs get four TLDs mapped to the existing handlers. The translation-time constraints (BOD-006) get one `TagLibraryValidator` per library over a shared internal SAX checker. Ibarra runs validators at page `init()`, before any output. The other fixes are local: a TLD attribute and a handler setter (BOD-009), a Peano accessor table (BOD-007), one `setResponseLocale` call (BOD-008), and the `fmt:parseDate` parse rule (BOD-010, BOD-011).

**Tech Stack:** Java 25, Maven 3.9.16, Jakarta Tags API 3.0.2 (`jakarta.servlet.jsp.jstl`), Pages 4.0 / EL 6.0 / Servlet 6.1 APIs (`provided`), `java.xml` (SAX), Peano SPI (`peano-api`, `bodoni-peano` only), JUnit 6, official suite `jakarta.tck:tags-tck:11.0.3` on Ibarra + Foy + Peano.

**Spec:** `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-workspace/90b9f66f-b932-420f-992b-d61241321759/scratchpad/tck-fixes-plan-brief.md` (requirements and maintainer decisions) and the Tags 3.0 text `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-workspace/90b9f66f-b932-420f-992b-d61241321759/scratchpad/spec/spec.html.txt`. Evidence: `bodoni-tck/BASELINE.md`, `BUG.md` BOD-006..009, `PLAN.md`. TCK pages, for expected behaviour only (read them, never copy them): `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq-workspace/90b9f66f-b932-420f-992b-d61241321759/scratchpad/tck-research/central/x/com/sun/ts/tests/jstl/`.

## Global Constraints

- Java 25 (Temurin) + Maven 3.9.16: `sdk env` in the worktree before any build.
- **Precondition (before Task 1):** rebase `fix/tck-baseline` on the current `feat/b5-tck` head (at least `6aa4a73`, the B5 docs commit: `tck.adoc`, `reference.adoc`, `usage.adoc`, `PLAN.md`), or on `origin/main` once B5 has merged. Every docs edit below assumes the B5 Task 6 pages. This plan file is untracked: commit it with Task 1.
- Clean room: the Tags 3.0 spec, the API Javadoc and the API signatures (`javap -p`) only. Never GlassFish or Apache JSTL sources, never API method bodies (no `javap -c`). TCK pages and `.gf` files say what is expected; never copy them into Bodoni.
- `bodoni-core` depends on the JSTL API only (Pages, Servlet and EL APIs `provided`); `bodoni-peano` depends on `peano-api` and the JSTL API only, never on `bodoni-core`.
- No reflection (beyond the spec-mandated JDBC driver load), no `setAccessible`, no `opens`, no `synchronized`, no `ThreadLocal`; XML through `java.xml` only (`internal.XmlFactories`).
- JUnit 6, no Mockito: the hand-written doubles of `bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/`.
- Licence header `etc/license-header.txt` on every new `.java` file.
- English in code, Javadoc, comments, commits and Markdown; say "Java Modules", never "JPMS".
- Commits: `git commit -S -s`, Conventional Commits in English, citing the spec section and the BOD id, ending with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01JrpAgoGhwva5krc8qVqBqh
  ```
  No push from a task: the branch goes out as one PR after Task 7.
- TCK runs: `./run-official-tck-tags-3.0.sh -Dtest=<FQCN>[,<FQCN>…] --no-prepare` (classes are `com.sun.ts.tests.jstl.<…>.JSTLClientIT`). The first run in this worktree omits `--no-prepare` and sets `TAGS_TCK_ZIP=/Users/yblazart/projects/perso/vidocq-workspace/bodoni/feat/b5-tck/bodoni-tck/target/tags-tck-distribution/jakarta-tags-tck-3.0.0.zip`, which avoids the download (the script checks the pinned SHA-256). A run installs `bodoni-core` / `bodoni-peano` `0.4.0-SNAPSHOT` into `~/.m2`, which is shared with the sibling worktrees: never run it while another worktree builds or runs the Bodoni TCK.
- The TCK client checks only the HTTP status of a negative test (`status-code 500`). The unit tests carry the messages and the semantics.
- Every new defect a rerun reveals is fixed in this PR, with its own `BUG.md` entry (next id after BOD-011), before Task 7: no follow-up list.

## Review Focus

- **Attributes given through `<jsp:attribute>`** (`<c:if test="…" scope="page"><jsp:attribute name="var">v</jsp:attribute>…`): the validator must count `var` as present. Test `jspAttributeCountsAsAPresentAttribute` (Task 3).
- **Two URIs of one library on a page** (`c` + `c_rt`, or `jakarta.tags.core` + the JSTL 1.2 URI), with a `c_rt:when` inside a `c:choose`: the handlers accept this at run time, so the validators accept it too. Test `whenOfAnotherUriOfTheSameLibraryIsAccepted` (Task 3).
- **White space, `jsp:body` and JSP comments inside `choose`**: white-space-only `jsp:text`, and `when`s wrapped in a `jsp:body`, are valid. Template text, an HTML comment or a scriptlet is not (§5.4). Tests `whiteSpaceAndJspBodyInsideChooseAreAccepted`, `templateTextInsideChooseIsReported` (Task 3).
- **A value that the retry cannot parse either** still throws a `JspException` that names the value, with the *first* `ParseException` as cause: the TCK page `negativePDUnableToParseValueTest` checks the root cause. Test `failedRetryKeepsTheFirstParseExceptionAsCause` (Task 6).
- **Deferred `c:forTokens` `items` that are not a `String`, or are `null`**: coerced as EL coerces to `String` (`Enum` → `name()`, other → `toString()`), and `null` gives no iteration. Tests `nonStringItemsAreCoercedToString`, `nullDeferredItemsIterateZeroTimes` (Task 1).

## Rulings this plan applies (with evidence)

1. **`_rt` TLD names and versions.** `META-INF/c-1_0-rt.tld`, `fmt-1_0-rt.tld`, `sql-1_0-rt.tld`, `x-1_0-rt.tld`, with URIs `http://java.sun.com/jstl/core_rt`, `…/fmt_rt`, `…/sql_rt`, `…/xml_rt` and short names `c_rt`, `fmt_rt`, `sql_rt`, `x_rt` (the prefixes the TCK pages use). The schema is the same as the other TLDs: `xmlns="https://jakarta.ee/xml/ns/jakartaee"`, `web-jsptaglibrary_3_0.xsd`, `version="3.0"`, with no `<jsp-version>` (a 2.x+ schema has none) and `<tlib-version>3.0</tlib-version>`. They follow the `*-1_2.tld` precedent: the descriptor states what is served (Bodoni's 3.0 handlers), and the URI alone marks the legacy contract. Tags, handler classes, body content and attribute names are those of the 3.0 TLD, and so are `required` and `rtexprvalue` per attribute (the 3.0 static attributes, such as `var`, `scope` and the XPath `select`, were static in 1.0 too). There is no `<deferred-value>`: JSTL 1.0 predates deferred expressions. An attribute that is deferred in 3.0 gets `<type>java.lang.Object</type>`, the type of its setter. The 3.0 attribute set is a superset of every attribute the 1.0 pages use. Evidence: every `c_rt`/`fmt_rt`/`sql_rt`/`x_rt` attribute in the suite is in it, including `x_rt:parse xml` and `x_rt:transform xml` (deprecated in 3.0, footnotes 10–13 of the spec). JSTL 1.0 had no `fn:`, so there is no `fn_rt`.
2. **`x_rt` `select`** stays XPath and static (`rtexprvalue` false), as in `x.tld` (§2.3, §11.1.5). The suite uses `x_rt:set select="$doc//parent"` and `x_rt:forEach select=…` as literal XPath.
3. **JSTL 1.0 EL URIs (`http://java.sun.com/jstl/core`, `…/sql`, `…/fmt`, `…/xml`): not shipped; shipping nothing is acceptable.** Only `spec.sql.update` `negativeUpdateVarAttributeTest` and `negativeUpdateScopeVarAttributeTest` import them. Both are negative tests that check only HTTP 500, so they pass whether the URI is mapped or not. The constraints they target (an empty `var`, a `scope` without `var` on `sql:update`) are tested on `jakarta.tags.sql` by Task 3. No compat page uses them: `compat.onedotzero` pairs `jakarta.tags.*` with the `_rt` URIs, and `positiveJSTLURITest` imports exactly those eight. The Tags 3.0 spec names no 1.0 URI (§1.2, Appendices A and B). BASELINE keeps the "passes through the unmapped URI" note.
4. **BOD-006: `TagLibraryValidator`s, not run-time checks.**
   - §2.7 requires constraints and syntax errors on static attributes to be reported at translation time. A TLV is the only tag-library mechanism that sees nesting, sibling order and template text (a TEI sees one tag's attributes).
   - Ibarra runs TLD validators at page `init()`, before `jspInit` and before any output (IBARRA-087, a known deviation). The baseline proves this is early enough: the TCK's own validators fail `negativeScriptFreeTlv*` with "TagLibraryValidator validation failed for http://java.sun.com/jstltck/nodecl", HTTP 500, and `spec.etu.tlv.permitted` passes 2/2.
   - Run-time checks cannot report a `c:when` after `c:otherwise` before the `otherwise` body is written (the page prints "Body improperly processed."). They cannot see template text in a `choose`, and they miss violations in branches that do not run.
   - On Jasper or Open Liberty (B7) a TLV runs at real translation time.
   - The existing run-time checks stay, as defence in depth. A scan of every suite page found the checked constructs only in negative pages: a non-whitespace child of `choose`, `scope` without `var`, an empty `var`, an invalid static `scope`, and `x:parse` without `var`/`varDom`.
5. **parseDate: the baseline diagnosis is wrong; the controller ruling alone does not recover the two tests.** Both pages call `fmt:parseDate` without `type`, so the type is `date` (§9.10), not `both`. JDK 25 parses the date part of 'Nov 21, 2000 3:45:02 AM' and stops at index 12. Bodoni then refuses the text left over (`position.getIndex() != text.length()`). The JDK's own `DateFormat.parse(String)` accepts it: checked on JDK 25, `getDateInstance(DEFAULT, en_US)`, non-lenient, gives `Tue Nov 21 2000`. The page passes on any JDK only if the trailing text is ignored, so the TCK has always expected `DateFormat.parse(String)` semantics, which `fmt:parseNumber` already has (`NumberFormat.parse(String)`).
   - The U+202F ruling, as worded, also fails: replacing every ASCII space of 'Nov 21, 2000, 3:45:02 AM' by U+202F breaks the parse at index 3, because the CLDR pattern `MMM d, y, h:mm:ss<U+202F>a` keeps ordinary spaces elsewhere. Task 6 therefore implements:
     - (a) BOD-010: success as soon as the parse consumed text, the remainder ignored. This recovers the two tests and reverses the B2 unit test `trailingTextIsAJspExceptionNamingTheValue`. **Controller ruling (2026-10-24, under the maintainer's standing go-ahead):** accepted. §9.10 "Parsing is non-lenient, i.e. the given date string must strictly adhere to the parsing format" names `DateFormat.setLenient(false)`, which governs field values, not trailing text; the TCK pages (the arbiter) expect `Nov 21, 2000 3:45:02 AM` to parse with the default `type=date`, which leaves `3:45:02 AM` unread on every JDK, so the reference behaviour ignores the remainder, as `DateFormat.parse(String)` and Bodoni's `fmt:parseNumber` do. Record it in PLAN.md (Decisions) and BUG.md BOD-010.
     - (b) BOD-011: the ruling's intent, implemented as a retry with the *pattern's* U+202F and U+00A0 replaced by U+0020, for style-based formats only. An explicit `pattern` attribute stays strict.
6. **BOD-008.** §8.2 says "Localization context established by attribute bundle", and §8.4 says "Any `<fmt:message>` action that establishes an i18n localization context" calls `setLocale`. So a non-null `bundle` attribute sets the response locale. A `LocalizationContext` object configured in `jakarta.servlet.jsp.jstl.fmt.localizationContext` is "used", not established (§8.2), and its behaviour stays as it is.
7. **COMP-010 (32 tests, plus `compat.onedotzero#positiveDateParamQueryTimestampTest` once `_rt` ships).** This is outside the code scope. Task 7 says how to count it.

## File structure

| File | Responsibility | Task |
|---|---|---|
| `bodoni-core/src/main/resources/META-INF/c.tld`, `c-1_2.tld` | `c:forTokens` `items` deferred-value | 1 |
| `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/core/ForTokensTag.java` | `setItems(Object)`, deferred `items` | 1 |
| `bodoni-core/src/main/resources/META-INF/{c,fmt,sql,x}-1_0-rt.tld` (new) | JSTL 1.0 request-time URIs | 2 |
| `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/LegacyRtTldTest.java` (new) | RT TLD derivation rule | 2 |
| `bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/TldReader.java` | `tlibVersion()`, `validatorClass()` | 2, 3 |
| `bodoni-core/src/main/java/io/vidocq/bodoni/core/internal/PageConstraints.java` (new) | SAX walk of the XML view and the constraint table | 3 |
| `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/{core,fmt,sql,xml}/*TagLibraryValidator.java` (new) | Public validators named by the TLDs | 3 |
| `bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/TestPageData.java` (new) | `PageData` over a string | 3 |
| `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/TagLibraryValidatorsTest.java` (new) | Constraint tests through the public validators | 3 |
| `bodoni-peano/src/main/java/io/vidocq/bodoni/peano/internal/LocalizationContextAccessorTable.java` (new) | BOD-007 table | 4 |
| `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/fmt/MessageTag.java` | BOD-008 | 5 |
| `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/fmt/ParseDateTag.java`, `DateTagSupport.java` | BOD-010, BOD-011 | 6 |
| `bodoni-tck/BASELINE.md`, `BUG.md`, `PLAN.md`, `docs/en/modules/ROOT/pages/{reference,usage,migration,tck}.adoc` | Records and docs | each task, then 7 |

`module-info.java` changes in no module: the validators live in packages that are already exported, and `java.xml` is already required.

---

### Task 1: `c:forTokens` deferred `items` (BOD-009) — recovers 1 test

**Files:**
- Modify: `bodoni-core/src/main/resources/META-INF/c.tld` and `c-1_2.tld` (the `forTokens` `items` attribute, lines ~238–244)
- Modify: `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/core/ForTokensTag.java`
- Test: `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/core/ForTokensTagTest.java`, `CoreTldTest.java`
- Docs: `BUG.md` (BOD-009 status), `PLAN.md` (new section "Decisions recorded in the TCK fixes (2026-10-24)"), `reference.adoc` (core tags: `forTokens` accepts a deferred `items`)

**Interfaces:**
- Produces: `ForTokensTag.setItems(Object items)` (replaces `setItems(String)`). A rule used by Tasks 2 and 3: a TLD attribute that is both `rtexprvalue` and `deferred-value` has a setter of type `java.lang.Object` (Ibarra's `TagAttribute.setterType()` and the Pages TLD rule).

- [ ] **Step 0: Rebase** (Global Constraints precondition), then `./mvnw -ntp -q install -DskipTests` to confirm a green start.
- [ ] **Step 1: Write the failing tests.**
  - `CoreTldTest`: `SPEC.put("forTokens", …)` gets `"items", new Attr(true, true, String.class, true)` (required, dynamic, `deferred-value` type `java.lang.String`). In `handlersExposeEveryDeclaredAttribute`, the expected setter parameter is `java.lang.Object` when `a.deferred() && a.rtexprvalue()`, else `a.type()`.
  - `ForTokensTagTest.deferredItemsMapVarToIteratedValueExpressionsOverTheTokens`: `setItems(new TestValueExpression("[Tiger Woods][Pebble Beach]"))`, `setDelims("[]")`, `setVar("x")`. During the body, collect `pc.getELContext().getVariableMapper().resolveVariable("x")`. Assert 2 items, each `assertInstanceOf(IteratedValueExpression.class, …)`, `getValue(pc.getELContext())` equal to `"Tiger Woods"` then `"Pebble Beach"`, and `resolveVariable("x")` `null` after the tag (the same shape as `ForEachTagTest.deferredNonListItemsMapVarToIteratedValueExpressions`).
  - `ForTokensTagTest.nonStringItemsAreCoercedToString`: `setItems(42)`, `setDelims(",")` gives one iteration of `"42"`; `setItems(new TestValueExpression(java.time.DayOfWeek.MONDAY))` gives `"MONDAY"`.
  - `ForTokensTagTest.nullDeferredItemsIterateZeroTimes`: `setItems(new TestValueExpression(null))` gives no body evaluation.
- [ ] **Step 2: Run them; expect FAIL** (compilation: no `setItems(Object)`; `CoreTldTest` deferred mismatch).
  `./mvnw -ntp -pl bodoni-core test -Dtest='ForTokensTagTest,CoreTldTest'`
- [ ] **Step 3: Implement.**
  - TLDs: `items` keeps `<required>true</required><rtexprvalue>true</rtexprvalue>`, and its `<type>` is replaced by `<deferred-value><type>java.lang.String</type></deferred-value>`, in both files. The `thePrefixedAndLegacyDescriptorsDifferOnlyByUri` test must stay green.
  - `ForTokensTag.setItems(Object items)` stores the raw value. In `prepare()`, a `ValueExpression` sets the inherited `deferredExpression` and is evaluated with `pageContext.getELContext()`. The value is then coerced by hand: `null` → no iteration, `String` as is, `Enum` → `name()`, other → `toString()` (EL 6.0 coercion to `String`; no `ExpressionFactory` lookup in the handler). `LoopTagSupport` maps `var` through `getDelims()`, which `ForTokensTag` already overrides (Javadoc of `IteratedExpression(ValueExpression, String delims)`). `release()` clears the raw value and `deferredExpression`.
- [ ] **Step 4: Run the tests; expect PASS**, then `./mvnw -ntp -pl bodoni-core test` (whole module green).
- [ ] **Step 5: Rerun the TCK class.** `TAGS_TCK_ZIP=… ./run-official-tck-tags-3.0.sh -Dtest=com.sun.ts.tests.jstl.spec.core.iteration.fortokens.JSTLClientIT` (the first run, without `--no-prepare`). Expected: `Tests run: 15, Failures: 0, Errors: 0`.
- [ ] **Step 6: Docs.** BOD-009 status: "fixed (2026-10-2x, branch `fix/tck-baseline`): `items` is `deferred-value` `java.lang.String` in `c.tld` and `c-1_2.tld`; `setItems(Object)`". Create the `PLAN.md` section with a table holding the BOD-009 row. Add one Reference line.
- [ ] **Step 7: Commit** `fix(core): c:forTokens accepts a deferred items expression (Tags 3.0 §6.3, BOD-009)`, with the plan file added.

### Task 2: JSTL 1.0 request-time TLDs — recovers 39 tests now, 40 with COMP-010

**Files:**
- Create: `bodoni-core/src/main/resources/META-INF/c-1_0-rt.tld`, `fmt-1_0-rt.tld`, `sql-1_0-rt.tld`, `x-1_0-rt.tld`
- Create: `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/LegacyRtTldTest.java`
- Modify: `bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/TldReader.java` (add `public String tlibVersion()`)
- Modify: `CoreTldTest`, `FmtTldTest`, `SqlTldTest`, `XmlTldTest` (RT rows; `libraryIdentity` gains a short-name column), `SetterAccessTest` (rows `fmt-1_0-rt.tld`, `x-1_0-rt.tld`, `sql-1_0-rt.tld`)
- Docs: `reference.adoc` (Tag libraries table: a third URI column "JSTL 1.0 request-time", the TLD list), `usage.adoc` (line on legacy URIs), `migration.adoc` (JSTL 1.0 bullet), `PLAN.md` (the "Legacy URIs" paragraph, open question 2, decision rows for Rulings 1–3)

**Interfaces:**
- Consumes: the Task 1 setter-type rule.
- Produces: the four RT TLD files, which Task 3 edits (adding `<validator>`), and the `LegacyRtTldTest` structure, which Task 3 extends with a validator assertion.

- [ ] **Step 1: Write the failing tests.**
  - `LegacyRtTldTest`, `@ParameterizedTest @CsvSource` over the rows `META-INF/c.tld,META-INF/c-1_0-rt.tld,http://java.sun.com/jstl/core_rt,c_rt`, `…fmt.tld,…fmt-1_0-rt.tld,http://java.sun.com/jstl/fmt_rt,fmt_rt`, `…sql…,http://java.sun.com/jstl/sql_rt,sql_rt`, `…x.tld,…x-1_0-rt.tld,http://java.sun.com/jstl/xml_rt,x_rt`:
    - `servesTheJstl10RequestTimeUri`: `rt.uri()`, `rt.shortName()`, `rt.tlibVersion()` = `"3.0"`.
    - `exposesTheSameTagsOnTheSameHandlers`: the same tag names in the same order, the same `tagClass` and `bodyContent` per tag.
    - `keepsEveryAttributeWithItsDynamicBehaviour`: per tag, the same attribute names, `required` and `rtexprvalue`; `deferred` false for every RT attribute; RT `type` equals `"java.lang.Object"` when the 3.0 attribute is `deferred && rtexprvalue`, else the 3.0 `type`.
  - Per-library `*TldTest`: add the RT file to `declaresExactlyThe…Tags` and `handlersExposeEveryDeclaredAttribute`, and the row `META-INF/c-1_0-rt.tld,http://java.sun.com/jstl/core_rt,c_rt` (and its three siblings) to `libraryIdentity(resource, uri, shortName)`.
- [ ] **Step 2: Run; expect FAIL** (`read` of a missing resource). `./mvnw -ntp -pl bodoni-core test -Dtest='LegacyRtTldTest,CoreTldTest,FmtTldTest,SqlTldTest,XmlTldTest,SetterAccessTest'`
- [ ] **Step 3: Write the four TLDs** by Ruling 1: the header of the matching 3.0 TLD, with `<description>Jakarta Standard Tag Library 3.0 - <library> library, JSTL 1.0 request-time URI</description>`, `<display-name>JSTL <library> RT</display-name>`, the RT `<short-name>` and `<uri>`, then the tags of the 3.0 TLD. In `c-1_0-rt.tld`, each `<deferred-value>…</deferred-value>` (`c:set value`, `c:forEach items`, `c:forTokens items`) becomes `<type>java.lang.Object</type>`.
- [ ] **Step 4: Run; expect PASS**, then `./mvnw -ntp -pl bodoni-core test`.
- [ ] **Step 5: Rerun the TCK classes.** `./run-official-tck-tags-3.0.sh -Dtest=com.sun.ts.tests.jstl.compat.onedotzero.JSTLClientIT,com.sun.ts.tests.jstl.spec.etu.tlv.scrfree.JSTLClientIT,com.sun.ts.tests.jstl.spec.sql.update.JSTLClientIT --no-prepare`. Expected:
  - `scrfree` 8/8: `negativeScriptFreeTlvNoRTExprTest` now fails through the TCK `nortexpr` validator; check its `server.log` line "TagLibraryValidator validation failed for http://java.sun.com/jstltck/nortexpr".
  - `sql.update` 18/21 (only the 3 COMP-010 errors).
  - `compat.onedotzero` 37/38 while Peano lacks COMP-010: `positiveDateParamQueryTimestampTest` fails on `<c:set var="expectedRows" value="${1}"/>` with the COMP-010 constructor signature.
- [ ] **Step 6: Triage every other `compat.onedotzero` error**, from the Surefire report and `bodoni-tck/target/pages-deployments/war-*/server.log`:
  - a cause a later task fixes (`LocalizationContext` under `deny`, the `bundle` response locale, parseDate trailing text): note the test in that task's Step 5 expectations;
  - anything else: a new `BUG.md` entry and a fix in this PR (Global Constraints).
- [ ] **Step 7: Docs** (the files listed above; the Reference table names each RT TLD and says "same handlers, attributes request-time, no deferred values"; migration: a `#{` in an attribute of a 1.0 library is a translation error unless `deferredSyntaxAllowedAsLiteral`, and the 1.0 EL URIs are not served, so such pages move to `jakarta.tags.*`). **Commit** `feat(tld): serve the JSTL 1.0 request-time URIs on the 3.0 handlers`.

### Task 3: translation-time constraints by tag library validators (BOD-006) — recovers 9 tests

**Files:**
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/internal/PageConstraints.java`
- Create: `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/core/CoreTagLibraryValidator.java`, `tags/fmt/FmtTagLibraryValidator.java`, `tags/sql/SqlTagLibraryValidator.java`, `tags/xml/XmlTagLibraryValidator.java`
- Create: `bodoni-core/src/test/java/io/vidocq/bodoni/core/testing/TestPageData.java`, `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/TagLibraryValidatorsTest.java`
- Modify: the 12 TLDs of `c`, `fmt`, `sql` and `x` (the 3.0, `-1_2` and `-1_0-rt` files; not `fn`), `TldReader` (add `public String validatorClass()`), `CoreTldTest`, `FmtTldTest`, `SqlTldTest`, `XmlTldTest` (`declaresTheLibraryValidator`), `LegacyRtTldTest` (same validator class as the 3.0 TLD)
- Docs: `BUG.md` (BOD-006 fixed), `reference.adoc` (replace the "Not checked at translation time" bullets of `sql:` and `x:` with one "Constraints" subsection under Tag libraries), `PLAN.md` (decision row for Ruling 4; mark the B3/B4 "no `<validator>` (B1 precedent)" rows as superseded, 2026-10-2x)

**Interfaces:**
- Produces:
  - `public final class PageConstraints` (internal) with `public enum Library { CORE, FMT, SQL, XML }` and `public static ValidationMessage[] validate(Library library, String uri, PageData page)`. It returns `null` when the page is valid (`TagLibraryValidator.validate` Javadoc: null or empty means no error), else one `ValidationMessage(jspId, text)` per violation. `jspId` is the element's `jsp:id` (namespace `http://java.sun.com/JSP/Page`), `null` when absent.
  - `public final class CoreTagLibraryValidator extends jakarta.servlet.jsp.tagext.TagLibraryValidator` (and the `Fmt`, `Sql` and `Xml` variants), each overriding only `validate(String prefix, String uri, PageData page)` to return `PageConstraints.validate(Library.<X>, uri, page)`. They are public, final and stateless, with a public no-argument constructor; they extend the API class directly, never an internal class (Ibarra generates `new <class>()`, `setInitParameters`, `validate`, `release` in the application module).
  - TLD element, placed right after `<uri>`: `<validator><validator-class>io.vidocq.bodoni.core.tags.core.CoreTagLibraryValidator</validator-class></validator>` (and the `fmt`, `sql` and `xml` equivalents).

**Constraint table** (the only rules; message text `"<qName>: <rule> (Tags 3.0 §n)"`):

| Library | Elements in namespace `uri` | Rule | Spec |
|---|---|---|---|
| CORE, XML | `choose` | children: white-space-only `jsp:text` and `when`/`otherwise` only; ≥ 1 `when`; every `when` before the `otherwise`; ≤ 1 `otherwise`. A `jsp:body` child is transparent, `jsp:attribute` children are ignored. Anything else (non-blank `jsp:text`, `jsp:scriptlet`, `jsp:expression`, another element) is reported | §5.4, §12.3 |
| CORE, XML | `when`, `otherwise` | parent (seen through a `jsp:body`) is a `choose` | §5.5, §5.6, §12.4, §12.5 |
| CORE | `if` | `scope` needs `var` | §5.3 |
| FMT | `message`, `formatNumber`, `parseNumber`, `formatDate`, `parseDate` | `scope` needs `var` | §8.8, §9.7–§9.10 |
| SQL | `update` | `scope` needs `var` | §10.4 |
| SQL | `query`, `update` | no `dataSource` attribute under a `transaction` ancestor | §10.3, §10.5 |
| XML | `if` | `scope` needs `var` | §12.2 |
| XML | `parse` | `var` or `varDom` present; `scope` needs `var`; `scopeDom` needs `varDom` | §11.2 |
| all four | any element | a literal `scope` / `scopeDom` not in `page`, `request`, `session`, `application` | §2.7 |
| all four | any element | a literal empty `var`, `varDom`, `varStatus`, `varReader` | §2.7 |

"Present" means an XML attribute, or a `jsp:attribute` child whose `name` is that attribute (such a value is never checked as a literal). "Of the same library" for `choose`, `when`, `otherwise` and `transaction` means any URI of the library family:
- CORE: `jakarta.tags.core`, `http://java.sun.com/jsp/jstl/core`, `http://java.sun.com/jstl/core_rt`
- XML: `jakarta.tags.xml`, `http://java.sun.com/jsp/jstl/xml`, `http://java.sun.com/jstl/xml_rt`
- SQL: `jakarta.tags.sql`, `http://java.sun.com/jsp/jstl/sql`, `http://java.sun.com/jstl/sql_rt`

Only elements in namespace `uri` are subjects of a rule, so a page that uses two URIs is checked once per URI (Ibarra calls the validator once per URI). The XML view is read with `XmlFactories.xmlReader(resolver)`, whose resolver throws on every external entity. A view that cannot be parsed gives one message, "cannot read the XML view of the page: <reason>": the validator fails closed.

- [ ] **Step 1: Write the failing tests.**
  - `TestPageData`, a `PageData` whose `getInputStream()` serves UTF-8 bytes of a `String`. Helper `TestPageData.view(String body, String... prefixUriPairs)` wraps the body in `<jsp:root xmlns:jsp="http://java.sun.com/JSP/Page" xmlns:<p>="<uri>"… version="2.0" jsp:id="0">…</jsp:root>`.
  - `TagLibraryValidatorsTest` calls the public validators (for example `new CoreTagLibraryValidator().validate("c", "jakarta.tags.core", view(…))`). Each "reported" test asserts exactly one message, its `getId()` (the offending element's `jsp:id`), and that the message contains the qName and the section:
    - `chooseWithoutWhenIsReported` (body: white space only, §5.4)
    - `whenAfterOtherwiseIsReported`
    - `secondOtherwiseIsReported`
    - `templateTextInsideChooseIsReported` (non-blank `jsp:text`; and a `jsp:scriptlet`)
    - `whiteSpaceAndJspBodyInsideChooseAreAccepted` (blank text and two `when` + `otherwise` inside `jsp:body`, giving `null`)
    - `whenOutsideChooseIsReported`, `otherwiseOutsideChooseIsReported`
    - `whenOfAnotherUriOfTheSameLibraryIsAccepted` (`c:choose` of `jakarta.tags.core` holding `c_rt:when` of `core_rt`, validated for both URIs, giving `null` twice)
    - `elementsOfOtherUrisAreIgnored` (an `x:choose` without `x:when`, validated as `jakarta.tags.core`, gives `null`)
    - the same structural checks for `x:` through `XmlTagLibraryValidator` (`xmlChooseRulesMatchCore`, parameterized)
    - `scopeWithoutVarIsReported`, `@CsvSource` over `CORE,jakarta.tags.core,if`; `FMT,jakarta.tags.fmt,message|formatNumber|parseNumber|formatDate|parseDate`; `SQL,jakarta.tags.sql,update`; `XML,jakarta.tags.xml,if|parse`. The same element with `var` gives `null`.
    - `scopeDomWithoutVarDomIsReported`, `parseWithoutVarOrVarDomIsReported`
    - `jspAttributeCountsAsAPresentAttribute`
    - `invalidLiteralScopeIsReported` (`c:set scope="invalid"`, `sql:setDataSource scope="invalid"`)
    - `everyValidScopeIsAccepted`
    - `emptyVarIsReported` (`sql:update var=""`, `c:forEach varStatus=""`)
    - `dataSourceInsideTransactionIsReported` (directly and two levels deep)
    - `malformedViewIsReported`, `externalEntityIsRefusedNotFetched` (a DOCTYPE with a `file:` system id gives a message, and nothing is read)
    - `aValidPageGivesNull`
  - `*TldTest.declaresTheLibraryValidator`: each 3.0/1.2/RT file of the library has `validatorClass()` equal to the library's validator, and that class is public and has a public no-argument constructor. `LegacyRtTldTest.declaresTheSameValidator`.
- [ ] **Step 2: Run; expect FAIL** (missing classes). `./mvnw -ntp -pl bodoni-core test -Dtest='TagLibraryValidatorsTest,*TldTest,LegacyRtTldTest'`
- [ ] **Step 3: Implement** `PageConstraints` (a SAX `DefaultHandler` with an element stack of `(namespace, localName, jspId, attributes, jsp:attribute names, child state)`, the table above as `Library` data) and the four validators. Add `<validator>` to the 12 TLDs.
- [ ] **Step 4: Run; expect PASS**; then `./mvnw -ntp clean install` (all modules green), and `./mvnw -ntp -Pit-ibarra -pl bodoni-it-ibarra -am verify` when the local Ibarra/Foy/Peano/Chappe snapshots are present (the IT pages must stay valid; if the snapshots are missing, say so in the task report).
- [ ] **Step 5: Rerun the TCK classes.** `./run-official-tck-tags-3.0.sh -Dtest=com.sun.ts.tests.jstl.spec.core.conditional.cwo.JSTLClientIT,com.sun.ts.tests.jstl.spec.xml.xconditional.xcwo.JSTLClientIT,com.sun.ts.tests.jstl.spec.xml.xconditional.xif.JSTLClientIT,com.sun.ts.tests.jstl.spec.xml.xmlcore.parse.JSTLClientIT,com.sun.ts.tests.jstl.spec.fmt.format.fmtdate.JSTLClientIT,com.sun.ts.tests.jstl.spec.fmt.format.fmtnum.JSTLClientIT --no-prepare`. Expected: cwo 9/9, xcwo 8/8, xif 7/7, parse 15/15, fmtdate 14/14, fmtnum 19/19. Check `server.log`: each of the 9 shows "TagLibraryValidator validation failed for jakarta.tags.…".
- [ ] **Step 6: Regression run.** `./run-official-tck-tags-3.0.sh all --no-prepare`. A validator touches every page, and the full run takes about 90 s. Expected: no test that passed before Task 3 fails. `parsedate#negativePDScopeNoVarTest` and `parsenum#negativePNScopeNoVarTest` now fail through the validator (`server.log`), not through "no formatting locale". Any new error: a false positive, so fix the rule (with a test) before the commit.
- [ ] **Step 7: Docs and commit** `feat(tlv): report the Tags 3.0 translation-time constraints from tag library validators (§2.7, BOD-006)`.

### Task 4: `LocalizationContext` accessor table (BOD-007) — recovers 3 tests

**Files:**
- Create: `bodoni-peano/src/main/java/io/vidocq/bodoni/peano/internal/LocalizationContextAccessorTable.java`
- Modify: `bodoni-peano/src/main/java/io/vidocq/bodoni/peano/internal/BodoniAccessorProvider.java` (third table)
- Test: `bodoni-peano/src/test/java/io/vidocq/bodoni/peano/internal/LocalizationContextAccessorTableTest.java`, `bodoni-peano/src/test/java/io/vidocq/bodoni/peano/LocalizationContextAccessTest.java`
- Docs: `BUG.md` (BOD-007 fixed), `reference.adoc` (section "Peano deny mode: bodoni-peano": three tables), `PLAN.md` (decision row)

**Interfaces:**
- Consumes: `ReadOnlyAccessorTable(Class<?>, List<String>, MethodDescriptor[])`, `descriptor(String, Class<?>)` and `read(Object base, int index)`, as `ResultAccessorTable` uses them.
- Produces: `final class LocalizationContextAccessorTable extends ReadOnlyAccessorTable`, keyed on `jakarta.servlet.jsp.jstl.fmt.LocalizationContext` (a class; subclasses reach it through Peano's supertype lookup), with properties `resourceBundle` (`java.util.ResourceBundle`) and `locale` (`java.util.Locale`) and methods `getResourceBundle`, `getLocale`. All read-only.

- [ ] **Step 1: Write the failing tests**, mirroring `ResultAccessorTableTest` and `ResultAccessTest` (the same `deny()` / `restore()` of `peano.reflection`):
  - table tests: `targetsTheClass`, `getValueReturnsEveryPropertyAndMarksItResolved`, `foreignBasesAndPropertiesAreNotResolved`, `everyPropertyRejectsWrites`, `invokesEveryGetter`, `getterFailuresAreWrapped`;
  - deny-mode EL tests: `readsBothPropertiesOfALocalizationContext` (`${ctx.resourceBundle}` is the same instance, `${ctx.locale}` is `Locale.ENGLISH`), `aContextWithoutBundleReadsNull` (`${ctx.resourceBundle == null}` and `${ctx.locale == null}` are `true` for `new LocalizationContext()`), `aSubclassIsResolvedThroughTheSupertype`, `invokesTheGetters` (`${ctx.getLocale()}`), `everyPropertyIsReadOnly`, `unknownPropertiesStillHitTheDenyGuard`, `agreesWithBeanELResolverOnEveryProperty`.
- [ ] **Step 2: Run; expect FAIL.** `./mvnw -ntp -pl bodoni-peano test`
- [ ] **Step 3: Implement** the table and register it in `BodoniAccessorProvider.accessors()`.
- [ ] **Step 4: Run; expect PASS.**
- [ ] **Step 5: Rerun the TCK class.** `./run-official-tck-tags-3.0.sh -Dtest=com.sun.ts.tests.jstl.spec.fmt.i18n.setbundle.JSTLClientIT --no-prepare`. Expected: 7/7.
- [ ] **Step 6: Docs and commit** `fix(peano): read LocalizationContext under peano.reflection=deny (Tags 3.0 §8.1.1, BOD-007)`.

### Task 5: `fmt:message bundle` sets the response locale (BOD-008) — recovers 2 tests

**Files:**
- Modify: `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/fmt/MessageTag.java` (`resolve()`)
- Test: `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/fmt/MessageTagTest.java`
- Docs: `BUG.md` (BOD-008 fixed), `reference.adoc` (fmt section: which actions set the response locale), `PLAN.md` (decision row for Ruling 6)

**Interfaces:**
- Consumes: `I18n.setResponseLocale(PageContext, Locale)` (ignores a `null` locale, records the charset only in an existing session).

- [ ] **Step 1: Write the failing tests.**
  - `bundleAttributeSetsTheResponseLocale`: with `preferred(Locale.FRENCH)` and a `bundle` of `new LocalizationContext(ResourceBundle.getBundle("test-bundles.ex2.Resources", Locale.ENGLISH), Locale.ENGLISH)`, the output is `"en"` and `((TestHttpServletResponse) pc.getResponse()).getLocale()` is `Locale.ENGLISH`.
  - `bundleAttributeRecordsTheCharsetInTheSession`: `response.mapLocaleEncoding(Locale.JAPANESE, "Shift_JIS")` and a context with `Locale.JAPANESE`; the session attribute `jakarta.servlet.jsp.jstl.fmt.request.charset` is `"Shift_JIS"` (as in `SetLocaleTagTest.sessionCharsetIsRecordedOnlyWhenASessionExists`).
  - `bundleAttributeWithoutLocaleLeavesTheResponseLocale`: `new LocalizationContext(bundle)` leaves the response locale at its value before the tag (§8.4 "unless … does not have any locale").
  - The existing `configuredLocalizationContextIsUsedAsIs` gains `assertEquals(<locale before>, response.getLocale())`, to pin Ruling 6.
- [ ] **Step 2: Run; expect FAIL** on the first two. `./mvnw -ntp -pl bodoni-core test -Dtest=MessageTagTest`
- [ ] **Step 3: Implement.** In `resolve()`, when the `bundle` attribute is non-null, call `I18n.setResponseLocale(pageContext, bundle.getLocale())` before the lookup. Nothing else changes (the enclosing `fmt:bundle` and a configured basename already set it).
- [ ] **Step 4: Run; expect PASS**; then `./mvnw -ntp -pl bodoni-core test`.
- [ ] **Step 5: Rerun the TCK class.** `./run-official-tck-tags-3.0.sh -Dtest=com.sun.ts.tests.jstl.spec.fmt.i18n.responseencoding.JSTLClientIT,com.sun.ts.tests.jstl.spec.fmt.i18n.message.JSTLClientIT --no-prepare`. Expected: 2/2 and 17/17.
- [ ] **Step 6: Docs and commit** `fix(fmt): a bundle attribute of fmt:message sets the response locale (Tags 3.0 §8.4, BOD-008)`.

### Task 6: `fmt:parseDate` parse rule (BOD-010, BOD-011) — recovers 2 tests

> **Gate lifted:** Ruling 5 (a) is accepted (controller ruling above); do both (a) and (b).

**Files:**
- Modify: `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/fmt/ParseDateTag.java` (`convert`)
- Modify: `bodoni-core/src/main/java/io/vidocq/bodoni/core/tags/fmt/DateTagSupport.java` (add `final boolean hasPattern()`: `pattern != null && !pattern.isEmpty()`)
- Test: `bodoni-core/src/test/java/io/vidocq/bodoni/core/tags/fmt/ParseDateTagTest.java`
- Docs: `BUG.md` (new BOD-010 "`fmt:parseDate` refuses text after a complete date", status fixed; new BOD-011 "`fmt:parseDate` refuses an ASCII space where the JDK 20+ CLDR style has U+202F", status fixed). Correct the BOD-006..009 neighbourhood only where they cite the parsedate category. Also `reference.adoc` (fmt section: the parse rule), `PLAN.md` (decision row for Ruling 5; amend the B2 "Dates" bullet).

**Interfaces:**
- Produces: the parse rule, which Task 7 documents.
  1. Parse with the format from `dateFormat(locale)`, non-lenient, with a `ParsePosition`. The result is a success when it is non-null, whatever text is left (`DateFormat.parse(String)` semantics; BOD-010).
  2. On failure, if `!hasPattern()`, the format is a `SimpleDateFormat`, and `toPattern()` contains U+202F or U+00A0: retry once, non-lenient, with `new SimpleDateFormat(<pattern with U+202F and U+00A0 replaced by U+0020>, <same locale>)` and the same time zone (BOD-011).
  3. Otherwise, throw `unparseable(text, <error index of the first attempt>)`, whose cause is a new `ParseException` built from the first attempt.

- [ ] **Step 1: Write the failing tests** (Locale `en_US` through `us(…)`, zone GMT; each CLDR test starts with `assumeTrue(((SimpleDateFormat) DateFormat.getTimeInstance(DateFormat.DEFAULT, Locale.US)).toPattern().indexOf(' ') >= 0)`):
  - `dateTypeAcceptsADateFollowedByATime`: value `"Nov 21, 2000 3:45:02 AM"`, no type, `var` `d`; `parsed()` equals 2000-11-21T00:00:00Z (the TCK case).
  - replace `trailingTextIsAJspExceptionNamingTheValue` by `textAfterACompleteDateIsIgnored`: `"4/13/02x"` with pattern `M/d/yy` gives `INSTANT`.
  - `asciiSpaceMatchesTheNarrowNoBreakSpaceOfATimeStyle`: type `time`, value `"3:45:03 AM"` gives `new Date(13_503_000L)`.
  - `asciiSpaceMatchesInADateAndTimeStyle`: type `both`, value `"Nov 21, 2000, 3:45:02 AM"` gives 2000-11-21T03:45:02Z.
  - `explicitPatternStaysStrict`: pattern `"h:mm a"`, value `"3:45 AM"`, gives a `JspException`.
  - `failedRetryKeepsTheFirstParseExceptionAsCause`: type `time`, value `"25:99 XM"`; the message contains the value, the cause is a `ParseException` and `getErrorOffset()` is that of the first attempt.
  - unchanged and still green: `impossibleDateIsAJspExceptionNamingTheValue`, `unparsableValueWithStylesIsAJspException`.
- [ ] **Step 2: Run; expect FAIL** on the new tests. `./mvnw -ntp -pl bodoni-core test -Dtest=ParseDateTagTest`
- [ ] **Step 3: Implement** the three-step rule of the Interfaces block.
- [ ] **Step 4: Run; expect PASS**; then `./mvnw -ntp -pl bodoni-core test`.
- [ ] **Step 5: Rerun the TCK classes.** `./run-official-tck-tags-3.0.sh -Dtest=com.sun.ts.tests.jstl.spec.fmt.format.parsedate.JSTLClientIT,com.sun.ts.tests.jstl.compat.onedotzero.JSTLClientIT --no-prepare`. Expected: parsedate 18/18; compat no worse than after Task 2.
- [ ] **Step 6: Docs and commit** `fix(fmt): fmt:parseDate ignores text after a complete date and accepts an ASCII space for the CLDR U+202F (Tags 3.0 §9.10, BOD-010, BOD-011)`.

### Task 7: full run, baseline, records and docs

**Files:**
- Modify: `bodoni-tck/BASELINE.md`, `BUG.md`, `PLAN.md`, `docs/en/modules/ROOT/pages/tck.adoc` (and `README.md`/`AGENTS.md` only where they quote the 452/541 figure)

- [ ] **Step 1: Peano state.** Check whether Vidocq/peano#61 (COMP-010) has merged, and whether the `peano-core` / `peano-compiler` snapshot in `~/.m2` or on central-snapshots was published after the merge (`maven-metadata` `lastUpdated`, then the checksum match as in BASELINE). Never install the unmerged companion branch for the recorded run.
- [ ] **Step 2: Full run.** `./run-official-tck-tags-3.0.sh all` (reactor install included); keep `bodoni-tck/target/full-run.log` and copy it with the reports to the scratchpad. Record date, commit, JVM, OS, zone, locale and the snapshot table (the same `dependency:list` and checksum method as BASELINE). Expected:
  - Peano without COMP-010: **508/541**, the 33 errors all with the COMP-010 constructor signature (`sql.param` 20, `sql.result` 4, `sql.transaction` 4, `sql.update` 3, `core.iteration.foreach` 1, `compat.onedotzero#positiveDateParamQueryTimestampTest` 1).
  - Peano with COMP-010: **541/541**.
- [ ] **Step 3: If any of the 33 still fail with COMP-010 merged**, decide by signature:
  - the same constructor signature: the snapshot is stale, so re-resolve and rerun those six classes;
  - another signature: triage as in BASELINE. A Bodoni cause gets a `BUG.md` entry and is fixed in this PR. An upstream cause gets a proposed issue title (BASELINE "Upstream items"), and those tests are recorded as owned upstream. 100 % is the merge gate, so the PR waits or the maintainer rules.
  - If Peano has not merged: record 508/541 with the 33 under "Peano COMP-010 (Vidocq/peano#61, pending)", then rerun the six classes and add a dated "Rerun" line once the snapshot lands.
  - Any error outside these 33: a defect of this PR, so fix it first.
- [ ] **Step 4: BASELINE.md.**
  - "Recorded results": add a column "After TCK fixes (<date>, <commit>)".
  - "Per class": add a column "After fixes" with PASS/FAIL and the residual cause.
  - Add a dated correction under "Pre-CLDR date-time input": the cause was Bodoni's trailing-text rule (BOD-010), not the CLDR pattern; withdraw the proposed TCK issue in "Upstream items". Keep the original text of the first run.
  - Update the "Passing tests worth a note" items: the scope-without-var and `choose` negatives now fail through the validators; the two 1.0 EL `sql/update` pages still pass through the unmapped URI (Ruling 3).
- [ ] **Step 5: Records and docs.** `BUG.md`: BOD-006..011 statuses with the commit ids. `PLAN.md`: TCK plan figures, the decisions table complete (Rulings 1–6), open question 2 closed. `tck.adoc`: the result line, the cause table (the "two TCK pages that cannot pass on JDK 20 and later" row removed, see BOD-010), and the "Fixes" row.
- [ ] **Step 6: Final checks.** `./mvnw -ntp clean install` green; `./run-official-tck-tags-3.0.sh --no-install smoke` 91/91.
- [ ] **Step 7: Commit** `test(tck): official Tags TCK after the baseline fixes (<n>/541)`.
