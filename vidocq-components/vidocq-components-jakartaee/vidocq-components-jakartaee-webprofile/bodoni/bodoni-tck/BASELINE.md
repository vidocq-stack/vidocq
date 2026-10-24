# Jakarta Tags official TCK baseline (jakarta.tck:tags-tck:11.0.3)

Date: 2026-10-24 (01:10:57 to 01:12:25 CEST, 2026-10-23 23:10:57 to 23:12:25 UTC, reactor
build included). Bodoni `0.4.0-SNAPSHOT` at commit `1637d78` (branch `feat/b5-tck`), Temurin
25+36 (`openjdk version "25" 2025-09-16 LTS`), Maven 3.9.16, macOS `Darwin arm64` (Apple M5
Max). Machine zone `Europe/Paris` (`TZ` unset, so the Surefire JVM and the forked hosts used
the system zone); JVM locale `fr_FR` (`user.language=fr`, `user.country=FR`), `LANG=en_US.UTF-8`.
Resolved snapshots (`./mvnw -ntp -Ptck,tck-official -pl bodoni-tck dependency:list`, then the
local jar matched by checksum with the downloaded build):

| Artifact | Build |
|---|---|
| `ibarra-arquillian`, `ibarra-core`, `ibarra-compiler`, `ibarra-foy` | local install of Ibarra `main` at `a36f57f` (2026-10-24 00:14 CEST); `maven-metadata-local.xml` `lastUpdated` 20261023221311 |
| `foy-core` | `0.4.0-20261023.171509-58` (central-snapshots, `lastUpdated` 20261023171509) |
| `peano-core`, `peano-compiler` | `0.4.0-20261022.203832-58` / `-41` (central-snapshots, `lastUpdated` 20261022203832) |
| `chappe-core` | `0.4.0-20261023.163359-37` (central-snapshots, `lastUpdated` 20261023163359) |
| H2 | 2.3.232 (test scope, database served over TCP to the hosts) |

This measures Bodoni on Ibarra + Foy + Peano with the Tags tests of the Jakarta EE 11 Platform
TCK; it is not a certification: the formal `jakarta-tags-tck-3.0.0.zip` (JavaTest) was not run.
The zip is read for one file only, the Derby DDL of the `sql` tests (pinned SHA-256, see the
script). Every host runs with `-Dpeano.reflection=deny`.

```bash
sdk env
./run-official-tck-tags-3.0.sh all
```

## Recorded results

| Measure | Result |
|---|---:|
| Official classes selected | 50 |
| Declared `@Test` methods in the official binary | 541 |
| Official Surefire entries reported | 541 |
| Passed | 452 |
| Assertion failures (Surefire `failure`) | 0 |
| Method errors (Surefire `error`) | 89 |
| Class-level (deployment) errors | 0 |
| Skipped | 0 |
| Classes at 100 % | 33/50 |
| Pass rate | **452/541 (83.5 %)** |

Surefire reports `Tests run: 541, Failures: 0, Errors: 89, Skipped: 0`. Every class deploys,
so the 541 entries match the 541 declared methods. The TCK client reports every failed check
as an `error` (`java.lang.Exception: [BaseUrlClient] … failed!`), never as a `failure`.

By library: `c` (`spec.core`) 111/115, `fn` + extensions (`spec.etu`) 77/81, `fmt` 114/123,
`sql` 86/117, `x` (`spec.xml`) 62/67, JSTL 1.0 compatibility (`compat.onedotzero`) 2/38.

By root cause (89 errors):

| Root cause | Owner | Tests |
|---|---|---:|
| JSTL 1.0 `_rt` URIs not shipped | known decision (2026-10-23) | 40 |
| Constant `${…}` on a deferred-value attribute: the Peano expression lacks the `(ELContext, Class)` constructor (COMP-010) | Peano | 32 |
| Translation-time constraints not enforced (BOD-006) | Bodoni | 9 |
| `LocalizationContext` unreadable under `deny` (BOD-007) | Bodoni | 3 |
| `fmt:message bundle` does not set the response locale (BOD-008) | Bodoni | 2 |
| `c:forTokens` deferred `items` (BOD-009) | Bodoni | 1 |
| TCK page expects the pre-CLDR `en_US` date-time format | harness (TCK input on JDK 20+) | 2 |

