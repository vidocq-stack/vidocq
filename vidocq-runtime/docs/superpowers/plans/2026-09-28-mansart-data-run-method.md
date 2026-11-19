# Running a Mansart Data repository method from the dev console — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan is executed natively (one executor, inline): use superpowers:executing-plans.**

**Goal:** In a `dev` launch, let a developer pick a repository method in the *Mansart Data* panel, fill its
arguments and run it against the application's database — a read to see what it returns, a write to try it, rolled
back (the default) or committed — with the result as JSON and the last 20 calls replayable.

**Architecture:**
- **Mansart:** only the javadoc of `EntityModels.of` changes (spec §5), in a separate worktree and branch.
- **`vidocq-runtime-mansart-data-extension` (EXT):** `MansartDataLive` also holds the repository interfaces of the
  boot (`repositories()`), published at the end of `start` and cleared first thing in `onStop`.
- **`vidocq-runtime-mansart-data-extension-dev` (DEV):** new package-private classes, each with one job:
  `Json` (a minimal JSON reader/writer, no library), `Scalars` (§4 types: schema, JSON→Java, Java→JSON),
  `Failures` (class + message, masked and cut), `EntityJson` (entity ↔ JSON through Mansart's `EntityModel`
  handles), `ResultJson` (§6 results), `RepositoryMethods` (the declared and inherited methods, generic types
  bound), `Signature` (a method's schema, reason, arguments), `WriteKinds` (§3 write rules), `TransactionRunner` +
  `JtaDemarcation` (optional `jakarta.transaction.TransactionManager`), `BeanLookup` (the bean at the call),
  `CallHistory` (§8), `RepositoryActions` (ids, groups, limits, the call itself). `CatalogueLivePanel` keeps the
  `BeanManager` in `start`, builds the actions in `actions()`, and samples the extra values.
- **Database test:** the mansart-h2 example's `DevConsoleSnapshotTest`, which already boots the real application
  in a dev launch on in-memory H2 with JTA and drives console actions over HTTP.

**Tech Stack:** Java 25, JPMS, Maven 3.9, Jakarta Data 1.0.1, Mansart Data (`mansart-data-core`, dialect SPI),
Jakarta CDI 4.1, Jakarta Transactions 2.0.1 (optional), the Vidocq dev console SPI, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-28-mansart-data-run-method-design.md` (binding). Section numbers below
refer to it. Read it first, then this plan's *Rulings*. Context: the catalogue sub-project
(`docs/superpowers/specs/2026-09-28-mansart-data-catalogue-design.md`, `docs/superpowers/plans/2026-09-28-mansart-data-catalogue.md`), merged.

## Global Constraints

- **Repositories and branches.**
  - Vidocq: `/Users/yblazart/projects/perso/vidocq/vidocq`, branch `feat/mansart-data-run-method` (checked out;
    never switch it).
  - Mansart: `/Users/yblazart/projects/perso/vidocq/mansart` is on the user's branch `ybl/opencode-3` with
    uncommitted work. **Never** stash, reset, checkout, switch, add or commit anything in that checkout. Task 1 works
    in a separate, visible worktree `/Users/yblazart/projects/perso/vidocq/mansart-of-javadoc` only.
  - Test app (read only): `/Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server`. Never
    touch its uncommitted `.run/*.xml` files (in the parent repo `lc4jcdi-on-vidocq`). Its H2 database is a file
    (`target/h2/tasks`): a committed write of the manual check is undone before the end (Task 11).
- **Maven.** Every command starts with
  `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;` and uses
  `mvn -nsu` (never `./mvnw` or `mvnw`). If a hook redirects a Maven call to the context-mode `ctx_execute` shell,
  run it there and print only the tail/summary. Maven cannot be put in the background through Bash; the only
  background run is Task 11's, through the existing script.
- **Paths** (all exist; checked while planning):
  - `V` = `/Users/yblazart/projects/perso/vidocq/vidocq`
  - `EXT` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension`
  - `DEV` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
  - `WEB` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web`
  - `EX` = `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example`, `PET` = `vidocq-runtime-examples/vidocq-runtime-petstore-example`
  - EXT sources: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/` (and `.../data/live/`)
  - DEV sources: `DEV/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/`, tests in the
    same package under `DEV/src/test/java/...`.
  - The commands below spell these out in full.
- **Scratch directory** for logs:
  `SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad`
  (set it in each shell: `export SCRATCH=...`).
- **Ports:** only 18090-18099, checked free with `lsof -nP -iTCP:<port> -sTCP:LISTEN` first; never 8080 or 8888.
  Never kill a process this plan did not start.
- **Commits.** Write the message with the Write tool to `<repo>/.git/PLAN_COMMIT_MSG` (for the Mansart worktree:
  `/Users/yblazart/projects/perso/vidocq/mansart/.git/PLAN_COMMIT_MSG`), then
  `git commit -S -F <that file> && rm <that file>` (never `-m`, never `-s`). Stage explicit paths only (never
  `git add -A` / `git add .`). Every message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  **Never push.** The controller pushes and opens the pull requests.
- **Code style.** English; 120 columns; Javadoc density like the surrounding files. Every **new** `.java` file
  starts with the license header below, verbatim (the code blocks of this plan start at `package` and omit it:
  prepend it):

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
- **Dependencies.** One new dependency only: `jakarta.transaction:jakarta.transaction-api:2.0.1`, `<optional>` in
  DEV, `requires static jakarta.transaction` (the same artifact and version `mansart-data-cdi` declares optional).
  No JSON library: DEV cannot count on JSON-P/JSON-B in a Mansart application, so `Json` is hand-written.
- **No change** to the console SPI, the console, its page, or Mansart's behaviour (spec §1).

## Rulings on the spec

1. **Id length.** A console id is at most 40 characters. `m.<repository key>.<method key>`: the repository key is
   `CatalogueLivePanel.key(name, used, 20)` over one panel-wide set; the method key is
   `CatalogueLivePanel.key(methodName, usedInRepository, 37 - repositoryKey.length())`, so an overload or a clash
   gets `-2`, `-3` inside that room. Only runnable methods take a key.
2. **Group title** (≤ 40 characters, `PanelAction.MAX_GROUP`): the catalogue's name, cut from the left with `…` when
   longer, ` 2`, ` 3` on a clash. A repository past the catalogue's 200 limit is named by its full class name.
3. **History.** One `CallHistory` keeps 20 calls **per repository**; the sample writes them as one panel-level table
   `calls`, newest first, at most 100 rows (the console's table cap). The page moves it to the tabs and keeps each
   repository's rows in its own tab (#151). A row whose replay is empty (past `MAX_REPLAY_CELL`) is left out of the
   tab by the page's own rule — accepted, as §8 empties such a cell. `outcome` is the result's summary, prefixed
   `error: ` for an error.
4. **Not runnable** methods are listed in *Monitoring* as one panel-level table `not-runnable`
   (`repository`, `method`, `reason`): a per-repository key such as `<key>.not-runnable` would exceed 40 characters.
   `and N more methods` is the text value `more-methods`.
5. **Summary.** ` in N ms` is added to collection results only (`3 rows in 12 ms`, `first 100 rows in 40 ms`), as
   §6's examples read; `1 row`, `no row`, `42`, `done` carry none. An error's summary is cut at 200 characters by
   `ActionResult`; the whole masked text (message cut at 500) is also the error's `text/plain` body. A write
   without `TransactionManager`, committed, says ` · committed`, its details `"transaction":"commit"`.
6. **Arguments.** Every action has the json argument `arguments`, even with no parameter (an empty object). A key
   no parameter has is refused, `<key>: unknown argument`; a missing one is `<name>: missing`.
7. **Entities.** A property per attribute that has a setter and a §4 type, in model order; a joined attribute, or a
   reference whose entity cannot be modelled, is left out (and refused as unknown if sent). A reference takes the
   referenced entity's id (its schema is the id's); from JSON it becomes a new referenced entity with only that id
   set. An entity parameter whose model cannot be read makes the method not runnable:
   `parameter <name>: <Type> has no model (<exception class>)`.
8. **Labels.** The method name; when two runnable overloads share it, each is labelled with its signature,
   `search(String, int)`. The description is `JDQL: <query>` (if any), then `name(param: Type, …) → Return`, then
   `inherited from BasicRepository` for an inherited method.
9. **Inherited parameter names** come from the Jakarta Data class files (`entity`, `id`, `pageRequest`, `sortBy`).
10. **Logging.** A failed call logs its action id and exception class at DEBUG, never the message or the stack trace
    (they may carry an unmasked secret).
11. **Database test** (§9): the mansart-h2 example's `DevConsoleSnapshotTest` (cheapest: it already boots the real
    application in dev with H2, XA/JTA and the console, and posts actions). The example gains one JDQL `UPDATE`
    method, `ProductRepository.reprice`, used by that test only; rows are counted with the existing `count()`.
12. **`TransactionManager` absent**: the `jakarta.transaction` classes absent (`LinkageError`) or no bean → only
    `commit`. A failed `commit` is an error with no ` · ` state.

## Review Focus

- **A `Stream` that fails half-way** (a lazy JDBC read that loses its connection): an `error` result with the
  exception's class, the stream closed. → `ResultJsonTest.aStreamThatFailsIsClosedAndTheFailureThrown`,
  `RepositoryActionsTest.aStreamThatFailsIsAnErrorAndIsClosed` (Tasks 4, 7).
- **A repository package not open to Vidocq**: every method listed as not runnable with the package, no action, no
  crash. → `SignatureTest.aPackageNotOpenMakesTheMethodNotRunnable`,
  `RepositoryActionsTest.aPackageNotOpenToVidocqMakesNoAction` (Tasks 5, 7).
- **Two repositories sharing a simple name** (fully qualified names over 40 characters): distinct tabs, titles cut
  from the left. → `RepositoryActionsTest.longNamesAreCutFromTheLeftAndKeptApart` (Task 7).
- **An exception message carrying a JDBC URL with `user:password@`**: masked in the summary, the body and the
  history. → `RepositoryActionsTest.aFailingMethodRollsBackAndShowsItsClassAndItsMaskedMessage` (Task 7).
- **Arguments too long for a replay**: the history keeps the row, arguments cut at 200, replay emptied past 4,096.
  → `CallHistoryTest.longArgumentsAreCutAndALongReplayIsDropped` (Task 7).

---

## File Structure

| File | Responsibility |
|---|---|
| EXT `live/MansartDataLive.java` (modify) | also holds `repositories()` |
| EXT `MansartDataIntegrationExtension.java` (modify) | publishes the repository interfaces in `start` |
| EXT `module-info.java` (modify) | comment of the qualified export |
| EXT test `MansartDataSectionTest.java` (modify) | repositories published and cleared |
| DEV `Json.java` | parse/write JSON trees |
| DEV `ArgumentException.java` | a value that does not convert: `<name>: <why>` |
| DEV `Scalars.java` | §4 scalar types: schema, from JSON, to JSON |
| DEV `Failures.java` | exception text, `user:password@` masking, cutting |
| DEV `EntityJson.java` | entity schema, entity from JSON, entity to JSON |
| DEV `ResultJson.java` | §6 result body and summary part |
| DEV `RepositoryMethods.java` | declared + five inherited methods, bound types, names, return text |
| DEV `Signature.java` | schema, not-runnable reason, argument conversion of one method |
| DEV `WriteKinds.java` | §3 write detection |
| DEV `TransactionRunner.java`, `JtaDemarcation.java` | transactions, optional `TransactionManager` |
| DEV `BeanLookup.java` | the repository bean, resolved at the call |
| DEV `CallHistory.java` | §8 history table |
| DEV `RepositoryActions.java` | the actions: ids, groups, limits, the call, the extra sample values |
| DEV `CatalogueLivePanel.java` (modify) | `start`, `stop`, `actions()`, sample wiring, `key(…, max)` |
| DEV `pom.xml`, `module-info.java` (modify) | optional `jakarta.transaction-api`; `requires jakarta.cdi`, `requires static jakarta.transaction` |
| DEV tests | `JsonTest`, `ScalarsTest`, `FailuresTest`, `RunFixtures`, `EntityJsonTest`, `ResultJsonTest`, `SignatureTest`, `WriteKindsTest`, `RecordingTransactionManager`, `TransactionRunnerTest`, `CallHistoryTest`, `RepositoryActionsTest`, `CatalogueLivePanelTest` (modify) |
| EX `ProductRepository.java`, `pom.xml`, `DevConsoleSnapshotTest.java` (modify) | the database test |
| docs `modules/vidocq-runtime-extensions.adoc`, `whats-new.adoc` (modify) | §10 |

---

### Task 1: Mansart — the javadoc of `EntityModels.of` (spec §5)

**Files:**
- Modify (in the worktree only):
  `/Users/yblazart/projects/perso/vidocq/mansart-of-javadoc/mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core/EntityModels.java`

**Interfaces:**
- Consumes: nothing.
- Produces: a commit on branch `docs/entity-models-of-handles` in the worktree; `mansart-data-core` 0.4.0-SNAPSHOT
  reinstalled in `~/.m2` from `origin/main` + this javadoc (no API change; Vidocq keeps compiling against it).

- [ ] **Step 1: Get the user's OK for the worktree**

The controller asks the user before execution. If the user's OK to create
`/Users/yblazart/projects/perso/vidocq/mansart-of-javadoc` is not recorded in the conversation, **STOP and ask**;
do not create it without that OK.

- [ ] **Step 2: Record the user's checkout, fetch, create the worktree**

```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 > "$SCRATCH/mansart-status-before.txt"
git -C /Users/yblazart/projects/perso/vidocq/mansart branch --show-current
git -C /Users/yblazart/projects/perso/vidocq/mansart fetch origin
git -C /Users/yblazart/projects/perso/vidocq/mansart worktree add -b docs/entity-models-of-handles ~/projects/perso/vidocq/mansart-of-javadoc origin/main
```
Expected: `ybl/opencode-3`; `Preparing worktree (new branch 'docs/entity-models-of-handles')`. If the branch or
the directory already exists, STOP and ask the user.

- [ ] **Step 3: Update the javadoc**

In the worktree's `EntityModels.java`, replace exactly:
```
     * {@code $MODEL} is public and carries the same. A caller reads the model to describe the entity, as the
     * Vidocq dev console does, and does not use those handles.
```
with:
```
     * {@code $MODEL} is public and carries the same. A tool may use them as Mansart does: the Vidocq dev console
     * reads the model to describe the entity and, in a {@code dev} launch, reads and builds with those handles the
     * entities of the repository methods a developer runs from it. It never changes the model.
```

- [ ] **Step 4: Build and install**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/mansart-of-javadoc && mvn -nsu -pl mansart-jakarta-data/mansart-data-core -am install -DskipTests > "$SCRATCH/t1.log" 2>&1; grep -E "BUILD|ERROR" "$SCRATCH/t1.log" | tail -5
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit in the worktree**

Message (`/Users/yblazart/projects/perso/vidocq/mansart/.git/PLAN_COMMIT_MSG`):
```
docs(data-core): EntityModels.of, the handles a tool may use

The javadoc said a caller reads the model and does not use its getter and
setter handles. The Vidocq dev console now also reads and builds, with
those handles, the entities of the repository methods a developer runs in
a dev launch; it never changes the model. No behaviour change.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/mansart-of-javadoc
git add mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core/EntityModels.java
git commit -S -F /Users/yblazart/projects/perso/vidocq/mansart/.git/PLAN_COMMIT_MSG && rm /Users/yblazart/projects/perso/vidocq/mansart/.git/PLAN_COMMIT_MSG
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 | diff - "$SCRATCH/mansart-status-before.txt" && echo "user checkout untouched"
```
Expected: one commit; `user checkout untouched`. Leave the worktree in place (the controller pushes it).

---

### Task 2: EXT publishes the repository interfaces

**Files:**
- Modify: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/live/MansartDataLive.java`
- Modify: `EXT/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/MansartDataIntegrationExtension.java`
- Modify: `EXT/src/main/java/module-info.java`
- Test: `EXT/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/MansartDataSectionTest.java`

**Interfaces:**
- Produces: `public static List<Class<?>> MansartDataLive.repositories()` (never `null`, empty when none),
  `public static void MansartDataLive.publishRepositories(List<Class<?>> found)`; `MansartDataLive.clear()` now
  clears both. `start(...)` publishes the repository interfaces after the catalogue.

- [ ] **Step 1: Write the failing tests**

In `MansartDataSectionTest.java`, insert immediately before the line `    @Test` that precedes
`    void onStopClearsTheCatalogueFirst() {` (i.e. replace `    @Test\n    void onStopClearsTheCatalogueFirst() {`
with the block below followed by that same text):
```java
    @Test
    void startPublishesTheRepositoryInterfacesForTheDevPanel() {
        ext.start(() -> List.of(CatalogueFixtures.GadgetRepositoryImpl.class, OrderRepository.class),
                CatalogueFixtures::model);

        assertEquals(List.of(CatalogueFixtures.GadgetRepository.class, OrderRepository.class),
                MansartDataLive.repositories());
    }

    @Test
    void onStopClearsTheRepositoriesFirst() {
        ext.start(() -> List.of(OrderRepository.class), CatalogueFixtures::model);
        assertEquals(List.of(OrderRepository.class), MansartDataLive.repositories());

        ext.onStop();

        assertEquals(List.of(), MansartDataLive.repositories(), "a dev reload reads no class of this boot");
    }

    @Test
    void noRepositoryIsPublishedWhenTheyCannotBeListed() {
        ext.start(() -> List.of(OrderRepository.class), CatalogueFixtures::model);
        ext.start(() -> {
            throw new IllegalStateException("the container could not list its beans");
        }, CatalogueFixtures::model);

        assertEquals(List.of(), MansartDataLive.repositories());
    }

```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension -Dtest=MansartDataSectionTest > "$SCRATCH/t2.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t2.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol ... repositories()`.

- [ ] **Step 3: `MansartDataLive`**

Replace the whole content after the license header of `MansartDataLive.java` with:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the Mansart Data extension found, for its {@code -dev} panel only: the catalogue it built and the repository
 * interfaces of the boot. Both are published at the end of {@code onStart} and cleared first thing in
 * {@code onStop}, so that a dev reload never shows the previous boot's catalogue nor lets the panel run the previous
 * boot's classes. The catalogue holds names and texts only; the repository interfaces are the application's classes,
 * which only the panel's actions hold, for one boot.
 */
public final class MansartDataLive {

    private static volatile MansartDataCatalogue catalogue;
    private static volatile List<Class<?>> repositories = List.of();

    private MansartDataLive() {}

    /** The catalogue of the running boot, empty before it is built and once the extension stopped. */
    public static Optional<MansartDataCatalogue> catalogue() {
        return Optional.ofNullable(catalogue);
    }

    public static void publish(MansartDataCatalogue built) {
        catalogue = Objects.requireNonNull(built, "catalogue");
    }

    /** The {@code @Repository} interfaces of the running boot, by name; empty before and after it. */
    public static List<Class<?>> repositories() {
        return repositories;
    }

    public static void publishRepositories(List<Class<?>> found) {
        repositories = List.copyOf(found);
    }

    /** Forgets the catalogue and the repository interfaces. */
    public static void clear() {
        catalogue = null;
        repositories = List.of();
    }
}
```

- [ ] **Step 4: Publish them in `start`**

In `MansartDataIntegrationExtension.java`, replace:
```java
    /**
     * Finds the repositories among {@code beanClasses}, logs them and builds the catalogue; never throws, the boot never
     * fails for it, not even when listing the beans or reading their interfaces does. Visible for tests.
     */
```
with:
```java
    /**
     * Finds the repositories among {@code beanClasses}, logs them, builds the catalogue and publishes the repository
     * interfaces for the {@code -dev} panel's actions; never throws, the boot never fails for it, not even when
     * listing the beans or reading their interfaces does. Visible for tests.
     */
```
and replace:
```java
        logRepositoryInventory(repositories);
        catalogue(repositories, models);
    }
```
with:
```java
        logRepositoryInventory(repositories);
        catalogue(repositories, models);
        MansartDataLive.publishRepositories(repositories);
    }
```

- [ ] **Step 5: The export's comment**

In `EXT/src/main/java/module-info.java`, replace
`    // What the catalogue panel of the -dev module reads; no other module sees it.` with
`    // What the -dev module's panel reads, the catalogue and the repository interfaces; no other module sees it.`

- [ ] **Step 6: Run the module's tests and install it**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension > "$SCRATCH/t2.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/t2.log" | tail -5
```
Expected: `BUILD SUCCESS`, no failure (the DEV module's tests use this install from Task 3 on).

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): publish the repository interfaces for the dev panel

MansartDataLive also holds the @Repository interfaces of the boot,
published at the end of start and cleared with the catalogue first thing
in onStop, so that the -dev panel can run their methods (spec §2) and a
dev reload never runs the previous boot's classes.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
E=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension
git add $E/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/live/MansartDataLive.java $E/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/MansartDataIntegrationExtension.java $E/src/main/java/module-info.java $E/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/MansartDataSectionTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: DEV — JSON, scalar types, failures

**Files:**
- Create: DEV main `Json.java`, `ArgumentException.java`, `Scalars.java`, `Failures.java`
- Test: DEV test `JsonTest.java`, `ScalarsTest.java`, `FailuresTest.java`

**Interfaces:**
- Produces:
  - `static Object Json.parse(String text)` → `Map<String,Object>` (ordered) / `List<Object>` / `String` /
    `BigDecimal` / `Boolean` / `null`; throws `IllegalArgumentException("not valid JSON at character N")`;
    `static String Json.write(Object tree)`; `static final int Json.MAX_DEPTH = 64`.
  - `final class ArgumentException extends Exception` with `ArgumentException(String name, String why)`, message
    `<name>: <why>`.
  - `static Map<String,Object> Scalars.schema(Class<?> type)` (`null` when unsupported);
    `static Object Scalars.fromJson(Class<?> type, Object json, String name) throws ArgumentException`;
    `static Object Scalars.toJson(Object value)`; `static Map<String,Object> Scalars.object(Object... namesAndValues)`.
  - `static String Failures.text(Throwable)`, `static String Failures.mask(String)`,
    `static String Failures.cut(String text, int max)` (adds `…`), `static final int Failures.MAX_MESSAGE = 500`.

- [ ] **Step 1: Write the failing tests**

`JsonTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The panel's own JSON: a tree of maps, lists, strings, BigDecimals, booleans and nulls. */
class JsonTest {

