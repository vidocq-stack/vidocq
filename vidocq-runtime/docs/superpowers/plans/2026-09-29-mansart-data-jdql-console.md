# A JDQL console for Mansart Data in the dev console — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **This plan is executed natively (one executor, inline): use superpowers:executing-plans.** Apply the fenced code blocks of a step in order; a block that starts with `package` is a whole file (prepend the license header of the Global Constraints); an edit is shown as the exact text to find, then the text that replaces it.

**Goal:** In a `dev` launch, let a developer type a JDQL statement in the *Mansart Data* panel's *JDQL* tab and run it
against the application's database — a query to look at data, an `UPDATE` or a `DELETE` rolled back (the default) or
committed — with the result as JSON and the last 20 statements replayable.

**Architecture:**
- **Mansart** (`mansart-data-core`, in a visible worktree): `JdqlExecutor.execute` stops taking a
  `java.lang.reflect.Method`; what it took from it becomes a package-private `CallShape` record (return type,
  projection element, parameter-name → index map, a `convert` flag). The `@Query` path builds it from the `Method`
  (`CallShape.of`, `convert=false`: behaviour unchanged); the new public `JdqlExecutor.run(jdql, parameters, model,
  runtime)` builds it from the parsed statement (`convert=true`) and wraps the result in the new sealed
  `JdqlResult` (`Entities`, `Rows`, `Count`, `Value`). `JdqlValues` converts a value to the compared attribute's
  type. `isWrite` and `target` read a statement lexically.
- **Vidocq page** (`vidocq-runtime-devconsole-extension`): a flat-schema form renders a string property with
  `"format": "textarea"` as a `<textarea>`.
- **Vidocq `-dev` module:** `JdqlActions` (the two actions `jdql.query` and `jdql.write`, group `JDQL`), behind a
  `JdqlRunner` seam (`JdqlRunner.MANSART` calls `JdqlExecutor.run`; unit tests use a recording fake, no database).
  `RepositoryActions.build` gains an overload that appends the JDQL tab and shares its `CallHistory`;
  `CatalogueLivePanel` passes `JdqlRunner.MANSART`. Results reuse `ResultJson`/`EntityJson`, errors `Failures`,
  transactions `TransactionRunner`.
- **Database test:** the mansart-h2 example's `DevConsoleSnapshotTest` posts the two actions.

**Tech Stack:** Java 25, JPMS, Maven 3.9, Jakarta Data 1.0.1, Mansart Data 0.4.0-SNAPSHOT (`mansart-data-core`, H2
dialect), Jakarta CDI 4.1, Jakarta Transactions 2.0.1 (optional), the Vidocq dev console SPI and page (vanilla JS),
JUnit 5, AssertJ (Mansart tests).

**Spec:** `docs/superpowers/specs/2026-09-29-mansart-data-jdql-console-design.md` (binding). Section numbers below
refer to it. Read it first, then this plan's *Rulings*. Context: sub-project 2
(`docs/superpowers/specs/2026-09-28-mansart-data-run-method-design.md`,
`docs/superpowers/plans/2026-09-28-mansart-data-run-method.md`), merged.

## Global Constraints

- **Repositories and branches.**
  - Vidocq: `/Users/yblazart/projects/perso/vidocq/vidocq`, branch `feat/mansart-data-jdql-console` (checked out;
    never switch it).
  - Mansart: `/Users/yblazart/projects/perso/vidocq/mansart` is the user's checkout, on `ybl/opencode-3` with
    uncommitted work. **Never** stash, reset, checkout, switch, add or commit anything there. Tasks 1-2 work only in
    the visible worktree `/Users/yblazart/projects/perso/vidocq/mansart-jdql-run`, branch `feat/jdql-run` from
    `origin/main` (the user approved it). Every Mansart command below runs in that worktree.
  - Test app (read only): `/Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server`. Never
    touch its uncommitted `.run/*.xml` files (in the parent repo `lc4jcdi-on-vidocq`). Its H2 database is a file
    (`target/h2/tasks`): the manual check rolls its `UPDATE` back and **never commits** anything.
- **Maven.** Every command starts with
  `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;` and uses
  `mvn -nsu` (never `./mvnw` or `mvnw`). If a hook redirects a Maven call to the context-mode `ctx_execute` shell,
  run it there and print only the tail/summary. Give long builds the Bash tool's `timeout: 600000`. Maven is never
  put in the background, except Task 8's `vidocq:dev`, through the existing script.
- **Paths** (all checked while planning):
  - `V` = `/Users/yblazart/projects/perso/vidocq/vidocq`; `W` = `/Users/yblazart/projects/perso/vidocq/mansart-jdql-run`
  - `CORE` = `W/mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core`
  - `MT` = `W/mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests`
  - `DEV` = `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev`
    (sources `DEV/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/`, tests in the same
    package under `DEV/src/test/java/...`)
  - `CON` = `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension`
    (page `CON/src/main/resources/META-INF/resources/devconsole/`, `PageTest` in
    `CON/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/`)
  - `EX` = `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example`, `PET` = `vidocq-runtime-examples/vidocq-runtime-petstore-example`
  - docs: `V/docs/en/modules/ROOT/pages/` (`modules/vidocq-runtime-extensions.adoc`, `dev-console-panels.adoc`,
    `whats-new.adoc`)
  - The commands below spell these out in full.
- **Scratch directory** for logs and the Mansart commit messages:
  `SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad`
  (set it in each shell: `export SCRATCH=...`).
- **Ports:** only 18090-18099, checked free with `lsof -nP -iTCP:<port> -sTCP:LISTEN` first; never 8080 or 8888.
  Never kill a process this plan did not start.
- **Commits.** Write the message with the Write tool — Vidocq: `V/.git/PLAN_COMMIT_MSG`; the Mansart worktree:
  `$SCRATCH/MANSART_COMMIT_MSG` — then `git commit -S -F <that file> && rm <that file>` (never `-m`, never `-s`).
  Stage explicit paths only (never `git add -A` / `git add .`). Every message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  **Never push.** The controller pushes and opens the pull requests.
- **Code style.** English; 120 columns; Javadoc density like the surrounding files. Every **new** `.java` file, in
  Vidocq and in Mansart alike, starts with this license header, verbatim — it is Vidocq's, and Mansart's own
  (`EntityModels.java` carries the same text). The code blocks of this plan start at `package` and omit it: prepend
  it.

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
- **Dependencies.** None new, in Mansart or in Vidocq. The `-dev` module reads `io.vidocq.mansart.data.core` through
  the Mansart Data extension's `requires transitive`; its `module-info.java` does not change.
- **Mansart behaviour.** The `@Query` path (`RuntimeRepositoryProxy`, `executeJdql`) behaves as before (spec §2):
  every test of `mansart-jakarta-data` that passed before Task 1 passes after Tasks 1 and 2.

## Rulings on the spec

1. **`params` is a string property** (`"format": "textarea"`) holding a JSON object as text, not a nested object:
   an `object` property would make the schema not flat (`isFlatSchema`), and §4's textarea would never render (§3
   and §4 contradict each other). The server also accepts `params` as a JSON object (the raw JSON editor, a replay,
   the example test). A member whose value is an object, or a list holding an object or a list, is refused
   (`params.<name>: not a value or a list of values`); a list feeds `IN :names`.
2. **`transaction` is a separate string argument** (`rollback`, `commit`; `commit` only without a
   `TransactionManager`), as in sub-project 2 — a `PanelAction` has at most one json argument. Absent, it is the
   runner's first mode (`rollback` when there is a manager). The json argument is named `statement` (label
   *Statement*).