## Per class

Class names are relative to `com.sun.ts.tests.jstl.`, each `….JSTLClientIT`.

| Class | Tests | Passed | Failures | Errors | Skipped | Status | Triage |
|---|---:|---:|---:|---:|---:|---|---|
| `compat.onedotzero` | 38 | 2 | 0 | 36 | 0 | FAIL | known: _rt TLDs pending (36) |
| `spec.core.conditional.cwo` | 9 | 7 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-006) (2) |
| `spec.core.conditional.iftag` | 6 | 6 | 0 | 0 | 0 | PASS |  |
| `spec.core.general.catchtag` | 2 | 2 | 0 | 0 | 0 | PASS |  |
| `spec.core.general.outtag` | 7 | 7 | 0 | 0 | 0 | PASS |  |
| `spec.core.general.remove` | 1 | 1 | 0 | 0 | 0 | PASS | smoke |
| `spec.core.general.set` | 8 | 8 | 0 | 0 | 0 | PASS |  |
| `spec.core.iteration.foreach` | 24 | 23 | 0 | 1 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (1) |
| `spec.core.iteration.fortokens` | 15 | 14 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-009) (1) |
| `spec.core.iteration.loopstatus` | 1 | 1 | 0 | 0 | 0 | PASS |  |
| `spec.core.urlresource.importtag` | 23 | 23 | 0 | 0 | 0 | PASS |  |
| `spec.core.urlresource.param` | 7 | 7 | 0 | 0 | 0 | PASS |  |
| `spec.core.urlresource.redirect` | 4 | 4 | 0 | 0 | 0 | PASS |  |
| `spec.core.urlresource.url` | 8 | 8 | 0 | 0 | 0 | PASS |  |
| `spec.etu.config` | 6 | 6 | 0 | 0 | 0 | PASS |  |
| `spec.etu.functions` | 64 | 64 | 0 | 0 | 0 | PASS | smoke |
| `spec.etu.tlv.permitted` | 2 | 2 | 0 | 0 | 0 | PASS |  |
| `spec.etu.tlv.scrfree` | 8 | 4 | 0 | 4 | 0 | FAIL | known: _rt TLDs pending (4) |
| `spec.etu.uri` | 1 | 1 | 0 | 0 | 0 | PASS | smoke |
| `spec.fmt.format.fmtdate` | 14 | 13 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-006) (1) |
| `spec.fmt.format.fmtnum` | 19 | 18 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-006) (1) |
| `spec.fmt.format.localecontext` | 4 | 4 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.format.parsedate` | 18 | 16 | 0 | 2 | 0 | FAIL | harness: TCK input vs JDK 20+ locale data (2) |
| `spec.fmt.format.parsenum` | 14 | 14 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.format.settimezone` | 6 | 6 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.format.timezone` | 2 | 2 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.i18n.bundle` | 6 | 6 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.i18n.message` | 17 | 17 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.i18n.param` | 2 | 2 | 0 | 0 | 0 | PASS | smoke |
| `spec.fmt.i18n.requestencoding` | 4 | 4 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.i18n.resourcelookup` | 2 | 2 | 0 | 0 | 0 | PASS |  |
| `spec.fmt.i18n.responseencoding` | 2 | 0 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-008) (2) |
| `spec.fmt.i18n.setbundle` | 7 | 4 | 0 | 3 | 0 | FAIL | Bodoni defect (BOD-007) (3) |
| `spec.fmt.i18n.setlocale` | 6 | 6 | 0 | 0 | 0 | PASS |  |
| `spec.sql.param` | 21 | 1 | 0 | 20 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (20) |
| `spec.sql.query` | 26 | 26 | 0 | 0 | 0 | PASS |  |
| `spec.sql.result` | 8 | 4 | 0 | 4 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (4) |
| `spec.sql.setdatasource` | 22 | 22 | 0 | 0 | 0 | PASS | smoke |
| `spec.sql.transaction` | 19 | 15 | 0 | 4 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (4) |
| `spec.sql.update` | 21 | 18 | 0 | 3 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (3) |
| `spec.xml.xconditional.xcwo` | 8 | 6 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-006) (2) |
| `spec.xml.xconditional.xforeach` | 9 | 9 | 0 | 0 | 0 | PASS |  |
| `spec.xml.xconditional.xif` | 7 | 6 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-006) (1) |
| `spec.xml.xmlcore.bindings` | 1 | 1 | 0 | 0 | 0 | PASS | smoke |
| `spec.xml.xmlcore.parse` | 15 | 13 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-006) (2) |
| `spec.xml.xmlcore.types` | 2 | 2 | 0 | 0 | 0 | PASS |  |
| `spec.xml.xmlcore.xout` | 4 | 4 | 0 | 0 | 0 | PASS |  |
| `spec.xml.xmlcore.xset` | 6 | 6 | 0 | 0 | 0 | PASS |  |
| `spec.xml.xtransform.param` | 3 | 3 | 0 | 0 | 0 | PASS |  |
| `spec.xml.xtransform.transform` | 12 | 12 | 0 | 0 | 0 | PASS |  |

No class reported `ERROR (deployment)`.

## Triage

Evidence comes from the Surefire report of each test (request, status line, body), the
deployment's `server.log` and `translation-errors.txt` under
`bodoni-tck/target/pages-deployments/war-*/`, and the TCK page. Every failing test has exactly
one category.

### JSTL 1.0 `_rt` URIs not shipped — known: _rt TLDs pending (decided 2026-10-23)

- Tests (40): the 36 failing tests of `compat.onedotzero`, and `spec.etu.tlv.scrfree`
  `#positiveScriptFreeTlvNoDeclTest`, `#positiveScriptFreeTlvNoScrTest`,
  `#positiveScriptFreeTlvNoExprTest`, `#positiveScriptFreeTlvNoRTExprTest`.