    @Test
    void readsAnObjectKeepingItsOrderAndItsNumbersText() {
        Object parsed = Json.parse(" {\"b\": 1, \"a\": [true, false, null, \"x\"], \"n\": -2.50e0, \"o\": {}} ");

        Map<?, ?> object = assertInstanceOf(Map.class, parsed);
        assertEquals(List.of("b", "a", "n", "o"), List.copyOf(object.keySet()));
        assertEquals(new BigDecimal("1"), object.get("b"));
        assertEquals(Arrays.asList(true, false, null, "x"), object.get("a"));
        assertEquals(new BigDecimal("-2.50"), object.get("n"));
        assertEquals(Map.of(), object.get("o"));
    }

    @Test
    void readsEscapes() {
        assertEquals("a\"b\\c/d\n\t\u00e9", Json.parse("\"a\\\"b\\\\c\\/d\\n\\t\\u00e9\""));
    }

    @Test
    void refusesWhatIsNotJson() {
        for (String text : List.of("", "{", "{\"a\" 1}", "[1,]", "01", "1.", "tru", "\"a", "{} x", "\"\u0001\"",
                "\"\\x\"", "\"\\u12G4\"")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
        }
    }

    @Test
    void refusesADeeperNestingThanTheConsole() {
        assertInstanceOf(List.class, Json.parse("[".repeat(64) + "]".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(65) + "]".repeat(65)));
    }

    @Test
    void writesCompactJson() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("s", "q\"\\\n\u0001");
        tree.put("n", new BigDecimal("2.50"));
        tree.put("l", 3L);
        tree.put("b", true);
        tree.put("z", null);
        tree.put("a", List.of(1, "x"));
        tree.put("nan", Double.NaN);

        assertEquals("{\"s\":\"q\\\"\\\\\\n\\u0001\",\"n\":2.50,\"l\":3,\"b\":true,\"z\":null,\"a\":[1,\"x\"],"
                + "\"nan\":\"NaN\"}", Json.write(tree));
    }

    @Test
    void whatItWritesItReads() {
        String text = "{\"a\":[1,2.50,{\"b\":\"c\"}],\"d\":null}";

        assertEquals(text, Json.write(Json.parse(text)));
    }
}
```

`ScalarsTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The types of spec §4: their schema, and their conversion from and to JSON. */
class ScalarsTest {

    enum Level { LOW, HIGH }

    private static Object convert(Class<?> type, String json) throws ArgumentException {
        return Scalars.fromJson(type, Json.parse(json), "x");
    }

    private static String refusal(Class<?> type, String json) {
        return assertThrows(ArgumentException.class, () -> convert(type, json)).getMessage();
    }

    private static String schema(Class<?> type) {
        return Json.write(Scalars.schema(type));
    }

    @Test
    void theSchemaOfEachSupportedType() {
        assertEquals("{\"type\":\"string\"}", schema(String.class));
        assertEquals("{\"type\":\"string\",\"maxLength\":1}", schema(char.class));
        assertEquals("{\"type\":\"string\",\"maxLength\":1}", schema(Character.class));
        for (Class<?> type : List.of(int.class, Integer.class, long.class, Long.class, short.class, Short.class,
                byte.class, Byte.class, BigInteger.class)) {
            assertEquals("{\"type\":\"integer\"}", schema(type), type.getName());
        }
        for (Class<?> type : List.of(double.class, Double.class, float.class, Float.class, BigDecimal.class)) {
            assertEquals("{\"type\":\"number\"}", schema(type), type.getName());
        }
        assertEquals("{\"type\":\"boolean\"}", schema(boolean.class));
        assertEquals("{\"type\":\"boolean\"}", schema(Boolean.class));
        assertEquals("{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}", schema(Level.class));
        assertEquals("{\"type\":\"string\",\"format\":\"date\"}", schema(LocalDate.class));
        for (Class<?> type : List.of(LocalDateTime.class, Instant.class, OffsetDateTime.class, ZonedDateTime.class)) {
            assertEquals("{\"type\":\"string\",\"format\":\"date-time\"}", schema(type), type.getName());
        }
        assertEquals("{\"type\":\"string\",\"format\":\"time\"}", schema(LocalTime.class));
        assertEquals("{\"type\":\"string\",\"format\":\"uuid\"}", schema(UUID.class));
    }

    @Test
    void otherTypesHaveNoSchema() {
        assertNull(Scalars.schema(List.class));
        assertNull(Scalars.schema(Object.class));
        assertNull(Scalars.schema(int[].class));
    }

    @Test
    void textsAndCharacters() throws Exception {
        assertEquals("bolt", convert(String.class, "\"bolt\""));
        assertEquals('A', convert(char.class, "\"A\""));
        assertEquals("x: not one character", refusal(Character.class, "\"AB\""));
        assertEquals("x: not a string", refusal(String.class, "42"));
    }

    @Test
    void integersExactlyAndInRange() throws Exception {
        assertEquals(42, convert(int.class, "42"));
        assertEquals(9007199254740993L, convert(Long.class, "9007199254740993"));
        assertEquals((short) -3, convert(short.class, "-3"));
        assertEquals((byte) 127, convert(Byte.class, "127"));
        assertEquals(new BigInteger("123456789012345678901234567890"),
                convert(BigInteger.class, "123456789012345678901234567890"));
        assertEquals(1, convert(int.class, "1.0"), "an integral value written with a fraction part");
        assertEquals("x: not an integer", refusal(int.class, "1.5"));
        assertEquals("x: not an integer", refusal(long.class, "\"12\""));
        assertEquals("x: out of range for int", refusal(Integer.class, "2147483648"));
        assertEquals("x: out of range for byte", refusal(byte.class, "128"));
        assertEquals("x: out of range for long", refusal(long.class, "1e5000"));
    }

    @Test
    void numbers() throws Exception {
        assertEquals(2.5d, convert(double.class, "2.5"));
        assertEquals(0.1f, convert(Float.class, "0.1"));
        assertEquals(new BigDecimal("2.50"), convert(BigDecimal.class, "2.50"), "the text kept, its scale too");
        assertEquals("x: out of range for double", refusal(double.class, "1e400"));
        assertEquals("x: out of range for float", refusal(float.class, "1e39"));
        assertEquals("x: not a number", refusal(BigDecimal.class, "\"2.50\""));
    }

    @Test
    void booleansAndEnums() throws Exception {
        assertEquals(true, convert(boolean.class, "true"));
        assertEquals("x: not a boolean", refusal(Boolean.class, "\"true\""));
        assertEquals(Level.HIGH, convert(Level.class, "\"HIGH\""));
        assertEquals("x: no constant MEDIUM in Level", refusal(Level.class, "\"MEDIUM\""));
    }

    @Test
    void timesInIso() throws Exception {
        assertEquals(LocalDate.of(2026, 9, 28), convert(LocalDate.class, "\"2026-09-28\""));
        assertEquals(LocalDateTime.of(2026, 9, 28, 10, 15, 30),
                convert(LocalDateTime.class, "\"2026-09-28T10:15:30\""));
        assertEquals(Instant.parse("2026-09-28T10:15:30Z"), convert(Instant.class, "\"2026-09-28T10:15:30Z\""));
        assertEquals(OffsetDateTime.parse("2026-09-28T10:15:30+02:00"),
                convert(OffsetDateTime.class, "\"2026-09-28T10:15:30+02:00\""));
        assertEquals(ZonedDateTime.parse("2026-09-28T10:15:30+02:00[Europe/Paris]"),
                convert(ZonedDateTime.class, "\"2026-09-28T10:15:30+02:00[Europe/Paris]\""));
        assertEquals(LocalTime.of(10, 15), convert(LocalTime.class, "\"10:15\""));
        assertEquals("x: not an ISO date", refusal(LocalDate.class, "\"28/09/2026\""));
        assertEquals("x: not an ISO date-time", refusal(Instant.class, "\"2026-09-28\""));
        assertEquals("x: not an ISO time", refusal(LocalTime.class, "\"25:00\""));
    }

    @Test
    void uuids() throws Exception {
        UUID id = UUID.fromString("5f0c1d2e-3a4b-4c5d-8e6f-7a8b9c0d1e2f");

        assertEquals(id, convert(UUID.class, "\"" + id + "\""));
        assertEquals("x: not a UUID", refusal(UUID.class, "\"nope\""));
    }

    @Test
    void nullForABoxNeverForAPrimitive() throws Exception {
        assertNull(convert(Integer.class, "null"));
        assertNull(convert(LocalDate.class, "null"));
        assertEquals("x: null is not allowed for int", refusal(int.class, "null"));
    }

    @Test
    void anUnsupportedTypeIsRefused() {
        assertEquals("x: List is not supported", refusal(List.class, "[]"));
    }

    @Test
    void valuesToJson() {
        assertEquals("HIGH", Scalars.toJson(Level.HIGH));
        assertEquals("2026-09-28T10:15:30Z", Scalars.toJson(Instant.parse("2026-09-28T10:15:30Z")));
        assertEquals("A", Scalars.toJson('A'));
        assertEquals(42L, Scalars.toJson(42L));
        assertEquals("NaN", Scalars.toJson(Double.NaN));
        assertNull(Scalars.toJson(null));
        assertEquals("[1, 2]", Scalars.toJson(new StringBuilder("[1, 2]")), "anything else as its text");
    }
}
```

`FailuresTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a failing method shows (spec §7): its class, its message cut at 500, a user:password@ masked. */
class FailuresTest {

    @Test
    void aUserAndPasswordBeforeAnAtAreMasked() {
        assertEquals("cannot reach jdbc:postgresql://***:***@db:5432/shop",
                Failures.mask("cannot reach jdbc:postgresql://app:s3cret@db:5432/shop"));
        assertEquals("no URL here: a:b c", Failures.mask("no URL here: a:b c"));
    }

    @Test
    void theClassThenTheMessageCutAt500() {
        assertEquals("java.lang.IllegalStateException", Failures.text(new IllegalStateException()));
        assertEquals("java.lang.IllegalArgumentException: " + "x".repeat(500) + "…",
                Failures.text(new IllegalArgumentException("x".repeat(600))));
    }

    @Test
    void aCutNeverSplitsASurrogatePair() {
        assertEquals("ab…", Failures.cut("ab\uD83D\uDE00cd", 3));
        assertEquals("abc", Failures.cut("abc", 3));
    }
}
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='JsonTest,ScalarsTest,FailuresTest' > "$SCRATCH/t3.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t3.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (`Json`, `Scalars`, `Failures`).

- [ ] **Step 3: `Json.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The little JSON the run actions need, without a library (a Mansart application need not have JSON-P): {@link #parse}
 * reads a text into a tree of {@link Map} (members in order), {@link List}, {@link String}, {@link BigDecimal} (its
 * text kept), {@link Boolean} and {@code null}; {@link #write} writes such a tree, compact. The console already
 * checked that an argument is one JSON object nesting at most 64 levels; the reader checks again, so that it never
 * trusts its caller.
 */
final class Json {

    /** How deep a value may nest: the console's own limit. */
    static final int MAX_DEPTH = 64;

    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    /**
     * The tree of {@code text}.
     *
     * @throws IllegalArgumentException when it is not one JSON value, or nests deeper than {@value #MAX_DEPTH}
     */
    static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no JSON");
        }
        Json reader = new Json(text);
        reader.blanks();
        Object value = reader.value(0);
        reader.blanks();
        if (reader.at != text.length()) {
            throw reader.error();
        }
        return value;
    }

    /** {@code value} as compact JSON: the types of the class comment, any other number as its text, anything else quoted. */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private Object value(int depth) {
        if (depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nests deeper than " + MAX_DEPTH + " levels");
        }
        if (at >= text.length()) {
            throw error();
        }
        return switch (text.charAt(at)) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> members = new LinkedHashMap<>();
        at++;
        blanks();
        if (next('}')) {
            return members;
        }
        do {
            blanks();
            if (at >= text.length() || text.charAt(at) != '"') {
                throw error();
            }
            String name = string();
            blanks();
            expect(':');
            blanks();
            members.put(name, value(depth + 1));
            blanks();
        } while (next(','));
        expect('}');
        return members;
    }

    private List<Object> array(int depth) {
        List<Object> elements = new ArrayList<>();
        at++;
        blanks();
        if (next(']')) {
            return elements;
        }
        do {
            blanks();
            elements.add(value(depth + 1));
            blanks();
        } while (next(','));
        expect(']');
        return elements;
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            if (at >= text.length()) {
                throw error();
            }
            char c = text.charAt(at++);
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (at >= text.length()) {
                throw error();
            }
            char escaped = text.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(unicode());
                default -> throw error();
            }
        }
    }

    private char unicode() {
        if (at + 4 > text.length()) {
            throw error();
        }
        int code = 0;
        for (int i = 0; i < 4; i++) {
            char digit = text.charAt(at + i);
            if (!HexFormat.isHexDigit(digit)) {
                throw error();
            }
            code = code * 16 + HexFormat.fromHexDigit(digit);
        }
        at += 4;
        return (char) code;
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, at)) {
            throw error();
        }
        at += word.length();
        return value;
    }

    private BigDecimal number() {
        int start = at;
        while (at < text.length() && "+-0123456789.eE".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        String number = text.substring(start, at);
        if (!NUMBER.matcher(number).matches()) {
            at = start;
            throw error();
        }
        try {
            return new BigDecimal(number);
        } catch (NumberFormatException exponentTooLarge) {
            at = start;
            throw error();
        }
    }

    private void blanks() {
        while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
    }

    private boolean next(char c) {
        if (at < text.length() && text.charAt(at) == c) {
            at++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!next(c)) {
            throw error();
        }
    }

    private IllegalArgumentException error() {
        return new IllegalArgumentException("not valid JSON at character " + (at + 1));
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> quote(text, out);
            case Boolean flag -> out.append(flag);
            case Double d when d.isNaN() || d.isInfinite() -> quote(d.toString(), out);
            case Float f when f.isNaN() || f.isInfinite() -> quote(f.toString(), out);
            case Number number -> out.append(number);
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> member : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(String.valueOf(member.getKey()), out);
                    out.append(':');
                    write(member.getValue(), out);
                }
                out.append('}');
            }
            case Collection<?> elements -> {
                out.append('[');
                boolean first = true;
                for (Object element : elements) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(element, out);
                }
                out.append(']');
            }
            default -> quote(value.toString(), out);
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
```

- [ ] **Step 4: `ArgumentException.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

/**
 * A value of an action's arguments that does not convert to its Java type (spec §4): the call is refused before
 * anything runs, with the message {@code <name>: <why>}, such as {@code task.dueDate: not an ISO date}.
 */
final class ArgumentException extends Exception {

    private static final long serialVersionUID = 1L;

    ArgumentException(String name, String why) {
        super(name + ": " + why, null, false, false);
    }
}
```

- [ ] **Step 5: `Failures.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.util.regex.Pattern;

/**
 * What a failing method shows (spec §7): the class of its exception and its message, cut at {@value #MAX_MESSAGE}
 * characters, with any {@code user:password@} masked as {@code ***:***@} — a dev launch only, but a JDBC URL may
 * still carry a real password.
 */
final class Failures {

    /** The longest message shown. */
    static final int MAX_MESSAGE = 500;

    /** A user and a password before an {@code @}, as a URL carries them. */
    private static final Pattern CREDENTIALS = Pattern.compile("[^\\s/:@]+:[^\\s/@]+@");

    private Failures() {}

    /** {@code <exception class>: <message>}, masked then cut; the class alone when there is no message. */
    static String text(Throwable failure) {
        String name = failure.getClass().getName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return name;
        }
        return name + ": " + cut(mask(message), MAX_MESSAGE);
    }

    /** {@code text} with each {@code user:password@} replaced by {@code ***:***@}. */
    static String mask(String text) {
        return CREDENTIALS.matcher(text).replaceAll("***:***@");
    }

    /** {@code text} cut after {@code max} characters with {@code …}, never inside a surrogate pair. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
```

- [ ] **Step 6: `Scalars.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The parameter types of spec §4 that are no entity: their JSON Schema, their conversion from the JSON the page
 * sends, and the JSON of a value a method returns (spec §6). A value never converts approximately: a fraction for an
 * integer, a number out of range, an unknown constant, a malformed date are refused with why.
 */
final class Scalars {

    private static final Set<Class<?>> INTEGERS = Set.of(int.class, Integer.class, long.class, Long.class,
            short.class, Short.class, byte.class, Byte.class, BigInteger.class);
    private static final Set<Class<?>> NUMBERS =
            Set.of(double.class, Double.class, float.class, Float.class, BigDecimal.class);
    private static final Set<Class<?>> DATE_TIMES =
            Set.of(LocalDateTime.class, Instant.class, OffsetDateTime.class, ZonedDateTime.class);
    private static final Map<Class<?>, String> PRIMITIVES = Map.of(Integer.class, "int", Long.class, "long",
            Short.class, "short", Byte.class, "byte", Double.class, "double", Float.class, "float");
    /** The most integer digits read: past it a value is out of the range of every type, BigInteger included. */
    private static final int MAX_DIGITS = 1000;

    private Scalars() {}

    /** A JSON object of these names and values, in this order: {@code object("type", "string")}. */
    static Map<String, Object> object(Object... namesAndValues) {
        Map<String, Object> object = new LinkedHashMap<>();
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            object.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return object;
    }

    /** The JSON Schema of {@code type}, or {@code null} when §4 does not support it. */
    static Map<String, Object> schema(Class<?> type) {
        if (type == String.class) {
            return object("type", "string");
        }
        if (type == char.class || type == Character.class) {
            return object("type", "string", "maxLength", 1);
        }
        if (INTEGERS.contains(type)) {
            return object("type", "integer");
        }
        if (NUMBERS.contains(type)) {
            return object("type", "number");
        }
        if (type == boolean.class || type == Boolean.class) {
            return object("type", "boolean");
        }
        if (type.isEnum()) {
            List<String> names = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return object("type", "string", "enum", names);
        }
        if (type == LocalDate.class) {
            return object("type", "string", "format", "date");
        }
        if (type == LocalTime.class) {
            return object("type", "string", "format", "time");
        }
        if (DATE_TIMES.contains(type)) {
            return object("type", "string", "format", "date-time");
        }
        if (type == UUID.class) {
            return object("type", "string", "format", "uuid");
        }
        return null;
    }

    /**
     * {@code json}, a value of {@link Json#parse}, as a {@code type}: the exact wrapper of a primitive, so that a
     * method handle or a reflective call unboxes it.
     *
     * @param name the parameter or property it is for, which a refusal names
     * @throws ArgumentException when it does not convert
     */
    static Object fromJson(Class<?> type, Object json, String name) throws ArgumentException {
        if (json == null) {
            if (type.isPrimitive()) {
                throw new ArgumentException(name, "null is not allowed for " + type.getName());
            }
            return null;
        }
        if (type == String.class) {
            return text(json, name);
        }
        if (type == char.class || type == Character.class) {
            String text = text(json, name);
            if (text.length() != 1) {
                throw new ArgumentException(name, "not one character");
            }
            return text.charAt(0);
        }
        if (INTEGERS.contains(type)) {
            return integer(type, json, name);
        }
        if (type == double.class || type == Double.class) {
            double value = number(json, name, "not a number").doubleValue();
            if (Double.isInfinite(value)) {
                throw outOfRange(type, name);
            }
            return value;
        }
        if (type == float.class || type == Float.class) {
            float value = number(json, name, "not a number").floatValue();
            if (Float.isInfinite(value)) {
                throw outOfRange(type, name);
            }
            return value;
        }
        if (type == BigDecimal.class) {
            return number(json, name, "not a number");
        }
        if (type == boolean.class || type == Boolean.class) {
            if (json instanceof Boolean flag) {
                return flag;
            }
            throw new ArgumentException(name, "not a boolean");
        }
        if (type.isEnum()) {
            String text = text(json, name);
            for (Object constant : type.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(text)) {
                    return constant;
                }
            }
            throw new ArgumentException(name, "no constant " + Failures.cut(text, 60) + " in " + type.getSimpleName());
        }
        if (type == LocalDate.class) {
            return time(json, name, "date", LocalDate::parse);
        }
        if (type == LocalTime.class) {
            return time(json, name, "time", LocalTime::parse);
        }
        if (type == LocalDateTime.class) {
            return time(json, name, "date-time", LocalDateTime::parse);
        }
        if (type == Instant.class) {
            return time(json, name, "date-time", Instant::parse);
        }
        if (type == OffsetDateTime.class) {
            return time(json, name, "date-time", OffsetDateTime::parse);
        }
        if (type == ZonedDateTime.class) {
            return time(json, name, "date-time", ZonedDateTime::parse);
        }
        if (type == UUID.class) {
            try {
                return UUID.fromString(text(json, name));
            } catch (IllegalArgumentException malformed) {
                throw new ArgumentException(name, "not a UUID");
            }
        }
        throw new ArgumentException(name, type.getSimpleName() + " is not supported");
    }

    /**
     * The JSON of a value a method returns (spec §6): {@code null}, a number, a boolean or a string as it is, an enum
     * as its name, a {@code char} as a string, anything else — a {@code java.time} value, a {@code UUID} — as its
     * text; a number JSON cannot hold, such as {@code NaN}, as its text too.
     */
    static Object toJson(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Character c -> String.valueOf(c);
            case Boolean flag -> flag;
            case Double d -> d.isNaN() || d.isInfinite() ? d.toString() : d;
            case Float f -> f.isNaN() || f.isInfinite() ? f.toString() : f;
            case Number number -> number;
            case Enum<?> constant -> constant.name();
            default -> value.toString();
        };
    }

    private static String text(Object json, String name) throws ArgumentException {
        if (json instanceof String text) {
            return text;
        }
        throw new ArgumentException(name, "not a string");
    }

    private static BigDecimal number(Object json, String name, String why) throws ArgumentException {
        if (json instanceof BigDecimal number) {
            return number;
        }
        if (json instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new ArgumentException(name, why);
    }

    private static Object integer(Class<?> type, Object json, String name) throws ArgumentException {
        BigDecimal number = number(json, name, "not an integer");
        if (number.precision() - number.scale() > MAX_DIGITS) {
            throw outOfRange(type, name);
        }
        BigInteger value;
        try {
            value = number.toBigIntegerExact();
        } catch (ArithmeticException fraction) {
            throw new ArgumentException(name, "not an integer");
        }
        try {
            if (type == int.class || type == Integer.class) {
                return value.intValueExact();
            }
            if (type == long.class || type == Long.class) {
                return value.longValueExact();
            }
            if (type == short.class || type == Short.class) {
                return value.shortValueExact();
            }
            if (type == byte.class || type == Byte.class) {
                return value.byteValueExact();
            }
            return value;
        } catch (ArithmeticException tooLarge) {
            throw outOfRange(type, name);
        }
    }

    private static ArgumentException outOfRange(Class<?> type, String name) {
        String shown = type.isPrimitive() ? type.getName() : PRIMITIVES.getOrDefault(type, type.getSimpleName());
        return new ArgumentException(name, "out of range for " + shown);
    }

    private static Object time(Object json, String name, String what, Function<String, Object> parse)
            throws ArgumentException {
        try {
            return parse.apply(text(json, name));
        } catch (DateTimeParseException malformed) {
            throw new ArgumentException(name, "not an ISO " + what);
        }
    }
}
```