3. **Numbers.** Besides the spec's `String` conversions, a `Number` compared to a numeric attribute is converted
   exactly to its type (the console's JSON reader gives `BigDecimal`s): `2.5` for an `Integer` fails. A value of any
   other type is passed as it is.
4. **Messages** (all `MansartDataException`): `:status: not a TaskStatus` (the type's simple name, `not a Instant`
   included, as §2 writes it); a literal is labelled with its text in quotes, `'LOST': not a ShipmentStatus`;
   `:min: no value given`; `:extra: not used by the statement` (the first unused name in alphabetical order);
   `positional parameters are not supported here; use :name`; a statement that does not parse is a
   `MansartDataException` carrying the parser's message (its cause the `JdqlAst.ParseException`).
5. **The entity's name.** `run` accepts the model's simple name, as the parser always did, and its full class name
   (the catalogue shows a full name when two entities share a simple name). Vidocq looks a name up among the
   catalogue's entities by simple class name, then by full class name; two entities with that simple name are
   refused (`entity Gizmo is ambiguous: x.Gizmo, y.Gizmo; use its full name`); a statement naming none
   (`WHERE x = 1`) is refused (`no entity: name it, FROM <Entity>, UPDATE <Entity> or DELETE FROM <Entity>`); the
   unknown-entity list is the catalogue's names in alphabetical order.
6. **Aggregates.** `MIN`/`MAX` read the attribute's type; `SUM` a `Long` over an integral attribute, a `Double` over
   `float`/`double`, else the attribute's type; `AVG` a `Double`, a `BigDecimal` over a `BigDecimal`.
7. **Summaries.** A write: `no row`, `1 row` or `N rows`, then ` · rolled back` / ` · committed`. A count query: its
   number. A `Value`: its JSON text (`null` when there is no row). `Entities`/`Rows`: as sub-project 2's lists,
   with ` in N ms`. An error: `<exception class>: <message>` masked and cut (sub-project 2's `Failures.text`, message
   cut at 500), which is also the `text/plain` body (the "full text" of §3 read as that text).
8. **The tab.** Present when the catalogue holds at least one entity. Its two actions come after the repositories'
   and count against the panel's 128: the repositories get 126. Its title is `JDQL`, or `JDQL 2` if a repository's
   tab already has that title. A projection selecting a column twice keeps the last value under that key.
9. **History.** One table `calls` per panel (a sample key is written once): the JDQL calls go into the repositories'
   `CallHistory` under the group `JDQL` (20 kept), `method` *Query* or *Update / Delete*, replay
   `jdql.query {"statement":{…}}` or `jdql.write {"statement":{…},"transaction":"rollback"}`; the page puts them in
   the JDQL tab by their action id.
10. **`CallShape.of(Method)`** reads the projection element when the `@Query` dispatcher is built rather than at
    each call; it only differs for a return type whose generic signature cannot be read, which already fails.
11. **Mansart test entity** `Shipment` lives in `mansart-data-tests/src/test/java`, without `@Entity` (no metamodel
    generated, the model built at run time), with an explicit `@Table`/`@Column`s; its table is created by SQL.

## Review Focus

- **A parameter given as a JSON number** (`{"min": 3}` → `BigDecimal`) against an `Integer`, `Long`, `double` or
  `BigDecimal` attribute: converted exactly; `2.5` for an `Integer` is refused with the parameter's name. →
  `JdqlRunTest.numbersAreConvertedExactly`, `aValueThatDoesNotConvertIsRefusedNamingIt` (Task 2);
  `DevConsoleSnapshotTest.runsJdqlFromTheConsole` (Task 6).
- **A string literal or a parameter that reads like a keyword** (`'FROM Elsewhere'`, `:from`): the entity found is
  still the statement's. → `JdqlRunTest.targetSkipsStringLiteralsAndParameters`, `datesAndInstantsAreReadAsIsoText`
  (Task 2).
- **`IN :names` given a JSON array of enum names, or an empty one:** each element converted; an empty list selects
  no row without failing. → `JdqlRunTest.aListParameterFeedsAnInElementByElement` (Task 2);
  `JdqlActionsTest.aListParameterIsPassedAsAList` (Task 4).
- **Two entities with one simple name** (the catalogue then shows full names): the simple name is refused as
  ambiguous, the full name runs. → `JdqlRunTest.theEntityMayBeNamedByItsFullClassName` (Task 2),
  `JdqlActionsTest.anAmbiguousSimpleNameIsRefusedAndAFullNameRuns` (Task 4).
- **A query over a big table:** the whole result is read (Mansart reads fully), the page gets the first 100 rows and
  says so. → `JdqlActionsTest.aQueryOfManyRowsShowsTheFirstHundred`, `ResultJsonTest.aTableIsOneObjectPerRow…`
  (Task 4).

---

## File Structure

| File | Responsibility |
|---|---|
| `CORE/JdqlExecutor.java` (modify) | `CallShape`; `execute(Stmt, CallShape, …)`; conversion plumbing; `run`, `isWrite`, `target` |
| `CORE/JdqlValues.java` (new) | a value converted to an attribute's Java type |
| `CORE/JdqlResult.java` (new) | sealed result of `run`: `Entities`, `Rows`, `Count`, `Value` |
| `CORE/RuntimeRepositoryProxy.java` (modify) | the `@Query` dispatcher passes `CallShape.of(m)` |
| `MT/Shipment.java`, `MT/ShipmentStatus.java`, `MT/JdqlRunTest.java` (new) | `run` on H2 |
| `CON/.../console.js`, `console.css` (modify), `PageTest.java` (modify) | `format: textarea` |
| `DEV/ResultJson.java` (modify) | `count(long)`, `table(columns, rows, entities)`, `node(value, entities)` |
| `DEV/JdqlRunner.java` (new) | the seam: runs a statement; `MANSART` = `JdqlExecutor.run` |
| `DEV/JdqlActions.java` (new) | the JDQL tab: two actions, refusals, lookup, run, JSON, history |
| `DEV/RepositoryActions.java` (modify) | `build(…, JdqlRunner)` overload, `load(className, repositories)` |
| `DEV/CatalogueLivePanel.java` (modify) | passes `JdqlRunner.MANSART` |
| DEV tests | `JdqlActionsTest` (new), `ResultJsonTest`, `CatalogueLivePanelTest` (modify) |
| `EX/.../DevConsoleSnapshotTest.java` (modify) | JDQL against H2 |
| docs (modify) | `vidocq-runtime-extensions.adoc` `[#mansart-data-jdql]`, `dev-console-panels.adoc`, `whats-new.adoc` |

---

### Task 1: Mansart — the worktree, a green baseline, and `execute` without a `Method` (spec §2, "The existing path")

**Files:**
- Modify: `CORE/JdqlExecutor.java` (whole content after the license header)
- Create: `CORE/JdqlValues.java`
- Modify: `CORE/RuntimeRepositoryProxy.java` (the `@Query` dispatcher, 3 lines)
- Test: the existing tests of `mansart-jakarta-data` (non-regression)

**Interfaces:**
- Consumes: nothing.
- Produces (package `io.vidocq.mansart.data.core`, package-private unless said):
  - `record JdqlExecutor.CallShape(Class<?> returnType, Class<?> element, Map<String, Integer> names, boolean convert)`
    with `static CallShape of(Method method)` (`convert=false`);
  - `static Object JdqlExecutor.execute(JdqlAst.Stmt stmt, CallShape shape, EntityModel<?> model,
    Map<String, Attribute<?, ?>> attrIndex, RepositoryRuntime runtime, Object[] args)`;
  - `static Class<?> JdqlExecutor.boxed(Class<?>)`, `static Attribute<?, ?> JdqlExecutor.lookup(EntityModel<?>,
    Map<String, Attribute<?, ?>>, String)` (private, used by Task 2's code inside the class);
  - `static Object JdqlValues.convert(Object value, Class<?> type, String label)`;
  - the line `    /** Executes {@code stmt} as a call of {@code shape} makes it, with the call's {@code args}. */`, the
    anchor Task 2 inserts before;
  - unchanged: `public static Object executeJdql(String, Method, EntityModel<?>, RepositoryRuntime, Object[])`,
    `public static Object dispatchMultiProjection(Class<?>, Class<?>, List<Object[]>, List<Attribute<?, ?>>)`,
    `static Map<String, Integer> nameToIndexFor(Method)`.

- [ ] **Step 1: Record the user's checkout, fetch, create the worktree**

```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 > "$SCRATCH/mansart-status-before.txt"
git -C /Users/yblazart/projects/perso/vidocq/mansart branch --show-current
git -C /Users/yblazart/projects/perso/vidocq/mansart fetch origin
git -C /Users/yblazart/projects/perso/vidocq/mansart worktree add -b feat/jdql-run ~/projects/perso/vidocq/mansart-jdql-run origin/main
git -C /Users/yblazart/projects/perso/vidocq/mansart-jdql-run log --oneline -1
```
Expected: `ybl/opencode-3`; `Preparing worktree (new branch 'feat/jdql-run')`. If the branch or the directory already
exists, STOP and ask the user.

- [ ] **Step 2: The baseline — build, test and install `mansart-jakarta-data` as it is on `origin/main`**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run && mvn -nsu -f mansart-jakarta-data/pom.xml install > "$SCRATCH/t1-baseline.log" 2>&1; grep -E "Tests run:.*Fail|Reactor Summary|SUCCESS|FAILURE|BUILD" "$SCRATCH/t1-baseline.log" | grep -v "Tests run:.*Time elapsed" | tail -20
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`; note the `Tests run: N, Failures: 0, Errors: 0, Skipped: S` total of
`mansart-data-tests` (the line after its `Results:`) for Step 6. If the baseline fails, STOP and report (the failure
is not this plan's).

- [ ] **Step 3: `JdqlValues`**

Create `CORE/JdqlValues.java`:
```java
package io.vidocq.mansart.data.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The values of a statement {@code JdqlExecutor.run} runs, converted to the Java type of the attribute they are
 * compared to or assigned: a statement given as text brings its values as text — a literal {@code '2026-09-29'}, a
 * parameter read from a form — and its numbers as whatever a JSON reader made of them.
 *
 * <ul>
 *   <li>A value of the attribute's type is kept, and so is {@code null}.</li>
 *   <li>A {@code String}, for an attribute that is not text: an enum by constant name, the {@code java.time} types by
 *       ISO parsing, the numeric types exactly ({@code BigDecimal} and {@code BigInteger} included), a
 *       {@code Boolean} from {@code true} or {@code false}, a {@code UUID} by {@link UUID#fromString}. Another type
 *       keeps the text, for the database to read.</li>
 *   <li>A {@code Number}, for a numeric attribute: exactly, so that {@code 2.5} is no {@code Integer}.</li>
 *   <li>A collection, the value of an {@code IN :names}: element by element.</li>
 * </ul>
 * A value that does not convert throws {@code MansartDataException("<label>: not a <Type>")} before anything runs.
 * The {@code @Query} path never converts: its values are typed by the method's parameters.
 */
final class JdqlValues {

    /** The numeric types, boxed, that a number or a text converts to exactly. */
    private static final Set<Class<?>> NUMBERS = Set.of(Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class, BigDecimal.class, BigInteger.class);

    private JdqlValues() {}

    /**
     * {@code value} as a value of {@code type}.
     *
     * @param value the value, as given
     * @param type  the Java type of the attribute it is compared to or assigned, a primitive included
     * @param label what an error names: {@code :status} for a parameter, {@code 'LOST'} for a literal
     * @throws MansartDataException when it does not convert
     */
    static Object convert(Object value, Class<?> type, String label) {
        if (value == null || type == null) {
            return value;
        }
        Class<?> target = boxed(type);
        if (value instanceof Collection<?> values) {
            List<Object> out = new ArrayList<>(values.size());
            for (Object element : values) {
                out.add(convert(element, target, label));
            }
            return out;
        }
        if (target.isInstance(value)) {
            return value;
        }
        try {
            if (value instanceof String text) {
                return fromText(text, target);
            }
            if (value instanceof Number number && NUMBERS.contains(target)) {
                return fromNumber(new BigDecimal(number.toString()), target);
            }
        } catch (RuntimeException notConverted) {
            throw new MansartDataException(label + ": not a " + target.getSimpleName(), notConverted);
        }
        return value;
    }

    private static Object fromText(String text, Class<?> target) {
        if (target == String.class || target == Character.class) {
            return text;
        }
        if (target.isEnum()) {
            return constant(target, text);
        }
        if (target == LocalDate.class) {
            return LocalDate.parse(text);
        }
        if (target == LocalDateTime.class) {
            return LocalDateTime.parse(text);
        }
        if (target == LocalTime.class) {
            return LocalTime.parse(text);
        }
        if (target == OffsetDateTime.class) {
            return OffsetDateTime.parse(text);
        }
        if (target == ZonedDateTime.class) {
            return ZonedDateTime.parse(text);
        }
        if (target == Instant.class) {
            return Instant.parse(text);
        }
        if (target == Boolean.class) {
            if (text.equals("true")) {
                return Boolean.TRUE;
            }
            if (text.equals("false")) {
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("neither true nor false");
        }
        if (target == UUID.class) {
            return UUID.fromString(text);
        }
        if (NUMBERS.contains(target)) {
            return fromNumber(new BigDecimal(text.strip()), target);
        }
        return text;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object constant(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static Object fromNumber(BigDecimal number, Class<?> target) {
        if (target == Integer.class) {
            return number.intValueExact();
        }
        if (target == Long.class) {
            return number.longValueExact();
        }
        if (target == Short.class) {
            return number.shortValueExact();
        }
        if (target == Byte.class) {
            return number.byteValueExact();
        }
        if (target == BigInteger.class) {
            return number.toBigIntegerExact();
        }
        if (target == Double.class) {
            return number.doubleValue();
        }
        if (target == Float.class) {
            return number.floatValue();
        }
        return number;
    }

    private static Class<?> boxed(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == char.class)    return Character.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == float.class)   return Float.class;
        if (c == double.class)  return Double.class;
        return c;
    }
}
```

- [ ] **Step 4: `JdqlExecutor` takes a `CallShape`**

Replace the whole content of `CORE/JdqlExecutor.java` after its license header (from `package` to the end) with:
```java
package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.OrderBy;
import io.vidocq.mansart.data.dialect.Where;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a parsed {@link JdqlAst.Stmt} and executes it against a {@link RepositoryRuntime}, used
 * by {@link RuntimeRepositoryProxy} when a {@code @Query}-annotated method is invoked.
 *
 * <p>Mirror of the compile-time JdqlParser code emitter: same lowering of predicates to
 * {@link Where}, same Order resolution, same dispatch by return type, same dynamic In(Collection)
 * with empty short-circuit. Aggregate / projection / UPDATE / DELETE statements are honoured.
 *
 * <p>What an execution takes from its call — the return type the result is dispatched to, the element type of a
 * projection, the index of each named parameter — is a {@link CallShape}: a {@code @Query} method's, or one built
 * from the statement itself when a tool runs a statement given as text.
 */
// M8-2 — promoted from package-private to public so generated repository impls in user packages
// can call the shared dispatchMultiProjection helper. The other static methods remain
// package-private (visibility narrowed where possible).
public final class JdqlExecutor {

    private JdqlExecutor() {}

    /**
     * What executing a statement takes from its call.
     *
     * @param returnType the type the result is dispatched to: a {@code @Query} method's return type
     * @param element    the element type of a projection: the return type's type argument or array component
     * @param names      the index, in the call's arguments, of each named parameter
     * @param convert    whether a value compared to an attribute, or assigned to one, is converted to the attribute's
     *                   type ({@link JdqlValues}); never for a {@code @Query} method, whose parameters type its values
     */
    record CallShape(Class<?> returnType, Class<?> element, Map<String, Integer> names, boolean convert) {

        /** The shape of a {@code @Query} method: its return type, its projection element, its parameter names. */
        static CallShape of(Method method) {
            return new CallShape(method.getReturnType(), projectionElement(method), nameToIndexFor(method), false);
        }
    }

    /**
     * M8-1f — public entry point used by compile-time generated repository impls when the JDQL
     * grammar requires features not yet emitted as static Java by {@link io.vidocq.mansart.data.processor.JdqlParser}
     * (currently: UPDATE with arithmetic/scalar-function SET RHS). Re-parses the JDQL string at
     * each call — acceptable cost since the alternative is a few hundred LOC of static emitter
     * code duplicating the runtime AST walker. Caching of the parsed {@link JdqlAst.Stmt} per
     * (jdql, entity) pair is a future optimisation.
     *
     * <p>The compile-time emitter passes everything the executor needs to wire up: the raw JDQL
     * source, the call-site {@code Method} (for return-type dispatch and {@code @Param}/parameter-name
     * resolution), and the entity model.
     */
    public static Object executeJdql(String jdql, Method method, EntityModel<?> model,
                                     RepositoryRuntime runtime, Object[] args) {
        Map<String, Attribute<?, ?>> attrIndex = new HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);
        java.util.Set<String> attrNames = attrIndex.keySet();
        JdqlAst.Stmt stmt = JdqlAst.parse(jdql, attrNames, model.entityClass().getSimpleName());
        return execute(stmt, CallShape.of(method), model, attrIndex, runtime, args);
    }

    /** Executes {@code stmt} as a call of {@code shape} makes it, with the call's {@code args}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object execute(JdqlAst.Stmt stmt, CallShape shape, EntityModel<?> model,
                          Map<String, Attribute<?, ?>> attrIndex,
                          RepositoryRuntime runtime, Object[] args) {
        OrderBy orderBy = buildOrderBy(stmt.orderBy, model, attrIndex);
        // M7-21 — fold any Sort/Order control args into the OrderBy. The query's own ORDER BY
        // (from JDQL) takes precedence (declared first); runtime Sort args extend it.
        orderBy = appendRuntimeSorts(orderBy, args, attrIndex);

        return switch (stmt.kind) {
            case SELECT, COUNT -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                if (stmt.kind == JdqlAst.Stmt.Kind.COUNT) {
                    yield bw.where == Where.ALWAYS_FALSE ? 0L
                            : runtime.countWhere((EntityModel) model, bw.where, bw.args);
                }
                Class<?> rt = shape.returnType();
                boolean optional = rt == java.util.Optional.class;
                boolean stream   = rt == java.util.stream.Stream.class;
                boolean list     = java.util.List.class.isAssignableFrom(rt)
                                 || java.util.Collection.class == rt
                                 || Iterable.class == rt;
                boolean array    = rt.isArray();
                boolean longRet  = rt == long.class || rt == Long.class;
                boolean boolRet  = rt == boolean.class || rt == Boolean.class;
                boolean pageRet  = jakarta.data.page.Page.class.isAssignableFrom(rt);
                boolean cursorRet = jakarta.data.page.CursoredPage.class.isAssignableFrom(rt);
                if (bw.where == Where.ALWAYS_FALSE) {
                    if (optional) yield java.util.Optional.empty();
                    if (stream)   yield java.util.stream.Stream.empty();
                    if (list)     yield java.util.List.of();
                    if (longRet)  yield 0L;
                    if (boolRet)  yield false;
                    throw new MansartDataException("@Query empty IN with single-entity return");
                }
                if (longRet)  yield runtime.countWhere((EntityModel) model, bw.where, bw.args);
                if (boolRet)  yield runtime.existsWhere((EntityModel) model, bw.where, bw.args);
                if (cursorRet || pageRet) {
                    jakarta.data.page.PageRequest pr = findPageRequest(args);
                    if (pr == null) {
                        throw new MansartDataException("@Query returning Page/CursoredPage requires a PageRequest argument");
                    }
                    if (cursorRet) yield runtime.queryCursored((EntityModel) model, bw.where, orderBy, pr, bw.args);
                    yield runtime.queryPage((EntityModel) model, bw.where, orderBy, pr, bw.args);
                }
                if (optional) yield runtime.queryOne((EntityModel) model, bw.where, bw.args);
                List<Object> data = runtime.queryList((EntityModel) model, bw.where, orderBy, bw.args);
                jakarta.data.Limit lim2 = findLimit(args);
                if (lim2 != null) {
                    int from = Math.max(0, (int) (lim2.startAt() - 1));
                    int to = Math.min(data.size(), from + (int) lim2.maxResults());
                    data = (from >= data.size()) ? new ArrayList<>()
                                                  : new ArrayList<>(data.subList(from, to));
                }
                if (stream) yield data.stream();
                if (list)   yield data;
                if (array)  {
                    Object arr = java.lang.reflect.Array.newInstance(rt.getComponentType(), data.size());
                    for (int i = 0; i < data.size(); i++) java.lang.reflect.Array.set(arr, i, data.get(i));
                    yield arr;
                }
                java.util.Optional<?> one = runtime.queryOne((EntityModel) model, bw.where, bw.args);
                if (one.isEmpty()) {
                    throw new jakarta.data.exceptions.EmptyResultException(
                            "@Query returned no result for " + model.entityClass().getSimpleName());
                }
                yield one.get();
            }
            case AGGREGATE -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                Attribute<?, ?> attr = lookup(model, attrIndex, stmt.scalarAttr);
                Class<?> rt = shape.returnType();
                Class<?> boxed = boxed(rt);
                Object v = runtime.aggregate((EntityModel) model, stmt.aggregateOp, attr, boxed, bw.where, bw.args);
                if (v == null && rt.isPrimitive()) yield zeroFor(rt);
                yield v;
            }
            case PROJECT_MULTI -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                java.util.List<Attribute<?, ?>> attrs = new ArrayList<>(stmt.projectAttrs.size());
                for (String name : stmt.projectAttrs) {
                    attrs.add(lookup(model, attrIndex, name));
                }
                Class<?> rt = shape.returnType();
                List<Object[]> rows = runtime.projectColumns((EntityModel) model, attrs,
                        bw.where, orderBy, bw.args);
                jakarta.data.Limit lim = findLimit(args);
                if (lim != null) {
                    int from = Math.max(0, (int) (lim.startAt() - 1));
                    int to = Math.min(rows.size(), from + (int) lim.maxResults());
                    rows = (from >= rows.size()) ? java.util.List.of()
                                                  : new ArrayList<>(rows.subList(from, to));
                }
                yield dispatchMultiProjection(rt, shape.element(), rows, attrs);
            }
            case PROJECT -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                Attribute<?, ?> attr = lookup(model, attrIndex, stmt.scalarAttr);
                Class<?> rt = shape.returnType();
                Class<?> elem = shape.element();
                List<Object> col = runtime.projectColumn((EntityModel) model, attr,
                        (Class) elem, bw.where, orderBy, bw.args);
                // Apply a Limit argument (Jakarta Data control parameter) by slicing.
                jakarta.data.Limit lim = findLimit(args);
                if (lim != null) {
                    int from = Math.max(0, (int) (lim.startAt() - 1));
                    int to = Math.min(col.size(), from + (int) lim.maxResults());
                    col = (from >= col.size()) ? java.util.List.of()
                                               : new ArrayList<>(col.subList(from, to));
                }
                if (jakarta.data.page.Page.class.isAssignableFrom(rt)) {
                    jakarta.data.page.PageRequest pr2 = findPageRequest(args);
                    if (pr2 == null) {
                        throw new MansartDataException("@Query projection returning Page requires a PageRequest argument");
                    }
                    int pageSize = pr2.size();
                    int offset = (int) ((pr2.page() - 1) * pageSize);
                    int from = Math.min(offset, col.size());
                    int to   = Math.min(offset + pageSize, col.size());
                    java.util.List<Object> pageContent = new ArrayList<>(col.subList(from, to));
                    yield new MansartPage<>(pageContent, pr2, col.size());
                }
                if (java.util.List.class.isAssignableFrom(rt)
                        || java.util.Collection.class == rt
                        || Iterable.class == rt) yield col;
                if (rt == java.util.stream.Stream.class) yield col.stream();
                if (rt == java.util.Optional.class) yield col.isEmpty()
                        ? java.util.Optional.empty()
                        : java.util.Optional.ofNullable(col.get(0));
                if (rt.isArray()) {
                    Object arr = java.lang.reflect.Array.newInstance(rt.getComponentType(), col.size());
                    for (int i = 0; i < col.size(); i++) java.lang.reflect.Array.set(arr, i, col.get(i));
                    yield arr;
                }
                if (col.isEmpty()) {
                    throw new jakarta.data.exceptions.EmptyResultException(
                            "Projection of " + stmt.scalarAttr + " returned no result");
                }
                yield col.get(0);
            }
            case UPDATE -> {
                StringBuilder setSql = new StringBuilder();
                List<Object> setVals = new ArrayList<>();
                List<Class<?>> setTypes = new ArrayList<>();
                boolean firstSet = true;
                for (JdqlAst.SetAssign sa : stmt.setAssignments) {
                    // SET LHS doesn't allow path expressions (cannot UPDATE through a join). Stay
                    // on the flat attrIndex and reject paths explicitly.
                    if (sa.attr().indexOf('.') >= 0) {
                        throw new MansartDataException("UPDATE SET LHS cannot be a path expression: " + sa.attr());
                    }
                    Attribute<?, ?> a = attrIndex.get(sa.attr());
                    if (a == null) throw new MansartDataException("Unknown attribute: " + sa.attr());
                    if (!firstSet) setSql.append(", ");
                    setSql.append('"').append(a.columnName()).append("\" = ");
                    renderExpr(setSql, sa.value(), attrIndex, args, shape, setVals, setTypes, a.javaType());
                    firstSet = false;
                }
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                long n = runtime.executeUpdateRaw((EntityModel) model, setSql.toString(),
                        setVals, setTypes, bw.where, bw.args);
                yield wrapLongResult(shape.returnType(), n);
            }
            case DELETE -> {
                BuiltWhere bw = buildWhere(stmt.where, model, attrIndex, args, shape);
                long n = runtime.deleteWhere((EntityModel) model, bw.where, bw.args);
                yield wrapLongResult(shape.returnType(), n);
            }
        };
    }

    private static Object wrapLongResult(Class<?> rt, long n) {
        if (rt == void.class) return null;
        if (rt == boolean.class || rt == Boolean.class) return n > 0;
        if (rt == int.class || rt == Integer.class) return (int) n;
        return n;
    }

    private static OrderBy buildOrderBy(List<JdqlAst.Order> orders, EntityModel<?> model,
                                        Map<String, Attribute<?, ?>> attrIndex) {
        if (orders == null || orders.isEmpty()) return OrderBy.NONE;
        List<OrderBy.Order> out = new ArrayList<>(orders.size());
        for (JdqlAst.Order o : orders) {
            Attribute<?, ?> a = lookup(model, attrIndex, o.attr());
            out.add(o.asc() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
        }
        return new OrderBy(out);
    }

    private record BuiltWhere(Where where, Object[] args) {}

    private static BuiltWhere buildWhere(JdqlAst.Pred p, EntityModel<?> model,
                                         Map<String, Attribute<?, ?>> attrIndex,
                                         Object[] callArgs, CallShape shape) {
        if (p == null) return new BuiltWhere(Where.ALWAYS_TRUE, new Object[0]);
        List<Object> argsOut = new ArrayList<>();
        boolean[] alwaysFalse = { false };
        Where w = build(p, model, attrIndex, callArgs, shape, argsOut, alwaysFalse);
        if (alwaysFalse[0]) return new BuiltWhere(Where.ALWAYS_FALSE, new Object[0]);
        return new BuiltWhere(w, argsOut.toArray());
    }

    private static Where build(JdqlAst.Pred p, EntityModel<?> model,
                               Map<String, Attribute<?, ?>> attrIndex,
                               Object[] callArgs, CallShape shape,
                               List<Object> argsOut, boolean[] alwaysFalse) {
        Attribute<?, ?> a;
        switch (p) {
            case JdqlAst.Cmp c -> {
                a = lookup(model, attrIndex, c.attr());
                argsOut.add(resolveArg(c.arg(), callArgs, shape, a.javaType()));
                return cmpOf(a, c.op());
            }
            case JdqlAst.FnCmp c -> {
                a = lookup(model, attrIndex, c.attr());
                argsOut.add(resolveArg(c.arg(), callArgs, shape, comparedType(c.fn(), a)));
                return new Where.Func(c.fn(), cmpOf(a, c.op()));
            }
            case JdqlAst.IsNull n -> {
                a = lookup(model, attrIndex, n.attr());
                return n.negated() ? new Where.IsNotNull(a) : new Where.IsNull(a);
            }
            case JdqlAst.FnIsNull n -> {
                a = lookup(model, attrIndex, n.attr());
                return new Where.Func(n.fn(), n.negated() ? new Where.IsNotNull(a) : new Where.IsNull(a));
            }
            case JdqlAst.Between b -> {
                a = lookup(model, attrIndex, b.attr());
                argsOut.add(resolveArg(b.lo(), callArgs, shape, a.javaType()));
                argsOut.add(resolveArg(b.hi(), callArgs, shape, a.javaType()));
                return new Where.Between(a);
            }
            case JdqlAst.FnBetween b -> {
                a = lookup(model, attrIndex, b.attr());
                argsOut.add(resolveArg(b.lo(), callArgs, shape, comparedType(b.fn(), a)));
                argsOut.add(resolveArg(b.hi(), callArgs, shape, comparedType(b.fn(), a)));
                return new Where.Func(b.fn(), new Where.Between(a));
            }
            case JdqlAst.In in -> {
                a = lookup(model, attrIndex, in.attr());
                return buildIn(a, in.args(), in.collection(), callArgs, shape, argsOut, alwaysFalse, null);
            }
            case JdqlAst.FnIn in -> {
                a = lookup(model, attrIndex, in.attr());
                return buildIn(a, in.args(), in.collection(), callArgs, shape, argsOut, alwaysFalse, in.fn());
            }
            case JdqlAst.And and -> {
                List<Where> cs = new ArrayList<>(and.children().size());
                for (JdqlAst.Pred c : and.children()) cs.add(build(c, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
                return new Where.And(cs);
            }
            case JdqlAst.Or or -> {
                List<Where> cs = new ArrayList<>(or.children().size());
                for (JdqlAst.Pred c : or.children()) cs.add(build(c, model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
                return new Where.Or(cs);
            }
            case JdqlAst.Not n -> {
                return new Where.Not(build(n.child(), model, attrIndex, callArgs, shape, argsOut, alwaysFalse));
            }
        }
    }

    /**
     * The type a value compared to {@code fn(attr)} is bound as, as {@link WhereBinder} binds it: an {@code Integer}
     * for {@code LENGTH}, which counts characters, else the attribute's own.
     */
    private static Class<?> comparedType(String fn, Attribute<?, ?> a) {
        return "LENGTH".equals(fn) ? Integer.class : a.javaType();
    }

    private static Where cmpOf(Attribute<?, ?> a, JdqlAst.Op op) {
        return switch (op) {
            case EQ -> new Where.Eq(a);
            case NE -> new Where.NotEq(a);
            case LT -> new Where.Lt(a);
            case LTE -> new Where.Lte(a);
            case GT -> new Where.Gt(a);
            case GTE -> new Where.Gte(a);
            case LIKE -> new Where.Like(a);
        };
    }

    private static Where buildIn(Attribute<?, ?> a, List<JdqlAst.ArgRef> argRefs, boolean collection,
                                 Object[] callArgs, CallShape shape,
                                 List<Object> argsOut, boolean[] alwaysFalse, String wrapFn) {
        Class<?> target = wrapFn == null ? a.javaType() : comparedType(wrapFn, a);
        Where inner;
        if (collection) {
            Object v = resolveArg(argRefs.get(0), callArgs, shape, target);
            if (v instanceof Collection<?> col) {
                if (col.isEmpty()) {
                    alwaysFalse[0] = true;
                    return Where.ALWAYS_FALSE;
                }
                for (Object e : col) argsOut.add(e);
                inner = new Where.In(a, col.size());
            } else {
                argsOut.add(v);
                inner = new Where.In(a, 1);
            }
        } else {
            for (JdqlAst.ArgRef r : argRefs) argsOut.add(resolveArg(r, callArgs, shape, target));
            inner = new Where.In(a, argRefs.size());
        }
        return wrapFn == null ? inner : new Where.Func(wrapFn, inner);
    }

    /**
     * M8-3 — flat name fast path for {@code attr}; dotted names ({@code book.author.name}) are
     * resolved via {@link PathResolver} which walks target metamodels and produces a
     * {@link io.vidocq.mansart.data.dialect.attribute.JoinedAttribute}.
     */
    private static Attribute<?, ?> lookup(EntityModel<?> model, Map<String, Attribute<?, ?>> idx, String name) {
        if (name.indexOf('.') < 0) {
            Attribute<?, ?> a = idx.get(name);
            if (a == null) throw new MansartDataException("Unknown attribute: " + name);
            return a;
        }
        return PathResolver.resolve(model, name);
    }

    /**
     * The value {@code ref} stands for in this call: a literal lifted by {@link #resolveLiteral}, or the argument of
     * a named or positional parameter; converted to {@code target}, the type of the attribute it meets, when the
     * shape says so ({@link JdqlValues}), as is otherwise.
     */
    private static Object resolveArg(JdqlAst.ArgRef ref, Object[] args, CallShape shape, Class<?> target) {
        if (ref.isLiteral) {
            Object value = resolveLiteral(ref.literal);
            return shape.convert() ? JdqlValues.convert(value, target, literalLabel(ref.literal)) : value;
        }
        Object value;
        if (ref.isNamed()) {
            Integer i = shape.names().get(ref.named);
            if (i == null) throw new MansartDataException("@Query references :" + ref.named
                    + " but the method has no parameter with that name (compile with -parameters or use ?N)");
            value = args[i];
        } else {
            value = args[ref.positional - 1];
        }
        return shape.convert()
                ? JdqlValues.convert(value, target, ref.isNamed() ? ":" + ref.named : "?" + ref.positional)
                : value;
    }

    /** How an error names a literal: a text in quotes, {@code 'LOST'}, anything else as it reads. */
    private static String literalLabel(Object literal) {
        return literal instanceof String text ? "'" + text + "'" : String.valueOf(literal);
    }

    /**
     * Lift a JDQL literal token to a runtime value. Strings whose form is
     * {@code pkg.Class.MEMBER} (≥ 2 dots, last segment uppercase) are loaded as enum constants;
     * strings without dots are returned verbatim; numbers/booleans pass through.
     */
    private static Object resolveLiteral(Object lit) {
        if (lit instanceof String s && s.indexOf('.') > 0) {
            int last = s.lastIndexOf('.');
            String member = s.substring(last + 1);
            if (!member.isEmpty() && Character.isUpperCase(member.charAt(0))) {
                String enumFqn = s.substring(0, last);
                try {
                    Class<?> raw = loadClassForEnum(enumFqn);
                    if (raw != null && raw.isEnum()) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        Enum<?> v = Enum.valueOf((Class) raw, member);
                        return v;
                    }
                } catch (Exception ignored) { /* fall through — treat as plain string */ }
            }
        }
        return lit;
    }

    private static Class<?> loadClassForEnum(String fqn) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = JdqlExecutor.class.getClassLoader();
        // Try as-is, then progressively replace inner-class dot separators with '$'
        try { return Class.forName(fqn, false, cl); } catch (ClassNotFoundException ignored) {}
        StringBuilder sb = new StringBuilder(fqn);
        for (int i = sb.length() - 1; i > 0; i--) {
            if (sb.charAt(i) != '.') continue;
            sb.setCharAt(i, '$');
            try { return Class.forName(sb.toString(), false, cl); } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    /* ---- helpers ---- */

    /**
     * M7-24 — render a JDQL value expression (supports + - * /, attribute references, and arg
     * references) into a SQL fragment. Bindings flow into {@code outVals} / {@code outTypes}
     * in the order the placeholders appear, paired with {@code targetType} for the dialect to
     * coerce against (the SET column's Java type).
     */
    @SuppressWarnings("unchecked")
    private static void renderExpr(StringBuilder sb, JdqlAst.Expr expr,
                                   Map<String, Attribute<?, ?>> attrIndex,
                                   Object[] callArgs, CallShape shape,
                                   List<Object> outVals, List<Class<?>> outTypes,
                                   Class<?> targetType) {
        switch (expr) {
            case JdqlAst.ExprAttr a -> {
                Attribute<?, ?> attr = attrIndex.get(a.attr());
                if (attr == null) throw new MansartDataException("Unknown attribute in SET: " + a.attr());
                sb.append('"').append(attr.columnName()).append('"');
            }
            case JdqlAst.ExprArg ar -> {
                outVals.add(resolveArg(ar.arg(), callArgs, shape, targetType));
                outTypes.add(targetType);
                sb.append('?');
            }
            case JdqlAst.ExprBin bin -> {
                sb.append('(');
                renderExpr(sb, bin.left(), attrIndex, callArgs, shape, outVals, outTypes, targetType);
                sb.append(' ').append(bin.op()).append(' ');
                renderExpr(sb, bin.right(), attrIndex, callArgs, shape, outVals, outTypes, targetType);
                sb.append(')');
            }
            // M8-1 — render scalar functions in SET RHS. LENGTH → CHAR_LENGTH (SQL-portable).
            // CONCAT renders as fn(arg1, arg2, ...) per SQL standard (works on H2 and PG).
            // Bound parameters in fn args inherit the SET column's targetType — sufficient for
            // the common case where all CONCAT pieces are strings.
            case JdqlAst.ExprFunc fn -> {
                String sqlFn = "LENGTH".equals(fn.name()) ? "CHAR_LENGTH" : fn.name();
                Class<?> innerType = "LENGTH".equals(fn.name()) ? Integer.class : targetType;
                sb.append(sqlFn).append('(');
                List<JdqlAst.Expr> args = fn.args();
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    renderExpr(sb, args.get(i), attrIndex, callArgs, shape, outVals, outTypes, innerType);
                }
                sb.append(')');
            }
        }
    }

    private static jakarta.data.page.PageRequest findPageRequest(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof jakarta.data.page.PageRequest pr) return pr;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OrderBy appendRuntimeSorts(OrderBy base, Object[] args,
                                              Map<String, Attribute<?, ?>> attrIndex) {
        if (args == null) return base;
        java.util.List<OrderBy.Order> out = new ArrayList<>(base.orders());
        for (Object a : args) {
            if (a instanceof jakarta.data.Sort<?> s) {
                addSort(s, attrIndex, out);
            } else if (a instanceof jakarta.data.Sort[] arr) {
                for (jakarta.data.Sort<?> s : arr) addSort(s, attrIndex, out);
            } else if (a instanceof jakarta.data.Order<?> o) {
                for (jakarta.data.Sort<?> s : o.sorts()) addSort(s, attrIndex, out);
            }
        }
        return out.isEmpty() ? OrderBy.NONE : new OrderBy(out);
    }

    private static void addSort(jakarta.data.Sort<?> s, Map<String, Attribute<?, ?>> attrIndex,
                                java.util.List<OrderBy.Order> out) {
        Attribute<?, ?> a = attrIndex.get(s.property());
        if (a == null) {
            throw new MansartDataException("Sort references unknown attribute: " + s.property());
        }
        out.add(s.isAscending() ? OrderBy.Order.asc(a) : OrderBy.Order.desc(a));
    }

    private static jakarta.data.Limit findLimit(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof jakarta.data.Limit l) return l;
        }
        return null;
    }

    /**
     * Maps named parameters to method-arg indices. Resolution order:
     * <ol>
     *   <li>{@code @jakarta.data.repository.Param("name")} on the parameter (authoritative).</li>
     *   <li>{@code Parameter.getName()} when {@code -parameters} is honoured by javac.</li>
     * </ol>
     * Both are read so deployments compiled without {@code -parameters} (e.g. the official
     * Jakarta Data TCK jar built with maven-compiler-plugin 4.0.0-beta-4 — see BUG-20260505-01,
     * M7-9) still resolve {@code @Query} {@code :name} bindings via {@code @Param}.
     */
    static Map<String, Integer> nameToIndexFor(Method m) {
        Map<String, Integer> map = new HashMap<>();
        java.lang.reflect.Parameter[] params = m.getParameters();
        for (int i = 0; i < params.length; i++) {
            for (var ann : params[i].getDeclaredAnnotations()) {
                if (ann.annotationType().getName().equals("jakarta.data.repository.Param")) {
                    try {
                        var v = ann.annotationType().getMethod("value").invoke(ann);
                        if (v instanceof String s && !s.isEmpty()) {
                            map.put(s, i);
                        }
                    } catch (ReflectiveOperationException ignored) { /* fall through */ }
                }
            }
            // Also register the reflective name (arg0/arg1 when -parameters absent — harmless,
            // a well-named @Param always wins because it's set first).
            String n = params[i].getName();
            map.putIfAbsent(n, i);
        }
        return map;
    }

    private static Class<?> boxed(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class)    return Byte.class;
        if (c == short.class)   return Short.class;
        if (c == int.class)     return Integer.class;
        if (c == long.class)    return Long.class;
        if (c == float.class)   return Float.class;
        if (c == double.class)  return Double.class;
        return c;
    }

    private static Object zeroFor(Class<?> c) {
        if (c == long.class)   return 0L;
        if (c == double.class) return 0.0;
        if (c == float.class)  return 0.0f;
        return 0;
    }

    /**
     * M8-2 — dispatch the result of a multi-column projection (one {@code Object[]} per row)
     * to the requested return type:
     * <ul>
     *   <li>{@code List<Object[]>}/{@code Collection}/{@code Iterable} → the rows directly</li>
     *   <li>{@code Stream<Object[]>} → {@code rows.stream()}</li>
     *   <li>{@code Object[][]} → 2-D array</li>
     *   <li>{@code Optional<Object[]>} → first row or empty</li>
     *   <li>Record types ({@code List<R>}, {@code Stream<R>}, {@code R[]}, {@code Optional<R>}, {@code R})
     *       — each row is mapped to a canonical record constructor whose component count matches
     *       the projected attribute count. Components are passed in JDQL declaration order.</li>
     * </ul>
     */
    public static Object dispatchMultiProjection(Class<?> rt, Class<?> elem, List<Object[]> rows,
                                                  java.util.List<Attribute<?, ?>> attrs) {
        boolean isRecordTarget = elem != null && elem != Object[].class && elem.isRecord();

        // Object[]-shaped returns
        if (java.util.List.class.isAssignableFrom(rt) || java.util.Collection.class == rt || Iterable.class == rt) {
            if (!isRecordTarget) return rows;
            return mapRowsToRecords(rows, elem, attrs);
        }
        if (rt == java.util.stream.Stream.class) {
            return isRecordTarget ? mapRowsToRecords(rows, elem, attrs).stream() : rows.stream();
        }
        if (rt.isArray()) {
            Class<?> comp = rt.getComponentType();
            if (comp == Object[].class) {
                Object[][] arr = new Object[rows.size()][];
                for (int i = 0; i < rows.size(); i++) arr[i] = rows.get(i);
                return arr;
            }
            if (comp.isRecord()) {
                java.util.List<Object> recs = mapRowsToRecords(rows, comp, attrs);
                Object arr = java.lang.reflect.Array.newInstance(comp, recs.size());
                for (int i = 0; i < recs.size(); i++) java.lang.reflect.Array.set(arr, i, recs.get(i));
                return arr;
            }
            throw new MansartDataException("Multi-projection array return must be Object[][] or RecordType[], got: " + rt);
        }
        if (rt == java.util.Optional.class) {
            if (rows.isEmpty()) return java.util.Optional.empty();
            return isRecordTarget ? java.util.Optional.of(mapRowToRecord(rows.get(0), elem, attrs))
                                  : java.util.Optional.of(rows.get(0));
        }
        if (rt.isRecord()) {
            if (rows.isEmpty()) {
                throw new jakarta.data.exceptions.EmptyResultException(
                        "Multi-projection returned no result for " + rt.getSimpleName());
            }
            return mapRowToRecord(rows.get(0), rt, attrs);
        }
        throw new MansartDataException("Unsupported multi-projection return type: " + rt);
    }

    private static java.util.List<Object> mapRowsToRecords(List<Object[]> rows, Class<?> recordType,
                                                            java.util.List<Attribute<?, ?>> attrs) {
        java.util.List<Object> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) out.add(mapRowToRecord(row, recordType, attrs));
        return out;
    }

    private static Object mapRowToRecord(Object[] row, Class<?> recordType,
                                         java.util.List<Attribute<?, ?>> attrs) {
        java.lang.reflect.RecordComponent[] comps = recordType.getRecordComponents();
        if (comps.length != attrs.size()) {
            throw new MansartDataException("Record " + recordType.getSimpleName()
                    + " has " + comps.length + " components but projection selected " + attrs.size() + " attributes");
        }
        Class<?>[] paramTypes = new Class<?>[comps.length];
        for (int i = 0; i < comps.length; i++) paramTypes[i] = comps[i].getType();
        try {
            return recordType.getDeclaredConstructor(paramTypes).newInstance(row);
        } catch (ReflectiveOperationException e) {
            throw new MansartDataException("Failed to construct " + recordType.getName() + " from projection row", e);
        }
    }

    private static Class<?> projectionElement(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt.isArray()) return rt.getComponentType();
        java.lang.reflect.Type genericRt = m.getGenericReturnType();
        if (genericRt instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type[] args = pt.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> c) return c;
        }
        return rt;
    }
}
```
What changed, for the reviewer: `execute`, `buildWhere`, `build`, `buildIn`, `renderExpr` and `resolveArg` take a
`CallShape` instead of the `Method` and the name map; `resolveArg` also gets the type of the attribute the value meets
(`comparedType` for `fn(attr)`, the SET column's type in `renderExpr`) and converts only when `shape.convert()`;
`wrapLongResult` takes the return type; the private `dispatchMultiProjection(Class, Method, …, Object[])` overload is
gone (its caller calls the public one with `shape.element()`, the value it computed); `nameToIndexFor`'s Javadoc is
back on its own method. With `convert=false` every value is the one the old code bound.

- [ ] **Step 5: The `@Query` dispatcher passes the method's shape**

In `CORE/RuntimeRepositoryProxy.java`, replace exactly:
```java
        Map<String, Integer> nameToIdx = JdqlExecutor.nameToIndexFor(m);
        return (rt, em, args) -> JdqlExecutor.execute(stmt, m, em, attrIdx, rt,
                args == null ? new Object[0] : args, nameToIdx);