- Evidence: each page fails to translate on its first `taglib` directive, HTTP 500:
  `Page translation failed: /positiveScriptFreeTlvNoDeclTest.jsp:19:1: No tag library is mapped to absolute URI: http://java.sun.com/jstl/core_rt`.
  In `compat.onedotzero`: `fmt_rt` 13 pages, `core_rt` 12, `sql_rt` 6, `xml_rt` 5. Once the
  `_rt` TLDs ship, these pages reach the actions and may then meet the other causes below.
- TLV (decision 5): the four `scrfree` failures use `core_rt` only. No test fails because
  Ibarra validates at page `init()` rather than at translation time: the TCK validators run and
  reject their negative pages (`TagLibraryValidator validation failed for http://java.sun.com/jstltck/nodecl: JSP page contains 1 declaration.`,
  and the same for `noscr` and `noexpr`), and `spec.etu.tlv.permitted` passes 2/2. The category
  "Ibarra: validation at init, not translation time (known deviation)" therefore has no test in
  this run.

### Constant `${…}` on a deferred-value attribute — Peano defect (COMP-010)

- Tests (32): `spec.sql.param` 20 (every test but one), `spec.sql.result` 4
  (`positiveResultGetColumnNamesTest`, `…GetRowsByIndexTest`, `…GetRowsLowerCaseTest`,
  `…GetRowsUpperCaseTest`), `spec.sql.transaction` 4 (`positiveTxUpdateRollbackTest`,
  `positiveTxQueryParamCommitTest`, `positiveTxUpdateCommitTest`, `positiveTxUpdateParamCommitTest`),
  `spec.sql.update` 3 (`positiveUpdateNoVarAttributeTest`, `positiveUpdateRowsResultTest`,
  `positiveUpdateSQLAttributeTest`), `spec.core.iteration.foreach#positiveForEachEndLTBeginTest`.