- [ ] **Step 7: Run the tests**

Same command as Step 2. Expected: `Tests run: ...` for the three classes, `Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

Message:
```
feat(mansart-data): the arguments' JSON and types of the run actions

A minimal JSON reader and writer (a Mansart application need not have
JSON-P), the JSON Schema of each parameter type of spec §4 and its exact
conversion from JSON, the JSON of returned values, and the text a failing
method shows: its class and message, cut at 500, user:password@ masked.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
M=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
T=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $M/Json.java $M/ArgumentException.java $M/Scalars.java $M/Failures.java $T/JsonTest.java $T/ScalarsTest.java $T/FailuresTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: DEV — entities and results as JSON

**Files:**
- Create: DEV main `EntityJson.java`, `ResultJson.java`
- Create: DEV test `RunFixtures.java` (fixtures used by Tasks 4-8), `EntityJsonTest.java`, `ResultJsonTest.java`

**Interfaces:**
- Consumes: `Json`, `Scalars`, `ArgumentException` (Task 3).
- Produces:
  - `EntityJson(Function<Class<?>, EntityModel<?>> models, Set<String> entityClassNames)`;
    `boolean isEntity(Class<?>)`; `Map<String,Object> schema(Class<?> entity)` (throws a `RuntimeException` or
    `LinkageError` when the model cannot be read); `Object fromJson(Class<?> entity, Object json, String name)
    throws ArgumentException`; `Map<String,Object> toJson(Object entity)`.
  - `ResultJson.Result(String body, String what, boolean rows)`; `static Result ResultJson.of(Object value,
    boolean isVoid, EntityJson entities)`; `static final int ResultJson.MAX_ROWS = 100`.
  - Test fixtures `RunFixtures`: `Level`, `Gizmo`, `Part`, `Broken`, `GizmoRepository`, `PartRepository`,
    `BrokenRepository`, `ReportQueries`, `gizmo(...)`, `part(...)`, `model(Class<?>)`, `entities()`,
    `catalogue()`, `REPOSITORIES`.

- [ ] **Step 1: The fixtures**

`RunFixtures.java` (test):
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.IdAttribute;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;
import io.vidocq.mansart.data.dialect.attribute.TextAttribute;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import jakarta.data.page.Page;
import jakarta.data.page.PageRequest;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Entities and repositories the run actions are tested with, as an application declares them. Gizmo and Broken go
 * through Mansart's own {@code EntityModels.of} (no metamodel is generated here, so Mansart builds their model at
 * run time); the model of Part is written by hand, to hold a reference and a joined attribute.
 */
final class RunFixtures {

    private RunFixtures() {}

    public enum Level { LOW, HIGH }

    /** Its id is the field named {@code id}; one field of most kinds, a primitive among them. */
    public static class Gizmo {
        private Long id;
        private String name;
        private int stock;
        private Level level;
        private LocalDate due;
        private BigDecimal price;

        public Gizmo() {}

        Long id() {
            return id;
        }

        String name() {
            return name;
        }

        int stock() {
            return stock;
        }

        Level level() {
            return level;
        }

        LocalDate due() {
            return due;
        }

        BigDecimal price() {
            return price;
        }
    }

    /** Its model is {@link #partModel()}: a reference to a gizmo, and the gizmo's name joined. */
    public static class Part {
        private Long id;
        private Gizmo gizmo;
        private String label;

        public Part() {}

        Gizmo gizmo() {
            return gizmo;
        }

        String label() {
            return label;
        }
    }

    /** No id field: Mansart cannot build its model. */
    public static class Broken {
        private String label;

        public Broken() {}
    }

    static Gizmo gizmo(Long id, String name, int stock, Level level, LocalDate due, BigDecimal price) {
        Gizmo gizmo = new Gizmo();
        gizmo.id = id;
        gizmo.name = name;
        gizmo.stock = stock;
        gizmo.level = level;
        gizmo.due = due;
        gizmo.price = price;
        return gizmo;
    }

    static Part part(Long id, Gizmo gizmo, String label) {
        Part part = new Part();
        part.id = id;
        part.gizmo = gizmo;
        part.label = label;
        return part;
    }

    /** Every kind of method: reads, writes of each rule, overloads, and parameters §4 does not support. */
    @Repository
    public interface GizmoRepository extends BasicRepository<Gizmo, Long> {

        List<Gizmo> findByName(@Param("name") String name);

        long countByStockGreaterThan(@Param("min") int min);

        @Query("FROM Gizmo WHERE name LIKE :pattern")
        List<Gizmo> search(@Param("pattern") String pattern);

        @Query("FROM Gizmo WHERE name LIKE :pattern AND stock > :min")
        List<Gizmo> search(@Param("pattern") String pattern, @Param("min") int min);

        @Query("  update Gizmo SET stock = 0 WHERE name = :name")
        long empty(@Param("name") String name);

        @Query("DELETE FROM Gizmo WHERE stock = 0")
        long purge();

        long deleteByName(@Param("name") String name);

        @Insert
        Gizmo add(@Param("gizmo") Gizmo gizmo);

        @Update
        Gizmo change(@Param("gizmo") Gizmo gizmo);

        @Delete
        void remove(@Param("gizmo") Gizmo gizmo);

        @Save
        Gizmo keep(@Param("gizmo") Gizmo gizmo);

        Page<Gizmo> findByLevel(@Param("level") Level level, @Param("page") PageRequest page);

        List<Gizmo> findByNameIn(@Param("names") List<String> names);
    }

    @Repository
    public interface PartRepository extends BasicRepository<Part, Long> {
        List<Part> findByLabel(@Param("label") String label);
    }

    @Repository
    public interface BrokenRepository extends BasicRepository<Broken, Long> {}

    /** No Jakarta Data super-interface: no primary entity, no inherited method. */
    @Repository
    public interface ReportQueries {
        @Query("SELECT count(this) FROM Gizmo")
        long gizmoCount();
    }

    /** The entities of the catalogue, by class name. */
    static final Set<String> ENTITIES = Set.of(Gizmo.class.getName(), Part.class.getName(), Broken.class.getName());

    /** The repositories the panel runs, as MansartDataLive publishes them. */
    static final List<Class<?>> REPOSITORIES = List.of(GizmoRepository.class, PartRepository.class,
            ReportQueries.class);

    /** The models: Part's by hand, the others from Mansart itself. */
    static EntityModel<?> model(Class<?> type) {
        return type == Part.class ? partModel() : EntityModels.of(type);
    }

    static EntityJson entities() {
        return new EntityJson(RunFixtures::model, ENTITIES);
    }

    /** The catalogue of {@link #REPOSITORIES}; only the names and classes matter to the actions. */
    static MansartDataCatalogue catalogue() {
        MansartDataCatalogue.Entity gizmo =
                new MansartDataCatalogue.Entity("Gizmo", Gizmo.class.getName(), "gizmos", List.of(), null);
        MansartDataCatalogue.Entity part =
                new MansartDataCatalogue.Entity("Part", Part.class.getName(), "parts", List.of(), null);
        MansartDataCatalogue.Repository gizmos = new MansartDataCatalogue.Repository("GizmoRepository",
                GizmoRepository.class.getName(), Gizmo.class.getName(), "Gizmo", "Long", List.of(), 13, "");
        MansartDataCatalogue.Repository parts = new MansartDataCatalogue.Repository("PartRepository",
                PartRepository.class.getName(), Part.class.getName(), "Part", "Long", List.of(), 1, "");
        MansartDataCatalogue.Repository reports = new MansartDataCatalogue.Repository("ReportQueries",
                ReportQueries.class.getName(), null, null, null, List.of(), 1, "");
        return new MansartDataCatalogue(List.of(gizmo, part), List.of(gizmos, parts, reports), 2, 3, 15);
    }

    /** The model of {@link Part}, with real handles: its id, a reference to a gizmo, a label, a joined name. */
    static EntityModel<Part> partModel() {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            IdAttribute<Part, Long> id = new IdAttribute<>("id", "id", Long.class, Part.class, true,
                    lookup.findGetter(Part.class, "id", Long.class), lookup.findSetter(Part.class, "id", Long.class));
            ReferenceAttribute<Part, Gizmo> gizmo = new ReferenceAttribute<>("gizmo", "gizmo_id", Gizmo.class,
                    Part.class, true, false, false, lookup.findGetter(Part.class, "gizmo", Gizmo.class),
                    lookup.findSetter(Part.class, "gizmo", Gizmo.class));
            TextAttribute<Part> label = new TextAttribute<>("label", "label", Part.class, true, false, 100,
                    lookup.findGetter(Part.class, "label", String.class),
                    lookup.findSetter(Part.class, "label", String.class));
            TextAttribute<Gizmo> gizmoName = new TextAttribute<>("name", "name", Gizmo.class, true, false, 100,
                    null, null);
            JoinedAttribute<Part, String> joined = new JoinedAttribute<>(gizmoName,
                    JoinPath.of(new JoinPath.Step("gizmo", "gizmo_id", "id", "gizmos", "", Gizmo.class)), Part.class);
            return new EntityModel<>(Part.class, "parts", "", id, Optional.empty(), List.of(id, gizmo, label, joined),
                    lookup.findConstructor(Part.class, MethodType.methodType(void.class)));
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
```

- [ ] **Step 2: Write the failing tests**

`EntityJsonTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Part;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entities read and built through Mansart's model and its handles (spec §5). */
class EntityJsonTest {

    /** The schema of a Gizmo, in model order, none required. */
    static final String GIZMO_SCHEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},"
            + "\"name\":{\"type\":\"string\"},\"stock\":{\"type\":\"integer\"},"
            + "\"level\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]},"
            + "\"due\":{\"type\":\"string\",\"format\":\"date\"},\"price\":{\"type\":\"number\"}}}";

    private final EntityJson entities = RunFixtures.entities();

    @Test
    void anEntityIsAnObjectOfItsColumnsNoneRequired() {
        assertEquals(GIZMO_SCHEMA, Json.write(entities.schema(Gizmo.class)));
    }

    @Test
    void aReferenceTakesTheReferencedIdAndAJoinedAttributeIsLeftOut() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"},"
                + "\"gizmo\":{\"type\":\"integer\"},\"label\":{\"type\":\"string\"}}}",
                Json.write(entities.schema(Part.class)));
    }

    @Test
    void builtWithItsConstructorThenEachPropertyPresent() throws Exception {
        Gizmo gizmo = (Gizmo) entities.fromJson(Gizmo.class, Json.parse("{\"name\":\"bolt\",\"stock\":3,"
                + "\"level\":\"HIGH\",\"due\":\"2026-10-01\",\"price\":2.50}"), "gizmo");

        assertNull(gizmo.id(), "an absent id keeps the constructor's value, so that a generated one is generated");
        assertEquals("bolt", gizmo.name());
        assertEquals(3, gizmo.stock());
        assertEquals(Level.HIGH, gizmo.level());
        assertEquals(LocalDate.of(2026, 10, 1), gizmo.due());
        assertEquals(new BigDecimal("2.50"), gizmo.price());
    }

    @Test
    void aReferenceIsBuiltFromItsId() throws Exception {
        Part part = (Part) entities.fromJson(Part.class, Json.parse("{\"gizmo\":7,\"label\":\"left\"}"), "part");

        assertEquals(7L, part.gizmo().id());
        assertEquals("left", part.label());
    }

    @Test
    void eachFailureNamesItsProperty() {
        assertEquals("gizmo.colour: unknown property", refusal("{\"colour\":\"red\"}"));
        assertEquals("gizmo.stock: null is not allowed for int", refusal("{\"stock\":null}"));
        assertEquals("gizmo.due: not an ISO date", refusal("{\"due\":\"tomorrow\"}"));
        assertEquals("gizmo: not a JSON object", refusal("[]"));
    }

    private String refusal(String json) {
        return assertThrows(ArgumentException.class,
                () -> entities.fromJson(Gizmo.class, Json.parse(json), "gizmo")).getMessage();
    }

    @Test
    void toJsonInModelOrderAReferenceAsItsId() {
        Gizmo gizmo = RunFixtures.gizmo(7L, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));

        assertEquals("{\"id\":7,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":\"2026-10-01\","
                + "\"price\":2.50}", Json.write(entities.toJson(gizmo)));
        assertEquals("{\"id\":5,\"gizmo\":7,\"label\":\"left\"}",
                Json.write(entities.toJson(RunFixtures.part(5L, gizmo, "left"))));
    }

    @Test
    void onlyTheCatalogueEntitiesAreEntities() {
        assertTrue(entities.isEntity(Gizmo.class));
        assertFalse(entities.isEntity(String.class));
        assertFalse(entities.isEntity(null));
    }
}
```

`ResultJsonTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a method returns, as JSON and as the first words of the summary (spec §6). */
class ResultJsonTest {

    private final EntityJson entities = RunFixtures.entities();

    private static Gizmo bolt(long id) {
        return RunFixtures.gizmo(id, "bolt", 3, Level.LOW, null, null);
    }

    @Test
    void anEntityIsOneRow() {
        ResultJson.Result result = ResultJson.of(bolt(1), false, entities);

        assertEquals("1 row", result.what());
        assertFalse(result.rows());
        assertEquals("{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":null,\"price\":null}",
                result.body());
    }

    @Test
    void aListIsRowsCountedAndCutAtOneHundred() {
        assertEquals("3 rows", ResultJson.of(List.of(bolt(1), bolt(2), bolt(3)), false, entities).what());
        ResultJson.Result none = ResultJson.of(List.of(), false, entities);
        assertEquals("no row", none.what());
        assertEquals("[]", none.body());

        List<Gizmo> many = LongStream.rangeClosed(1, 150).mapToObj(ResultJsonTest::bolt).toList();
        ResultJson.Result first = ResultJson.of(many, false, entities);

        assertEquals("first 100 rows", first.what());
        assertTrue(first.rows());
        assertEquals(100, ((List<?>) Json.parse(first.body())).size());
    }

    @Test
    void aStreamIsReadToItsHundredAndFirstRowAndClosed() {
        AtomicInteger read = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        Stream<Gizmo> stream = LongStream.rangeClosed(1, 1000).mapToObj(id -> {
            read.incrementAndGet();
            return bolt(id);
        }).onClose(() -> closed.set(true));

        ResultJson.Result result = ResultJson.of(stream, false, entities);

        assertEquals("first 100 rows", result.what());
        assertEquals(101, read.get());
        assertTrue(closed.get());
    }

    @Test
    void aStreamThatFailsIsClosedAndTheFailureThrown() {
        AtomicBoolean closed = new AtomicBoolean();
        Stream<Gizmo> failing = Stream.<Gizmo>generate(() -> {
            throw new IllegalStateException("connection lost");
        }).onClose(() -> closed.set(true));

        assertThrows(IllegalStateException.class, () -> ResultJson.of(failing, false, entities));
        assertTrue(closed.get());
    }

    @Test
    void anOptionalIsItsValueOrNull() {
        assertEquals("1 row", ResultJson.of(Optional.of(bolt(1)), false, entities).what());
        ResultJson.Result empty = ResultJson.of(Optional.empty(), false, entities);
        assertEquals("no row", empty.what());
        assertEquals("null", empty.body());
    }

    @Test
    void voidIsDoneWithNoBody() {
        ResultJson.Result done = ResultJson.of(null, true, entities);

        assertEquals("done", done.what());
        assertNull(done.body());
    }

    @Test
    void aScalarIsItsValue() {
        ResultJson.Result count = ResultJson.of(42L, false, entities);
        assertEquals("42", count.what());
        assertEquals("42", count.body());
        assertEquals("true", ResultJson.of(true, false, entities).what());
        assertEquals("no row", ResultJson.of(null, false, entities).what());
    }

    @Test
    void anArrayIsRows() {
        ResultJson.Result result = ResultJson.of(new int[] {1, 2}, false, entities);

        assertEquals("2 rows", result.what());
        assertEquals("[1,2]", result.body());
    }

    @Test
    void aReferencedEntityIsItsIdNeverTheGraph() {
        ResultJson.Result result = ResultJson.of(List.of(RunFixtures.part(5L, bolt(7), "left")), false, entities);

        assertEquals("[{\"id\":5,\"gizmo\":7,\"label\":\"left\"}]", result.body());
    }
}
```

- [ ] **Step 3: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='EntityJsonTest,ResultJsonTest' > "$SCRATCH/t4.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t4.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (`EntityJson`, `ResultJson`).

- [ ] **Step 4: `EntityJson.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;

import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Entities to and from JSON through the model Mansart itself uses, {@code EntityModels.of} (spec §5): read with each
 * attribute's getter handle, built with the no-arg constructor handle then each property present set with its setter
 * handle. A property per attribute that has a setter and a type of spec §4, in model order; a reference to another
 * entity is that entity's id, never its graph; a joined attribute is left out.
 */
final class EntityJson {

    private final Function<Class<?>, EntityModel<?>> models;
    private final Set<String> entities;

    /**
     * @param models           the model of an entity class, {@code EntityModels.of} outside tests
     * @param entityClassNames the class names the catalogue knows as entities
     */
    EntityJson(Function<Class<?>, EntityModel<?>> models, Set<String> entityClassNames) {
        this.models = Objects.requireNonNull(models, "models");
        this.entities = Set.copyOf(entityClassNames);
    }

    /** Whether {@code type} is an entity of the catalogue. */
    boolean isEntity(Class<?> type) {
        return type != null && entities.contains(type.getName());
    }

