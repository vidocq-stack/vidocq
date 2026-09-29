# The PostgreSQL dev service starts only for PostgreSQL — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Apply the fenced code blocks of a step in order; a block that starts with `package` is a whole new file (prepend the license header of the Global Constraints); an edit is shown as the exact text to find, then the text that replaces it. Every find-block was copied from the file as it is on `feat/devservice-postgres-kind` before this plan: if one does not match, STOP and re-read the file rather than guess.

**Goal:** Under `vidocq:dev`, `vidocq:run`, `vidocq:test` and a JUnit run, the PostgreSQL dev service starts a
container only for an application that is on PostgreSQL — never for one whose `vidocq.properties` names H2 or MySQL —
and says why when it does not start, in the log, the state file, the startup report and the *Dev services* panel.

**Architecture:**
- **SPI** (`vidocq-runtime-devservices-spi`): two `default` methods on `DevServiceContext`
  (`applicationProperty(key)`, `onApplicationClasspath(className)`) and one on `DevService` (`skipReason(ctx)`); the
  defaults answer "unknown", so every existing implementation still compiles.
- **Host** (`vidocq-runtime-devservices-host`): `ApplicationFiles.allOf` (every key of the application's files),
  `ApplicationClasspath` (a `.class` looked up in directories and jar entry names, never loaded),
  `DefaultDevServiceContext`'s 5-argument constructor, `DevServicesSession.open`'s 7-argument overload.
  `DevServiceManager` asks `skipReason` when `appliesWhen` is false, logs `DevService '<id>' not started: <reason>`
  and records `Skipped(id, reason)`; `StateFile` writes them as `"skipped"`.
- **Extension** (`vidocq-runtime-devservices-extension`): `StateReader` reads `"skipped"` into
  `DevServicesSnapshot.skipped()`; `DevServicesSection` writes a `not started` row per skipped provider and the
  summary `no dev service started` when nothing started. The panel renders the same section.
- **Provider** (`vidocq-runtime-devservice-postgres`): `PostgresDevService.decide` applies the spec's four rules per
  datasource; `plan` keeps the datasources with a plan; `skipReason` joins the reasons.
- **Hosts:** `VidocqDevMojo` and `VidocqRunMojo` pass `ApplicationFiles.allOf` and an `ApplicationClasspath` over
  `ApplicationLaunch.classpathOf(modulePath, classes)`; `VidocqTestMojo` resolves the test class path (hand-written
  `plugin.xml`: `requiresDependencyResolution` `test` and the `project` parameter); the JUnit listener asks the
  thread context class loader.

**Tech Stack:** Java 25, Maven 3.9 (hand-written plugin descriptor), JUnit 5, Testcontainers (the provider's
Docker-gated tests and the `-Pdocker` IT only), AsciiDoc/Antora docs.

**Spec:** `docs/superpowers/specs/2026-09-29-devservice-postgres-kind-design.md` (binding). Section numbers below
refer to it. Read it first, then this plan's *Rulings*. Context: the dev services visibility work
(`vidocq-runtime-it-devservices`, Vidocq/vidocq#123) and LC4JCDI-on-vidocq#9, where the bug was found.

## Global Constraints

- **Repository and branch.** Vidocq: `/Users/yblazart/projects/perso/vidocq/vidocq`, branch
  `feat/devservice-postgres-kind` (checked out; never switch it).
- **Test app** (read only): `/Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server`, branch
  `feat/tasks-on-postgres` (already has the provider). Never touch the uncommitted `.run/*.xml` files of its parent
  repo `lc4jcdi-on-vidocq`.
- **Maven.** `mvn -nsu` only (never `./mvnw` or `mvnw`), Java 25: every Maven command is prefixed with
  `JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem` (the shell's default Java is 21). Module-scoped with
  `-pl`. A module whose jar another module compiles against is installed (`mvn -nsu install -pl <module>`) before
  that other module is built with `-pl`. Give long builds the Bash tool's `timeout: 600000`. If a hook redirects a
  Maven call to the context-mode `ctx_execute` shell, run it there and print only the tail. No shell variables in
  the commands: every path is spelt out. Maven is never put in the background, except the `vidocq:dev` runs of
  Task 7, through
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh`
  (`dev-run.sh <project dir> <log>`: app on 18093, console on 18094).
- **Paths** (all checked while planning; the commands spell them out):
  - `SPI` = `vidocq-runtime-devservices/vidocq-runtime-devservices-spi`
  - `HOST` = `vidocq-runtime-devservices/vidocq-runtime-devservices-host`
  - `EXT` = `vidocq-runtime-devservices/vidocq-runtime-devservices-extension`
  - `JUNIT` = `vidocq-runtime-devservices/vidocq-runtime-devservices-junit`
  - `PG` = `vidocq-runtime-devservices/vidocq-runtime-devservice-postgres`
  - `KC` = `vidocq-runtime-devservices/vidocq-runtime-devservice-keycloak` (not changed; rebuilt in Task 7)
  - `PLUGIN` = `vidocq-runtime-maven-plugin`
  - `IT` = `vidocq-runtime-integration-tests/vidocq-runtime-it-devservices` (joins the reactor with `-Pdocker`)
  - `EX` = `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example`
  - docs: `docs/en/modules/ROOT/pages/dev-services.adoc`, `docs/en/modules/ROOT/pages/whats-new.adoc`,
    `DEV_SERVICES.md` (repository root)
- **Scratch directory** (logs):
  `/private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad`.
- **Ports:** what this plan binds itself is 18090-18099 only, checked free with `lsof -nP -iTCP:<port> -sTCP:LISTEN`
  first; never 8080 or 8888. The ITs' own `freePort()` asks the OS for an ephemeral port (never 8080/8888); the one
  in-JVM boot that would take Chappe's default 8080 (`DevServicesTestHostIT`) is given 18095 (Task 7). Never kill a
  process this plan did not start. Docker is available locally (Testcontainers).
- **Commits.** Write the message with the Write tool to
  `/Users/yblazart/projects/perso/vidocq/vidocq/.git/PLAN_COMMIT_MSG`, then, from the repository root,
  `git add <the task's new files> && git commit -S -F .git/PLAN_COMMIT_MSG --only -- <every path of the task> && rm .git/PLAN_COMMIT_MSG`
  (a new file must be added first: `--only` refuses a path git does not know). Never `-m`, never `-s`, never a
  bare commit, never `git add -A` / `git add .`. A conventional message that ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
  **Never push.**
- **Code style.** English; 120 columns; Javadoc density like the surrounding files; braces on every `if`/`for`
  body. The repository has no Spotless, Checkstyle or `-Werror`: follow the surrounding style. Every **new** `.java`
  file starts with this license header, verbatim (the one every file of the devservices modules and the plugin
  carries); the code blocks of this plan start at `package` and omit it: prepend it.

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
- **Dependencies.** None new: jar entries are read with `java.util.zip.ZipFile`, the descriptor is hand-edited.
  None of the touched devservices modules has a `module-info.java` (only `EXT`, whose `requires` do not change).
- **Secrets.** A reason never holds anything past the JDBC scheme (no host, no user, no password), and every reason
  also goes through `SecretMasking.withoutCredentials` in the manager and in the state file.
- **Verbatim strings from the spec:**
  - `vidocq.pool.url is jdbc:h2, not PostgreSQL` (named: `vidocq.pool.<name>.url is …`);
  - `no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path`;
  - reasons of several datasources joined with `; `;
  - log `DevService '<id>' not started: <reason>`; `DevService '<id>' skipped (already configured)` kept for no
    reason;
  - state file `"skipped": [{"id": "postgres", "reason": "…"}]` (written without spaces, as the rest of the file);
  - section row value `not started: <reason>`; summary `no dev service started`; the old
    `no dev service: not started by vidocq:dev, vidocq:run or the test launcher` stays when there is no state file
    (or it lists nothing at all).

## Rulings on the spec

1. **Rule 1 has no reason** (§2, §4). An explicit URL gives `null`: when every datasource was given explicitly,
   `skipReason` is `null`, the manager logs the old `skipped (already configured)` and records nothing. Only a
   provider that gives a reason is recorded, written to the state file and shown. A datasource of rule 1 beside one
   of rule 2 or 4 contributes nothing to the joined reason.
2. **The scheme** (§2 "up to the second `:`"): `jdbc:` then the run of ASCII letters, digits, `-` and `_` after it,
   so it stops at the second `:` of every real JDBC URL, and also at a `@`, `/`, `;` or `?` of a malformed one that
   has no second `:`; at most 32 characters in all. Shown as written (`JDBC:H2`). The file's value is `strip()`ped
   first; `jdbc:` and `jdbc:postgresql:` are compared ignoring case (`regionMatches(true, …)`). A Testcontainers URL
   `jdbc:tc:postgresql:…` is rule 2 (`jdbc:tc`): that driver starts its own container.
3. **Order and join** (§2): the `@Default` datasource first, then the names of `vidocq.dev.postgres.datasources` in
   their order (the existing `parseNames`: trimmed, blank and dotted names dropped, duplicates once). `skipReason`
   joins the non-null reasons with `; ` — asked only when no datasource got a plan.
4. **The manager** (§4): a reason is made one line (`strip()`, every run of whitespace one space) and masked with
   `SecretMasking.withoutCredentials`; a blank reason is `null`. A `skipReason` that throws a `RuntimeException` is
   `null` after a WARNING `DevService '<id>' skipReason() threw <class>` (never the message), and the run goes on.
   The record is `DevServiceManager.Skipped(String id, String reason)`, a public nested record, listed by
   `DevServiceManager.skipped()` in start order.
5. **The state file** (§4): `"skipped"` is always written, after `"services"`, `[]` when empty. `StateFile.json`'s
   5-argument form stays and writes `[]`; the new 6-argument form takes `List<DevServiceManager.Skipped>`. Both the
   running and the stopped file carry the list.
6. **Reading it** (§4): `DevServicesSnapshot` gains a fifth component `List<Skipped> skipped` (never `null`) and
   keeps a 4-argument constructor (empty list). `StateReader` reads `"skipped"` when it is an array, drops an entry
   whose `id` is missing or blank, keeps a `null` reason; a file without the key reads as empty.
7. **The section** (§4): with no service and at least one skipped, the summary is `no dev service started`, then one
   row per skipped provider. With services, the summary and the `started` row are unchanged, then the skipped rows,
   both before the verbosity check (so "at every verbosity"). A row is `section.row(id, "not started: " + reason)`,
   `not started` alone when the reason is `null` or blank (possible only in a hand-edited file). The report renders a
   row as its key then its value; the spec's `postgres — not started: …` is that row.
8. **Hosts** (§5): the class path of `vidocq:dev`/`vidocq:run` is `ApplicationLaunch.classpathOf(modulePath,
   classes)` — the module path as built, plus the classes directory (off the module path in layer mode), once —
   taken **before** the dev services extension jars and the dev tools are appended. `vidocq:test`'s `project` and
   `requiresDependencyResolution` go into the **hand-written** `plugin.xml` (the annotations are documentation
   only: `PluginDescriptorContinuousTestingTest` reads the XML); with no project (a unit test) its class path is
   empty. The JUnit listener asks the thread context class loader, or its own class loader when there is none,
   through `getResource("<name>.class")` (a `.class` resource is found even inside a named module).
9. **The session API**: `DevServicesSession.open(host, basedir, seed, applicationFiles, applicationValues,
   applicationClasspath, log)` is added; the 5-argument `open` stays and delegates with "none"
   (`key -> Optional.empty()`, `className -> false`). `DefaultDevServiceContext` gets the 5-argument constructor of
   §3; its 3-argument one delegates with "none".
10. **The end-to-end IT keeps its container** (§7 "stays green"). `vidocq-runtime-it-devservices` declares
    `org.postgresql:postgresql` in **test** scope only and has no `vidocq.properties`: under the new rule its
    `vidocq:run` and `vidocq:dev` (runtime class path) would start nothing (rule 4) and `DevServicesRunGoalIT` /
    `DevServicesDevGoalIT` would fail. Task 4 gives it `src/main/resources/vidocq.properties` with
    `vidocq.pool.url=jdbc:postgresql://localhost:5432/vidocq`: rule 3 for all three hosts, which the IT then proves
    end to end. The `pool` namespace is claimed by no extension of that app, so the key audit says nothing, and the
    JUnit host's system property still wins over the file for `DevServicesTestHostIT`.
11. **Profiles** (§7): the end-to-end IT joins the reactor with `-Pdocker` (not `-Pit`, which the task suggestion
    named); `-Pit` runs `vidocq-runtime-it-continuous-testing`, whose `ContinuousTestingTestGoalIT` runs a real
    `vidocq:test` — the goal whose dependency resolution changes. Task 7 runs both.
12. **Docs anchor**: `[#postgres-only-for-postgres]`, a `===` subsection of `[#configuration-sources]`.

## Review Focus

- **A file URL that carries credentials, or has no second `:`** (`jdbc:mysql://admin:s3cret@db.internal:3306/app?password=hunter2`,
  `jdbc:x@s3cret.internal/db`, a 60-character subprotocol): the reason reads `vidocq.pool.url is jdbc:mysql, not
  PostgreSQL` / `… jdbc:x …` / 32 characters at most, and the manager masks anything that still looks like
  `user:password@`. → `PostgresDevServiceTest.aReasonNeverHoldsAnythingPastTheScheme` (Task 4),
  `DevServiceManagerTest.aReasonIsOneLineWithoutCredentialsAndAThrowingOneIsNone` (Task 3).
- **A `${db.url}` placeholder in the file** (the dev host never resolves expressions): it is no URL at all, rule 4 —
  a container with the driver, `no vidocq.pool.url and no PostgreSQL driver …` without. →
  `PostgresDevServiceTest.aPlaceholderInTheFileIsNoUrlAtAll` (Task 4).
- **A named datasource declared only in the file** (`vidocq.dev.postgres.datasources=audit` and
  `vidocq.pool.audit.url=jdbc:h2:mem:audit` both in `vidocq.properties`): the name comes through `property` (a
  tuning key), the URL through `applicationProperty` only; `audit` gets no container, with its own key in the
  reason. → `DefaultDevServiceContextTest.aNamedDatasourceOnlyInTheFilesIsSeenThroughBothLookups` (Task 2),
  `PostgresDevServiceTest.aNamedDatasourceDeclaredOnlyInTheFileIsDecidedByTheFile` (Task 4).
- **The JUnit listener's class loader** (the driver a test dependency, the listener's own jar in the unnamed module,
  possibly no context class loader): the driver is found through the context class loader, a class it does not
  hold is not, and without one the listener's own loader answers. →
  `DevServicesSessionListenerTest.theDevServicesSeeTheTestClassPathThroughTheContextClassLoader` (Task 5).
- **A state file written before `skipped` existed, or a hand-edited entry** (no `id`, a `null` reason): reads as no
  skipped provider / drops the id-less entry / shows `not started` with no `null` anywhere in the report. →
  `StateReaderTest.readsTheSkippedProvidersAndAnOlderFileHasNone`,
  `DevServicesSectionTest.aProviderNotStartedBesideAStartedOneKeepsTheSummaryAndAddsItsRow` (Task 3).

---

## File Structure

| File | Responsibility |
|---|---|
| `SPI/.../spi/DevServiceContext.java`, `DevService.java` (modify), `DevServiceDefaultsTest.java` (new) | the two context defaults, `skipReason` |
| `HOST/.../host/ApplicationFiles.java` (modify), `ApplicationFilesTest.java` (new) | `allOf`: every key of the application's files |
| `HOST/.../host/ApplicationClasspath.java` (new), `ApplicationClasspathTest.java` (new) | a class in a directory or a jar, never loaded |
| `HOST/.../host/DefaultDevServiceContext.java` (modify), `DefaultDevServiceContextTest.java` (modify) | the 5-argument constructor, `applicationProperty`, `onApplicationClasspath` |
| `HOST/.../host/DevServicesSession.java` (modify), `DevServicesSessionTest.java` (modify) | the 7-argument `open`; `skipped` in both state files |
| `HOST/.../host/DevServiceManager.java` (modify), `DevServiceManagerTest.java` (modify) | asks `skipReason`, logs, records `Skipped` |
| `HOST/.../host/StateFile.java` (modify), `StateFileTest.java` (modify) | `"skipped"` in the JSON |
| `EXT/.../extension/DevServicesSnapshot.java`, `StateReader.java`, `DevServicesSection.java` (modify), `StateReaderTest.java`, `DevServicesSectionTest.java` (modify) | read and show the skipped providers |
| `PG/.../postgres/PostgresDevService.java` (modify), `PostgresDevServiceTest.java` (modify) | the rule, the reasons |
| `IT/src/main/resources/vidocq.properties` (new) | the IT app says it is on PostgreSQL (Ruling 10) |
| `PLUGIN/.../maven/ApplicationLaunch.java`, `VidocqRunMojo.java`, `dev/VidocqDevMojo.java`, `dev/VidocqTestMojo.java`, `META-INF/maven/plugin.xml` (modify); `ApplicationLaunchTest.java`, `dev/VidocqTestMojoTest.java`, `dev/PluginDescriptorContinuousTestingTest.java` (modify) | the three Maven hosts |
| `JUNIT/.../junit/DevServicesSessionListener.java` (modify), `DevServicesSessionListenerTest.java` (modify) | the JUnit host |
| docs (modify) | `dev-services.adoc`, `DEV_SERVICES.md`, `whats-new.adoc` |

**Deviations from the suggested decomposition** (seven tasks, as suggested): the SPI task carries its own defaults
test; Task 2 also adds the session's 7-argument `open`, which is plain delegation the hosts of Task 5 need; the IT's
`vidocq.properties` (Ruling 10) goes with the rule that makes it necessary (Task 4), not with the verification.

---

### Task 1: The SPI — the application's values, its class path, a reason not to start (spec §3, §4)

**Files:**
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevServiceContext.java:32-36,67-71`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevService.java:59-66`
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/test/java/io/vidocq/runtime/devservices/spi/DevServiceDefaultsTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `default Optional<String> DevServiceContext.applicationProperty(String key)` (default `Optional.empty()`),
  `default boolean DevServiceContext.onApplicationClasspath(String className)` (default `false`),
  `default String DevService.skipReason(DevServiceContext ctx)` (default `null`). The SPI jar installed in `~/.m2`.

- [ ] **Step 1: Write the failing test**

Create `DevServiceDefaultsTest.java`:
```java
package io.vidocq.runtime.devservices.spi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SPI's {@code default} methods (spec 2026-09-29-devservice-postgres-kind §3, §4): an implementation written
 * before them still compiles, and answers "unknown".
 */
class DevServiceDefaultsTest {

    /** A context that implements only what the SPI asked for before. */
    private static final DevServiceContext OLD_CONTEXT = new DevServiceContext() {
        @Override public Optional<String> property(String key) { return Optional.of("explicit"); }
        @Override public Map<String, String> properties() { return Map.of(); }
        @Override public Path basedir() { return Path.of("."); }
        @Override public Path resolve(String relative) { return Path.of(relative); }
        @Override public System.Logger log() { return System.getLogger("test"); }
    };

    @Test
    void anOlderContextKnowsNoApplicationValueAndNoClass() {
        assertEquals(Optional.empty(), OLD_CONTEXT.applicationProperty("vidocq.pool.url"));
        assertFalse(OLD_CONTEXT.onApplicationClasspath("org.postgresql.Driver"));
    }

    @Test
    void aProviderGivesNoReasonUnlessItSaysOne() {
        DevService provider = new DevService() {
            @Override public String id() { return "old"; }
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public Map<String, String> start(DevServiceContext ctx) { return Map.of(); }
            @Override public void stop() { }
        };

        assertNull(provider.skipReason(OLD_CONTEXT));
    }
}
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-spi test -Dtest=DevServiceDefaultsTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `applicationProperty` (and `skipReason`).

- [ ] **Step 3: The context's two defaults**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevServiceContext.java`, replace exactly:
```java
 * {@code vidocq.properties} and {@code application.properties}, after those; every other key never is, so that a
 * baked-in default such as {@code vidocq.pool.url} does not switch a dev service off.</p>
 */
```
with:
```java
 * {@code vidocq.properties} and {@code application.properties}, after those; every other key never is, so that a
 * baked-in default such as {@code vidocq.pool.url} does not switch a dev service off.</p>
 *
 * <p>A provider may still learn what the application is configured for: {@link #applicationProperty} answers any key
 * of the application's own files, {@link #onApplicationClasspath} whether its class path holds a class.</p>
 */
```

Then replace exactly:
```java
    /**
     * A logger bound to the dev-mode output.
     */
    System.Logger log();
}
```
with:
```java
    /**
     * A logger bound to the dev-mode output.
     */
    System.Logger log();

    /**
     * A value of the application's own configuration files ({@code vidocq.properties}, {@code application.properties},
     * the external configuration directory), whatever its key — for a provider to learn what the application is
     * configured for, such as the kind of database a URL names. Never a reason to switch a service off in place of
     * {@link #property(String)}, whose explicit sources alone do that.
     *
     * <p>The default knows nothing: every host of this repository implements it.</p>
     *
     * @param key the property key, such as {@code "vidocq.pool.url"}
     * @return the value if the files give a non-blank one, otherwise {@link Optional#empty()}
     */
    default Optional<String> applicationProperty(String key) {
        return Optional.empty();
    }

    /**
     * Whether the application's class path, as its launch will see it (runtime dependencies for {@code vidocq:dev}
     * and {@code vidocq:run}, test dependencies for a test run), holds this class, found as a {@code .class} entry,
     * never loaded.
     *
     * <p>The default knows nothing, so it says {@code false}: every host of this repository implements it.</p>
     *
     * @param className a binary class name, such as {@code "org.postgresql.Driver"}
     * @return whether the class is there
     */
    default boolean onApplicationClasspath(String className) {
        return false;
    }
}
```

- [ ] **Step 4: The provider's reason**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevService.java`, replace exactly:
```java
     * Whether this provider should run for the current project. Mirrors the Quarkus DevServices
     * rule: a provider opts out when the application has already configured the dependency itself
     * (e.g. the Postgres provider returns {@code false} when {@code vidocq.pool.url} is present).
```
with:
```java
     * Whether this provider should run for the current project. Mirrors the Quarkus DevServices
     * rule: a provider opts out when the application has already configured the dependency itself
     * (e.g. the Postgres provider returns {@code false} when {@code vidocq.pool.url} is given explicitly), or when
     * the application does not use it (the Postgres provider for an application on H2); {@link #skipReason} then
     * says why.
```

Then replace exactly:
```java
    boolean appliesWhen(DevServiceContext ctx);
```
with:
```java
    boolean appliesWhen(DevServiceContext ctx);

    /**
     * Why this provider does not start, when {@link #appliesWhen} is {@code false}: one line, no secret. The host
     * logs it as {@code DevService '<id>' not started: <reason>}, keeps it in the state file and shows it in the
     * startup report and the dev console; {@code null}, the default, when there is none to give (the host then logs
     * {@code skipped (already configured)}, as before).
     *
     * @param ctx the context {@link #appliesWhen} was given
     * @return the reason, or {@code null}
     */
    default String skipReason(DevServiceContext ctx) {
        return null;
    }
```

- [ ] **Step 5: Run the module's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devservices/vidocq-runtime-devservices-spi 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -5
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 6: Commit**

Message:
```
feat(devservices): the application's values, its class path and a reason not to start in the SPI

DevServiceContext gains applicationProperty(key), any key of the
application's own files, and onApplicationClasspath(className), whether
its class path holds a class; DevService gains skipReason(ctx). All three
are default methods answering "unknown", so existing providers and
contexts still compile.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/test/java/io/vidocq/runtime/devservices/spi/DevServiceDefaultsTest.java && git commit -S -F .git/PLAN_COMMIT_MSG --only -- vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevServiceContext.java vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevService.java vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/test/java/io/vidocq/runtime/devservices/spi/DevServiceDefaultsTest.java && rm .git/PLAN_COMMIT_MSG
```

---

### Task 2: The host reads every application value and looks up the class path (spec §3)

**Files:**
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationFiles.java:34,45-86`
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationClasspath.java`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContext.java:29,46-65,94-97`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java:34,70-75`
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/ApplicationFilesTest.java`
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/ApplicationClasspathTest.java`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContextTest.java`

**Interfaces:**
- Consumes: Task 1's SPI (installed).
- Produces: `public static Function<String, Optional<String>> ApplicationFiles.allOf(Path classesDir)`;
  `public final class ApplicationClasspath` with `public ApplicationClasspath(Collection<Path> entries)` and
  `public boolean contains(String className)`; `public DefaultDevServiceContext(Path basedir, Map<String, String>
  seed, Function<String, Optional<String>> applicationFiles, Function<String, Optional<String>> applicationValues,
  Predicate<String> applicationClasspath)`; `public static DevServicesSession DevServicesSession.open(String host,
  Path basedir, Map<String, String> seed, Function<String, Optional<String>> applicationFiles, Function<String,
  Optional<String>> applicationValues, Predicate<String> applicationClasspath, System.Logger log) throws
  DevServicesException`.

- [ ] **Step 1: Write the failing tests**

Create `ApplicationFilesTest.java`:
```java
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ApplicationFiles#allOf} answers every key of the files; {@link ApplicationFiles#of} still the dev ones only. */
class ApplicationFilesTest {

    @Test
    void allOfAnswersEveryKeyOfTheFilesAndOfStillOnlyTheDevOnes(@TempDir Path classes) throws Exception {
        Files.writeString(classes.resolve("vidocq.properties"),
                "vidocq.dev.postgres.port=55432\nvidocq.pool.url=jdbc:h2:mem:x\n");
        Files.writeString(classes.resolve("application.properties"), "vidocq.pool.audit.url=jdbc:mysql://h/db\n");

        Function<String, Optional<String>> all = ApplicationFiles.allOf(classes);
        assertEquals(Optional.of("jdbc:h2:mem:x"), all.apply("vidocq.pool.url"));
        assertEquals(Optional.of("jdbc:mysql://h/db"), all.apply("vidocq.pool.audit.url"));
        assertEquals(Optional.of("55432"), all.apply("vidocq.dev.postgres.port"));
        assertEquals(Optional.empty(), all.apply("absent.key"));

        Function<String, Optional<String>> dev = ApplicationFiles.of(classes);
        assertEquals(Optional.empty(), dev.apply("vidocq.pool.url"), "of(...) keeps its vidocq.dev. filter");
        assertEquals(Optional.of("55432"), dev.apply("vidocq.dev.postgres.port"));
    }

    @Test
    void aClassesDirectoryWithoutFilesAnswersNothing(@TempDir Path classes) {
        assertEquals(Optional.empty(), ApplicationFiles.allOf(classes).apply("vidocq.pool.url"));
    }
}
```

Create `ApplicationClasspathTest.java`:
```java
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A class looked up in directories and jars of the application's class path, never loaded (spec §3). */
class ApplicationClasspathTest {

    @Test
    void findsAClassInADirectory(@TempDir Path dir) throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("org/postgresql"));
        Files.write(classes.resolve("org/postgresql/Driver.class"), new byte[0]);

        ApplicationClasspath classpath = new ApplicationClasspath(List.of(classes));

        assertTrue(classpath.contains("org.postgresql.Driver"));
        assertFalse(classpath.contains("org.h2.Driver"));
    }

    @Test
    void findsAClassInAJarWithoutLoadingIt(@TempDir Path dir) throws Exception {
        Path jar = driverJar(dir.resolve("postgresql.jar"));

        ApplicationClasspath classpath = new ApplicationClasspath(List.of(dir.resolve("classes"), jar));

        assertTrue(classpath.contains("org.postgresql.Driver"), "found although its bytes are not a class");
        assertFalse(classpath.contains("org.postgresql.Missing"));
    }

    @Test
    void aJarsEntriesAreReadOnceAndRemembered(@TempDir Path dir) throws Exception {
        Path jar = driverJar(dir.resolve("postgresql.jar"));
        ApplicationClasspath classpath = new ApplicationClasspath(List.of(jar));
        assertTrue(classpath.contains("org.postgresql.Driver"));

        Files.delete(jar);

        assertTrue(classpath.contains("org.postgresql.Driver"), "the names read the first time are kept");
    }

    @Test
    void aMissingPathOrAFileThatIsNoJarHoldsNothing(@TempDir Path dir) throws Exception {
        Path notAJar = dir.resolve("broken.jar");
        Files.writeString(notAJar, "not a zip");

        ApplicationClasspath classpath = new ApplicationClasspath(
                List.of(dir.resolve("missing"), dir.resolve("missing.jar"), notAJar));

        assertFalse(classpath.contains("org.postgresql.Driver"));
    }

    /** A jar holding {@code org/postgresql/Driver.class}, whose content is not a class file: it is never loaded. */
    private static Path driverJar(Path jar) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("org/postgresql/Driver.class"));
            out.write(new byte[] {1, 2, 3});
            out.closeEntry();
        }
        return jar;
    }
}
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContextTest.java`, replace exactly:
```java
    @Test
    void applicationFilesReadVidocqPropertiesFromTheClassesDirectory(@TempDir Path classes) throws Exception {
```
with:
```java
    @Test
    void theApplicationsOwnValuesAnswerAnyKeyButNeverOptOut() {
        Function<String, Optional<String>> values = key -> key.equals("vidocq.pool.url")
                ? Optional.of("jdbc:h2:mem:x") : Optional.empty();
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(),
                key -> Optional.empty(), values, name -> false);

        assertEquals(Optional.of("jdbc:h2:mem:x"), ctx.applicationProperty("vidocq.pool.url"));
        assertEquals(Optional.empty(), ctx.property("vidocq.pool.url"), "property(key) still ignores the files");
    }

    @Test
    void aBlankApplicationValueIsAbsent() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(),
                key -> Optional.empty(), key -> Optional.of("   "), name -> false);

        assertEquals(Optional.empty(), ctx.applicationProperty("vidocq.pool.url"));
    }

    @Test
    void theClassPathCheckIsTheOneGiven() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(),
                key -> Optional.empty(), key -> Optional.empty(), name -> name.equals("org.postgresql.Driver"));

        assertTrue(ctx.onApplicationClasspath("org.postgresql.Driver"));
        assertFalse(ctx.onApplicationClasspath("org.h2.Driver"));
    }

    @Test
    void theShorterConstructorsKnowNothingOfTheApplication() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(), key -> Optional.of("x"));

        assertEquals(Optional.empty(), ctx.applicationProperty("vidocq.pool.url"));
        assertFalse(ctx.onApplicationClasspath("org.postgresql.Driver"));
    }

    /**
     * Review Focus: a named datasource declared in the application's file only — its name is a tuning key, read by
     * property(key); its URL is not, so only applicationProperty(key) sees it.
     */
    @Test
    void aNamedDatasourceOnlyInTheFilesIsSeenThroughBothLookups(@TempDir Path classes) throws Exception {
        Files.writeString(classes.resolve("vidocq.properties"),
                "vidocq.dev.postgres.datasources=audit\nvidocq.pool.audit.url=jdbc:h2:mem:audit\n");
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(),
                ApplicationFiles.of(classes), ApplicationFiles.allOf(classes), name -> false);

        assertEquals(Optional.of("audit"), ctx.property("vidocq.dev.postgres.datasources"));
        assertEquals(Optional.empty(), ctx.property("vidocq.pool.audit.url"));
        assertEquals(Optional.of("jdbc:h2:mem:audit"), ctx.applicationProperty("vidocq.pool.audit.url"));
    }

    @Test
    void applicationFilesReadVidocqPropertiesFromTheClassesDirectory(@TempDir Path classes) throws Exception {
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host test -Dtest='ApplicationFilesTest,ApplicationClasspathTest,DefaultDevServiceContextTest' 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `allOf`, `ApplicationClasspath`, and no constructor
`DefaultDevServiceContext(Path,Map,…,…,…)`.

- [ ] **Step 3: `ApplicationFiles.allOf`**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationFiles.java`, replace exactly:
```java
import java.util.function.Function;
```
with:
```java
import java.util.function.Function;
import java.util.function.Predicate;
```

Then replace exactly:
```java
    public static Function<String, Optional<String>> of(Path classesDir) {
        Map<String, String> tuning = new HashMap<>();
        // Lowest precedence first: a later source overwrites.
        copyTuning(classpathSource(classesDir), tuning);
        if (System.getProperty("vidocq.config.dir") != null || System.getenv("VIDOCQ_CONFIG_DIR") != null) {
            copyTuning(new ExternalFileConfigSource(), tuning);
        }
        Map<String, String> frozen = Map.copyOf(tuning);
        return key -> Optional.ofNullable(frozen.get(key));
    }
```
with:
```java
    public static Function<String, Optional<String>> of(Path classesDir) {
        return read(classesDir, key -> key.startsWith("vidocq.dev."));
    }

    /**
     * Every key of the same files, in the same order as {@link #of}, without its {@code vidocq.dev.} filter: what
     * {@link DefaultDevServiceContext#applicationProperty} answers, for a provider to learn what the application is
     * configured for (spec 2026-09-29-devservice-postgres-kind §3). Never a source of {@code property(key)}.
     */
    public static Function<String, Optional<String>> allOf(Path classesDir) {
        return read(classesDir, key -> true);
    }

    private static Function<String, Optional<String>> read(Path classesDir, Predicate<String> keys) {
        Map<String, String> values = new HashMap<>();
        // Lowest precedence first: a later source overwrites.
        copy(classpathSource(classesDir), keys, values);
        if (System.getProperty("vidocq.config.dir") != null || System.getenv("VIDOCQ_CONFIG_DIR") != null) {
            copy(new ExternalFileConfigSource(), keys, values);
        }
        Map<String, String> frozen = Map.copyOf(values);
        return key -> Optional.ofNullable(frozen.get(key));
    }
```

Then replace exactly:
```java
    private static void copyTuning(ConfigSource source, Map<String, String> into) {
        for (String key : source.getPropertyNames()) {
            if (key.startsWith("vidocq.dev.")) {
```
with:
```java
    private static void copy(ConfigSource source, Predicate<String> keys, Map<String, String> into) {
        for (String key : source.getPropertyNames()) {
            if (keys.test(key)) {
```

- [ ] **Step 4: `ApplicationClasspath`**

Create `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationClasspath.java`:
```java
package io.vidocq.runtime.devservices.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The application's class path as its launch will see it, for
 * {@link io.vidocq.runtime.devservices.spi.DevServiceContext#onApplicationClasspath}: whether it holds a class,
 * looked up as a {@code .class} file in each directory and as an entry of each jar — never loaded (spec
 * 2026-09-29-devservice-postgres-kind §3). A jar's entry names are read once, the first time a lookup reaches it, and
 * remembered. A missing path, or a file that is not a readable jar, holds nothing.
 */
public final class ApplicationClasspath {

    private final List<Path> entries;
    private final Map<Path, Set<String>> jarNames = new ConcurrentHashMap<>();

    /**
     * @param entries the jars and directories, in class path order
     */
    public ApplicationClasspath(Collection<Path> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Whether a class of this binary name, such as {@code org.postgresql.Driver}, is on the class path.
     *
     * @param className the binary name
     * @return {@code true} when a directory holds its {@code .class} file or a jar its entry
     */
    public boolean contains(String className) {
        String resource = className.replace('.', '/') + ".class";
        for (Path entry : entries) {
            Set<String> names = jarNames.get(entry);
            if (names != null) {
                if (names.contains(resource)) {
                    return true;
                }
            } else if (Files.isDirectory(entry)) {
                if (Files.isRegularFile(entry.resolve(resource))) {
                    return true;
                }
            } else if (Files.isRegularFile(entry)
                    && jarNames.computeIfAbsent(entry, ApplicationClasspath::namesOf).contains(resource)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> namesOf(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Set<String> names = new HashSet<>();
            zip.stream().map(ZipEntry::getName).forEach(names::add);
            return Set.copyOf(names);
        } catch (IOException e) {
            return Set.of(); // not a readable jar: it holds nothing
        }
    }
}
```

- [ ] **Step 5: The context's constructor and its two answers**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContext.java`, replace exactly:
```java
import java.util.function.Function;
```
with:
```java
import java.util.function.Function;
import java.util.function.Predicate;
```

Then replace exactly:
```java
 * resolved, as a last resort, from the {@code applicationFiles} lookup given at construction.</p>
 */
public final class DefaultDevServiceContext implements DevServiceContext {
```
with:
```java
 * resolved, as a last resort, from the {@code applicationFiles} lookup given at construction.</p>
 *
 * <p>Apart from both, {@link #applicationProperty} answers any key of the application's files (the
 * {@code applicationValues} lookup) and {@link #onApplicationClasspath} whether its class path holds a class: what a
 * provider reads to learn what the application is configured for, never to opt out.</p>
 */
public final class DefaultDevServiceContext implements DevServiceContext {
```

Then replace exactly:
```java
    private final Function<String, Optional<String>> applicationFiles;

    public DefaultDevServiceContext(Path basedir, Map<String, String> seed) {
        this(basedir, seed, key -> Optional.empty());
    }

    public DefaultDevServiceContext(
            Path basedir, Map<String, String> seed, Function<String, Optional<String>> applicationFiles) {
        this.basedir = basedir;
        this.resolved = new LinkedHashMap<>(seed);
        this.logger = System.getLogger("vidocq.dev.devservices");
        this.applicationFiles = applicationFiles;
    }
```
with:
```java
    private final Function<String, Optional<String>> applicationFiles;
    private final Function<String, Optional<String>> applicationValues;
    private final Predicate<String> applicationClasspath;

    public DefaultDevServiceContext(Path basedir, Map<String, String> seed) {
        this(basedir, seed, key -> Optional.empty());
    }

    public DefaultDevServiceContext(
            Path basedir, Map<String, String> seed, Function<String, Optional<String>> applicationFiles) {
        this(basedir, seed, applicationFiles, key -> Optional.empty(), className -> false);
    }

    /**
     * @param basedir              the project base directory
     * @param seed                 the goal's explicit values
     * @param applicationFiles     the {@code vidocq.dev.*} keys of the application's files, {@link ApplicationFiles#of}
     * @param applicationValues    every key of the same files, {@link ApplicationFiles#allOf}, for
     *                             {@link #applicationProperty}
     * @param applicationClasspath whether the application's class path holds a class, for
     *                             {@link #onApplicationClasspath}: an {@link ApplicationClasspath}'s {@code contains}
     */
    public DefaultDevServiceContext(Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, Function<String, Optional<String>> applicationValues,
            Predicate<String> applicationClasspath) {
        this.basedir = basedir;
        this.resolved = new LinkedHashMap<>(seed);
        this.logger = System.getLogger("vidocq.dev.devservices");
        this.applicationFiles = applicationFiles;
        this.applicationValues = applicationValues;
        this.applicationClasspath = applicationClasspath;
    }
```

Then replace exactly:
```java
    @Override
    public Map<String, String> properties() {
        return Collections.unmodifiableMap(resolved);
    }
```
with:
```java
    /** From the {@code applicationValues} lookup given at construction; a blank value is absent. */
    @Override
    public Optional<String> applicationProperty(String key) {
        return applicationValues.apply(key).filter(DefaultDevServiceContext::isPresent);
    }

    @Override
    public boolean onApplicationClasspath(String className) {
        return applicationClasspath.test(className);
    }

    @Override
    public Map<String, String> properties() {
        return Collections.unmodifiableMap(resolved);
    }
```

- [ ] **Step 6: The session's 7-argument `open`**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java`, replace exactly:
```java
import java.util.function.Function;
```
with:
```java
import java.util.function.Function;
import java.util.function.Predicate;
```

Then replace exactly:
```java
    public static DevServicesSession open(String host, Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, System.Logger log) throws DevServicesException {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(basedir, seed, applicationFiles);
        DevServiceManager mgr = DevServiceManager.start(ctx, log);
        return open(host, basedir, mgr, log, Clock.systemUTC());
    }
```
with:
```java
    public static DevServicesSession open(String host, Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, System.Logger log) throws DevServicesException {
        return open(host, basedir, seed, applicationFiles, key -> Optional.empty(), className -> false, log);
    }

    /**
     * {@link #open(String, Path, Map, Function, System.Logger)}, the providers also told what the application is
     * configured for (spec 2026-09-29-devservice-postgres-kind §5): every value of its files
     * ({@link ApplicationFiles#allOf}) and whether its class path holds a class ({@link ApplicationClasspath}).
     */
    public static DevServicesSession open(String host, Path basedir, Map<String, String> seed,
            Function<String, Optional<String>> applicationFiles, Function<String, Optional<String>> applicationValues,
            Predicate<String> applicationClasspath, System.Logger log) throws DevServicesException {
        DefaultDevServiceContext ctx =
                new DefaultDevServiceContext(basedir, seed, applicationFiles, applicationValues, applicationClasspath);
        DevServiceManager mgr = DevServiceManager.start(ctx, log);
        return open(host, basedir, mgr, log, Clock.systemUTC());
    }
```

- [ ] **Step 7: Run the tests to see them pass**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host test 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure (the module's whole suite, the new classes included).

- [ ] **Step 8: Commit**

Message:
```
feat(devservices): the host reads every application value and looks up the class path

ApplicationFiles.allOf answers every key of the application's files, in
the order of(...) reads them; ApplicationClasspath tells whether a class
is in a directory or a jar of the application's class path, reading each
jar's entry names once and never loading anything. DefaultDevServiceContext
and DevServicesSession.open gain the overloads that pass both on; the
existing ones delegate with "none".

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationClasspath.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/ApplicationFilesTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/ApplicationClasspathTest.java && git commit -S -F .git/PLAN_COMMIT_MSG --only -- vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationFiles.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationClasspath.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContext.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/ApplicationFilesTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/ApplicationClasspathTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContextTest.java && rm .git/PLAN_COMMIT_MSG
```

---

### Task 3: A provider's reason — logged, recorded, written, read, shown (spec §4)

**Files:**
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServiceManager.java:22-23,55,78-81,106`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/StateFile.java:35,54-68`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java` (the two `StateFile.json` calls)
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/DevServicesSnapshot.java`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/StateReader.java:70-72`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/DevServicesSection.java:67-75`
- Test: `HOST/.../DevServiceManagerTest.java`, `StateFileTest.java`, `DevServicesSessionTest.java`;
  `EXT/.../StateReaderTest.java`, `DevServicesSectionTest.java` (all modify)

**Interfaces:**
- Consumes: `DevService.skipReason` (Task 1); `SecretMasking.withoutCredentials(String)` (existing).
- Produces: `public record DevServiceManager.Skipped(String id, String reason)`; `public List<Skipped>
  DevServiceManager.skipped()`; `public static String StateFile.json(String host, String state, Instant startedAt,
  List<DevServiceState> services, Map<String, String> injected, List<DevServiceManager.Skipped> skipped)`;
  `DevServicesSnapshot(String host, String state, String startedAt, List<Service> services, List<Skipped> skipped)`
  with `public record DevServicesSnapshot.Skipped(String id, String reason)` and a kept 4-argument constructor. The
  host and extension jars installed.

- [ ] **Step 1: Write the failing host tests**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DevServiceManagerTest.java`, replace exactly:
```java
    /** A provider that applies, starts with no output and stops quietly, for a test to override one step of. */
```
with:
```java
    /*
     * CapturingLogger renders a line through MessageFormat, which drops the single quotes around a provider's id:
     * "DevService 'postgres' not started: …" is captured as "INFO DevService postgres not started: …".
     */

    @Test
    void aProviderThatDoesNotApplyAndSaysWhyIsLoggedAndRecorded() throws Exception {
        DevService h2 = new Scripted("postgres") {
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                return "vidocq.pool.url is jdbc:h2, not PostgreSQL";
            }
        };
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(h2), ctx(), log);

        assertEquals(List.of(new DevServiceManager.Skipped("postgres", "vidocq.pool.url is jdbc:h2, not PostgreSQL")),
                mgr.skipped());
        assertTrue(log.lines.stream().anyMatch(line -> line.startsWith("INFO")
                && line.contains("postgres not started: vidocq.pool.url is jdbc:h2, not PostgreSQL")), log.lines.toString());
        assertTrue(mgr.collectedProperties().isEmpty());
    }

    @Test
    void withoutAReasonTheOldLineIsLoggedAndNothingIsRecorded() throws Exception {
        List<String> events = new ArrayList<>();
        FakeDevService configured = new FakeDevService("keycloak", 100, false, Map.of(), false, null, events);
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(configured), ctx(), log);

        assertEquals(List.of(), mgr.skipped());
        assertTrue(log.lines.stream().anyMatch(line -> line.startsWith("INFO")
                && line.contains("keycloak skipped (already configured)")), log.lines.toString());
    }

    /** Review Focus: a reason is one line with no credentials; a skipReason that throws is none, and never fatal. */
    @Test
    void aReasonIsOneLineWithoutCredentialsAndAThrowingOneIsNone() throws Exception {
        DevService leaky = new Scripted("leaky") {
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                return "  url jdbc:mysql://admin:hunter2@db/app\n   refused  ";
            }
        };
        DevService broken = new Scripted("broken") {
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                throw new IllegalStateException("secret-in-message");
            }
        };
        CapturingLogger log = new CapturingLogger();

        DevServiceManager mgr = DevServiceManager.start(List.of(leaky, broken), ctx(), log);

        assertEquals(List.of(new DevServiceManager.Skipped("leaky", "url jdbc:mysql://***@db/app refused")),
                mgr.skipped());
        assertFalse(log.lines.toString().contains("hunter2"), log.lines.toString());
        assertFalse(log.lines.toString().contains("secret-in-message"), log.lines.toString());
        assertTrue(log.lines.stream().anyMatch(line -> line.startsWith("WARNING") && line.contains("broken")
                && line.contains("IllegalStateException")), log.lines.toString());
        assertTrue(log.lines.stream().anyMatch(line -> line.contains("broken skipped (already configured)")),
                log.lines.toString());
    }

    /** A provider that applies, starts with no output and stops quietly, for a test to override one step of. */
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/StateFileTest.java`, replace exactly:
```java
    @Test
    void writeIsAtomic(@TempDir Path dir) throws Exception {
```
with:
```java
    @Test
    void skippedProvidersAreWrittenWithTheirReasonAfterTheServices() {
        String json = StateFile.json("vidocq:dev", "running", Instant.EPOCH, List.of(), Map.of(),
                List.of(new DevServiceManager.Skipped("postgres", "vidocq.pool.url is jdbc:h2, not PostgreSQL"),
                        new DevServiceManager.Skipped("acme", "tried jdbc:x://u:p@h/db")));

        assertTrue(json.endsWith(",\"services\":[],\"skipped\":["
                + "{\"id\":\"postgres\",\"reason\":\"vidocq.pool.url is jdbc:h2, not PostgreSQL\"},"
                + "{\"id\":\"acme\",\"reason\":\"tried jdbc:x://***@h/db\"}]}"), json);
    }

    @Test
    void theFiveArgumentFormWritesNoSkippedProvider() {
        String json = StateFile.json("t", "running", Instant.EPOCH, List.of(), Map.of());

        assertTrue(json.endsWith(",\"services\":[],\"skipped\":[]}"), json);
    }

    @Test
    void writeIsAtomic(@TempDir Path dir) throws Exception {
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DevServicesSessionTest.java`, replace exactly:
```java
    /** A minimal {@link DevService} scripted to succeed or fail, recording how many times it was stopped. */
```
with:
```java
    @Test
    void bothStateFilesKeepWhyAProviderDidNotStart(@TempDir Path basedir) throws Exception {
        DevService h2 = new DevService() {
            @Override public String id() { return "postgres"; }
            @Override public boolean appliesWhen(DevServiceContext ctx) { return false; }
            @Override public String skipReason(DevServiceContext ctx) {
                return "vidocq.pool.url is jdbc:h2, not PostgreSQL";
            }
            @Override public Map<String, String> start(DevServiceContext ctx) { throw new AssertionError("started"); }
            @Override public void stop() { }
        };
        String skipped = "\"skipped\":[{\"id\":\"postgres\",\"reason\":\"vidocq.pool.url is jdbc:h2, not PostgreSQL\"}]";

        DevServicesSession s = DevServicesSession.open("vidocq:dev", basedir, List.of(h2), ctx(basedir), LOG, CLOCK);
        String running = Files.readString(s.stateFile());
        s.close();
        String stopped = Files.readString(s.stateFile());

        assertTrue(running.contains(skipped), running);
        assertTrue(stopped.contains(skipped), stopped);
    }

    /** A minimal {@link DevService} scripted to succeed or fail, recording how many times it was stopped. */
```

- [ ] **Step 2: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host test -Dtest='DevServiceManagerTest,StateFileTest,DevServicesSessionTest' 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `Skipped` / `skipped()`, no `json` with 6 arguments.

- [ ] **Step 3: The manager asks, logs and records**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServiceManager.java`, replace exactly:
```java
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceState;
```
with:
```java
import io.vidocq.runtime.devservices.spi.DevService;
import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.devservices.spi.DevServiceState;
```

Then replace exactly:
```java
    private final Map<String, String> providers = new LinkedHashMap<>();
```
with:
```java
    private final Map<String, String> providers = new LinkedHashMap<>();
    private final List<Skipped> skipped = new ArrayList<>();
```

Then replace exactly:
```java
                if (!p.appliesWhen(ctx)) {
                    log.log(System.Logger.Level.INFO, "DevService '" + p.id() + "' skipped (already configured)");
                    continue;
                }
```
with:
```java
                if (!p.appliesWhen(ctx)) {
                    String reason = skipReason(p, ctx, log);
                    if (reason == null) {
                        log.log(System.Logger.Level.INFO, "DevService '" + p.id() + "' skipped (already configured)");
                    } else {
                        log.log(System.Logger.Level.INFO, "DevService '" + p.id() + "' not started: " + reason);
                        mgr.skipped.add(new Skipped(p.id(), reason));
                    }
                    continue;
                }
```

Then replace exactly:
```java
    /** The {@code key=value} pairs to expose to the child JVM (provider outputs only). */
```
with:
```java
    /**
     * A provider that did not start and said why ({@link DevService#skipReason}).
     *
     * @param id     the provider's id
     * @param reason one line, credentials masked
     */
    public record Skipped(String id, String reason) {}

    /** The providers that did not start and said why, in start order; one that gave no reason is not here. */
    public List<Skipped> skipped() {
        return Collections.unmodifiableList(skipped);
    }

    /**
     * {@code p.skipReason(ctx)} as one line — stripped, every run of whitespace a single space — with a URL's
     * credentials masked ({@link SecretMasking#withoutCredentials}); {@code null} when it gives none or a blank one,
     * or throws: then a WARNING names the provider and the exception's class, never its message.
     */
    static String skipReason(DevService p, DevServiceContext ctx, System.Logger log) {
        String reason;
        try {
            reason = p.skipReason(ctx);
        } catch (RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "DevService '" + p.id() + "' skipReason() threw "
                    + e.getClass().getName());
            return null;
        }
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return SecretMasking.withoutCredentials(reason.strip().replaceAll("\\s+", " "));
    }

    /** The {@code key=value} pairs to expose to the child JVM (provider outputs only). */
```

- [ ] **Step 4: The state file writes them**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/StateFile.java`, replace exactly:
```java
 * {@code startedAt}, {@code services[id, image, endpoints, injected[key + value|configured]]}, and no library is
```
with:
```java
 * {@code startedAt}, {@code services[id, image, endpoints, injected[key + value|configured]]},
 * {@code skipped[id, reason]}, and no library is
```

Then replace exactly:
```java
    public static String json(String host, String state, Instant startedAt, List<DevServiceState> services,
            Map<String, String> injected) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"host\":").append(str(host))
                .append(",\"state\":").append(str(state))
                .append(",\"startedAt\":").append(str(startedAt.toString()))
                .append(",\"services\":[");
        for (int i = 0; i < services.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            appendService(b, services.get(i), injected);
        }
        return b.append("]}").toString();
    }
```
with:
```java
    public static String json(String host, String state, Instant startedAt, List<DevServiceState> services,
            Map<String, String> injected) {
        return json(host, state, startedAt, services, injected, List.of());
    }

    /**
     * {@link #json(String, String, Instant, List, Map)} with the providers that did not start and why
     * ({@link DevServiceManager#skipped()}), after the services: {@code "skipped":[{"id":…,"reason":…}]}, {@code []}
     * when there are none. A reason goes through {@link SecretMasking#withoutCredentials}, as every other value.
     */
    public static String json(String host, String state, Instant startedAt, List<DevServiceState> services,
            Map<String, String> injected, List<DevServiceManager.Skipped> skipped) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"host\":").append(str(host))
                .append(",\"state\":").append(str(state))
                .append(",\"startedAt\":").append(str(startedAt.toString()))
                .append(",\"services\":[");
        for (int i = 0; i < services.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            appendService(b, services.get(i), injected);
        }
        b.append("],\"skipped\":[");
        for (int i = 0; i < skipped.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            DevServiceManager.Skipped s = skipped.get(i);
            b.append("{\"id\":").append(str(s.id()))
                    .append(",\"reason\":").append(str(SecretMasking.withoutCredentials(s.reason()))).append('}');
        }
        return b.append("]}").toString();
    }
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java`, replace exactly:
```java
            StateFile.write(stateFile, StateFile.json(host, "running", startedAt, states, mgr.collectedProperties()));
```
with:
```java
            StateFile.write(stateFile,
                    StateFile.json(host, "running", startedAt, states, mgr.collectedProperties(), mgr.skipped()));
```

Then replace exactly:
```java
                    StateFile.json(host, "stopped", startedAt, mgr.states(), mgr.collectedProperties()));
```
with:
```java
                    StateFile.json(host, "stopped", startedAt, mgr.states(), mgr.collectedProperties(),
                            mgr.skipped()));
```

- [ ] **Step 5: Run the host's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 6: Write the failing extension tests**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/test/java/io/vidocq/runtime/devservices/extension/StateReaderTest.java`, replace exactly:
```java
import java.util.Map;
```
with:
```java
import java.util.List;
import java.util.Map;
```

Then replace exactly:
```java
    @Test
    void rejectsWhatItCannotRead() {
```
with:
```java
    /** Review Focus: a file written before "skipped" existed, and hand-edited entries, read without a surprise. */
    @Test
    void readsTheSkippedProvidersAndAnOlderFileHasNone() {
        DevServicesSnapshot s = StateReader.parse("""
            {"host":"vidocq:dev","state":"running","startedAt":"x","services":[],"skipped":[\
            {"id":"postgres","reason":"vidocq.pool.url is jdbc:h2, not PostgreSQL"},{"id":"acme","reason":null},\
            {"reason":"an entry without an id is dropped"},{"id":" ","reason":"so is a blank one"}]}""");

        assertEquals(List.of(
                new DevServicesSnapshot.Skipped("postgres", "vidocq.pool.url is jdbc:h2, not PostgreSQL"),
                new DevServicesSnapshot.Skipped("acme", null)), s.skipped());
        assertEquals(List.of(), StateReader.parse(JSON).skipped(), "a file written before skipped existed");
        assertEquals(List.of(), DevServicesSnapshot.NONE.skipped());
    }

    @Test
    void rejectsWhatItCannotRead() {
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/test/java/io/vidocq/runtime/devservices/extension/DevServicesSectionTest.java`, replace exactly:
```java
    @Test
    void valuesAreShownInADevLaunchOnly() {
```
with:
```java
    @Test
    void aProviderNotStartedIsARowAtEveryVerbosityAndNothingStartedSaysSo() {
        DevServicesSnapshot snapshot = StateReader.parse("""
            {"host":"vidocq:dev","state":"running","startedAt":"x","services":[],"skipped":[\
            {"id":"postgres","reason":"vidocq.pool.url is jdbc:h2, not PostgreSQL"}]}""");
        for (Verbosity verbosity : List.of(Verbosity.SUMMARY, Verbosity.DETAILED)) {
            RecordingSection section = new RecordingSection();
            DevServicesSection.write(snapshot, null, null, new FakeReportContext(verbosity), section);
            assertEquals("no dev service started", section.summary, verbosity.name());
            assertEquals("not started: vidocq.pool.url is jdbc:h2, not PostgreSQL", section.rows.get("postgres"),
                    verbosity.name());
            assertNull(section.rows.get("started"), "nothing started: no started row");
            assertTrue(section.anomalies.isEmpty());
        }
    }

    /** Review Focus: a hand-edited entry without a reason shows "not started" and no "null" anywhere. */
    @Test
    void aProviderNotStartedBesideAStartedOneKeepsTheSummaryAndAddsItsRow() {
        DevServicesSnapshot snapshot = StateReader.parse("""
            {"host":"vidocq:dev","state":"running","startedAt":"2026-09-24T10:12:03Z","services":[{"id":"keycloak",\
            "image":"quay.io/keycloak/keycloak:26","endpoints":{"issuer":"http://localhost:8180/realms/vidocq"},\
            "injected":[]}],"skipped":[{"id":"postgres","reason":null}]}""");
        RecordingSection section = new RecordingSection();

        DevServicesSection.write(snapshot, null, null, new FakeReportContext(Verbosity.SUMMARY), section);

        assertEquals("1 service: keycloak (quay.io/keycloak/keycloak:26 at http://localhost:8180/realms/vidocq)"
                + " — vidocq:dev", section.summary);
        assertEquals("2026-09-24T10:12:03Z by vidocq:dev", section.rows.get("started"));
        assertEquals("not started", section.rows.get("postgres"));
        assertFalse(section.everything().contains("null"), section.everything());
    }

    @Test
    void anEmptySkippedListKeepsTheNoStateFileSummary() {
        DevServicesSnapshot snapshot = StateReader.parse(
                "{\"host\":\"test\",\"state\":\"running\",\"startedAt\":\"x\",\"services\":[],\"skipped\":[]}");
        RecordingSection section = new RecordingSection();

        DevServicesSection.write(snapshot, null, null, new FakeReportContext(Verbosity.DETAILED), section);

        assertEquals("no dev service: not started by vidocq:dev, vidocq:run or the test launcher", section.summary);
    }

    @Test
    void valuesAreShownInADevLaunchOnly() {
```

- [ ] **Step 7: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-extension test -Dtest='StateReaderTest,DevServicesSectionTest' 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `skipped()` / `DevServicesSnapshot.Skipped`.

- [ ] **Step 8: The snapshot, the reader, the section**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/DevServicesSnapshot.java`, replace exactly:
```java
 * @param services   the services the host started, in file order
 */
public record DevServicesSnapshot(String host, String state, String startedAt, List<Service> services) {

    /** No state property, no file, or a missing file: not an anomaly, see {@link DevServicesSection}. */
    public static final DevServicesSnapshot NONE = new DevServicesSnapshot(null, null, null, List.of());

    public DevServicesSnapshot {
        services = services == null ? List.of() : List.copyOf(services);
    }
```
with:
```java
 * @param services   the services the host started, in file order
 * @param skipped    the providers that did not start and said why, in file order; empty for a file written before
 *                   the host kept them
 */
public record DevServicesSnapshot(String host, String state, String startedAt, List<Service> services,
        List<Skipped> skipped) {

    /** No state property, no file, or a missing file: not an anomaly, see {@link DevServicesSection}. */
    public static final DevServicesSnapshot NONE = new DevServicesSnapshot(null, null, null, List.of(), List.of());

    public DevServicesSnapshot {
        services = services == null ? List.of() : List.copyOf(services);
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /** A snapshot with no skipped provider. */
    public DevServicesSnapshot(String host, String state, String startedAt, List<Service> services) {
        this(host, state, startedAt, services, List.of());
    }
```

Then replace exactly:
```java
    public record Injected(String key, String value, boolean configured) {}
}
```
with:
```java
    public record Injected(String key, String value, boolean configured) {}

    /**
     * A provider the host did not start.
     *
     * @param id     the provider's id, such as {@code postgres}
     * @param reason why, one line with no secret; {@code null} only in a hand-edited file
     */
    public record Skipped(String id, String reason) {}
}
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/StateReader.java`, replace exactly:
```java
        return new DevServicesSnapshot(
                asString(root.get("host")), asString(root.get("state")), asString(root.get("startedAt")), services);
    }
```
with:
```java
        List<DevServicesSnapshot.Skipped> skipped = new ArrayList<>();
        if (root.get("skipped") instanceof List<?> list) {
            for (Object item : list) {
                Map<?, ?> entry = asObject(item, "a skipped provider");
                String id = asString(entry.get("id"));
                if (id != null && !id.isBlank()) {
                    skipped.add(new DevServicesSnapshot.Skipped(id, asString(entry.get("reason"))));
                }
            }
        }
        return new DevServicesSnapshot(asString(root.get("host")), asString(root.get("state")),
                asString(root.get("startedAt")), services, skipped);
    }
```

In `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/DevServicesSection.java`, replace exactly:
```java
        List<DevServicesSnapshot.Service> services = snapshot.services();
        if (services.isEmpty()) {
            section.summary("no dev service: not started by vidocq:dev, vidocq:run or the test launcher");
            return;
        }

        section.summary(summaryLine(snapshot, services));
        section.row("started", snapshot.startedAt() + " by " + snapshot.host());
```
with:
```java
        List<DevServicesSnapshot.Service> services = snapshot.services();
        List<DevServicesSnapshot.Skipped> skipped = snapshot.skipped();
        if (services.isEmpty()) {
            if (skipped.isEmpty()) {
                section.summary("no dev service: not started by vidocq:dev, vidocq:run or the test launcher");
                return;
            }
            section.summary("no dev service started");
            writeSkipped(skipped, section);
            return;
        }

        section.summary(summaryLine(snapshot, services));
        section.row("started", snapshot.startedAt() + " by " + snapshot.host());
        writeSkipped(skipped, section);
```

Then replace exactly:
```java
    private static String summaryLine(DevServicesSnapshot snapshot, List<DevServicesSnapshot.Service> services) {
```
with:
```java
    /**
     * One row per provider that did not start, at every verbosity (spec 2026-09-29-devservice-postgres-kind §4):
     * {@code not started: <reason>}, or {@code not started} alone when the file gives no reason.
     */
    private static void writeSkipped(List<DevServicesSnapshot.Skipped> skipped, StartupReportSection section) {
        for (DevServicesSnapshot.Skipped provider : skipped) {
            String reason = blankToNull(provider.reason());
            section.row(provider.id(), reason == null ? "not started" : "not started: " + reason);
        }
    }

    private static String summaryLine(DevServicesSnapshot snapshot, List<DevServicesSnapshot.Service> services) {
```

- [ ] **Step 9: Run the extension's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devservices/vidocq-runtime-devservices-extension 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 10: Commit**

Message:
```
feat(devservices): a provider's reason for not starting, in the log, the state file and the report

When a provider does not apply, the manager asks its skipReason: with a
reason, it logs "DevService '<id>' not started: <reason>" (one line,
credentials masked) and records it; without one, the old "skipped
(already configured)" line. The state file keeps them as "skipped", the
extension reads them (an older file has none) and the devservices section
shows a "not started" row per provider at every verbosity, with the
summary "no dev service started" when nothing started.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git commit -S -F .git/PLAN_COMMIT_MSG --only -- vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServiceManager.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/StateFile.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DevServiceManagerTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/StateFileTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-host/src/test/java/io/vidocq/runtime/devservices/host/DevServicesSessionTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/DevServicesSnapshot.java vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/StateReader.java vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/main/java/io/vidocq/runtime/devservices/extension/DevServicesSection.java vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/test/java/io/vidocq/runtime/devservices/extension/StateReaderTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-extension/src/test/java/io/vidocq/runtime/devservices/extension/DevServicesSectionTest.java && rm .git/PLAN_COMMIT_MSG
```

---

### Task 4: The PostgreSQL rule (spec §2)

**Files:**
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/main/java/io/vidocq/runtime/devservices/postgres/PostgresDevService.java:44-46,59,75-78,150-168`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/test/java/io/vidocq/runtime/devservices/postgres/PostgresDevServiceTest.java:144,195-206`
- Create: `vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/src/main/resources/vidocq.properties`

**Interfaces:**
- Consumes: `DevServiceContext.applicationProperty` / `onApplicationClasspath`, `DevService.skipReason` (Task 1).
- Produces: `static final String PostgresDevService.DRIVER = "org.postgresql.Driver"`; `record
  PostgresDevService.Decision(String name, DatasourcePlan plan, String reason)`; `static List<Decision>
  PostgresDevService.decide(DevServiceContext)`; `static String PostgresDevService.scheme(String url)`;
  `skipReason` overridden; `plan(DevServiceContext)` unchanged in signature. The provider jar installed.

- [ ] **Step 1: The test context learns the application's file and its driver**

In `vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/test/java/io/vidocq/runtime/devservices/postgres/PostgresDevServiceTest.java`, replace exactly:
```java
    private static DevServiceContext ctx(Map<String, String> props) {
        return new DevServiceContext() {
            @Override public Optional<String> property(String key) {
                String v = props.get(key);
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
            @Override public Map<String, String> properties() { return props; }
            @Override public Path basedir() { return Path.of("."); }
            @Override public Path resolve(String relative) { return Path.of(".").resolve(relative); }
            @Override public System.Logger log() { return System.getLogger("test"); }
        };
    }
```
with:
```java
    /** An application on PostgreSQL by its driver, as every test written before the rule assumes. */
    private static DevServiceContext ctx(Map<String, String> props) {
        return ctx(props, Map.of(), true);
    }

    /**
     * As {@code DefaultDevServiceContext} answers: {@code props} are the explicit values; {@code file} the
     * application's own, answering {@code applicationProperty} for any key and {@code property} for a
     * {@code vidocq.dev.} key only; {@code driver} whether {@code org.postgresql.Driver} is on the class path.
     */
    private static DevServiceContext ctx(Map<String, String> props, Map<String, String> file, boolean driver) {
        return new DevServiceContext() {
            @Override public Optional<String> property(String key) {
                String v = props.get(key);
                if ((v == null || v.isBlank()) && key.startsWith("vidocq.dev.")) {
                    v = file.get(key);
                }
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
            @Override public Optional<String> applicationProperty(String key) {
                String v = file.get(key);
                return (v == null || v.isBlank()) ? Optional.empty() : Optional.of(v);
            }
            @Override public boolean onApplicationClasspath(String className) {
                return driver && PostgresDevService.DRIVER.equals(className);
            }
            @Override public Map<String, String> properties() { return props; }
            @Override public Path basedir() { return Path.of("."); }
            @Override public Path resolve(String relative) { return Path.of(".").resolve(relative); }
            @Override public System.Logger log() { return System.getLogger("test"); }
        };
    }

    private static List<String> names(List<PostgresDevService.DatasourcePlan> plan) {
        return plan.stream().map(PostgresDevService.DatasourcePlan::name).toList();
    }
```

- [ ] **Step 2: Write the failing tests**

In the same file, replace exactly:
```java
    // ---- describe (pure, no Docker) ----
```
with:
```java
    // ---- the rule: a container only for an application on PostgreSQL (spec 2026-09-29 §2) ----

    @Test
    void anExplicitUrlStartsNoContainerAndGivesNoReason() {
        DevServiceContext ctx = ctx(Map.of("vidocq.pool.url", "jdbc:h2:mem:x"),
                Map.of("vidocq.pool.url", "jdbc:postgresql://prod:5432/db"), true);
        PostgresDevService svc = new PostgresDevService();

        assertFalse(svc.appliesWhen(ctx));
        assertNull(svc.skipReason(ctx), "rule 1: the host keeps its 'already configured' line");
    }

    @Test
    void aFileUrlOfAnotherDatabaseStartsNoContainerAndNamesItsScheme() {
        Map<String, String> schemes = Map.of(
                "jdbc:h2:mem:x", "jdbc:h2",
                "jdbc:mysql://h/db", "jdbc:mysql",
                "JDBC:H2:mem:x", "JDBC:H2",
                "  jdbc:mariadb://h/db  ", "jdbc:mariadb",
                "jdbc:tc:postgresql:16:///db", "jdbc:tc");
        schemes.forEach((url, scheme) -> {
            DevServiceContext ctx = ctx(Map.of(), Map.of("vidocq.pool.url", url), true);
            PostgresDevService svc = new PostgresDevService();

            assertFalse(svc.appliesWhen(ctx), url);
            assertEquals("vidocq.pool.url is " + scheme + ", not PostgreSQL", svc.skipReason(ctx), url);
        });
    }

    @Test
    void aFilePostgresUrlStartsAContainerWhateverTheDriverAndTheCase() {
        for (String url : List.of("jdbc:postgresql://prod:5432/db", "JDBC:PostgreSQL://prod:5432/db")) {
            assertEquals(List.of("default"),
                    names(PostgresDevService.plan(ctx(Map.of(), Map.of("vidocq.pool.url", url), false))), url);
        }
    }

    @Test
    void noUrlStartsAContainerOnlyWithTheDriverOnTheClassPath() {
        assertEquals(List.of("default"), names(PostgresDevService.plan(ctx(Map.of(), Map.of(), true))));

        DevServiceContext without = ctx(Map.of(), Map.of(), false);
        PostgresDevService svc = new PostgresDevService();
        assertFalse(svc.appliesWhen(without));
        assertEquals("no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path",
                svc.skipReason(without));
    }

    /** Review Focus: a value that is not a jdbc: URL — an expression the dev host never resolves — is rule 4. */
    @Test
    void aPlaceholderInTheFileIsNoUrlAtAll() {
        Map<String, String> file = Map.of("vidocq.pool.url", "${db.url}");

        assertEquals(List.of("default"), names(PostgresDevService.plan(ctx(Map.of(), file, true))),
                "with the driver: a container");
        assertEquals("no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path",
                new PostgresDevService().skipReason(ctx(Map.of(), file, false)));
    }

    /** Review Focus: nothing of the URL past its scheme reaches a reason (the log, the state file, the report). */
    @Test
    void aReasonNeverHoldsAnythingPastTheScheme() {
        String longName = "a".repeat(60);
        Map<String, String> schemes = Map.of(
                "jdbc:mysql://admin:s3cret@db.internal:3306/app?password=hunter2", "jdbc:mysql",
                "jdbc:oracle:thin:scott/tiger@db.internal:1521/XE", "jdbc:oracle",
                "jdbc:x@s3cret.internal/db", "jdbc:x",
                "jdbc:" + longName, "jdbc:" + "a".repeat(27));
        schemes.forEach((url, scheme) -> {
            String reason = new PostgresDevService().skipReason(ctx(Map.of(), Map.of("vidocq.pool.url", url), true));

            assertEquals("vidocq.pool.url is " + scheme + ", not PostgreSQL", reason, url);
            for (String secret : List.of("admin", "s3cret", "hunter2", "scott", "tiger", "internal")) {
                assertFalse(reason.contains(secret), reason);
            }
        });
    }

    @Test
    void aNamedDatasourceFollowsTheSameRuleUnderItsOwnKey() {
        DevServiceContext ctx = ctx(Map.of("vidocq.dev.postgres.datasources", "audit,analytics"),
                Map.of("vidocq.pool.url", "jdbc:postgresql://prod/app", "vidocq.pool.audit.url", "jdbc:h2:mem:audit"),
                false);

        List<PostgresDevService.Decision> decisions = PostgresDevService.decide(ctx);

        assertEquals(List.of("default", "audit", "analytics"),
                decisions.stream().map(PostgresDevService.Decision::name).toList());
        assertEquals(List.of("default"), names(PostgresDevService.plan(ctx)));
        assertEquals("vidocq.pool.audit.url is jdbc:h2, not PostgreSQL", decisions.get(1).reason());
        assertEquals("no vidocq.pool.analytics.url and no PostgreSQL driver (org.postgresql.Driver) on the class path",
                decisions.get(2).reason());
        assertTrue(new PostgresDevService().appliesWhen(ctx), "the default datasource still gets its container");
    }

    /** Review Focus: the list of names and the named URL both in the application's file only. */
    @Test
    void aNamedDatasourceDeclaredOnlyInTheFileIsDecidedByTheFile() {
        DevServiceContext ctx = ctx(Map.of(),
                Map.of("vidocq.dev.postgres.datasources", "audit", "vidocq.pool.audit.url", "jdbc:h2:mem:audit"),
                false);
        PostgresDevService svc = new PostgresDevService();

        assertFalse(svc.appliesWhen(ctx));
        assertEquals("no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path; "
                + "vidocq.pool.audit.url is jdbc:h2, not PostgreSQL", svc.skipReason(ctx));
    }

    // ---- describe (pure, no Docker) ----
```

- [ ] **Step 3: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devservices/vidocq-runtime-devservice-postgres test -Dtest=PostgresDevServiceTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `DRIVER`, `Decision`, `decide`.

- [ ] **Step 4: The rule**

In `vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/main/java/io/vidocq/runtime/devservices/postgres/PostgresDevService.java`, replace exactly:
```java
 * — it cannot derive which names the application wants. Each datasource opts out individually: the
 * {@code @Default} is skipped when {@code vidocq.pool.url} is set, a named one when
 * {@code vidocq.pool.<name>.url} is set, so a developer pointing at their own database is never overridden.</p>
```
with:
```java
 * — it cannot derive which names the application wants. Each datasource is decided individually ({@link #decide}):
 * no container when its URL is given explicitly, so a developer pointing at their own database is never overridden,
 * nor when the application's file names another database; a container when the file names PostgreSQL, or names
 * nothing and the PostgreSQL driver is on the application's class path.</p>
```

Then replace exactly:
```java
    private static final String DEFAULT_NAME     = "default";
```
with:
```java
    private static final String DEFAULT_NAME     = "default";

    /** On the application's class path, the sign that it talks to PostgreSQL when no URL says so (rule 4). */
    static final String DRIVER = "org.postgresql.Driver";

    /** The longest scheme a reason shows, {@code jdbc:} included. */
    private static final int MAX_SCHEME = 32;
```

Then replace exactly:
```java
    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return !plan(ctx).isEmpty();
    }
```
with:
```java
    @Override
    public boolean appliesWhen(DevServiceContext ctx) {
        return !plan(ctx).isEmpty();
    }

    /**
     * Why no datasource gets a container: the reasons of the datasources that have one, in {@link #decide}'s order,
     * joined with {@code "; "}; {@code null} when every one was given explicitly.
     */
    @Override
    public String skipReason(DevServiceContext ctx) {
        List<String> reasons = new ArrayList<>();
        for (Decision decision : decide(ctx)) {
            if (decision.reason() != null) {
                reasons.add(decision.reason());
            }
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }
```

Then replace exactly:
```java
    /**
     * The datasources this provider must create: the {@code @Default} (unless {@code vidocq.pool.url} is set)
     * plus every name in {@code vidocq.dev.postgres.datasources} whose {@code vidocq.pool.<name>.url} is not
     * already configured. Names are single-segment; blank and dotted entries are ignored.
     */
    static List<DatasourcePlan> plan(DevServiceContext ctx) {
        List<DatasourcePlan> out = new ArrayList<>();
        if (ctx.property("vidocq.pool.url").isEmpty()) {
            out.add(specFor(ctx, DEFAULT_NAME, "vidocq.pool.", "vidocq.dev.postgres."));
        }
        for (String name : parseNames(ctx.property("vidocq.dev.postgres.datasources").orElse(""))) {
            String poolPrefix = "vidocq.pool." + name + ".";
            if (ctx.property(poolPrefix + "url").isPresent()) {
                continue; // the application configured this named datasource itself
            }
            out.add(specFor(ctx, name, poolPrefix, "vidocq.dev.postgres." + name + "."));
        }
        return out;
    }
```
with:
```java
    /**
     * What the rule decided for one datasource: a plan to provision, or none — with the reason, or {@code null}
     * when its URL was given explicitly.
     */
    record Decision(String name, DatasourcePlan plan, String reason) {
    }

    /** The datasources this provider must create: those of {@link #decide} that have a plan, in its order. */
    static List<DatasourcePlan> plan(DevServiceContext ctx) {
        List<DatasourcePlan> out = new ArrayList<>();
        for (Decision decision : decide(ctx)) {
            if (decision.plan() != null) {
                out.add(decision.plan());
            }
        }
        return out;
    }

    /**
     * The rule (spec 2026-09-29-devservice-postgres-kind §2) for the {@code @Default} datasource
     * ({@code vidocq.pool.url}), then for every name of {@code vidocq.dev.postgres.datasources}
     * ({@code vidocq.pool.<name>.url}; names are single-segment, blank and dotted entries are ignored):
     * <ol>
     *   <li>the URL is given explicitly ({@link DevServiceContext#property}): no container, no reason;</li>
     *   <li>the application's file gives a {@code jdbc:} URL that is not {@code jdbc:postgresql:} (any case): no
     *       container, the reason naming the key and the URL's {@link #scheme} only;</li>
     *   <li>the file gives a {@code jdbc:postgresql:} URL, the production one: a container, whose URL replaces it
     *       under the dev host;</li>
     *   <li>no URL, or a value that is not a {@code jdbc:} URL (such as {@code ${db.url}}): a container only when
     *       {@value #DRIVER} is on the application's class path.</li>
     * </ol>
     */
    static List<Decision> decide(DevServiceContext ctx) {
        List<Decision> out = new ArrayList<>();
        out.add(decide(ctx, DEFAULT_NAME, "vidocq.pool.", "vidocq.dev.postgres."));
        for (String name : parseNames(ctx.property("vidocq.dev.postgres.datasources").orElse(""))) {
            out.add(decide(ctx, name, "vidocq.pool." + name + ".", "vidocq.dev.postgres." + name + "."));
        }
        return out;
    }

    private static Decision decide(DevServiceContext ctx, String name, String poolPrefix, String devPrefix) {
        String urlKey = poolPrefix + "url";
        if (ctx.property(urlKey).isPresent()) {
            return new Decision(name, null, null); // rule 1: the developer's own database
        }
        String fileUrl = ctx.applicationProperty(urlKey).map(String::strip).orElse("");
        if (startsWithIgnoringCase(fileUrl, "jdbc:")) {
            if (!startsWithIgnoringCase(fileUrl, "jdbc:postgresql:")) {
                return new Decision(name, null, urlKey + " is " + scheme(fileUrl) + ", not PostgreSQL");
            }
            return new Decision(name, specFor(ctx, name, poolPrefix, devPrefix), null);
        }
        if (!ctx.onApplicationClasspath(DRIVER)) {
            return new Decision(name, null,
                    "no " + urlKey + " and no PostgreSQL driver (" + DRIVER + ") on the class path");
        }
        return new Decision(name, specFor(ctx, name, poolPrefix, devPrefix), null);
    }

    /**
     * {@code jdbc:} and the name after it, never more: the run of ASCII letters, digits, {@code -} and {@code _}
     * after {@code jdbc:}, so it stops at the second {@code :} and before any host, user or password, and at most
     * {@value #MAX_SCHEME} characters. As written, case included: {@code JDBC:H2}.
     */
    static String scheme(String url) {
        int end = "jdbc:".length();
        int max = Math.min(url.length(), MAX_SCHEME);
        while (end < max && isNameCharacter(url.charAt(end))) {
            end++;
        }
        return url.substring(0, end);
    }

    private static boolean isNameCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    private static boolean startsWithIgnoringCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }
```

- [ ] **Step 5: The IT application says it is on PostgreSQL (Ruling 10)**

Create `vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/src/main/resources/vidocq.properties`:
```properties
# This application is on PostgreSQL: its tests query the dev service's database with java.sql. The driver is a
# test dependency only, so vidocq:run and vidocq:dev, which see the runtime class path, learn it from this URL:
# a jdbc:postgresql: URL in the application's file gets a dev container, whose URL replaces this one
# (docs/superpowers/specs/2026-09-29-devservice-postgres-kind-design.md, rule 3). Never connected to as is.
vidocq.pool.url=jdbc:postgresql://localhost:5432/vidocq
```

- [ ] **Step 6: Run the provider's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devservices/vidocq-runtime-devservice-postgres 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`; the two Docker-gated tests start real containers.) Expected: `BUILD SUCCESS`, no failure, no
test skipped for want of Docker.

- [ ] **Step 7: Commit**

Message:
```
feat(devservices): the PostgreSQL dev service starts only for an application on PostgreSQL

For the default datasource and each named one: an explicit URL starts no
container, as before; a jdbc: URL of another database in the
application's file starts none either, and says so with the scheme only
("vidocq.pool.url is jdbc:h2, not PostgreSQL"); a jdbc:postgresql: URL
there is the production one and still gets its container; with no URL,
a container only when org.postgresql.Driver is on the application's class
path. The reasons are joined with "; " when nothing starts.

The dev services IT application gains a PostgreSQL URL in its file: its
driver is test-scoped, so vidocq:run and vidocq:dev learn it from there.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/src/main/resources/vidocq.properties && git commit -S -F .git/PLAN_COMMIT_MSG --only -- vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/main/java/io/vidocq/runtime/devservices/postgres/PostgresDevService.java vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/test/java/io/vidocq/runtime/devservices/postgres/PostgresDevServiceTest.java vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/src/main/resources/vidocq.properties && rm .git/PLAN_COMMIT_MSG
```

---

### Task 5: The four hosts (spec §5)

**Files:**
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/ApplicationLaunch.java:122-124`
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqRunMojo.java:22,236-238`
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java:22,342-344`
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqTestMojo.java`
- Modify: `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml:94-126` (the `test` mojo)
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservices-junit/src/main/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListener.java`
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/ApplicationLaunchTest.java`,
  `.../maven/dev/VidocqTestMojoTest.java`, `.../maven/dev/PluginDescriptorContinuousTestingTest.java`,
  `vidocq-runtime-devservices/vidocq-runtime-devservices-junit/src/test/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListenerTest.java`
  (all modify)

**Interfaces:**
- Consumes: `ApplicationFiles.allOf`, `ApplicationClasspath`, the 7-argument `DevServicesSession.open` (Task 2,
  installed with Task 3).
- Produces: `public static List<Path> ApplicationLaunch.classpathOf(List<Path> modulePath, Path classesDir)`;
  `List<Path> VidocqTestMojo.testClasspath() throws MojoExecutionException` and `void setProject(MavenProject)`
  (package-private); `static Predicate<String> DevServicesSessionListener.onTestClasspath(ClassLoader loader)`
  (package-private). The junit and plugin jars installed.

- [ ] **Step 1: Write the failing JUnit-host test**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-junit/src/test/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListenerTest.java`, replace exactly:
```java
import java.nio.file.Files;
```
with:
```java
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
```

Then replace exactly:
```java
import static org.junit.jupiter.api.Assertions.assertEquals;
```
with:
```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
```

Then replace exactly:
```java
    private static void writeVidocqProperties(Path basedir, String content) throws Exception {
```
with:
```java
    /**
     * Review Focus: the test JVM's class path is asked through the context class loader, the driver a test
     * dependency; without one, the listener's own class loader answers.
     */
    @Test
    void theDevServicesSeeTheTestClassPathThroughTheContextClassLoader(@TempDir Path dir) throws Exception {
        Path driver = dir.resolve("org/postgresql/Driver.class");
        Files.createDirectories(driver.getParent());
        Files.write(driver, new byte[0]);

        try (URLClassLoader loader =
                new URLClassLoader(new URL[] {dir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertTrue(DevServicesSessionListener.onTestClasspath(loader).test("org.postgresql.Driver"));
            assertFalse(DevServicesSessionListener.onTestClasspath(loader).test("com.mysql.cj.jdbc.Driver"));
        }
        assertFalse(DevServicesSessionListener.onTestClasspath(ClassLoader.getPlatformClassLoader())
                .test("org.postgresql.Driver"));
        assertTrue(DevServicesSessionListener.onTestClasspath(null)
                .test("org.junit.platform.launcher.LauncherSessionListener"), "no context loader: the listener's own");
    }

    private static void writeVidocqProperties(Path basedir, String content) throws Exception {
```

- [ ] **Step 2: Run it to see it fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-devservices/vidocq-runtime-devservices-junit test -Dtest=DevServicesSessionListenerTest 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `onTestClasspath`.

- [ ] **Step 3: The JUnit host**

In `vidocq-runtime-devservices/vidocq-runtime-devservices-junit/src/main/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListener.java`, replace exactly:
```java
import java.util.function.Function;
```
with:
```java
import java.util.function.Function;
import java.util.function.Predicate;
```

Then replace exactly:
```java
        this(basedir -> {
            try {
                return DevServicesSession.open(
                        "test",
                        basedir,
                        Map.of(),
                        ApplicationFiles.of(basedir.resolve("target").resolve("classes")),
                        System.getLogger("vidocq.test.devservices"));
```
with:
```java
        this(basedir -> {
            try {
                Path classes = basedir.resolve("target").resolve("classes");
                return DevServicesSession.open(
                        "test",
                        basedir,
                        Map.of(),
                        ApplicationFiles.of(classes),
                        ApplicationFiles.allOf(classes),
                        onTestClasspath(Thread.currentThread().getContextClassLoader()),
                        System.getLogger("vidocq.test.devservices"));
```

Then replace exactly:
```java
    /** For {@code vidocq-runtime-devservices-junit}'s own tests, which supply a fake opener. */
```
with:
```java
    /**
     * The test JVM's own class path (spec 2026-09-29-devservice-postgres-kind §5), through {@code loader} — the
     * thread context class loader — or this class's own loader when there is none: whether it holds a class, found as
     * a {@code .class} resource (visible even inside a named module), never loaded.
     */
    static Predicate<String> onTestClasspath(ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : DevServicesSessionListener.class.getClassLoader();
        return className -> effective.getResource(className.replace('.', '/') + ".class") != null;
    }

    /** For {@code vidocq-runtime-devservices-junit}'s own tests, which supply a fake opener. */
```

- [ ] **Step 4: Run the junit module's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devservices/vidocq-runtime-devservices-junit 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 5: Write the failing plugin tests**

In `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/ApplicationLaunchTest.java`, replace exactly:
```java
    @Test
    void dropDevOnlyDropsAMarkedJarAndWarnsWithItsArtifactId(@TempDir Path dir) throws Exception {
```
with:
```java
    /** What the dev services look into for a driver (spec 2026-09-29-devservice-postgres-kind §5). */
    @Test
    void theClasspathOfALaunchIsItsModulePathAndItsClassesOnce(@TempDir Path dir) {
        Path classes = dir.resolve("target/classes");
        Path lib = dir.resolve("lib.jar");

        assertEquals(List.of(lib, classes), ApplicationLaunch.classpathOf(List.of(lib), classes),
                "layer mode keeps the classes off the module path: they are added");
        assertEquals(List.of(classes, lib), ApplicationLaunch.classpathOf(List.of(classes, lib), classes),
                "never twice");
    }

    @Test
    void dropDevOnlyDropsAMarkedJarAndWarnsWithItsArtifactId(@TempDir Path dir) throws Exception {
```

In `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/VidocqTestMojoTest.java`, replace exactly:
```java
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
```
with:
```java
import io.vidocq.runtime.devservices.host.ApplicationClasspath;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
```

Then replace exactly:
```java
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
```
with:
```java
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
```

Then replace exactly:
```java
    @Test
    void closingNoSessionIsHarmless() {
```
with:
```java
    /** The PostgreSQL dev service looks for its driver where the tests run: the test class path (spec §5). */
    @Test
    void theDevServicesSeeTheTestClassPath(@TempDir Path dir) throws Exception {
        Path testClasses = dir.resolve("target/test-classes");
        Files.createDirectories(testClasses.resolve("org/postgresql"));
        Files.write(testClasses.resolve("org/postgresql/Driver.class"), new byte[0]);
        Path classes = dir.resolve("target/classes");
        VidocqTestMojo mojo = new VidocqTestMojo();
        mojo.setProject(new MavenProject() {
            @Override
            public List<String> getTestClasspathElements() {
                return List.of(testClasses.toString(), classes.toString());
            }
        });

        assertEquals(List.of(testClasses, classes), mojo.testClasspath());
        assertTrue(new ApplicationClasspath(mojo.testClasspath()).contains("org.postgresql.Driver"));
        assertEquals(List.of(), new VidocqTestMojo().testClasspath(), "no project: an empty class path");
    }

    @Test
    void closingNoSessionIsHarmless() {
```

In `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/PluginDescriptorContinuousTestingTest.java`, replace exactly:
```java
    static void assertConfigured(String goal, String name, String type, String expression, String defaultValue) {
```
with:
```java
    /** The PostgreSQL dev service looks for its driver on the test class path (spec 2026-09-29 §5). */
    @Test
    void theTestGoalResolvesTheTestClassPathAndGetsTheProject() throws Exception {
        Element mojo = mojo("test");
        assertEquals("test", text(mojo, "requiresDependencyResolution"));
        assertEquals("org.apache.maven.project.MavenProject", text(parameter(mojo, "project"), "type"));
        Element entry = child(child(mojo, "configuration"), "project");
        assertNotNull(entry, "<configuration> of test has no project entry");
        assertEquals("${project}", entry.getAttribute("default-value"));
        assertEquals("org.apache.maven.project.MavenProject", entry.getAttribute("implementation"));
        assertEquals("org.apache.maven.project.MavenProject",
                VidocqTestMojo.class.getDeclaredField("project").getType().getName());
    }

    static void assertConfigured(String goal, String name, String type, String expression, String defaultValue) {
```

- [ ] **Step 6: Run them to see them fail**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -q -pl vidocq-runtime-maven-plugin test -Dtest='ApplicationLaunchTest,VidocqTestMojoTest,PluginDescriptorContinuousTestingTest' 2>&1 | tail -20
```
Expected: `COMPILATION ERROR`, `cannot find symbol` … `classpathOf`, `setProject`, `testClasspath`.

- [ ] **Step 7: `ApplicationLaunch.classpathOf`, and `vidocq:dev` and `vidocq:run`**

In `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/ApplicationLaunch.java`, replace exactly:
```java
    public static List<Path> appPath(Path classesDir, boolean layerMode) {
        return layerMode ? List.of(classesDir) : List.of();
    }
```
with:
```java
    public static List<Path> appPath(Path classesDir, boolean layerMode) {
        return layerMode ? List.of(classesDir) : List.of();
    }

    /**
     * Everything the application's classes come from once launched: the module path as {@link #modulePath} built
     * it, plus the classes directory, which layer mode keeps off it — once. What a dev service looks into to learn
     * which driver the application has (spec 2026-09-29-devservice-postgres-kind §5); taken before the dev tools
     * join the module path.
     */
    public static List<Path> classpathOf(List<Path> modulePath, Path classesDir) {
        List<Path> entries = new ArrayList<>(modulePath);
        if (!entries.contains(classesDir)) {
            entries.add(classesDir);
        }
        return entries;
    }
```

In `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqRunMojo.java`, replace exactly:
```java
import io.vidocq.runtime.devservices.host.ApplicationFiles;
```
with:
```java
import io.vidocq.runtime.devservices.host.ApplicationClasspath;
import io.vidocq.runtime.devservices.host.ApplicationFiles;
```

Then replace exactly:
```java
            try {
                devs = DevServicesSession.open("vidocq:run", projectDir, systemProperties, applicationFiles,
                        System.getLogger("vidocq.run.devservices"));
```
with:
```java
            // What the child will see, taken before the dev services extension joins the module path (spec §5).
            ApplicationClasspath classpath = new ApplicationClasspath(ApplicationLaunch.classpathOf(modulePath, classes));
            try {
                devs = DevServicesSession.open("vidocq:run", projectDir, systemProperties, applicationFiles,
                        ApplicationFiles.allOf(classes), classpath::contains,
                        System.getLogger("vidocq.run.devservices"));
```

In `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java`, replace exactly:
```java
import io.vidocq.runtime.devservices.host.ApplicationFiles;
```
with:
```java
import io.vidocq.runtime.devservices.host.ApplicationClasspath;
import io.vidocq.runtime.devservices.host.ApplicationFiles;
```

Then replace exactly:
```java
            try {
                devs = DevServicesSession.open("vidocq:dev", projectDir, sysProps, applicationFiles,
                        System.getLogger("vidocq.dev.devservices"));
```
with:
```java
            // What the child will see, taken before the dev services extension and the dev tools join the module
            // path (spec 2026-09-29-devservice-postgres-kind §5).
            ApplicationClasspath classpath =
                    new ApplicationClasspath(ApplicationLaunch.classpathOf(modulePath, classesDir.toPath()));
            try {
                devs = DevServicesSession.open("vidocq:dev", projectDir, sysProps, applicationFiles,
                        ApplicationFiles.allOf(classesDir.toPath()), classpath::contains,
                        System.getLogger("vidocq.dev.devservices"));
```

- [ ] **Step 8: `vidocq:test` resolves and passes the test class path**

In `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqTestMojo.java`, replace exactly:
```java
import io.vidocq.runtime.devservices.host.ApplicationFiles;
```
with:
```java
import io.vidocq.runtime.devservices.host.ApplicationClasspath;
import io.vidocq.runtime.devservices.host.ApplicationFiles;
```

Then replace exactly:
```java
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
```
with:
```java
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
```

Then replace exactly:
```java
@Mojo(name = "test", defaultPhase = LifecyclePhase.NONE, threadSafe = false)
```
with:
```java
@Mojo(name = "test", defaultPhase = LifecyclePhase.NONE, requiresDependencyResolution = ResolutionScope.TEST,
        threadSafe = false)
```

Then replace exactly:
```java
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;
```
with:
```java
    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    /** The project, for its test class path: where the PostgreSQL dev service looks for its driver. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
```

Then replace exactly:
```java
        if (devServicesEnabled(files)) {
            try {
                devs = DevServicesSession.open(HOST, projectDir, new LinkedHashMap<>(), files,
                        System.getLogger("vidocq.test.devservices"));
```
with:
```java
        if (devServicesEnabled(files)) {
            ApplicationClasspath classpath = new ApplicationClasspath(testClasspath());
            try {
                devs = DevServicesSession.open(HOST, projectDir, new LinkedHashMap<>(), files,
                        ApplicationFiles.allOf(classesDir.toPath()), classpath::contains,
                        System.getLogger("vidocq.test.devservices"));
```

Then replace exactly:
```java
    void setDevServices(Boolean devServices) {
```
with:
```java
    /**
     * The test class path the tests run against (spec 2026-09-29-devservice-postgres-kind §5): an application on
     * PostgreSQL at runtime and on H2 in its tests is seen as its tests are. Empty without a project, as in the unit
     * tests that build this mojo by hand.
     */
    // package-private for the unit test.
    List<Path> testClasspath() throws MojoExecutionException {
        if (project == null) {
            return List.of();
        }
        try {
            List<Path> entries = new ArrayList<>();
            for (String element : project.getTestClasspathElements()) {
                entries.add(Path.of(element));
            }
            return entries;
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("vidocq:test needs the test class path: " + e.getMessage(), e);
        }
    }

    void setProject(MavenProject project) {
        this.project = project;
    }

    void setDevServices(Boolean devServices) {
```

In `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml`, replace exactly:
```xml
            <description>Continuous testing: run the application's tests on every change, without starting the application</description>
            <requiresDirectInvocation>true</requiresDirectInvocation>
```
with:
```xml
            <description>Continuous testing: run the application's tests on every change, without starting the application</description>
            <requiresDependencyResolution>test</requiresDependencyResolution>
            <requiresDirectInvocation>true</requiresDirectInvocation>
```

Then replace exactly:
```xml
                <parameter><name>buildDir</name><type>java.io.File</type><required>false</required><editable>false</editable><description>Build directory, where the results, the log and the Surefire reports are.</description></parameter>
```
with:
```xml
                <parameter><name>buildDir</name><type>java.io.File</type><required>false</required><editable>false</editable><description>Build directory, where the results, the log and the Surefire reports are.</description></parameter>
                <parameter><name>project</name><type>org.apache.maven.project.MavenProject</type><required>true</required><editable>false</editable><description>The Maven project, for its test class path: where the PostgreSQL dev service looks for its driver</description></parameter>
```

Then replace exactly:
```xml
                <devServices implementation="java.lang.Boolean">${vidocq.dev.devServices}</devServices>
                <classesDir implementation="java.io.File" default-value="${project.build.outputDirectory}"/>
                <baseDir implementation="java.io.File" default-value="${project.basedir}"/>
                <buildDir implementation="java.io.File" default-value="${project.build.directory}"/>
            </configuration>
```
with:
```xml
                <devServices implementation="java.lang.Boolean">${vidocq.dev.devServices}</devServices>
                <classesDir implementation="java.io.File" default-value="${project.build.outputDirectory}"/>
                <baseDir implementation="java.io.File" default-value="${project.basedir}"/>
                <buildDir implementation="java.io.File" default-value="${project.build.directory}"/>
                <project implementation="org.apache.maven.project.MavenProject" default-value="${project}"/>
            </configuration>
```
(That last find-block is the `test` mojo's: in the `run` mojo, `buildDir` is followed by `pluginArtifactMap`.)

- [ ] **Step 9: Run the plugin's tests, install it**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-maven-plugin 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure (`PluginDescriptorRunTest` and the other descriptor
tests included).

- [ ] **Step 10: Commit**

Message:
```
feat(devservices): the four hosts give the application's values and class path

vidocq:dev and vidocq:run pass every value of the application's files and
its class path as the child will see it (the module path plus the classes
directory, before the dev tools join it). vidocq:test resolves the test
class path (requiresDependencyResolution test and the project in the
hand-written descriptor) and passes it; the JUnit listener asks the test
JVM's context class loader.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git commit -S -F .git/PLAN_COMMIT_MSG --only -- vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/ApplicationLaunch.java vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqRunMojo.java vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqTestMojo.java vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/ApplicationLaunchTest.java vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/VidocqTestMojoTest.java vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/PluginDescriptorContinuousTestingTest.java vidocq-runtime-devservices/vidocq-runtime-devservices-junit/src/main/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListener.java vidocq-runtime-devservices/vidocq-runtime-devservices-junit/src/test/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListenerTest.java && rm .git/PLAN_COMMIT_MSG
```

---

### Task 6: Documentation (spec §6)

**Files:**
- Modify: `docs/en/modules/ROOT/pages/dev-services.adoc` (`[#configuration-sources]`, `[#the-report-section]`)
- Modify: `DEV_SERVICES.md` ("How it works", "Opt-out semantics", "Where configuration comes from")
- Modify: `docs/en/modules/ROOT/pages/whats-new.adoc` (one entry)

**Interfaces:**
- Consumes: the behaviour of Tasks 1-5 as the Rulings state it.
- Produces: the anchor `postgres-only-for-postgres`, which `whats-new.adoc` links to.

- [ ] **Step 1: `dev-services.adoc`**

In `docs/en/modules/ROOT/pages/dev-services.adoc`, replace exactly:
```
* **Opt-out keys** decide whether a service starts at all — `vidocq.pool[.<name>].url`,
  `mp.jwt.verify.issuer`. They are read from an explicit `-D`, an environment variable, or the
  dev-goal configuration, and **never** from `vidocq.properties`/`application.properties`: a
  production default baked into the application's own file must never silently switch a dev
  container off.
```
with:
```
* **Opt-out keys** decide whether a service starts at all — `vidocq.pool[.<name>].url`,
  `mp.jwt.verify.issuer`. Only an explicit `-D`, an environment variable, or the dev-goal
  configuration switches a service off through them, **never** `vidocq.properties`/`application.properties`:
  a production default baked into the application's own file must never silently switch a dev
  container off. A provider may still read that file to learn what the application is configured
  for (<<postgres-only-for-postgres>>).
```

Then replace exactly:
```
`vidocq.pool.url` in that same file still never disables the PostgreSQL dev service — it is an
opt-out key, not a tuning key.
```
with:
```
`vidocq.pool.url` in that same file is still no opt-out: a PostgreSQL URL there never disables the
PostgreSQL dev service. What the file tells the provider is which database the application uses.

[#postgres-only-for-postgres]
=== A PostgreSQL container only for a PostgreSQL application [.tag-new]#NEW#

With `vidocq-runtime-devservice-postgres` among the plugin's dependencies, the PostgreSQL dev
service decides, for the `@Default` datasource (`vidocq.pool.url`) and then for each name of
`vidocq.dev.postgres.datasources` (`vidocq.pool.<name>.url`), in this order:

[cols="3,2,4"]
|===
| When | Container | What it says

| The URL is given explicitly: a `-D`, an environment variable, the goal's configuration
| No
| Nothing in the report; the log keeps `DevService 'postgres' skipped (already configured)`

| The application's file gives a `jdbc:` URL of another database — `jdbc:h2:mem:app`, `jdbc:mysql://db/app`, whatever the case
| No
| `not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`

| The file gives a `jdbc:postgresql:` URL — the production one
| Yes, and its URL replaces the file's under the dev host
| The service, as before

| No URL at all, or a value that is not a `jdbc:` URL, such as a `${db.url}` expression
| Only when `org.postgresql.Driver` is on the application's class path
| Without the driver: `not started: no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path`
|===

The class path is the one the application will run with: its runtime dependencies under
`vidocq:dev` and `vidocq:run`, its test dependencies under `vidocq:test` and a JUnit run. An
application on PostgreSQL at runtime and on H2 in its tests, whose file names PostgreSQL, still gets
its container in a test run. The driver is looked up as a `.class` entry, never loaded.

A reason names the key and the URL's scheme — `jdbc:h2`, as written — never the rest of the URL,
which may carry a host, a user or a password. When no datasource gets a container, the reasons of
all of them are joined with `; `. The log says `DevService 'postgres' not started: <reason>`, the
state file keeps it, and the report shows it (<<the-report-section>>).
```

Then replace exactly:
```
when a later host overwrites `vidocq.devservices.state`, never merely because the file exists.
```
with:
```
when a later host overwrites `vidocq.devservices.state`, never merely because the file exists.

A provider that did not start and said why (<<postgres-only-for-postgres>>) gets a row of its own,
at every verbosity: the row `postgres`, its value
`not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`. When no service started and at least one
said why, the summary reads `no dev service started`. The dev console's *Dev services* panel shows
the same rows. The state file keeps them as `"skipped": [{"id": "postgres", "reason": "…"}]`; a file
written before that key existed reads as none.
```

- [ ] **Step 2: `DEV_SERVICES.md`**

In `DEV_SERVICES.md`, replace exactly:
```
1. decides whether it `appliesWhen(...)` (it opts out when you configured the dependency
   yourself),
```
with:
```
1. decides whether it `appliesWhen(...)` (it opts out when you configured the dependency
   yourself, or when the application does not use what it provides) and, when it does not start,
   may say why through `skipReason(...)` — one line, no secret — which the host logs as
   `DevService '<id>' not started: <reason>`, keeps in the state file and shows in the startup
   report,
```

Then replace exactly:
```
This keeps the heavy machinery (Testcontainers, the Docker client) entirely **off the runtime
module-path** and out of the AOT / native image — the application module-path is identical to
production.
```
with:
```
This keeps the heavy machinery (Testcontainers, the Docker client) entirely **off the runtime
module-path** and out of the AOT / native image — the application module-path is identical to
production.

Besides `property(key)` — the explicit sources, then the `vidocq.dev.*` keys of the application's
files — the `DevServiceContext` a provider gets has two `default` methods, which every host of this
repository implements (an older one answers "unknown": empty, `false`):

- `applicationProperty(key)` — any key of the application's own files (`vidocq.properties`,
  `application.properties`, the external configuration directory), to learn what the application
  is configured for, such as the database a URL names. Never a reason to switch a service off in
  place of `property(key)`.
- `onApplicationClasspath(className)` — whether the application's class path, as its launch will
  see it (runtime dependencies for `vidocq:dev` and `vidocq:run`, test dependencies for
  `vidocq:test` and a JUnit run), holds that class, found as a `.class` entry, never loaded.
```

Then replace exactly:
```
A provider only applies when the dependency is **not explicitly configured**. For Postgres,
the `@Default` datasource is skipped when `vidocq.pool.url` is set **as an explicit `-D`, an
environment variable, or in the dev-goal configuration** — a baked-in default in
`vidocq.properties` does **not** count, so your production default URL never suppresses a dev
container. Point dev mode at your own database by passing `-Dvidocq.pool.url=…` (the container
is then not started, and your URL is used as-is).
```
with:
```
A provider only applies when the dependency is **not explicitly configured**. For Postgres,
the `@Default` datasource is skipped when `vidocq.pool.url` is set **as an explicit `-D`, an
environment variable, or in the dev-goal configuration**. Point dev mode at your own database by
passing `-Dvidocq.pool.url=…` (the container is then not started, and your URL is used as-is).

A provider also stays off for an application that does not use what it provides. The Postgres
provider reads the URL your `vidocq.properties` (or `application.properties`, or the external
configuration directory) gives, for the `@Default` datasource and then for each name of
`vidocq.dev.postgres.datasources`, in this order:

1. an explicit URL (`-D`, environment variable, goal configuration): no container;
2. a file URL starting with `jdbc:` but not `jdbc:postgresql:` (whatever the case), such as
   `jdbc:h2:mem:app`: no container, and the log says
   `DevService 'postgres' not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`;
3. a file URL `jdbc:postgresql:…`: a container, whose URL replaces the file's under the dev host —
   the file's URL is the production one, and never switches the dev container off;
4. no URL anywhere, or a value that is not a `jdbc:` URL (such as `${db.url}`): a container only
   when `org.postgresql.Driver` is on the application's class path (runtime dependencies for
   `vidocq:dev`/`vidocq:run`, test dependencies for `vidocq:test` and a JUnit run); otherwise
   `not started: no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path`.

A reason names the key and the URL's scheme only, never its host, user or password; with several
datasources and none started, the reasons are joined with `; `. It is kept in the state file
(`"skipped"`) and shown as a `postgres` row, `not started: …`, in the startup report and the dev
console's *Dev services* panel; with nothing started, the summary reads `no dev service started`.
```

Then replace exactly:
```
build's classpath. `vidocq.pool.url` (and any other opt-out key) is therefore read from nowhere
but an explicit `-D`, an environment variable or the dev-goal configuration, exactly as before —
**this did not change**. Only the `vidocq.dev.*` tuning keys gained `vidocq.properties` as a
source, which is what makes the stable-port example below, and the file-based `vidocq:run`
opt-in further down, work.
```
with:
```
build's classpath. `vidocq.pool.url` (and any other opt-out key) therefore switches a provider off
only from an explicit `-D`, an environment variable or the dev-goal configuration, exactly as
before. A provider may still **read** the file to learn what the application is configured for:
the Postgres provider reads the file's URL for its database kind only (see
[Opt-out semantics](#opt-out-semantics)) — an H2 or MySQL URL there means the application is not on
PostgreSQL, while a PostgreSQL one still gets its container. The `vidocq.dev.*` tuning keys are read
from `vidocq.properties` as before, which is what makes the stable-port example below, and the
file-based `vidocq:run` opt-in further down, work.
```

- [ ] **Step 3: What's new**

In `docs/en/modules/ROOT/pages/whats-new.adoc`, replace exactly (the start of that line; the rest of it stays as it
is):
```
* **Dev services are visible, `vidocq.properties`-aware, and available to `vidocq:run` and to tests** [.tag-new]#NEW#
```
with:
```
* **The PostgreSQL dev service starts only for an application on PostgreSQL** [.tag-new]#NEW# — with `vidocq-runtime-devservice-postgres` on the plugin, an application whose `vidocq.properties` says `vidocq.pool.url=jdbc:h2:…` (or MySQL, or any other database) no longer gets a PostgreSQL container whose URL replaced its own: the provider reads which database the application's file names and, with no URL at all, starts a container only when `org.postgresql.Driver` is on the application's class path. A PostgreSQL URL in the file still gets its container: it is the production one. A provider that does not start says why — `not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`, the scheme only — in the log, the state file, the startup report and the *Dev services* panel. The `DevServiceContext` SPI gains `applicationProperty(key)` and `onApplicationClasspath(className)`, and `DevService` gains `skipReason(ctx)`, all `default`. xref:dev-services.adoc#postgres-only-for-postgres[A PostgreSQL container only for a PostgreSQL application].
* **Dev services are visible, `vidocq.properties`-aware, and available to `vidocq:run` and to tests** [.tag-new]#NEW#
```

- [ ] **Step 4: Check the anchors**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq/docs/en/modules/ROOT/pages && grep -c '^\[#postgres-only-for-postgres\]' dev-services.adoc && grep -c '<<postgres-only-for-postgres>>' dev-services.adoc && grep -c 'dev-services.adoc#postgres-only-for-postgres' whats-new.adoc
```
Expected: `1`, `2`, `1`.

- [ ] **Step 5: Commit**

Message:
```
docs(devservices): the PostgreSQL dev service starts only for PostgreSQL

dev-services.adoc gains the rule as a table under the configuration
sources, and the "not started" rows of the report; DEV_SERVICES.md
rewrites its opt-out semantics and where configuration comes from, and
documents the context's two new methods and skipReason. And an entry in
what's new.

Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
```
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git commit -S -F .git/PLAN_COMMIT_MSG --only -- docs/en/modules/ROOT/pages/dev-services.adoc DEV_SERVICES.md docs/en/modules/ROOT/pages/whats-new.adoc && rm .git/PLAN_COMMIT_MSG
```

---

### Task 7: Verification — builds, the end-to-end ITs, and two real `vidocq:dev` runs (spec §7)

**Files:** none changed, except `EX/pom.xml` for the time of Step 6, reverted in Step 8 and never committed. If a
check fails, fix it in the owning task's files, re-run that task's tests, and commit with a `fix(devservices): …`
message following the Global Constraints.

**Interfaces:**
- Consumes: everything above; the script `dev-run.sh` of the Global Constraints.

- [ ] **Step 1: Install every touched module, and the untouched Keycloak provider against the new SPI**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu install -pl vidocq-runtime-devservices/vidocq-runtime-devservices-spi,vidocq-runtime-devservices/vidocq-runtime-devservices-host,vidocq-runtime-devservices/vidocq-runtime-devservices-extension,vidocq-runtime-devservices/vidocq-runtime-devservices-junit,vidocq-runtime-devservices/vidocq-runtime-devservice-postgres,vidocq-runtime-devservices/vidocq-runtime-devservice-keycloak,vidocq-runtime-maven-plugin 2>&1 | grep -E "Tests run:|FAIL|BUILD|Reactor Summary" | tail -12
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 2: The end-to-end dev services IT (`-Pdocker`, Ruling 11)**

```bash
lsof -nP -iTCP:18095 -sTCP:LISTEN; echo checked
```
Expected: only `checked` (else STOP and ask the user). Then:
```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -Pdocker verify -pl vidocq-runtime-integration-tests/vidocq-runtime-it-devservices -Dvidocq.chappe.listener.default.port=18095 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`; the `-D` reaches `DevServicesTestHostIT`'s in-JVM boot, which would otherwise take Chappe's
default 8080; the goal ITs choose their own free ports.) Expected: `BUILD SUCCESS`, `DevServicesTestHostIT`,
`DevServicesRunGoalIT` and `DevServicesDevGoalIT` run, no failure. Then:
```bash
grep -h "DevService 'postgres'" /Users/yblazart/projects/perso/vidocq/vidocq/vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/target/it-logs/*.log | sort | uniq -c
```
Expected: `DevService 'postgres' starting…` and `started` lines, no `not started` (rule 3 through the file's
PostgreSQL URL).

- [ ] **Step 3: `vidocq:test` in a real Maven run (`-Pit`, Ruling 11)**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu -Pit verify -pl vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -8
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, `ContinuousTestingTestGoalIT` among the tests run, no failure — the
goal still starts with its new test-scope resolution.

- [ ] **Step 4: The mansart-h2 example builds with the new plugin**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && JAVA_HOME=/Users/yblazart/.sdkman/candidates/java/25-tem mvn -nsu clean verify -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example 2>&1 | grep -E "Tests run:|FAIL|BUILD" | tail -6
```
(`timeout: 600000`.) Expected: `BUILD SUCCESS`, no failure.

- [ ] **Step 5: Free ports, a clean example**

```bash
lsof -nP -iTCP:18093 -sTCP:LISTEN; lsof -nP -iTCP:18094 -sTCP:LISTEN; echo checked
```
Expected: only `checked` (else STOP and ask the user; never kill what this plan did not start).
```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short vidocq-runtime-examples/vidocq-runtime-mansart-h2-example
```
Expected: nothing (the example is clean, so Step 8 can revert it with `git checkout`).

- [ ] **Step 6: An H2 application with the provider — no container (spec §7, a manual check, not committed)**

In `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/pom.xml`, replace exactly:
```xml
                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                <version>${project.version}</version>
                <executions>
```
with:
```xml
                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                <version>${project.version}</version>
                <dependencies>
                    <dependency>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-devservice-postgres</artifactId>
                        <version>${project.version}</version>
                    </dependency>
                </dependencies>
                <executions>
```
Then run with the Bash tool and `run_in_background: true`:
```bash
bash /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh /Users/yblazart/projects/perso/vidocq/vidocq/vidocq-runtime-examples/vidocq-runtime-mansart-h2-example /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/h2-dev.log
```
Wait with the Monitor tool on an until-loop (never a foreground `sleep`):
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/h2-dev.log; do sleep 2; done`.
Then:
```bash
grep -nE "DevService 'postgres'|Postgres dev service|no dev service|not started|BUILD FAILURE" /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/h2-dev.log | head -20
```
Expected: `DevService 'postgres' not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`; in the startup report
`no dev service started` and a `postgres` row `not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`; no
`Postgres dev service 'default' ready`, no `DevService 'postgres' starting`.
```bash
cat /Users/yblazart/projects/perso/vidocq/vidocq/vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/target/vidocq-dev-services.json; echo; curl -s http://127.0.0.1:18094/api/snapshot | grep -o 'not started: [^"\\]*' | head -3
```
Expected: the state file ends with
`"services":[],"skipped":[{"id":"postgres","reason":"vidocq.pool.url is jdbc:h2, not PostgreSQL"}]}`; the panel's
snapshot holds `not started: vidocq.pool.url is jdbc:h2, not PostgreSQL`. And the application works on its H2
database:
```bash
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:18093/api/products
```
Expected: `200`.

- [ ] **Step 7: Stop it**

Stop the background task of Step 6 with `TaskStop` (its id), then re-run the `lsof` command of Step 5: both ports
free.

- [ ] **Step 8: Revert the example**

```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq checkout -- vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/pom.xml && git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short vidocq-runtime-examples/vidocq-runtime-mansart-h2-example
```
Expected: nothing printed after the checkout (the example is as it was).

- [ ] **Step 9: A PostgreSQL application — a container as before (spec §7)**

```bash
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short > /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/lc4jcdi-status-before.txt; git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq branch --show-current
```
Expected: `feat/tasks-on-postgres`. Re-run the `lsof` command of Step 5 (only `checked`). Then run with the Bash tool
and `run_in_background: true`:
```bash
bash /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/dev-run.sh /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq/mcp-tasks-server /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log
```
Wait with the Monitor tool:
`until grep -qE "Vidocq dev console: http://127.0.0.1:18094/|BUILD FAILURE|Exception in thread" /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log; do sleep 2; done`.
Then:
```bash
grep -nE "DevService 'postgres'|Postgres dev service|service: postgres|not started|BUILD FAILURE" /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/mcp-tasks-dev.log | head -20
```
Expected: `DevService 'postgres' starting…`, `Postgres dev service 'default' ready at jdbc:postgresql://…`,
`DevService 'postgres' started`, the report's `1 service: postgres (postgres:… at localhost:…) — vidocq:dev`; no
`not started` (its file's `jdbc:postgresql://localhost:5432/tasks` is rule 3).

- [ ] **Step 10: Stop what this plan started, check the test app is untouched**

Stop the background task of Step 9 with `TaskStop`, re-run the `lsof` command of Step 5 (both ports free), then:
```bash
git -C /Users/yblazart/projects/perso/vidocq-tools/lc4jcdi-on-vidocq status --short | diff - /private/tmp/claude-501/-Users-yblazart-projects-perso-langchain4j-cdi-fork/51f107e8-b788-4233-adad-5dc012d1be01/scratchpad/lc4jcdi-status-before.txt && echo "test app untouched"
```
Expected: `test app untouched`.

- [ ] **Step 11: Final state**

```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq status --short
```
```bash
git -C /Users/yblazart/projects/perso/vidocq/vidocq log --oneline -10
```
Expected: the tree clean (but for files that were untracked before this plan), this plan's commits on
`feat/devservice-postgres-kind`. Nothing pushed. Report: the branch to push, the lines of Steps 6 and 9, and the
IT results of Steps 2 and 3.

---

## Self-review (done while writing)

- **Spec coverage.** §1 in scope: the two context defaults (Task 1), the four hosts (Task 5), the rule for
  `@Default` and each named datasource (Task 4), the reason kept in the state file and shown in the report and the
  panel (Task 3), the docs (Task 6); out of scope respected (no auto-activation, Keycloak unchanged, no `%dev.`
  keys). §2: rules 1-4, placeholder as rule 4, `appliesWhen` unchanged, reasons joined with `; ` (Task 4). §3: the
  SPI methods (Task 1), the `DefaultDevServiceContext` constructor, `ApplicationFiles.allOf`, `ApplicationClasspath`
  (Task 2). §4: `skipReason`, the manager's log and record, the state file, the reader, the section and the panel
  (Tasks 1, 3). §5: the host table (Task 5). §6: docs (Task 6). §7: every listed test (Tasks 1-5), the real checks
  (Task 7), `-Pdocker` IT green (Task 7, made possible by Ruling 10). §8: URL + driver, a PostgreSQL file URL never
  switches the container off (Task 4's `aFilePostgresUrlStartsAContainerWhateverTheDriverAndTheCase`),
  mcp-tasks-server as the PostgreSQL example (Task 7).
- **Types.** `DevServiceContext.applicationProperty(String): Optional<String>`,
  `onApplicationClasspath(String): boolean`, `DevService.skipReason(DevServiceContext): String`,
  `ApplicationFiles.allOf(Path)`, `ApplicationClasspath(Collection<Path>)` / `contains(String)`,
  `DefaultDevServiceContext(Path, Map, Function, Function, Predicate)`, `DevServicesSession.open(String, Path, Map,
  Function, Function, Predicate, System.Logger)`, `DevServiceManager.Skipped(String, String)` / `skipped()`,
  `StateFile.json(…, List<DevServiceManager.Skipped>)`, `DevServicesSnapshot(…, List<Skipped>)` /
  `DevServicesSnapshot.Skipped(String, String)`, `PostgresDevService.DRIVER` / `Decision(String, DatasourcePlan,
  String)` / `decide` / `scheme`, `ApplicationLaunch.classpathOf(List<Path>, Path)`,
  `VidocqTestMojo.testClasspath()` / `setProject(MavenProject)`, `DevServicesSessionListener.onTestClasspath
  (ClassLoader)` are used with the same signatures in every task.
- **Find-blocks.** Every "replace exactly" block was checked against the current file on this branch (indentation
  included) and matches once; `plugin.xml`'s last block is the `test` mojo's only.
- **Placeholders.** None: every code step carries its code, every command its expected output.