- Evidence (the same for all 32, HTTP 500):
  ```
  Page translation failed: /positiveForEachEndLTBeginTest.jsp:22:1: constructor Page_…$Expression0 in class tck.application.ibarra_generated.Page_…$Expression0 cannot be applied to given types;
    required: java.lang.Class
    found:    jakarta.el.ELContext,java.lang.Class<java.lang.Object>
  ```
  The line named is a constant expression on an attribute Bodoni's `c.tld` declares
  `rtexprvalue` with `<deferred-value>`, as the JSTL 3.0 TLD does: `<c:set var="expectedRows" value="${1}"/>`
  in the `sql` pages, `<c:forEach items="${'one,two,three'}" begin="2" end="1">` in `foreach`.
- Cause (Peano, COMP-010): Peano's `ExpressionCompiler.compile` gave a constant expression only a
  `(Class)` constructor, while every other compiled expression also has `(ELContext, Class)`. The
  symptom appears in Ibarra-generated pages, because Ibarra's `PageGenerator.deferredAttribute`
  calls `new …$ExpressionN(ELContext, Class)` for any `${…}` on such an attribute. Not a Bodoni
  defect.
- Fixed in [Vidocq/peano#61](https://codefloe.com/Vidocq/peano/pulls/61) (merged 2026-10-24): constant expressions get the
  `(ELContext, Class)` constructor; Ibarra regression test IBARRA-156 on branch
  `fix/deferred-constant-ctor`. The 32 tests stay counted as failing here: the run predates the
  fix.

### Translation-time constraints not enforced — Bodoni defect (BOD-006)

- Tests (9): `spec.core.conditional.cwo` and `spec.xml.xconditional.xcwo`
  `#negativeCWONoWhenActionsTest`, `#negativeCWOWhenBeforeOtherwiseTest`;
  `spec.xml.xconditional.xif#negativeIfScopeVarTest`; `spec.xml.xmlcore.parse`
  `#negativeParseScopeVarSyntaxTest`, `#negativeParseScopeVarDomSyntaxTest`;
  `spec.fmt.format.fmtdate#negativeFDScopeNoVarTest`; `spec.fmt.format.fmtnum#negativeFNScopeNoVarTest`.
- Evidence: each expects an error and gets `HTTP/1.1 200 OK`. `negativeCWOWhenBeforeOtherwiseTest`
  prints "Body improperly processed."; `negativeFNScopeNoVarTest` prints `1234`;
  `negativeFDScopeNoVarTest` prints the date. Tags 3.0 §2.7 requires constraints to be
  reported at translation time; Bodoni's TLDs have no `<validator>` and no `<tei-class>`, and
  these handlers do not check at run time either. `negativeParseScopeVarDomSyntaxTest` requests
  the same page as `negativeParseScopeVarSyntaxTest` (TCK client), so one fix covers both.
- See [BUG.md BOD-006](../BUG.md).

### `LocalizationContext` unreadable under `peano.reflection=deny` — Bodoni defect (BOD-007)

- Tests (3): `spec.fmt.i18n.setbundle#positiveSetBundleFallbackLocaleTest`,
  `#positiveSetBundleLocaleConfigurationTest`, `#positiveSetBundleBasenameNullEmptyTest`.
- Evidence (HTTP 500, `server.log`): `jakarta.el.ELException: Reflection fallback denied for jakarta.servlet.jsp.jstl.fmt.LocalizationContext; register a generated accessor provider or a custom resolver`,
  from `${rBundle.resourceBundle}` and `${bundle2.resourceBundle == null}`. `bodoni-peano` has
  accessor tables for `LoopTagStatus` and `Result` only.
- See [BUG.md BOD-007](../BUG.md).

### `fmt:message` with `bundle` does not set the response locale — Bodoni defect (BOD-008)

- Tests (2): `spec.fmt.i18n.responseencoding#positiveResponseEncodingTest`,
  `#positiveResponseSetCharEncodingAttrTest`.
- Evidence: the fourth request of each (`?action=message`) answers `200` with the right body
  (`en message`) but without the expected header (`setlocale: en`, respectively
  `charset: attribute set`). The page passes a `LocalizationContext` to
  `fmt:message bundle='<%= … %>'`; §8.4 makes "Any <fmt:message> action that establishes an i18n
  localization context" set the response locale. The `locale`, `bundle` and `setbundle` requests
  of the same tests pass. Whether a `bundle=` attribute "establishes" a localization context is
  not spelled out by §8.4; the TCK is the arbiter, and it expects the response locale to be set.
- See [BUG.md BOD-008](../BUG.md).

### `c:forTokens` deferred `items` — Bodoni defect (BOD-009)

- Test (1): `spec.core.iteration.fortokens#positiveForTokensDeferredValueTest`.
- Evidence (HTTP 500): `Page translation failed: /positiveForTokensDeferredValueTest.jsp:29:4: Deferred action attributes require tag-handler support`,
  on `<c:forTokens var="item" items="#{golf}" delims="[]">`. Bodoni's `c.tld` declares
  `c:forTokens` `items` without `<deferred-value>`; §6.3 "Deferred Values" applies the
  `c:forEach` rules.
- See [BUG.md BOD-009](../BUG.md).

### Pre-CLDR date-time input — harness (TCK input on JDK 20+)

- Tests (2): `spec.fmt.format.parsedate#positivePDVarTest`, `#positivePDScopeTest`.
- Evidence (HTTP 500): `jakarta.servlet.jsp.JspException: fmt:parseDate: cannot parse 'Nov 21, 2000 3:45:02 AM'`
  / `java.text.ParseException: Unparseable date: "Nov 21, 2000 3:45:02 AM"`. Both pages call
  `<fmt:setLocale value="en_US"/>`, then `fmt:parseDate` with the default date and time styles.
  On JDK 25 (CLDR locale data, the `COMPAT` provider is removed) the `en_US` default date-time
  pattern is `MMM d, y, h:mm:ss<U+202F>a`: a comma after the year and a narrow no-break space
  before `AM`. `DateFormat.parse` rejects the TCK string in lenient and non-lenient mode alike
  (one-off check, error index 12), so no implementation on this JDK parses it. The TCK ships
  `…Java20Plus` variants of ten other `parsedate` pages for this reason, not of these two. The
  result is the same with `TZ=UTC` and does not depend on the machine locale (the page sets
  `en_US`).
- Not a Bodoni defect; nothing to change in the runner either. Proposed TCK issue title:
  "Tags TCK: `positivePDVarTest` and `positivePDScopeTest` parse 'Nov 21, 2000 3:45:02 AM',
  which the JDK 20+ `en_US` default style no longer accepts (no Java20Plus variant)".

### Passing tests worth a note

The TCK checks only the status of a negative test, so these pass through an error other than
the one the page describes:

- `spec.sql.update#negativeUpdateVarAttributeTest` and `#negativeUpdateScopeVarAttributeTest`
  import the JSTL 1.0 EL URIs `http://java.sun.com/jstl/core` and `http://java.sun.com/jstl/sql`;
  they pass because the URI is not mapped (`No tag library is mapped to absolute URI: http://java.sun.com/jstl/sql`).
  The category "known: JSTL 1.0 EL URIs not shipped — maintainer to rule (decision 4 covers _rt
  only)" therefore has no failing test, but shipping those URIs would make these two pages
  reach the actions.