    /**
     * The JSON Schema of {@code entity}: an object, one property per settable attribute, none required.
     *
     * @throws RuntimeException when its model cannot be read, as {@code EntityModels.of} throws it
     */
    Map<String, Object> schema(Class<?> entity) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Settable> property : settable(model(entity)).entrySet()) {
            properties.put(property.getKey(), Scalars.schema(property.getValue().type()));
        }
        return Scalars.object("type", "object", "properties", properties);
    }

    /**
     * A new {@code entity} from {@code json}: its no-arg constructor, then each property present, converted as spec
     * §4 says; an absent one keeps the constructor's value.
     *
     * @param name the parameter it is for, which a refusal names, {@code <name>.<property>}
     * @throws ArgumentException for a value that is no object, an unknown property or a value that does not convert
     */
    Object fromJson(Class<?> entity, Object json, String name) throws ArgumentException {
        if (json == null) {
            return null;
        }
        if (!(json instanceof Map<?, ?> members)) {
            throw new ArgumentException(name, "not a JSON object");
        }
        EntityModel<?> model = model(entity);
        Map<String, Settable> settable = settable(model);
        Object instance = construct(model);
        for (Map.Entry<?, ?> member : members.entrySet()) {
            String path = name + "." + member.getKey();
            Settable property = settable.get(String.valueOf(member.getKey()));
            if (property == null) {
                throw new ArgumentException(path, "unknown property");
            }
            Object value = Scalars.fromJson(property.type(), member.getValue(), path);
            if (property.referenced() != null && value != null) {
                Object reference = construct(property.referenced());
                set(property.referenced().id().setter(), reference, value);
                value = reference;
            }
            set(property.attribute().setter(), instance, value);
        }
        return instance;
    }

    /** {@code entity} as an object, one property per attribute in model order, a reference as its id. */
    Map<String, Object> toJson(Object entity) {
        EntityModel<?> model = model(entity.getClass());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model.attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?> || attribute.getter() == null) {
                continue;
            }
            Object value = get(attribute.getter(), entity);
            if (attribute instanceof ReferenceAttribute<?, ?> reference && value != null) {
                value = get(model(reference.javaType()).id().getter(), value);
            }
            out.put(attribute.name(), Scalars.toJson(value));
        }
        return out;
    }

    /**
     * One property a JSON object may set.
     *
     * @param attribute  its attribute
     * @param type       the Java type its value converts to: the field's own type, a primitive included, or for a
     *                   reference the referenced entity's id type
     * @param referenced for a reference, the model of the referenced entity; {@code null} otherwise
     */
    private record Settable(Attribute<?, ?> attribute, Class<?> type, EntityModel<?> referenced) {}

    private Map<String, Settable> settable(EntityModel<?> model) {
        Map<String, Settable> out = new LinkedHashMap<>();
        for (Attribute<?, ?> attribute : model.attributes()) {
            if (attribute == null || attribute instanceof JoinedAttribute<?, ?> || attribute.setter() == null) {
                continue;
            }
            try {
                EntityModel<?> referenced = attribute instanceof ReferenceAttribute<?, ?> reference
                        ? model(reference.javaType()) : null;
                Class<?> type = referenced == null ? fieldType(attribute) : fieldType(referenced.id());
                if (Scalars.schema(type) != null && (referenced == null || referenced.id().setter() != null)) {
                    out.put(attribute.name(), new Settable(attribute, type, referenced));
                }
            } catch (RuntimeException | LinkageError unreadable) {
                // a reference to an entity Mansart cannot model: left out, as a joined attribute is
            }
        }
        return out;
    }

    /** The type of the field behind {@code attribute}: its setter's, which keeps a primitive, else its Java type. */
    private static Class<?> fieldType(Attribute<?, ?> attribute) {
        MethodHandle setter = attribute.setter();
        return setter != null && setter.type().parameterCount() == 2
                ? setter.type().parameterType(1) : attribute.javaType();
    }

    private EntityModel<?> model(Class<?> type) {
        EntityModel<?> model = models.apply(type);
        if (model == null) {
            throw new IllegalStateException("no model of " + type.getName());
        }
        return model;
    }

    private static Object construct(EntityModel<?> model) {
        try {
            return model.constructor().invoke();
        } catch (Throwable failed) {
            throw unchecked(failed, "cannot build " + model.entityClass().getName());
        }
    }

    private static Object get(MethodHandle getter, Object entity) {
        try {
            return getter.invoke(entity);
        } catch (Throwable failed) {
            throw unchecked(failed, "cannot read " + entity.getClass().getName());
        }
    }

    private static void set(MethodHandle setter, Object entity, Object value) {
        try {
            setter.invoke(entity, value);
        } catch (Throwable failed) {
            throw unchecked(failed, "cannot set a value of " + entity.getClass().getName());
        }
    }

    private static RuntimeException unchecked(Throwable failed, String what) {
        if (failed instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failed instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(what, failed);
    }
}
```

- [ ] **Step 5: `ResultJson.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What a repository method returned, as the JSON body of its result and the first words of its summary (spec §6): an
 * entity as an object, a {@code List}, {@code Collection}, {@code Stream} or array as an array of at most
 * {@value #MAX_ROWS} elements (a {@code Stream} read to the next one only, then closed), an {@code Optional} as its
 * value or {@code null}, {@code void} as nothing.
 */
final class ResultJson {

    /** The most elements an array holds. */
    static final int MAX_ROWS = 100;

    /** How deep nested collections are followed; past it an element is its text. */
    private static final int MAX_DEPTH = 8;

    /**
     * @param body the JSON text, {@code null} for {@code void}
     * @param what {@code 3 rows}, {@code first 100 rows}, {@code 1 row}, {@code no row}, the value such as
     *             {@code 42}, or {@code done}
     * @param rows whether it was a list of rows, whose summary says how long it took
     */
    record Result(String body, String what, boolean rows) {}

    private ResultJson() {}

    /**
     * @param value    what the method returned
     * @param isVoid   whether it returns {@code void}
     * @param entities how entities are written
     */
    static Result of(Object value, boolean isVoid, EntityJson entities) {
        if (isVoid) {
            return new Result(null, "done", false);
        }
        if (value == null) {
            return new Result("null", "no row", false);
        }
        if (value instanceof Optional<?> optional) {
            return optional.isPresent()
                    ? new Result(Json.write(node(optional.get(), entities, 0)), "1 row", false)
                    : new Result("null", "no row", false);
        }
        if (value instanceof Stream<?> stream) {
            try (stream) {
                List<Object> read = new ArrayList<>();
                Iterator<?> iterator = stream.iterator();
                while (read.size() <= MAX_ROWS && iterator.hasNext()) {
                    read.add(iterator.next());
                }
                return rows(read, entities);
            }
        }
        if (value instanceof Collection<?> collection) {
            List<Object> read = new ArrayList<>();
            for (Object row : collection) {
                if (read.size() > MAX_ROWS) {
                    break;
                }
                read.add(row);
            }
            return rows(read, entities);
        }
        if (value.getClass().isArray()) {
            List<Object> read = new ArrayList<>();
            int length = Array.getLength(value);
            for (int i = 0; i < length && i <= MAX_ROWS; i++) {
                read.add(Array.get(value, i));
            }
            return rows(read, entities);
        }
        Object node = node(value, entities, 0);
        return new Result(Json.write(node), entities.isEntity(value.getClass()) ? "1 row" : String.valueOf(node),
                false);
    }

    /** {@code read} holds up to {@value #MAX_ROWS} + 1 rows: the last one only says there were more. */
    private static Result rows(List<Object> read, EntityJson entities) {
        boolean more = read.size() > MAX_ROWS;
        List<Object> kept = more ? read.subList(0, MAX_ROWS) : read;
        List<Object> json = new ArrayList<>(kept.size());
        for (Object row : kept) {
            json.add(node(row, entities, 1));
        }
        int n = kept.size();
        String what = more ? "first " + MAX_ROWS + " rows" : n == 0 ? "no row" : n == 1 ? "1 row" : n + " rows";
        return new Result(Json.write(json), what, true);
    }

    private static Object node(Object value, EntityJson entities, int depth) {
        if (value == null) {
            return null;
        }
        if (entities.isEntity(value.getClass())) {
            return entities.toJson(value);
        }
        if (depth < MAX_DEPTH) {
            if (value instanceof Optional<?> optional) {
                return optional.isPresent() ? node(optional.get(), entities, depth + 1) : null;
            }
            if (value instanceof Collection<?> collection) {
                List<Object> out = new ArrayList<>();
                for (Object element : collection) {
                    if (out.size() == MAX_ROWS) {
                        break;
                    }
                    out.add(node(element, entities, depth + 1));
                }
                return out;
            }
            if (value.getClass().isArray()) {
                List<Object> out = new ArrayList<>();
                int length = Math.min(Array.getLength(value), MAX_ROWS);
                for (int i = 0; i < length; i++) {
                    out.add(node(Array.get(value, i), entities, depth + 1));
                }
                return out;
            }
        }
        return Scalars.toJson(value);
    }
}
```

- [ ] **Step 6: Run the tests**

Same command as Step 3. Expected: both classes pass, `BUILD SUCCESS`. If Mansart builds Gizmo's attributes in
another order than its fields, fix the fixture's expectation only after checking `EntityModels.of(Gizmo.class)
.attributes()` (the runtime builder reads `getDeclaredFields()`, declaration order on HotSpot).

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): entities and results as JSON for the run actions

Entities are read and built through the model Mansart itself uses, with
its constructor, getter and setter handles (spec §5): a reference as the
referenced id, a joined attribute left out. A method's result becomes the
JSON body and the first words of the summary (spec §6): at most 100
elements, a Stream read to the next one only and closed.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
M=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
T=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $M/EntityJson.java $M/ResultJson.java $T/RunFixtures.java $T/EntityJsonTest.java $T/ResultJsonTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: DEV — a repository's methods, their signature, write detection

**Files:**
- Create: DEV main `RepositoryMethods.java`, `Signature.java`, `WriteKinds.java`
- Test: DEV test `SignatureTest.java`, `WriteKindsTest.java`

**Interfaces:**
- Consumes: `EntityJson`, `Scalars`, `Json`, `ArgumentException`; fixtures `RunFixtures`.
- Produces:
  - `RepositoryMethods.Candidate(Method method, List<String> names, List<Class<?>> types, String returns,
    boolean inherited)` with `String signature()` (`search(String, int)`);
    `static List<Candidate> RepositoryMethods.of(Class<?> repository)` — declared methods by name then signature,
    then the inherited `findById`, `findAll`, `save`, `deleteById`, `delete`, same order.
  - `Signature.Parameter(String name, Class<?> type, boolean entity)`;
    `static Signature Signature.of(Candidate, EntityJson, Predicate<Method> accessible)`;
    `List<Parameter> parameters()`, `String reason()` (`null` when runnable), `String schema()` (`null` when not),
    `Object[] arguments(Object json) throws ArgumentException`.
  - `static boolean WriteKinds.isWrite(Method)`.

- [ ] **Step 1: Write the failing tests**

`SignatureTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.BrokenRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.GizmoRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The methods of a repository, and for each its schema, why it cannot run, and its arguments (spec §2-§4). */
class SignatureTest {

    private final EntityJson entities = RunFixtures.entities();

    private static RepositoryMethods.Candidate candidate(Class<?> repository, String signature) {
        return RepositoryMethods.of(repository).stream().filter(c -> c.signature().equals(signature)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + signature));
    }

    private Signature signature(Class<?> repository, String signature) {
        return Signature.of(candidate(repository, signature), entities, method -> true);
    }

    @Test
    void theDeclaredMethodsByNameThenTheFiveInherited() {
        assertEquals(List.of("add(Gizmo)", "change(Gizmo)", "countByStockGreaterThan(int)", "deleteByName(String)",
                "empty(String)", "findByLevel(Level, PageRequest)", "findByName(String)", "findByNameIn(List)",
                "keep(Gizmo)", "purge()", "remove(Gizmo)", "search(String)", "search(String, int)",
                "delete(Gizmo)", "deleteById(Long)", "findAll()", "findAll(PageRequest, Order)", "findById(Long)",
                "save(Gizmo)"),
                RepositoryMethods.of(GizmoRepository.class).stream().map(RepositoryMethods.Candidate::signature)
                        .toList());
    }

    @Test
    void inheritedMethodsAreBoundToTheRepositorysTypes() {
        RepositoryMethods.Candidate save = candidate(GizmoRepository.class, "save(Gizmo)");
        assertEquals(List.of("entity"), save.names());
        assertEquals(List.of(Gizmo.class), save.types());
        assertEquals("Gizmo", save.returns());
        assertEquals(true, save.inherited());
        RepositoryMethods.Candidate findById = candidate(GizmoRepository.class, "findById(Long)");
        assertEquals(List.of("id"), findById.names());
        assertEquals("Optional<Gizmo>", findById.returns());
        assertEquals("Stream<Gizmo>", candidate(GizmoRepository.class, "findAll()").returns());
        assertEquals("List<Gizmo>", candidate(GizmoRepository.class, "search(String)").returns());
        assertEquals(false, candidate(GizmoRepository.class, "search(String)").inherited());
    }

    @Test
    void theSchemaOfEachParameterAllRequired() {
        assertEquals("{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"},"
                + "\"min\":{\"type\":\"integer\"}},\"required\":[\"pattern\",\"min\"]}",
                signature(GizmoRepository.class, "search(String, int)").schema());
        assertEquals("{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                signature(GizmoRepository.class, "purge()").schema());
        assertEquals("{\"type\":\"object\",\"properties\":{\"gizmo\":" + EntityJsonTest.GIZMO_SCHEMA
                + "},\"required\":[\"gizmo\"]}", signature(GizmoRepository.class, "keep(Gizmo)").schema());
        assertNull(signature(GizmoRepository.class, "search(String)").reason());
    }

    @Test
    void anUnsupportedParameterMakesTheMethodNotRunnable() {
        Signature page = signature(GizmoRepository.class, "findByLevel(Level, PageRequest)");
        assertEquals("parameter page: PageRequest is not supported", page.reason());
        assertNull(page.schema());
        assertEquals("parameter names: List is not supported",
                signature(GizmoRepository.class, "findByNameIn(List)").reason());
        assertEquals("parameter pageRequest: PageRequest is not supported",
                signature(GizmoRepository.class, "findAll(PageRequest, Order)").reason());
    }

    @Test
    void anEntityWithoutAModelMakesTheMethodNotRunnable() {
        assertEquals("parameter entity: Broken has no model (io.vidocq.mansart.data.core.MansartDataException)",
                signature(BrokenRepository.class, "save(Broken)").reason());
    }

    @Test
    void aPackageNotOpenMakesTheMethodNotRunnable() {
        Signature closed = Signature.of(candidate(GizmoRepository.class, "search(String)"), entities, m -> false);

        assertEquals("package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev not open to Vidocq",
                closed.reason());
    }

    @Test
    void theArgumentsInTheirOrder() throws Exception {
        assertArrayEquals(new Object[] {"%a%", 2}, signature(GizmoRepository.class, "search(String, int)")
                .arguments(Json.parse("{\"min\":2,\"pattern\":\"%a%\"}")));
        Object[] kept = signature(GizmoRepository.class, "keep(Gizmo)")
                .arguments(Json.parse("{\"gizmo\":{\"name\":\"bolt\"}}"));
        assertEquals("bolt", ((Gizmo) kept[0]).name());
        assertArrayEquals(new Object[0], signature(GizmoRepository.class, "purge()").arguments(Json.parse("{}")));
    }

    @Test
    void eachFailureNamesItsArgument() {
        Signature search = signature(GizmoRepository.class, "search(String, int)");

        assertEquals("min: missing", refusal(search, "{\"pattern\":\"a\"}"));
        assertEquals("max: unknown argument", refusal(search, "{\"pattern\":\"a\",\"min\":1,\"max\":2}"));
        assertEquals("arguments: not a JSON object", refusal(search, "[1]"));
        assertEquals("min: not an integer", refusal(search, "{\"pattern\":\"a\",\"min\":\"two\"}"));
    }

    private static String refusal(Signature signature, String json) {
        return assertThrows(ArgumentException.class, () -> signature.arguments(Json.parse(json))).getMessage();
    }
}
```

`WriteKindsTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which methods write (spec §3): each rule, and reads that look close. */
class WriteKindsTest {

    interface Samples {
        Gizmo save(Gizmo gizmo);

        List<Gizmo> saveAll(List<Gizmo> gizmos);

        Gizmo insertNew(Gizmo gizmo);

        long updateStock(int stock);

        void deleteEverything();

        long deleteByName(String name);

        @Insert
        Gizmo add(Gizmo gizmo);

        @Update
        Gizmo change(Gizmo gizmo);

        @Delete
        void remove(Gizmo gizmo);

        @Save
        Gizmo keep(Gizmo gizmo);

        @Query("  update Gizmo SET stock = 0")
        long empty();

        @Query("\n\tDELETE FROM Gizmo")
        long purge();

        @Query("FROM Gizmo WHERE name = :n")
        List<Gizmo> named(@Param("n") String n);

        @Query("SELECT count(this) FROM Gizmo")
        long total();

        List<Gizmo> findByName(String name);

        long countByStock(int stock);

        Gizmo saved();
    }

    private static final Set<String> WRITES = Set.of("save", "saveAll", "insertNew", "updateStock",
            "deleteEverything", "deleteByName", "add", "change", "remove", "keep", "empty", "purge");

    @Test
    void everyRuleOfSection3AndNothingElse() {
        for (Method method : Samples.class.getDeclaredMethods()) {
            assertEquals(WRITES.contains(method.getName()), WriteKinds.isWrite(method), method.getName());
        }
    }

    @Test
    void theInheritedWrites() throws Exception {
        assertTrue(WriteKinds.isWrite(BasicRepository.class.getMethod("save", Object.class)));
        assertTrue(WriteKinds.isWrite(BasicRepository.class.getMethod("deleteById", Object.class)));
        assertTrue(WriteKinds.isWrite(BasicRepository.class.getMethod("delete", Object.class)));
        assertFalse(WriteKinds.isWrite(BasicRepository.class.getMethod("findById", Object.class)));
        assertFalse(WriteKinds.isWrite(BasicRepository.class.getMethod("findAll")));
    }
}
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='SignatureTest,WriteKindsTest' > "$SCRATCH/t5.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t5.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (`RepositoryMethods`, `Signature`, `WriteKinds`).

- [ ] **Step 3: `RepositoryMethods.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.data.repository.Param;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The methods of a repository the panel offers (spec §1): every method the interface declares, neither static,
 * private nor synthetic, by name then signature, then the {@code findById}, {@code findAll}, {@code save},
 * {@code deleteById} and {@code delete} it inherits from Jakarta Data, same order. Their generic types are bound to
 * the repository's own, so that {@code BasicRepository.save(S)} takes a {@code Task} for a
 * {@code BasicRepository<Task, Long>}.
 */
final class RepositoryMethods {

    /** The Jakarta Data methods offered besides the declared ones. */
    static final Set<String> INHERITED = Set.of("findById", "findAll", "save", "deleteById", "delete");

    private static final String JAKARTA_DATA = "jakarta.data.repository";
    private static final int MAX_DEPTH = 16;

    /**
     * One method.
     *
     * @param method    the method, as the interface declares or inherits it
     * @param names     its parameters' names: {@code @Param}, else the real name, else {@code argN}
     * @param types     its parameters' classes, type variables bound
     * @param returns   its return type in simple names, type variables bound: {@code Optional<Task>}
     * @param inherited whether it comes from Jakarta Data
     */
    record Candidate(Method method, List<String> names, List<Class<?>> types, String returns, boolean inherited) {

        /** {@code name(Type, Type)}, in simple names: {@code search(String, int)}. */
        String signature() {
            StringJoiner out = new StringJoiner(", ", method.getName() + "(", ")");
            for (Class<?> type : types) {
                out.add(type.getSimpleName());
            }
            return out.toString();
        }
    }

    private RepositoryMethods() {}

    /** The methods of {@code repository}; reflection failures propagate, the caller lists the repository as such. */
    static List<Candidate> of(Class<?> repository) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        bind(repository, bindings, new HashSet<>());
        Comparator<Candidate> order = Comparator.comparing((Candidate c) -> c.method().getName())
                .thenComparing(Candidate::signature);

        List<Candidate> declared = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!method.isSynthetic() && !method.isBridge() && !Modifier.isStatic(modifiers)
                    && !Modifier.isPrivate(modifiers)) {
                declared.add(candidate(method, bindings, false));
            }
        }
        declared.sort(order);

        Map<String, Candidate> inherited = new HashMap<>();
        for (Method method : repository.getMethods()) {
            Class<?> owner = method.getDeclaringClass();
            if (owner != repository && JAKARTA_DATA.equals(owner.getPackageName())
                    && INHERITED.contains(method.getName()) && !Modifier.isStatic(method.getModifiers())) {
                inherited.putIfAbsent(method.getName() + Arrays.toString(method.getParameterTypes()),
                        candidate(method, bindings, true));
            }
        }
        List<Candidate> all = new ArrayList<>(declared);
        inherited.values().stream().sorted(order).forEach(all::add);
        return List.copyOf(all);
    }

    /** The name from {@code @Param}, else the real name when compiled with {@code -parameters}, else {@code argN}. */
    static String name(Parameter parameter, int index) {
        Param param = parameter.getAnnotation(Param.class);
        if (param != null && !param.value().isBlank()) {
            return param.value();
        }
        return parameter.isNamePresent() ? parameter.getName() : "arg" + index;
    }

    private static Candidate candidate(Method method, Map<TypeVariable<?>, Type> bindings, boolean inherited) {
        Parameter[] parameters = method.getParameters();
        Type[] generic;
        try {
            generic = method.getGenericParameterTypes();
        } catch (RuntimeException | LinkageError unreadable) {
            generic = method.getParameterTypes();
        }
        List<String> names = new ArrayList<>();
        List<Class<?>> types = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            names.add(name(parameters[i], i));
            types.add(resolve(i < generic.length ? generic[i] : parameters[i].getType(), bindings, 0));
        }
        String returns;
        try {
            returns = print(method.getGenericReturnType(), bindings, 0);
        } catch (RuntimeException | LinkageError unreadable) {
            returns = method.getReturnType().getSimpleName();
        }
        return new Candidate(method, List.copyOf(names), List.copyOf(types), returns, inherited);
    }

    /** Binds the type parameters of every super-interface of {@code type} to their arguments, however deep. */
    private static void bind(Class<?> type, Map<TypeVariable<?>, Type> bindings, Set<Class<?>> seen) {
        for (Type superType : type.getGenericInterfaces()) {
            if (superType instanceof ParameterizedType parameterized
                    && parameterized.getRawType() instanceof Class<?> raw) {
                TypeVariable<?>[] variables = raw.getTypeParameters();
                Type[] arguments = parameterized.getActualTypeArguments();
                for (int i = 0; i < variables.length && i < arguments.length; i++) {
                    bindings.putIfAbsent(variables[i], arguments[i]);
                }
                if (seen.add(raw)) {
                    bind(raw, bindings, seen);
                }
            } else if (superType instanceof Class<?> raw && seen.add(raw)) {
                bind(raw, bindings, seen);
            }
        }
    }

    /** The class {@code type} stands for: a variable's binding, else its first bound; {@code Object} at worst. */
    static Class<?> resolve(Type type, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (depth > MAX_DEPTH) {
            return Object.class;
        }
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType array) {
            return resolve(array.getGenericComponentType(), bindings, depth + 1).arrayType();
        }
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            if (bound != null) {
                return resolve(bound, bindings, depth + 1);
            }
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 ? Object.class : resolve(bounds[0], bindings, depth + 1);
        }
        if (type instanceof WildcardType wildcard) {
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 ? Object.class : resolve(upper[0], bindings, depth + 1);
        }
        return Object.class;
    }

    /** {@code type} in simple names, variables bound: {@code Optional<Task>}. */
    static String print(Type type, Map<TypeVariable<?>, Type> bindings, int depth) {
        if (depth > MAX_DEPTH) {
            return "?";
        }
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                return print(c.getComponentType(), bindings, depth + 1) + "[]";
            }
            return c.getSimpleName().isEmpty() ? c.getName() : c.getSimpleName();
        }
        if (type instanceof ParameterizedType parameterized) {
            StringJoiner arguments = new StringJoiner(", ", "<", ">");
            for (Type argument : parameterized.getActualTypeArguments()) {
                arguments.add(print(argument, bindings, depth + 1));
            }
            return print(parameterized.getRawType(), bindings, depth + 1) + arguments;
        }
        if (type instanceof GenericArrayType array) {
            return print(array.getGenericComponentType(), bindings, depth + 1) + "[]";
        }
        if (type instanceof TypeVariable<?> variable) {
            Type bound = bindings.get(variable);
            if (bound != null) {
                return print(bound, bindings, depth + 1);
            }
            Type[] bounds = variable.getBounds();
            return bounds.length == 0 || bounds[0] == Object.class
                    ? variable.getName() : print(bounds[0], bindings, depth + 1);
        }
        if (type instanceof WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return "? super " + print(wildcard.getLowerBounds()[0], bindings, depth + 1);
            }
            Type[] upper = wildcard.getUpperBounds();
            return upper.length == 0 || upper[0] == Object.class
                    ? "?" : "? extends " + print(upper[0], bindings, depth + 1);
        }
        return type.getTypeName();
    }
}
```

