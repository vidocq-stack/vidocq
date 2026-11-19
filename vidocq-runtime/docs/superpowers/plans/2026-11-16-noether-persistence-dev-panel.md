# Noether Persistence dev panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The `vidocq-runtime-noether-persistence-extension-dev` module: a live *Noether Persistence* panel whose
*Persistence unit* dropdown drives six sub-tabs: Monitoring, Model, JPQL, Explore, Indexes, DDL.

**Architecture:** The runtime extension publishes each unit's boot facts and its `EntityManagerFactory` for one boot
in a `.live` package exported to the `-dev` module only, as Mansart Data does. The `-dev` module reads
`UnitDescription` and `SchemaScripts` (noether#94) through `EntityManagerFactory.unwrap`, and answers with the
console's results:
- graph for Model (part 4);
- rows with a menu for Explore (part 4);
- rows and CSV for JPQL;
- a new SQL download for DDL.

Every database access is an action; `sample()` reads memory only.

**Tech Stack:** Java 25, Java Modules, Jakarta Persistence 3.2 API (Noether), JDBC `DatabaseMetaData`, the dev console
SPI, JUnit 5, the module-path ITs of `vidocq-runtime-it-noether-persistence`.

**Spec:** `noether/docs/superpowers/specs/2026-11-16-persistence-dev-panel-design.md`, part 5.

## Global Constraints

- Strict Java Modules; the `-dev` jar carries `Vidocq-Dev-Only: true` and is never packaged.
- Zero new runtime dependency.
- No reflection, no `java.lang.reflect`, no dynamic proxy.
- No thread created.
- English everywhere; say Java Modules.
- `sample()` reads memory only.
- Nothing of the application outlives a reload:
  - no static field of the `-dev` module holds a `Class`, a bean or a factory;
  - the extension's live holder is set in `onStart` and cleared in `onStop`.
- Explore writes nothing. Model, Indexes and DDL show and download only.
- The `<qualifier>`/`<scope>` binding stays out.
- SonarQube: zero new issue, new coverage ≥ 80 %. Reread `vidocq-sonar-rules-i-keep-hitting` before the first push:
  S5778, S1845, S1192, S3776, S135, S7467.

## Decisions made here (builder's calls)

- **Panel id `noether-persistence`.** It is the section id of part 2. The selector is `PanelSelector("unit",
  "Persistence unit")`. Each unit is a sample group, named after the unit.
- **Section rows keyed by the unit's name alone.** The section of part 2 writes `unit <name>`. The console puts a row
  in a group's header only when its key is the group's name (`dev-console-panels.adoc#links`), so the key becomes
  `<name>`. The log reads `notes  JTA on pool notes, …`.
- **One action set for every unit.** Each action takes the `unit` argument (allowed values: the unit names), which
  the page fills from the dropdown. Explore's `entity` argument lists the entities of every unit, and the action
  refuses an entity the chosen unit does not have. JPQL has one language, `jpql`: the union of the units' entities
  (the first unit wins on a name clash).
- **Entity values come from a projection, never reflection.** The JPA API has no getter of an attribute's value. An
  entity's packages are not open to the `-dev` module, and Noether reads them through generated code. So:
  - Explore runs `SELECT e.<id>, e.<basic>…, j1.<id>… FROM <Entity> e LEFT JOIN e.<to-one> j1 … ORDER BY e.<id>`.
  - A JPQL result whose items are entities is expanded the same way: its ids go through
    `PersistenceUnitUtil.getIdentifier` into `… WHERE e.<id> IN :ids`, in the same transaction.
  - An entity inside an `Object[]` result is written `<Entity>#<id>`.
  - Composite ids (embedded id, id class) are written `<Entity>#<id toString>`, and Explore offers no menu for them.
- **Transactions.**
  - A RESOURCE_LOCAL unit uses `EntityTransaction`.
  - A JTA unit uses the application's `jakarta.transaction.TransactionManager` (looked up through the
    `BeanManager` given to `LivePanel.start`), then `em.joinTransaction()`.
  - Reads, Explore, Indexes and the entity expansion run in a transaction that is always rolled back.
  - `jpql.write` takes `transaction`: `rollback` (first, the default) or `commit`.