- `spec.etu.tlv.scrfree#negativeScriptFreeTlvNoRTExprTest` passes on the unmapped `core_rt` URI,
  not through the TCK validator.
- `spec.fmt.format.parsedate#negativePDScopeNoVarTest` and
  `spec.fmt.format.parsenum#negativePNScopeNoVarTest` pass on "no formatting locale to parse …",
  not on the scope-without-var constraint (BOD-006 covers both actions).
- The `sql:` negative tests on `var` / `scope` / `dataSource`, `c:when` / `c:otherwise` /
  `x:when` / `x:otherwise` outside `choose`, and the invalid-`scope` tests pass through a
  run-time `JspTagException` instead of a translation-time error (§2.7).
- `spec.xml.xconditional.xif#negativeIfInvalidScopeTest`, `spec.xml.xmlcore.xset#negativeSetInvalidScopeTest`
  and `spec.sql.transaction#negativeTxIsolationLevelAttributeTest` pass on a translation error
  the page itself causes (`x:if test=…` without `select`, `x:set` without `var`, an
  `isolationLevel` attribute that `sql:transaction` does not declare).

## Open interpretations

- **Empty `x:forEach` `select`** (B3): not exercised by the suite. `positiveForEachSelectEmptyTest.jsp`
  is packaged in the `xforeach` WAR, but `spec.xml.xconditional.xforeach.JSTLClientIT` has no
  method that requests it (9 methods, all passing; `javap` of the 11.0.3 class). The page
  comment expects no iteration; Tags 3.0 §12.6 "Null & Error Handling" says "If select is
  empty, a JspException is thrown", which is what Bodoni does. Conclusion: Bodoni follows the
  spec text; not a defect, no `BUG.md` entry. If the maintainer prefers the page's reading, it is
  a change of interpretation, not a fix.