- [ ] **Step 4: `Signature.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * What the page asks for one method and how it becomes the call's arguments (spec §4): a JSON Schema with one
 * property per parameter, in order, all required; or why the method cannot run — its package is not open to Vidocq
 * (spec §3), a parameter type spec §4 does not list, or an entity whose model cannot be read.
 */
final class Signature {

    /**
     * One parameter.
     *
     * @param name   its name, which is its property's
     * @param type   its class, type variables bound
     * @param entity whether it is an entity of the catalogue
     */
    record Parameter(String name, Class<?> type, boolean entity) {}

    private final List<Parameter> parameters;
    private final String reason;
    private final String schema;
    private final EntityJson entities;

    private Signature(List<Parameter> parameters, String reason, String schema, EntityJson entities) {
        this.parameters = parameters;
        this.reason = reason;
        this.schema = schema;
        this.entities = entities;
    }

    /**
     * @param candidate  the method
     * @param entities   the catalogue's entities
     * @param accessible whether reflection may call the method; it makes it accessible when it can
     */
    static Signature of(RepositoryMethods.Candidate candidate, EntityJson entities, Predicate<Method> accessible) {
        Method method = candidate.method();
        String reason = accessible.test(method) ? null
                : "package " + method.getDeclaringClass().getPackageName() + " not open to Vidocq";
        List<Parameter> parameters = new ArrayList<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < candidate.types().size(); i++) {
            String name = candidate.names().get(i);
            Class<?> type = candidate.types().get(i);
            boolean entity = entities.isEntity(type);
            parameters.add(new Parameter(name, type, entity));
            if (reason != null) {
                continue;
            }
            Map<String, Object> property;
            try {
                property = entity ? entities.schema(type) : Scalars.schema(type);
            } catch (RuntimeException | LinkageError failed) {
                reason = "parameter " + name + ": " + type.getSimpleName() + " has no model ("
                        + failed.getClass().getName() + ")";
                continue;
            }
            if (property == null) {
                reason = "parameter " + name + ": " + type.getSimpleName() + " is not supported";
            } else {
                properties.put(name, property);
            }
        }
        String schema = reason != null ? null : Json.write(Scalars.object("type", "object", "properties", properties,
                "required", parameters.stream().map(Parameter::name).toList()));
        return new Signature(List.copyOf(parameters), reason, schema, entities);
    }

    List<Parameter> parameters() {
        return parameters;
    }

    /** Why the method cannot run, {@code null} when it can. */
    String reason() {
        return reason;
    }

    /** The schema of its {@code arguments}, {@code null} when it cannot run. */
    String schema() {
        return schema;
    }

    /**
     * The call's arguments from the JSON object the page sent, in the parameters' order.
     *
     * @throws ArgumentException for a value that is no object, a property no parameter has, a missing one, or a value
     *                           that does not convert
     */
    Object[] arguments(Object json) throws ArgumentException {
        if (!(json instanceof Map<?, ?> members)) {
            throw new ArgumentException("arguments", "not a JSON object");
        }
        for (Object key : members.keySet()) {
            if (parameters.stream().noneMatch(parameter -> parameter.name().equals(key))) {
                throw new ArgumentException(String.valueOf(key), "unknown argument");
            }
        }
        Object[] values = new Object[parameters.size()];
        for (int i = 0; i < values.length; i++) {
            Parameter parameter = parameters.get(i);
            if (!members.containsKey(parameter.name())) {
                throw new ArgumentException(parameter.name(), "missing");
            }
            Object value = members.get(parameter.name());
            values[i] = parameter.entity() ? entities.fromJson(parameter.type(), value, parameter.name())
                    : Scalars.fromJson(parameter.type(), value, parameter.name());
        }
        return values;
    }
}
```

- [ ] **Step 5: `WriteKinds.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.data.repository.Delete;
import jakarta.data.repository.Insert;
import jakarta.data.repository.Query;
import jakarta.data.repository.Save;
import jakarta.data.repository.Update;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Which methods write (spec §3), so that they ask first and run in a transaction: {@code save}, {@code saveAll},
 * a name starting with {@code insert}, {@code update} or {@code delete} (a derived {@code delete…By…} included),
 * {@code @Insert}, {@code @Update}, {@code @Delete}, {@code @Save}, and a {@code @Query} whose text starts, after
 * blanks, with {@code UPDATE} or {@code DELETE}, in any case.
 */
final class WriteKinds {

    private WriteKinds() {}

    static boolean isWrite(Method method) {
        String name = method.getName();
        if (name.equals("save") || name.equals("saveAll") || name.startsWith("insert") || name.startsWith("update")
                || name.startsWith("delete")) {
            return true;
        }
        if (method.isAnnotationPresent(Insert.class) || method.isAnnotationPresent(Update.class)
                || method.isAnnotationPresent(Delete.class) || method.isAnnotationPresent(Save.class)) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
            return false;
        }
        String text = query.value().stripLeading().toUpperCase(Locale.ROOT);
        return text.startsWith("UPDATE") || text.startsWith("DELETE");
    }
}
```

- [ ] **Step 6: Run the tests**

Same command as Step 2. Expected: both pass, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): a repository's methods, their schema and writes

The declared methods and the five inherited from Jakarta Data, their
generic types bound to the repository's; for each, the JSON Schema of its
arguments or why it cannot run (a package not open to Vidocq, a type spec
§4 does not list, an entity with no model), the conversion of the JSON
the page sends, and whether it writes (spec §3).

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
M=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
T=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $M/RepositoryMethods.java $M/Signature.java $M/WriteKinds.java $T/SignatureTest.java $T/WriteKindsTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: DEV — transactions and the bean at the call

**Files:**
- Create: DEV main `TransactionRunner.java`, `JtaDemarcation.java`, `BeanLookup.java`
- Modify: `DEV/pom.xml`, `DEV/src/main/java/module-info.java`
- Test: DEV test `RecordingTransactionManager.java`, `TransactionRunnerTest.java`

**Interfaces:**
- Produces:
  - `TransactionRunner`: `static final String ROLLBACK = "rollback"`, `COMMIT = "commit"`,
    `COMMITTED = "committed"`, `ROLLED_BACK = "rolled back"`; `interface Demarcation { void begin() throws
    Exception; void commit() throws Exception; void rollback() throws Exception; }`; `interface Work<T> { T run()
    throws Throwable; }`; `record Outcome<T>(T value, Throwable failure, String state)`;
    `static final TransactionRunner NONE`; `TransactionRunner(Demarcation demarcation)`;
    `static TransactionRunner of(BeanManager)`; `boolean available()`; `List<String> modes()`;
    `<T> Outcome<T> run(String mode, Work<T> work)` (`mode` `null` for a read).
  - `JtaDemarcation(Supplier<TransactionManager>)`; `static TransactionRunner.Demarcation of(BeanManager)` (`null`
    when no bean).
  - `interface BeanLookup { Object reference(Class<?> type); final class NoBean extends RuntimeException;
    static BeanLookup of(BeanManager); }`.

- [ ] **Step 1: The dependency and the module**

In `DEV/pom.xml`, replace:
```xml
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-devconsole-spi</artifactId>
        </dependency>
```
with:
```xml
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-devconsole-spi</artifactId>
        </dependency>
        <!-- A write runs in a transaction of the application's TransactionManager when it has one (spec §3): the
             application brings the API with vidocq-runtime-mansart-transactions-extension, so it stays optional here,
             as in mansart-data-cdi, and the module requires it static. -->
        <dependency>
            <groupId>jakarta.transaction</groupId>
            <artifactId>jakarta.transaction-api</artifactId>
            <version>2.0.1</version>
            <optional>true</optional>
        </dependency>
```
In `DEV/src/main/java/module-info.java`, replace:
```java
/** The Mansart Data catalogue panel of the dev console, which only vidocq:dev adds (Vidocq/vidocq#143). */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev {
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;
    requires io.vidocq.runtime.spi.devconsole;
```
with:
```java
/**
 * The Mansart Data panel of the dev console, its catalogue and the actions that run the repositories' methods, which
 * only vidocq:dev adds (Vidocq/vidocq#143).
 */
module io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev {
    requires io.vidocq.runtime.extensions.jakartaee.web.mansart.data;
    requires io.vidocq.runtime.spi.devconsole;
    // The repository beans, resolved at the call.
    requires jakarta.cdi;
    // A write runs in the application's TransactionManager when it has one; without the API, commit only.
    requires static jakarta.transaction;
```

- [ ] **Step 2: Write the failing tests**

`RecordingTransactionManager.java` (test):
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.List;

/** A transaction manager that records {@code begin}, {@code commit} and {@code rollback}, and may fail a commit. */
final class RecordingTransactionManager implements TransactionManager {

    final List<String> events = new ArrayList<>();
    RollbackException commitFailure;

    @Override
    public void begin() {
        events.add("begin");
    }

    @Override
    public void commit() throws RollbackException {
        events.add("commit");
        if (commitFailure != null) {
            throw commitFailure;
        }
    }

    @Override
    public void rollback() {
        events.add("rollback");
    }

    @Override
    public int getStatus() {
        return Status.STATUS_NO_TRANSACTION;
    }

    @Override
    public Transaction getTransaction() {
        return null;
    }

    @Override
    public void resume(Transaction transaction) {
        // nothing suspended here
    }

    @Override
    public void setRollbackOnly() {
        events.add("rollback-only");
    }

    @Override
    public void setTransactionTimeout(int seconds) {
        // no timeout here
    }

    @Override
    public Transaction suspend() {
        return null;
    }
}
```

`TransactionRunnerTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.transaction.RollbackException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A write's transaction (spec §3), with a recording TransactionManager. */
class TransactionRunnerTest {

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final TransactionRunner runner = new TransactionRunner(new JtaDemarcation(() -> manager));

