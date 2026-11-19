# Mansart Data catalogue in the dev console — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `mvn vidocq:dev` show, in the startup report and in a live dev console panel, what Mansart Data knows
about the application: its entities with their table and columns, its repositories with their methods and queries.

**Architecture:**
- **Mansart:** `EntityModels` becomes public and gets `of(Class<E>)`, which delegates to the package-private `lookup`.
- **`vidocq-runtime-mansart-data-extension`:**
  - `RepositoryReader` reads one `@Repository` interface by reflection (primary entity, declared methods, inherited
    Jakarta Data methods); `TypeNames` prints generic types in simple names;
  - `CatalogueBuilder` turns the repository interfaces and `EntityModels.of` into an immutable
    `MansartDataCatalogue` (names and texts only), with the limits;
  - `MansartDataIntegrationExtension` builds it once in `onStart`, publishes it in `MansartDataLive`, writes the
    `mansart-data` section (and `MANSART-DATA-001`), and clears the holder first thing in `onStop`;
  - the package `...mansart.data.live` is exported only to the `-dev` module.
- **`vidocq-runtime-mansart-data-extension-dev`** (new): `CatalogueLivePanel`, a `LivePanel` with id `mansart-data`
  that reads `MansartDataLive` only. The runtime jar names it in `META-INF/vidocq/dev-module`.

**Tech Stack:** Java 25, JPMS, Maven 3.9, Jakarta Data 1.0.1, Mansart Data (`mansart-data-core`,
`mansart-data-dialect-spi`), the Vidocq report SPI and dev console SPI, JUnit 5 (AssertJ on the Mansart side).

**Spec:** `docs/superpowers/specs/2026-09-28-mansart-data-catalogue-design.md` (binding). Section numbers below
refer to it. Read it first.

## Global Constraints

- **Repositories and branches.**
  - Vidocq: `/Users/yblazart/projects/perso/vidocq/vidocq`, branch `feat/mansart-data-catalogue` (already checked
    out; never switch it).
  - Mansart: `/Users/yblazart/projects/perso/vidocq/mansart`. It is on the user's branch `ybl/opencode-3` with
    uncommitted work (modified and deleted tracked files under `.opencode/`, `docs/agent-harness.md`,
    `opencode.json`, `scripts/opencode-harness/`, plus untracked files). **Never** stash, reset, checkout, add or
    commit any of those files. Task 1 says exactly how the Mansart branch is made and left.
  - Test app (read only): `/Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server`. Never
    touch its uncommitted `.run/*.xml` files (they are in the parent repo `lc4jcdi-on-vidocq`).
- **Maven.** Every command starts with
  `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;` and uses
  `mvn -nsu` (never `./mvnw`). If a hook redirects a Maven call to the context-mode `ctx_execute` shell tool, run
  it there and print only the tail/summary. Maven cannot be put in the background through Bash (a hook blocks it);
  the only background run is the one of Task 7, through the existing script.
- **Module paths** (checked with `-pl ... validate` while planning):
  - `EXT` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension`
  - `DEV` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
  - `WEB` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web`
  - Java sources of EXT: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/`
    (package `io.vidocq.runtime.extensions.jakartaee.web.mansart.data`), tests in the same package under
    `EXT/src/test/java/...`.
  - Mansart: `mansart-jakarta-data/mansart-data-core`, `mansart-jakarta-data/mansart-data-tests`.
- **Scratch directory** for logs: `SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad`.
- **Ports:** only 18090-18099, checked free with `lsof -nP -iTCP:<port> -sTCP:LISTEN` first; never 8080 or 8888.
  Never kill a process this plan did not start.
- **Commits.** Write the message with the Write tool to `<repo>/.git/PLAN_COMMIT_MSG`, then
  `git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG` (never `-m`, never `-s`). Stage explicit paths
  only (never `git add -A` or `git add .`). Every message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  Subjects follow the repos' conventional style (`feat(mansart-data): ...`). **Never push**; the controller opens
  the pull requests when the user says so.
- **CI dependency.** Vidocq's CI resolves `io.vidocq.mansart:*:0.4.0-SNAPSHOT` from the published snapshots: the
  Vidocq pull request cannot go green before the Mansart pull request (`feat/entity-models-of`) is merged and its
  snapshot published. Locally, Task 1 installs it into `~/.m2`. If the user later rebuilds Mansart from a branch
  without the change, `EntityModels.of` disappears from `~/.m2` and the Vidocq modules stop compiling.
- **Code style.** English; 120 columns; the license header below at the top of every new `.java` file (the code
  blocks of this plan omit it: prepend it verbatim); Javadoc density like the surrounding files; no new dependency
  (the extension already has `mansart-data-core`, which `requires transitive` the dialect SPI, and `jakarta.data`).

```java
/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
```

- **Names, verbatim** (spec §3-§5): section id and panel id `mansart-data`; section title `Mansart Data`; anomaly
  `MANSART-DATA-001`; hint `Check its mapping annotations; Mansart could not build its model, so its repositories
  may fail too.`; group `Other repositories`; absent reason `no catalogue yet`; limits 200 entities, 200
  repositories, 200 methods per repository, query text 1,000 characters then `…`; kinds `JDQL`, `@Find`,
  `@Insert`, `@Update`, `@Delete`, `@Save`, `derived`, `other`.

## Rulings on the spec

These are decisions this plan takes where the spec is silent, ambiguous or cannot be followed literally:

1. **`EntityModels` must become `public`** (§2 says "nothing else changes"): the class is package-private, so a public
   static method on it is unreachable from Vidocq.
2. **The Mansart test lives in `mansart-data-tests`, not `mansart-data-core`** (§2, §7): `mansart-data-core` has no
   test sources and cannot run `mansart-data-processor`, so it cannot hold "an entity with a generated metamodel";
   `mansart-data-tests` runs the processor and already tests `_Book.$MODEL`.
3. **Panel keys** (§5): `PanelSample` keys must match `[a-z][a-z0-9.-]{0,39}`, so a table cannot be "named after the
   repository" literally, and `<Repository> inherits` (space, capitals) is no valid key. The table key is the
   repository name in kebab case (`TaskRepository` → `task-repository`, cut to 31 characters, `-2`, `-3` on a
   clash), the inherited line is `<key>.inherits`, and a repository past the method limit gets `<key>.more`.
4. **"The group's kind: its table"** (§5): `PanelSample` has no group kind; the group gets a text value `table`
   holding `schema.table`. A failed model writes the text `model` = `unavailable: <exception class>` instead of
   `table` and `columns`.
5. **Row values** (§4): the spec's `→` in "`TaskRepository` → `Task, 6 methods`" is the key-to-value notation; the
   values are `Task, 6 methods` and `no primary entity, 2 methods`. A failed entity's row is
   `model unavailable (<exception class>)`. Past a limit: rows `more entities` / `more repositories` = `and N more`.
6. **Counts:** the summary counts every entity, repository and declared method, those past a limit included (every
   repository is read; only the lists are capped). The spec's example "2 entities, 2 repositories, 8 methods" does
   not match the test app, which gives `2 entities, 2 repositories, 7 methods` (6 + 1); Task 7 expects 7.
7. **Unusable model** (§3): `null`, no id, or a blank table; its reason reads `unusable model`. The exception class
   is `getClass().getName()` (fully qualified). `RuntimeException` and `LinkageError` are both caught (a generated
   `_Entity.<clinit>` failure is an `ExceptionInInitializerError`).
8. **Same simple name twice** (entities or repositories from two packages): both are shown under their fully
   qualified names, so that groups and rows never merge.
9. **Past the entity limit**, an entity's repositories are not shown under any group (they stay counted). The
   console itself keeps 64 groups and 100 rows per table, and cuts every cell at 200 characters, so a 1,000-character
   query shows its first 200 there.
10. **`-parameters`:** Vidocq compiles its tests without it, so the real-interface test expects whatever
    `Parameter.isNamePresent()` says, and the three naming branches (`@Param`, real name, `argN`) are pinned on the
    pure helper `RepositoryReader.parameterName`.
11. **Declared methods** are the interface's own non-static, non-private, non-synthetic methods; the methods of an
    application's own base interface are neither declared nor "inherited from Jakarta Data", so they are not shown.
    The inherited line names the first `jakarta.data.repository` interfaces reached (`inherits BasicRepository: …`);
    none, or none with methods (`DataRepository`), gives no line.
12. **The extension's tests run on the class path** (`useModulePath=false`, as the `-dev` modules do):
    `EntityModels.of` opens a private lookup on the entity class, which the test classes patched into the module are
    not open for.
13. **No catalogue:** before `onStart`, the summary is `no catalogue: the extension did not start`; if building it
    threw (never expected), `no catalogue (<exception class>)`, a WARNING is logged, and the boot goes on.
14. **Docs:** besides §8, the dev console's panel table (`dev-console.adoc`) gets a *Mansart Data* row.

## Review Focus

1. **An entity whose model lookup throws an `Error`**, such as `NoClassDefFoundError` or the
   `ExceptionInInitializerError` of a generated `_Entity`: the boot must go on and the entity show `unavailable` —
   pinned in Task 3 (`aLinkageErrorFromTheModelIsReportedNotThrown`).
2. **Two entities or repositories with the same simple name** in different packages: groups and rows must not merge —
   pinned in Task 3 (`twoTypesWithOneSimpleNameAreShownUnderTheirFullNames`).
3. **Repository names that give an invalid or clashing panel key** (`Columns`, a 60-character name, a name starting
   with `_` or a digit, a fully qualified name): the sample must not throw — pinned in Task 5
   (`keysAreValidAndDistinct`).
4. **A dev reload**: once `onStop` ran, the panel must read no catalogue, never the previous boot's — pinned in Task 4
   (`onStopClearsTheCatalogueFirst`) and Task 5 (`withNoCatalogueTheSampleSaysSo`).
5. **A primary entity bound through an application's own generic base interface, or a raw `BasicRepository`**: the
   first is found, the second has none — pinned in Task 2 (`primaryTypesThroughAGenericBaseInterface`,
   `aRawBasicRepositoryHasNoPrimaryEntity`).

## File Structure

**Mansart** (`/Users/yblazart/projects/perso/vidocq/mansart`)
- Modify `mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core/EntityModels.java`: public
  class, new `of`.
- Create `mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/EntityModelsOfTest.java`,
  `UnscannedGadget.java`, `NotAnEntity.java`.

**Vidocq runtime extension** (`EXT`)
- Create `.../mansart/data/live/MansartDataCatalogue.java`: the immutable catalogue records (shared with `-dev`).
- Create `.../mansart/data/live/MansartDataLive.java`: the `volatile` holder.
- Create `.../mansart/data/TypeNames.java`: generic types in simple names.
- Create `.../mansart/data/RepositoryReader.java`: one repository interface by reflection.
- Create `.../mansart/data/CatalogueBuilder.java`: repositories and entity models to a catalogue, with limits.
- Modify `.../mansart/data/MansartDataIntegrationExtension.java`: report contributor, publish/clear.
- Modify `EXT/src/main/java/module-info.java`: export `live` to the `-dev` module.
- Modify `EXT/pom.xml`: surefire on the class path.
- Create `EXT/src/main/resources/META-INF/vidocq/dev-module`.
- Tests: `CatalogueFixtures.java`, `TypeNamesTest.java`, `RepositoryReaderTest.java`, `CatalogueBuilderTest.java`,
  `RecordedSection.java`, `ReportContext.java`, `MansartDataSectionTest.java`.

**Vidocq `-dev` module** (`DEV`, new)
- `DEV/pom.xml`, `DEV/src/main/java/module-info.java`,
  `DEV/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanel.java`,
  `DEV/src/main/resources/META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`,
  tests `CatalogueLivePanelTest.java`, `RecordedSample.java`.

**Wiring:** `WEB/pom.xml` (module list), root `pom.xml` (`dependencyManagement`).

**Docs:** `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc`, `reference.adoc`, `whats-new.adoc`,
`dev-console.adoc`.

---

### Task 1: Mansart — `EntityModels.of`

**Files:**
- Modify: `mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core/EntityModels.java`
- Create: `mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/EntityModelsOfTest.java`
- Create: `mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/UnscannedGadget.java`
- Create: `mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/NotAnEntity.java`

**Interfaces:**
- Produces: `public static <E> io.vidocq.mansart.data.dialect.EntityModel<E> io.vidocq.mansart.data.core.EntityModels.of(Class<E> entityType)`,
  throwing `io.vidocq.mansart.data.core.MansartDataException` (public) for a class Mansart cannot map. Installed as
  `io.vidocq.mansart:mansart-data-core:0.4.0-SNAPSHOT` in `~/.m2`.

All commands of this task run in `/Users/yblazart/projects/perso/vidocq/mansart` (`M` below).

- [ ] **Step 1: Record the working tree as it is**

```bash
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 > "$SCRATCH/mansart-status-before.txt"
git -C /Users/yblazart/projects/perso/vidocq/mansart branch --show-current
```
Expected: `ybl/opencode-3`. (`SCRATCH` as in Global Constraints.)

- [ ] **Step 2: Branch from `origin/main`**

```bash
git -C /Users/yblazart/projects/perso/vidocq/mansart fetch origin
git -C /Users/yblazart/projects/perso/vidocq/mansart switch -c feat/entity-models-of origin/main
```
Expected: `Switched to a new branch 'feat/entity-models-of'`, the uncommitted changes carried along.

**If git refuses** ("Your local changes to the following files would be overwritten…"): **STOP the task here and
ask the user**; do not stash, reset, checkout or commit anything, do not create a worktree. A check made while
planning predicts this refusal: `opencode.json` differs between `ybl/opencode-3` and `origin/main`, and the modified
or deleted `.opencode/agents/*.md`, `.opencode/commands/*.md`, `docs/agent-harness.md` and
`scripts/opencode-harness/*.sh` do not exist on `origin/main`. Report that list to the user and wait for their
decision; resume at Step 3 once `git branch --show-current` prints `feat/entity-models-of`.

- [ ] **Step 3: Write the failing test and its two fixtures**

`UnscannedGadget.java` (no `@Entity`: `mansart-data-processor` only reads `@Entity` and `@Repository`, so no
`_UnscannedGadget` is generated):

```java
package io.vidocq.mansart.data.tests;

import jakarta.persistence.Id;

/** Mapped by its {@code @Id} but not {@code @Entity}: the processor generates no metamodel for it. */
public class UnscannedGadget {

    @Id
    private Long id;

    private String label;

    public UnscannedGadget() {}
}
```

`NotAnEntity.java`:

```java
package io.vidocq.mansart.data.tests;

/** No id field, explicit or implicit: Mansart cannot build a model for it. */
public class NotAnEntity {

    private String label;

    public NotAnEntity() {}
}
```

`EntityModelsOfTest.java`:

```java
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link EntityModels#of}: the model Mansart itself uses for an entity, read by tools such as Vidocq's dev console. */
class EntityModelsOfTest {

    @Test
    void anEntityWithAGeneratedMetamodelGetsThatMetamodel() {
        assertThat(EntityModels.of(Book.class)).isSameAs(_Book.$MODEL);
    }

