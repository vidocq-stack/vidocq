# Dev console: a JDQL console for Mansart Data

Date: 2026-09-29. Sub-project 4 of the Mansart Data panel; sub-projects 1 (catalogue, Vidocq/vidocq#153) and 2 (running
a repository method, Vidocq/vidocq#154) are merged, sub-project 3 (live statistics) was dropped. A spike (2026-09-29)
found Mansart's runtime JDQL parser and executor usable once they no longer require a `java.lang.reflect.Method`.

## 1. Goal

In a `dev` launch, a developer types a JDQL statement in the *Mansart Data* panel and runs it against the
application's database: a query to look at data, an `UPDATE` or a `DELETE` to try a change, rolled back or kept.

- **In scope:**
  - a public Mansart API that runs a JDQL statement given as text, with named parameters, converting values to the
    compared attribute's type;
  - two console actions in a *JDQL* tab: *Query* for reads, *Update / Delete* for writes (confirmed, rollback or
    commit);
  - a generic page rule: a JSON Schema string property with `"format": "textarea"` renders as a multi-line field.
- **Out of scope:**
  - `INSERT`: JDQL (Jakarta Data 1.0) has none; inserting goes through `save` or an `@Insert` method (sub-project 2)
    and, later, a CSV import (next sub-project);
  - several data stores (`@Repository(dataStore = …)`): the console uses the default `RepositoryRuntime` bean;
  - pagination beyond a first page; joins beyond what Mansart's JDQL grammar already accepts.

## 2. Mansart: `JdqlExecutor.run`

In `mansart-data-core`, next to the existing executor:

```java
/** Runs one JDQL statement given as text, as a tool does; values are converted to the compared attribute's type. */
public static JdqlResult run(String jdql, Map<String, ?> parameters, EntityModel<?> model, RepositoryRuntime runtime)

/** Whether a statement is an UPDATE or a DELETE, read from its first keyword, without parsing it. */
public static boolean isWrite(String jdql)

/** The entity a statement names: the identifier after FROM, UPDATE or DELETE FROM; empty when none is found. */
public static Optional<String> target(String jdql)
```

`JdqlResult` is a sealed interface of records:
- `Entities(List<?> entities)` for a plain `FROM … [WHERE …] [ORDER BY …]`;
- `Rows(List<String> columns, List<Object[]> rows)` for a `SELECT a, b …` projection (one column for `SELECT a`);
- `Count(long count)` for `SELECT COUNT(this)`, an `UPDATE` and a `DELETE` (rows changed);
- `Value(Object value)` for an aggregate (`SELECT MAX(price) …`), `null` when there is no row.

**Parameters.** A named parameter `:name` takes `parameters.get("name")`; a missing one, or an entry no parameter
uses, throws `MansartDataException` naming it. Positional parameters (`?1`) are refused in `run` (a console has no
positions): `positional parameters are not supported here; use :name`.

**Conversion.** A value compared to an attribute (a parameter or a literal) that is a `String` while the attribute is
not text is converted by the attribute's Java type: an enum by constant name, the `java.time` types by ISO parsing,
the numeric types exactly (`BigDecimal`/`BigInteger` included), `Boolean` from `true`/`false`, `UUID` by
`fromString`. A value of the right type is kept. A value that does not convert throws
`MansartDataException("<:name or literal>: not a <Type>")` before anything runs. The same conversion applies to the
values of an `UPDATE … SET`.

**The existing path.** `execute(Stmt, Method, …)` is refactored so that what it takes from the `Method` (the
return-type dispatch, the parameter names, the projection element type) comes from a small internal description
that both `executeJdql` (the `@Query` path, built from the `Method`) and `run` (built from the statement) produce.
The `@Query` path's behaviour does not change; its tests stay green.

**Limits.** `run` returns at most what the statement selects; the caller cuts. `Entities` and `Rows` are lists fully
read, as every Mansart query is today.

The Mansart checkout (`~/projects/perso/vidocq/mansart`) is on the user's branch with uncommitted work: the work goes
in a visible git worktree next to it, with the user's OK, removed once merged.

## 3. Vidocq: the *JDQL* tab

In `vidocq-runtime-mansart-data-extension-dev`, `RepositoryActions` (or a sibling `JdqlActions`) adds two actions in
the group `JDQL` (a tab after the repositories' tabs):

- **`jdql.query`, label *Query*:** one JSON argument `{ "query": string (format textarea, required), "params":
  object (optional) }`; no confirmation. A statement for which `isWrite` is true is refused: `an UPDATE or DELETE:
  use Update / Delete`.
- **`jdql.write`, label *Update / Delete*:** the same argument, plus `transaction` (`rollback`, `commit`; `commit`
  only without a `TransactionManager`), and the confirmation `Runs this JDQL statement against the database.`; a
  statement for which `isWrite` is false is refused: `not an UPDATE or DELETE: use Query`. It runs in the
  application's transaction as sub-project 2's writes do (`TransactionRunner`).

**The entity.** `JdqlExecutor.target(query)` gives a name; it is looked up among the catalogue's entities by simple
name, then by fully qualified name. None: `unknown entity <name>; entities: <names>`. The model is `EntityModels.of`
of that class; the runtime is the default `RepositoryRuntime` bean, resolved at the call through the panel's
`BeanManager` (`no RepositoryRuntime bean` when none).

**Results** (through the JSON viewer, as sub-project 2's `ResultJson`):
- `Entities`: an array of entities (sub-project 2's `EntityJson`), at most 100; summary `3 rows in 12 ms`,
  `first 100 rows in 40 ms`, `no row in 1 ms`;
- `Rows`: an array of objects `{ column: value }`, at most 100, same summaries;
- `Count`: the number; summary `42` for a count, `4 rows · rolled back` / `4 rows · committed` for a write;
- `Value`: the value; summary its text.
- *details* (under *Exchange*): `{"entity": "<class>", "query": "…", "params": {…}, "transaction": "…"}`.

**Errors:** a `MansartDataException` or any exception from the run: an `error` result, one-line summary
`<class>: <message>`, the message masked and cut (sub-project 2's `Failures`), the body the full text; a write's
transaction rolled back.

**History:** the tab keeps its last 20 calls in the panel's `calls` table (`method` = *Query* or *Update / Delete*,
`arguments` the JSON sent), each with a replay cell; a replay fills the form and sends nothing.

## 4. The page: `format: textarea`

In `console.js`, a flat-schema form renders a string property whose schema has `"format": "textarea"` as a
`<textarea>` (monospace, 4 rows, resizable, the same value handling as an `<input>`). Everything else is unchanged.
`PageTest` pins the rule; `dev-console-panels.adoc` documents it.

## 5. Testing

- **Mansart** (`mansart-data-tests`, H2): `run` for a list, a filtered list with `:param`, a projection (one and
  several columns), `COUNT`, `MAX`, an `UPDATE` and a `DELETE` (counts, and the rows really changed); conversion of a
  parameter and of a literal to an enum, a `LocalDate`/`Instant` and a number, and each conversion error; a missing
  and an unused parameter; `?1` refused; `isWrite` and `target` on the statement shapes; the existing `@Query` tests
  unchanged.
- **Vidocq `-dev` module:** the entity lookup and its error; each refusal; the summaries of each result kind; the
  transaction with the recording `TransactionManager` (rollback, commit, an exception rolling back); the history;
  the `textarea` rule in `PageTest`.
- **mansart-h2 example** (`DevConsoleSnapshotTest`): *Query* `FROM Product WHERE price > :min` returns rows; *Update /
  Delete* `UPDATE Product SET price = price * 2` in `rollback` changes nothing.
- **Manual check in Chrome** under `vidocq:dev` on `lc4jcdi-on-vidocq/mcp-tasks-server`: the *JDQL* tab, a query with
  an enum parameter, a projection, a count, an `UPDATE` rolled back then verified unchanged, an unknown entity, a
  syntax error, a replay; the textarea.

## 6. Documentation

- Mansart: the javadoc of `run`, `isWrite`, `target` and `JdqlResult`.
- `vidocq-runtime-extensions.adoc`: a `[#mansart-data-jdql]` section [NEW]: what it runs, the parameters and their
  conversion, rollback/commit, no `INSERT` in JDQL (use `save`), the default data store only.
- `dev-console-panels.adoc`: `format: textarea`.
- `whats-new.adoc`: one entry.

## 7. Decisions taken with the user

- A JDQL console first; CSV import and export are the next sub-project.
- JDQL has no `INSERT`: inserts go through `save`/`@Insert` (and the future CSV import).
- A small public Mansart API (`JdqlExecutor.run`) that converts values by the attribute's type, rather than working
  around the executor from Vidocq.
- Two actions, *Query* and *Update / Delete*, so that a write is always confirmed and rolled back by default.
- A generic `format: textarea` in the page.
