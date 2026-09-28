# Dev console: running a Mansart Data repository method

Date: 2026-09-28. Second of four sub-projects of the Mansart Data panel; the first, the catalogue, is
`2026-09-28-mansart-data-catalogue-design.md` (Vidocq/vidocq#153).

## 1. Goal

In a `dev` launch, a developer picks a repository method in the *Mansart Data* panel, fills its arguments, and
runs it against the application's database: a query to see what it returns, a write to try it, then roll it back
or keep it.

- **In scope:**
  - every declared method of a `@Repository` interface, plus the inherited `findById`, `findAll`, `save`,
    `deleteById` and `delete`, as dev console actions, one tab per repository (#151's action groups);
  - arguments converted from JSON to the method's Java types, an entity built for `save(Task)`;
  - writes behind a confirmation, run in a transaction that is rolled back (the default) or committed;
  - results as JSON through the page's JSON viewer, the last 20 calls with a replay.
- **Out of scope:**
  - live statistics (sub-project 3) and a free JDQL console (sub-project 4);
  - `PageRequest`, `Limit`, `Sort`, `Order`, collection and array parameters, and any other type §4 does not list:
    such a method is listed, not runnable;
  - any change to the console SPI (`PanelAction`, `ActionResult`), to the page, or to Mansart's behaviour.

## 2. Where it lives

- **`vidocq-runtime-mansart-data-extension`** publishes, besides the catalogue, the repository interfaces themselves
  in `MansartDataLive` (`repositories(): List<Class<?>>`), set in `onStart` and cleared first in `onStop`, like
  the catalogue. The qualified export of the `live` package to the `-dev` module already exists.
- **`vidocq-runtime-mansart-data-extension-dev`**: `CatalogueLivePanel` gains `start(ExtensionContext)`, keeping the
  context's `BeanManager`, and `actions()`. The actions are built once per boot, in `actions()`, from
  `MansartDataLive.repositories()` and the catalogue, as the console calls it once per boot after `start`.
- **Tabs:** each repository is a group, titled with its simple name (the catalogue's name, fully qualified when two
  share it); the page shows *Monitoring* (the catalogue as today) then one tab per repository, its combo listing
  its methods by name (#151).
- **Action id:** `m.<repository key>.<method key>`, keys as the catalogue panel builds them; two overloads of one
  name get `-2`, `-3`. At most 128 actions per panel (the console's limit): past it, the rest is listed in
  *Monitoring* as `and N more methods`, not runnable.

## 3. Calling a method

- **The bean:** resolved at the call, `bm.getReference(bm.resolve(bm.getBeans(repository)), repository, cc)`.
  Actions may create a bean (the "no bean created" rule is `sample()`'s); a Mansart repository is a `@Singleton`.
- **The call:** `Method.invoke` on the interface's method. The repository's package must be open to Vidocq (a Vidocq
  application is an `open module` by convention). When reflection is refused (`InaccessibleObjectException`,
  `IllegalAccessException`), found when the actions are built by `setAccessible` or a test call on the `Method`,
  the method is listed with `not runnable: package <p> not open to Vidocq`, and no action.
- **Writes:** `save`, `saveAll`, `insert*`, `update*`, `delete*`, a method annotated `@Insert`, `@Update`,
  `@Delete` or `@Save`, a derived `delete…By…`, and a `@Query` whose text starts, after blanks, with `UPDATE` or
  `DELETE` (case-insensitive). A write:
  - has a confirmation, `Runs <Repository>.<method> against the database.`;
  - has a second argument `transaction`, allowed values `rollback`, `commit`, in that order, so `rollback` is the
    default; when the application has no `jakarta.transaction.TransactionManager` bean, only `commit`;
  - runs in a transaction of that `TransactionManager`: `begin`, the call, then `commit` or `rollback` as asked; an
    exception from the call rolls it back whatever was asked. Without a `TransactionManager`, `commit` runs the call
    as it is (Mansart then autocommits).
- **Reads:** no confirmation, no transaction.
- **Time:** the console's own 60 s limit applies; a longer call keeps running and its outcome shows on a later poll.

## 4. Arguments

One JSON argument per action, `arguments`, described by a JSON Schema built from the method's parameters, in order,
each a property named after the catalogue's parameter name (`@Param`, the real name, else `argN`), all required:

| Java type | Schema | From JSON |
|---|---|---|
| `String`, `char`/`Character` | `string` (`maxLength` 1 for a char) | as is |
| `int`, `long`, `short`, `byte` and their boxes, `BigInteger` | `integer` | exact, out of range refused |
| `double`, `float` and boxes, `BigDecimal` | `number` | `BigDecimal` keeps the text |
| `boolean`/`Boolean` | `boolean` | as is |
| an `enum` | `string` with `enum` = its constants' names | by name |
| `LocalDate`, `LocalDateTime`, `LocalTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime` | `string`, `format` `date`, `date-time` or `time` | ISO parse |
| `UUID` | `string`, `format` `uuid` | `UUID.fromString` |
| an entity of the catalogue | `object`, one property per column (types above), none required | §5 |

A boxed type accepts `null`; a primitive does not. Any other type makes the method not runnable, with the reason
`parameter <name>: <Type> is not supported`. A flat schema gives the page's form; an entity parameter gives its raw
JSON editor, prefilled by the page's skeleton.

A value that does not convert (an unknown enum name, a malformed date, a missing property, a number out of range)
fails the action before any call: an `error` result `<name>: <why>`, nothing invoked, no transaction begun.

## 5. Entities

Read and built through Mansart's model, `EntityModels.of(entity)`:
- **to JSON:** an object with one property per attribute, in model order, read with the attribute's getter handle;
  values as in §6;
- **from JSON:** the entity's no-arg constructor (`EntityModel.constructor()`), then each property present set with
  the attribute's setter handle, converted by its Java type as in §4; an unknown property is an error; an absent one
  keeps the constructor's value (so `id` absent lets a generated id be generated).

The javadoc of `EntityModels.of` says today that a tool describing an entity does not use those handles. A Mansart
pull request updates it: the Vidocq dev console reads and builds entities with them, in a `dev` launch, for the
methods a developer runs.

## 6. Results

- **Values:** `null`; numbers, booleans and strings as they are; an enum as its name; a `java.time` value as ISO
  text; a `UUID` or anything else as `toString()`; an entity as in §5, a referenced entity as its id only (never the
  graph); a joined attribute is left out.
- **Shapes:** `List`, `Collection`, `Stream` and arrays become a JSON array of at most 100 elements (a `Stream` is
  read to the 101st and closed); `Optional` its value or `null`; `void` nothing.
- **The answer** (`ActionResult`): `contentType` `application/json`, the body the JSON above (the console caps it at
  256 KiB); the summary:
  - `3 rows in 12 ms`, `first 100 rows in 40 ms` when more were there, `1 row`, `no row`, `42`, `done`;
  - a write adds ` · committed` or ` · rolled back`;
- **details** (under *Exchange*): `{"method": "<Repository>.<signature>", "arguments": [{"name", "type", "value"}],
  "transaction": "rollback|commit|none"}`.

## 7. Errors

- **A method that throws:** an `error` result, summary `<exception class>: <message>`, the message cut at 500
  characters, a `user:password@` in it masked; the transaction rolled back. The console itself only logs the action's
  id and outcome.
- **A bean that cannot be resolved** (none or ambiguous): `error`, `no bean for <Repository>`.
- **After a dev reload:** the actions are rebuilt from the new boot; a call to an action of the previous boot is the
  console's own `404`.

## 8. History

A table `calls` in each repository's group, the last 20 calls of the boot, newest first: `time`, `method`,
`outcome`, `ms`, `arguments` (the JSON sent, cut at 200 characters), and `replay` (`PanelSample.REPLAY_COLUMN`,
`<action id> <JSON arguments>`, empty past `MAX_REPLAY_CELL`). The page shows it in the repository's tab only
(#151). A dev reload starts a new history.

## 9. Testing

- **`-dev` module, unit:**
  - the schema of a signature: each type of §4, an entity, an unsupported type and its reason, overloads;
  - JSON to Java: each type, each failure naming its parameter;
  - results to JSON: an entity, a list cut at 100, a `Stream` closed, `Optional`, a reference as its id;
  - write detection, for every rule of §3;
  - transactions, with a recording `TransactionManager`: `rollback`, `commit`, an exception rolling back, none
    without a manager;
  - the history and its replay cells.
- **Against a database:** a test with an in-memory H2 and real Mansart repositories, in the module that already has
  such fixtures (the mansart-h2 example, or a test module the plan names): `findById`, a `save` in `rollback` that
  leaves no row, a `save` in `commit` that leaves one, a JDQL `UPDATE` in `rollback`.
- **Manual check in Chrome** under `vidocq:dev` on `lc4jcdi-on-vidocq/mcp-tasks-server`, console on a free port of
  18090-18099: the `TaskRepository` and `TaskEventRepository` tabs; `findByStatusOrderByDueDateAsc` with the enum
  list; `searchText`; `save` of a `Task` in `rollback`, then a read that does not find it; `renameProject` in
  `commit`, then a read that shows it; an error call; a replay.
- **Mansart:** no behaviour change, only the javadoc of `of()`.

## 10. Documentation

- `docs/en/.../modules/vidocq-runtime-extensions.adoc`: a new `[#mansart-data-run-method]` section [NEW]: the tabs,
  the arguments and their types, writes and `rollback`/`commit`, the limits, the open package.
- `whats-new.adoc`: a new entry.
- Mansart: the javadoc of `EntityModels.of` (§5).

## 11. Decisions taken with the user

- Writes: a confirmation, and a choice between committing and a transaction rolled back after the call (the default).
- Entities read and built through Mansart's `EntityModel` handles, not JSON-B.
- Declared methods plus the inherited `findById`, `findAll`, `save`, `deleteById`, `delete`; an unsupported
  parameter lists the method with its reason.
- One tab per repository.
- A failing method shows its exception's class and message, cut and masked (a dev launch only).