    @Test
    void anEntityWithoutMetamodelGetsAModelBuiltAtRunTimeOnce() throws Exception {
        assertThatThrownBy(() -> Class.forName("io.vidocq.mansart.data.tests._UnscannedGadget"))
                .isInstanceOf(ClassNotFoundException.class);

        EntityModel<UnscannedGadget> model = EntityModels.of(UnscannedGadget.class);

        assertThat(model.entityClass()).isEqualTo(UnscannedGadget.class);
        assertThat(model.tableName()).isEqualTo("unscanned_gadgets");
        assertThat(model.id().name()).isEqualTo("id");
        assertThat(model.attributes()).extracting(Attribute::name).containsExactly("id", "label");
        assertThat(EntityModels.of(UnscannedGadget.class)).isSameAs(model);
    }

    @Test
    void aClassThatIsNoEntityThrowsWhatTheLookupThrows() {
        assertThatThrownBy(() -> EntityModels.of(NotAnEntity.class))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("has no @Id field");
    }
}
```

- [ ] **Step 4: Run it to see it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/mansart && mvn -nsu -pl mansart-jakarta-data/mansart-data-tests -am test -Dtest=EntityModelsOfTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | tail -30
```
Expected: COMPILATION ERROR, `EntityModels is not public in io.vidocq.mansart.data.core` (or `cannot find symbol of`).

- [ ] **Step 5: Implement**

In `EntityModels.java`, replace the class Javadoc and declaration line and add `of` (keep `lookup`, `resolve` and
the cache exactly as they are):

```java
/**
 * M8-3 — central lookup of {@link EntityModel} by entity class. Resolution order:
 * <ol>
 *   <li>Compile-time generated metamodel: {@code <pkg>._<EntitySimpleName>.$MODEL} static.</li>
 *   <li>Fallback to {@link RuntimeEntityModelBuilder#build(Class)} for entities whose owning
 *       module didn't run APT (TCK harness, ad-hoc tests).</li>
 * </ol>
 *
 * <p>Used by {@link JdqlExecutor} and {@link io.vidocq.mansart.data.core.MethodNamePathResolver}
 * when resolving JDQL/method-name path expressions ({@code book.author.name}) — the dialect
 * needs the target table name and PK column to materialise the JOIN clause. Public through
 * {@link #of(Class)} only, for tools that show what Mansart knows, such as the Vidocq dev console.
 *
 * <p>Cached per-class. Thread-safe.
 */
public final class EntityModels {

    private EntityModels() {}

    private static final ConcurrentMap<Class<?>, EntityModel<?>> CACHE = new ConcurrentHashMap<>();

    /**
     * The model Mansart uses for {@code entityType}: its generated metamodel, or one built at run time.
     *
     * <p>The generated {@code <pkg>._<EntitySimpleName>.$MODEL} wins; without it, the model is built from the
     * class's fields and its {@code jakarta.persistence} mapping annotations, read by name. The model is cached:
     * a second call returns the same instance.
     *
     * @param entityType the entity class
     * @param <E>        the entity type
     * @return its model, never {@code null}
     * @throws MansartDataException when Mansart cannot map {@code entityType}: no id field, no no-arg constructor,
     *                              a package not open to {@code io.vidocq.mansart.data.core}, or a generated
     *                              metamodel without a public static {@code $MODEL} field; the initialisation of a
     *                              generated metamodel may also throw its own error
     */
    @SuppressWarnings("unchecked")
    public static <E> EntityModel<E> of(Class<E> entityType) {
        return (EntityModel<E>) lookup(entityType);
    }
```
The existing `static EntityModel<?> lookup(Class<?> entityType)` and `resolve` follow unchanged.

- [ ] **Step 6: Run the test to see it pass**

Same command as Step 4. Expected: `Tests run: 3, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Run the whole `mansart-data-tests` suite (nothing else broke)**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/mansart && mvn -nsu -pl mansart-jakarta-data/mansart-data-tests -am test 2>&1 | grep -E "Tests run:|BUILD|ERROR" | tail -15
```
Expected: `BUILD SUCCESS` (the `pg-it` tests stay excluded by default).

- [ ] **Step 8: Commit (only the four files)**

Message file `M/.git/PLAN_COMMIT_MSG`:
```
feat(data-core): EntityModels.of, the model Mansart uses for an entity

EntityModels.lookup resolves an entity's model, the generated _Entity.$MODEL
first and one built at run time otherwise, but it is package-private: a tool
that wants to show what Mansart knows had to re-read the JPA annotations or
reflect on generated classes. EntityModels is now public and of(Class<E>)
delegates to lookup, throwing what it throws. Nothing else changes.

The Vidocq dev console uses it for its Mansart Data catalogue.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/mansart
git add mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core/EntityModels.java \
  mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/EntityModelsOfTest.java \
  mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/UnscannedGadget.java \
  mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests/NotAnEntity.java
git diff --cached --name-only   # exactly the four paths above
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

- [ ] **Step 9: Install `mansart-data-core` for Vidocq**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/mansart && mvn -nsu -pl mansart-jakarta-data/mansart-data-core -am install -DskipTests 2>&1 | grep -E "Installing .*mansart-data-core|BUILD" | tail -5
unzip -l ~/.m2/repository/io/vidocq/mansart/mansart-data-core/0.4.0-SNAPSHOT/mansart-data-core-0.4.0-SNAPSHOT.jar | grep EntityModels
```
Expected: `BUILD SUCCESS`; `EntityModels.class` listed. (`javap -cp <that jar> io.vidocq.mansart.data.core.EntityModels`
shows `public final class` and `public static <E> ... of(java.lang.Class<E>)`.)

- [ ] **Step 10: Give the user their branch back**

```bash
git -C /Users/yblazart/projects/perso/vidocq/mansart switch ybl/opencode-3
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 | diff "$SCRATCH/mansart-status-before.txt" - && echo SAME
```
Expected: `SAME`. If anything differs, STOP and tell the user. Do not push; the controller opens the Mansart PR
when the user says so.

---

### Task 2: The catalogue records and the repository reader

**Files:**
- Create: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/live/MansartDataCatalogue.java`
- Create: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/live/MansartDataLive.java`
- Create: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/TypeNames.java`
- Create: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/RepositoryReader.java`
- Modify: `EXT/src/main/java/module-info.java`, `EXT/pom.xml`
- Test: `EXT/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/CatalogueFixtures.java`,
  `TypeNamesTest.java`, `RepositoryReaderTest.java`

**Interfaces:**
- Consumes: `EntityModels.of` from Task 1, installed in `~/.m2` (the fixtures call it, so the test sources do not
  compile without it).
- Produces:
  - `record MansartDataCatalogue(List<Entity> entities, List<Repository> repositories, int entityCount, int repositoryCount, int methodCount)`
    with `int moreEntities()`, `int moreRepositories()`, `List<Repository> repositoriesOf(Entity)`,
    `List<Repository> otherRepositories()`, and nested records
    `Entity(String name, String className, String table, List<Column> columns, String failure)`,
    `Column(String field, String column, String type, String key, boolean nullable, boolean unique)`,
    `Repository(String name, String className, String entityClassName, String entityName, String idType, List<Method> methods, int methodCount, String inherits)` with `int moreMethods()`,
    `Method(String name, String kind, String query, String parameters, String returns)`.
  - `MansartDataLive`: `static Optional<MansartDataCatalogue> catalogue()`, `static void publish(MansartDataCatalogue)`, `static void clear()`.
  - `TypeNames.of(Type) : String`.
  - `RepositoryReader.read(Class<?> repository, int maxQuery) : RepositoryReader.Read` with
    `record Read(Class<?> entity, String idType, List<MansartDataCatalogue.Method> methods, String inherits)`;
    `RepositoryReader.cut(String, int)`, `RepositoryReader.parameterName(String, boolean, String, int)`.
  - Test fixtures `CatalogueFixtures` (used by Tasks 3 and 4).

- [ ] **Step 1: Test sources on the class path**

In `EXT/pom.xml`, after `</dependencies>` and before `</project>`, add:

```xml
    <build>
        <plugins>
            <!--
                The catalogue tests read entities through Mansart's EntityModels.of, which opens a private lookup on
                the entity class: on the module path, the test classes patched into this module are not open to
                io.vidocq.mansart.data.core. Same class-path forks as the -dev modules.
            -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                </configuration>
            </plugin>
        </plugins>
    </build>
```

- [ ] **Step 2: Write the fixtures**

`CatalogueFixtures.java` (test sources):

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.EnumStorage;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.By;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.DataRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interfaces and entities the catalogue is built from, as an application declares them. Gadget, Customer
 * and Broken go through Mansart's own {@code EntityModels.of} (no metamodel is generated here, so Mansart builds
 * their model at run time); the model of Order is written by hand, to hold every kind of attribute.
 */
final class CatalogueFixtures {

    private CatalogueFixtures() {}

    /** Three fields, the implicit id {@code id} first. */
    public static class Gadget {
        private Long id;
        private String label;
        private int stockCount;

        public Gadget() {}
    }

    /** No id field at all: Mansart cannot build its model. */
    public static class Broken {
        private String label;

        public Broken() {}
    }

    /** Its model is {@link #orderModel()}. */
    public static class Order {
        public Order() {}
    }

    public static class Customer {
        private String id;
        private String name;

        public Customer() {}
    }

    public enum Status { OPEN, SHIPPED }

    /** Every kind of method; ten declared methods. */
    @Repository
    public interface GadgetRepository extends BasicRepository<Gadget, Long> {

        List<Gadget> findByLabel(String label);

        long countByStockCountGreaterThan(@Param("min") int min);

        @Query("FROM Gadget WHERE label LIKE :pattern ORDER BY id")
        List<Gadget> search(@Param("pattern") String pattern);

        @Find
        Optional<Gadget> byLabel(@By("label") String label);

        @Insert
        Gadget add(Gadget gadget);

        @Update
        Gadget change(Gadget gadget);

        @Delete
        void remove(Gadget gadget);

        @Save
        Gadget keep(Gadget gadget);

        int[] stockCounts();

        Map<String, ? extends Number> totals();
    }

    /** What mansart-data-processor generates next to an interface: the bean class that implements it. */
    public abstract static class GadgetRepositoryImpl implements GadgetRepository {}

    /** Through {@code CrudRepository}. */
    @Repository
    public interface OrderRepository extends CrudRepository<Order, UUID> {
        List<Order> findByStatus(Status status);
    }

    /** An application's own generic base interface: the primary entity is bound one level down. */
    public interface NamedRepository<T> extends DataRepository<T, String> {}

    @Repository
    public interface CustomerRepository extends NamedRepository<Customer> {
        boolean existsByName(String name);
    }

    @Repository
    public interface BrokenRepository extends BasicRepository<Broken, Long> {}

    /** No Jakarta Data super-interface: no primary entity. */
    @Repository
    public interface ReportQueries {

        @Query("SELECT count(this) FROM Order")
        long orderCount();

        @Query("SELECT count(this) FROM Gadget")
        long gadgetCount();
    }

    /** The five repository interfaces, as the builder receives them. */
    static final List<Class<?>> REPOSITORIES = List.of(BrokenRepository.class, CustomerRepository.class,
            GadgetRepository.class, OrderRepository.class, ReportQueries.class);

    /** The models: Order's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        return type == Order.class ? orderModel() : io.vidocq.mansart.data.core.EntityModels.of(type);
    }

    /** The model of {@link Order}: its attributes in an order a model may hold them, id and version not first. */
    static EntityModel<Order> orderModel() {
        IdAttribute<Order, UUID> id = new IdAttribute<>("id", "id", UUID.class, Order.class, false, null, null);
        VersionAttribute<Order, Integer> version =
                new VersionAttribute<>("version", "row_version", Integer.class, Order.class, null, null);
        EnumAttribute<Order, Status> status = new EnumAttribute<>("status", "status", Status.class, Order.class,
                false, false, EnumStorage.STRING, null, null);
        ReferenceAttribute<Order, Customer> customer = new ReferenceAttribute<>("customer", "customer_id",
                Customer.class, Order.class, true, false, false, null, null);
        TextAttribute<Customer> customerName =
                new TextAttribute<>("name", "name", Customer.class, true, false, 100, null, null);
        JoinedAttribute<Order, String> joined = new JoinedAttribute<>(customerName,
                JoinPath.of(new JoinPath.Step("customer", "customer_id", "id", "customers", "", Customer.class)),
                Order.class);
        TextAttribute<Order> note = new TextAttribute<>("note", "note", Order.class, true, true, 500, null, null);
        List<Attribute<Order, ?>> attributes = List.of(note, id, customer, status, version, joined);
        Optional<VersionAttribute<Order, ?>> versioned = Optional.of(version);
        return new EntityModel<>(Order.class, "orders", "shop", id, versioned, attributes, null);
    }
}
```

- [ ] **Step 3: Write the failing tests**

`TypeNamesTest.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TypeNames}: a generic type in simple names, as a developer writes it. */
class TypeNamesTest {

    interface Shapes<T> {
        List<String> list();

        Optional<Integer> optional();

        long primitive();

        void nothing();

        int[] ints();

        Map<String, ? extends Number> bounded();

        List<? super Integer> lower();

        List<?> any();

        T variable();

        List<T>[] genericArray();

        Map.Entry<String, Long> nested();
    }

    private static String returnOf(String method) throws NoSuchMethodException {
        return TypeNames.of(Shapes.class.getMethod(method).getGenericReturnType());
    }

    @Test
    void genericTypesInSimpleNames() throws Exception {
        assertEquals("List<String>", returnOf("list"));
        assertEquals("Optional<Integer>", returnOf("optional"));
        assertEquals("long", returnOf("primitive"));
        assertEquals("void", returnOf("nothing"));
        assertEquals("int[]", returnOf("ints"));
        assertEquals("Map<String, ? extends Number>", returnOf("bounded"));
        assertEquals("List<? super Integer>", returnOf("lower"));
        assertEquals("List<?>", returnOf("any"));
        assertEquals("T", returnOf("variable"));
        assertEquals("List<T>[]", returnOf("genericArray"));
        assertEquals("Entry<String, Long>", returnOf("nested"));
    }

    @Test
    void aTypeThatCannotBePrintedShowsItsRawName() {
        Type odd = new Type() {
            @Override
            public String getTypeName() {
                return "com.acme.Odd";
            }
        };
        assertEquals("com.acme.Odd", TypeNames.of(odd));
        assertEquals("?", TypeNames.of(null));
    }
}
```

`RepositoryReaderTest.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.BrokenRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Customer;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.CustomerRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Gadget;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.GadgetRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Order;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.OrderRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.ReportQueries;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Method;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link RepositoryReader}: what one {@code @Repository} interface says of itself, read by reflection. */
class RepositoryReaderTest {

    /** Raw: no type argument names an entity. */
    @SuppressWarnings("rawtypes")
    @Repository
    interface RawRepository extends BasicRepository {}

    /** The name reflection gives a parameter without {@code @Param}: real with -parameters, argN without. */
    private static String nameOf(String method, Class<?>... types) throws NoSuchMethodException {
        var parameter = GadgetRepository.class.getMethod(method, types).getParameters()[0];
        return parameter.isNamePresent() ? parameter.getName() : "arg0";
    }