    @Test
    void aReadRunsWithoutATransaction() {
        TransactionRunner.Outcome<String> read = runner.run(null, () -> "read");

        assertEquals("read", read.value());
        assertNull(read.state());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void rollbackComesFirstAndRollsBackAfterTheCall() {
        assertTrue(runner.available());
        assertEquals(List.of("rollback", "commit"), runner.modes());

        TransactionRunner.Outcome<Integer> outcome = runner.run("rollback", () -> {
            manager.events.add("call");
            return 1;
        });

        assertEquals(1, outcome.value());
        assertEquals("rolled back", outcome.state());
        assertEquals(List.of("begin", "call", "rollback"), manager.events);
    }

    @Test
    void commitCommits() {
        TransactionRunner.Outcome<Integer> outcome = runner.run("commit", () -> {
            manager.events.add("call");
            return 1;
        });

        assertEquals("committed", outcome.state());
        assertEquals(List.of("begin", "call", "commit"), manager.events);
    }

    @Test
    void anExceptionRollsBackWhateverWasAsked() {
        IllegalStateException boom = new IllegalStateException("boom");

        TransactionRunner.Outcome<Object> outcome = runner.run("commit", () -> {
            throw boom;
        });

        assertSame(boom, outcome.failure());
        assertNull(outcome.value());
        assertEquals("rolled back", outcome.state());
        assertEquals(List.of("begin", "rollback"), manager.events);
    }

    @Test
    void aFailedCommitIsAFailure() {
        manager.commitFailure = new RollbackException("timed out");

        TransactionRunner.Outcome<Integer> outcome = runner.run("commit", () -> 1);

        assertInstanceOf(RollbackException.class, outcome.failure());
        assertNull(outcome.state());
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void withoutAManagerOnlyCommitAndTheCallRunsAsItIs() {
        TransactionRunner none = TransactionRunner.NONE;

        assertFalse(none.available());
        assertEquals(List.of("commit"), none.modes());
        TransactionRunner.Outcome<Integer> committed = none.run("commit", () -> 1);
        assertEquals(1, committed.value());
        assertEquals("committed", committed.state());
        assertInstanceOf(IllegalStateException.class, none.run("rollback", () -> 1).failure());
    }

    @Test
    void noBeanManagerNoTransactionManager() {
        assertFalse(TransactionRunner.of(null).available());
    }
}
```

- [ ] **Step 3: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='TransactionRunnerTest' > "$SCRATCH/t6.log" 2>&1; grep -E "cannot find symbol|module not found|Tests run:|BUILD" "$SCRATCH/t6.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (`TransactionRunner`, `JtaDemarcation`). A `module not found:
jakarta.interceptor` instead means the CDI API does not bring the interceptor API to this module's compile path:
check `mvn -nsu dependency:tree -pl <DEV>` and, only then, add `jakarta.interceptor:jakarta.interceptor-api` (the
version the tree shows under `jakarta.enterprise.cdi-api`) as `<optional>true</optional>` next to the transaction
API, noting it in the commit message.

- [ ] **Step 4: `TransactionRunner.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.enterprise.inject.spi.BeanManager;

import java.util.List;
import java.util.Objects;

/**
 * Runs a call as spec §3 says: a read as it is; a write in a transaction of the application's
 * {@code jakarta.transaction.TransactionManager}, begun, then rolled back or committed as asked, and always rolled back
 * when the call throws. Without a manager — no bean, or not even the API on the module path — a write can only be
 * committed, and runs as it is (Mansart then autocommits).
 *
 * <p>This class never names a {@code jakarta.transaction} type: {@link JtaDemarcation} does, and is only loaded by
 * {@link #of}, which falls back to {@link #NONE} when it cannot be.
 */
final class TransactionRunner {

    static final String ROLLBACK = "rollback";
    static final String COMMIT = "commit";
    static final String COMMITTED = "committed";
    static final String ROLLED_BACK = "rolled back";

    /** No transaction manager: a write is committed as it runs. */
    static final TransactionRunner NONE = new TransactionRunner(null);

    /** Begins, commits and rolls back a transaction of the application. */
    interface Demarcation {
        void begin() throws Exception;

        void commit() throws Exception;

        void rollback() throws Exception;
    }

    /** The call, and whatever reads its result, run inside the transaction. */
    @FunctionalInterface
    interface Work<T> {
        T run() throws Throwable;
    }

    /**
     * How a call ended.
     *
     * @param value   what the work returned, {@code null} when it failed
     * @param failure what the work or the transaction threw, {@code null} when it went through
     * @param state   {@value #COMMITTED}, {@value #ROLLED_BACK}, or {@code null} for a read or when unknown
     */
    record Outcome<T>(T value, Throwable failure, String state) {}

    private final Demarcation demarcation;

    TransactionRunner(Demarcation demarcation) {
        this.demarcation = demarcation;
    }

    /** The runner of the application behind {@code beans}; {@link #NONE} when it has no transaction manager. */
    static TransactionRunner of(BeanManager beans) {
        try {
            Demarcation found = JtaDemarcation.of(beans);
            return found == null ? NONE : new TransactionRunner(found);
        } catch (RuntimeException | LinkageError absent) {
            return NONE;
        }
    }

    /** Whether writes run in a transaction that can be rolled back. */
    boolean available() {
        return demarcation != null;
    }

    /** The values of a write's {@code transaction} argument, the default first. */
    List<String> modes() {
        return available() ? List.of(ROLLBACK, COMMIT) : List.of(COMMIT);
    }

    /**
     * Runs {@code work}.
     *
     * @param mode {@code null} for a read, else {@value #ROLLBACK} or {@value #COMMIT}
     */
    <T> Outcome<T> run(String mode, Work<T> work) {
        Objects.requireNonNull(work, "work");
        if (mode == null) {
            return call(work, null);
        }
        if (demarcation == null) {
            return COMMIT.equals(mode) ? call(work, COMMITTED) : new Outcome<>(null,
                    new IllegalStateException("no TransactionManager: a write can only be committed"), null);
        }
        if (!COMMIT.equals(mode) && !ROLLBACK.equals(mode)) {
            return new Outcome<>(null, new IllegalArgumentException("no transaction mode " + mode), null);
        }
        try {
            demarcation.begin();
        } catch (Exception failed) {
            return new Outcome<>(null, failed, null);
        }
        T value;
        try {
            value = work.run();
        } catch (Throwable failure) {
            rollback(failure);
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            return new Outcome<>(null, failure, ROLLED_BACK);
        }
        try {
            if (COMMIT.equals(mode)) {
                demarcation.commit();
                return new Outcome<>(value, null, COMMITTED);
            }
            demarcation.rollback();
            return new Outcome<>(value, null, ROLLED_BACK);
        } catch (Exception failed) {
            return new Outcome<>(null, failed, null);
        }
    }

    private static <T> Outcome<T> call(Work<T> work, String state) {
        try {
            return new Outcome<>(work.run(), null, state);
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            return new Outcome<>(null, failure, null);
        }
    }

    private void rollback(Throwable failure) {
        try {
            demarcation.rollback();
        } catch (Exception suppressed) {
            failure.addSuppressed(suppressed);
        }
    }
}
```

- [ ] **Step 5: `JtaDemarcation.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.transaction.TransactionManager;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The transactions of the application's {@link TransactionManager} bean, resolved on first use and kept for the boot.
 * The only class of the module that names a {@code jakarta.transaction} type: without the API it fails to load, which
 * {@link TransactionRunner#of} turns into "no transaction manager".
 */
final class JtaDemarcation implements TransactionRunner.Demarcation {

    private final Supplier<TransactionManager> resolve;
    private volatile TransactionManager manager;

    JtaDemarcation(Supplier<TransactionManager> resolve) {
        this.resolve = Objects.requireNonNull(resolve, "resolve");
    }

    /** The transactions of the {@link TransactionManager} bean of {@code beans}; {@code null} when there is none. */
    static TransactionRunner.Demarcation of(BeanManager beans) {
        if (beans == null || beans.getBeans(TransactionManager.class).isEmpty()) {
            return null;
        }
        return new JtaDemarcation(() -> {
            Bean<?> bean = beans.resolve(beans.getBeans(TransactionManager.class));
            return (TransactionManager) beans.getReference(bean, TransactionManager.class,
                    beans.createCreationalContext(bean));
        });
    }

    private TransactionManager manager() {
        TransactionManager found = manager;
        if (found == null) {
            found = resolve.get();
            manager = found;
        }
        return found;
    }

    @Override
    public void begin() throws Exception {
        manager().begin();
    }

    @Override
    public void commit() throws Exception {
        manager().commit();
    }

    @Override
    public void rollback() throws Exception {
        manager().rollback();
    }
}
```

- [ ] **Step 6: `BeanLookup.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Set;

/**
 * The bean of a repository, resolved at each call (spec §3): {@code bm.getReference(bm.resolve(bm.getBeans(type)),
 * type, cc)}. An action may create a bean, unlike a sample; a Mansart repository is a singleton.
 */
@FunctionalInterface
interface BeanLookup {

    /**
     * The one bean of {@code type}.
     *
     * @throws NoBean when there is none, or more than one
     */
    Object reference(Class<?> type);

    /** No bean, or an ambiguous one: the call answers {@code no bean for <Repository>}. */
    final class NoBean extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NoBean() {
            super(null, null, false, false);
        }
    }

    /** The beans of {@code beans}. */
    static BeanLookup of(BeanManager beans) {
        return type -> {
            Set<Bean<?>> found = beans.getBeans(type);
            if (found.isEmpty()) {
                throw new NoBean();
            }
            Bean<?> bean;
            try {
                bean = beans.resolve(found);
            } catch (AmbiguousResolutionException ambiguous) {
                throw new NoBean();
            }
            if (bean == null) {
                throw new NoBean();
            }
            return beans.getReference(bean, type, beans.createCreationalContext(bean));
        };
    }
}
```

- [ ] **Step 7: Run the tests**

Same command as Step 3, then the whole module:
```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/t6b.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/t6b.log" | tail -5
```
Expected: all pass, `BUILD SUCCESS` (the module-info compiles with `requires static jakarta.transaction`).

- [ ] **Step 8: Commit**

Message:
```
feat(mansart-data): a write's transaction, and the bean at the call

A write runs in a transaction of the application's TransactionManager,
rolled back or committed as asked and always rolled back when the call
throws; without a manager, or without the API on the module path, it can
only be committed (spec §3). jakarta.transaction-api is optional and
required static. The repository bean is resolved at each call.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
D=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev
M=$D/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
T=$D/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $D/pom.xml $D/src/main/java/module-info.java $M/TransactionRunner.java $M/JtaDemarcation.java $M/BeanLookup.java $T/RecordingTransactionManager.java $T/TransactionRunnerTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: DEV — the actions and their history

**Files:**
- Create: DEV main `CallHistory.java`, `RepositoryActions.java`
- Modify: DEV main `CatalogueLivePanel.java` (only its `key` gains a `max`; the rest is Task 8)
- Test: DEV test `CallHistoryTest.java`, `RepositoryActionsTest.java`

**Interfaces:**
- Consumes: everything of Tasks 3-6.
- Produces:
  - `CallHistory`: `MAX = 20`, `MAX_ARGUMENTS = 200`, `MAX_ROWS = 100`, `COLUMNS = [time, method, outcome, ms,
    arguments, replay]`; `record Call(long sequence, long time, String method, String outcome, long millis,
    String arguments, String replay)`; `void add(String group, long time, String method, String outcome,
    long millis, String arguments, String replay)`; `List<Call> calls(String group)`; `void writeTo(PanelSample)`
    (table `calls`).
  - `RepositoryActions`: `NONE`; `MAX_ACTIONS = 128`; `MAX_REPOSITORY_KEY = 20`; `ARGUMENTS = "arguments"`;
    `TRANSACTION = "transaction"`; `static RepositoryActions build(List<Class<?>> repositories,
    MansartDataCatalogue catalogue, BeanLookup beans, TransactionRunner transactions,
    Function<Class<?>, EntityModel<?>> models, Predicate<Method> accessible, int maxActions)`;
    `List<PanelAction> actions()`; `void sample(PanelSample out)` (`not-runnable`, `more-methods`, `calls`);
    `static boolean accessible(Method)`; `static String group(String name, Set<String> used)`.
  - `static String CatalogueLivePanel.key(String name, Set<String> used, int max)`.

- [ ] **Step 1: `key` with a room**

In `CatalogueLivePanel.java`, replace:
```java
    /**
     * The key of a repository's table: its name in kebab case, {@code TaskRepository} as {@code task-repository},
     * starting with a letter, at most {@value #MAX_BASE} characters, and {@code -2}, {@code -3}… when {@code used}
     * already holds it. Adds the key to {@code used}.
     */
    static String key(String name, Set<String> used) {
```
with:
```java
    /**
     * The key of a repository's table: its name in kebab case, {@code TaskRepository} as {@code task-repository},
     * starting with a letter, at most {@value #MAX_BASE} characters, and {@code -2}, {@code -3}… when {@code used}
     * already holds it. Adds the key to {@code used}.
     */
    static String key(String name, Set<String> used) {
        return key(name, used, MAX_BASE);
    }

    /**
     * {@code name} in kebab case as {@link #key(String, Set)} makes it, at most {@code max} characters, the suffix of a
     * clash included: the run actions build their ids {@code m.<repository>.<method>} with it. Adds it to {@code used}.
     */
    static String key(String name, Set<String> used, int max) {
```
and in the body that follows, replace the two uses of `MAX_BASE`:
```java
        base = trim(base, MAX_BASE);
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = trim(base, MAX_BASE - suffix.length()) + suffix;
        }
        return key;
```
with:
```java
        base = trim(base, max);
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = trim(base, max - suffix.length()) + suffix;
        }
        return key;
```

- [ ] **Step 2: Write the failing tests**

`CallHistoryTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The last 20 calls of each repository, one table whose replay column the page splits by tab (spec §8). */
class CallHistoryTest {

    private final CallHistory history = new CallHistory();

    private Table table() {
        RecordedSample sample = new RecordedSample();
        history.writeTo(sample);
        return (Table) sample.value("calls");
    }

    @Test
    void theLastTwentyOfEachRepositoryNewestFirst() {
        for (int i = 1; i <= 25; i++) {
            history.add("GizmoRepository", 0L, "find" + i, "1 row", i, "{}", "m.g.find {}");
        }

        List<CallHistory.Call> calls = history.calls("GizmoRepository");

        assertEquals(20, calls.size());
        assertEquals("find25", calls.get(0).method());
        assertEquals("find6", calls.get(19).method());
    }

    @Test
    void theTableMergesTheRepositoriesNewestFirst() {
        history.add("A", 0L, "one", "1 row", 1, "{}", "m.a.one {\"arguments\":{}}");
        history.add("B", 0L, "two", "no row", 2, "{}", "m.b.two {\"arguments\":{}}");
        history.add("A", 0L, "three", "done", 3, "{}", "m.a.three {\"arguments\":{}}");

        Table table = table();

        assertEquals(List.of("time", "method", "outcome", "ms", "arguments", "replay"), table.columns());
        assertEquals(List.of("three", "two", "one"), table.rows().stream().map(row -> row.get(1)).toList());
        assertEquals(List.of("done", "3", "{}", "m.a.three {\"arguments\":{}}"),
                table.rows().get(0).subList(2, 6));
        assertTrue(table.rows().get(0).get(0).matches("\\d{2}:\\d{2}:\\d{2}"), table.rows().get(0).get(0));
    }

    @Test
    void longArgumentsAreCutAndALongReplayIsDropped() {
        String arguments = "{\"pattern\":\"" + "x".repeat(300) + "\"}";
        String replay = "m.g.search {\"arguments\":{\"pattern\":\"" + "y".repeat(5000) + "\"}}";

        history.add("A", 0L, "search", "no row", 1, arguments, replay);

        CallHistory.Call call = history.calls("A").getFirst();
        assertEquals(201, call.arguments().length());
        assertTrue(call.arguments().endsWith("…"));
        assertEquals("", call.replay());
    }

    @Test
    void atMostOneHundredRows() {
        for (int group = 0; group < 6; group++) {
            for (int i = 0; i < 20; i++) {
                history.add("G" + group, 0L, "m", "done", 0, "{}", "");
            }
        }

        assertEquals(100, table().rows().size());
    }
}
```

`RepositoryActionsTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Text;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.GizmoRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The run actions of the Mansart Data panel: what they are, and what a call does (spec §2-§8). */
class RepositoryActionsTest {

    private static final String SECRET = "hunter2";

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final RecordingBean bean = new RecordingBean();
    private final Object gizmos = Proxy.newProxyInstance(GizmoRepository.class.getClassLoader(),
            new Class<?>[] {GizmoRepository.class}, bean);

    /** The bean of GizmoRepository: records each call and answers what {@link #answers} holds for its name. */
    static final class RecordingBean implements InvocationHandler {

        /** The answer that returns the call's first argument. */
        static final Object ECHO = new Object();

        final List<String> methods = new ArrayList<>();
        final List<Object[]> arguments = new ArrayList<>();
        final Map<String, Object> answers = new HashMap<>();
        RuntimeException failure;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            methods.add(method.getName());
            Object[] given = args == null ? new Object[0] : args;
            arguments.add(given);
            if (failure != null) {
                throw failure;
            }
            Object answer = answers.get(method.getName());
            return answer == ECHO ? given[0] : answer;
        }
    }

    private RepositoryActions build(TransactionRunner transactions, Predicate<Method> accessible, int max) {
        return RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), type -> {
            if (type == GizmoRepository.class) {
                return gizmos;
            }
            throw new BeanLookup.NoBean();
        }, transactions, RunFixtures::model, accessible, max);
    }

    private RepositoryActions build() {
        return build(new TransactionRunner(new JtaDemarcation(() -> manager)), method -> true,
                RepositoryActions.MAX_ACTIONS);
    }

    private static PanelAction action(RepositoryActions actions, String id) {
        return actions.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    private static ActionResult run(RepositoryActions actions, String id, String arguments, String transaction) {
        Map<String, String> given = new HashMap<>();
        given.put("arguments", arguments);
        if (transaction != null) {
            given.put("transaction", transaction);
        }
        return action(actions, id).call().apply(given);
    }

    private static RecordedSample sample(RepositoryActions actions) {
        RecordedSample sample = new RecordedSample();
        actions.sample(sample);
        return sample;
    }

    private static Gizmo bolt() {
        return RunFixtures.gizmo(1L, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));
    }

    @Test
    void oneActionPerRunnableMethodIdsByRepositoryAndMethodKeys() {
        List<String> ids = build().actions().stream().map(PanelAction::id).toList();

        assertEquals(List.of("m.gizmo-repository.add", "m.gizmo-repository.change",
                "m.gizmo-repository.count-by-stock-greate", "m.gizmo-repository.delete-by-name",
                "m.gizmo-repository.empty", "m.gizmo-repository.find-by-name", "m.gizmo-repository.keep",
                "m.gizmo-repository.purge", "m.gizmo-repository.remove", "m.gizmo-repository.search",
                "m.gizmo-repository.search-2", "m.gizmo-repository.delete", "m.gizmo-repository.delete-by-id",
                "m.gizmo-repository.find-all", "m.gizmo-repository.find-by-id", "m.gizmo-repository.save",
                "m.part-repository.find-by-label", "m.part-repository.delete", "m.part-repository.delete-by-id",
                "m.part-repository.find-all", "m.part-repository.find-by-id", "m.part-repository.save",
                "m.report-queries.gizmo-count"), ids);
        for (String id : ids) {
            assertDoesNotThrow(() -> PanelSample.requireKey(id), id);
        }
    }

    @Test
    void oneGroupPerRepositoryLabelledByMethodName() {
        RepositoryActions actions = build();

        assertEquals(List.of("GizmoRepository", "PartRepository", "ReportQueries"),
                actions.actions().stream().map(PanelAction::group).distinct().toList());
        assertEquals("findById", action(actions, "m.gizmo-repository.find-by-id").label());
        assertEquals("search(String)", action(actions, "m.gizmo-repository.search").label());
        assertEquals("search(String, int)", action(actions, "m.gizmo-repository.search-2").label());
        assertEquals("JDQL: FROM Gizmo WHERE name LIKE :pattern\nsearch(pattern: String) → List<Gizmo>",
                action(actions, "m.gizmo-repository.search").description());
        assertEquals("findById(id: Long) → Optional<Gizmo>\ninherited from BasicRepository",
                action(actions, "m.gizmo-repository.find-by-id").description());
    }

    @Test
    void aWriteAsksFirstAndOffersRollbackThenCommit() {
        RepositoryActions actions = build();

        PanelAction save = action(actions, "m.gizmo-repository.save");
        assertEquals("Runs GizmoRepository.save against the database.", save.confirmation());
        assertEquals(List.of("arguments", "transaction"),
                save.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals(List.of("rollback", "commit"), save.arguments().get(1).allowedValues());

        PanelAction find = action(actions, "m.gizmo-repository.find-by-id");
        assertNull(find.confirmation());
        assertEquals(List.of("arguments"), find.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}",
                find.arguments().getFirst().schema());
    }

    @Test
    void withoutATransactionManagerAWriteCanOnlyBeCommitted() {
        RepositoryActions actions = build(TransactionRunner.NONE, method -> true, RepositoryActions.MAX_ACTIONS);
        bean.answers.put("save", RecordingBean.ECHO);

        assertEquals(List.of("commit"), action(actions, "m.gizmo-repository.save").arguments().get(1).allowedValues());
        assertEquals("1 row · committed",
                run(actions, "m.gizmo-repository.save", "{\"entity\":{\"name\":\"nut\"}}", "commit").summary());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void theMethodsThatCannotRunAreListedWithTheirReason() {
        Table table = (Table) sample(build()).value("not-runnable");

        assertEquals(List.of("repository", "method", "reason"), table.columns());
        assertEquals(List.of(
                List.of("GizmoRepository", "findByLevel(Level, PageRequest)",
                        "parameter page: PageRequest is not supported"),
                List.of("GizmoRepository", "findByNameIn(List)", "parameter names: List is not supported"),
                List.of("GizmoRepository", "findAll(PageRequest, Order)",
                        "parameter pageRequest: PageRequest is not supported"),
                List.of("PartRepository", "findAll(PageRequest, Order)",
                        "parameter pageRequest: PageRequest is not supported")), table.rows());
    }

    @Test
    void aPackageNotOpenToVidocqMakesNoAction() {
        RepositoryActions actions = build(TransactionRunner.NONE, method -> false, RepositoryActions.MAX_ACTIONS);

        assertEquals(List.of(), actions.actions());
        Table table = (Table) sample(actions).value("not-runnable");
        assertEquals(List.of("GizmoRepository", "add(Gizmo)",
                "package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev not open to Vidocq"),
                table.rows().getFirst());
        assertNull(sample(actions).value("calls"), "no action, no history");
    }

    @Test
    void pastTheConsolesLimitTheRestIsCounted() {
        RepositoryActions actions = build(TransactionRunner.NONE, method -> true, 5);

        assertEquals(5, actions.actions().size());
        assertEquals(new Text("and 18 more methods"), sample(actions).value("more-methods"));
    }

    @Test
    void aReadRunsWithoutATransactionAndAnswersJson() {
        RepositoryActions actions = build();
        bean.answers.put("findById", Optional.of(bolt()));

        ActionResult result = run(actions, "m.gizmo-repository.find-by-id", "{\"id\":1}", null);

        assertFalse(result.error());
        assertEquals("1 row", result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertEquals("{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":\"2026-10-01\","
                + "\"price\":2.50}", result.body());
        assertEquals("{\"method\":\"GizmoRepository.findById(Long)\",\"arguments\":[{\"name\":\"id\","
                + "\"type\":\"Long\",\"value\":1}],\"transaction\":\"none\"}", result.details());
        assertArrayEquals(new Object[] {1L}, bean.arguments.getFirst());
        assertEquals(List.of(), manager.events);
    }

    @Test
    void aListAnswerSaysHowLongItTook() {
        RepositoryActions actions = build();
        bean.answers.put("findByName", List.of(bolt(), bolt()));

        String summary = run(actions, "m.gizmo-repository.find-by-name", "{\"name\":\"bolt\"}", null).summary();

        assertTrue(summary.matches("2 rows in \\d+ ms"), summary);
    }

    @Test
    void aWriteInRollbackRollsBackAfterTheCall() {
        RepositoryActions actions = build();
        bean.answers.put("save", RecordingBean.ECHO);

        ActionResult result = run(actions, "m.gizmo-repository.save", "{\"entity\":{\"name\":\"nut\",\"stock\":1}}",
                "rollback");

        assertEquals("1 row · rolled back", result.summary());
        assertEquals(List.of("begin", "rollback"), manager.events);
        Gizmo saved = (Gizmo) bean.arguments.getFirst()[0];
        assertEquals("nut", saved.name());
        assertEquals(1, saved.stock());
        assertNull(saved.id());
        assertTrue(result.details().endsWith("\"transaction\":\"rollback\"}"), result.details());
    }

    @Test
    void aWriteCommitsWhenAsked() {
        ActionResult result = run(build(), "m.gizmo-repository.delete-by-id", "{\"id\":3}", "commit");

        assertEquals("done · committed", result.summary());
        assertNull(result.body());
        assertEquals(List.of("begin", "commit"), manager.events);
    }

    @Test
    void aFailingMethodRollsBackAndShowsItsClassAndItsMaskedMessage() {
        RepositoryActions actions = build();
        bean.failure = new IllegalStateException("connection to jdbc:h2:tcp://sa:" + SECRET + "@db/x refused");

        ActionResult result = run(actions, "m.gizmo-repository.save", "{\"entity\":{\"name\":\"nut\"}}", "commit");

        String expected = "java.lang.IllegalStateException: connection to jdbc:h2:tcp://***:***@db/x refused";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events, "rolled back although commit was asked");
        Table calls = (Table) sample(actions).value("calls");
        assertEquals("error: " + expected, calls.rows().getFirst().get(2));
        assertFalse(calls.toString().contains(SECRET));
    }

    @Test
    void aValueThatDoesNotConvertFailsBeforeAnyCall() {
        RepositoryActions actions = build();

        assertEquals("id: not an integer",
                run(actions, "m.gizmo-repository.find-by-id", "{\"id\":\"one\"}", null).summary());
        assertEquals("id: missing", run(actions, "m.gizmo-repository.find-by-id", "{}", null).summary());
        ActionResult write = run(actions, "m.gizmo-repository.save", "{\"entity\":{\"stock\":null}}", "rollback");
        assertTrue(write.error());
        assertEquals("entity.stock: null is not allowed for int", write.summary());
        assertEquals(List.of(), bean.methods, "nothing invoked");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void aRepositoryWithoutABeanSaysSo() {
        ActionResult result = run(build(), "m.part-repository.find-by-label", "{\"label\":\"x\"}", null);

        assertTrue(result.error());
        assertEquals("no bean for PartRepository", result.summary());
    }

    @Test
    void aStreamThatFailsIsAnErrorAndIsClosed() {
        RepositoryActions actions = build();
        AtomicBoolean closed = new AtomicBoolean();
        bean.answers.put("findAll", Stream.<Gizmo>generate(() -> {
            throw new IllegalStateException("connection lost");
        }).onClose(() -> closed.set(true)));

        ActionResult result = run(actions, "m.gizmo-repository.find-all", "{}", null);

        assertTrue(result.error());
        assertEquals("java.lang.IllegalStateException: connection lost", result.summary());
        assertTrue(closed.get());
    }

    @Test
    void eachCallIsKeptWithItsReplay() {
        RepositoryActions actions = build();
        bean.answers.put("save", RecordingBean.ECHO);
        run(actions, "m.gizmo-repository.save", "{\"entity\": {\"name\": \"nut\", \"stock\": 1}}", "rollback");

        Table calls = (Table) sample(actions).value("calls");

        List<String> row = calls.rows().getFirst();
        assertEquals(CallHistory.COLUMNS, calls.columns());
        assertEquals("save", row.get(1));
        assertEquals("1 row · rolled back", row.get(2));
        assertEquals("{\"entity\":{\"name\":\"nut\",\"stock\":1}}", row.get(4), "the JSON sent, compact");
        assertEquals("m.gizmo-repository.save {\"arguments\":{\"entity\":{\"name\":\"nut\",\"stock\":1}},"
                + "\"transaction\":\"rollback\"}", row.get(5));
    }

    @Test
    void longNamesAreCutFromTheLeftAndKeptApart() {
        Set<String> used = new HashSet<>();
        String name = "io.vidocq.examples.shop.inventory.persistence.GizmoRepository";

        String first = RepositoryActions.group(name, used);
        String second = RepositoryActions.group(name, used);

        assertEquals(40, first.length());
        assertTrue(first.startsWith("…") && first.endsWith("persistence.GizmoRepository"), first);
        assertTrue(second.endsWith(" 2") && second.length() <= 40 && !second.equals(first), second);
        assertEquals("GizmoRepository", RepositoryActions.group("GizmoRepository", used));
    }
}
```

- [ ] **Step 3: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='CallHistoryTest,RepositoryActionsTest,CatalogueLivePanelTest' > "$SCRATCH/t7.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t7.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (`CallHistory`, `RepositoryActions`).

- [ ] **Step 4: `CallHistory.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last {@value #MAX} calls of each repository this boot (spec §8), newest first, in memory. They are published as
 * one {@code calls} table with a {@link PanelSample#REPLAY_COLUMN replay} column, which the page moves to the
 * repository tabs, each keeping the rows of its own actions. Immutable lists behind a {@code volatile}:
 * {@code sample} reads them without a lock. A dev reload builds a new history with the new actions.
 */
final class CallHistory {

    /** How many calls of one repository are kept. */
    static final int MAX = 20;
    /** The longest {@code arguments} cell. */
    static final int MAX_ARGUMENTS = 200;
    /** The most rows of the table: the console keeps 100 rows per table. */
    static final int MAX_ROWS = 100;
    /** The columns of the {@code calls} table. */
    static final List<String> COLUMNS = List.of("time", "method", "outcome", "ms", "arguments",
            PanelSample.REPLAY_COLUMN);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * One call.
     *
     * @param sequence  its rank in the boot, newest highest
     * @param time      when it ended, in epoch milliseconds
     * @param method    the label of its action
     * @param outcome   its summary, {@code error: } first for an error
     * @param millis    how long it took
     * @param arguments the JSON sent, cut at {@value #MAX_ARGUMENTS} characters
     * @param replay    {@code <action id> <JSON of its arguments by name>}, or empty past
     *                  {@link PanelSample#MAX_REPLAY_CELL}
     */
    record Call(long sequence, long time, String method, String outcome, long millis, String arguments,
                String replay) {}

    private long sequence;
    private volatile Map<String, List<Call>> calls = Map.of();

    /** Adds a call of the repository whose group is {@code group}, first, dropping its oldest past {@value #MAX}. */
    synchronized void add(String group, long time, String method, String outcome, long millis, String arguments,
                          String replay) {
        Call call = new Call(++sequence, time, method, outcome, millis, Failures.cut(arguments, MAX_ARGUMENTS),
                replay.length() > PanelSample.MAX_REPLAY_CELL ? "" : replay);
        Map<String, List<Call>> next = new HashMap<>(calls);
        List<Call> previous = next.getOrDefault(group, List.of());
        List<Call> kept = new ArrayList<>(MAX);
        kept.add(call);
        kept.addAll(previous.subList(0, Math.min(previous.size(), MAX - 1)));
        next.put(group, List.copyOf(kept));
        calls = Map.copyOf(next);
    }

    /** The calls of {@code group}, newest first. */
    List<Call> calls(String group) {
        return calls.getOrDefault(group, List.of());
    }

    /** Writes the {@code calls} table: every repository's calls, newest first, {@value #MAX_ROWS} at most. */
    void writeTo(PanelSample out) {
        List<List<String>> rows = calls.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> Long.compare(b.sequence(), a.sequence()))
                .limit(MAX_ROWS)
                .map(call -> List.of(TIME.format(Instant.ofEpochMilli(call.time())), call.method(), call.outcome(),
                        Long.toString(call.millis()), call.arguments(), call.replay()))
                .toList();
        out.table("calls", COLUMNS, rows);
    }
}
```

- [ ] **Step 5: `RepositoryActions.java`**

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import jakarta.data.repository.Query;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The actions of the Mansart Data panel that run the repositories' methods (spec §2-§8), built once per boot by the
 * panel's {@code actions()}. Each repository is a group, a tab of the page; each runnable method one action,
 * {@code m.<repository key>.<method key>}, whose json argument {@code arguments} holds its parameters. A write asks
 * first and has a {@code transaction} argument, {@code rollback} (the default) or {@code commit}.
 *
 * <p>A call converts the arguments before anything else, then resolves the bean, then runs the method — for a write
 * in a transaction — and turns its result into JSON inside that transaction, so that a {@code Stream} is read before
 * it is rolled back. Every call is kept in a {@link CallHistory}.
 *
 * <p>Holds the application's classes and bean manager for one boot: the panel drops it in {@code stop}.
 */
final class RepositoryActions {

    /** Before {@code actions()} and after {@code stop}: no action, nothing sampled. */
    static final RepositoryActions NONE = new RepositoryActions(null, TransactionRunner.NONE, null);
    /** The most actions of one panel the console keeps. */
    static final int MAX_ACTIONS = 128;
    /** The longest repository key of an id, so that the method key keeps at least 17 characters. */
    static final int MAX_REPOSITORY_KEY = 20;
    static final String ARGUMENTS = "arguments";
    static final String TRANSACTION = "transaction";
    static final List<String> NOT_RUNNABLE_COLUMNS = List.of("repository", "method", "reason");

    private static final int MAX_ID = 40;
    private static final String PREFIX = "m.";
    private static final System.Logger LOG = System.getLogger(RepositoryActions.class.getName());

    /**
     * One action.
     *
     * @param id             its id
     * @param label          its label, the method's name or signature
     * @param repositoryName the catalogue's name of its repository
     * @param repository     the repository interface
     * @param group          its tab
     * @param candidate      its method
     * @param signature      its schema and arguments
     * @param write          whether it writes
     */
    record Entry(String id, String label, String repositoryName, Class<?> repository, String group,
                 RepositoryMethods.Candidate candidate, Signature signature, boolean write) {}

    private final BeanLookup beans;
    private final TransactionRunner transactions;
    private final EntityJson entities;
    private final CallHistory history = new CallHistory();
    private final List<PanelAction> actions = new ArrayList<>();
    private final List<List<String>> notRunnable = new ArrayList<>();
    private int moreMethods;

    private RepositoryActions(BeanLookup beans, TransactionRunner transactions, EntityJson entities) {
        this.beans = beans;
        this.transactions = transactions;
        this.entities = entities;
    }

    /**
     * The actions of {@code repositories}, in the catalogue's name order.
     *
     * @param repositories the repository interfaces {@code MansartDataLive} holds
     * @param catalogue    the catalogue, for the names and the entities
     * @param beans        the beans, resolved at each call
     * @param transactions the transactions of a write
     * @param models       the model of an entity class, {@code EntityModels.of}
     * @param accessible   whether reflection may call a method, making it accessible; see {@link #accessible}
     * @param maxActions   the most actions, {@value #MAX_ACTIONS}; the rest is counted as {@code more-methods}
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions) {
        Set<String> entityNames = new HashSet<>();
        for (MansartDataCatalogue.Entity entity : catalogue.entities()) {
            entityNames.add(entity.className());
        }
        Map<String, String> names = new HashMap<>();
        for (MansartDataCatalogue.Repository repository : catalogue.repositories()) {
            names.put(repository.className(), repository.name());
            if (repository.entityClassName() != null) {
                entityNames.add(repository.entityClassName());
            }
        }
        RepositoryActions built = new RepositoryActions(beans, transactions, new EntityJson(models, entityNames));
        List<Class<?>> ordered = new ArrayList<>(repositories);
        ordered.sort(Comparator.comparing((Class<?> type) -> names.getOrDefault(type.getName(), type.getName())));
        Set<String> keys = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (Class<?> repository : ordered) {
            String name = names.getOrDefault(repository.getName(), repository.getName());
            built.add(repository, name, CatalogueLivePanel.key(name, keys, MAX_REPOSITORY_KEY), group(name, groups),
                    accessible, maxActions);
        }
        return built;
    }

    /** Whether reflection may call {@code method}: its package open to this module; made accessible when it is. */
    static boolean accessible(Method method) {
        try {
            return method.trySetAccessible();
        } catch (SecurityException refused) {
            return false;
        }
    }

    /**
     * The tab of a repository: its name, cut from the left with {@code …} past {@value PanelAction#MAX_GROUP}
     * characters, {@code  2}, {@code  3}… when {@code used} already holds it. Adds it to {@code used}.
     */
    static String group(String name, Set<String> used) {
        String base = left(name, PanelAction.MAX_GROUP);
        String group = base;
        for (int n = 2; !used.add(group); n++) {
            String suffix = " " + n;
            group = left(base, PanelAction.MAX_GROUP - suffix.length()) + suffix;
        }
        return group;
    }

    private static String left(String name, int max) {
        return name.length() <= max ? name : "…" + name.substring(name.length() - (max - 1));
    }

    /** The actions, in page order. */
    List<PanelAction> actions() {
        return List.copyOf(actions);
    }

    /**
     * Writes, in the <i>Monitoring</i> tab, the methods that cannot run ({@code not-runnable}) and those past the
     * console's limit ({@code more-methods}); and the {@code calls} table, which the page shows in the repository tabs.
     */
    void sample(PanelSample out) {
        if (!notRunnable.isEmpty()) {
            out.table("not-runnable", NOT_RUNNABLE_COLUMNS, notRunnable);
        }
        if (moreMethods > 0) {
            out.text("more-methods", "and " + moreMethods + " more methods");
        }
        if (!actions.isEmpty()) {
            history.writeTo(out);
        }
    }

    private void add(Class<?> repository, String name, String key, String group, Predicate<Method> accessible,
                     int maxActions) {
        List<RepositoryMethods.Candidate> candidates;
        try {
            candidates = RepositoryMethods.of(repository);
        } catch (RuntimeException | LinkageError unreadable) {
            notRunnable.add(List.of(name, "", "methods unreadable: " + unreadable.getClass().getName()));
            return;
        }
        List<Signature> signatures = new ArrayList<>();
        Map<String, Integer> runnable = new HashMap<>();
        for (RepositoryMethods.Candidate candidate : candidates) {
            Signature signature = Signature.of(candidate, entities, accessible);
            signatures.add(signature);
            if (signature.reason() == null) {
                runnable.merge(candidate.method().getName(), 1, Integer::sum);
            }
        }
        Set<String> methodKeys = new HashSet<>();
        int maxMethodKey = MAX_ID - PREFIX.length() - key.length() - 1;
        for (int i = 0; i < candidates.size(); i++) {
            RepositoryMethods.Candidate candidate = candidates.get(i);
            Signature signature = signatures.get(i);
            if (signature.reason() != null) {
                notRunnable.add(List.of(name, candidate.signature(), signature.reason()));
                continue;
            }
            if (actions.size() >= maxActions) {
                moreMethods++;
                continue;
            }
            String method = candidate.method().getName();
            try {
                String id = PREFIX + key + "." + CatalogueLivePanel.key(method, methodKeys, maxMethodKey);
                String label = runnable.get(method) > 1 ? candidate.signature() : method;
                actions.add(action(new Entry(id, label, name, repository, group, candidate, signature,
                        WriteKinds.isWrite(candidate.method()))));
            } catch (IllegalArgumentException refused) {
                notRunnable.add(List.of(name, candidate.signature(), "the console refuses it: "
                        + refused.getMessage()));
            } catch (RuntimeException | LinkageError unreadable) {
                notRunnable.add(List.of(name, candidate.signature(), "unreadable: "
                        + unreadable.getClass().getName()));
            }
        }
    }

    private PanelAction action(Entry entry) {
        List<PanelAction.Argument> arguments = new ArrayList<>();
        arguments.add(PanelAction.Argument.json(ARGUMENTS, "Arguments", entry.signature().schema()));
        if (entry.write()) {
            arguments.add(new PanelAction.Argument(TRANSACTION, "Transaction", transactions.modes(), null, null));
        }
        String confirmation = entry.write() ? "Runs " + entry.repositoryName() + "."
                + entry.candidate().method().getName() + " against the database." : null;
        return new PanelAction(entry.id(), entry.label(), confirmation, arguments, given -> call(entry, given),
                entry.group(), description(entry.candidate()));
    }

    /** {@code JDQL: <query>}, then {@code name(param: Type, …) → Return}, then where an inherited method comes from. */
    static String description(RepositoryMethods.Candidate candidate) {
        StringBuilder out = new StringBuilder();
        Query query = candidate.method().getAnnotation(Query.class);
        if (query != null) {
            out.append("JDQL: ").append(query.value().strip()).append('\n');
        }
        out.append(candidate.method().getName()).append('(');
        for (int i = 0; i < candidate.names().size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(candidate.names().get(i)).append(": ").append(candidate.types().get(i).getSimpleName());
        }
        out.append(") → ").append(candidate.returns());
        if (candidate.inherited()) {
            out.append("\ninherited from ").append(candidate.method().getDeclaringClass().getSimpleName());
        }
        return Failures.cut(out.toString(), PanelAction.MAX_DESCRIPTION - 1);
    }

    /** Runs {@code entry} with the arguments the console checked, and keeps the call. */
    PanelAction.ActionResult call(Entry entry, Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(ARGUMENTS, "{}");
        String mode = entry.write() ? given.getOrDefault(TRANSACTION, TransactionRunner.COMMIT) : null;
        Object parsed = null;
        PanelAction.ActionResult result;
        try {
            parsed = parse(sent);
            result = run(entry, arguments(entry, parsed), bean(entry), parsed, mode, start);
        } catch (Refused refused) {
            result = new PanelAction.ActionResult(refused.getMessage(), null, null, true, details(entry, parsed, mode));
        }
        history.add(entry.group(), System.currentTimeMillis(), entry.label(),
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed), replay(entry, parsed, mode));
        return result;
    }

    private PanelAction.ActionResult run(Entry entry, Object[] arguments, Object bean, Object parsed, String mode,
                                         long start) {
        Method method = entry.candidate().method();
        boolean isVoid = method.getReturnType() == void.class;
        TransactionRunner.Outcome<ResultJson.Result> outcome =
                transactions.run(mode, () -> ResultJson.of(invoke(method, bean, arguments), isVoid, entities));
        String details = details(entry, parsed, mode);
        if (outcome.failure() != null) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + entry.id() + " failed: "
                    + outcome.failure().getClass().getName());
            String text = Failures.text(outcome.failure());
            return new PanelAction.ActionResult(text, PanelAction.ActionResult.TEXT, text, true, details);
        }
        ResultJson.Result value = outcome.value();
        String summary = value.what() + (value.rows() ? " in " + millis(start) + " ms" : "")
                + (outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, value.body() == null ? null : PanelAction.ActionResult.JSON,
                value.body(), false, details);
    }

    private static Object invoke(Method method, Object bean, Object[] arguments) throws Throwable {
        try {
            return method.invoke(bean, arguments);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause() == null ? thrown : thrown.getCause();
        }
    }

    private static Object parse(String sent) throws Refused {
        try {
            return Json.parse(sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(ARGUMENTS + ": " + unreadable.getMessage());
        }
    }

    private static Object[] arguments(Entry entry, Object parsed) throws Refused {
        try {
            return entry.signature().arguments(parsed);
        } catch (ArgumentException refused) {
            throw new Refused(refused.getMessage());
        } catch (RuntimeException | LinkageError failed) {
            throw new Refused(Failures.text(failed));
        }
    }

    private Object bean(Entry entry) throws Refused {
        try {
            return beans.reference(entry.repository());
        } catch (RuntimeException | LinkageError none) {
            throw new Refused("no bean for " + entry.repositoryName());
        }
    }

    /** What the page shows under "Exchange" (spec §6): the method, each argument, the transaction asked. */
    private static String details(Entry entry, Object parsed, String mode) {
        Map<?, ?> members = parsed instanceof Map<?, ?> map ? map : Map.of();
        List<Object> arguments = new ArrayList<>();
        for (Signature.Parameter parameter : entry.signature().parameters()) {
            arguments.add(Scalars.object("name", parameter.name(), "type", parameter.type().getSimpleName(),
                    "value", members.get(parameter.name())));
        }
        return Json.write(Scalars.object("method", entry.repositoryName() + "." + entry.candidate().signature(),
                "arguments", arguments, "transaction", mode == null ? "none" : mode));
    }

    /** {@code <action id> {"arguments": {...}, "transaction": "..."}}, or empty when the arguments were no object. */
    private static String replay(Entry entry, Object parsed, String mode) {
        if (!(parsed instanceof Map<?, ?>)) {
            return "";
        }
        Map<String, Object> values = mode == null ? Scalars.object(ARGUMENTS, parsed)
                : Scalars.object(ARGUMENTS, parsed, TRANSACTION, mode);
        return entry.id() + " " + Json.write(values);
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** A call refused before the method runs: its message is the summary. */
    private static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
```

- [ ] **Step 6: Run the tests**

Same command as Step 3. Expected: `CallHistoryTest`, `RepositoryActionsTest` and the existing
`CatalogueLivePanelTest` pass, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

Message:
```
feat(mansart-data): the run actions and their history

One action per runnable repository method, m.<repository>.<method>, one
group per repository (spec §2): a write asks first and offers rollback
then commit (spec §3); a call converts its arguments before anything,
resolves the bean, runs the method and reads its result in the
transaction; a failure shows its class and masked message (spec §7). The
methods that cannot run, those past the console's 128, and the last 20
calls of each repository with their replay (spec §8) are sampled.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
M=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
T=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $M/CallHistory.java $M/RepositoryActions.java $M/CatalogueLivePanel.java $T/CallHistoryTest.java $T/RepositoryActionsTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 8: DEV — wiring into the panel

**Files:**
- Modify: DEV main `CatalogueLivePanel.java` (whole file below)
- Modify: DEV test `CatalogueLivePanelTest.java`

**Interfaces:**
- Consumes: `RepositoryActions`, `BeanLookup`, `TransactionRunner`, `MansartDataLive.repositories()`.
- Produces: `CatalogueLivePanel.start(ExtensionContext)`, `stop()`, `actions()`; package-private
  `List<PanelAction> actions(BeanLookup, TransactionRunner, Function<Class<?>, EntityModel<?>>, Predicate<Method>)`.
  DEV installed in `~/.m2` for Task 9.

- [ ] **Step 1: Write the failing tests**

In `CatalogueLivePanelTest.java`:

1. Replace
```java
import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.AfterEach;
```
with
```java
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import org.junit.jupiter.api.AfterEach;
```
2. Replace
```java
import java.util.HashSet;
import java.util.List;
import java.util.Set;
```
with
```java
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
```
3. Replace
```java
    @AfterEach
    void clear() {
        MansartDataLive.clear();
    }
```
with
```java
    @AfterEach
    void clear() {
        panel.stop();
        MansartDataLive.clear();
    }
```
4. Replace the end of the file,
```java
        for (String key : keys) {
            assertDoesNotThrow(() -> PanelSample.requireKey(key + ".inherits"), key);
            assertTrue(key.length() <= 31, key);
        }
    }
}
```
with
```java
        for (String key : keys) {
            assertDoesNotThrow(() -> PanelSample.requireKey(key + ".inherits"), key);
            assertTrue(key.length() <= 31, key);
        }
    }

    @Test
    void aKeyFitsTheRoomItIsGiven() {
        Set<String> used = new HashSet<>();

        assertEquals("count-by-stock-greate", CatalogueLivePanel.key("countByStockGreaterThan", used, 21));
        assertEquals("count-by-stock-grea-2", CatalogueLivePanel.key("countByStockGreaterThan", used, 21));
    }

    @Test
    void withoutAStartThereIsNoAction() {
        publishRunFixtures();

        assertEquals(List.of(), panel.actions());
    }

    @Test
    void startKeepsTheBeanManagerTheActionsAreBuiltFrom() {
        publishRunFixtures();
        panel.start(new FakeContext(emptyBeanManager()));

        List<PanelAction> actions = panel.actions();

        assertEquals(23, actions.size());
        PanelAction save = actions.stream().filter(a -> a.id().equals("m.gizmo-repository.save")).findFirst()
                .orElseThrow();
        assertEquals(List.of("commit"), save.arguments().get(1).allowedValues(), "no TransactionManager bean");
        assertEquals("no bean for GizmoRepository", save.call()
                .apply(Map.of("arguments", "{\"entity\":{}}", "transaction", "commit")).summary());
    }

    @Test
    void theMonitoringTabAlsoListsWhatCannotRunAndTheCalls() {
        publishRunFixtures();
        panel.actions(type -> {
            throw new BeanLookup.NoBean();
        }, TransactionRunner.NONE, RunFixtures::model, method -> true);

        RecordedSample sample = sample();

        assertEquals(List.of("not-runnable", "calls"), sample.keys());
        assertEquals(List.of("Gizmo", "Part", "Other repositories"), sample.groupNames(), "the catalogue as before");
    }

    @Test
    void stopDropsTheActionsAndTheirCalls() {
        publishRunFixtures();
        panel.start(new FakeContext(emptyBeanManager()));
        panel.actions();

        panel.stop();

        assertEquals(List.of(), panel.actions());
        assertEquals(List.of(), sample().keys());
    }

    private static void publishRunFixtures() {
        MansartDataLive.publish(RunFixtures.catalogue());
        MansartDataLive.publishRepositories(RunFixtures.REPOSITORIES);
    }

    /** A bean manager with no bean at all: no repository bean, no TransactionManager. */
    private static BeanManager emptyBeanManager() {
        return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                new Class<?>[] {BeanManager.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getBeans")) {
                        return Set.of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    /** What {@code start} reads: the bean manager only. */
    private record FakeContext(BeanManager beanManager) implements ExtensionContext {

        @Override
        public VaubanContainer container() {
            throw new UnsupportedOperationException("start reads the bean manager only");
        }

        @Override
        public VidocqConfiguration configuration() {
            throw new UnsupportedOperationException("start reads the bean manager only");
        }

        @Override
        public VidocqConfig config() {
            throw new UnsupportedOperationException("start reads the bean manager only");
        }
    }
}
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='CatalogueLivePanelTest' > "$SCRATCH/t8.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t8.log" | tail -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (the package-private `actions(...)`).

- [ ] **Step 3: `CatalogueLivePanel.java`**

Replace everything after the license header with:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Column;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue.Repository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import jakarta.enterprise.inject.spi.BeanManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The Mansart Data section, live. Its <i>Monitoring</i> tab is the catalogue the runtime extension built at boot,
 * from {@link MansartDataLive}: one group per entity with its table, its columns and its repositories, then a group
 * for the repositories with no primary entity. {@link #sample} reads that holder and the calls kept in memory: no
 * bean, no connection, no query.
 *
 * <p>Its actions run the repositories' methods, one tab per repository (see {@link RepositoryActions}): built once
 * per boot by {@link #actions()}, which the console calls after {@link #start}, from the repository interfaces
 * {@link MansartDataLive} holds and the {@link BeanManager} {@code start} keeps; dropped by {@link #stop}.
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

    private static final System.Logger LOG = System.getLogger(CatalogueLivePanel.class.getName());

    /** The bean manager of this boot, {@code null} before {@link #start} and after {@link #stop}. */
    private volatile BeanManager beans;
    /** The actions of this boot, {@link RepositoryActions#NONE} until {@link #actions()} builds them. */
    private volatile RepositoryActions run = RepositoryActions.NONE;

    /** Created by the service loader. */
    public CatalogueLivePanel() {}

    @Override
    public String id() {
        return "mansart-data";
    }

    /** Keeps the bean manager the actions resolve the repositories and the transaction manager with. */
    @Override
    public void start(ExtensionContext context) {
        run = RepositoryActions.NONE;
        try {
            beans = context.beanManager();
        } catch (RuntimeException | LinkageError unavailable) {
            beans = null;
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: no bean manager, no action: "
                    + unavailable.getClass().getName());
        }
    }

    /** Drops the bean manager, the actions and their history: nothing of this boot outlives a dev reload. */
    @Override
    public void stop() {
        beans = null;
        run = RepositoryActions.NONE;
    }

    /** The run actions of this boot, built now; none before {@link #start}, or without a catalogue. */
    @Override
    public List<PanelAction> actions() {
        BeanManager manager = beans;
        if (manager == null) {
            return List.of();
        }
        return actions(BeanLookup.of(manager), TransactionRunner.of(manager), type -> EntityModels.of(type),
                RepositoryActions::accessible);
    }

    /** Builds the actions from what {@link MansartDataLive} holds, and keeps them for {@link #sample}. */
    List<PanelAction> actions(BeanLookup lookup, TransactionRunner transactions,
                              Function<Class<?>, EntityModel<?>> models, Predicate<Method> accessible) {
        Optional<MansartDataCatalogue> catalogue = MansartDataLive.catalogue();
        List<Class<?>> repositories = MansartDataLive.repositories();
        if (catalogue.isEmpty() || repositories.isEmpty()) {
            run = RepositoryActions.NONE;
            return List.of();
        }
        try {
            RepositoryActions built = RepositoryActions.build(repositories, catalogue.get(), lookup, transactions,
                    models, accessible, RepositoryActions.MAX_ACTIONS);
            run = built;
            return built.actions();
        } catch (RuntimeException | LinkageError failed) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: the run actions could not be built: "
                    + failed.getClass().getName());
            run = RepositoryActions.NONE;
            return List.of();
        }
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
        run.sample(sample);
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
            // the key says "inherits" already: the value keeps what follows it
            group.text(key + ".inherits", repository.inherits().replaceFirst("^inherits ", ""));
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
        return key(name, used, MAX_BASE);
    }

    /**
     * {@code name} in kebab case as {@link #key(String, Set)} makes it, at most {@code max} characters, the suffix of a
     * clash included: the run actions build their ids {@code m.<repository>.<method>} with it. Adds it to {@code used}.
     */
    static String key(String name, Set<String> used, int max) {
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
        base = trim(base, max);
        String key = base;
        for (int n = 2; !used.add(key); n++) {
            String suffix = "-" + n;
            key = trim(base, max - suffix.length()) + suffix;
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
(The body of `key` is the existing one with `MAX_BASE` replaced by `max`, as Task 7 Step 1 left it; compare with
`git diff` that nothing else of the catalogue part changed.)

- [ ] **Step 4: Run the module's tests and install it**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/t8b.log" 2>&1; grep -E "Tests run:|FAIL|BUILD|checkpom" "$SCRATCH/t8b.log" | tail -8
```
Expected: every test passes, `BUILD SUCCESS` (the existing checkpom warning of this dev-only module is expected).

- [ ] **Step 5: Commit**

Message:
```
feat(mansart-data): the panel runs the repositories' methods

CatalogueLivePanel keeps the bean manager in start, builds the run actions
once per boot in actions() from the repository interfaces and the
catalogue MansartDataLive holds, samples what cannot run and the calls,
and drops it all in stop so that a dev reload starts afresh (spec §2).

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
D=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev
git add $D/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanel.java $D/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/CatalogueLivePanelTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 9: Against a database — the mansart-h2 example

**Files:**
- Modify: `EX/src/main/java/io/vidocq/runtime/examples/mansart/ProductRepository.java`
- Modify: `EX/pom.xml`
- Modify: `EX/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java`

**Interfaces:**
- Consumes: DEV installed (Task 8), EXT installed (Task 2). The console's HTTP action API
  (`POST /api/action/<panel>/<action>`, body `{"arguments":"<JSON text>","transaction":"rollback"}`, answer
  `{"result","contentType","body","details","error"?}`).
- Produces: `ProductRepository.reprice(String name, double price)` (JDQL `UPDATE`); a test that runs `findById`, a
  `save` rolled back (no row), a `save` committed (one row, then deleted), and a JDQL `UPDATE` rolled back.

- [ ] **Step 1: The JDQL `UPDATE`**

In `ProductRepository.java`, replace:
```java
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Repository;
```
with:
```java
import jakarta.data.repository.BasicRepository;
import jakarta.data.repository.Param;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
```
and replace:
```java
    List<Product> findByNameLike(String pattern);
}
```
with:
```java
    List<Product> findByNameLike(String pattern);

    /**
     * Sets the price of the products of a name, in one {@code UPDATE}; DevConsoleSnapshotTest runs it from the dev
     * console's Mansart Data panel, in a transaction rolled back.
     *
     * @return how many products changed
     */
    @Query("UPDATE Product SET price = :price WHERE name = :name")
    long reprice(@Param("name") String name, @Param("price") double price);
}
```

- [ ] **Step 2: The panel on the test path**

In `EX/pom.xml`, replace:
```xml
            <artifactId>vidocq-runtime-migration-extension-dev</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