- **Two JPQL actions, as in Mansart Data.**
  - `jpql.query` runs a SELECT and refuses UPDATE and DELETE, naming `jpql.write`.
  - `jpql.write` runs UPDATE and DELETE behind a confirmation.
  - Explore's menu targets `jpql.write`, and its arguments leave the unit out, since the page sends its choice:
    `{"statement": {"query": "DELETE FROM Note e WHERE e.id IN (${id})"}}`. A json argument's `fill` takes an
    object (`console.js`, `fill(values)` of the json field).
- **Indexes compare by table and columns, case-insensitively.**
  - The table name is schema-qualified as `UnitDescription.Table.name`.
  - Primary-key indexes are left out of the database side.
  - Each row: table, name, columns, unique, declared (yes/no), in the database (yes/no), and a status: `same`,
    `missing in the database`, `extra in the database`, or `different` (same name, other columns or uniqueness).
- **DDL downloads as SQL.** The console offers *Download* for CSV only. Task 1 adds `ActionResult.SQL`
  (`application/sql`), shown as text with *Download*, saved as `<action id>-<yyyyMMdd-HHmmss>.sql`.

## Review Focus

1. **A syntax error in JPQL, or a constraint violation on a write.** Expect an `error` result, cut by `Failures`. No
   EntityManager left open, and no transaction left active. Test in Task 5 (`aBadQueryAnswersAnErrorAndLeavesNothingOpen`).
2. **A JTA unit's write in `rollback` mode leaves no row changed, and in `commit` mode changes them.** Test in Task 8,
   on the IT's JTA unit.
3. **An entity with a composite id.** Explore pages it sorted by its id columns, with no menu, and the JPQL
   expansion writes `<Entity>#…`. Test in Task 6 (`aCompositeIdHasNoMenu`) with a hand-built `UnitDescription`.
4. **A dev reload.** After `onStop` the live holder is empty: `units()` returns `List.of()`, the panel's `sample`
   writes `absent("units", "no persistence unit")`, and actions answer an error rather than a 500. Test in Task 2
   and Task 3.
5. **Database identifiers in another case** (H2 upper-cases `idx_note_title`). Expect the diff to say `same`, not
   both `missing` and `extra`. Test in Task 7 (`anIndexInAnotherCaseIsTheSame`).

---

### Task 1: The console downloads a SQL answer

**Files:**
- Modify: `vidocq-runtime-devconsole-spi/.../PanelAction.java` (`ActionResult.SQL`, the content type check and its
  message)
- Modify: `devconsole/console.js` (`isDownloadType`, the file name)
- Modify: `devconsole/editor-core.js`, if the file name lives there
- Docs: `dev-console-panels.adoc#action-result`, `dev-console.adoc`, `whats-new.adoc`
- Test: `ActionResultTest`, `PageTest`

**Interfaces:**
- Produces: `ActionResult.SQL = "application/sql"`; `new ActionResult(summary, ActionResult.SQL, script, false, null)`
  is shown as text with *Download*.

- [ ] Write `ActionResultTest.aSqlBodyIsAcceptedAndNamed`: `ActionResult.SQL` equals `"application/sql"`, and a
  result of that type keeps its body. Then a PageTest or core test: the download name of `ddl.create` on
  2026-11-16 14:00:00 is `ddl.create-20261116-140000.sql`, and of a CSV it stays `.csv`.
- [ ] Run them and see them fail. Implement: generalise the CSV download (`isCsvType`, `csvFileName`) to a download
  for CSV and SQL, with the extension taken from the type. Run them and see them pass.
- [ ] Docs, and the content type message: "… application/x-graph+json or application/sql".
- [ ] Commit `feat(devconsole): a SQL answer is shown with a Download button`.

