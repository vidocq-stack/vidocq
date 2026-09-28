# Dev console: a Mansart Data catalogue

Date: 2026-09-28. First of four sub-projects of a Mansart Data panel, in this order:
1. the catalogue (this spec);
2. running a repository method from the console, through the action groups as tabs of Vidocq/vidocq#151;
3. live statistics per repository method, which need counters in Mansart;
4. a free JDQL console, which starts with a spike on whether Mansart can run a JDQL query built at run time.

## 1. Goal

A developer running `mvn vidocq:dev` sees, in the dev console, what Mansart Data knows about the application: its
entities with their table and columns, and its repositories with their methods and queries.

- **In scope:**
  - a public way to read an entity's model in Mansart (`EntityModels.of`);
  - a `mansart-data` section in the startup report, written by `vidocq-runtime-mansart-data-extension`;
  - a new `vidocq-runtime-mansart-data-extension-dev` module that shows the catalogue live in the dev console;
  - the `MANSART-DATA-001` anomaly.
- **Out of scope:**
  - running anything against the database (sub-projects 2 and 4); the panel opens no connection;
  - counters or timings (sub-project 3);
  - Mansart persistence (`vidocq-runtime-mansart-persistence-extension`): its inventory is still a stub;
  - translating a derived method into SQL: Mansart does not keep the generated SQL at run time.

## 2. Mansart: `EntityModels.of`

`io.vidocq.mansart.data.core.EntityModels.lookup(Class<?>)` is package-private. It resolves an entity's
`EntityModel<E>`: the generated `_Entity.$MODEL` first, `RuntimeEntityModelBuilder` otherwise, cached per class.

Add, in `mansart-data-core`:

```java
/** The model Mansart uses for {@code entityType}: its generated metamodel, or one built at run time. */
public static <E> EntityModel<E> of(Class<E> entityType)
```

It delegates to `lookup` and throws what `lookup` throws for a class that is no entity. Nothing else changes in
Mansart. Tested in `mansart-data-core`: an entity with a generated metamodel, one without, a class that is no entity.

The Mansart checkout (`~/projects/perso/vidocq/mansart`, `git@codefloe.com:Vidocq/mansart.git`) is on the user's
branch `ybl/opencode-3` with uncommitted `.opencode/` files. The plan asks the user how to branch from Mansart's
`main` there before any change; those files are never touched.

## 3. The catalogue (`vidocq-runtime-mansart-data-extension`)

Built once per boot in `onStart`, from the CDI container and reflection, and never again.

**Repositories.** Every bean whose types include an interface annotated `jakarta.data.repository.Repository`, as
`MansartDataIntegrationExtension.logRepositoryInventory` finds them today. A repository is identified by its
interface: the generated `TaskRepositoryImpl` bean and the interface count once. For each:
- **its primary entity and id type**, read from the type arguments of `BasicRepository<E, K>`,
  `CrudRepository<E, K>` or `DataRepository<E, K>`, however deep in its super-interfaces; none when it has no such
  super-interface;
- **its declared methods**, those of the interface itself, sorted by name (reflection gives no declaration order).
  Each gets:
  - **kind:** `JDQL` for `@Query`; `@Find`, `@Insert`, `@Update`, `@Delete`, `@Save` for those annotations;
    `derived` for a name starting with `find`, `count`, `exists` or `delete` and holding `By`; `other` otherwise;
  - **query:** the `@Query` value, as written; empty for other kinds;
  - **parameters:** `name: Type`, comma-separated; the name from `@Param`, else the real name when compiled with
    `-parameters`, else `argN`;
  - **returns:** the generic return type in simple names, such as `List<Task>`, `Optional<Task>`, `long`, `void`;
- **its inherited methods**, one line: the names of the methods it gets from `jakarta.data.repository` interfaces,
  such as `inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll`.

**Entities.** The primary entities of the repositories, deduplicated. For each, `EntityModels.of(entity)`:
- **table:** `tableName`, prefixed with `schema.` when there is one;
- **columns:** one row per attribute, id first, then version, then the others in model order:
  - `field`: the attribute name; `column`: the column name; `type`: the Java type's simple name;
  - `key`: `id` (plus `, generated` when it is), `version`, `enum`, `→ <Entity>` for a reference, `joined` for a
    joined attribute, empty otherwise; `nullable` and `unique` as `yes` or empty.

**When the model fails.** `EntityModels.of` throws, or returns something unusable: the entity keeps its place in the
catalogue with no columns and the reason, and the section raises `MANSART-DATA-001` (§4). The boot never fails for
it. A repository with no primary entity is listed under *Other repositories*.

**Limits.** At most 200 entities, 200 repositories and 200 methods per repository; past a limit, the rest is
counted, not listed (`and 3 more`). A query text longer than 1,000 characters is cut, with `…`.