```
with:
```java
        JdqlExecutor.CallShape shape = JdqlExecutor.CallShape.of(m);
        return (rt, em, args) -> JdqlExecutor.execute(stmt, shape, em, attrIdx, rt,
                args == null ? new Object[0] : args);
```

- [ ] **Step 6: Non-regression — the whole of `mansart-jakarta-data`, tests included**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run && mvn -nsu -f mansart-jakarta-data/pom.xml install > "$SCRATCH/t1-refactor.log" 2>&1; grep -E "ERROR|Tests run:.*Fail|Reactor Summary|BUILD" "$SCRATCH/t1-refactor.log" | grep -v "Time elapsed" | tail -20
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, the same `mansart-data-tests` total as Step 2, no failure. A
compilation error names a call left with the old arguments: fix it with the signatures above.

- [ ] **Step 7: Commit in the worktree**

Message (`$SCRATCH/MANSART_COMMIT_MSG`, written with the Write tool):
```
refactor(data-core): JdqlExecutor.execute takes a call shape, not a Method

What execute took from the @Query method -- the return type it dispatches
on, the element type of a projection, the index of each named parameter --
is now a CallShape record, which CallShape.of(Method) builds as before. A
shape can also ask for the values compared to an attribute to be converted
to its type (JdqlValues); a @Query method's never does. No behaviour
change: every test of mansart-jakarta-data passes as before.

This prepares JdqlExecutor.run, which runs a statement given as text.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run
C=mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core
git add $C/JdqlExecutor.java $C/JdqlValues.java $C/RuntimeRepositoryProxy.java
git commit -S -F "$SCRATCH/MANSART_COMMIT_MSG" && rm "$SCRATCH/MANSART_COMMIT_MSG"
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 | diff - "$SCRATCH/mansart-status-before.txt" && echo "user checkout untouched"
```
Expected: one commit; `user checkout untouched`.

---

### Task 2: Mansart — `JdqlExecutor.run`, `isWrite`, `target` and `JdqlResult` (spec §2, §5 Mansart side, §6)

**Files:**
- Create: `CORE/JdqlResult.java`
- Modify: `CORE/JdqlExecutor.java` (insert the public API before `execute`)
- Create (tests): `MT/ShipmentStatus.java`, `MT/Shipment.java`, `MT/JdqlRunTest.java`

**Interfaces:**
- Consumes: Task 1's `CallShape`, `execute(Stmt, CallShape, …)`, `lookup`, `boxed`, `JdqlValues.convert`.
- Produces (public, package `io.vidocq.mansart.data.core`, installed in `~/.m2` as `0.4.0-SNAPSHOT`):
  - `public static JdqlResult JdqlExecutor.run(String jdql, Map<String, ?> parameters, EntityModel<?> model,
    RepositoryRuntime runtime)`;
  - `public static boolean JdqlExecutor.isWrite(String jdql)`;
  - `public static java.util.Optional<String> JdqlExecutor.target(String jdql)`;
  - `public sealed interface JdqlResult` with `record Entities(List<?> entities)`,
    `record Rows(List<String> columns, List<Object[]> rows)`, `record Count(long count)`, `record Value(Object value)`.

- [ ] **Step 1: The test entity**

Create `MT/ShipmentStatus.java`:
```java
package io.vidocq.mansart.data.tests;

/** The status of a {@link Shipment}, which a JDQL statement gives by name. */
public enum ShipmentStatus {
    PENDING,
    SHIPPED,
    DELIVERED
}
```
Create `MT/Shipment.java`:
```java
package io.vidocq.mansart.data.tests;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What {@link JdqlRunTest} runs statements on: one attribute of each type a statement given as text converts a value
 * to. Not an {@code @Entity}, so that the processor generates no metamodel and Mansart builds its model at run time,
 * as it does for a tool's entity; the table is created by the test.
 */