### Task 2: The Noether extension publishes its units to its dev companion

**Files:**
- Create: `vidocq-runtime-noether-persistence-extension/src/main/java/.../noether/persistence/live/NoetherPersistenceLive.java`
- Create: `.../src/main/resources/META-INF/vidocq/dev-module`, containing `vidocq-runtime-noether-persistence-extension-dev`
- Modify: `module-info.java`, adding `exports …noether.persistence.live to …noether.persistence.dev;`
- Modify: `NoetherPersistenceExtension` (publish in `onStart`, clear in `onStop`), `PersistenceSection` (row key)
- Test: `NoetherPersistenceLiveTest`, `PersistenceSectionTest`, `NoetherPersistenceReportModulePathTest`

**Interfaces:**
- Produces:
  ```java
  public final class NoetherPersistenceLive {
      public record Unit(String name, boolean jta, String pool, String schemaAction, EntityManagerFactory factory) {}
      public static List<Unit> units();          // in boot order, immutable, empty before onStart and after onStop
      public static void publish(List<Unit> units);
      public static void clear();
  }
  ```
  Model it on `MansartDataLive` (read it first). `pool` is `null` for the default pool.

- [ ] Tests, red first:
  - `units()` is empty, then holds what `publish` gave (an immutable copy), then is empty after `clear()`;
  - the jar names its dev companion: copy `MansartDataSectionTest.theJarNamesItsDevCompanionAndIsNoPanel`;
  - `PersistenceSectionTest` expects the row key `notes`.
- [ ] Implement; `onStart` publishes from the factories it already deduplicates.
- [ ] Commit `feat(noether): publish the persistence units to the dev console companion`.

### Task 3: The `-dev` module and its Monitoring tab