- **`startRow < 0` is an error** (B4): not exercised by the suite.
- **A blank `jakarta.servlet.jsp.jstl.sql.maxRows` setting means no limit** (B4): not exercised
  by the suite.
- **`limitedByMaxRows` is false for a result of exactly `maxRows` rows** (B4): not exercised by
  the suite.

## Upstream items

B5 files no issue; the COMP-010 fix went through Vidocq/peano#61.

- Peano: COMP-010, constant expressions lacked the `(ELContext, Class)` constructor (32 tests),
  fixed in [Vidocq/peano#61](https://codefloe.com/Vidocq/peano/pulls/61) (merged 2026-10-24); Ibarra
  regression test IBARRA-156 on branch `fix/deferred-constant-ctor`.
- Ibarra: none of its own in this run (the Peano item shows up in Ibarra-generated pages).
- Foy: none.
- Jakarta Tags TCK: "`positivePDVarTest` and `positivePDScopeTest` parse 'Nov 21, 2000 3:45:02 AM',
  which the JDK 20+ `en_US` default style no longer accepts (no Java20Plus variant)" (2 tests).

## Reruns

Each of the 17 failing classes was rerun alone (`./run-official-tck-tags-3.0.sh
-Dtest=<class> --no-prepare --no-install`, fresh database). Every rerun reports the same failing
tests as the full run, so no result depends on the database state left by earlier classes.
`spec.fmt.format.parsedate` was also rerun with `TZ=UTC`: same two errors.

## Smoke set

`./run-official-tck-tags-3.0.sh smoke` runs six whole classes at 100 % in this run, one per
library plus the URI check: `spec.core.general.remove` (`c`), `spec.etu.functions` (`fn`),
`spec.etu.uri`, `spec.fmt.i18n.param` (`fmt`), `spec.sql.setdatasource` (`sql`, through the
H2 database) and `spec.xml.xmlcore.bindings` (`x`). Rerun at commit `1fa0aab`
(2026-10-23 23:57:29 to 23:57:51 UTC, after the loopback binding fix `f7d4068` and the download
retries `eee4814`), with `./run-official-tck-tags-3.0.sh smoke`: reactor build green, runner
tests 39/39, official classes **91/91**, `BUILD SUCCESS`.
