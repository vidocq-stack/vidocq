# Persistence TCK — defects and upstream proposals

Reference suite: Jakarta Persistence TCK **3.2.1**, PostgreSQL 17, executed through
`mansart-persistence/mansart-jpa-tck`. This document distinguishes upstream fixes, local
development adaptations and official certification results.

## TCK-BUG-001 — Delimited defaults conflict with unquoted native SQL and PostgreSQL DDL

- **Date**: 2026-10-10
- **Status**: upstream 4.0 fix merged; 3.2 backport request planned, not submitted.
- **Affected version**: 3.2.1; both affected files still contain the directive on
  `3.2-TCK` when checked on 2026-10-10.
- **Upstream issue**: [jakartaee/persistence#1175](https://github.com/jakartaee/persistence/issues/1175).
- **Upstream fix**: [1fea05e58151f10954206a15d70b18008043d3d9](https://github.com/jakartaee/persistence/commit/1fea05e58151f10954206a15d70b18008043d3d9),
  merged on 2026-09-04.
- **Specification**: Jakarta Persistence 3.2
  [section 2.15, Naming of Database Objects](https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html).
  The unit-wide `<delimited-identifiers/>` default applies to database identifiers,
  including defaulted names, and cannot be overridden.

### Reproduction and observed impact

These resources in the official spec-tests artifact enable the unit-wide default:

```text
ee/jakarta/tck/persistence/core/annotations/nativequery/orm.xml
ee/jakarta/tck/persistence/core/entitytest/apitests/orm.xml
```

The tests use unquoted native SQL, while mappings require quoted identifiers with
their declared case. The 3.2.1 PostgreSQL DDL also creates the tables without quotes:

```sql
CREATE TABLE PURCHASE_ORDER (...); -- PostgreSQL creates purchase_order
INSERT INTO "PURCHASE_ORDER" (...) VALUES (...); -- a different identifier
```

Generating quoted tables instead does not resolve the native-query mismatch: native
SQL still references the unquoted names and must not be rewritten by the provider.

Mansart's final untouched full run on 2026-10-09 at 20:43:24Z recorded **2135 tests,
2096 passes, 35 errors and 4 skips**. Of the errors, 11 belong to
`core.annotations.nativequery` and 14 to `core.entitytest.apitests`; the remaining
10 are independent P11 cache errors. No test identities were added or removed.
See `mansart-persistence/TCK.md` for commands and comparisons.

### Evidence from other implementations

In issue #1175, the EclipseLink report explains that Derby's uppercase folding masked
the problem in the 3.2 TCK, whereas PostgreSQL's lowercase folding exposed it.
Gavin King confirms that Hibernate's failure to apply the XML directive is the known
bug HHH-13347; enabling global quotation also exposes the faulty fixtures.
These statements concern the versions discussed upstream, not every provider version.

### Requested 3.2 backport

Backport the semantic change of commit `1fea05e` to `3.2-TCK`: remove the following
otherwise unused block from **both affected mapping files**, retaining their 3.2
namespace, schema version and all other mappings:

```xml
<persistence-unit-metadata>
    <persistence-unit-defaults>
        <delimited-identifiers/>
    </persistence-unit-defaults>
</persistence-unit-metadata>
```

Request clarification of the maintenance release and approved challenge/patch procedure
for users of the published 3.2.1 suite. No backport request has been filed by this work.
Neither fixture actually asserts the delimited-identifier contract; dedicated TCK
coverage should exercise quoted identifiers with matching schema and native SQL.

### Maintainer-directed interim adoption

The maintainer has decided to adopt the upstream fixture fix for local development
without waiting for publication of the 3.2 backport. Mansart does not intend to
implement the full Web Profile and will not request a certification, so the local
patched score serves development only. **Applied and tested on 2026-10-10** (see
"Application record" below); the official artifacts are not modified.

The adaptation must remove the block from the two **test resources**, not teach
Mansart to ignore `<delimited-identifiers/>`. The provider must retain the existing
case-preserving behavior and its regression tests.

Keep the original downloaded artifacts and checksum-verified bundle intact. A runner
adaptation should use an explicitly identified derived artifact, validate the exact
two-resource change, preserve every test and other resource, and record patch provenance.
Report patched runs separately from untouched official runs; do not present a locally
patched score as an official certification result without upstream approval.
Recheck the full test identities and errors after application: an improved score is an
expected outcome, not a result already measured.

### Application record (2026-10-10)

- **Tool**: `mansart-persistence/mansart-jpa-tck/tools/tck_fixture_patch.py` (Python
  standard library), invoked by `run-official-tck-persistence-3.2.sh`. Default mode
  `TCK_FIXTURES=patched`; `TCK_FIXTURES=official` runs the untouched jar (raw reproduction).
- **Input** (read only): installed `persistence-tck-spec-tests-3.2.1.jar`, SHA-256
  `a6ad07d4442aace8630348f7aae990d31d79a31692e14ed463f482c58b364024`, identical to the jar of
  the checksum-verified bundle (`1d282675f43fa13cf8ab2537d6dbfb1e1c95f7b838ab7cdd053e185c363a6519`).
  Re-hashed unchanged after every run.
- **Output**: `target/tck-fixtures/persistence-tck-spec-tests-3.2.1-tck-bug-001.jar`, SHA-256
  `1f7e82bac32a04a903d41fc5755f5c3f348a08865ccf89792ee28d08b1e4d9a4` (deterministic: identical
  on regeneration), plus `fixture-provenance.json` (upstream issue, commit
  `1fea05e58151f10954206a15d70b18008043d3d9`, reason, checksums). 1126 entries kept in order;
  only the two resources differ:
  - `core/annotations/nativequery/orm.xml`: `fdf87f63…` → `0afff5fbe2f646e33868106c7f9b4ef6e2c22bcc0980a324861905eab6995126`
  - `core/entitytest/apitests/orm.xml`: `64810777…` → `b01999cff62cc08375292043b67cab20a8a191038720d36c2c576da0985dc767`
  The exact five upstream lines are removed; namespace, `version="3.2"`, schema location
  and all other mappings are kept. Unknown checksums, an absent (already removed),
  duplicated or reformatted block, richer unit defaults, signed jars and duplicate or
  missing entries abort the run (exit 2) instead of silently passing through.
- **Wiring**: the runner POM takes the spec-tests jar from `${tck.spec.tests.jar}`
  (system scope, test-only out-of-reactor runner, same GAV; `dependency-gatekeeper`
  approved). Test classes and the `orm.xml` that the TCK copies into its ShrinkWrap
  deployments therefore come from the derived jar; the deployed
  `jpa_core_annotations_nativequery.jar` carries the patched `META-INF/orm.xml`. Every
  failsafe report's `java.class.path` is checked; the report is labelled PATCHED or
  UNMODIFIED with this provenance.
- **Tool tests**: `python3 -m unittest discover -s tools -v` — 30 tests OK, written red first.
- **Results** (Temurin 25.0.4+7-LTS, Maven 3.9.16, PostgreSQL 17, provider unchanged since `bb37a0a`):
  patched **2135 tests, 2121 passed, 10 errors, 4 skipped**; untouched 2096 / 35 / 4,
  identical test by test to the preserved run of 2026-10-09. Compared by execution, class
  and test name: 0 added, 0 missing, 0 regressions, exactly the 25 fixture errors
  (11 `nativequery`, 14 `apitests`) now pass. The 10 remaining errors are P11 cache tests.
  Detail: `mansart-persistence/TCK.md`.
- **Exit**: drop the patch when an official 3.2.x suite ships the fix (the pinned
  checksums will then reject the new jar, forcing a review).