    @Test
    void theDeclaredMethodsInNameOrderWithTheirKindQueryParametersAndReturns() throws Exception {
        RepositoryReader.Read read = RepositoryReader.read(GadgetRepository.class, 1_000);

        String gadget = nameOf("add", Gadget.class);
        assertEquals(List.of(
                new Method("add", "@Insert", "", gadget + ": Gadget", "Gadget"),
                new Method("byLabel", "@Find", "", nameOf("byLabel", String.class) + ": String", "Optional<Gadget>"),
                new Method("change", "@Update", "", gadget + ": Gadget", "Gadget"),
                new Method("countByStockCountGreaterThan", "derived", "", "min: int", "long"),
                new Method("findByLabel", "derived", "", nameOf("findByLabel", String.class) + ": String",
                        "List<Gadget>"),
                new Method("keep", "@Save", "", gadget + ": Gadget", "Gadget"),
                new Method("remove", "@Delete", "", gadget + ": Gadget", "void"),
                new Method("search", "JDQL", "FROM Gadget WHERE label LIKE :pattern ORDER BY id",
                        "pattern: String", "List<Gadget>"),
                new Method("stockCounts", "other", "", "", "int[]"),
                new Method("totals", "other", "", "", "Map<String, ? extends Number>")), read.methods());
    }

    @Test
    void thePrimaryEntityAndIdAndTheInheritedLine() {
        RepositoryReader.Read gadgets = RepositoryReader.read(GadgetRepository.class, 1_000);
        assertEquals(Gadget.class, gadgets.entity());
        assertEquals("Long", gadgets.idType());
        assertEquals("inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll",
                gadgets.inherits());

        RepositoryReader.Read orders = RepositoryReader.read(OrderRepository.class, 1_000);
        assertEquals(Order.class, orders.entity());
        assertEquals("UUID", orders.idType());
        assertEquals("inherits CrudRepository: delete, deleteAll, deleteById, findAll, findById, insert, insertAll,"
                + " save, saveAll, update, updateAll", orders.inherits());

        RepositoryReader.Read broken = RepositoryReader.read(BrokenRepository.class, 1_000);
        assertEquals(Broken.class, broken.entity());
        assertEquals(List.of(), broken.methods());
    }

    @Test
    void primaryTypesThroughAGenericBaseInterface() {
        RepositoryReader.Read customers = RepositoryReader.read(CustomerRepository.class, 1_000);

        assertEquals(Customer.class, customers.entity());
        assertEquals("String", customers.idType());
        assertEquals("", customers.inherits(), "DataRepository has no method to inherit");
        assertEquals(List.of(new Method("existsByName", "derived", "",
                        (CustomerRepository.class.getMethods()[0].getParameters()[0].isNamePresent() ? "name" : "arg0")
                                + ": String", "boolean")),
                customers.methods());
    }

    @Test
    void aRepositoryWithoutJakartaDataSuperInterfaceHasNoPrimaryEntity() {
        RepositoryReader.Read reports = RepositoryReader.read(ReportQueries.class, 1_000);

        assertNull(reports.entity());
        assertNull(reports.idType());
        assertEquals("", reports.inherits());
        assertEquals(List.of("gadgetCount", "orderCount"), reports.methods().stream().map(Method::name).toList());
        assertEquals("SELECT count(this) FROM Gadget", reports.methods().getFirst().query());
    }

    @Test
    void aRawBasicRepositoryHasNoPrimaryEntity() {
        RepositoryReader.Read raw = RepositoryReader.read(RawRepository.class, 1_000);

        assertNull(raw.entity());
        assertEquals("inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll",
                raw.inherits());
    }

    @Test
    void aLongQueryIsCut() {
        assertEquals("FROM Gadge…", RepositoryReader.read(GadgetRepository.class, 10).methods().stream()
                .filter(m -> m.name().equals("search")).findFirst().orElseThrow().query());
        assertEquals("abc", RepositoryReader.cut("abc", 3));
        assertEquals("ab…", RepositoryReader.cut("abc", 2));
    }

    @Test
    void aParameterIsNamedByParamThenByItsRealNameThenArgN() {
        assertEquals("pattern", RepositoryReader.parameterName("pattern", true, "p", 0));
        assertEquals("pattern", RepositoryReader.parameterName("pattern", false, "arg0", 0));
        assertEquals("label", RepositoryReader.parameterName(null, true, "label", 0));
        assertEquals("label", RepositoryReader.parameterName(" ", true, "label", 0));
        assertEquals("arg2", RepositoryReader.parameterName(null, false, "arg2", 2));
    }

    @Test
    void theDerivedRule() {
        for (String name : List.of("findByLabel", "countByProject", "existsByName", "deleteByStatus")) {
            assertEquals(true, RepositoryReader.derived(name), name);
        }
        for (String name : List.of("findAll", "byLabel", "searchByText", "count")) {
            assertEquals(false, RepositoryReader.derived(name), name);
        }
    }
}
```

- [ ] **Step 4: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension test -Dtest='TypeNamesTest,RepositoryReaderTest' 2>&1 | grep -E "ERROR|BUILD" | head -15
```
Expected: COMPILATION ERROR, `cannot find symbol ... TypeNames`, `RepositoryReader`, `MansartDataCatalogue`.

- [ ] **Step 5: Write the records and the holder**

`live/MansartDataCatalogue.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live;

import java.util.List;

/**
 * What Mansart Data knows about the application, read once per boot by the runtime extension: its entities with
 * their table and columns, its repositories with their methods. It holds names and texts only, never a class, a bean,
 * a class loader or a connection, so that the {@code -dev} panel holding it across a dev reload keeps nothing of the
 * previous boot alive.
 *
 * @param entities        the primary entities of the repositories, in name order, up to the limit
 * @param repositories    the repositories, in name order, up to the limit
 * @param entityCount     how many entities there are, those past the limit included
 * @param repositoryCount how many repositories there are, those past the limit included
 * @param methodCount     how many methods all the repositories declare
 */
public record MansartDataCatalogue(List<Entity> entities, List<Repository> repositories, int entityCount,
                                   int repositoryCount, int methodCount) {

    public MansartDataCatalogue {
        entities = List.copyOf(entities);
        repositories = List.copyOf(repositories);
    }

    /** How many entities are counted but not listed. */
    public int moreEntities() {
        return entityCount - entities.size();
    }

    /** How many repositories are counted but not listed. */
    public int moreRepositories() {
        return repositoryCount - repositories.size();
    }

    /** The listed repositories whose primary entity is {@code entity}, in name order. */
    public List<Repository> repositoriesOf(Entity entity) {
        return repositories.stream().filter(r -> entity.className().equals(r.entityClassName())).toList();
    }

    /** The listed repositories with no primary entity, in name order. */
    public List<Repository> otherRepositories() {
        return repositories.stream().filter(r -> r.entityClassName() == null).toList();
    }

    /**
     * An entity, as Mansart's own model describes it.
     *
     * @param name      its simple name, or its full name when another entity has the same simple name
     * @param className its full class name
     * @param table     {@code schema.table}, or the table alone; empty when its model could not be read
     * @param columns   the id first, then the version, then the others in model order; empty when its model could not
     *                  be read
     * @param failure   why its model could not be read, the class of the exception or {@code unusable model};
     *                  {@code null} when it was read
     */
    public record Entity(String name, String className, String table, List<Column> columns, String failure) {

        public Entity {
            columns = List.copyOf(columns);
        }
    }

    /**
     * One attribute of an entity.
     *
     * @param field    the attribute name
     * @param column   the column name
     * @param type     the simple name of its Java type
     * @param key      {@code id} ({@code id, generated}), {@code version}, {@code enum}, {@code → <Entity>},
     *                 {@code joined}, or empty
     * @param nullable whether the column accepts null
     * @param unique   whether the column is unique
     */
    public record Column(String field, String column, String type, String key, boolean nullable, boolean unique) {}

    /**
     * A repository interface.
     *
     * @param name            its simple name, or its full name when another repository has the same simple name
     * @param className       its full interface name
     * @param entityClassName the full class name of its primary entity, {@code null} when it has none
     * @param entityName      the catalogue's name of that entity, {@code null} when it has none
     * @param idType          the simple name of its id type, {@code null} when it has no primary entity
     * @param methods         its declared methods, in name order, up to the limit
     * @param methodCount     how many methods it declares
     * @param inherits        {@code inherits BasicRepository: delete, …}, or empty
     */
    public record Repository(String name, String className, String entityClassName, String entityName, String idType,
                             List<Method> methods, int methodCount, String inherits) {

        public Repository {
            methods = List.copyOf(methods);
        }

        /** How many declared methods are counted but not listed. */
        public int moreMethods() {
            return methodCount - methods.size();
        }
    }

    /**
     * A declared method of a repository.
     *
     * @param name       the method name
     * @param kind       {@code JDQL}, {@code @Find}, {@code @Insert}, {@code @Update}, {@code @Delete}, {@code @Save},
     *                   {@code derived} or {@code other}
     * @param query      the {@code @Query} text as written, cut after 1,000 characters; empty for the other kinds
     * @param parameters {@code name: Type}, comma-separated
     * @param returns    the generic return type in simple names
     */
    public record Method(String name, String kind, String query, String parameters, String returns) {}
}
```

`live/MansartDataLive.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live;

import java.util.Objects;
import java.util.Optional;

/**
 * The catalogue the Mansart Data extension built, for its {@code -dev} panel only: published at the end of
 * {@code onStart}, cleared first thing in {@code onStop}, so that a dev reload never shows the previous boot's
 * catalogue.
 */
public final class MansartDataLive {

    private static volatile MansartDataCatalogue catalogue;

    private MansartDataLive() {}

    /** The catalogue of the running boot, empty before it is built and once the extension stopped. */
    public static Optional<MansartDataCatalogue> catalogue() {
        return Optional.ofNullable(catalogue);
    }

    public static void publish(MansartDataCatalogue built) {
        catalogue = Objects.requireNonNull(built, "catalogue");
    }

    public static void clear() {
        catalogue = null;
    }
}
```

- [ ] **Step 6: Write `TypeNames`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.StringJoiner;

/** A type as a developer writes it, in simple names: {@code List<Task>}, {@code Optional<Task>}, {@code long}. */
final class TypeNames {

    private TypeNames() {}

    /** The type in simple names; its raw name when it cannot be printed, {@code ?} when even that fails. */
    static String of(Type type) {
        try {
            return print(type);
        } catch (RuntimeException | LinkageError unreadable) {
            return raw(type);
        }
    }

    private static String raw(Type type) {
        try {
            return type.getTypeName();
        } catch (RuntimeException | LinkageError unreadable) {
            return "?";
        }
    }

    private static String print(Type type) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                return print(c.getComponentType()) + "[]";
            }
            String simple = c.getSimpleName();
            return simple.isEmpty() ? c.getName() : simple;
        }
        if (type instanceof ParameterizedType parameterized) {
            StringJoiner arguments = new StringJoiner(", ", "<", ">");
            for (Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(print(argument));
            }
            return print(parameterized.getRawType()) + arguments;
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return "? super " + print(wildcard.getLowerBounds()[0]);
            }
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 || upper[0] == Object.class ? "?" : "? extends " + print(upper[0]);
        }
        if (type instanceof GenericArrayType array) {
            return print(array.getGenericComponentType()) + "[]";
        }
        if (type instanceof TypeVariable<?> variable) {
            return variable.getName();
        }
        return raw(type);
    }
}
```

- [ ] **Step 7: Write `RepositoryReader`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.DataRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Find;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reads one {@code @Repository} interface by reflection, once per boot: its primary entity and id type, its declared
 * methods, and the methods it inherits from Jakarta Data. Best effort: what reflection cannot read is left out, never
 * thrown — a method it cannot read is kept as {@code other}, with no parameters.
 */
final class RepositoryReader {

    /** The Jakarta Data interfaces whose type arguments name a repository's primary entity and its id. */
    private static final Set<Class<?>> PRIMARY =
            Set.of(BasicRepository.class, CrudRepository.class, DataRepository.class);

    private static final String JAKARTA_DATA = "jakarta.data.repository";

    /**
     * What a repository interface says of itself.
     *
     * @param entity   its primary entity, {@code null} when it has none
     * @param idType   the simple name of its id type, {@code null} when it has no primary entity
     * @param methods  all its declared methods, in name order
     * @param inherits {@code inherits BasicRepository: delete, …}, or empty
     */
    record Read(Class<?> entity, String idType, List<MansartDataCatalogue.Method> methods, String inherits) {}

    private RepositoryReader() {}

    /** Reads {@code repository}, cutting a query after {@code maxQuery} characters. */
    static Read read(Class<?> repository, int maxQuery) {
        Type[] primary = primaryTypes(repository);
        Class<?> entity = primary == null ? null : rawClass(primary[0]);
        String idType = entity == null ? null : TypeNames.of(primary[1]);
        return new Read(entity, idType, methods(repository, maxQuery), inherits(repository));
    }

    /**
     * The type arguments of {@code BasicRepository}, {@code CrudRepository} or {@code DataRepository}, however deep in
     * the super-interfaces, with the type variables of the interfaces in between bound; {@code null} when there is
     * none or it cannot be read.
     */
    static Type[] primaryTypes(Class<?> repository) {
        try {
            return primaryTypes(repository, Map.of());
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    private static Type[] primaryTypes(Class<?> type, Map<TypeVariable<?>, Type> bindings) {
        for (Type superType : type.getGenericInterfaces()) {
            Class<?> raw;
            Type[] arguments;
            if (superType instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> rawType) {
                raw = rawType;
                arguments = parameterized.getActualTypeArguments().clone();
                for (int i = 0; i < arguments.length; i++) {
                    Type bound = bindings.get(arguments[i]);
                    if (bound != null) {
                        arguments[i] = bound;
                    }
                }
            } else if (superType instanceof Class<?> rawType) {
                raw = rawType;
                arguments = new Type[0];
            } else {
                continue;
            }
            if (PRIMARY.contains(raw) && arguments.length == 2) {
                return arguments;
            }
            Map<TypeVariable<?>, Type> next = new HashMap<>();
            TypeVariable<?>[] parameters = raw.getTypeParameters();
            for (int i = 0; i < parameters.length && i < arguments.length; i++) {
                next.put(parameters[i], arguments[i]);
            }
            Type[] found = primaryTypes(raw, next);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The class a type argument names, or {@code null} for a type variable or a wildcard. */
    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    /** The interface's own methods, neither static, private nor synthetic, by name then parameters. */
    static List<MansartDataCatalogue.Method> methods(Class<?> repository, int maxQuery) {
        Method[] declared;
        try {
            declared = repository.getDeclaredMethods();
        } catch (RuntimeException | LinkageError unreadable) {
            return List.of();
        }
        List<MansartDataCatalogue.Method> methods = new ArrayList<>();
        for (Method method : declared) {
            int modifiers = method.getModifiers();
            if (method.isSynthetic() || method.isBridge() || Modifier.isStatic(modifiers)
                    || Modifier.isPrivate(modifiers)) {
                continue;
            }
            methods.add(method(method, maxQuery));
        }
        methods.sort(Comparator.comparing(MansartDataCatalogue.Method::name)
                .thenComparing(MansartDataCatalogue.Method::parameters));
        return methods;
    }

    private static MansartDataCatalogue.Method method(Method method, int maxQuery) {
        try {
            Query query = method.getAnnotation(Query.class);
            return new MansartDataCatalogue.Method(method.getName(), kind(method, query),
                    query == null ? "" : cut(query.value(), maxQuery), parameters(method), returns(method));
        } catch (RuntimeException | LinkageError unreadable) {
            return new MansartDataCatalogue.Method(method.getName(), "other", "", "", "");
        }
    }

    private static String kind(Method method, Query query) {
        if (query != null) {
            return "JDQL";
        }
        if (method.isAnnotationPresent(Find.class)) {
            return "@Find";
        }
        if (method.isAnnotationPresent(Insert.class)) {
            return "@Insert";
        }
        if (method.isAnnotationPresent(Update.class)) {
            return "@Update";
        }
        if (method.isAnnotationPresent(Delete.class)) {
            return "@Delete";
        }
        if (method.isAnnotationPresent(Save.class)) {
            return "@Save";
        }
        return derived(method.getName()) ? "derived" : "other";
    }

    /** A query derived from the method name: it starts with find, count, exists or delete, and holds By. */
    static boolean derived(String name) {
        return (name.startsWith("find") || name.startsWith("count") || name.startsWith("exists")
                || name.startsWith("delete")) && name.contains("By");
    }

    private static String parameters(Method method) {
        Parameter[] parameters = method.getParameters();
        Type[] types = parameterTypes(method);
        StringJoiner out = new StringJoiner(", ");
        for (int i = 0; i < parameters.length; i++) {
            Param param = parameters[i].getAnnotation(Param.class);
            String name = parameterName(param == null ? null : param.value(), parameters[i].isNamePresent(),
                    parameters[i].getName(), i);
            out.add(name + ": " + TypeNames.of(i < types.length ? types[i] : parameters[i].getType()));
        }
        return out.toString();
    }

    /** The name from {@code @Param}, else the real name when compiled with {@code -parameters}, else {@code argN}. */
    static String parameterName(String param, boolean namePresent, String realName, int index) {
        if (param != null && !param.isBlank()) {
            return param;
        }
        return namePresent ? realName : "arg" + index;
    }

    private static Type[] parameterTypes(Method method) {
        try {
            return method.getGenericParameterTypes();
        } catch (RuntimeException | LinkageError unreadable) {
            return method.getParameterTypes();
        }
    }

    private static String returns(Method method) {
        try {
            return TypeNames.of(method.getGenericReturnType());
        } catch (RuntimeException | LinkageError unreadable) {
            return TypeNames.of(method.getReturnType());
        }
    }

    /**
     * {@code inherits BasicRepository: delete, …}: the first {@code jakarta.data.repository} interfaces reached, and
     * the names of their methods, each once, in name order; empty when there is none, or none with a method.
     */
    static String inherits(Class<?> repository) {
        try {
            Map<String, Class<?>> reached = new TreeMap<>();
            collectJakartaData(repository, reached, new HashSet<>());
            Set<String> names = new TreeSet<>();
            for (Class<?> type : reached.values()) {
                for (Method method : type.getMethods()) {
                    if (!Modifier.isStatic(method.getModifiers())) {
                        names.add(method.getName());
                    }
                }
            }
            if (names.isEmpty()) {
                return "";
            }
            return "inherits " + String.join(", ", reached.keySet()) + ": " + String.join(", ", names);
        } catch (RuntimeException | LinkageError unreadable) {
            return "";
        }
    }

    private static void collectJakartaData(Class<?> type, Map<String, Class<?>> reached, Set<Class<?>> seen) {
        for (Class<?> superType : type.getInterfaces()) {
            if (!seen.add(superType)) {
                continue;
            }
            if (JAKARTA_DATA.equals(superType.getPackageName())) {
                reached.put(superType.getSimpleName(), superType);
            } else {
                collectJakartaData(superType, reached, seen);
            }
        }
    }

    /** {@code text} cut after {@code max} characters, with {@code …}. */
    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
```