@Table(name = "jdql_shipments")
public class Shipment {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "reference")
    private String reference;

    @Column(name = "status")
    private ShipmentStatus status;

    @Column(name = "shipped_on")
    private LocalDate shippedOn;

    @Column(name = "logged_at")
    private Instant loggedAt;

    @Column(name = "weight")
    private BigDecimal weight;

    @Column(name = "parcels")
    private Integer parcels;

    @Column(name = "fragile")
    private Boolean fragile;

    public Shipment() {}

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public LocalDate getShippedOn() {
        return shippedOn;
    }

    public Instant getLoggedAt() {
        return loggedAt;
    }

    public BigDecimal getWeight() {
        return weight;
    }

    public Integer getParcels() {
        return parcels;
    }

    public Boolean getFragile() {
        return fragile;
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `MT/JdqlRunTest.java`:
```java
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.EntityModels;
import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.MansartData;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JdqlExecutor#run}: a JDQL statement given as text, as a tool such as the Vidocq dev console runs it, on H2 —
 * the shapes of its result, the conversion of its values to the attributes' types, and what it refuses.
 */
class JdqlRunTest {

    private static DataSource dataSource;
    private static RepositoryRuntime runtime;
    private static EntityModel<Shipment> model;

    @BeforeAll
    static void initStack() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mansart-jdql-run;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        dataSource = ds;
        runtime = MansartData.builder().dataSource(ds).build().runtime();
        model = EntityModels.of(Shipment.class);
    }

    @BeforeEach
    void resetRows() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS \"jdql_shipments\"");
            s.execute("CREATE TABLE \"jdql_shipments\" (\"id\" BIGINT PRIMARY KEY, \"reference\" VARCHAR(20),"
                    + " \"status\" VARCHAR(20), \"shipped_on\" DATE, \"logged_at\" TIMESTAMP WITH TIME ZONE,"
                    + " \"weight\" NUMERIC(10, 2), \"parcels\" INTEGER, \"fragile\" BOOLEAN)");
            s.execute("INSERT INTO \"jdql_shipments\" VALUES"
                    + " (1, 'A-1', 'PENDING', DATE '2026-09-01',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-01 08:00:00+00', 2.50, 1, TRUE),"
                    + " (2, 'B-2', 'SHIPPED', DATE '2026-09-10',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-10 09:30:00+00', 12.75, 3, FALSE),"
                    + " (3, 'C-3', 'SHIPPED', DATE '2026-09-20',"
                    + " TIMESTAMP WITH TIME ZONE '2026-09-20 17:45:00+00', 7.00, 2, FALSE),"
                    + " (4, 'D-4', 'DELIVERED', DATE '2026-08-15',"
                    + " TIMESTAMP WITH TIME ZONE '2026-08-15 12:00:00+00', 0.80, 1, TRUE)");
        }
    }

    private static JdqlResult run(String jdql, Map<String, ?> parameters) {
        return JdqlExecutor.run(jdql, parameters, model, runtime);
    }

    /** The references of the entities {@code result} holds, in its order. */
    private static List<String> references(JdqlResult result) {
        assertThat(result).isInstanceOf(JdqlResult.Entities.class);
        return ((JdqlResult.Entities) result).entities().stream().map(e -> ((Shipment) e).getReference()).toList();
    }

    /** The rows of a projection, each as a list. */
    private static List<List<Object>> values(JdqlResult result) {
        assertThat(result).isInstanceOf(JdqlResult.Rows.class);
        return ((JdqlResult.Rows) result).rows().stream().map(row -> Arrays.asList(row)).toList();
    }

    private static long count(String jdql, Map<String, ?> parameters) {
        return ((JdqlResult.Count) run(jdql, parameters)).count();
    }

    /** Parameters that may hold {@code null}, which {@link Map#of} refuses. */
    private static Map<String, Object> params(String name, Object value) {
        Map<String, Object> out = new HashMap<>();
        out.put(name, value);
        return out;
    }

    @Test
    void aPlainQueryReturnsTheEntitiesInTheirOrder() {
        JdqlResult result = run("FROM Shipment ORDER BY id", Map.of());

        assertThat(references(result)).containsExactly("A-1", "B-2", "C-3", "D-4");
        Shipment first = (Shipment) ((JdqlResult.Entities) result).entities().getFirst();
        assertThat(first.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(first.getShippedOn()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void aNamedParameterGivenAsTextIsConvertedToAnEnum() {
        assertThat(references(run("FROM Shipment WHERE status = :status ORDER BY id", Map.of("status", "SHIPPED"))))
                .containsExactly("B-2", "C-3");
    }

    @Test
    void aValueOfTheAttributesTypeIsKept() {
        assertThat(references(run("FROM Shipment WHERE status = :status", Map.of("status", ShipmentStatus.PENDING))))
                .containsExactly("A-1");
    }

    @Test
    void aLiteralIsConvertedToo() {
        assertThat(references(run("FROM Shipment WHERE status = 'DELIVERED'", Map.of()))).containsExactly("D-4");
        assertThat(references(run("FROM Shipment WHERE status = DELIVERED", Map.of()))).containsExactly("D-4");
        assertThat(references(run("FROM Shipment WHERE shippedOn >= '2026-09-10' ORDER BY id", Map.of())))
                .containsExactly("B-2", "C-3");
        assertThat(references(run("FROM Shipment WHERE weight > '10'", Map.of()))).containsExactly("B-2");
    }

    @Test
    void datesAndInstantsAreReadAsIsoText() {
        assertThat(references(run("FROM Shipment WHERE loggedAt < :before ORDER BY id",
                Map.of("before", "2026-09-05T00:00:00Z")))).containsExactly("A-1", "D-4");
        assertThat(references(run("FROM Shipment WHERE shippedOn BETWEEN :from AND :to ORDER BY id",
                Map.of("from", "2026-09-01", "to", "2026-09-10")))).containsExactly("A-1", "B-2");
    }

    @Test
    void numbersAreConvertedExactly() {
        assertThat(references(run("FROM Shipment WHERE parcels = :n", Map.of("n", "3")))).containsExactly("B-2");
        assertThat(references(run("FROM Shipment WHERE parcels = :n", Map.of("n", new BigDecimal("3")))))
                .containsExactly("B-2");
        assertThat(references(run("FROM Shipment WHERE weight > :w ORDER BY id", Map.of("w", 7))))
                .containsExactly("B-2");
        assertThat(references(run("FROM Shipment WHERE fragile = :f ORDER BY id", Map.of("f", "true"))))
                .containsExactly("A-1", "D-4");
    }

    @Test
    void aListParameterFeedsAnInElementByElement() {
        assertThat(references(run("FROM Shipment WHERE status IN :states ORDER BY id",
                Map.of("states", List.of("PENDING", "DELIVERED"))))).containsExactly("A-1", "D-4");
        assertThat(references(run("FROM Shipment WHERE status IN :states", Map.of("states", List.of()))))
                .isEmpty();
    }

    @Test
    void aValueThatDoesNotConvertIsRefusedNamingIt() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = :status", Map.of("status", "LOST")))
                .isInstanceOf(MansartDataException.class).hasMessage(":status: not a ShipmentStatus");
        assertThatThrownBy(() -> run("FROM Shipment WHERE parcels = :n", Map.of("n", "2.5")))
                .isInstanceOf(MansartDataException.class).hasMessage(":n: not a Integer");
        assertThatThrownBy(() -> run("FROM Shipment WHERE parcels = :n", Map.of("n", new BigDecimal("2.5"))))
                .hasMessage(":n: not a Integer");
        assertThatThrownBy(() -> run("FROM Shipment WHERE shippedOn = :d", Map.of("d", "yesterday")))
                .hasMessage(":d: not a LocalDate");
        assertThatThrownBy(() -> run("FROM Shipment WHERE loggedAt < :at", Map.of("at", "2026-09-01")))
                .hasMessage(":at: not a Instant");
        assertThatThrownBy(() -> run("FROM Shipment WHERE fragile = :f", Map.of("f", "yes")))
                .hasMessage(":f: not a Boolean");
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = 'LOST'", Map.of()))
                .isInstanceOf(MansartDataException.class).hasMessage("'LOST': not a ShipmentStatus");
    }

    @Test
    void aWriteWhoseValueDoesNotConvertChangesNothing() {
        assertThatThrownBy(() -> run("UPDATE Shipment SET status = :to WHERE status = :from",
                Map.of("to", "LOST", "from", "SHIPPED"))).hasMessage(":to: not a ShipmentStatus");

        assertThat(count("SELECT COUNT(this) FROM Shipment WHERE status = :s", Map.of("s", "SHIPPED"))).isEqualTo(2);
    }

    @Test
    void aProjectionOfOneColumnIsRowsOfOneValue() {
        JdqlResult result = run("SELECT reference FROM Shipment WHERE fragile = :f ORDER BY reference",
                Map.of("f", true));

        assertThat(((JdqlResult.Rows) result).columns()).containsExactly("reference");
        assertThat(values(result)).containsExactly(List.of("A-1"), List.of("D-4"));
    }

    @Test
    void aProjectionOfSeveralColumnsKeepsTheirOrder() {
        JdqlResult result = run("SELECT reference, parcels FROM Shipment WHERE status = :s ORDER BY reference",
                Map.of("s", "SHIPPED"));

        assertThat(((JdqlResult.Rows) result).columns()).containsExactly("reference", "parcels");
        assertThat(values(result)).containsExactly(List.of("B-2", 3), List.of("C-3", 2));
    }

    @Test
    void aCountCountsTheRows() {
        assertThat(run("SELECT COUNT(this) FROM Shipment WHERE status = :s", Map.of("s", "SHIPPED")))
                .isEqualTo(new JdqlResult.Count(2));
    }

    @Test
    void anAggregateIsItsValueOrNullWithoutRows() {
        JdqlResult.Value max = (JdqlResult.Value) run("SELECT MAX(weight) FROM Shipment", Map.of());
        assertThat((BigDecimal) max.value()).isEqualByComparingTo("12.75");
        assertThat(run("SELECT MAX(parcels) FROM Shipment WHERE reference = :r", Map.of("r", "none")))
                .isEqualTo(new JdqlResult.Value(null));
        assertThat(run("SELECT SUM(parcels) FROM Shipment", Map.of())).isEqualTo(new JdqlResult.Value(7L));
    }

    @Test
    void anUpdateCountsTheRowsItChangedAndChangesThem() {
        assertThat(run("UPDATE Shipment SET status = :to WHERE status = :from",
                Map.of("to", "DELIVERED", "from", "SHIPPED"))).isEqualTo(new JdqlResult.Count(2));
        assertThat(count("SELECT COUNT(this) FROM Shipment WHERE status = :s", Map.of("s", "DELIVERED")))
                .isEqualTo(3);

        assertThat(run("UPDATE Shipment SET parcels = parcels + :more WHERE reference = :r",
                Map.of("more", "2", "r", "A-1"))).isEqualTo(new JdqlResult.Count(1));
        assertThat(values(run("SELECT parcels FROM Shipment WHERE reference = 'A-1'", Map.of())))
                .containsExactly(List.of(3));
    }

    @Test
    void aDeleteCountsTheRowsItRemovedAndRemovesThem() {
        assertThat(run("DELETE FROM Shipment WHERE fragile = true", Map.of())).isEqualTo(new JdqlResult.Count(2));
        assertThat(references(run("FROM Shipment ORDER BY id", Map.of()))).containsExactly("B-2", "C-3");
    }

    @Test
    void aParameterWithoutValueOrAValueWithoutParameterIsRefused() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = :status", Map.of()))
                .isInstanceOf(MansartDataException.class).hasMessage(":status: no value given");
        assertThatThrownBy(() -> run("FROM Shipment WHERE status = :status",
                Map.of("status", "PENDING", "extra", 1)))
                .isInstanceOf(MansartDataException.class).hasMessage(":extra: not used by the statement");
        assertThat(references(run("FROM Shipment WHERE reference = :r", params("r", null)))).isEmpty();
    }

    @Test
    void positionalParametersAreRefused() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE parcels = ?1", Map.of()))
                .isInstanceOf(MansartDataException.class)
                .hasMessage("positional parameters are not supported here; use :name");
    }

    @Test
    void theEntityMayBeNamedByItsFullClassName() {
        assertThat(references(run("FROM io.vidocq.mansart.data.tests.Shipment WHERE id = :id", Map.of("id", 4))))
                .containsExactly("D-4");
    }

    @Test
    void aStatementThatDoesNotParseIsAMansartDataException() {
        assertThatThrownBy(() -> run("FROM Shipment WHERE", Map.of())).isInstanceOf(MansartDataException.class);
        assertThatThrownBy(() -> run("FROM Parcel", Map.of())).isInstanceOf(MansartDataException.class)
                .hasMessageContaining("Parcel");
    }

    @Test
    void isWriteReadsTheFirstKeyword() {
        assertThat(JdqlExecutor.isWrite("  update Shipment SET parcels = 1")).isTrue();
        assertThat(JdqlExecutor.isWrite("DELETE FROM Shipment")).isTrue();
        assertThat(JdqlExecutor.isWrite("FROM Shipment")).isFalse();
        assertThat(JdqlExecutor.isWrite("SELECT COUNT(this) FROM Shipment")).isFalse();
        assertThat(JdqlExecutor.isWrite("UPDATED")).isFalse();
        assertThat(JdqlExecutor.isWrite("")).isFalse();
        assertThat(JdqlExecutor.isWrite(null)).isFalse();
    }

    @Test
    void targetIsTheEntityAStatementNames() {
        assertThat(JdqlExecutor.target("FROM Shipment WHERE parcels > 1")).contains("Shipment");
        assertThat(JdqlExecutor.target("select reference, parcels from Shipment")).contains("Shipment");
        assertThat(JdqlExecutor.target("SELECT COUNT(this) FROM Shipment")).contains("Shipment");
        assertThat(JdqlExecutor.target("UPDATE Shipment SET parcels = 1")).contains("Shipment");
        assertThat(JdqlExecutor.target("DELETE FROM Shipment WHERE parcels = 0")).contains("Shipment");
        assertThat(JdqlExecutor.target("FROM io.vidocq.mansart.data.tests.Shipment"))
                .contains("io.vidocq.mansart.data.tests.Shipment");
        assertThat(JdqlExecutor.target("WHERE parcels > 1")).isEmpty();
        assertThat(JdqlExecutor.target("UPDATE SET parcels = 1")).isEmpty();
        assertThat(JdqlExecutor.target("DELETE WHERE parcels = 0")).isEmpty();
        assertThat(JdqlExecutor.target(null)).isEmpty();
    }

    @Test
    void targetSkipsStringLiteralsAndParameters() {
        assertThat(JdqlExecutor.target("UPDATE Shipment SET reference = 'FROM Elsewhere'")).contains("Shipment");
        assertThat(JdqlExecutor.target(
                "SELECT reference FROM Shipment WHERE reference = 'from x' OR reference = :from"))
                .contains("Shipment");
        assertThat(JdqlExecutor.target("SELECT reference WHERE reference = 'FROM Elsewhere'")).isEmpty();
    }
}
```

- [ ] **Step 3: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run && mvn -nsu -f mansart-jakarta-data/pom.xml -pl mansart-data-tests test -Dtest=JdqlRunTest > "$SCRATCH/t2.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t2.log" | head -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` for `JdqlResult` (Task 1 installed `mansart-data-core` without it).

- [ ] **Step 4: `JdqlResult`**

Create `CORE/JdqlResult.java`:
```java
package io.vidocq.mansart.data.core;

import java.util.List;

/**
 * What {@link JdqlExecutor#run} returns for one JDQL statement, by the statement's shape. Every list is read whole,
 * as every Mansart query is: the caller cuts.
 */
public sealed interface JdqlResult {

    /**
     * A plain {@code FROM … [WHERE …] [ORDER BY …]}.
     *
     * @param entities the entities, in the statement's order
     */
    record Entities(List<?> entities) implements JdqlResult {

        public Entities {
            entities = List.copyOf(entities);
        }
    }

    /**
     * A projection, {@code SELECT a, b FROM …}, or {@code SELECT a FROM …}.
     *
     * @param columns the attributes selected, as the statement names them, a path such as {@code author.name}
     *                included
     * @param rows    one array per row, a value per column in that order
     */
    record Rows(List<String> columns, List<Object[]> rows) implements JdqlResult {

        public Rows {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }

    /**
     * {@code SELECT COUNT(this) FROM …}: the rows counted; an {@code UPDATE} or a {@code DELETE}: the rows changed.
     *
     * @param count how many
     */
    record Count(long count) implements JdqlResult {}

    /**
     * An aggregate, {@code SELECT MAX(price) FROM …}.
     *
     * @param value its value, {@code null} when there is no row
     */
    record Value(Object value) implements JdqlResult {}
}
```

- [ ] **Step 5: `run`, `isWrite`, `target`**

In `CORE/JdqlExecutor.java`, replace exactly:
```java
    /** Executes {@code stmt} as a call of {@code shape} makes it, with the call's {@code args}. */
```
with:
```java
    /**
     * Runs one JDQL statement given as text, as a tool does — the Vidocq dev console, for one — rather than a
     * {@code @Query} method: the statement is parsed against {@code model}, its named parameters take their values
     * from {@code parameters}, and it runs on {@code runtime} as a {@code @Query} method's statement would, in
     * whatever transaction the caller has begun.
     *
     * <p>A named parameter {@code :name} takes {@code parameters.get("name")}. A value compared to an attribute — a
     * parameter, a literal, or a value of an {@code UPDATE … SET} — is converted to the attribute's Java type when it
     * is a {@code String} and the attribute is not text: an enum by constant name, the {@code java.time} types by ISO
     * parsing, the numeric types exactly ({@code BigDecimal} and {@code BigInteger} included), a {@code Boolean} from
     * {@code true} or {@code false}, a {@code UUID} by {@link java.util.UUID#fromString}. A number is converted
     * exactly to a numeric attribute's type, a collection (for {@code IN :names}) element by element; a value of the
     * right type is kept. Every value is checked before anything runs.
     *
     * <p>The result is read whole: {@code run} returns all that the statement selects, and the caller cuts.
     *
     * @param jdql       one statement: {@code FROM …}, {@code SELECT … FROM …}, {@code UPDATE … SET …} or
     *                   {@code DELETE FROM …}, naming {@code model}'s entity by its simple or its full class name
     * @param parameters the value of each named parameter, by its name without the colon; {@code null} for none
     * @param model      the model of the entity the statement names, such as {@link EntityModels#of} gives
     * @param runtime    the runtime of the data store to run it on
     * @return the entities, the rows of a projection, the rows counted or changed, or an aggregate's value
     * @throws MansartDataException when the statement does not parse; when it uses a positional parameter
     *                              ({@code ?1}), a named parameter that has no value, or when a value is given that
     *                              no parameter uses; when a value does not convert ({@code :status: not a Status});
     *                              and as a {@code @Query} method's statement throws when it runs
     */
    public static JdqlResult run(String jdql, Map<String, ?> parameters, EntityModel<?> model,
                                 RepositoryRuntime runtime) {
        java.util.Objects.requireNonNull(jdql, "jdql");
        java.util.Objects.requireNonNull(model, "model");
        java.util.Objects.requireNonNull(runtime, "runtime");
        Map<String, ?> given = parameters == null ? Map.of() : parameters;
        Map<String, Attribute<?, ?>> attrIndex = new HashMap<>();
        for (Attribute<?, ?> a : model.attributes()) attrIndex.put(a.name(), a);
        // the parser compares the entity it reads with this name: the full class name when the statement uses it
        String entityName = target(jdql).filter(model.entityClass().getName()::equals)
                .orElse(model.entityClass().getSimpleName());
        JdqlAst.Stmt stmt;
        try {
            stmt = JdqlAst.parse(jdql, attrIndex.keySet(), entityName);
        } catch (JdqlAst.ParseException e) {
            throw new MansartDataException(e.getMessage(), e);
        }
        List<String> used = parameterNames(stmt);
        for (String name : used) {
            if (!given.containsKey(name)) throw new MansartDataException(":" + name + ": no value given");
        }
        for (String name : new java.util.TreeSet<>(given.keySet())) {
            if (!used.contains(name)) throw new MansartDataException(":" + name + ": not used by the statement");
        }
        Map<String, Integer> names = new HashMap<>();
        Object[] args = new Object[used.size()];
        for (int i = 0; i < used.size(); i++) {
            names.put(used.get(i), i);
            args[i] = given.get(used.get(i));
        }
        Object result = execute(stmt, shapeOf(stmt, model, attrIndex, names), model, attrIndex, runtime, args);
        return switch (stmt.kind) {
            case SELECT -> new JdqlResult.Entities((List<?>) result);
            case COUNT, UPDATE, DELETE -> new JdqlResult.Count(((Number) result).longValue());
            case AGGREGATE -> new JdqlResult.Value(result);
            case PROJECT -> new JdqlResult.Rows(List.of(stmt.scalarAttr), columnRows((List<?>) result));
            case PROJECT_MULTI -> new JdqlResult.Rows(stmt.projectAttrs, projectedRows(result));
        };
    }

    /**
     * Whether {@code jdql} is an {@code UPDATE} or a {@code DELETE}, read from its first keyword, without parsing it.
     *
     * @param jdql a statement, or {@code null}
     */
    public static boolean isWrite(String jdql) {
        List<String> words = words(jdql);
        return !words.isEmpty()
                && (words.getFirst().equalsIgnoreCase("UPDATE") || words.getFirst().equalsIgnoreCase("DELETE"));
    }

    /**
     * The entity {@code jdql} names: the identifier after {@code UPDATE}, or after its first {@code FROM}
     * ({@code SELECT … FROM}, {@code DELETE FROM}), a dotted full class name kept whole; read without parsing, string
     * literals and parameters skipped.
     *
     * @param jdql a statement, or {@code null}
     * @return the name as written; empty when the statement names none, such as {@code WHERE price > :min}
     */
    public static java.util.Optional<String> target(String jdql) {
        List<String> words = words(jdql);
        if (words.isEmpty()) return java.util.Optional.empty();
        if (words.getFirst().equalsIgnoreCase("UPDATE")) {
            return words.size() > 1 && !isClauseKeyword(words.get(1))
                    ? java.util.Optional.of(words.get(1)) : java.util.Optional.empty();
        }
        for (int i = 0; i + 1 < words.size(); i++) {
            if (words.get(i).equalsIgnoreCase("FROM")) {
                String next = words.get(i + 1);
                return isClauseKeyword(next) ? java.util.Optional.empty() : java.util.Optional.of(next);
            }
        }
        return java.util.Optional.empty();
    }

    /** The keywords that may follow {@code UPDATE} or {@code FROM} where an entity name is left out. */
    private static boolean isClauseKeyword(String word) {
        return switch (word.toUpperCase(java.util.Locale.ROOT)) {
            case "SET", "WHERE", "ORDER", "FROM" -> true;
            default -> false;
        };
    }

    /**
     * The words of {@code jdql} — keywords and identifiers, a dotted name kept whole — in order, without its string
     * literals, its parameters ({@code :name}, {@code ?1}) and its numbers.
     */
    private static List<String> words(String jdql) {
        List<String> out = new ArrayList<>();
        if (jdql == null) return out;
        int i = 0;
        int n = jdql.length();
        while (i < n) {
            char c = jdql.charAt(i);
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (jdql.charAt(i) == '\'') {
                        // '' inside a literal is one quote
                        if (i + 1 < n && jdql.charAt(i + 1) == '\'') { i += 2; continue; }
                        i++;
                        break;
                    }
                    i++;
                }
            } else if (c == ':' || c == '?' || Character.isDigit(c)) {
                i++;
                while (i < n && isWordPart(jdql.charAt(i))) i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && isWordPart(jdql.charAt(i))) i++;
                out.add(jdql.substring(start, i));
            } else {
                i++;
            }
        }
        return out;
    }

    private static boolean isWordPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    /** The named parameters {@code stmt} uses, each once, in the order they appear; a positional one is refused. */
    private static List<String> parameterNames(JdqlAst.Stmt stmt) {
        List<JdqlAst.ArgRef> refs = new ArrayList<>();
        for (JdqlAst.SetAssign sa : stmt.setAssignments) collectArgs(sa.value(), refs);
        if (stmt.where != null) collectArgs(stmt.where, refs);
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (JdqlAst.ArgRef ref : refs) {
            if (ref.isLiteral) continue;
            if (!ref.isNamed()) {
                throw new MansartDataException("positional parameters are not supported here; use :name");
            }
            names.add(ref.named);
        }
        return List.copyOf(names);
    }

    private static void collectArgs(JdqlAst.Pred p, List<JdqlAst.ArgRef> out) {
        switch (p) {
            case JdqlAst.Cmp c -> out.add(c.arg());
            case JdqlAst.FnCmp c -> out.add(c.arg());
            case JdqlAst.IsNull ignored -> { /* no value */ }
            case JdqlAst.FnIsNull ignored -> { /* no value */ }
            case JdqlAst.Between b -> {
                out.add(b.lo());
                out.add(b.hi());
            }
            case JdqlAst.FnBetween b -> {
                out.add(b.lo());
                out.add(b.hi());
            }
            case JdqlAst.In in -> out.addAll(in.args());
            case JdqlAst.FnIn in -> out.addAll(in.args());
            case JdqlAst.And and -> and.children().forEach(c -> collectArgs(c, out));
            case JdqlAst.Or or -> or.children().forEach(c -> collectArgs(c, out));
            case JdqlAst.Not not -> collectArgs(not.child(), out);
        }
    }

    private static void collectArgs(JdqlAst.Expr e, List<JdqlAst.ArgRef> out) {
        switch (e) {
            case JdqlAst.ExprArg a -> out.add(a.arg());
            case JdqlAst.ExprAttr ignored -> { /* an attribute, no value */ }
            case JdqlAst.ExprBin bin -> {
                collectArgs(bin.left(), out);
                collectArgs(bin.right(), out);
            }
            case JdqlAst.ExprFunc fn -> fn.args().forEach(arg -> collectArgs(arg, out));
        }
    }

    /**
     * The shape of a statement run as text: its result is a list (entities, a column, rows), a {@code long} (a count,
     * the rows changed) or an aggregate's value, and its values are converted.
     */
    private static CallShape shapeOf(JdqlAst.Stmt stmt, EntityModel<?> model,
                                     Map<String, Attribute<?, ?>> attrIndex, Map<String, Integer> names) {
        return switch (stmt.kind) {
            case SELECT -> new CallShape(List.class, model.entityClass(), names, true);
            case PROJECT -> new CallShape(List.class,
                    boxed(lookup(model, attrIndex, stmt.scalarAttr).javaType()), names, true);
            case PROJECT_MULTI -> new CallShape(List.class, Object[].class, names, true);
            case AGGREGATE -> new CallShape(
                    aggregateType(stmt.aggregateOp, lookup(model, attrIndex, stmt.scalarAttr).javaType()),
                    null, names, true);
            case COUNT, UPDATE, DELETE -> new CallShape(long.class, null, names, true);
        };
    }

    /**
     * The type an aggregate is read as: {@code MIN} and {@code MAX} the attribute's; {@code SUM} a {@code Long} over
     * an integral attribute, a {@code Double} over a floating one, else the attribute's; {@code AVG} a
     * {@code Double}, a {@code BigDecimal} over a {@code BigDecimal}.
     */
    private static Class<?> aggregateType(String op, Class<?> attribute) {
        Class<?> type = boxed(attribute);
        boolean integral = type == Long.class || type == Integer.class || type == Short.class || type == Byte.class;
        boolean floating = type == Double.class || type == Float.class;
        return switch (op) {
            case "AVG" -> type == java.math.BigDecimal.class ? java.math.BigDecimal.class : Double.class;
            case "SUM" -> integral ? Long.class : floating ? Double.class : type;
            default -> type;
        };
    }

    /** A projected column as rows of one value. */
    private static List<Object[]> columnRows(List<?> column) {
        List<Object[]> rows = new ArrayList<>(column.size());
        for (Object value : column) rows.add(new Object[] {value});
        return rows;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> projectedRows(Object rows) {
        return (List<Object[]>) rows;
    }

    /** Executes {@code stmt} as a call of {@code shape} makes it, with the call's {@code args}. */
```

- [ ] **Step 6: Install `mansart-data-core`, run the tests to see them pass**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run && mvn -nsu -f mansart-jakarta-data/pom.xml -pl mansart-data-core install -DskipTests > "$SCRATCH/t2-core.log" 2>&1; grep -E "ERROR|BUILD" "$SCRATCH/t2-core.log" | tail -5; mvn -nsu -f mansart-jakarta-data/pom.xml -pl mansart-data-tests test -Dtest=JdqlRunTest > "$SCRATCH/t2.log" 2>&1; grep -E "Tests run:|FAIL|expected|BUILD" "$SCRATCH/t2.log" | tail -15
```
Expected: `BUILD SUCCESS` twice; `Tests run: 22, Failures: 0, Errors: 0`. A failure prints the assertion: fix the
code of Step 5 (not the test's expectation, which is the spec's) and re-run.

- [ ] **Step 7: Everything of `mansart-jakarta-data`, then install it whole**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run && mvn -nsu -f mansart-jakarta-data/pom.xml verify > "$SCRATCH/t2-verify.log" 2>&1; grep -E "ERROR|Tests run:.*Fail|BUILD" "$SCRATCH/t2-verify.log" | grep -v "Time elapsed" | tail -15 && mvn -nsu -f mansart-jakarta-data/pom.xml install -DskipTests > "$SCRATCH/t2-install.log" 2>&1; grep -E "ERROR|BUILD" "$SCRATCH/t2-install.log" | tail -3
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS` for both; the `mansart-data-tests` total is Task 1's plus 22, no
failure (the existing `@Query` tests unchanged). `~/.m2` now holds every `mansart-jakarta-data` jar of this branch.

- [ ] **Step 8: Commit in the worktree**

Message (`$SCRATCH/MANSART_COMMIT_MSG`):
```
feat(data-core): JdqlExecutor.run, a JDQL statement given as text

A tool such as the Vidocq dev console runs a JDQL statement a developer
types: JdqlExecutor.run(jdql, parameters, model, runtime) parses it
against the entity's model, binds its named parameters by name and runs
it as a @Query method's statement, returning a JdqlResult: the entities,
the rows of a projection, a count (or the rows an UPDATE or a DELETE
changed), or an aggregate's value.

A value compared to or assigned to an attribute is converted to the
attribute's type: an enum by name, java.time by ISO parsing, numbers
exactly, booleans, UUIDs; a value that does not convert, a parameter
without value, a value without parameter and a positional parameter are
refused before anything runs. isWrite and target read a statement's
first keyword and its entity without parsing it.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
cd /Users/yblazart/projects/perso/vidocq/mansart-jdql-run
C=mansart-jakarta-data/mansart-data-core/src/main/java/io/vidocq/mansart/data/core
T=mansart-jakarta-data/mansart-data-tests/src/test/java/io/vidocq/mansart/data/tests
git add $C/JdqlExecutor.java $C/JdqlResult.java $T/Shipment.java $T/ShipmentStatus.java $T/JdqlRunTest.java
git commit -S -F "$SCRATCH/MANSART_COMMIT_MSG" && rm "$SCRATCH/MANSART_COMMIT_MSG"
git status --short
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 | diff - "$SCRATCH/mansart-status-before.txt" && echo "user checkout untouched"
```
Expected: one commit, the worktree clean, `user checkout untouched`. Leave the worktree in place (the controller
pushes it).

---

### Task 3: The page — a string of `"format": "textarea"` is a multi-line field (spec §4, §6)

**Files:**
- Modify: `CON/src/main/resources/META-INF/resources/devconsole/console.js`, `console.css`
- Test: `CON/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java`
- Modify: `V/docs/en/modules/ROOT/pages/dev-console-panels.adoc` (the json-argument section)

**Interfaces:**
- Consumes: nothing.
- Produces: in a flat-schema form, a property `{"type": "string", "format": "textarea"}` is a
  `<textarea class="json-text">` of 4 rows inside a `label.arg.wide`; its value is read, filled, disabled and sent
  as an `<input>`'s. `vidocq-runtime-devconsole-extension` installed in `~/.m2`.

- [ ] **Step 1: Write the failing test**

In `PageTest.java`, replace exactly:
```java
    @Test
    void aReplayColumnCellThatReplaysNoActionOfThePanelStaysText() {
```
with:
```java
    @Test
    void aStringPropertyOfFormatTextareaIsAMultiLineFieldOfTheForm() {
        String script = file("console.js");
        String style = file("console.css");

        assertTrue(script.contains("} else if (kind === \"string\" && definition.format === \"textarea\") {"),
                "a string property of \"format\": \"textarea\", and only such a property, gets a textarea");
        assertTrue(script.contains("input = el(\"textarea\", \"json-text\");"), "a textarea of its own class");
        assertTrue(script.contains("input.rows = 4;"), "four lines to start with");
        assertTrue(script.contains("wrap.classList.add(\"wide\");"), "on a line of its own");
        String textarea = rule(style, ".action textarea.json-text {");
        assertTrue(textarea.contains("var(--mono)"), "monospace, as the JSON editor");
        assertTrue(textarea.contains("resize: vertical"), "taller when dragged");
        assertTrue(rule(style, ".action .arg.wide {").contains("flex-basis: 100%"), "the whole width of the form");
    }

    @Test
    void aReplayColumnCellThatReplaysNoActionOfThePanelStaysText() {
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension -Dtest=PageTest > "$SCRATCH/t3.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/t3.log" | tail -5
```
Expected: `Tests run: …, Failures: 1`, `aStringPropertyOfFormatTextareaIsAMultiLineFieldOfTheForm`.

- [ ] **Step 3: The field**

In `console.js`, replace exactly:
```js
      } else {
        input = el("input");
        input.type = "text";
        input.autocomplete = "off";
        input.spellcheck = false;
        if (kind !== "string") input.inputMode = "decimal";
      }
```
with:
```js
      } else if (kind === "string" && definition.format === "textarea") {
        // A text of several lines, such as a query: a textarea, read, filled and sent as an input is.
        input = el("textarea", "json-text");
        input.rows = 4;
        input.spellcheck = false;
        wrap.classList.add("wide");
      } else {
        input = el("input");
        input.type = "text";
        input.autocomplete = "off";
        input.spellcheck = false;
        if (kind !== "string") input.inputMode = "decimal";
      }
```
Then replace exactly:
```js
 * A json argument: a form generated from its schema when the schema is flat, using required, default, description
 * and enum, and a raw JSON editor otherwise, starting from the required properties. A "JSON" switch shows the form's
```
with:
```js
 * A json argument: a form generated from its schema when the schema is flat, using required, default, description,
 * enum and a string's "format": "textarea" (a field of several lines), and a raw JSON editor otherwise, starting from
 * the required properties. A "JSON" switch shows the form's
```

- [ ] **Step 4: Its style**

In `console.css`, replace exactly:
```css
.action input { width: 18em; max-width: 100%; }
```
with:
```css
.action input { width: 18em; max-width: 100%; }
.action .arg.wide { flex-basis: 100%; align-items: flex-start; }
.action textarea.json-text { font-family: var(--mono); font-size: 12px; color: var(--ink); background: var(--surface);
  border: 1px solid var(--rule); border-radius: 5px; padding: 4px 8px; width: 80ch; max-width: 100%;
  resize: vertical; }
```

- [ ] **Step 5: Run the page's tests, install the module**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension > "$SCRATCH/t3.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/t3.log" | tail -5
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 6: The documentation**

In `V/docs/en/modules/ROOT/pages/dev-console-panels.adoc`, replace exactly:
```
----

[#action-result]
```
with:
```
----

A string property whose schema also says `"format": "textarea"` [.tag-new]#NEW# is a field of several lines in that
form rather than a one-line one: four lines of monospace text to start with, taller when dragged, its value sent as
any other string's. The Mansart Data panel's *JDQL* tab uses it for its statement and its parameters
(xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data]):

[source,json]
----
{"type": "object",
 "properties": {"query": {"type": "string", "format": "textarea"},
                "params": {"type": "string", "format": "textarea"}},
 "required": ["query"]}
----

[#action-result]
```

- [ ] **Step 7: Commit**

Message (`V/.git/PLAN_COMMIT_MSG`):
```
feat(devconsole): a string of format textarea is a multi-line field

In the form the page generates from a flat JSON Schema, a string property
with "format": "textarea" is now a textarea of four lines, monospace and
resizable, read, filled and sent as a text field is. Nothing else of the
form changes. The Mansart Data panel's JDQL tab uses it for a statement.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
C=vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension
git add $C/src/main/resources/META-INF/resources/devconsole/console.js $C/src/main/resources/META-INF/resources/devconsole/console.css $C/src/test/java/io/vidocq/runtime/extensions/essentials/devconsole/PageTest.java docs/en/modules/ROOT/pages/dev-console-panels.adoc
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: DEV — the JDQL actions (spec §3, §5 `-dev` module)

**Files:**
- Modify: `DEV/src/main/java/.../dev/ResultJson.java`
- Create: `DEV/src/main/java/.../dev/JdqlRunner.java`, `DEV/src/main/java/.../dev/JdqlActions.java`
- Modify: `DEV/src/main/java/.../dev/RepositoryActions.java`
- Test: `DEV/src/test/java/.../dev/JdqlActionsTest.java` (new), `DEV/src/test/java/.../dev/ResultJsonTest.java`

(`.../dev/` is `io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev/`.)

**Interfaces:**
- Consumes: Task 2's `JdqlExecutor.run/isWrite/target`, `JdqlResult` (from `~/.m2`); sub-project 2's
  `ResultJson.of(Object, boolean, EntityJson)`, `ResultJson.Result(String body, String what, boolean rows)`,
  `ResultJson.MAX_ROWS`, `EntityJson`, `Json.parse/write`, `Scalars.object`, `Failures.text/line/cut`,
  `TransactionRunner.run(String, Work)`/`modes()`/`COMMIT`/`NONE`, `BeanLookup.reference(Class)`/`NoBean`,
  `CallHistory.add(String, long, String, String, long, String, String)`/`COLUMNS`/`writeTo`,
  `RepositoryActions.TRANSACTION`, `RepositoryActions.group(String, Set<String>)`.
- Produces:
  - `static String ResultJson.count(long n)` (`no row`, `1 row`, `N rows`);
  - `static ResultJson.Result ResultJson.table(List<String> columns, List<Object[]> rows, EntityJson entities)`;
  - `static Object ResultJson.node(Object value, EntityJson entities)`;
  - `interface JdqlRunner { JdqlRunner MANSART; JdqlResult run(String jdql, Map<String, Object> parameters,
    EntityModel<?> model, Object runtime); }`;
  - `final class JdqlActions` with `GROUP = "JDQL"`, `QUERY = "jdql.query"`, `WRITE = "jdql.write"`,
    `QUERY_LABEL = "Query"`, `WRITE_LABEL = "Update / Delete"`, `STATEMENT = "statement"`, `CONFIRMATION`,
    `COUNT = 2`, the constructor `JdqlActions(List<MansartDataCatalogue.Entity> entities,
    Function<String, Class<?>> classes, Function<Class<?>, EntityModel<?>> models, EntityJson json,
    BeanLookup beans, TransactionRunner transactions, JdqlRunner runner, CallHistory history, String group)`,
    `List<PanelAction> actions()`, `PanelAction.ActionResult call(boolean write, Map<String, String> given)`,
    `static String schema()`;
  - `static RepositoryActions RepositoryActions.build(List<Class<?>>, MansartDataCatalogue, BeanLookup,
    TransactionRunner, Function<Class<?>, EntityModel<?>>, Predicate<Method>, int maxActions, JdqlRunner jdql)`
    (the 7-argument `build` delegates with `null`: no JDQL tab), `static Class<?> RepositoryActions.load(String
    className, List<Class<?>> repositories)`.

- [ ] **Step 1: Write the failing tests**

Create `DEV/src/test/java/.../dev/JdqlActionsTest.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RecordedSample.Table;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Gizmo;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.Level;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev.RunFixtures.ReportQueries;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JDQL tab of the Mansart Data panel (JDQL console spec §3): what it offers, what it refuses before anything runs,
 * how it runs a statement — through a recording {@link JdqlRunner}, no database — and what it answers and keeps.
 */
class JdqlActionsTest {

    private static final String SECRET = "hunter2";
    /** The {@code RepositoryRuntime} bean the lookup gives: the runner only receives it. */
    private static final Object RUNTIME = new Object();

    private final RecordingTransactionManager manager = new RecordingTransactionManager();
    private final RecordingRunner runner = new RecordingRunner();

    /** Records each statement, what it ran with and the transaction's events at that moment; answers or throws. */
    final class RecordingRunner implements JdqlRunner {

        final List<String> queries = new ArrayList<>();
        final List<Map<String, Object>> parameters = new ArrayList<>();
        final List<Class<?>> entities = new ArrayList<>();
        final List<Object> runtimes = new ArrayList<>();
        final List<List<String>> eventsDuringRun = new ArrayList<>();
        JdqlResult answer = new JdqlResult.Entities(List.of());
        RuntimeException failure;

        @Override
        public JdqlResult run(String jdql, Map<String, Object> params, EntityModel<?> model, Object runtime) {
            queries.add(jdql);
            parameters.add(params);
            entities.add(model.entityClass());
            runtimes.add(runtime);
            eventsDuringRun.add(List.copyOf(manager.events));
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private RepositoryActions build(TransactionRunner transactions) {
        return RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(), type -> {
            if (type == RepositoryRuntime.class) {
                return RUNTIME;
            }
            throw new BeanLookup.NoBean();
        }, transactions, RunFixtures::model, method -> true, RepositoryActions.MAX_ACTIONS, runner);
    }

    private RepositoryActions build() {
        return build(new TransactionRunner(new JtaDemarcation(() -> manager)));
    }

    private static PanelAction action(RepositoryActions actions, String id) {
        return actions.actions().stream().filter(a -> a.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no action " + id));
    }

    private static ActionResult run(RepositoryActions actions, String id, String statement, String transaction) {
        Map<String, String> given = new HashMap<>();
        given.put("statement", statement);
        if (transaction != null) {
            given.put("transaction", transaction);
        }
        return action(actions, id).call().apply(given);
    }

    private static ActionResult query(RepositoryActions actions, String statement) {
        return run(actions, JdqlActions.QUERY, statement, null);
    }

    private static void assertRefused(RepositoryActions actions, String id, String statement, String expected) {
        ActionResult result = run(actions, id, statement, null);
        assertTrue(result.error(), statement);
        assertEquals(expected, result.summary(), statement);
        assertNull(result.body(), statement);
    }

    private static Table calls(RepositoryActions actions) {
        RecordedSample sample = new RecordedSample();
        actions.sample(sample);
        return (Table) sample.value("calls");
    }

    private static Gizmo bolt(long id) {
        return RunFixtures.gizmo(id, "bolt", 3, Level.LOW, LocalDate.of(2026, 10, 1), new BigDecimal("2.50"));
    }

    @Test
    void twoActionsInAJdqlTabAfterTheRepositories() {
        List<PanelAction> actions = build().actions();

        PanelAction query = actions.get(actions.size() - 2);
        PanelAction write = actions.getLast();
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE), List.of(query.id(), write.id()));
        assertEquals("m.report-queries.gizmo-count", actions.get(actions.size() - 3).id(), "after the repositories");
        assertEquals("JDQL", query.group());
        assertEquals("JDQL", write.group());
        assertEquals("Query", query.label());
        assertEquals("Update / Delete", write.label());
        assertNull(query.confirmation(), "a query runs on the first click");
        assertEquals("Runs this JDQL statement against the database.", write.confirmation());
        assertEquals(List.of("statement"), query.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals(List.of("statement", "transaction"),
                write.arguments().stream().map(PanelAction.Argument::name).toList());
        assertEquals(List.of("rollback", "commit"), write.arguments().get(1).allowedValues());
        String schema = query.arguments().getFirst().schema();
        assertEquals(schema, write.arguments().getFirst().schema());
        assertTrue(schema.contains("\"query\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
        assertTrue(schema.contains("\"params\":{\"type\":\"string\",\"format\":\"textarea\""), schema);
        assertTrue(schema.endsWith("\"required\":[\"query\"]}"), schema);
        assertTrue(query.description().endsWith("Entities: Gizmo, Part"), query.description());
    }

    @Test
    void aQueryRunsOnTheEntityItNamesWithItsParametersOutsideATransaction() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Entities(List.of(bolt(1), bolt(2)));

        ActionResult result = query(actions,
                "{\"query\":\" FROM Gizmo WHERE stock > :min \",\"params\":\"{\\\"min\\\": 2}\"}");

        assertFalse(result.error(), result.summary());
        assertTrue(result.summary().matches("2 rows in \\d+ ms"), result.summary());
        assertEquals(ActionResult.JSON, result.contentType());
        assertTrue(result.body().startsWith("[{\"id\":1,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\""),
                result.body());
        assertEquals(List.of("FROM Gizmo WHERE stock > :min"), runner.queries, "the statement, stripped");
        assertEquals(List.of(Map.of("min", new BigDecimal("2"))), runner.parameters);
        assertEquals(List.of(Gizmo.class), runner.entities);
        assertSame(RUNTIME, runner.runtimes.getFirst());
        assertEquals(List.of(), manager.events, "a query runs in no transaction of the console's");
        assertEquals("{\"entity\":\"" + Gizmo.class.getName() + "\",\"query\":\"FROM Gizmo WHERE stock > :min\","
                + "\"params\":{\"min\":2},\"transaction\":\"none\"}", result.details());
    }

    @Test
    void paramsMayAlsoBeAJsonObjectOrNothing() {
        RepositoryActions actions = build();

        assertFalse(query(actions, "{\"query\":\"FROM Gizmo WHERE name = :name\",\"params\":{\"name\":\"bolt\"}}")
                .error());
        assertFalse(query(actions, "{\"query\":\"FROM Gizmo\"}").error());
        assertFalse(query(actions, "{\"query\":\"FROM Gizmo\",\"params\":\"  \"}").error());

        assertEquals(List.of(Map.of("name", "bolt"), Map.of(), Map.of()), runner.parameters);
    }

    @Test
    void aListParameterIsPassedAsAList() {
        query(build(), "{\"query\":\"FROM Gizmo WHERE level IN :levels\",\"params\":{\"levels\":[\"LOW\",\"HIGH\"]}}");

        assertEquals(List.of(Map.of("levels", List.of("LOW", "HIGH"))), runner.parameters);
    }

    @Test
    void eachResultKindHasItsSummary() {
        RepositoryActions actions = build();

        runner.answer = new JdqlResult.Rows(List.of("name", "stock"), List.<Object[]>of(new Object[] {"bolt", 3}));
        ActionResult rows = query(actions, "{\"query\":\"SELECT name, stock FROM Gizmo\"}");
        assertTrue(rows.summary().matches("1 row in \\d+ ms"), rows.summary());
        assertEquals("[{\"name\":\"bolt\",\"stock\":3}]", rows.body());

        runner.answer = new JdqlResult.Count(42);
        ActionResult count = query(actions, "{\"query\":\"SELECT COUNT(this) FROM Gizmo\"}");
        assertEquals("42", count.summary());
        assertEquals("42", count.body());

        runner.answer = new JdqlResult.Value(new BigDecimal("4.00"));
        ActionResult max = query(actions, "{\"query\":\"SELECT MAX(price) FROM Gizmo\"}");
        assertEquals("4.00", max.summary());
        assertEquals("4.00", max.body());

        runner.answer = new JdqlResult.Value(null);
        assertEquals("null", query(actions, "{\"query\":\"SELECT MAX(price) FROM Gizmo\"}").summary());

        runner.answer = new JdqlResult.Entities(List.of());
        String none = query(actions, "{\"query\":\"FROM Gizmo\"}").summary();
        assertTrue(none.matches("no row in \\d+ ms"), none);
    }

    @Test
    void aQueryOfManyRowsShowsTheFirstHundred() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Entities(LongStream.rangeClosed(1, 150).mapToObj(JdqlActionsTest::bolt)
                .toList());

        ActionResult result = query(actions, "{\"query\":\"FROM Gizmo\"}");

        assertTrue(result.summary().matches("first 100 rows in \\d+ ms"), result.summary());
        assertEquals(100, ((List<?>) Json.parse(result.body())).size());
    }

    @Test
    void aWriteRunsInATransactionRolledBackByDefault() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Count(4);

        ActionResult result = run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}", null);

        assertEquals("4 rows · rolled back", result.summary());
        assertEquals("4", result.body());
        assertEquals(List.of(List.of("begin")), runner.eventsDuringRun, "run inside the transaction");
        assertEquals(List.of("begin", "rollback"), manager.events);
        assertTrue(result.details().endsWith("\"transaction\":\"rollback\"}"), result.details());
    }

    @Test
    void aWriteCommitsWhenAsked() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Count(1);

        ActionResult result = run(actions, JdqlActions.WRITE, "{\"query\":\"DELETE FROM Gizmo WHERE stock = 0\"}",
                "commit");

        assertEquals("1 row · committed", result.summary());
        assertEquals(List.of("begin", "commit"), manager.events);
        runner.answer = new JdqlResult.Count(0);
        assertEquals("no row · rolled back",
                run(actions, JdqlActions.WRITE, "{\"query\":\"DELETE FROM Gizmo\"}", "rollback").summary());
    }

    @Test
    void withoutATransactionManagerAWriteCanOnlyBeCommitted() {
        RepositoryActions actions = build(TransactionRunner.NONE);
        runner.answer = new JdqlResult.Count(2);

        assertEquals(List.of("commit"), action(actions, JdqlActions.WRITE).arguments().get(1).allowedValues());
        assertEquals("2 rows · committed",
                run(actions, JdqlActions.WRITE, "{\"query\":\"DELETE FROM Gizmo\"}", null).summary());
    }

    @Test
    void aFailingStatementRollsBackAndShowsItsClassAndItsMaskedMessage() {
        RepositoryActions actions = build();
        runner.failure = new MansartDataException("connection to jdbc:h2:tcp://sa:" + SECRET + "@db/x refused");

        ActionResult result = run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}", "commit");

        String expected = "io.vidocq.mansart.data.core.MansartDataException: "
                + "connection to jdbc:h2:tcp://***:***@db/x refused";
        assertTrue(result.error());
        assertEquals(expected, result.summary());
        assertEquals(ActionResult.TEXT, result.contentType());
        assertEquals(expected, result.body());
        assertEquals(List.of("begin", "rollback"), manager.events, "rolled back although commit was asked");
        Table calls = calls(actions);
        assertEquals("error: " + expected, calls.rows().getFirst().get(2));
        assertFalse(calls.toString().contains(SECRET));
    }

    @Test
    void whatIsRefusedRunsNothing() {
        RepositoryActions actions = build();

        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}",
                "an UPDATE or DELETE: use Update / Delete");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"delete from Gizmo\"}",
                "an UPDATE or DELETE: use Update / Delete");
        assertRefused(actions, JdqlActions.WRITE, "{\"query\":\"FROM Gizmo\"}", "not an UPDATE or DELETE: use Query");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Nope\"}",
                "unknown entity Nope; entities: Gizmo, Part");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"WHERE stock > 1\"}",
                "no entity: name it, FROM <Entity>, UPDATE <Entity> or DELETE FROM <Entity>");
        assertRefused(actions, JdqlActions.QUERY, "{}", "query: missing");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"  \"}", "query: missing");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":3}", "query: not a string");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"limit\":3}", "limit: unknown argument");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"params\":\"[1]\"}",
                "params: not a JSON object");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"params\":\"{\"}",
                "params: not valid JSON at character 2");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\",\"params\":{\"x\":{\"a\":1}}}",
                "params.x: not a value or a list of values");
        assertRefused(actions, JdqlActions.QUERY, "{\"query\":\"FROM Gizmo\"",
                "statement: not valid JSON at character 22");

        assertEquals(List.of(), runner.queries, "nothing run");
        assertEquals(List.of(), manager.events, "no transaction begun");
    }

    @Test
    void withoutARepositoryRuntimeBeanTheCallSaysSo() {
        RepositoryActions actions = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(),
                type -> {
                    throw new BeanLookup.NoBean();
                }, TransactionRunner.NONE, RunFixtures::model, method -> true, RepositoryActions.MAX_ACTIONS, runner);

        ActionResult result = query(actions, "{\"query\":\"FROM Gizmo\"}");

        assertTrue(result.error());
        assertEquals("no RepositoryRuntime bean", result.summary());
        assertEquals(List.of(), runner.queries);
    }

    @Test
    void anAmbiguousSimpleNameIsRefusedAndAFullNameRuns() {
        List<MansartDataCatalogue.Entity> twins = List.of(
                new MansartDataCatalogue.Entity("x.Gizmo", "x.Gizmo", "gizmos", List.of(), null),
                new MansartDataCatalogue.Entity("y.Gizmo", "y.Gizmo", "gizmos", List.of(), null));
        List<String> loaded = new ArrayList<>();
        JdqlActions tab = new JdqlActions(twins, className -> {
            loaded.add(className);
            return Gizmo.class;
        }, RunFixtures::model, RunFixtures.entities(), type -> RUNTIME, TransactionRunner.NONE, runner,
                new CallHistory(), JdqlActions.GROUP);

        ActionResult ambiguous = tab.call(false, Map.of("statement", "{\"query\":\"FROM Gizmo\"}"));
        ActionResult full = tab.call(false, Map.of("statement", "{\"query\":\"FROM y.Gizmo\"}"));

        assertEquals("entity Gizmo is ambiguous: x.Gizmo, y.Gizmo; use its full name", ambiguous.summary());
        assertFalse(full.error(), full.summary());
        assertEquals(List.of("y.Gizmo"), loaded);
        assertEquals(List.of("FROM y.Gizmo"), runner.queries);
    }

    @Test
    void eachCallIsKeptWithItsReplayInTheJdqlTab() {
        RepositoryActions actions = build();
        runner.answer = new JdqlResult.Count(3);
        query(actions, "{\"query\": \"FROM Gizmo WHERE stock > :min\", \"params\": \"{\\\"min\\\":2}\"}");
        run(actions, JdqlActions.WRITE, "{\"query\":\"UPDATE Gizmo SET stock = 0\"}", "rollback");

        Table calls = calls(actions);

        assertEquals(CallHistory.COLUMNS, calls.columns());
        List<String> write = calls.rows().get(0);
        List<String> read = calls.rows().get(1);
        assertEquals("Update / Delete", write.get(1));
        assertEquals("3 rows · rolled back", write.get(2));
        assertEquals("jdql.write {\"statement\":{\"query\":\"UPDATE Gizmo SET stock = 0\"},"
                + "\"transaction\":\"rollback\"}", write.get(5));
        assertEquals("Query", read.get(1));
        assertEquals("3", read.get(2));
        assertEquals("{\"query\":\"FROM Gizmo WHERE stock > :min\",\"params\":\"{\\\"min\\\":2}\"}", read.get(4),
                "the JSON sent, compact");
        assertEquals("jdql.query {\"statement\":{\"query\":\"FROM Gizmo WHERE stock > :min\","
                + "\"params\":\"{\\\"min\\\":2}\"}}", read.get(5));
    }

    @Test
    void aCatalogueWithoutEntityHasNoJdqlTab() {
        MansartDataCatalogue.Repository reports = new MansartDataCatalogue.Repository("ReportQueries",
                ReportQueries.class.getName(), null, null, null, List.of(), 1, "");
        MansartDataCatalogue noEntity = new MansartDataCatalogue(List.of(), List.of(reports), 0, 1, 1);

        List<String> ids = RepositoryActions.build(List.of(ReportQueries.class), noEntity, type -> RUNTIME,
                        TransactionRunner.NONE, RunFixtures::model, method -> true, RepositoryActions.MAX_ACTIONS,
                        runner)
                .actions().stream().map(PanelAction::id).toList();

        assertEquals(List.of("m.report-queries.gizmo-count"), ids);
    }

    @Test
    void theTabTakesItsTwoActionsFromThePanelsLimit() {
        List<String> ids = RepositoryActions.build(RunFixtures.REPOSITORIES, RunFixtures.catalogue(),
                        type -> RUNTIME, TransactionRunner.NONE, RunFixtures::model, method -> true, 5, runner)
                .actions().stream().map(PanelAction::id).toList();

        assertEquals(5, ids.size());
        assertEquals(List.of(JdqlActions.QUERY, JdqlActions.WRITE), ids.subList(3, 5));
    }

    @Test
    void anEntityClassIsLoadedThroughTheRepositoriesLoader() {
        assertSame(Gizmo.class, RepositoryActions.load(Gizmo.class.getName(), RunFixtures.REPOSITORIES));
    }
}
```
In `ResultJsonTest.java`, replace exactly:
```java
    @Test
    void anArrayIsRows() {
```
with:
```java
    @Test
    void aTableIsOneObjectPerRowByColumnCutAtOneHundred() {
        ResultJson.Result result = ResultJson.table(List.of("name", "stock"),
                List.of(new Object[] {"bolt", 3}, new Object[] {"nut", null}), entities);

        assertEquals("[{\"name\":\"bolt\",\"stock\":3},{\"name\":\"nut\",\"stock\":null}]", result.body());
        assertEquals("2 rows", result.what());
        assertTrue(result.rows());
        List<Object[]> many = LongStream.rangeClosed(1, 150).mapToObj(n -> new Object[] {n}).toList();
        ResultJson.Result first = ResultJson.table(List.of("id"), many, entities);
        assertEquals("first 100 rows", first.what());
        assertEquals(100, ((List<?>) Json.parse(first.body())).size());
        assertEquals("no row", ResultJson.table(List.of("id"), List.of(), entities).what());
        assertEquals("[{\"gizmo\":{\"id\":7,\"name\":\"bolt\",\"stock\":3,\"level\":\"LOW\",\"due\":null,"
                + "\"price\":null}}]", ResultJson.table(List.of("gizmo"), List.<Object[]>of(new Object[] {bolt(7)}),
                entities).body(), "an entity in a column is an object");
    }

    @Test
    void aCountIsWordedAsRows() {
        assertEquals("no row", ResultJson.count(0));
        assertEquals("1 row", ResultJson.count(1));
        assertEquals("4 rows", ResultJson.count(4));
    }

    @Test
    void anArrayIsRows() {
```

- [ ] **Step 2: Run them to see them fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest='JdqlActionsTest,ResultJsonTest' > "$SCRATCH/t4.log" 2>&1; grep -E "cannot find symbol|Tests run:|BUILD" "$SCRATCH/t4.log" | head -5
```
Expected: `COMPILATION ERROR`, `cannot find symbol` (`JdqlRunner`, `JdqlActions`, `ResultJson.table`).

- [ ] **Step 3: `ResultJson` — counts, tables, a value**

In `ResultJson.java`, replace exactly:
```java
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
```
with:
```java
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
```
Then replace exactly:
```java
        int n = kept.size();
        String what = more ? "first " + MAX_ROWS + " rows" : n == 0 ? "no row" : n == 1 ? "1 row" : n + " rows";
        return new Result(Json.write(json), what, true);
    }
```
with:
```java
        return new Result(Json.write(json), more ? "first " + MAX_ROWS + " rows" : count(kept.size()), true);
    }

    /** {@code no row}, {@code 1 row} or {@code N rows}. */
    static String count(long n) {
        return n == 0 ? "no row" : n == 1 ? "1 row" : n + " rows";
    }

    /**
     * The rows of a projection (JDQL console spec §3): an array of at most {@value #MAX_ROWS} objects, one member per
     * column in the columns' order, each value written as an element of a list is; a column named twice keeps its
     * last value.
     */
    static Result table(List<String> columns, List<Object[]> rows, EntityJson entities) {
        boolean more = rows.size() > MAX_ROWS;
        List<Object> json = new ArrayList<>();
        for (Object[] row : more ? rows.subList(0, MAX_ROWS) : rows) {
            Map<String, Object> object = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                object.put(columns.get(i), node(i < row.length ? row[i] : null, entities, 1));
            }
            json.add(object);
        }
        return new Result(Json.write(json), more ? "first " + MAX_ROWS + " rows" : count(json.size()), true);
    }

    /** {@code value} as JSON, as an element of a list is written: an entity as an object, a scalar as itself. */
    static Object node(Object value, EntityJson entities) {
        return node(value, entities, 1);
    }
