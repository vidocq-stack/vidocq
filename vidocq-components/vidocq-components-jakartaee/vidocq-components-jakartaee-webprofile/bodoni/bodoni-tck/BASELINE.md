# Jakarta Tags official TCK baseline (jakarta.tck:tags-tck:11.0.3)

Two full runs are recorded: the first baseline of B5 (452/541) and the run after the TCK fixes
of branch `fix/tck-baseline` (541/541). The sections below keep the first run's text as
recorded; the second run adds a column to the tables and dated corrections.

**First run (B5 baseline).** Date: 2026-10-24 (01:10:57 to 01:12:25 CEST, 2026-10-23 23:10:57
to 23:12:25 UTC, reactor build included). Bodoni `0.4.0-SNAPSHOT` at commit `1637d78` (branch `feat/b5-tck`), Temurin
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

**After the TCK fixes.** Date: 2026-10-24 (02:55:40 to 02:57:10 CEST, 00:55:40 to 00:57:10
UTC, reactor build included). Bodoni `0.4.0-SNAPSHOT` at commit `174a887` (branch
`fix/tck-baseline`, rebased on `main` `a6e1315`), same JVM (`java.runtime.version` 25+36-LTS),
Maven, machine, zone (`Europe/Paris`, `TZ` unset) and locale (`fr_FR`, `LANG=en_US.UTF-8`) as
the first run. Command `./run-official-tck-tags-3.0.sh all --no-prepare` (the DDL was already
extracted; Bodoni installed by the script). Peano and Ibarra were installed from their `main`
just before the run (`./mvnw -ntp -q install -DskipTests`), because the jars in `~/.m2` predated
the merge commits; the published central-snapshots builds of both predate the merges too.
Resolved snapshots (same `dependency:list` and checksum method):