- [ ] **Step 8: Export the `live` package to the `-dev` module**

In `EXT/src/main/java/module-info.java`, before the `provides` clause, add:

```java
    // What the catalogue panel of the -dev module reads; no other module sees it.
    exports io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live
            to io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;
```
(javac warns that the target module is not found until Task 5 exists; the pool module has the same warning pattern.)

- [ ] **Step 9: Run the tests to see them pass**

Same command as Step 4. Expected: `Tests run: 10` across the two classes, `Failures: 0, Errors: 0`, `BUILD SUCCESS`.
If `primaryTypesThroughAGenericBaseInterface` fails on the parameter name, check `getMethods()[0]` is
`existsByName` (it is the only method of `CustomerRepository` and its super-interfaces).

- [ ] **Step 10: Commit**

Message (`.git/PLAN_COMMIT_MSG` in the Vidocq repo):
```
feat(mansart-data): read a repository interface for the catalogue

First brick of the Mansart Data catalogue (spec 2026-09-28): the immutable
MansartDataCatalogue records and the MansartDataLive holder, in a live package
exported to the coming -dev module only, and RepositoryReader, which reads one
@Repository interface by reflection: its primary entity and id type through
BasicRepository, CrudRepository or DataRepository however deep, its declared
methods with their kind, @Query text, parameters and generic return type, and
the methods it inherits from Jakarta Data as one line. Best effort: nothing
it cannot read is thrown.

The extension's tests now fork on the class path: EntityModels.of needs the
entity package open to Mansart, which a patched test module is not.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
E=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension
P=io/vidocq/runtime/extensions/jakartaee/web/mansart/data
git add $E/pom.xml $E/src/main/java/module-info.java $E/src/main/java/$P/live/MansartDataCatalogue.java \
  $E/src/main/java/$P/live/MansartDataLive.java $E/src/main/java/$P/TypeNames.java \
  $E/src/main/java/$P/RepositoryReader.java $E/src/test/java/$P/CatalogueFixtures.java \
  $E/src/test/java/$P/TypeNamesTest.java $E/src/test/java/$P/RepositoryReaderTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: The catalogue builder

**Files:**
- Create: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/CatalogueBuilder.java`
- Test: `EXT/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/CatalogueBuilderTest.java`

**Interfaces:**
- Consumes: `RepositoryReader.read`, `RepositoryReader.Read`, `TypeNames.of`, the records of `MansartDataCatalogue`
  (Task 2); `EntityModels.of` (Task 1); `CatalogueFixtures` (Task 2).
- Produces:
  - `CatalogueBuilder(int maxEntities, int maxRepositories, int maxMethods, int maxQuery)`;
    `static final CatalogueBuilder DEFAULT` (200, 200, 200, 1,000); `static final String UNUSABLE = "unusable model"`;
  - `MansartDataCatalogue build(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models)`;
  - `static List<Class<?>> repositoryInterfaces(Iterable<Class<?>> beanClasses)` (sorted by class name);
  - `static Map<Class<?>, String> names(Collection<Class<?>> types)`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Gadget;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.GadgetRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.GadgetRepositoryImpl;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.ReportQueries;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link CatalogueBuilder}: the repositories and Mansart's entity models, as one immutable catalogue. */
class CatalogueBuilderTest {

    private static final Function<Class<?>, EntityModel<?>> MODELS = CatalogueFixtures::model;

    /** A second class whose simple name is {@code Gadget}. */
    static final class Twin {
        static final class Gadget {}
    }

    private static MansartDataCatalogue build() {
        return CatalogueBuilder.DEFAULT.build(CatalogueFixtures.REPOSITORIES, MODELS);
    }