```
with:
```xml
            <artifactId>vidocq-runtime-migration-extension-dev</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
        <!-- DevConsoleSnapshotTest runs ProductRepository's methods from the console
             (runsProductRepositoryMethodsFromTheConsole): the Mansart Data panel's actions live in this -dev
             companion, needed at test scope for the same reason as the panels above. -->
        <dependency>
            <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
            <artifactId>vidocq-runtime-mansart-data-extension-dev</artifactId>
            <version>${project.version}</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: The test**

In `DevConsoleSnapshotTest.java`, replace
`import static org.junit.jupiter.api.Assertions.assertNotNull;` with:
```java
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
```
Then insert, immediately before the lines
```java
    /**
     * Sends the console an action request as its own page does: JSON, its own {@code Origin}, the token of the boot.
```
this block:
```java
    /**
     * Runs ProductRepository's methods from the Mansart Data panel against the in-memory H2 database, as a developer
     * does from its page: a read, a save rolled back that leaves no row, a save committed that leaves one (then
     * deleted, so that the other tests see the seeded rows), and a JDQL UPDATE rolled back.
     */
    @Test
    void runsProductRepositoryMethodsFromTheConsole() throws Exception {
        List<?> actions = (List<?>) panel("mansart-data").get("actions");
        Map<?, ?> findById = actions.stream().map(a -> (Map<?, ?>) a)
                .filter(a -> "m.product-repository.find-by-id".equals(a.get("id"))).findFirst()
                .orElseGet(() -> fail("no findById action in " + actions));
        assertEquals("ProductRepository", findById.get("group"));
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        Map<?, ?> espresso = runProducts(token, "find-by-id", "{\"id\":1}", null);
        assertEquals("1 row", espresso.get("result"));
        assertEquals("Espresso", json((String) espresso.get("body")).get("name"));

        long before = countProducts(token);
        assertEquals("1 row · rolled back", runProducts(token, "save",
                "{\"entity\":{\"name\":\"Mocha\",\"price\":4.5}}", "rollback").get("result"));
        assertEquals(before, countProducts(token), "a save rolled back leaves no row");

        Map<?, ?> saved = runProducts(token, "save", "{\"entity\":{\"name\":\"Mocha\",\"price\":4.5}}", "commit");
        assertEquals("1 row · committed", saved.get("result"));
        assertEquals(before + 1, countProducts(token), "a save committed leaves one");
        long id = ((Number) json((String) saved.get("body")).get("id")).longValue();
        assertEquals("done · committed",
                runProducts(token, "delete-by-id", "{\"id\":" + id + "}", "commit").get("result"));
        assertEquals(before, countProducts(token));

        assertEquals("1 · rolled back", runProducts(token, "reprice",
                "{\"name\":\"Espresso\",\"price\":9.99}", "rollback").get("result"));
        assertEquals(2.5, ((Number) json((String) runProducts(token, "find-by-id", "{\"id\":1}", null)
                .get("body")).get("price")).doubleValue(), "the UPDATE was rolled back");
    }

    /** Runs {@code m.product-repository.<method>} with these arguments; its answer, which must be no error. */
    private static Map<?, ?> runProducts(String token, String method, String arguments, String transaction)
            throws Exception {
        String body = "{\"arguments\":\"" + arguments.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (transaction == null ? "" : ",\"transaction\":\"" + transaction + "\"") + "}";
        Map<?, ?> answer = json(postAction("mansart-data/m.product-repository." + method, token, body));
        assertNotEquals(Boolean.TRUE, answer.get("error"), method + ": " + answer);
        return answer;
    }

    /** {@code ProductRepository.count()}, from the console. */
    private static long countProducts(String token) throws Exception {
        return Long.parseLong((String) runProducts(token, "count", "{}", null).get("result"));
    }

    private static Map<?, ?> json(String text) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return (Map<?, ?>) jsonb.fromJson(text, Object.class);
        }
    }

```