**Shared with the panel.** The extension publishes the catalogue, an immutable record, in a `MansartDataLive`
holder (a `volatile` field with `publish` and `clear`), as `MansartPoolsLive` does, and clears it first thing in
`onStop`. The catalogue holds names and texts only, never a bean, a class loader or a connection.

## 4. The startup report section `mansart-data`

`MansartDataIntegrationExtension` implements `StartupReportContributor`, section id `mansart-data`, title
*Mansart Data*:
- **summary:** `2 entities, 2 repositories, 8 methods` (declared methods; singular forms for one);
- **detailed:** a row per entity, `Task` → `tasks, 6 columns`; a row per repository, `TaskRepository` →
  `Task, 6 methods`; *Other repositories* the same way, `→ no primary entity, 2 methods`;
- **`MANSART-DATA-001`:** `The model of entity <Class> could not be read (<exception class>)`, hint `Check its
  mapping annotations; Mansart could not build its model, so its repositories may fail too.` One anomaly per
  entity. The exception's message is never shown.

The existing log lines of `logRepositoryInventory` stay; the connectivity check (`vidocq.data.checkOnStart`) is
unchanged.

## 5. The live panel (`vidocq-runtime-mansart-data-extension-dev`)

A new module next to `vidocq-runtime-mansart-data-extension`, built like `vidocq-runtime-mansart-pool-extension-dev`:
a `LivePanel` with id `mansart-data`, registered in `META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`,
and the descriptor `META-INF/vidocq/dev-module` in the runtime extension naming it, so that `vidocq:dev` adds it
and the packaging goals drop it (#143).

`sample(PanelSample)` reads `MansartDataLive` only, creates no bean, opens nothing, and writes, per entity in
name order, one group named after the entity:
- the group's kind: its table;
- a table `columns`: `field`, `column`, `type`, `key`, `nullable`, `unique`;
- per repository of that entity, in name order, a table named after the repository: `method`, `kind`, `query`,
  `parameters`, `returns`, then a value `<Repository> inherits` holding the inherited-methods line;
- for an entity whose model failed, a value `model` reading `unavailable: <exception class>` instead of the table.

Then a group *Other repositories* when there are any, with one methods table each. No charts. When
`MansartDataLive` holds nothing, the sample says `absent` with the reason `no catalogue yet`, like the other
panels. The page's existing limits apply (`MAX` cells, `truncated`).

## 6. Errors

Everything is best effort and never fails the boot: a repository interface that reflection cannot read is listed
with `kind` `other` and no parameters; a type that cannot be printed shows its raw name. The panel never throws
from `sample`; a failure there is the console's `VIDOCQ-DEVC-005`, as for any panel.

## 7. Testing

- **Mansart:** `EntityModels.of` in `mansart-data-core` (§2).
- **Extension, unit:** the catalogue builder against test repository interfaces and entities:
  - derived, `@Query`, `@Find`/`@Insert`/`@Delete`, and other methods, with `@Param`, `-parameters` and `argN` names;
  - generic returns (`List<Task>`, `Optional<Task>`, primitives);
  - a repository through `CrudRepository`, one with no primary entity, the generated `*Impl` bean deduplicated;
  - an entity whose model fails (`MANSART-DATA-001`, no message shown), the limits, a long query cut;
  - the section's summary and detailed rows.
- **`-dev` module, unit:** the sample written for a catalogue: groups, tables and their columns, the inherited line,
  the failed model, *Other repositories*, `absent` when there is no catalogue.
- **Manual check in Chrome** under `vidocq:dev` on `lc4jcdi-on-vidocq/mcp-tasks-server` (the user's test app),
  console on a free port of 18090-18099: the *Mansart Data* panel with `Task` and `TaskEvent`, their columns,
  `TaskRepository`'s six methods with `searchText` and `renameProject` as `JDQL` and their text, and
  `TaskEventRepository`.

## 8. Documentation

- `docs/en/.../modules/vidocq-runtime-extensions.adoc`: a new `[#mansart-data-catalogue]` section [NEW] (the report
  section, the panel, what is and is not shown) and `[#mansart-data-001]`.
- `reference.adoc`: `MANSART-DATA-001` in the anomaly table.
- `whats-new.adoc`: one new entry.
- Mansart: the javadoc of `EntityModels.of`.

## 9. Decisions taken with the user

- Four sub-projects, in the order of the header; this is the first.
- The catalogue is laid out per entity, its repositories inside it.
- Inherited `BasicRepository` methods are one summary line, not table rows.
- The entity model comes from Mansart itself, through a new public `EntityModels.of` (a Mansart PR), not from
  reflection on generated classes nor from re-reading the JPA annotations.