| Artifact | Build |
|---|---|
| `ibarra-arquillian`, `ibarra-api`, `ibarra-core`, `ibarra-compiler`, `ibarra-foy` | local install of Ibarra `main` at `d67bca4` (IBARRA-156, [Vidocq/ibarra#64](https://codefloe.com/Vidocq/ibarra/pulls/64)), 2026-10-24 02:54 CEST; `maven-metadata-local.xml` `lastUpdated` 20261024005447 |
| `peano-api`, `peano-core`, `peano-compiler` | local install of Peano `main` at `df3b997` (COMP-010, [Vidocq/peano#61](https://codefloe.com/Vidocq/peano/pulls/61)), 2026-10-24 02:54 CEST; `lastUpdated` 20261024005444 (central-snapshots still at 20261022203832, before the fix) |
| `foy-core` | `0.4.0-20261023.171509-58` (central-snapshots, checksum match), as in the first run |
| `chappe-core` | `0.4.0-20261023.163359-37` (central-snapshots, checksum match), as in the first run |
| H2 | 2.3.232 |

The script's reactor build ran the unit tests (`bodoni-core` 1066 + 3, `bodoni-peano` 55) and
the runner's own tests (39/39). Log and reports: `bodoni-tck/target/full-run.log` and
`bodoni-tck/target/surefire-reports/` (not committed; a later run replaces the reports).

This measures Bodoni on Ibarra + Foy + Peano with the Tags tests of the Jakarta EE 11 Platform
TCK; it is not a certification: the formal `jakarta-tags-tck-3.0.0.zip` (JavaTest) was not run.
The zip is read for one file only, the Derby DDL of the `sql` tests (pinned SHA-256, see the
script). Every host runs with `-Dpeano.reflection=deny`.

```bash
sdk env
./run-official-tck-tags-3.0.sh all
```

## Recorded results

| Measure | First run (2026-10-24, `1637d78`) | After TCK fixes (2026-10-24, `174a887`) |
|---|---:|---:|
| Official classes selected | 50 | 50 |
| Declared `@Test` methods in the official binary | 541 | 541 |
| Official Surefire entries reported | 541 | 541 |
| Passed | 452 | 541 |
| Assertion failures (Surefire `failure`) | 0 | 0 |
| Method errors (Surefire `error`) | 89 | 0 |
| Class-level (deployment) errors | 0 | 0 |
| Skipped | 0 | 0 |
| Classes at 100 % | 33/50 | 50/50 |
| Pass rate | **452/541 (83.5 %)** | **541/541 (100 %)** |

First run: Surefire reports `Tests run: 541, Failures: 0, Errors: 89, Skipped: 0`. Every class
deploys, so the 541 entries match the 541 declared methods. The TCK client reports every failed
check as an `error` (`java.lang.Exception: [BaseUrlClient] … failed!`), never as a `failure`.
After the fixes: `Tests run: 541, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

By library, first run: `c` (`spec.core`) 111/115, `fn` + extensions (`spec.etu`) 77/81, `fmt`
114/123, `sql` 86/117, `x` (`spec.xml`) 62/67, JSTL 1.0 compatibility (`compat.onedotzero`) 2/38.
After the fixes: `c` 115/115, `spec.etu` 81/81, `fmt` 123/123, `sql` 117/117, `x` 67/67,
`compat.onedotzero` 38/38.

This is still a compatibility measurement on Bodoni + Ibarra + Foy + Peano, not a certification:
the formal `jakarta-tags-tck-3.0.0.zip` was not run.

By root cause (89 errors in the first run):

| Root cause | Owner | Tests | After the TCK fixes |
|---|---|---:|---|
| JSTL 1.0 `_rt` URIs not shipped | known decision (2026-10-23) | 40 | fixed: the four `*-1_0-rt.tld` (`f8cdcf6`, Rulings 1–3) |
| Constant `${…}` on a deferred-value attribute: the Peano expression lacks the `(ELContext, Class)` constructor (COMP-010) | Peano | 32 | fixed upstream: Peano `df3b997` (Vidocq/peano#61); also covers `compat.onedotzero#positiveDateParamQueryTimestampTest` once `_rt` ships (33 in all) |
| Translation-time constraints not enforced (BOD-006) | Bodoni | 9 | fixed: tag library validators (`3c6c49f`, `a298d15`, `174a887`, Ruling 4) |
| `LocalizationContext` unreadable under `deny` (BOD-007) | Bodoni | 3 | fixed: `bodoni-peano` accessor table (`26540cc`, `758d4bf`) |
| `fmt:message bundle` does not set the response locale (BOD-008) | Bodoni | 2 | fixed (`868dcbd`, Ruling 6) |
| `c:forTokens` deferred `items` (BOD-009) | Bodoni | 1 | fixed (`d4bc5ef`, `33e01df`) |
| TCK page expects the pre-CLDR `en_US` date-time format | harness (TCK input on JDK 20+) | 2 | **corrected**: a Bodoni defect, BOD-010 (trailing text after a complete date), fixed with BOD-011 (`e77985f`, Ruling 5); see the correction under "Pre-CLDR date-time input" |

How the count moved on the branch (full runs of the task reports): 452 → 525 after the `_rt`
TLDs, BOD-009 and a Peano snapshot with COMP-010 → 534 after the validators (BOD-006) → 539
after BOD-007 and BOD-008 → 541 after BOD-010 / BOD-011. The recorded 541/541 is the run of
`174a887` above, on Peano and Ibarra `main`.

## Per class

Class names are relative to `com.sun.ts.tests.jstl.`, each `….JSTLClientIT`.

The columns Passed to Triage are the first run's. "After fixes" is the run of `174a887`: the
status, then what recovered the class's failing tests; no class has a residual cause.

| Class | Tests | Passed | Failures | Errors | Skipped | Status | Triage | After fixes |
|---|---:|---:|---:|---:|---:|---|---|---|
| `compat.onedotzero` | 38 | 2 | 0 | 36 | 0 | FAIL | known: _rt TLDs pending (36) | PASS 38/38: `_rt` TLDs (35), plus COMP-010 for `positiveDateParamQueryTimestampTest` |
| `spec.core.conditional.cwo` | 9 | 7 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-006) (2) | PASS 9/9: BOD-006 |
| `spec.core.conditional.iftag` | 6 | 6 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.general.catchtag` | 2 | 2 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.general.outtag` | 7 | 7 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.general.remove` | 1 | 1 | 0 | 0 | 0 | PASS | smoke | PASS |
| `spec.core.general.set` | 8 | 8 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.iteration.foreach` | 24 | 23 | 0 | 1 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (1) | PASS 24/24: COMP-010 |
| `spec.core.iteration.fortokens` | 15 | 14 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-009) (1) | PASS 15/15: BOD-009 |
| `spec.core.iteration.loopstatus` | 1 | 1 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.urlresource.importtag` | 23 | 23 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.urlresource.param` | 7 | 7 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.urlresource.redirect` | 4 | 4 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.core.urlresource.url` | 8 | 8 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.etu.config` | 6 | 6 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.etu.functions` | 64 | 64 | 0 | 0 | 0 | PASS | smoke | PASS |
| `spec.etu.tlv.permitted` | 2 | 2 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.etu.tlv.scrfree` | 8 | 4 | 0 | 4 | 0 | FAIL | known: _rt TLDs pending (4) | PASS 8/8: `_rt` TLDs |
| `spec.etu.uri` | 1 | 1 | 0 | 0 | 0 | PASS | smoke | PASS |
| `spec.fmt.format.fmtdate` | 14 | 13 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-006) (1) | PASS 14/14: BOD-006 |
| `spec.fmt.format.fmtnum` | 19 | 18 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-006) (1) | PASS 19/19: BOD-006 |
| `spec.fmt.format.localecontext` | 4 | 4 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.format.parsedate` | 18 | 16 | 0 | 2 | 0 | FAIL | harness: TCK input vs JDK 20+ locale data (2) | PASS 18/18: BOD-010 (the triage was wrong, see the correction below) |
| `spec.fmt.format.parsenum` | 14 | 14 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.format.settimezone` | 6 | 6 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.format.timezone` | 2 | 2 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.i18n.bundle` | 6 | 6 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.i18n.message` | 17 | 17 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.i18n.param` | 2 | 2 | 0 | 0 | 0 | PASS | smoke | PASS |
| `spec.fmt.i18n.requestencoding` | 4 | 4 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.i18n.resourcelookup` | 2 | 2 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.fmt.i18n.responseencoding` | 2 | 0 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-008) (2) | PASS 2/2: BOD-008 |
| `spec.fmt.i18n.setbundle` | 7 | 4 | 0 | 3 | 0 | FAIL | Bodoni defect (BOD-007) (3) | PASS 7/7: BOD-007 |
| `spec.fmt.i18n.setlocale` | 6 | 6 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.sql.param` | 21 | 1 | 0 | 20 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (20) | PASS 21/21: COMP-010 |
| `spec.sql.query` | 26 | 26 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.sql.result` | 8 | 4 | 0 | 4 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (4) | PASS 8/8: COMP-010 |
| `spec.sql.setdatasource` | 22 | 22 | 0 | 0 | 0 | PASS | smoke | PASS |
| `spec.sql.transaction` | 19 | 15 | 0 | 4 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (4) | PASS 19/19: COMP-010 |
| `spec.sql.update` | 21 | 18 | 0 | 3 | 0 | FAIL | Peano defect (COMP-010): constant deferred attribute (3) | PASS 21/21: COMP-010 |
| `spec.xml.xconditional.xcwo` | 8 | 6 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-006) (2) | PASS 8/8: BOD-006 |
| `spec.xml.xconditional.xforeach` | 9 | 9 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.xml.xconditional.xif` | 7 | 6 | 0 | 1 | 0 | FAIL | Bodoni defect (BOD-006) (1) | PASS 7/7: BOD-006 |
| `spec.xml.xmlcore.bindings` | 1 | 1 | 0 | 0 | 0 | PASS | smoke | PASS |
| `spec.xml.xmlcore.parse` | 15 | 13 | 0 | 2 | 0 | FAIL | Bodoni defect (BOD-006) (2) | PASS 15/15: BOD-006 |
| `spec.xml.xmlcore.types` | 2 | 2 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.xml.xmlcore.xout` | 4 | 4 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.xml.xmlcore.xset` | 6 | 6 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.xml.xtransform.param` | 3 | 3 | 0 | 0 | 0 | PASS |  | PASS |
| `spec.xml.xtransform.transform` | 12 | 12 | 0 | 0 | 0 | PASS |  | PASS |

No class reported `ERROR (deployment)`, in either run.

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
- After the TCK fixes (2026-10-24): Peano `main` `df3b997` carries the fix, and the Ibarra
  regression test merged as [Vidocq/ibarra#64](https://codefloe.com/Vidocq/ibarra/pulls/64)
  (`d67bca4`). On those builds the 32 tests pass, and so does
  `compat.onedotzero#positiveDateParamQueryTimestampTest`, the 33rd page with the same
  constructor signature, reached once the `_rt` TLDs ship. No Bodoni change was needed.

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
  accessor tables for `LoopTagStatus` and `Result` only (a `LocalizationContext` table was added by BOD-007).
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
- **Correction (2026-10-24, TCK fixes).** The diagnosis above is wrong; the text is kept as the
  first run recorded it. Both pages call `fmt:parseDate` without `type`, so the type is `date`
  (§9.10), not `both`, and the date-time pattern is never used. On JDK 25 the `en_US` date
  style parses `Nov 21, 2000` and stops at index 12, the error index seen above; Bodoni then
  refused the text left over (`position.getIndex() != text.length()`). The JDK's own
  `DateFormat.parse(String)` accepts the value (non-lenient, `Tue Nov 21 2000`). The cause is
  Bodoni's trailing-text rule, [BUG.md BOD-010](../BUG.md), fixed in `e77985f` (PLAN.md
  Ruling 5) together with BOD-011, the U+202F / U+00A0 retry for style-based formats. The
  proposed TCK issue is withdrawn (see "Upstream items"). `spec.fmt.format.parsedate` passes
  18/18 after the fix.

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

After the TCK fixes (2026-10-24):

- Bodoni's tag library validators (BOD-006, Ruling 4) now reject 22 negative pages at page
  `init()` on Ibarra, as §2.7 asks ("TagLibraryValidator validation failed for jakarta.tags.…"
  in the deployments' `server.log`): besides the 9 tests of BOD-006, `parsedate` /
  `parsenum` scope without `var` (`negativePDScopeNoVarTest`, `negativePNScopeNoVarTest`), the
  `c:` and `x:` `when` / `otherwise` outside `choose` (`negativeCWOWhenNoParentTest`,
  `negativeCWOOtherwiseNoParentTest`), `x:parse` with an invalid `scope`, and seven `sql:`
  pages (`negativeQueryNoVarAttributeTest`, `negativeQueryVarAttributeTest`,
  `negativeQueryScopeAttributeTest`, `negativeUpdateScopeAttributeTest`,
  `negativeSetDataSourceScopeAttributeTest`, `negativeTxQueryDataSourceAttributeTest`,
  `negativeTxUpdateDataSourceAttributeTest`). The run-time checks stay as defence in depth.
- Constraints on dynamic attributes stay run-time errors (§2.7, "unless they operate on a
  dynamic attribute value"): `spec.sql.query#negativeQueryMaxRowsAttributeTest2` catches the
  `JspException` of `maxRows="-5"` at run time and fails if a validator reports it.
- The two `spec.sql.update` pages on the JSTL 1.0 EL URIs still pass through the unmapped URI:
  those URIs are not shipped (Ruling 3), and the constraints the pages target are tested on
  `jakarta.tags.sql` by `TagLibraryValidatorsTest`.
- `spec.etu.tlv.scrfree#negativeScriptFreeTlvNoRTExprTest` now reaches its page on the shipped
  `core_rt` URI and fails through the TCK's own validator
  (`TagLibraryValidator validation failed for http://java.sun.com/jstltck/nortexpr`).

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
  fixed in [Vidocq/peano#61](https://codefloe.com/Vidocq/peano/pulls/61) (merged 2026-10-24,
  `df3b997`); Ibarra regression test IBARRA-156 merged as
  [Vidocq/ibarra#64](https://codefloe.com/Vidocq/ibarra/pulls/64) (`d67bca4`).
- Ibarra: none of its own in this run (the Peano item shows up in Ibarra-generated pages).
- Foy: none.
- Jakarta Tags TCK: ~~"`positivePDVarTest` and `positivePDScopeTest` parse 'Nov 21, 2000 3:45:02 AM',
  which the JDK 20+ `en_US` default style no longer accepts (no Java20Plus variant)" (2 tests)~~.
  Withdrawn on 2026-10-24: the cause was Bodoni's trailing-text rule (BOD-010), not the TCK
  input. No TCK challenge is filed, so there is no `TCK.md`.

After the TCK fixes, no upstream item is open: the run of `174a887` has no failing test.

## Reruns

Each of the 17 failing classes was rerun alone (`./run-official-tck-tags-3.0.sh
-Dtest=<class> --no-prepare --no-install`, fresh database). Every rerun reports the same failing
tests as the full run, so no result depends on the database state left by earlier classes.
`spec.fmt.format.parsedate` was also rerun with `TZ=UTC`: same two errors.

During the TCK fixes each task reran the classes it fixed, then a full run (`--no-prepare`):
525/541, 534/541, 539/541 and 541/541 (see "Recorded results"). Those runs used the Peano install then in `~/.m2`,
which already had the COMP-010 fix (the last one, 2026-10-24 01:28 CEST, predates the merge
commit of Vidocq/peano#61); the recorded run of `174a887` uses Peano and
Ibarra `main` and needed no rerun.

## Smoke set

`./run-official-tck-tags-3.0.sh smoke` runs six whole classes at 100 % in this run, one per
library plus the URI check: `spec.core.general.remove` (`c`), `spec.etu.functions` (`fn`),
`spec.etu.uri`, `spec.fmt.i18n.param` (`fmt`), `spec.sql.setdatasource` (`sql`, through the
H2 database) and `spec.xml.xmlcore.bindings` (`x`). Rerun at commit `1fa0aab`
(2026-10-23 23:57:29 to 23:57:51 UTC, after the loopback binding fix `f7d4068` and the download
retries `eee4814`), with `./run-official-tck-tags-3.0.sh smoke`: reactor build green, runner
tests 39/39, official classes **91/91**, `BUILD SUCCESS`.

After the TCK fixes, at `174a887` (2026-10-24 01:01:45 to 01:02:03 UTC, right after the full
run), with `./run-official-tck-tags-3.0.sh smoke --no-install --no-prepare`: runner tests 39/39,
official classes **91/91**, `BUILD SUCCESS`.

**Smoke set extended (2026-10-24, controller ruling).** So that the CI check covers the TCK
fixes, the smoke set gains two classes, now eight:

- `compat.onedotzero` (38 tests): the JSTL 1.0 `_rt` TLDs, the `sql` database path and
  COMP-010 (`positiveDateParamQueryTimestampTest`);
- `spec.core.conditional.cwo` (9 tests): the `c:choose` structure rules of the BOD-006 tag
  library validators.

`OfficialTckRunnerTest` asserts the new selection (it failed on the old script first). Rerun at
the head of `fix/tck-baseline` (2026-10-24 01:06:53 to 01:07:22 UTC) with
`./run-official-tck-tags-3.0.sh smoke --no-prepare`: reactor build green, runner tests 39/39,
official classes **138/138** (38 + 9 + 1 + 64 + 1 + 2 + 22 + 1), `BUILD SUCCESS`. The
other fixes stay guarded by their unit tests (`LegacyRtTldTest`, `TagLibraryValidatorsTest`,
`LocalizationContextAccessorTableTest`, `MessageTagTest`, `ParseDateTagTest`,
`ForTokensTagTest`), by the Ibarra IT (39/39, which now runs the validators on its pages) and
by the full run, whose 100 % PASS is the merge gate.