- [ ] **Step 4: Run it**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example > "$SCRATCH/t9.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/t9.log" | tail -8
```
Expected: `BUILD SUCCESS`, `DevConsoleSnapshotTest` and `TransactionalRollbackTest` pass. On a failure, read the
surefire report first (superpowers:systematic-debugging). Likely causes, in order: an action id (read the
snapshot's `actions` of `mansart-data`); a `400` for `transaction` (only `commit` offered: the DEV module does not
read `jakarta.transaction` in this layer — check with a DEBUG log of `TransactionRunner.of`); a rolled-back write
still counted (the connection not enlisted: `vidocq.pool.xa=true` is set, so check the transaction manager is the
one the `@Transactional` repository joins).

- [ ] **Step 5: Commit**

Message:
```
test(mansart-data): run repository methods against H2 from the console

The mansart-h2 example's dev-launch test now runs ProductRepository from
the Mansart Data panel over the console's action API: findById, a save
rolled back that leaves no row, a save committed that leaves one (then
deleted), and a JDQL UPDATE, reprice, rolled back (spec §9).

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
X=vidocq-runtime-examples/vidocq-runtime-mansart-h2-example
git add $X/pom.xml $X/src/main/java/io/vidocq/runtime/examples/mansart/ProductRepository.java $X/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 10: Documentation (spec §10)

**Files:**
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc`
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc`

**Interfaces:**
- Produces: the anchor `mansart-data-run-method`.

- [ ] **Step 1: The section**

In `vidocq-runtime-extensions.adoc`, insert immediately before the line `[#mansart-transactions-jdbc-bridge]`:

````
[#mansart-data-run-method]
== Running a repository method from the dev console [.tag-new]#NEW#

Under `mvn vidocq:dev`, the *Mansart Data* tab of the xref:dev-console.adoc[dev console] also runs the application's repository methods against its database: a query to see what it returns, a write to try it, then roll it back or keep it. The tab keeps the catalogue (<<mansart-data-catalogue>>) under *Monitoring*, then shows one tab per repository, titled with its name, whose list offers its methods by name: every method it declares, plus the `findById`, `findAll`, `save`, `deleteById` and `delete` it inherits from Jakarta Data.

*Arguments.* A method's arguments are one JSON object, one property per parameter, named as the catalogue names it (`@Param`, else the real name when the application is compiled with `-parameters`, else `arg0`, `arg1`…), all required. The page shows a form, or a JSON editor when a parameter is an entity.

[cols="2,3"]
|===
| Java type | JSON

| `String`, `char`, `Character`
| A string; one character for a `char`.

| `int`, `long`, `short`, `byte`, their boxes, `BigInteger`
| An integer; a fraction, or a value out of the type's range, is refused.

| `double`, `float`, their boxes, `BigDecimal`
| A number; a `BigDecimal` keeps the digits as written.

| `boolean`, `Boolean`
| `true` or `false`.

| An `enum`
| The name of one of its constants.

| `LocalDate`; `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime`; `LocalTime`
| ISO text: `2026-09-28`; `2026-09-28T10:15:30Z`; `10:15`.

| `UUID`
| Its text.

| An entity of a repository
| An object, one property per column, each optional: an absent one keeps what the entity's no-arg constructor sets, so that an absent generated `id` is generated. A reference to another entity takes that entity's id. The entity is built through Mansart's own model, as Mansart builds it from a row.
|===

A boxed type takes `null`, a primitive does not. A value that does not convert fails the call before anything runs, with the parameter and why: `dueDate: not an ISO date`. A method with any other parameter type, such as `PageRequest`, `Sort`, `Limit` or a `List`, is listed under *Monitoring* in `not-runnable`, with the reason, and is not offered.

*Writes.* `save`, `saveAll`, a method whose name starts with `insert`, `update` or `delete`, one annotated `@Insert`, `@Update`, `@Delete` or `@Save`, and a `@Query` that starts with `UPDATE` or `DELETE` are writes. The page asks before running one (`Runs TaskRepository.save against the database.`), and its *Transaction* list offers `rollback`, the default, then `commit`. The console runs the call in a transaction of the application's `jakarta.transaction.TransactionManager`, then rolls it back or commits it; an exception from the method always rolls it back. A rollback undoes only what was done on a connection enlisted in that transaction, which `vidocq-runtime-mansart-transactions-extension` sets up. An application without a `TransactionManager` bean gets `commit` only, and the call then runs as it would outside a transaction. Reads run as they are.

*Results.* The answer is JSON, shown by the page's JSON viewer: an entity as an object, one property per column (a referenced entity as its id, a joined attribute left out), a `List`, `Collection`, `Stream` or array as an array of at most 100 elements, an `Optional` as its value or `null`. The line under the form says what came back and, for a write, what became of the transaction: `3 rows in 12 ms`, `first 100 rows in 40 ms`, `1 row · rolled back`, `42 · committed`, `done`. *Exchange* holds the method, each argument with its type and value, and the transaction asked. A method that throws shows the class of its exception and its message, cut after 500 characters, with a `user:password@` in it masked.

*History.* Each repository tab lists its last 20 calls of the boot, newest first, each with *Replay*, which fills the form again; nothing is sent until you run it. A dev reload starts a new history, and builds the tabs again from the new boot.

*Limits.* The console keeps 128 actions per panel: past them, *Monitoring* says `and N more methods`. A call longer than the console's 60 seconds keeps running, and its outcome shows on a later poll. The repository's package must be open to Vidocq — a Vidocq application is an `open module` by convention, or `opens` the package; otherwise its methods are listed as `package <p> not open to Vidocq`.

````

- [ ] **Step 2: What's new**

In `whats-new.adoc`, insert immediately before the line starting with
`* **A Mansart Data catalogue in the startup report and the dev console** [.tag-new]#NEW# —` this line:
```
* **Run a Mansart Data repository method from the dev console** [.tag-new]#NEW# — under `mvn vidocq:dev`, the *Mansart Data* tab gets one tab per repository, which runs any of its methods against the application's database: the arguments as JSON, converted to the method's types, an entity built through Mansart's own model; the result as JSON, with the last 20 calls and a replay. A write asks first and runs in a transaction rolled back by default, or committed. xref:modules/vidocq-runtime-extensions.adoc#mansart-data-run-method[Running a repository method from the dev console].
```

- [ ] **Step 3: Check the anchors**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq/docs/en/modules/ROOT/pages
grep -c "mansart-data-run-method" modules/vidocq-runtime-extensions.adoc whats-new.adoc
grep -c "tag-new" modules/vidocq-runtime-extensions.adoc
```
Expected: `vidocq-runtime-extensions.adoc:1`, `whats-new.adoc:1`; the `tag-new` count one higher than before
(`git diff --stat` shows the two files only).

- [ ] **Step 4: Commit**

Message:
```
docs(mansart-data): running a repository method from the dev console

A new section of the extensions page, marked NEW: the repository tabs,
the arguments and their JSON types, writes with rollback or commit, the
results and the history, the limits and the open package; What's new
gets its entry.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
D=docs/en/modules/ROOT/pages
git add $D/modules/vidocq-runtime-extensions.adoc $D/whats-new.adoc
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 11: Verification — build, consumers, and the panel in Chrome

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, commit with a
`fix(mansart-data): ...` message following the Global Constraints).

**Interfaces:**
- Consumes: everything above; the script
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh`
  (app on 18093, console on 18094).

- [ ] **Step 1: Build and install the touched modules**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/install.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/install.log" | tail -8
```
Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 2: Build the consumers**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example,vidocq-runtime-examples/vidocq-runtime-petstore-example > "$SCRATCH/consumers.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/consumers.log" | tail -8
```
Expected: `BUILD SUCCESS` for both.

- [ ] **Step 3: Free ports**

```bash
lsof -nP -iTCP:18093 -sTCP:LISTEN; lsof -nP -iTCP:18094 -sTCP:LISTEN; echo checked
```
Expected: only `checked`. If a port is taken, STOP and ask the user (never kill what this plan did not start).

- [ ] **Step 4: Note the test app's state, start `vidocq:dev`**

```bash
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short > "$SCRATCH/lc4jcdi-status-before.txt"; cat "$SCRATCH/lc4jcdi-status-before.txt"
```
Then run with the Bash tool and `run_in_background: true` (the only background Maven run of this plan):
```bash
bash /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log
```
Wait with the Monitor tool on an until-loop (never a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" "$SCRATCH/mcp-tasks-dev.log"; do sleep 2; done`.
Then: `grep -E "Dev tools:|Mansart Data:|MANSART-DATA" "$SCRATCH/mcp-tasks-dev.log" | head` — expected
`vidocq-runtime-mansart-data-extension-dev` among the dev tools, two repositories wired, no `MANSART-DATA-001`.

- [ ] **Step 5: The panel in Chrome (spec §9)**

Load the tools in one call:
`ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__get_page_text,mcp__claude-in-chrome__find,mcp__claude-in-chrome__form_input")`.

1. `tabs_context_mcp`, `tabs_create_mcp`, `navigate` to `http://127.0.0.1:18094/`.
2. Before relying on polling, run with `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
3. Open *Mansart Data*. Check the sub-tabs: *Monitoring* (the catalogue as before, plus `not-runnable` with
   `findAll(PageRequest, Order)` for each repository), then `TaskEventRepository` and `TaskRepository`. Screenshot.
4. `TaskRepository` tab, `findByStatusOrderByDueDateAsc`: the form's `status` is a list `OPEN`, `DONE`; pick `OPEN`,
   run: `N rows in X ms`, the JSON viewer shows tasks with `status` `OPEN`, dates as ISO text.
5. `searchText`, `pattern` `%release%`: at least one row (the seeded "Publish the Vidocq 0.4.0 release notes").
6. `save` (JSON editor): `{"entity":{"title":"Console check","project":"vidocq","status":"OPEN","priority":"LOW","createdAt":"2026-09-28T10:00:00Z","updatedAt":"2026-09-28T10:00:00Z"}}`,
   *Transaction* `rollback`; the page asks `Runs TaskRepository.save against the database.`; confirm: `1 row ·
   rolled back`, a generated `id` in the body. Then `searchText` `%console check%`: `no row in X ms`.
7. `findByProjectOrderByIdAsc` `project` `lc4jcdi`: note N rows. `renameProject` with
   `{"oldName":"lc4jcdi","newName":"lc4jcdi-console","now":"2026-09-28T10:00:00Z"}`, `commit`: `N · committed`;
   `findByProjectOrderByIdAsc` `lc4jcdi-console`: N rows.
8. An error call: `save` `{"entity":{"project":"vidocq"}}`, `rollback`: an error line with the exception class and
   the database's message (the `title` NOT NULL constraint), no password. And `findById` `{"id":"x"}`:
   `id: not an integer`.
9. The history of the `TaskRepository` tab lists these calls newest first; click *Replay* on the `searchText` row:
   the form is filled with `%release%` and nothing is sent. Screenshot.
10. **Undo the committed write**: `renameProject`
    `{"oldName":"lc4jcdi-console","newName":"lc4jcdi","now":"2026-09-28T10:00:00Z"}`, `commit`: `N · committed`;
    `findByProjectOrderByIdAsc` `lc4jcdi`: N rows again. Note for the report: those N tasks keep
    `updatedAt` = `2026-09-28T10:00:00Z` (the test app's file database, `target/h2/tasks`).
11. `tabs_close_mcp` on the tab this step opened.

If the *Transaction* list offers `commit` only, the DEV module does not read `jakarta.transaction` in the dev layer:
STOP the check (never commit a `save` there) and debug (superpowers:systematic-debugging).

- [ ] **Step 6: Stop what this plan started**

Stop the background task of Step 4 with `TaskStop` (its id), then re-run the command of Step 3: both ports free.
```bash
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short | diff - "$SCRATCH/lc4jcdi-status-before.txt" && echo "test app untouched"
```
Expected: `test app untouched`.

- [ ] **Step 7: Final state**

```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short
git -C /Users/yblazart/projects/perso/vidocq/vidocq log --oneline -10
git -C /Users/yblazart/projects/perso/vidocq/mansart branch --show-current
git -C /Users/yblazart/projects/perso/vidocq/mansart-of-javadoc log --oneline -1
```
Expected: the Vidocq tree clean (the plan's commits on `feat/mansart-data-run-method`), Mansart's checkout still on
`ybl/opencode-3` untouched, the worktree's branch holding the javadoc commit. Nothing pushed. Report to the
controller: the two branches to push, the screenshots, and the test app's DB note of Step 5.10.

---

## Self-review (done while writing)

- **Spec coverage.** §1 declared + five inherited (Task 5), types (Task 3), entity for `save` (Task 4), confirmation
  and rollback/commit (Tasks 6-7), JSON results and last 20 with replay (Tasks 4, 7); out of scope types not runnable
  (Tasks 5, 7). §2 EXT holder (Task 2), `start` + `actions()` (Task 8), tabs/groups (Task 7), ids, overloads, 128
  (Task 7). §3 bean at the call, `Method.invoke`, open package, write rules, transaction argument, TM or not
  (Tasks 5-7). §4 schema, required, conversions, errors before any call (Tasks 3, 5, 7). §5 handles (Tasks 1, 4).
  §6 values, shapes, summaries, details (Tasks 4, 7). §7 errors, no bean, reload 404 (console's own; Task 8 drops the
  actions in `stop`). §8 history (Task 7). §9 unit tests (Tasks 3-8), database (Task 9), Chrome (Task 11), Mansart
  javadoc only (Task 1). §10 docs (Tasks 1, 10).
- **Types.** `CatalogueLivePanel.key(String, Set<String>, int)`, `RepositoryActions.build(..., int maxActions)`,
  `TransactionRunner.modes()`, `Signature.parameters()`, `CallHistory.add(String, long, String, String, long,
  String, String)` are used with the same signatures everywhere.