```

- [ ] **Step 4: `JdqlRunner`**

Create `DEV/src/main/java/.../dev/JdqlRunner.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;

import java.util.Map;

/**
 * Runs one JDQL statement for the <i>JDQL</i> tab: Mansart's own {@link JdqlExecutor#run} outside tests
 * ({@link #MANSART}), a recording fake in them, so that the tab's tests need no database.
 */
@FunctionalInterface
interface JdqlRunner {

    /** {@link JdqlExecutor#run} on the {@link RepositoryRuntime} bean the call resolved. */
    JdqlRunner MANSART = (jdql, parameters, model, runtime) ->
            JdqlExecutor.run(jdql, parameters, model, (RepositoryRuntime) runtime);

    /**
     * Runs {@code jdql}, in whatever transaction the caller has begun.
     *
     * @param jdql       the statement, stripped
     * @param parameters its named parameters, by name; {@code null} values included
     * @param model      the model of the entity it names
     * @param runtime    the {@code RepositoryRuntime} bean
     * @return its result
     */
    JdqlResult run(String jdql, Map<String, Object> parameters, EntityModel<?> model, Object runtime);
}
```

- [ ] **Step 5: `JdqlActions`**

Create `DEV/src/main/java/.../dev/JdqlActions.java`:
```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The <i>JDQL</i> tab of the Mansart Data panel (JDQL console spec §3): two actions that run a JDQL statement typed in
 * the page, {@value #QUERY} for a read and {@value #WRITE} for an {@code UPDATE} or a {@code DELETE}, which asks first
 * and runs in a transaction rolled back unless {@code commit} is asked. Their json argument, {@value #STATEMENT}, holds
 * {@code query}, the statement, and {@code params}, its named parameters as a JSON object or the text of one.
 *
 * <p>A call reads the statement, refuses it to the wrong action, finds the entity it names among the catalogue's, its
 * model and the {@code RepositoryRuntime} bean — all before anything runs — then runs it through a {@link JdqlRunner},
 * for a write in a transaction, and turns its result into JSON inside that transaction. Its calls go into the panel's
 * {@link CallHistory}, with the repositories' calls, under this tab's title.
 */
final class JdqlActions {

    static final String GROUP = "JDQL";
    static final String QUERY = "jdql.query";
    static final String WRITE = "jdql.write";
    static final String QUERY_LABEL = "Query";
    static final String WRITE_LABEL = "Update / Delete";
    static final String STATEMENT = "statement";
    static final String CONFIRMATION = "Runs this JDQL statement against the database.";
    /** How many actions the tab adds to the panel's. */
    static final int COUNT = 2;

    private static final String QUERY_MEMBER = "query";
    private static final String PARAMS_MEMBER = "params";
    private static final System.Logger LOG = System.getLogger(JdqlActions.class.getName());

    /**
     * The statement of a call.
     *
     * @param query  its text, stripped
     * @param params its named parameters by name, in the order given
     */
    private record Statement(String query, Map<String, Object> params) {}

    private final List<MansartDataCatalogue.Entity> entities;
    private final Function<String, Class<?>> classes;
    private final Function<Class<?>, EntityModel<?>> models;
    private final EntityJson json;
    private final BeanLookup beans;
    private final TransactionRunner transactions;
    private final JdqlRunner runner;
    private final CallHistory history;
    private final String group;

    /**
     * @param entities     the catalogue's entities, those a statement may name
     * @param classes      the class of an entity by its full name, as the application loads it
     * @param models       the model of an entity class, {@code EntityModels.of} outside tests
     * @param json         how entities are written
     * @param beans        the beans, resolved at each call: the {@code RepositoryRuntime}
     * @param transactions the transactions of a write
     * @param runner       runs a statement, {@link JdqlRunner#MANSART} outside tests
     * @param history      the panel's history, which the repositories' actions write too
     * @param group        the tab's title, {@value #GROUP} unless a repository's tab has it already
     */
    JdqlActions(List<MansartDataCatalogue.Entity> entities, Function<String, Class<?>> classes,
                Function<Class<?>, EntityModel<?>> models, EntityJson json, BeanLookup beans,
                TransactionRunner transactions, JdqlRunner runner, CallHistory history, String group) {
        this.entities = List.copyOf(entities);
        this.classes = Objects.requireNonNull(classes, "classes");
        this.models = Objects.requireNonNull(models, "models");
        this.json = Objects.requireNonNull(json, "json");
        this.beans = Objects.requireNonNull(beans, "beans");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.history = Objects.requireNonNull(history, "history");
        this.group = Objects.requireNonNull(group, "group");
    }

    /** The two actions, {@value #QUERY} then {@value #WRITE}. */
    List<PanelAction> actions() {
        String names = entityNames();
        PanelAction.Argument statement = PanelAction.Argument.json(STATEMENT, "Statement", schema());
        PanelAction query = new PanelAction(QUERY, QUERY_LABEL, null, List.of(statement),
                given -> call(false, given), group, description("A JDQL query: FROM …, SELECT a, b FROM …, "
                        + "SELECT COUNT(this) FROM … or an aggregate such as SELECT MAX(price) FROM …; at most 100 "
                        + "rows are shown.", names));
        PanelAction write = new PanelAction(WRITE, WRITE_LABEL, CONFIRMATION, List.of(statement,
                new PanelAction.Argument(RepositoryActions.TRANSACTION, "Transaction", transactions.modes(), null,
                        null)), given -> call(true, given), group, description("A JDQL UPDATE … SET … or DELETE "
                        + "FROM …, rolled back unless commit is asked. JDQL has no INSERT: use a repository's save.",
                        names));
        return List.of(query, write);
    }

    /** The JSON Schema of {@value #STATEMENT}: {@code query} and {@code params}, each a field of several lines. */
    static String schema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "FROM Product WHERE price > :min ORDER BY name"),
                        PARAMS_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "the named parameters, a JSON object: {\"min\": 3}")),
                "required", List.of(QUERY_MEMBER)));
    }

    private static String description(String what, String names) {
        return Failures.cut(what + "\nA named parameter, :name, takes the member name of params, converted to the "
                + "type of the attribute it is compared to.\nEntities: " + names, PanelAction.MAX_DESCRIPTION - 1);
    }

    /** Runs a statement with the arguments the console checked, and keeps the call. */
    PanelAction.ActionResult call(boolean write, Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(STATEMENT, "{}");
        String mode = write ? given.getOrDefault(RepositoryActions.TRANSACTION, transactions.modes().getFirst())
                : null;
        Object parsed = null;
        Statement statement = null;
        Class<?> entity = null;
        PanelAction.ActionResult result;
        try {
            parsed = parse(sent);
            statement = statement(parsed);
            refuseTheWrongAction(write, statement.query());
            entity = entity(statement.query());
            EntityModel<?> model = model(entity);
            Object runtime = runtime();
            result = run(write, statement, model, runtime, mode, start, details(entity, statement, mode));
        } catch (Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    details(entity, statement, mode));
        }
        history.add(group, System.currentTimeMillis(), write ? WRITE_LABEL : QUERY_LABEL,
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed), replay(write, parsed, mode));
        return result;
    }

    private PanelAction.ActionResult run(boolean write, Statement statement, EntityModel<?> model, Object runtime,
                                         String mode, long start, String details) {
        TransactionRunner.Outcome<ResultJson.Result> outcome = transactions.run(mode,
                () -> answer(runner.run(statement.query(), statement.params(), model, runtime), write, json));
        if (outcome.failure() != null) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + (write ? WRITE : QUERY) + " failed: "
                    + outcome.failure().getClass().getName());
            String text = Failures.text(outcome.failure());
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        ResultJson.Result value = outcome.value();
        String summary = value.what() + (value.rows() ? " in " + millis(start) + " ms" : "")
                + (outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, PanelAction.ActionResult.JSON, value.body(), false, details);
    }

    /**
     * What a statement's result shows (spec §3): entities and rows as arrays of at most {@value ResultJson#MAX_ROWS}
     * objects, a count as its number — a write's as {@code N rows} —, an aggregate as its value.
     */
    static ResultJson.Result answer(JdqlResult result, boolean write, EntityJson entities) {
        return switch (result) {
            case JdqlResult.Entities found -> ResultJson.of(found.entities(), false, entities);
            case JdqlResult.Rows rows -> ResultJson.table(rows.columns(), rows.rows(), entities);
            case JdqlResult.Count count -> new ResultJson.Result(Long.toString(count.count()),
                    write ? ResultJson.count(count.count()) : Long.toString(count.count()), false);
            case JdqlResult.Value value -> {
                Object node = ResultJson.node(value.value(), entities);
                yield new ResultJson.Result(Json.write(node), String.valueOf(node), false);
            }
        };
    }

    private static Object parse(String sent) throws Refused {
        try {
            return Json.parse(sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(STATEMENT + ": " + unreadable.getMessage());
        }
    }

    /** The statement's members: {@code query}, a text, and {@code params}, optional; nothing else. */
    private static Statement statement(Object parsed) throws Refused {
        if (!(parsed instanceof Map<?, ?> members)) {
            throw new Refused(STATEMENT + ": not a JSON object");
        }
        for (Object name : members.keySet()) {
            if (!QUERY_MEMBER.equals(name) && !PARAMS_MEMBER.equals(name)) {
                throw new Refused(name + ": unknown argument");
            }
        }
        Object query = members.get(QUERY_MEMBER);
        if (query != null && !(query instanceof String)) {
            throw new Refused(QUERY_MEMBER + ": not a string");
        }
        if (query == null || ((String) query).isBlank()) {
            throw new Refused(QUERY_MEMBER + ": missing");
        }
        return new Statement(((String) query).strip(), params(members.get(PARAMS_MEMBER)));
    }

    /** {@code params}: absent, blank, a JSON object or the text of one; each member a value or a list of values. */
    private static Map<String, Object> params(Object given) throws Refused {
        Object value = given;
        if (value instanceof String text) {
            if (text.isBlank()) {
                return Map.of();
            }
            try {
                value = Json.parse(text);
            } catch (IllegalArgumentException unreadable) {
                throw new Refused(PARAMS_MEMBER + ": " + unreadable.getMessage());
            }
        }
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> members)) {
            throw new Refused(PARAMS_MEMBER + ": not a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> member : members.entrySet()) {
            Object parameter = member.getValue();
            boolean nested = parameter instanceof Map<?, ?> || parameter instanceof List<?> list
                    && list.stream().anyMatch(element -> element instanceof Map<?, ?> || element instanceof List<?>);
            if (nested) {
                throw new Refused(PARAMS_MEMBER + "." + member.getKey() + ": not a value or a list of values");
            }
            out.put(String.valueOf(member.getKey()), parameter);
        }
        return out;
    }

    /** A query in the write action, or a write in the query action, never runs. */
    private static void refuseTheWrongAction(boolean write, String query) throws Refused {
        boolean isWrite = JdqlExecutor.isWrite(query);
        if (write && !isWrite) {
            throw new Refused("not an UPDATE or DELETE: use Query");
        }
        if (!write && isWrite) {
            throw new Refused("an UPDATE or DELETE: use Update / Delete");
        }
    }

    /** The class of the entity {@code query} names: by simple class name, then by full class name. */
    private Class<?> entity(String query) throws Refused {
        Optional<String> named = JdqlExecutor.target(query);
        if (named.isEmpty()) {
            throw new Refused("no entity: name it, FROM <Entity>, UPDATE <Entity> or DELETE FROM <Entity>");
        }
        String name = named.get();
        List<MansartDataCatalogue.Entity> found = entities.stream()
                .filter(entity -> simpleName(entity.className()).equals(name)).toList();
        if (found.isEmpty()) {
            found = entities.stream().filter(entity -> entity.className().equals(name)).toList();
        }
        if (found.isEmpty()) {
            throw new Refused("unknown entity " + name + "; entities: " + entityNames());
        }
        if (found.size() > 1) {
            throw new Refused("entity " + name + " is ambiguous: " + found.stream()
                    .map(MansartDataCatalogue.Entity::className).collect(Collectors.joining(", "))
                    + "; use its full name");
        }
        try {
            return classes.apply(found.getFirst().className());
        } catch (RuntimeException | LinkageError missing) {
            throw new Refused("entity " + name + ": " + Failures.text(missing));
        }
    }

    private EntityModel<?> model(Class<?> entity) throws Refused {
        try {
            EntityModel<?> model = models.apply(entity);
            if (model == null) {
                throw new Refused("no model of " + entity.getName());
            }
            return model;
        } catch (RuntimeException | LinkageError unreadable) {
            throw new Refused(Failures.text(unreadable));
        }
    }

    /** The default {@code RepositoryRuntime} bean, resolved now: the data store of the {@code @Default} datasource. */
    private Object runtime() throws Refused {
        try {
            return beans.reference(RepositoryRuntime.class);
        } catch (RuntimeException | LinkageError none) {
            throw new Refused("no RepositoryRuntime bean");
        }
    }

    /** The catalogue's names of its entities, in alphabetical order. */
    private String entityNames() {
        return entities.stream().map(MansartDataCatalogue.Entity::name).sorted().collect(Collectors.joining(", "));
    }

    /** {@code io.x.Outer$Gizmo} → {@code Gizmo}: the name a statement uses. */
    private static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }

    /** What the page shows under "Exchange": the entity, the statement, its parameters, the transaction asked. */
    private static String details(Class<?> entity, Statement statement, String mode) {
        return Json.write(Scalars.object("entity", entity == null ? null : entity.getName(),
                "query", statement == null ? null : statement.query(),
                "params", statement == null ? Map.of() : statement.params(),
                "transaction", mode == null ? "none" : mode));
    }

    /** {@code <action id> {"statement": {...}, "transaction": "..."}}, or empty when the statement was no object. */
    private static String replay(boolean write, Object parsed, String mode) {
        if (!(parsed instanceof Map<?, ?>)) {
            return "";
        }
        Map<String, Object> values = write
                ? Scalars.object(STATEMENT, parsed, RepositoryActions.TRANSACTION, mode)
                : Scalars.object(STATEMENT, parsed);
        return (write ? WRITE : QUERY) + " " + Json.write(values);
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** A call refused before the statement runs: its message is the summary. */
    private static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
```

- [ ] **Step 6: `RepositoryActions` builds the tab**

In `RepositoryActions.java`, replace exactly:
```java
 * <p>Holds the application's classes and bean manager for one boot: the panel drops it in {@code stop}.
```
with:
```java
 * <p>After the repositories' tabs comes the <i>JDQL</i> tab of {@link JdqlActions}, whose calls the same history keeps.
 *
 * <p>Holds the application's classes and bean manager for one boot: the panel drops it in {@code stop}.
```
Then replace exactly:
```java
     * @param maxActions   the most actions, {@value #MAX_ACTIONS}; the rest is counted as {@code more-methods}
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions) {
        Set<String> entityNames = new HashSet<>();
```
with:
```java
     * @param maxActions   the most actions, {@value #MAX_ACTIONS}; the rest is counted as {@code more-methods}
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions) {
        return build(repositories, catalogue, beans, transactions, models, accessible, maxActions, null);
    }

    /**
     * The actions of {@code repositories}, in the catalogue's name order, then, when {@code jdql} is given and the
     * catalogue holds an entity, the {@value JdqlActions#COUNT} actions of the <i>JDQL</i> tab, which keep their calls
     * in the same history; the repositories then get {@code maxActions} less those.
     *
     * @param jdql runs a JDQL statement, {@link JdqlRunner#MANSART} outside tests; {@code null} for no JDQL tab
     * @see #build(List, MansartDataCatalogue, BeanLookup, TransactionRunner, Function, Predicate, int)
     */
    static RepositoryActions build(List<Class<?>> repositories, MansartDataCatalogue catalogue, BeanLookup beans,
                                   TransactionRunner transactions, Function<Class<?>, EntityModel<?>> models,
                                   Predicate<Method> accessible, int maxActions, JdqlRunner jdql) {
        Set<String> entityNames = new HashSet<>();
```
Then replace exactly:
```java
        Set<String> keys = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (Class<?> repository : ordered) {
            String name = names.getOrDefault(repository.getName(), repository.getName());
            built.add(repository, name, CatalogueLivePanel.key(name, keys, MAX_REPOSITORY_KEY), group(name, groups),
                    accessible, maxActions);
        }
        return built;
    }
```
with:
```java
        Set<String> keys = new HashSet<>();
        Set<String> groups = new HashSet<>();
        boolean withJdql = jdql != null && !catalogue.entities().isEmpty();
        int room = withJdql ? maxActions - JdqlActions.COUNT : maxActions;
        for (Class<?> repository : ordered) {
            String name = names.getOrDefault(repository.getName(), repository.getName());
            built.add(repository, name, CatalogueLivePanel.key(name, keys, MAX_REPOSITORY_KEY), group(name, groups),
                    accessible, room);
        }
        if (withJdql) {
            JdqlActions tab = new JdqlActions(catalogue.entities(), className -> load(className, repositories),
                    models, built.entities, beans, transactions, jdql, built.history,
                    group(JdqlActions.GROUP, groups));
            built.actions.addAll(tab.actions());
        }
        return built;
    }

    /**
     * The class {@code className} as the application loads it: through the loader of the first repository that finds
     * it, an entity living with its repositories.
     *
     * @throws IllegalStateException when none does
     */
    static Class<?> load(String className, List<Class<?>> repositories) {
        for (Class<?> repository : repositories) {
            try {
                return Class.forName(className, false, repository.getClassLoader());
            } catch (ClassNotFoundException | LinkageError notThere) {
                // the next repository's loader may know it
            }
        }
        throw new IllegalStateException("class " + className + " not found");
    }
```

- [ ] **Step 7: Run the module's tests**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/t4.log" 2>&1; grep -E "Tests run:|FAIL|expected|BUILD" "$SCRATCH/t4.log" | tail -20
```
Expected: `BUILD SUCCESS`; `JdqlActionsTest` 17 tests and `ResultJsonTest` 2 more, no failure; every other test of
the module unchanged (`RepositoryActionsTest` builds without a runner, so without a JDQL tab; `CatalogueLivePanelTest`
still counts 23 actions until Task 5 wires the runner).

- [ ] **Step 8: Commit**

Message (`V/.git/PLAN_COMMIT_MSG`):
```
feat(mansart-data): the JDQL actions of the dev panel

JdqlActions adds a JDQL tab after the repositories' tabs: Query runs a
FROM, a projection, a count or an aggregate; Update / Delete runs an
UPDATE or a DELETE, confirmed, in a transaction rolled back unless commit
is asked. The statement and its named parameters are one JSON argument.
The entity is looked up among the catalogue's, the RepositoryRuntime bean
resolved at the call, a wrong action, an unknown entity or a malformed
statement refused before anything runs. Results go through ResultJson
(new: tables of projections, counts worded as rows), failures through
Failures, calls into the panel's history with a replay. A JdqlRunner seam
runs Mansart's JdqlExecutor.run, or a fake in the tests.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
D=vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev
M=$D/src/main/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
T=$D/src/test/java/io/vidocq/runtime/extensions/jakartaee/web/mansart/data/dev
git add $M/ResultJson.java $M/JdqlRunner.java $M/JdqlActions.java $M/RepositoryActions.java $T/JdqlActionsTest.java $T/ResultJsonTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: DEV — the panel offers the JDQL tab

**Files:**
- Modify: `DEV/src/main/java/.../dev/CatalogueLivePanel.java`
- Test: `DEV/src/test/java/.../dev/CatalogueLivePanelTest.java`

**Interfaces:**
- Consumes: Task 4's `RepositoryActions.build(…, int, JdqlRunner)`, `JdqlRunner.MANSART`.
- Produces: `CatalogueLivePanel.actions()` and `actions(BeanLookup, TransactionRunner, Function, Predicate)` return
  the repositories' actions then `jdql.query`, `jdql.write`; the DEV module installed in `~/.m2`.

- [ ] **Step 1: Write the failing test**

In `CatalogueLivePanelTest.java`, replace exactly:
```java
        assertEquals(23, actions.size());
```
with:
```java
        assertEquals(25, actions.size());
        assertEquals(List.of("jdql.query", "jdql.write"),
                actions.subList(23, 25).stream().map(PanelAction::id).toList(), "the JDQL tab, last");
        assertEquals(List.of("commit"), actions.get(24).arguments().get(1).allowedValues(),
                "no TransactionManager bean");
        assertEquals("no RepositoryRuntime bean",
                actions.get(23).call().apply(Map.of("statement", "{\"query\":\"FROM Gizmo\"}")).summary());
```

- [ ] **Step 2: Run it to see it fail**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu test -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev -Dtest=CatalogueLivePanelTest > "$SCRATCH/t5.log" 2>&1; grep -E "Tests run:|expected|BUILD" "$SCRATCH/t5.log" | tail -5
```
Expected: `Failures: 1`, `expected: <25> but was: <23>`.

- [ ] **Step 3: Wire the runner**

In `CatalogueLivePanel.java`, replace exactly:
```java
            RepositoryActions built = RepositoryActions.build(repositories, catalogue.get(), lookup, transactions,
                    models, accessible, RepositoryActions.MAX_ACTIONS);
```
with:
```java
            RepositoryActions built = RepositoryActions.build(repositories, catalogue.get(), lookup, transactions,
                    models, accessible, RepositoryActions.MAX_ACTIONS, JdqlRunner.MANSART);
```
Then replace exactly:
```java
 * <p>Its actions run the repositories' methods, one tab per repository (see {@link RepositoryActions}): built once
```
with:
```java
 * <p>Its actions run the repositories' methods, one tab per repository (see {@link RepositoryActions}), then JDQL
 * statements in a <i>JDQL</i> tab (see {@link JdqlActions}, on Mansart's {@code JdqlExecutor.run}): built once
```

- [ ] **Step 4: Run the module's tests, install it**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/t5.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/t5.log" | tail -5
```
Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 5: Commit**

Message (`V/.git/PLAN_COMMIT_MSG`):
```
feat(mansart-data): the dev panel offers the JDQL tab

CatalogueLivePanel builds its actions with Mansart's JdqlExecutor.run
behind the JdqlRunner seam: after the repositories' tabs comes the JDQL
tab, Query and Update / Delete, its RepositoryRuntime and its
TransactionManager resolved at the call from the panel's bean manager.

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

### Task 6: Against a database — the mansart-h2 example (spec §5)

**Files:**
- Modify: `EX/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java`

**Interfaces:**
- Consumes: Tasks 2-5 installed (`~/.m2`): Mansart's `run`, the DEV module's JDQL tab.
- Produces: `DevConsoleSnapshotTest.runsJdqlFromTheConsole`, posting `mansart-data/jdql.query` and
  `mansart-data/jdql.write` over HTTP to the console of a dev boot on in-memory H2 (products `Espresso` 2.50,
  `Cappuccino` 3.50, `Latte` 4.00, seeded by `V1__products.sql`).

- [ ] **Step 1: The test**

In `DevConsoleSnapshotTest.java`, replace exactly:
```java
    /** Runs {@code m.product-repository.<method>} with these arguments; its answer, which must be no error. */
```
with:
```java
    /**
     * Runs JDQL from the Mansart Data panel's JDQL tab against the in-memory H2 database, as a developer does from its
     * page: a query with a parameter given as a JSON number, then an UPDATE of every product rolled back, which leaves
     * every price as it was.
     */
    @Test
    void runsJdqlFromTheConsole() throws Exception {
        List<?> actions = (List<?>) panel("mansart-data").get("actions");
        Map<?, ?> query = actions.stream().map(a -> (Map<?, ?>) a)
                .filter(a -> "jdql.query".equals(a.get("id"))).findFirst()
                .orElseGet(() -> fail("no JDQL query action in " + actions));
        assertEquals("JDQL", query.get("group"));
        String token = (String) ((Map<?, ?>) snapshot.get("console")).get("actionToken");

        Map<?, ?> dear = runJdql(token, "query",
                "{\"query\":\"FROM Product WHERE price > :min ORDER BY name\",\"params\":{\"min\":3}}", null);
        assertTrue(((String) dear.get("result")).matches("2 rows in \\d+ ms"), "result: " + dear);
        assertEquals(List.of("Cappuccino", "Latte"),
                rows((String) dear.get("body")).stream().map(r -> ((Map<?, ?>) r).get("name")).toList());

        long products = Long.parseLong((String) runJdql(token, "query",
                "{\"query\":\"SELECT COUNT(this) FROM Product\"}", null).get("result"));
        assertEquals(products + (products == 1 ? " row" : " rows") + " · rolled back", runJdql(token, "write",
                "{\"query\":\"UPDATE Product SET price = price * 2\"}", "rollback").get("result"));
        Map<?, ?> espresso = runJdql(token, "query", "{\"query\":\"FROM Product WHERE name = 'Espresso'\"}", null);
        assertEquals(2.5, ((Number) ((Map<?, ?>) rows((String) espresso.get("body")).getFirst()).get("price"))
                .doubleValue(), "the UPDATE was rolled back");
    }

    /** Runs {@code jdql.<action>} with this statement; its answer, which must be no error. */
    private static Map<?, ?> runJdql(String token, String action, String statement, String transaction)
            throws Exception {
        String body = "{\"statement\":\"" + statement.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (transaction == null ? "" : ",\"transaction\":\"" + transaction + "\"") + "}";
        Map<?, ?> answer = json(postAction("mansart-data/jdql." + action, token, body));
        assertNotEquals(Boolean.TRUE, answer.get("error"), action + ": " + answer);
        return answer;
    }

    private static List<?> rows(String text) throws Exception {
        try (Jsonb jsonb = JsonbBuilder.create()) {
            return (List<?>) jsonb.fromJson(text, Object.class);
        }
    }

    /** Runs {@code m.product-repository.<method>} with these arguments; its answer, which must be no error. */
```

- [ ] **Step 2: Run it**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example -Dtest=DevConsoleSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false > "$SCRATCH/t6.log" 2>&1; grep -E "Tests run:|FAIL|expected|BUILD" "$SCRATCH/t6.log" | tail -10
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, `DevConsoleSnapshotTest` with no failure. If the JDQL action is
missing, check that Tasks 2 and 5 installed their jars (`ls -l ~/.m2/repository/io/vidocq/mansart/mansart-data-core/0.4.0-SNAPSHOT/`
and the DEV jar's date); if `runsJdqlFromTheConsole` fails on the answer, its `result` line says why.

- [ ] **Step 3: Commit**

Message (`V/.git/PLAN_COMMIT_MSG`):
```
test(mansart-h2): JDQL from the dev console against H2

DevConsoleSnapshotTest now also runs the Mansart Data panel's JDQL tab in
the dev boot of the example: a query whose parameter comes as a JSON
number, a count, and an UPDATE of every product rolled back, after which
the prices are what they were.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
git add vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/src/test/java/io/vidocq/runtime/examples/mansart/DevConsoleSnapshotTest.java
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: Documentation (spec §6)

**Files:**
- Modify: `V/docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc` (new section before
  `[#mansart-transactions-jdbc-bridge]`)
- Modify: `V/docs/en/modules/ROOT/pages/whats-new.adoc` (one entry)

(Mansart's Javadoc was Task 2's, the page's rule Task 3's.)

**Interfaces:**
- Consumes: the behaviour of Tasks 2-5 as the Rulings state it.
- Produces: the anchor `mansart-data-jdql`, which `dev-console-panels.adoc` (Task 3) links to.

- [ ] **Step 1: The section**

In `modules/vidocq-runtime-extensions.adoc`, replace exactly:
```
[#mansart-transactions-jdbc-bridge]
== Mansart Data writes join the transaction [.tag-new]#NEW#
```
with:
````
[#mansart-data-jdql]
== A JDQL console for Mansart Data [.tag-new]#NEW#

Under `mvn vidocq:dev`, the *Mansart Data* tab of the xref:dev-console.adoc[dev console] ends with a *JDQL* tab, which runs a JDQL statement you type against the application's database: a query to look at data, an `UPDATE` or a `DELETE` to try a change, then roll it back or keep it. Its list offers two actions.

* *Query* runs a `FROM …`, a projection `SELECT a, b FROM …`, a `SELECT COUNT(this) FROM …` or an aggregate such as `SELECT MAX(price) FROM …`, without asking first. An `UPDATE` or a `DELETE` is refused there: `an UPDATE or DELETE: use Update / Delete`.
* *Update / Delete* runs an `UPDATE … SET …` or a `DELETE FROM …`, and nothing else (`not an UPDATE or DELETE: use Query`). The page asks first (`Runs this JDQL statement against the database.`), and its *Transaction* list offers `rollback`, the default, then `commit`, as a repository method's write does (<<mansart-data-run-method>>): the statement runs in a transaction of the application's `TransactionManager`, rolled back unless `commit` is asked, and always rolled back when it fails. Without a `TransactionManager`, `commit` only.

The form has two fields of several lines: `query`, the statement, and `params`, its named parameters as a JSON object.

[source,text]
----
FROM Task WHERE status = :status ORDER BY dueDate           params: {"status": "OPEN"}
SELECT title, priority FROM Task WHERE project = :project   params: {"project": "vidocq"}
SELECT COUNT(this) FROM Task WHERE dueDate < :day           params: {"day": "2026-10-01"}
UPDATE Task SET priority = :priority WHERE project = :p     params: {"priority": "HIGH", "p": "vidocq"}
DELETE FROM TaskEvent WHERE type = 'REOPENED'
----

*The entity.* A statement names its entity after `FROM`, `UPDATE` or `DELETE FROM`, by its simple name, or by its full class name when two entities share a simple name, as the catalogue then shows them. A name the catalogue does not know is refused, with the names it knows: `unknown entity Nope; entities: Task, TaskEvent`.

*Parameters.* A named parameter `:name` takes the member `name` of `params`; a parameter with no value, or a member no parameter uses, is refused (`:status: no value given`), and so is a positional `?1`. A value compared to an attribute — a parameter, a literal such as `'OPEN'` or `'2026-09-29'`, a value of `SET` — is converted to the attribute's Java type: an enum by the name of a constant, a date or a time as ISO text, a number exactly (`2.5` for an `int` is refused), a `Boolean` from `true` or `false`, a `UUID` from its text. A JSON array feeds an `IN :names`. A value that does not convert fails the statement before it runs: `:status: not a TaskStatus`.

*Results.* Through the page's JSON viewer: the entities as objects, as a repository method returns them (<<mansart-data-run-method>>); a projection as one object per row, `{"title": "Release notes", "priority": "HIGH"}`; at most 100 rows, the line saying `3 rows in 12 ms` or `first 100 rows in 40 ms`. A count is its number, `42`; an aggregate its value, `null` when there is no row; a write the rows it changed, `4 rows · rolled back`. *Exchange* holds the entity, the statement, its parameters and the transaction asked. A statement that does not parse, or fails, shows the class of the exception and its message, cut after 500 characters and with a `user:password@` masked.

*History.* The *JDQL* tab lists its last 20 statements, *Query* or *Update / Delete*, newest first, each with *Replay*, which fills the form again without sending it.

*Limits.* JDQL has no `INSERT`: insert with a repository's `save`, or an `@Insert` method (<<mansart-data-run-method>>). A statement runs on the default data store, the `RepositoryRuntime` bean of the `@Default` datasource: a repository routed elsewhere with `@Repository(dataStore = …)` is not reached. Paths such as `author.name` go as far as Mansart's JDQL does. The whole result is read before the first 100 rows are shown: narrow a large table with a `WHERE`. The console runs Mansart's own `JdqlExecutor.run(jdql, parameters, model, runtime)`, which a tool of yours may call too.

[#mansart-transactions-jdbc-bridge]
== Mansart Data writes join the transaction [.tag-new]#NEW#
````

- [ ] **Step 2: What's new**

In `whats-new.adoc`, replace exactly (the start of line 34; the rest of that line stays as it is):
```
* **Run a Mansart Data repository method from the dev console** [.tag-new]#NEW#
```
with:
```
* **A JDQL console for Mansart Data** [.tag-new]#NEW# — under `mvn vidocq:dev`, the *Mansart Data* tab ends with a *JDQL* tab that runs a statement you type against the application's database: *Query* for a `FROM`, a projection, a count or an aggregate, *Update / Delete* for a write, asked first and rolled back unless you commit it; named parameters as a JSON object, converted to the attributes' types; the result as JSON, with the last 20 statements and a replay. Mansart gains `JdqlExecutor.run` for it, and a JSON argument's form now shows a string of `"format": "textarea"` as a field of several lines. xref:modules/vidocq-runtime-extensions.adoc#mansart-data-jdql[A JDQL console for Mansart Data].
* **Run a Mansart Data repository method from the dev console** [.tag-new]#NEW#
```

- [ ] **Step 3: Check the anchors**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq/docs/en/modules/ROOT/pages && grep -c '^\[#mansart-data-jdql\]' modules/vidocq-runtime-extensions.adoc && grep -c 'vidocq-runtime-extensions.adoc#mansart-data-jdql' whats-new.adoc dev-console-panels.adoc
```
Expected: `1`, then `whats-new.adoc:1` and `dev-console-panels.adoc:1`.

- [ ] **Step 4: Commit**

Message (`V/.git/PLAN_COMMIT_MSG`):
```
docs(mansart-data): the JDQL console of the dev panel

A section on the JDQL tab: its two actions, the entity a statement names,
the parameters and their conversion, the results, the history, and the
limits -- no INSERT in JDQL, the default data store only. And its entry in
what's new.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
git add docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc docs/en/modules/ROOT/pages/whats-new.adoc
git commit -S -F .git/PLAN_COMMIT_MSG && rm .git/PLAN_COMMIT_MSG
```

---

### Task 8: Verification — builds, consumers, and the JDQL tab in Chrome (spec §5)

**Files:** none changed, unless a check fails (then fix in the owning task's files, re-run its tests, commit with a
`fix(...)` message following the Global Constraints; a Mansart fix goes in the worktree and is installed again with
Task 2 Step 7).

**Interfaces:**
- Consumes: everything above; the script
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh`
  (`dev-run.sh <project dir> <log>`: app on 18093, console on 18094).

- [ ] **Step 1: Install the touched Vidocq modules**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu install -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-data-extension-dev > "$SCRATCH/install.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/install.log" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 2: The consumers**

```bash
export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH; export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad; cd /Users/yblazart/projects/perso/vidocq/vidocq && mvn -nsu verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example > "$SCRATCH/h2.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/h2.log" | tail -6; mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-petstore-example > "$SCRATCH/petstore.log" 2>&1; grep -E "Tests run:|FAIL|BUILD" "$SCRATCH/petstore.log" | tail -6
```
(`timeout: 600000`; `clean` for the petstore, whose generated sources may be stale.) Expected: `BUILD SUCCESS` for
both, no failure.

- [ ] **Step 3: Free ports**

```bash
lsof -nP -iTCP:18093 -sTCP:LISTEN; lsof -nP -iTCP:18094 -sTCP:LISTEN; echo checked
```
Expected: only `checked`. If a port is taken, STOP and ask the user (never kill what this plan did not start).

- [ ] **Step 4: Note the test app's state, start `vidocq:dev`**

```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short > "$SCRATCH/lc4jcdi-status-before.txt"; cat "$SCRATCH/lc4jcdi-status-before.txt"
```
Then run with the Bash tool and `run_in_background: true` (the only background Maven run of this plan):
```bash
bash /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log
```
Wait with the Monitor tool on an until-loop (never a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" "$SCRATCH/mcp-tasks-dev.log"; do sleep 2; done`.
Then `grep -E "Dev tools:|Mansart Data:|MANSART-DATA|BUILD FAILURE" "$SCRATCH/mcp-tasks-dev.log" | head` — expected
`vidocq-runtime-mansart-data-extension-dev` among the dev tools, no `MANSART-DATA-001`, no failure.

- [ ] **Step 5: The JDQL tab in Chrome (spec §5)**

Load the tools in one call:
`ToolSearch("select:mcp__claude-in-chrome__tabs_context_mcp,mcp__claude-in-chrome__navigate,mcp__claude-in-chrome__computer,mcp__claude-in-chrome__read_page,mcp__claude-in-chrome__tabs_create_mcp,mcp__claude-in-chrome__tabs_close_mcp,mcp__claude-in-chrome__javascript_tool,mcp__claude-in-chrome__get_page_text,mcp__claude-in-chrome__find,mcp__claude-in-chrome__form_input")`.

1. `tabs_context_mcp`, `tabs_create_mcp`, `navigate` to `http://127.0.0.1:18094/`.
2. Before relying on polling, run with `javascript_tool`:
   `Object.defineProperty(document, 'hidden', {value: false, configurable: true}); Object.defineProperty(document, 'visibilityState', {value: 'visible', configurable: true}); document.dispatchEvent(new Event('visibilitychange')); 'visible'`.
3. Open *Mansart Data*: the sub-tabs end with *JDQL*, after `TaskEventRepository` and `TaskRepository`. Open it: the
   list offers *Query* and *Update / Delete*; the form shows `query *` and `params` as **textareas** of four lines,
   monospace, resizable (drag one taller). Screenshot.
4. *Query*, `query` `FROM Task WHERE status = :status ORDER BY id`, `params` `{"status": "OPEN"}`, run:
   `N rows in X ms`, the viewer shows tasks whose `status` is `OPEN` (an enum parameter converted).
5. *Query* `SELECT title, priority FROM Task WHERE project = :p ORDER BY title`, `params` `{"p": "lc4jcdi"}`: rows
   as objects `{"title": …, "priority": …}` (a projection).
6. *Query* `SELECT COUNT(this) FROM Task WHERE priority = :p`, `params` `{"p": "HIGH"}`: the number `H` (a count).
   Note `H`.
7. *Update / Delete*, `query` `UPDATE Task SET priority = :p WHERE status = :s`, `params`
   `{"p": "HIGH", "s": "OPEN"}`, *Transaction* **`rollback`** (never `commit`); the page asks
   `Runs this JDQL statement against the database.`; confirm: `M rows · rolled back`. Run step 6 again: still `H` —
   the UPDATE left nothing. If the *Transaction* list offers `commit` only, STOP (do not run the write) and debug
   with superpowers:systematic-debugging: the DEV module does not see the application's `TransactionManager`.
8. *Query* `FROM Nope`: the error `unknown entity Nope; entities: Task, TaskEvent`.
9. *Query* `FROM Task WHERE`: an error line `io.vidocq.mansart.data.core.MansartDataException: …` (a syntax error),
   no password anywhere.
10. The JDQL tab's history lists these calls newest first, *Query* and *Update / Delete*; click *Replay* on the count
    row of step 6: the form is filled with that query and its params, and nothing is sent (no new history row).
    Screenshot.
11. `tabs_close_mcp` on the tab this step opened.

- [ ] **Step 6: Stop what this plan started**

Stop the background task of Step 4 with `TaskStop` (its id), then re-run the command of Step 3: both ports free.
```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short | diff - "$SCRATCH/lc4jcdi-status-before.txt" && echo "test app untouched"
```
Expected: `test app untouched`.

- [ ] **Step 7: Final state**

```bash
export SCRATCH=/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad
git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short
git -C /Users/yblazart/projects/perso/vidocq/vidocq log --oneline -8
git -C /Users/yblazart/projects/perso/vidocq/mansart branch --show-current
git -C /Users/yblazart/projects/perso/vidocq/mansart status --porcelain=v1 | diff - "$SCRATCH/mansart-status-before.txt" && echo "user checkout untouched"
git -C /Users/yblazart/projects/perso/vidocq/mansart-jdql-run log --oneline -3
```
Expected: the Vidocq tree clean with this plan's commits on `feat/mansart-data-jdql-console`; Mansart's checkout still
on `ybl/opencode-3`, untouched; the worktree's `feat/jdql-run` holding the two Mansart commits. Nothing pushed.
Report to the controller: the two branches to push, the screenshots, and that the manual check committed nothing.

---

## Self-review (done while writing)

- **Spec coverage.** §1: the Mansart API (Tasks 1-2), the two actions (Tasks 4-5), the textarea (Task 3); out of
  scope respected (no INSERT, default data store, first page). §2: `run`, `isWrite`, `target`, `JdqlResult` (Task 2),
  parameters and conversion (Tasks 1-2, Rulings 3-4), the `@Query` path refactored with its tests green (Task 1
  Step 6, Task 2 Step 7), limits (Task 2 Javadoc), the worktree (Task 1). §3: ids, labels, group, argument,
  confirmation, transaction, refusals, entity lookup, model, runtime bean, results and summaries, details, errors,
  history with replay (Task 4; wiring Task 5). §4: page rule, `PageTest`, docs (Task 3). §5: Mansart tests (Task 2),
  `-dev` tests (Task 4-5), mansart-h2 (Task 6), Chrome (Task 8). §6: Javadoc (Task 2), docs (Tasks 3, 7). §7: nothing
  to build.
- **Types.** `CallShape(Class<?>, Class<?>, Map<String, Integer>, boolean)`, `execute(Stmt, CallShape, EntityModel,
  Map, RepositoryRuntime, Object[])`, `JdqlValues.convert(Object, Class<?>, String)`, `run(String, Map<String, ?>,
  EntityModel<?>, RepositoryRuntime)`, `JdqlRunner.run(String, Map<String, Object>, EntityModel<?>, Object)`,
  `JdqlActions(List, Function, Function, EntityJson, BeanLookup, TransactionRunner, JdqlRunner, CallHistory,
  String)`, `RepositoryActions.build(…, int, JdqlRunner)`, `ResultJson.count/table/node` are used with the same
  signatures in every task.
- **Placeholders.** None: every code step carries its code, every command its expected output.