    private static Entity entity(MansartDataCatalogue catalogue, String name) {
        return catalogue.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void theRepositoriesOfTheBeansEachOnceTheGeneratedImplementationIncluded() {
        assertEquals(List.of(GadgetRepository.class, ReportQueries.class), CatalogueBuilder.repositoryInterfaces(
                List.of(GadgetRepositoryImpl.class, GadgetRepository.class, String.class, ReportQueries.class)));
    }

    @Test
    void theCatalogueCountsAndOrdersEverything() {
        MansartDataCatalogue catalogue = build();

        assertEquals(List.of("Broken", "Customer", "Gadget", "Order"),
                catalogue.entities().stream().map(Entity::name).toList());
        assertEquals(List.of("BrokenRepository", "CustomerRepository", "GadgetRepository", "OrderRepository",
                "ReportQueries"), catalogue.repositories().stream().map(MansartDataCatalogue.Repository::name).toList());
        assertEquals(4, catalogue.entityCount());
        assertEquals(5, catalogue.repositoryCount());
        assertEquals(14, catalogue.methodCount());
        assertEquals(0, catalogue.moreEntities());
        assertEquals(0, catalogue.moreRepositories());
    }

    @Test
    void eachRepositoryNamesItsEntityAndCountsItsMethods() {
        MansartDataCatalogue catalogue = build();
        MansartDataCatalogue.Repository gadgets = catalogue.repositories().get(2);

        assertEquals("GadgetRepository", gadgets.name());
        assertEquals(GadgetRepository.class.getName(), gadgets.className());
        assertEquals(Gadget.class.getName(), gadgets.entityClassName());
        assertEquals("Gadget", gadgets.entityName());
        assertEquals("Long", gadgets.idType());
        assertEquals(10, gadgets.methodCount());
        assertEquals(10, gadgets.methods().size());
        assertEquals(List.of("GadgetRepository"), catalogue.repositoriesOf(entity(catalogue, "Gadget")).stream()
                .map(MansartDataCatalogue.Repository::name).toList());
        assertEquals(List.of("ReportQueries"),
                catalogue.otherRepositories().stream().map(MansartDataCatalogue.Repository::name).toList());
        assertNull(catalogue.otherRepositories().getFirst().entityName());
    }

    @Test
    void anEntityBuiltAtRunTimeByMansart() {
        Entity gadget = entity(build(), "Gadget");

        assertEquals("gadgets", gadget.table());
        assertNull(gadget.failure());
        assertEquals(List.of(
                new Column("id", "id", "Long", "id", false, true),
                new Column("label", "label", "String", "", true, false),
                new Column("stockCount", "stock_count", "Integer", "", true, false)), gadget.columns());
    }

    @Test
    void theIdFirstThenTheVersionThenTheOthersInModelOrder() {
        Entity order = entity(build(), "Order");

        assertEquals("shop.orders", order.table());
        assertEquals(List.of(
                new Column("id", "id", "UUID", "id", false, true),
                new Column("version", "row_version", "Integer", "version", false, false),
                new Column("note", "note", "String", "", true, true),
                new Column("customer", "customer_id", "Customer", "→ Customer", true, false),
                new Column("status", "status", "Status", "enum", false, false),
                new Column("name", "name", "String", "joined", true, false)), order.columns());
    }

    @Test
    void anEntityWhoseModelFailsKeepsItsPlaceWithTheExceptionClass() {
        Entity broken = entity(build(), "Broken");

        assertEquals(Broken.class.getName(), broken.className());
        assertEquals("", broken.table());
        assertEquals(List.of(), broken.columns());
        assertEquals("io.vidocq.mansart.data.core.MansartDataException", broken.failure());
    }

    @Test
    void anUnusableModelIsSaidSo() {
        MansartDataCatalogue catalogue = CatalogueBuilder.DEFAULT.build(List.of(GadgetRepository.class), type -> null);

        assertEquals(CatalogueBuilder.UNUSABLE, catalogue.entities().getFirst().failure());
    }

    @Test
    void aLinkageErrorFromTheModelIsReportedNotThrown() {
        MansartDataCatalogue catalogue = CatalogueBuilder.DEFAULT.build(List.of(GadgetRepository.class), type -> {
            throw new NoClassDefFoundError("io/acme/_Gadget");
        });

        assertEquals("java.lang.NoClassDefFoundError", catalogue.entities().getFirst().failure());
    }

    @Test
    void pastALimitTheRestIsCountedNotListed() {
        MansartDataCatalogue catalogue =
                new CatalogueBuilder(1, 3, 3, 1_000).build(CatalogueFixtures.REPOSITORIES, MODELS);

        assertEquals(List.of("Broken"), catalogue.entities().stream().map(Entity::name).toList());
        assertEquals(3, catalogue.moreEntities());
        assertEquals(List.of("BrokenRepository", "CustomerRepository", "GadgetRepository"),
                catalogue.repositories().stream().map(MansartDataCatalogue.Repository::name).toList());
        assertEquals(2, catalogue.moreRepositories());
        MansartDataCatalogue.Repository gadgets = catalogue.repositories().get(2);
        assertEquals(List.of("add", "byLabel", "change"),
                gadgets.methods().stream().map(MansartDataCatalogue.Method::name).toList());
        assertEquals(7, gadgets.moreMethods());
        assertEquals(14, catalogue.methodCount(), "every declared method is counted");
    }

    @Test
    void aLongQueryIsCut() {
        MansartDataCatalogue catalogue =
                new CatalogueBuilder(200, 200, 200, 10).build(List.of(GadgetRepository.class), MODELS);

        assertEquals("FROM Gadge…", catalogue.repositories().getFirst().methods().stream()
                .filter(m -> m.name().equals("search")).findFirst().orElseThrow().query());
    }

    @Test
    void twoTypesWithOneSimpleNameAreShownUnderTheirFullNames() {
        Map<Class<?>, String> names = CatalogueBuilder.names(List.of(Gadget.class, Twin.Gadget.class, Broken.class));

        assertEquals(Gadget.class.getName(), names.get(Gadget.class));
        assertEquals(Twin.Gadget.class.getName(), names.get(Twin.Gadget.class));
        assertEquals("Broken", names.get(Broken.class));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension test -Dtest=CatalogueBuilderTest 2>&1 | grep -E "ERROR|BUILD" | head -10
```
Expected: COMPILATION ERROR, `cannot find symbol ... CatalogueBuilder`.

- [ ] **Step 3: Write `CatalogueBuilder`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.VersionAttribute;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Builds the {@link MansartDataCatalogue} once per boot, from the repository interfaces the container holds and the
 * entity models Mansart itself uses. Best effort: a model that fails keeps its entity in the catalogue, with the class
 * of the exception and no columns; nothing is thrown for it.
 *
 * <p>Every repository is read and counted; only the lists stop at the limits.
 */
final class CatalogueBuilder {

    /** The limits of spec §3: 200 entities, 200 repositories, 200 methods per repository, 1,000 query characters. */
    static final CatalogueBuilder DEFAULT = new CatalogueBuilder(200, 200, 200, 1_000);

    /** The reason of a model that came back {@code null}, without id, or without table. */
    static final String UNUSABLE = "unusable model";

    private final int maxEntities;
    private final int maxRepositories;
    private final int maxMethods;
    private final int maxQuery;

    CatalogueBuilder(int maxEntities, int maxRepositories, int maxMethods, int maxQuery) {
        this.maxEntities = maxEntities;
        this.maxRepositories = maxRepositories;
        this.maxMethods = maxMethods;
        this.maxQuery = maxQuery;
    }

    /**
     * The {@code @Repository} interfaces among these bean classes and the interfaces they implement, each once — the
     * {@code *RepositoryImpl} bean mansart-data-processor generates counts as its interface — sorted by name.
     */
    static List<Class<?>> repositoryInterfaces(Iterable<Class<?>> beanClasses) {
        Set<Class<?>> found = new HashSet<>();
        for (Class<?> beanClass : beanClasses) {
            if (beanClass == null) {
                continue;
            }
            for (Class<?> itf : beanClass.getInterfaces()) {
                if (itf.isAnnotationPresent(jakarta.data.repository.Repository.class)) {
                    found.add(itf);
                    break;
                }
            }
            if (beanClass.isInterface() && beanClass.isAnnotationPresent(jakarta.data.repository.Repository.class)) {
                found.add(beanClass);
            }
        }
        return found.stream().sorted(Comparator.comparing(Class::getName)).toList();
    }

    /** The catalogue of these repository interfaces, their entities' models read through {@code models}. */
    MansartDataCatalogue build(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models) {
        Map<Class<?>, RepositoryReader.Read> reads = new LinkedHashMap<>();
        for (Class<?> repository : repositories) {
            reads.put(repository, RepositoryReader.read(repository, maxQuery));
        }
        List<Class<?>> entityTypes = reads.values().stream()
                .map(RepositoryReader.Read::entity)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Class<?>, String> entityNames = names(entityTypes);
        Map<Class<?>, String> repositoryNames = names(reads.keySet());
        int methodCount = reads.values().stream().mapToInt(read -> read.methods().size()).sum();

        List<MansartDataCatalogue.Entity> entities = entityTypes.stream()
                .sorted(Comparator.comparing(entityNames::get))
                .limit(maxEntities)
                .map(type -> entity(type, entityNames.get(type), models))
                .toList();

        List<Class<?>> ordered = new ArrayList<>(reads.keySet());
        ordered.sort(Comparator.comparing(repositoryNames::get));
        List<MansartDataCatalogue.Repository> listed = new ArrayList<>();
        for (Class<?> type : ordered.subList(0, Math.min(ordered.size(), maxRepositories))) {
            RepositoryReader.Read read = reads.get(type);
            List<MansartDataCatalogue.Method> methods = read.methods();
            Class<?> entity = read.entity();
            listed.add(new MansartDataCatalogue.Repository(repositoryNames.get(type), type.getName(),
                    entity == null ? null : entity.getName(), entity == null ? null : entityNames.get(entity),
                    read.idType(), methods.subList(0, Math.min(methods.size(), maxMethods)), methods.size(),
                    read.inherits()));
        }
        return new MansartDataCatalogue(entities, listed, entityTypes.size(), reads.size(), methodCount);
    }

    /** The name the catalogue gives each type: its simple name, its full name when another type shares it. */
    static Map<Class<?>, String> names(Collection<Class<?>> types) {
        Map<String, Integer> uses = new HashMap<>();
        for (Class<?> type : types) {
            uses.merge(type.getSimpleName(), 1, Integer::sum);
        }
        Map<Class<?>, String> names = new HashMap<>();
        for (Class<?> type : types) {
            String simple = type.getSimpleName();
            names.put(type, simple.isEmpty() || uses.get(simple) > 1 ? type.getName() : simple);
        }
        return names;
    }

    private static MansartDataCatalogue.Entity entity(Class<?> type, String name,
                                                      Function<Class<?>, EntityModel<?>> models) {
        EntityModel<?> model;
        try {
            model = models.apply(type);
        } catch (RuntimeException | LinkageError failure) {
            return failed(type, name, failure.getClass().getName());
        }
        if (model == null || model.id() == null || model.tableName() == null || model.tableName().isBlank()) {
            return failed(type, name, UNUSABLE);
        }
        try {
            return new MansartDataCatalogue.Entity(name, type.getName(), table(model), columns(model), null);
        } catch (RuntimeException | LinkageError failure) {
            return failed(type, name, failure.getClass().getName());
        }
    }

    private static MansartDataCatalogue.Entity failed(Class<?> type, String name, String failure) {
        return new MansartDataCatalogue.Entity(name, type.getName(), "", List.of(), failure);
    }

    private static String table(EntityModel<?> model) {
        String schema = model.schema();
        return schema == null || schema.isBlank() ? model.tableName() : schema + "." + model.tableName();
    }

    /** The id first, then the version, then the other attributes in model order. */
    private static <E> List<Column> columns(EntityModel<E> model) {
        IdAttribute<E, ?> id = model.id();
        VersionAttribute<E, ?> version = model.version() == null ? null : model.version().orElse(null);
        List<Column> columns = new ArrayList<>();
        columns.add(column(id));
        if (version != null) {
            columns.add(column(version));
        }
        for (Attribute<E, ?> attribute : model.attributes()) {
            if (attribute == null || attribute.name().equals(id.name())
                    || (version != null && attribute.name().equals(version.name()))) {
                continue;
            }
            columns.add(column(attribute));
        }
        return columns;
    }

    private static Column column(Attribute<?, ?> attribute) {
        Class<?> type = attribute.javaType();
        return new Column(text(attribute.name()), text(attribute.columnName()), type == null ? "" : TypeNames.of(type),
                key(attribute), attribute.nullable(), attribute.unique());
    }

    private static String key(Attribute<?, ?> attribute) {
        return switch (attribute) {
            case IdAttribute<?, ?> id -> id.generated() ? "id, generated" : "id";
            case VersionAttribute<?, ?> _ -> "version";
            case EnumAttribute<?, ?> _ -> "enum";
            case ReferenceAttribute<?, ?> reference -> "→ " + TypeNames.of(reference.javaType());
            case JoinedAttribute<?, ?> _ -> "joined";
            default -> "";
        };
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
```

- [ ] **Step 4: Run the extension's tests to see them pass**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension test 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
Expected: all tests pass (`CatalogueBuilderTest` 11, plus Task 2's and the existing one), `BUILD SUCCESS`.
If `anEntityBuiltAtRunTimeByMansart` fails with `unavailable`, the fork is not on the class path: check Task 2 Step 1.

- [ ] **Step 5: Commit**

Message:
```
feat(mansart-data): build the Mansart Data catalogue

CatalogueBuilder turns the @Repository interfaces the container holds into the
catalogue: every repository read once, the generated *RepositoryImpl bean
counted as its interface, and each primary entity described by the model
Mansart itself uses, EntityModels.of: its table, schema-qualified when it has
one, and its columns, id first, then version, then the others in model order,
with their key (id, generated, version, enum, a reference, joined), nullable
and unique. A model that throws, an Error included, or comes back unusable
keeps its entity in place with the exception class. Past 200 entities, 200
repositories or 200 methods the rest is counted, not listed; a query is cut
after 1,000 characters. Two types with one simple name show under their full
names.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
E=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension
P=io/vidocq/runtime/extensions/jakartaee/web/mansart/data
git add $E/src/main/java/$P/CatalogueBuilder.java $E/src/test/java/$P/CatalogueBuilderTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: The `mansart-data` section and `MANSART-DATA-001`

**Files:**
- Modify: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/MansartDataIntegrationExtension.java`
  (full replacement below)
- Modify: `EXT/src/main/java/module-info.java` (Javadoc only)
- Test: `EXT/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/RecordedSection.java`,
  `ReportContext.java`, `MansartDataSectionTest.java`

**Interfaces:**
- Consumes: `CatalogueBuilder.DEFAULT`, `CatalogueBuilder(int, int, int, int)`, `CatalogueBuilder.repositoryInterfaces`,
  `build` (Task 3); `MansartDataLive`, `MansartDataCatalogue` (Task 2); `EntityModels.of` (Task 1).
- Produces: `MansartDataIntegrationExtension implements VidocqExtension, StartupReportContributor` with `id()` =
  `mansart-data`, `title()` = `Mansart Data`, `static final String ANOMALY = "MANSART-DATA-001"`,
  `static final String HINT`, package-private `void catalogue(List<Class<?>>, Function<Class<?>, EntityModel<?>>)`
  and `void catalogue(List<Class<?>>, Function<Class<?>, EntityModel<?>>, CatalogueBuilder)`, `onStop()` clearing
  `MansartDataLive` first.

- [ ] **Step 1: Write the test helpers**

`RecordedSection.java` (same as the pool module's, for this section):

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A {@link StartupReportSection} that keeps what a contributor writes, as the report and the dev console both receive
 * it: the summary, the rows in order, and the anomalies.
 */
final class RecordedSection implements StartupReportSection {

    /** A row or a list: its key, then its values. */
    record Row(String key, List<String> values) {}

    /** An anomaly, as {@link StartupReportSection#anomaly} received it. */
    record Anomaly(String code, String message, String hint) {}

    private final List<Row> rows = new ArrayList<>();
    private final List<Anomaly> anomalies = new ArrayList<>();
    private String summary;

    @Override
    public StartupReportSection summary(String text) {
        summary = text;
        return this;
    }

    @Override
    public StartupReportSection row(String key, Object value) {
        rows.add(new Row(Objects.requireNonNull(key, "key"), List.of(String.valueOf(value))));
        return this;
    }

    @Override
    public StartupReportSection list(String key, Collection<String> items) {
        if (items != null && !items.isEmpty()) {
            rows.add(new Row(Objects.requireNonNull(key, "key"), List.copyOf(items)));
        }
        return this;
    }

    @Override
    public StartupReportSection secret(String key, boolean configured) {
        return row(key, configured ? "configured" : "not configured");
    }

    @Override
    public StartupReportSection listener(String name, String boundBaseUri) {
        throw new UnsupportedOperationException("Mansart Data declares no listener");
    }

    @Override
    public StartupReportSection route(String listener, String method, String path, String handler) {
        throw new UnsupportedOperationException("Mansart Data serves no route");
    }

    @Override
    public StartupReportSection anomaly(String code, String message, String hint) {
        anomalies.add(new Anomaly(Objects.requireNonNull(code, "code"), Objects.requireNonNull(message, "message"),
                hint));
        return this;
    }

    /** The summary line, or {@code null} when none was written. */
    String summary() {
        return summary;
    }

    /** The keys of the rows and lists, in order. */
    List<String> keys() {
        return rows.stream().map(Row::key).toList();
    }

    /** The values of the row {@code key}, joined with a comma, or {@code null} when there is no such row. */
    String value(String key) {
        return rows.stream().filter(r -> r.key().equals(key)).findFirst()
                .map(r -> String.join(", ", r.values())).orElse(null);
    }

    /** The anomalies, in order. */
    List<Anomaly> anomalies() {
        return List.copyOf(anomalies);
    }
}
```

`ReportContext.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.List;
import java.util.Optional;

/**
 * The context of the report: what a contributor reads while it writes its section. It knows no bean and no route.
 *
 * @param launchMode the launch mode of the boot
 * @param verbosity  how much the report shows
 */
record ReportContext(LaunchMode launchMode, Verbosity verbosity) implements StartupReportContext {

    /** The context the dev console gives: every row. */
    static ReportContext detailed() {
        return new ReportContext(LaunchMode.DEV, Verbosity.DETAILED);
    }

    @Override
    public boolean hasBeanOfType(String typeName) {
        return false;
    }

    @Override
    public <T> Optional<T> lookup(Class<T> type) {
        return Optional.empty();
    }

    @Override
    public List<String> routeUrls(String handlerClassName) {
        return List.of();
    }
}
```

- [ ] **Step 2: Write the failing test**

`MansartDataSectionTest.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.OrderRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.RecordedSection.Anomaly;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mansart-data} section of the startup report, written by {@link MansartDataIntegrationExtension} from the
 * catalogue it builds once per boot and publishes in {@link MansartDataLive} for the {@code -dev} panel.
 */
class MansartDataSectionTest {

    private final MansartDataIntegrationExtension ext = new MansartDataIntegrationExtension();

    @AfterEach
    void stop() {
        ext.onStop();
    }

    private RecordedSection section(ReportContext context) {
        RecordedSection section = new RecordedSection();
        ext.contribute(context, section);
        return section;
    }

    private void buildFixtures() {
        ext.catalogue(CatalogueFixtures.REPOSITORIES, CatalogueFixtures::model);
    }

    @Test
    void theExtensionContributesItsSection() {
        assertInstanceOf(StartupReportContributor.class, ext);
        assertEquals("mansart-data", ext.id());
        assertEquals("Mansart Data", ext.title());
        assertEquals("mansart-data", ext.name());
    }

    @Test
    void beforeOnStartThereIsNoCatalogue() {
        RecordedSection section = section(ReportContext.detailed());

        assertEquals("no catalogue: the extension did not start", section.summary());
        assertEquals(List.of(), section.keys());
        assertEquals(List.of(), section.anomalies());
    }

    @Test
    void theCatalogueIsPublishedForTheDevPanel() {
        buildFixtures();

        assertTrue(MansartDataLive.catalogue().isPresent());
        assertEquals(4, MansartDataLive.catalogue().orElseThrow().entityCount());
    }

    @Test
    void theSummaryAndTheDetailedRows() {
        buildFixtures();

        RecordedSection section = section(ReportContext.detailed());

        assertEquals("4 entities, 5 repositories, 14 methods", section.summary());
        assertEquals(List.of("Broken", "Customer", "Gadget", "Order",
                "BrokenRepository", "CustomerRepository", "GadgetRepository", "OrderRepository",
                "ReportQueries"), section.keys());
        assertEquals("model unavailable (io.vidocq.mansart.data.core.MansartDataException)", section.value("Broken"));
        assertEquals("customers, 2 columns", section.value("Customer"));
        assertEquals("gadgets, 3 columns", section.value("Gadget"));
        assertEquals("shop.orders, 6 columns", section.value("Order"));
        assertEquals("Broken, 0 methods", section.value("BrokenRepository"));
        assertEquals("Customer, 1 method", section.value("CustomerRepository"));
        assertEquals("Gadget, 10 methods", section.value("GadgetRepository"));
        assertEquals("Order, 1 method", section.value("OrderRepository"));
        assertEquals("no primary entity, 2 methods", section.value("ReportQueries"));
    }

    @Test
    void anEntityWhoseModelFailsRaisesOneAnomalyWithoutItsMessage() {
        buildFixtures();

        List<Anomaly> anomalies = section(ReportContext.detailed()).anomalies();

        assertEquals(List.of(new Anomaly("MANSART-DATA-001",
                "The model of entity " + Broken.class.getName()
                        + " could not be read (io.vidocq.mansart.data.core.MansartDataException)",
                "Check its mapping annotations; Mansart could not build its model, so its repositories may fail"
                        + " too.")), anomalies);
        assertFalse(anomalies.getFirst().message().contains("@Id"), "the exception's message is never shown");
    }

    @Test
    void belowDetailedOnlyTheSummaryAndTheAnomaliesAreWritten() {
        buildFixtures();

        for (Verbosity verbosity : List.of(Verbosity.SUMMARY, Verbosity.OFF)) {
            RecordedSection section = section(new ReportContext(LaunchMode.DEV, verbosity));

            assertEquals("4 entities, 5 repositories, 14 methods", section.summary());
            assertEquals(List.of(), section.keys(), verbosity + ": the rows would not be printed");
            assertEquals(1, section.anomalies().size(), verbosity + ": an anomaly is never lost");
        }
    }

    @Test
    void singularFormsForOne() {
        ext.catalogue(List.of(OrderRepository.class), CatalogueFixtures::model);

        assertEquals("1 entity, 1 repository, 1 method", section(ReportContext.detailed()).summary());
    }

    @Test
    void pastALimitARowCountsTheRest() {
        ext.catalogue(CatalogueFixtures.REPOSITORIES, CatalogueFixtures::model, new CatalogueBuilder(1, 1, 200, 1_000));

        RecordedSection section = section(ReportContext.detailed());

        assertEquals("4 entities, 5 repositories, 14 methods", section.summary());
        assertEquals(List.of("Broken", "more entities", "BrokenRepository", "more repositories"), section.keys());
        assertEquals("and 3 more", section.value("more entities"));
        assertEquals("and 4 more", section.value("more repositories"));
    }

    @Test
    void aCatalogueThatCannotBeBuiltNeverFailsTheBoot() {
        ext.catalogue(null, CatalogueFixtures::model);

        assertEquals("no catalogue (java.lang.NullPointerException)", section(ReportContext.detailed()).summary());
        assertTrue(MansartDataLive.catalogue().isEmpty());
    }

    @Test
    void onStopClearsTheCatalogueFirst() {
        buildFixtures();
        var published = MansartDataLive.catalogue().orElseThrow();
        assertSame(published, MansartDataLive.catalogue().orElseThrow());

        ext.onStop();

        assertTrue(MansartDataLive.catalogue().isEmpty(), "a dev console poll now reads no catalogue");
        assertEquals("no catalogue: the extension did not start", section(ReportContext.detailed()).summary());
    }
}
```

- [ ] **Step 3: Run it to see it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension test -Dtest=MansartDataSectionTest 2>&1 | grep -E "ERROR|BUILD" | head -10
```
Expected: COMPILATION ERROR (`cannot find symbol ... id()`, `catalogue(...)`, `contribute`).

- [ ] **Step 4: Replace `MansartDataIntegrationExtension.java`** (license header, then):

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Glue between Vidocq lifecycle and the Mansart Jakarta Data 1.0 stack.
 *
 * <p>This extension does <b>not</b> register the {@code mansart-data-cdi} BCE — Vauban picks it
 * up via the standard CDI 4.1 ServiceLoader contract
 * ({@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}).
 * Instead, it does three things at {@code onStart}:
 *
 * <ol>
 *   <li><b>Fail-fast connectivity check</b>: opens a connection from the {@code @Default}
 *       {@link DataSource} and immediately closes it. A misconfigured JDBC URL or unreachable
 *       database surfaces here at boot, not on the first user request.</li>
 *   <li><b>Repository inventory log</b>: walks the {@link BeanManager} and prints the
 *       {@code @Repository} interfaces that have been wired, so an operator can confirm at a
 *       glance that the APT generation + BCE discovery actually fired.</li>
 *   <li><b>Catalogue</b>: reads those repositories and, through {@link EntityModels#of}, the model Mansart uses for
 *       each primary entity, once, into a {@link MansartDataCatalogue} of names and texts. It is the
 *       {@code mansart-data} section of the startup report, and the {@code vidocq-runtime-mansart-data-extension-dev}
 *       companion, which only {@code vidocq:dev} adds, shows it live from {@link MansartDataLive}. An entity whose
 *       model cannot be read raises {@value #ANOMALY}; nothing here fails the boot or opens a connection.</li>
 * </ol>
 *
 * <p>The connectivity check is opt-out via {@code vidocq.data.checkOnStart=false} for tests or
 * deployments that boot before the database is reachable (typical migration pipelines).
 *
 * <p>Priority {@code 300} — runs after the pool extension (200) which publishes the
 * {@code DataSource}, but before any HTTP transport (Cassini at 500) so a broken database
 * configuration aborts the boot before exposing a port.
 */
public final class MansartDataIntegrationExtension implements VidocqExtension, StartupReportContributor {

    private static final System.Logger LOG =
            System.getLogger(MansartDataIntegrationExtension.class.getName());

    private static final String P_CHECK_ON_START = "vidocq.data.checkOnStart";

    /** An entity whose model Mansart could not build. */
    static final String ANOMALY = "MANSART-DATA-001";

    static final String HINT =
            "Check its mapping annotations; Mansart could not build its model, so its repositories may fail too.";

    /** The catalogue of this boot, {@code null} before {@code onStart} and after {@code onStop}. */
    private volatile MansartDataCatalogue catalogue;

    /** Why there is no catalogue although {@code onStart} ran: the class of the exception; {@code null} otherwise. */
    private volatile String unavailable;

    @Override
    public String name() {
        return "mansart-data";
    }

    @Override
    public String id() {
        return "mansart-data";
    }

    @Override
    public String title() {
        return "Mansart Data";
    }

    @Override
    public int priority() {
        return 300;
    }

    @Override
    public void onStart(ExtensionContext context) {
        BeanManager bm = context.beanManager();

        if (context.config().getValue(P_CHECK_ON_START, Boolean.class, Boolean.TRUE)) {
            checkDataSourceReachable(bm);
        }
        List<Class<?>> repositories = CatalogueBuilder.repositoryInterfaces(beanClasses(bm));
        logRepositoryInventory(repositories);
        catalogue(repositories, type -> EntityModels.of(type));
    }

    /** Builds the catalogue with the limits of the spec, keeps it and publishes it. Visible for tests. */
    void catalogue(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models) {
        catalogue(repositories, models, CatalogueBuilder.DEFAULT);
    }

    /** Builds the catalogue, keeps it and publishes it; never throws: the boot never fails for it. Visible for tests. */
    void catalogue(List<Class<?>> repositories, Function<Class<?>, EntityModel<?>> models, CatalogueBuilder builder) {
        try {
            MansartDataCatalogue built = builder.build(repositories, models);
            catalogue = built;
            unavailable = null;
            MansartDataLive.publish(built);
        } catch (RuntimeException | LinkageError failure) {
            catalogue = null;
            unavailable = failure.getClass().getName();
            MansartDataLive.clear();
            LOG.log(System.Logger.Level.WARNING, "Mansart Data: the catalogue could not be built", failure);
        }
    }

    @Override
    public void onStop() {
        // First: a dev console poll from now on reads no catalogue.
        MansartDataLive.clear();
        catalogue = null;
        unavailable = null;
    }

    /**
     * The catalogue, from memory: the summary, then at {@link Verbosity#DETAILED} a row per entity and per repository,
     * then one {@value #ANOMALY} per entity whose model could not be read, at every verbosity.
     */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        MansartDataCatalogue read = catalogue;
        if (read == null) {
            String why = unavailable;
            section.summary(why == null ? "no catalogue: the extension did not start" : "no catalogue (" + why + ")");
            return;
        }
        section.summary(count(read.entityCount(), "entity", "entities") + ", "
                + count(read.repositoryCount(), "repository", "repositories") + ", "
                + count(read.methodCount(), "method", "methods"));
        if (context.verbosity() == Verbosity.DETAILED) {
            for (MansartDataCatalogue.Entity entity : read.entities()) {
                section.row(entity.name(), entity.failure() == null
                        ? entity.table() + ", " + count(entity.columns().size(), "column", "columns")
                        : "model unavailable (" + entity.failure() + ")");
            }
            if (read.moreEntities() > 0) {
                section.row("more entities", "and " + read.moreEntities() + " more");
            }
            for (MansartDataCatalogue.Repository repository : read.repositories()) {
                if (repository.entityClassName() != null) {
                    section.row(repository.name(), repository.entityName() + ", "
                            + count(repository.methodCount(), "method", "methods"));
                }
            }
            for (MansartDataCatalogue.Repository repository : read.otherRepositories()) {
                section.row(repository.name(),
                        "no primary entity, " + count(repository.methodCount(), "method", "methods"));
            }
            if (read.moreRepositories() > 0) {
                section.row("more repositories", "and " + read.moreRepositories() + " more");
            }
        }
        for (MansartDataCatalogue.Entity entity : read.entities()) {
            if (entity.failure() != null) {
                section.anomaly(ANOMALY, "The model of entity " + entity.className() + " could not be read ("
                        + entity.failure() + ")", HINT);
            }
        }
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static void checkDataSourceReachable(BeanManager bm) {
        Set<Bean<?>> dsBeans = bm.getBeans(DataSource.class, AnyLiteral.INSTANCE);
        if (dsBeans.isEmpty()) {
            throw new IllegalStateException(
                    "Mansart Data extension is enabled but no DataSource bean is exposed by CDI. "
                            + "Either deploy the mansart-pool extension with vidocq.pool.url, or "
                            + "publish your own @Produces DataSource.");
        }
        // Resolve via lookup() so Default qualifier semantics apply; the @Any literal above is
        // only there to enumerate candidates for the diagnostic message.
        DataSource ds = (DataSource) bm.getReference(
                bm.resolve(bm.getBeans(DataSource.class)),
                DataSource.class,
                bm.createCreationalContext(null));
        try (Connection c = ds.getConnection()) {
            if (!c.isValid(2)) {
                throw new IllegalStateException(
                        "Mansart Data: DataSource handed out an invalid connection on boot");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Mansart Data: cannot reach the configured DataSource on boot", e);
        }
        LOG.log(System.Logger.Level.INFO, "Mansart Data: DataSource reachable");
    }

    /**
     * The class of every bean. We cannot ask the BeanManager directly for an annotated type set without crossing CDI
     * Lite limits, so the repositories are picked among them.
     */
    private static List<Class<?>> beanClasses(BeanManager bm) {
        List<Class<?>> classes = new ArrayList<>();
        for (Bean<?> bean : bm.getBeans(Object.class, AnyLiteral.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass != null) {
                classes.add(beanClass);
            }
        }
        return classes;
    }

    private static void logRepositoryInventory(List<Class<?>> repositories) {
        if (repositories.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING,
                    "Mansart Data: no @Repository interface discovered. "
                            + "Did the APT processor (mansart-data-processor) run on the application module?");
        } else {
            LOG.log(System.Logger.Level.INFO,
                    "Mansart Data: " + repositories.size() + " repository(ies) wired: "
                            + repositories.stream().map(Class::getName).toList());
        }
    }

    /** {@code @Any} qualifier literal — kept off the hot path. */
    private static final class AnyLiteral
            extends jakarta.enterprise.util.AnnotationLiteral<Any> implements Any {
        static final AnyLiteral INSTANCE = new AnyLiteral();
        private AnyLiteral() {}
    }
}
```

In `EXT/src/main/java/module-info.java`, replace the module Javadoc's first paragraph so it reads:

```java
/**
 * Vidocq extension that fails fast on boot if Mansart Data cannot reach its {@link
 * javax.sql.DataSource}, logs the {@code @Repository} interfaces wired by the underlying
 * {@code mansart-data-cdi} BCE, and writes their catalogue — entities, columns, repositories and methods — as the
 * {@code mansart-data} section of the startup report.
 *
 * <p>The actual repository discovery is done by Vauban's automatic scan of
 * {@code META-INF/services/jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension}
 * — this extension does not register the BCE itself; it only validates wiring at boot.
 */
```

- [ ] **Step 5: Run the module's tests to see them pass**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension test 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
Expected: every test passes (`MansartDataSectionTest` 10), `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

Message:
```
feat(mansart-data): the mansart-data section of the startup report, and MANSART-DATA-001

MansartDataIntegrationExtension is now a StartupReportContributor: it builds
the catalogue once in onStart, after the connectivity check and the
repository log, which stay, publishes it in MansartDataLive for the coming
-dev panel, and clears it first thing in onStop. The section, Mansart Data,
sums it up as "2 entities, 2 repositories, 7 methods" and adds, in the
detailed report, a row per entity (its table and column count) and per
repository (its entity and method count, or "no primary entity"). An entity
whose model Mansart cannot build raises MANSART-DATA-001 with the exception
class, never its message. Building the catalogue never fails the boot.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
E=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension
P=io/vidocq/runtime/extensions/jakartaee/web/mansart/data
git add $E/src/main/java/module-info.java $E/src/main/java/$P/MansartDataIntegrationExtension.java \
  $E/src/test/java/$P/RecordedSection.java $E/src/test/java/$P/ReportContext.java \
  $E/src/test/java/$P/MansartDataSectionTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: The `-dev` module and its live panel

**Files:**
- Create: `DEV/pom.xml`, `DEV/src/main/java/module-info.java`,
  `DEV/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanel.java`,
  `DEV/src/main/resources/META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`
- Create: `EXT/src/main/resources/META-INF/vidocq/dev-module`
- Modify: `WEB/pom.xml` (modules), root `pom.xml` (`dependencyManagement`)
- Modify: `EXT/src/test/java/.../mansart/data/MansartDataSectionTest.java` (one test)
- Test: `DEV/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanelTest.java`,
  `RecordedSample.java`

**Interfaces:**
- Consumes: `MansartDataLive.catalogue()`, `MansartDataCatalogue` and its nested records and `repositoriesOf`,
  `otherRepositories`, `moreEntities`, `moreRepositories`, `Repository.moreMethods()` (Task 2).
- Produces: artifact `io.vidocq.runtime.extensions.jakartaee.web:vidocq-runtime-mansart-data-extension-dev`, module
  `io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev`, `CatalogueLivePanel implements LivePanel`
  (`id()` = `mansart-data`), `static String key(String name, Set<String> used)`.

- [ ] **Step 1: Wire the module**

`WEB/pom.xml`, in `<modules>`, after `<module>vidocq-runtime-mansart-data-extension</module>`:
```xml
        <module>vidocq-runtime-mansart-data-extension-dev</module>
```

Root `pom.xml`, in `dependencyManagement`, right after the `vidocq-runtime-mansart-data-extension` entry:
```xml
            <dependency>
                <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
                <artifactId>vidocq-runtime-mansart-data-extension-dev</artifactId>
                <version>${project.version}</version>
            </dependency>
```

`EXT/src/main/resources/META-INF/vidocq/dev-module` (one line, trailing newline):
```
vidocq-runtime-mansart-data-extension-dev
```

`DEV/pom.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
        <artifactId>vidocq-runtime-extensions-jakartaee-web</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </parent>

    <artifactId>vidocq-runtime-mansart-data-extension-dev</artifactId>
    <name>Vidocq :: Core Extensions :: Mansart Data :: dev console panel</name>
    <description>The Mansart Data catalogue panel of the dev console. Only vidocq:dev adds it; no binary contains it
        (Vidocq/vidocq#143).</description>

    <dependencies>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
            <artifactId>vidocq-runtime-mansart-data-extension</artifactId>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-devconsole-spi</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <!-- Vidocq/vidocq#143: a dev tool; vidocq:dev adds it, no binary ever contains it. -->
                            <Vidocq-Dev-Only>true</Vidocq-Dev-Only>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
            <!--
                module-info.java lives in the standard src/main/java, so it compiles normally. Surefire still
                must not fork on the module path: the reactor does not assemble a full module path for ad-hoc
                test forks. Same pattern as vidocq-runtime-mansart-pool-extension-dev.
            -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <useModulePath>false</useModulePath>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

`DEV/src/main/java/module-info.java` (license header, then):
```java
/** The Mansart Data catalogue panel of the dev console, which only vidocq:dev adds (Vidocq/vidocq#143). */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev {
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;
    requires io.vidocq.runtime.spi.devconsole;

    provides io.vidocq.runtime.spi.devconsole.LivePanel
            with io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.CatalogueLivePanel;
}
```

`DEV/src/main/resources/META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`:
```
io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.CatalogueLivePanel
```

- [ ] **Step 2: Pin the descriptor in the runtime module's test**

Add to `MansartDataSectionTest` (imports: `static org.junit.jupiter.api.Assertions.assertFalse` is already there):

```java
    @Test
    void theJarNamesItsDevCompanionAndIsNoPanel() throws Exception {
        try (var in = MansartDataIntegrationExtension.class.getClassLoader()
                .getResourceAsStream("META-INF/vidocq/dev-module")) {
            assertEquals("vidocq-runtime-mansart-data-extension-dev", new String(in.readAllBytes()).strip());
        }
        assertFalse(java.util.Arrays.stream(MansartDataIntegrationExtension.class.getInterfaces())
                .anyMatch(type -> type.getName().startsWith("io.vidocq.runtime.spi.devconsole")));
    }
```

- [ ] **Step 3: Write the test helper and the failing panel test**

`RecordedSample.java` (`DEV/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/`), a copy of the
pool module's:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link PanelSample} that keeps what a panel writes on one poll, checked as the dev console checks it: every key
 * follows {@link PanelSample#requireKey}, a group is named and does not nest. The values are records, so that a test
 * compares them whole.
 */
final class RecordedSample implements PanelSample {

    /** One value of a scope. */
    sealed interface Value permits Gauge, Counter, Elapsed, Text, Absent, Table {}

    /** @param max the most it can reach, {@code null} for none */
    record Gauge(double value, Double max, Unit unit) implements Value {}

    record Counter(long total, Unit unit) implements Value {}

    record Elapsed(Duration value) implements Value {}

    record Text(String value) implements Value {}

    record Absent(String reason) implements Value {}

    record Table(List<String> columns, List<List<String>> rows) implements Value {}

    private final boolean group;
    private final Map<String, Value> values = new LinkedHashMap<>();
    private final Map<String, RecordedSample> groups = new LinkedHashMap<>();

    /** The sample of a panel, with its groups. */
    RecordedSample() {
        this(false);
    }

    private RecordedSample(boolean group) {
        this.group = group;
    }

    private PanelSample put(String key, Value value) {
        values.put(PanelSample.requireKey(key), value);
        return this;
    }

    @Override
    public PanelSample gauge(String key, double value, Unit unit) {
        return put(key, new Gauge(value, null, Objects.requireNonNull(unit, "unit")));
    }

    @Override
    public PanelSample gauge(String key, double value, double max, Unit unit) {
        return put(key, new Gauge(value, max, Objects.requireNonNull(unit, "unit")));
    }

    @Override
    public PanelSample counter(String key, long total, Unit unit) {
        return put(key, new Counter(total, Objects.requireNonNull(unit, "unit")));
    }

    @Override
    public PanelSample duration(String key, Duration value) {
        return put(key, value == null ? new Absent(null) : new Elapsed(value));
    }

    @Override
    public PanelSample text(String key, String value) {
        return put(key, value == null ? new Absent(null) : new Text(value));
    }

    @Override
    public PanelSample absent(String key, String reason) {
        return put(key, new Absent(reason));
    }

    @Override
    public PanelSample table(String key, List<String> columns, List<List<String>> rows) {
        return put(key, new Table(List.copyOf(columns), List.copyOf(rows)));
    }

    @Override
    public PanelSample group(String name) {
        if (group) {
            throw new IllegalStateException("groups do not nest");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a group has a name");
        }
        return groups.computeIfAbsent(name, n -> new RecordedSample(true));
    }

    /** The keys of this scope, in the order they were first written. */
    List<String> keys() {
        return List.copyOf(values.keySet());
    }

    /** The value {@code key} of this scope, or {@code null}. */
    Value value(String key) {
        return values.get(key);
    }

    /** The names of the groups, in the order of their first call. */
    List<String> groupNames() {
        return List.copyOf(groups.keySet());
    }

    /** The group {@code name}, which the panel wrote. */
    RecordedSample written(String name) {
        RecordedSample found = groups.get(name);
        if (found == null) {
            throw new AssertionError("no group " + name + " in " + groups.keySet());
        }
        return found;
    }
}
```

`CatalogueLivePanelTest.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Absent;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Text;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Method;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Repository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mansart-data} section, live: {@link CatalogueLivePanel} reads {@link MansartDataLive}, the holder the
 * runtime extension publishes at the end of {@code onStart} and clears first thing in {@code onStop}.
 */
class CatalogueLivePanelTest {

    private final CatalogueLivePanel panel = new CatalogueLivePanel();

    @AfterEach
    void clear() {
        MansartDataLive.clear();
    }

    private RecordedSample sample() {
        RecordedSample sample = new RecordedSample();
        panel.sample(sample);
        return sample;
    }

    /** Two entities, one whose model failed, three repositories of theirs and one with no primary entity. */
    private static MansartDataCatalogue catalogue() {
        Entity broken = new Entity("Broken", "com.acme.Broken", "", List.of(), "com.acme.MappingException");
        Entity task = new Entity("Task", "com.acme.Task", "shop.tasks", List.of(
                new Column("id", "id", "Long", "id, generated", false, true),
                new Column("title", "title", "String", "", false, false),
                new Column("project", "project_id", "Project", "→ Project", true, false)), null);
        Repository brokenRepository = new Repository("BrokenRepository", "com.acme.BrokenRepository",
                "com.acme.Broken", "Broken", "Long", List.of(), 0, "inherits BasicRepository: delete, findAll");
        Repository reports = new Repository("ReportQueries", "com.acme.ReportQueries", null, null, null,
                List.of(new Method("taskCount", "JDQL", "SELECT count(this) FROM Task", "", "long")), 1, "");
        Repository archive = new Repository("TaskArchive", "com.acme.TaskArchive", "com.acme.Task", "Task", "Long",
                List.of(new Method("findByProject", "derived", "", "arg0: String", "List<Task>")), 1, "");
        Repository tasks = new Repository("TaskRepository", "com.acme.TaskRepository", "com.acme.Task", "Task",
                "Long", List.of(
                        new Method("countByProject", "derived", "", "project: String", "long"),
                        new Method("searchText", "JDQL", "FROM Task WHERE title LIKE :pattern", "pattern: String",
                                "List<Task>")), 5,
                "inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll");
        return new MansartDataCatalogue(List.of(broken, task), List.of(brokenRepository, reports, archive, tasks),
                3, 4, 9);
    }

    @Test
    void itMakesTheMansartDataSectionLiveWithNoChartAndNoAction() {
        assertEquals("mansart-data", panel.id());
        assertEquals(List.of(), panel.charts());
        assertEquals(List.of(), panel.actions());
    }

    @Test
    void withNoCatalogueTheSampleSaysSo() {
        RecordedSample sample = sample();

        assertEquals(List.of("catalogue"), sample.keys());
        assertEquals(new Absent("no catalogue yet"), sample.value("catalogue"));
        assertEquals(List.of(), sample.groupNames());
    }

    @Test
    void oneGroupPerEntityInOrderThenTheOtherRepositories() {
        MansartDataLive.publish(catalogue());

        RecordedSample sample = sample();

        assertEquals(List.of("Broken", "Task", "Other repositories"), sample.groupNames());
        assertEquals(List.of("more-entities"), sample.keys());
        assertEquals(new Text("and 1 more"), sample.value("more-entities"));
    }

    @Test
    void anEntityGroupHoldsItsTableItsColumnsAndItsRepositories() {
        MansartDataLive.publish(catalogue());

        RecordedSample task = sample().written("Task");

        assertEquals(List.of("table", "columns", "task-archive", "task-repository", "task-repository.inherits",
                "task-repository.more"), task.keys());
        assertEquals(new Text("shop.tasks"), task.value("table"));
        assertEquals(new Table(List.of("field", "column", "type", "key", "nullable", "unique"), List.of(
                List.of("id", "id", "Long", "id, generated", "", "yes"),
                List.of("title", "title", "String", "", "", ""),
                List.of("project", "project_id", "Project", "→ Project", "yes", ""))), task.value("columns"));
        assertEquals(new Table(List.of("method", "kind", "query", "parameters", "returns"), List.of(
                List.of("countByProject", "derived", "", "project: String", "long"),
                List.of("searchText", "JDQL", "FROM Task WHERE title LIKE :pattern", "pattern: String",
                        "List<Task>"))), task.value("task-repository"));
        assertEquals(new Text("inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save,"
                + " saveAll"), task.value("task-repository.inherits"));
        assertEquals(new Text("and 3 more"), task.value("task-repository.more"));
    }

    @Test
    void anEntityWhoseModelFailedSaysWhyInsteadOfItsColumns() {
        MansartDataLive.publish(catalogue());

        RecordedSample broken = sample().written("Broken");

        assertEquals(List.of("model", "broken-repository", "broken-repository.inherits"), broken.keys());
        assertEquals(new Text("unavailable: com.acme.MappingException"), broken.value("model"));
        assertEquals(new Table(List.of("method", "kind", "query", "parameters", "returns"), List.of()),
                broken.value("broken-repository"));
    }

    @Test
    void theOtherRepositoriesHaveTheirOwnGroup() {
        MansartDataLive.publish(catalogue());

        RecordedSample others = sample().written("Other repositories");

        assertEquals(List.of("report-queries"), others.keys());
        assertEquals(new Table(List.of("method", "kind", "query", "parameters", "returns"),
                        List.of(List.of("taskCount", "JDQL", "SELECT count(this) FROM Task", "", "long"))),
                others.value("report-queries"));
    }

    @Test
    void afterTheExtensionStoppedThereIsNoCatalogueAgain() {
        MansartDataLive.publish(catalogue());
        MansartDataLive.clear();

        assertEquals(new Absent("no catalogue yet"), sample().value("catalogue"));
    }

    @Test
    void keysAreValidAndDistinct() {
        Set<String> used = new HashSet<>(Set.of("table", "columns", "model"));
        List<String> keys = List.of(
                CatalogueLivePanel.key("TaskRepository", used),
                CatalogueLivePanel.key("TaskEventRepository", used),
                CatalogueLivePanel.key("Columns", used),
                CatalogueLivePanel.key("TaskRepository", used),
                CatalogueLivePanel.key("_Weird", used),
                CatalogueLivePanel.key("9Lives", used),
                CatalogueLivePanel.key("com.acme.orders.TaskRepository", used),
                CatalogueLivePanel.key("AVeryLongRepositoryNameThatGoesOnAndOnForeverAndEver", used));

        assertEquals(List.of("task-repository", "task-event-repository", "columns-2", "task-repository-2", "weird",
                "r-9-lives", "com-acme-orders-task-repository", "avery-long-repository-name-that"), keys);
        for (String key : keys) {
            assertDoesNotThrow(() -> PanelSample.requireKey(key + ".inherits"), key);
            assertTrue(key.length() <= 31, key);
        }
    }
}
```

- [ ] **Step 4: Run it to see it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev install 2>&1 | grep -E "ERROR|BUILD" | head -10
```
Expected: the runtime module passes (its `theJarNamesItsDevCompanionAndIsNoPanel` included); the `-dev` module fails
with COMPILATION ERROR `cannot find symbol ... CatalogueLivePanel`.

- [ ] **Step 5: Write `CatalogueLivePanel`** (license header, then):

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Repository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Mansart Data section, live: the catalogue the runtime extension built at boot, from {@link MansartDataLive},
 * one group per entity with its table, its columns and its repositories, then a group for the repositories with no
 * primary entity. It reads that holder only: no bean, no connection, no query.
 *
 * <p>A value key must match {@code [a-z][a-z0-9.-]{0,39}}: a repository's table is keyed by its name in kebab case,
 * {@code TaskRepository} as {@code task-repository}, its inherited methods by {@code <key>.inherits}, and the count
 * of its methods past the limit by {@code <key>.more}.
 */
public final class CatalogueLivePanel implements LivePanel {

    static final List<String> COLUMNS = List.of("field", "column", "type", "key", "nullable", "unique");
    static final List<String> METHODS = List.of("method", "kind", "query", "parameters", "returns");
    static final String OTHER = "Other repositories";

    /** The longest key base, so that {@code <key>.inherits} stays within the 40 characters of a key. */
    private static final int MAX_BASE = 40 - ".inherits".length();

    /** The keys an entity group already uses. */
    private static final Set<String> RESERVED = Set.of("table", "columns", "model");

    /** Created by the service loader. */
    public CatalogueLivePanel() {}

    @Override
    public String id() {
        return "mansart-data";
    }

    @Override
    public void sample(PanelSample sample) {
        Optional<MansartDataCatalogue> published = MansartDataLive.catalogue();
        if (published.isEmpty()) {
            sample.absent("catalogue", "no catalogue yet");
            return;
        }
        MansartDataCatalogue catalogue = published.get();
        for (Entity entity : catalogue.entities()) {
            PanelSample group = sample.group(entity.name());
            if (entity.failure() == null) {
                group.text("table", entity.table());
                group.table("columns", COLUMNS, entity.columns().stream().map(CatalogueLivePanel::row).toList());
            } else {
                group.text("model", "unavailable: " + entity.failure());
            }
            Set<String> used = new HashSet<>(RESERVED);
            for (Repository repository : catalogue.repositoriesOf(entity)) {
                write(group, repository, used);
            }
        }
        List<Repository> others = catalogue.otherRepositories();
        if (!others.isEmpty()) {
            PanelSample group = sample.group(OTHER);
            Set<String> used = new HashSet<>(RESERVED);
            for (Repository repository : others) {
                write(group, repository, used);
            }
        }
        if (catalogue.moreEntities() > 0) {
            sample.text("more-entities", "and " + catalogue.moreEntities() + " more");
        }
        if (catalogue.moreRepositories() > 0) {
            sample.text("more-repositories", "and " + catalogue.moreRepositories() + " more");
        }
    }

    private static List<String> row(Column column) {
        return Arrays.asList(column.field(), column.column(), column.type(), column.key(),
                column.nullable() ? "yes" : "", column.unique() ? "yes" : "");
    }

    private static void write(PanelSample group, Repository repository, Set<String> used) {
        String key = key(repository.name(), used);
        group.table(key, METHODS, repository.methods().stream()
                .map(m -> Arrays.asList(m.name(), m.kind(), m.query(), m.parameters(), m.returns()))
                .toList());
        if (repository.inherits() != null && !repository.inherits().isEmpty()) {
            group.text(key + ".inherits", repository.inherits());
        }
        if (repository.moreMethods() > 0) {
            group.text(key + ".more", "and " + repository.moreMethods() + " more");
        }
    }

    /**
     * The key of a repository's table: its name in kebab case, {@code TaskRepository} as {@code task-repository},
     * starting with a letter, at most {@value #MAX_BASE} characters, and {@code -2}, {@code -3}… when {@code used}
     * already holds it. Adds the key to {@code used}.
     */
    static String key(String name, Set<String> used) {
        StringBuilder out = new StringBuilder();
        char previous = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                boolean afterWord = (previous >= 'a' && previous <= 'z') || (previous >= '0' && previous <= '9');
                if (!out.isEmpty() && afterWord) {
                    out.append('-');
                }
                out.append((char) (c + ('a' - 'A')));
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
            previous = c;
        }
        String base = out.toString();
        if (base.isEmpty() || base.charAt(0) < 'a' || base.charAt(0) > 'z') {
            base = "r-" + base;
        }
        base = trim(base, MAX_BASE);
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = trim(base, MAX_BASE - suffix.length()) + suffix;
        }
        return key;
    }

    private static String trim(String key, int max) {
        String cut = key.length() > max ? key.substring(0, max) : key;
        while (cut.endsWith("-")) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut;
    }
}
```

Check of the expected keys: `AVeryLongRepositoryNameThatGoesOnAndOnForeverAndEver` gives
`avery-long-repository-name-that-goes-…` (no dash between `A` and `V`: an uppercase letter after an uppercase one
starts no word), cut at 31 characters to `avery-long-repository-name-that` (31 characters, no trailing dash);
`com.acme.orders.TaskRepository` gives `com-acme-orders-task-repository` (31); `_Weird` gives `weird`; `9Lives` gives
`9-lives`, then `r-9-lives`. If an assertion of `keysAreValidAndDistinct` disagrees, recount by hand before touching
the code: the rule is the Javadoc above.

- [ ] **Step 6: Run both modules' tests to see them pass**

Same command as Step 4. Expected: `CatalogueLivePanelTest` 8 tests pass, `BUILD SUCCESS` for both modules, and
`unzip -p DEV/target/vidocq-runtime-mansart-data-extension-dev-0.4.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Vidocq-Dev-Only`
prints `Vidocq-Dev-Only: true`.

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): the Mansart Data panel of the dev console, in a -dev companion

New module vidocq-runtime-mansart-data-extension-dev, built like the pool's:
CatalogueLivePanel, a LivePanel for the mansart-data section, reads the
catalogue the runtime extension published, and nothing else. One group per
entity, in name order: its table, its columns (field, column, type, key,
nullable, unique) and, per repository, a methods table (method, kind, query,
parameters, returns) with the inherited Jakarta Data methods as one line; an
entity whose model failed says why instead. The repositories with no primary
entity get a group of their own. With no catalogue, the sample says "no
catalogue yet". Panel keys are the repository names in kebab case.

The runtime jar names the companion in META-INF/vidocq/dev-module, so
vidocq:dev adds it and the packaging goals drop it (Vidocq/vidocq#143).

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
W=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web
E=$W/vidocq-runtime-mansart-data-extension
D=$W/vidocq-runtime-mansart-data-extension-dev
P=io/vidocq/runtime/extensions/jakartaee/web/mansart/data
git add pom.xml $W/pom.xml $E/src/main/resources/META-INF/vidocq/dev-module \
  $E/src/test/java/$P/MansartDataSectionTest.java $D/pom.xml $D/src/main/java/module-info.java \
  $D/src/main/java/$P/dev/CatalogueLivePanel.java \
  $D/src/main/resources/META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel \
  $D/src/test/java/$P/dev/CatalogueLivePanelTest.java $D/src/test/java/$P/dev/RecordedSample.java
git status --short   # nothing else staged; no target/ file
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc`
- Modify: `docs/en/modules/ROOT/pages/reference.adoc`
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`
- Modify: `docs/en/modules/ROOT/pages/dev-console.adoc`

**Interfaces:**
- Consumes: the names, keys, summary and rows of Tasks 4 and 5.
- Produces: anchors `mansart-data-catalogue` and `mansart-data-001` in `vidocq-runtime-extensions.adoc`.

- [ ] **Step 1: The catalogue row of the extensions table**

In `vidocq-runtime-extensions.adoc`, replace
`| Jakarta Data 1.0 via Mansart — scans `@Repository`, implementations generated via APT (companion `-codegen` artefact).`
with
```
| Jakarta Data 1.0 via Mansart — scans `@Repository`, implementations generated via APT (companion `-codegen` artefact). Its section of the startup report is the application's entities and repositories, a dev console panel [.tag-new]#NEW# (<<mansart-data-catalogue>>).
```

- [ ] **Step 2: The catalogue section and `MANSART-DATA-001`**

In the same file, insert immediately before the line `[#mansart-transactions-jdbc-bridge]`:

````
[#mansart-data-catalogue]
== The Mansart Data catalogue in the startup report and the dev console [.tag-new]#NEW#

`vidocq-runtime-mansart-data-extension` writes the section `mansart-data`, *Mansart Data*, of the xref:modules/vidocq-runtime-core.adoc#startup-report[startup report]: what Mansart Data knows about the application, read once per boot from the CDI container and by reflection. Under `mvn vidocq:dev`, its companion `vidocq-runtime-mansart-data-extension-dev` shows it in the xref:dev-console.adoc[dev console] as the *Mansart Data* tab, one card per entity with its repositories inside. Nothing here opens a connection or runs a query.

*Repositories.* Every `@Repository` interface a bean implements, once: the implementation `mansart-data-processor` generates and its interface count as one. The primary entity and the id type come from the type arguments of `BasicRepository`, `CrudRepository` or `DataRepository`, however deep in the super-interfaces; a repository without one is listed under *Other repositories*. Its declared methods, in name order:

[cols="1,3"]
|===
| Column | Value

| `method`
| The method name.

| `kind`
| `JDQL` for `@Query`; `@Find`, `@Insert`, `@Update`, `@Delete` or `@Save`; `derived` for a name that starts with `find`, `count`, `exists` or `delete` and holds `By`; `other` otherwise.

| `query`
| The `@Query` text as written, cut after 1,000 characters; empty for the other kinds. The console shows the first 200.

| `parameters`
| `name: Type`, comma-separated. The name comes from `@Param`, else from the class file when the application is compiled with `-parameters`, else it is `arg0`, `arg1`…

| `returns`
| The generic return type in simple names: `List<Task>`, `Optional<Task>`, `long`, `void`.
|===

The methods inherited from Jakarta Data are one line under the table: `inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll`.

*Entities.* The primary entities of the repositories, each with the model Mansart itself uses, `EntityModels.of`: the generated `_Entity.$MODEL`, or the one Mansart builds at run time. The card shows its table, `schema.table` when it has a schema, and its columns, the id first, then the version, then the others in model order:

[cols="1,3"]
|===
| Column | Value

| `field`, `column`, `type`
| The attribute, its column, the simple name of its Java type.

| `key`
| `id`, or `id, generated`; `version`; `enum`; `→ <Entity>` for a reference; `joined` for an attribute reached through a relation; empty otherwise.

| `nullable`, `unique`
| `yes`, or empty.
|===

The summary counts the entities, the repositories and the methods they declare: `2 entities, 2 repositories, 7 methods`. The detailed report, which the console always has, adds a row per entity, its table and column count, then a row per repository, its entity and method count, the repositories without a primary entity last:

.A tasks application (shape of the section)
[source,text]
----
mansart-data  Mansart Data
  2 entities, 2 repositories, 7 methods
  Task                 tasks, 10 columns
  TaskEvent            task_events, 5 columns
  TaskEventRepository  TaskEvent, 1 method
  TaskRepository       Task, 6 methods
----

Two entities, or two repositories, with the same simple name are shown under their full names. The catalogue lists at most 200 entities, 200 repositories and 200 methods per repository; the rest is counted, not listed: `more entities  and 3 more`. The console keeps 64 cards and 100 rows per table.

The panel does not show the SQL of a derived method, which Mansart does not keep at run time, nor any statistic, nor Mansart persistence.

[#mansart-data-001]
=== MANSART-DATA-001 — the model of an entity cannot be read [.tag-new]#NEW#

Mansart could not build the model of a repository's primary entity: its generated metamodel failed, or the entity has no id field, no no-arg constructor, or a package Mansart cannot open. The entity keeps its place in the catalogue, without its columns, and its card says `unavailable:` with the class of the exception. The exception's message is never shown. The boot goes on, but the repositories of that entity will likely fail on their first call:

[source,text]
----
[MANSART-DATA-001] The model of entity com.acme.Invoice could not be read (io.vidocq.mansart.data.core.MansartDataException) Check its mapping annotations; Mansart could not build its model, so its repositories may fail too.
----

Check the entity's `jakarta.persistence` annotations, its id and its constructor, and that its package is open to `io.vidocq.mansart.data.core`.

````

- [ ] **Step 3: The anomaly table**

In `reference.adoc`, after the two lines
```
| xref:modules/vidocq-runtime-extensions.adoc#mansart-pool-002[`MANSART-POOL-002`] [.tag-new]#NEW#
| A named Mansart pool is open, but no `@Named` `DataSource` bean serves it.
```
insert (a blank line before it, as between the other rows):
```

| xref:modules/vidocq-runtime-extensions.adoc#mansart-data-001[`MANSART-DATA-001`] [.tag-new]#NEW#
| Mansart could not build the model of a repository's entity; the catalogue shows the entity without its columns.
```

- [ ] **Step 4: The dev console's panel table**

In `dev-console.adoc`, insert immediately before the line `| *REST (Cassini)* [.tag-new]#NEW#`:
```
| *Mansart Data* [.tag-new]#NEW#
| The catalogue of `vidocq-runtime-mansart-data-extension`, one card per entity: its table and columns, then each of its repositories with its methods, their kind, `@Query` text, parameters and return type, and the methods inherited from Jakarta Data. Read once per boot; no connection, no query. See xref:modules/vidocq-runtime-extensions.adoc#mansart-data-catalogue[the Mansart Data catalogue].

```

- [ ] **Step 5: What's new**

In `whats-new.adoc`, insert immediately before the line starting with
`* **The MCP request state is encrypted** [.tag-new]#NEW# —` this line:
```
* **A Mansart Data catalogue in the startup report and the dev console** [.tag-new]#NEW# — `vidocq-runtime-mansart-data-extension` writes a `mansart-data` section: the application's entities with their table and columns, as Mansart's own model gives them, and its repositories with each declared method's kind, `@Query` text, parameters and return type, plus the methods they inherit from Jakarta Data. Under `mvn vidocq:dev` its new companion `vidocq-runtime-mansart-data-extension-dev` shows it as the *Mansart Data* tab, one card per entity with its repositories inside. It reads names only: no connection, no query. An entity whose model Mansart cannot build keeps its place and raises `MANSART-DATA-001`. xref:modules/vidocq-runtime-extensions.adoc#mansart-data-catalogue[The Mansart Data catalogue].
```

- [ ] **Step 6: Check the anchors**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq/docs/en/modules/ROOT/pages
grep -c "mansart-data-catalogue" modules/vidocq-runtime-extensions.adoc whats-new.adoc dev-console.adoc
grep -n "\[#mansart-data-001\]\|#mansart-data-001\[" modules/vidocq-runtime-extensions.adoc reference.adoc
```
Expected: `vidocq-runtime-extensions.adoc` counts 2 (anchor and table link), the others 1; the anchor and the xref
both found.

- [ ] **Step 7: Commit**

Message:
```
docs(mansart-data): the Mansart Data catalogue, MANSART-DATA-001

A new section of the extensions page, marked NEW: the mansart-data section of
the startup report and its dev console tab, what each column holds, the
limits, and what is not shown (the SQL of derived methods, statistics,
Mansart persistence); MANSART-DATA-001 with its log line. The anomaly joins
the reference table, the panel joins the dev console's list of panels, and
What's new gets its entry.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
D=docs/en/modules/ROOT/pages
git add $D/modules/vidocq-runtime-extensions.adoc $D/reference.adoc $D/whats-new.adoc $D/dev-console.adoc
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: Verification — build, consumers, and the panel in Chrome

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, and commit
with a `fix(mansart-data): ...` message following the Global Constraints).

**Interfaces:**
- Consumes: everything above; the script
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh`
  (app on 18093, console on 18094).

- [ ] **Step 1: Build and install the touched modules**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl .,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/install.log" 2>&1; grep -E "Tests run:|WARN.*checkpom|BUILD" "$SCRATCH/install.log" | tail -10
```
Expected: `BUILD SUCCESS`, no test failure, no checkpom warning about the new module.

- [ ] **Step 2: Build the consumers**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example,vidocq-runtime-examples/vidocq-runtime-petstore-example > "$SCRATCH/consumers.log" 2>&1; grep -E "Tests run:|BUILD" "$SCRATCH/consumers.log" | tail -8
grep -rh "mansart-data\|MANSART-DATA-001" "$SCRATCH/consumers.log" vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/target/surefire-reports/ 2>/dev/null | head -10
```
Expected: `BUILD SUCCESS`; the H2 example's boots print a `mansart-data  Mansart Data` section with a summary such as
`N entities, N repositories, N methods`, and no `MANSART-DATA-001`. If a `MANSART-DATA-001` shows, read which entity
and why before anything else (superpowers:systematic-debugging): it is either a real mapping problem of the example
or a bug of Task 3.

- [ ] **Step 3: Free ports**

```bash
lsof -nP -iTCP:18093 -sTCP:LISTEN; lsof -nP -iTCP:18094 -sTCP:LISTEN; echo checked
```
Expected: only `checked`. If either port is taken, STOP and ask the user (never kill a process this plan did not
start; the script's ports are fixed).

- [ ] **Step 4: Start `vidocq:dev` on the test app**

Run with the Bash tool and `run_in_background: true` (the only background Maven run of this plan):
```bash
bash /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server "$SCRATCH/mcp-tasks-dev.log"
```
Then wait with the Monitor tool on an until-loop (not a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" "$SCRATCH/mcp-tasks-dev.log"; do sleep 2; done`.

- [ ] **Step 5: Check the log**

```bash
grep -E "Dev tools:|mansart-data|MANSART-DATA|Mansart Data:" "$SCRATCH/mcp-tasks-dev.log" | head -20
```
Expected:
- `Dev tools:` lists `vidocq-runtime-mansart-data-extension-dev` (with the pool's and the others);
- `Mansart Data: 2 repository(ies) wired: [io.vidocq.tools.lc4jcdi.mcptasks.TaskEventRepository, io.vidocq.tools.lc4jcdi.mcptasks.TaskRepository]`;
- the section `mansart-data  Mansart Data` with `2 entities, 2 repositories, 7 methods`;
- no `MANSART-DATA-001`.

- [ ] **Step 6: The panel in Chrome**

Load the Chrome tools in one call:
`ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__get_page_text,mcp__claude-in-chrome__find")`.

1. `tabs_context_mcp`, then `tabs_create_mcp` and `navigate` to `http://127.0.0.1:18094/`.
2. Before relying on polling, run with `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
3. Open the *Mansart Data* tab (`find` "Mansart Data", click it with `computer`), wait one poll, then `get_page_text`.
4. Check, and take a screenshot with `computer` for the report:
   - the summary `2 entities, 2 repositories, 7 methods`;
   - a card `Task` with `table` `tasks` and a `columns` table of 10 rows: `id` (`id, generated`), `title`,
     `description`, `project`, `status` (`enum`), `priority` (`enum`), `dueDate` / `due_date`, `createdAt` /
     `created_at`, `updatedAt` / `updated_at`, `completedAt` / `completed_at`;
   - in it, the table `task-repository` with six rows in this order: `countByProject` (`derived`,
     `project: String`, `long`), `findByProjectAndStatusOrderByDueDateAsc`, `findByProjectOrderByIdAsc`,
     `findByStatusOrderByDueDateAsc` (all `derived`), `renameProject` (`JDQL`,
     `UPDATE Task SET project = :newName, updatedAt = :now WHERE project = :oldName`,
     `oldName: String, newName: String, now: Instant`, `long`) and `searchText` (`JDQL`,
     `FROM Task WHERE LOWER(title) LIKE :pattern OR LOWER(description) LIKE :pattern ORDER BY id ASC`,
     `pattern: String`, `List<Task>`), and the line
     `inherits BasicRepository: delete, deleteAll, deleteById, findAll, findById, save, saveAll`;
   - a card `TaskEvent` with `table` `task_events`, 5 columns, and `task-event-repository` holding
     `findByTaskIdOrderByIdAsc` (`derived`, `taskId: Long`, `List<TaskEvent>`);
   - no *Other repositories* card, no `unavailable`, and the page's anomaly list has no `MANSART-DATA-001`.
   (The test app compiles with `<parameters>true</parameters>`, hence the real parameter names.)
5. `tabs_close_mcp` on the tab this step opened.

- [ ] **Step 7: Stop what this plan started**

Stop the background task of Step 4 with `TaskStop` (its id from Step 4), then check both ports are free again with
the command of Step 3. Do not touch any other process. `git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short`
must show the same `.run/*.xml` changes as before and nothing else.

- [ ] **Step 8: Final state**

```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short
git -C /Users/yblazart/projects/perso/vidocq/vidocq log --oneline -7
git -C /Users/yblazart/projects/perso/vidocq/mansart branch --show-current
git -C /Users/yblazart/projects/perso/vidocq/mansart log --oneline -1 feat/entity-models-of
```
Expected: the Vidocq tree clean (the plan's commits on `feat/mansart-data-catalogue`), Mansart back on
`ybl/opencode-3` with its own changes untouched, `feat/entity-models-of` holding the one commit of Task 1. Nothing
pushed. Report to the controller: the Mansart PR must be merged and its snapshot published before the Vidocq PR's CI
can pass.