**Files:**
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-noether-persistence-extension-dev/`,
  with `pom.xml` (copy Mansart Data dev's: manifest `Vidocq-Dev-Only`, surefire `useModulePath=false`),
  `module-info.java`, `META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`
- Create: `.../noether/persistence/dev/PersistenceLivePanel.java`
- Register the module in five places, all in this task (`ApplicationBomAndParentTest` fails otherwise):
  - the aggregator's `<modules>`;
  - the root `dependencyManagement`;
  - `vidocq-runtime-bom`;
  - `vidocq-runtime-architecture-tests/pom.xml` (test scope);
  - `RuntimeClasses`, if it lists modules.
- Test: `PersistenceLivePanelTest`

**Interfaces:**
- Consumes: `NoetherPersistenceLive.units()` (Task 2).
- Produces: `PersistenceLivePanel implements LivePanel`, with `id()` = `"noether-persistence"` and `selector()` =
  `Optional.of(new PanelSelector("unit", "Persistence unit"))`. It sets the `BeanManager` in `start(context)` and
  clears it in `stop()`. It has the package-private constructor `PersistenceLivePanel(Supplier<List<Unit>> units)`
  for tests.

- [ ] Test: with two units, `sample` writes the groups `notes` and `ledger`, in that order. Each group has the gauges
  `entities`, `embeddables`, `named queries` and `indexes` (`Unit.COUNT`), read from `unwrap(UnitDescription)`. With
  none, it writes `absent("units", "no persistence unit")`. Use a hand-built `UnitDescription` and a minimal
  `EntityManagerFactory` stub.
- [ ] Implement. `actions()` returns `List.of()` for now.
- [ ] Commit `feat(noether-dev): a live Noether Persistence panel with one group per unit`.

### Task 4: Model

**Files:** create `ModelGraph.java`, test `ModelGraphTest`.

**Interfaces:**
- Produces: `static ActionResult ModelGraph.of(UnitDescription unit)`, and the action `model` ("Show the model",
  group `Model`, argument `unit`).

The graph:
- **A node per entity.** Its kind is `entity` or `abstract entity`. Its facts are the table, the inheritance strategy
  when not `NONE`, and then one fact per attribute: `<name>`, with the value `<javaType>, <kind>, <columns joined>`,
  plus `, owner` or `, mapped by <x>`.
- **A node per embeddable**, of kind `embeddable`.
- **A node per mapped superclass:** a `declaringClass` of an attribute that is neither an entity nor an embeddable,
  of kind `mapped superclass`.
- **Edges:**
  - from an entity to its `superEntity`, or to the mapped superclass declaring its attributes: arrow, not dashed,
    labelled `extends`;
  - one edge per owning relation, from the owner to the target. Its label is `<attr> <card>`, with the card
    `*..1`, `1..1`, `1..*` or `*..*`, and `/ <inverse attr>` when an inverse names it;
  - an `EMBEDDED` or `ELEMENT_COLLECTION` of an embeddable: dashed, labelled with the attribute.
- The summary reads `N entities, M relations`.
- A unit past 500 nodes or 2,000 edges answers an error that says so.

- [ ] Tests, red first:
  - an inheritance gives an `extends` arrow to the parent;
  - a `@MappedSuperclass` becomes a node;
  - a bidirectional relation gives one edge, labelled `author *..1 / notes`;
  - an embedded value is dashed;
  - a self-reference is an edge from and to the same node;
  - the facts list the columns.
- [ ] Implement, wire the action, and commit `feat(noether-dev): the Model tab draws the unit as a graph`.

### Task 5: JPQL

**Files:** create `JpqlLanguage.java`, `JpqlActions.java`, `UnitWork.java` (one EntityManager and one transaction per
call), `ResultRows.java`, `Failures.java` (copy Mansart's masking and cutting), `Json.java` (a reader for the `params`
object; reuse Mansart Data dev's by copy only if no shared one exists in the SPI). Tests: `JpqlLanguageTest`,
`ResultRowsTest`, `ParamsTest`.

**Interfaces:**
- Produces:
  - `JpqlLanguage.json(List<UnitDescription>) -> String`. It is a `PanelLanguage` of mode `query`, with
    `"aliases": true`, `"self": null`, `"identifierQuote": null`, the JPQL keywords and functions,
    `targetAfter` `["FROM","JOIN","UPDATE"]`, and targets by entity name, whose attributes carry `type` and `target`.
  - `UnitWork.run(Unit unit, Mode mode, Function<EntityManager, T> work) -> Outcome<T>`, where `Mode` is `READ`
    (rolled back), `ROLLBACK` or `COMMIT`.
  - `ResultRows.of(List<?> found, EntityManager em, UnitDescription unit) -> ActionResult` (`rows`, at most 100,
    `more` past it).
  - The actions `jpql.query`, `jpql.write` and `jpql.export` (CSV of the query's rows, at most 256 KiB), group `JPQL`.
    Their statement schema is Mansart's: `query` (`text/x-query`, `x-language: jpql`) and `params`
    (`x-parameters-of: query`).
- [ ] Tests, red first:
  - the language lists `Note` with `title` and `author` (target `Author`), aliases on, no self;
  - `params` `{"id": 3, "title": "x"}` becomes `Long` 3 and `"x"`;
  - a result of `Object[]` gives one column per item;
  - a result of entities is expanded through the projection of Task 6 (an IT check, in Task 8);
  - `aBadQueryAnswersAnErrorAndLeavesNothingOpen` uses a stub whose `createQuery` throws, and checks that
    `close()` was called and the transaction rolled back.
- [ ] Implement, wire, and register the language in `languages()`.
- [ ] Commit `feat(noether-dev): the JPQL tab runs queries and writes in a transaction`.

### Task 6: Explore

**Files:** create `Projection.java` and `ExploreActions.java`; test `ProjectionTest`.

**Interfaces:**
- Produces:
  - `Projection.of(UnitDescription.Entity entity) -> Projection`, with `select()` (the JPQL text without `WHERE` or
    `ORDER BY`), `columns()` (the `Column` list) and `idColumn()` (`null` for a composite id).
  - The actions:
    - `explore.page`: arguments `unit`, `entity` (allowed: every entity name), `size` (`20`, `50`, `100`) and `page`
      (pattern `[0-9]{1,6}`, default `1`). It answers `rowsWithMenu` with *Delete these rows* and *Update these
      rows*, both targeting `jpql.write`.
    - `explore.export`: the same page as CSV.

    Both are in group `Explore`.
- [ ] Tests, red first:
  - `Note(id, title, author → Author)` projects to
    `SELECT e.id, e.title, j1.id FROM Note e LEFT JOIN e.author j1`, with columns `id`, `title`, `author.id`;
  - `aCompositeIdHasNoMenu`;
  - the menu's DELETE reads `DELETE FROM Note e WHERE e.id IN (${id})`, and its UPDATE reads
    `UPDATE Note e SET e.title = e.title WHERE e.id IN (${id})`;
  - an entity of another unit is refused.
- [ ] Implement, and commit `feat(noether-dev): the Explore tab pages through an entity, its selected rows open JPQL`.

### Task 7: Indexes and DDL

**Files:** create `IndexDiff.java`, `DatabaseIndexes.java`, `IndexActions.java`, `DdlActions.java`; test
`IndexDiffTest`.

**Interfaces:**
- Produces:
  - `IndexDiff.rows(List<UnitDescription.Table> declared, List<Found> found) -> List<List<String>>`, with
    `record Found(String table, String name, List<String> columns, boolean unique)`.
  - `DatabaseIndexes.read(Connection, List<Table>) -> List<Found>`, through `DatabaseMetaData.getIndexInfo`. A
    `Connection` comes from `em.unwrap(Connection.class)` inside the READ transaction.
  - The actions:
    - `indexes.declared`: memory only;
    - `indexes.compare`;
    - `ddl.create` and `ddl.drop`: `ActionResult.SQL`, each statement ended by `;` and a line.
- [ ] Tests, red first:
  - `same`, `missing in the database`, `extra in the database`, and `different` (other columns, other uniqueness);
  - `anIndexInAnotherCaseIsTheSame`;
  - a primary-key index found is left out.
- [ ] Implement, and commit `feat(noether-dev): the Indexes and DDL tabs`.

### Task 8: End to end on the module path

**Files:**
- Modify: `vidocq-runtime-integration-tests/vidocq-runtime-it-noether-persistence/pom.xml`, adding the `-dev` module
  and the devconsole SPI in test scope
- Create: `NoetherPersistencePanelModulePathTest`

- [ ] Write the test. Boot the IT application with the dev module and find the `LivePanel` with id
  `noether-persistence`. Then check:
  - the sample groups are the units;
  - `model` answers a graph holding `Note`;
  - `jpql.query` `SELECT n FROM Note n` answers rows with `title`;
  - `jpql.write` `DELETE FROM Note n` with `rollback` leaves the count unchanged, and with `commit` empties the table,
    on the JTA unit and on a RESOURCE_LOCAL unit;
  - `explore.page` answers rows with a menu;
  - `indexes.compare` says `same` for each declared index;
  - `ddl.create` holds `CREATE TABLE`.
- [ ] Run it, fix what it finds, and commit `test(noether-dev): the panel's tabs on a booted application`.

### Task 9: Docs and the browser

- [ ] On the Noether extension's page, add a section "In the dev console": the dropdown, the six tabs, and what each
  reads (memory or the database). Add a what's-new entry, and the module in the list of `-dev` companions if
  `dev-console-panels.adoc` keeps one.
- [ ] In Chrome, under `vidocq:dev`, on an application with Noether and the web server, check the six tabs.
  - **Model:** no box or label overlaps.
  - **Explore:** tick two rows, choose *Delete these rows*: JPQL is filled, then Run with `rollback`.
  - **DDL:** Download saves a `.sql` file.
- [ ] Commit `docs(noether-dev): the Persistence panel`.
