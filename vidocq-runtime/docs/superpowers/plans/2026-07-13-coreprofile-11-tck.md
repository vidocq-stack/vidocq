# Jakarta EE Core Profile 11 TCK — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the assembled Vidocq runtime pass the Jakarta EE Core Profile 11 TCK (profile bundle + every constituent spec TCK) with zero unjustified exclusion, and document the Eclipse certification process — the two gates before the 0.3.0 release.

**Architecture:** Two tiers. The **profile** bundle (`jakarta-core-profile-tck-11.0.0`: composite integration tests + signature tests) runs through the existing `vidocq-runtime-arquillian` embedded container that boots the real `VidocqBootstrap` and serves HTTP via cassini-on-chappe. The **constituent spec** TCKs run per-brick: CDI 4.1 Lite (refresh `vauban-tck-runner`, which also covers Interceptors 2.2), plus new atinject (DI 2.0) and Annotations 3.0 runners in vauban. REST/JSON-P/JSON-B are already green and only referenced.

**Tech Stack:** Java 25, Maven 3.9.16, Arquillian 1.8, TestNG (CDI TCK) / JUnit (atinject TCK), ShrinkWrap, `jakarta.tck:sigtest-maven-plugin`, EFTL TCK binaries (final run only).

## Global Constraints

- **Toolchain:** JDK 25 (Temurin) + Maven 3.9.16, pinned via `.sdkmanrc`; run `sdk env` in each sub-project before building. `sdk env` breaks under `set -u` — invoke `mvn` directly in scripts.
- **POMs:** `modelVersion 4.0.0` everywhere. Internal versions: vauban `0.3.0-SNAPSHOT`, vidocq runtime `0.3.0-SNAPSHOT`.
- **JPMS:** strict elsewhere, but TCK runner modules carry **no `module-info.java`** (unnamed module) so the TCK deployment classes — which have no JPMS descriptor — stay readable by Vauban/Cassini. Follow the existing `vidocq-runtime-tck-*` and `vauban-tck-runner` modules.
- **Build hygiene:** always `clean test` / `clean install`, never bare `test` or `install` — stale `target/` from the IDE produces false failures (MDEP-187, Eclipse writing broken classes).
- **Language:** all code, comments, Javadoc, commit messages, and `.md` files in **English**. Chat with maintainer in French.
- **Commits:** DCO `Signed-off-by` **and** GPG-signed (`git commit -S --signoff`). **No `Co-Authored-By: Claude`, no AI mention.**
- **Dependencies:** TCK artifacts are **test-scope only**; they never enter a published/runtime module. `install.skip`/`deploy.skip`/`gpg.skip`/`skipPublishing` on every runner (they are harnesses, never on Central).
- **Verification:** run every TCK **personally** and read the surefire/testng output — never trust an agent's "green" report. Keep raw logs under `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/`.
- **Merge order:** vauban first (any engine fix), then the vidocq runner + docs. Each repo on branch `pr/ybl/coreprofile-11-tck`.
- **Dev vs certify:** develop against Maven Central artifacts; the *certification* run (out of scope here) uses the EFTL-signed binary, Core Profile TCK 11.0.0 SHA-256 `0357bfab7025972edb2bf50277b6b4206b499a2961bc94e783f34782cc4a9bda`.

---

### Task 1: Re-baseline the CDI 4.1 Lite TCK (critical path)

Establishes the true CDI Lite pass rate on today's Vauban. The last log (2026-04-03) predates the whole assembled-runtime campaign; its 90 failures are stale. This task produces the categorized failure inventory that sizes Task 2. **No code is written here** — it is a measurement whose deliverable is a triaged list.

**Files:**
- Run against: `vauban/vauban-tck-runner/` (existing, `mvn verify -Ptck`)
- Output: `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-baseline.log`

**Interfaces:**
- Consumes: nothing.
- Produces: `cdi-lite-baseline.log` + a failure inventory grouped by `(test class → assertion id → error type)` — consumed by Task 2.

- [ ] **Step 1: Clean-run the CDI Lite suite**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban
sdk env >/dev/null 2>&1 || true
mvn -q clean verify -pl vauban-tck-runner -Ptck \
  2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-baseline.log
```

Note: the runner already excludes `integration,javaee-full,se,cdi-full` groups (Full-only tests are out of Lite scope) and sets `cdiCoreMode=true`, so every remaining failure is an in-scope Lite defect.

- [ ] **Step 2: Extract the summary line and failure families**

```bash
grep -E "Tests run: .*Failures|Tests run: .*Errors" /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-baseline.log | tail -3
grep -E "^\[ERROR\]   \w" /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-baseline.log \
  | sed -E 's/>.*//' | sort | uniq -c | sort -rn
```

Expected: a `Tests run: N, Failures: F, Errors: E, Skipped: S` line, and a per-class failure histogram. Record both in the task notes.

- [ ] **Step 3: Triage each failing class to a root cause**

For each failing class, open the TCK assertion (from the class name + the `@SpecAssertion`/error message) and classify: **(a)** genuine Vauban engine gap, **(b)** harness/SPI wiring gap in `io.vidocq.vauban.tck.Vauban*` SPI classes, or **(c)** legitimately-out-of-Lite-scope (candidate documented exclusion). Produce the inventory as a markdown list `class → cause-category → one-line hypothesis`.

- [ ] **Step 4: Record the baseline (no commit — measurement only)**

Write the inventory into the task's notes and into `vauban/tasks/todo.md` (append a "CDI Lite TCK baseline 2026-07-13" section). This gates Task 2's scope.

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban && git add tasks/todo.md
git commit -S --signoff -m "docs(tck): record CDI 4.1 Lite TCK baseline inventory"
```

---

### Task 2: Drive the CDI 4.1 Lite TCK to green

Consumes Task 1's inventory. This is a measure → fix → re-run loop; the fix code is determined by triage (it cannot be pre-written without the failure list), so each real-failure family is its own red→green→commit cycle. Category-(c) items become documented exclusions with a spec citation, not silent skips.

**Files:**
- Modify (as triage dictates): `vauban/vauban-core/src/main/java/...` (engine), and/or `vauban/vauban-tck-runner/src/test/java/io/vidocq/vauban/tck/Vauban*.java` (SPI wiring).
- Modify (exclusions only): `vauban/vauban-tck-runner/` TestNG exclude list / `pom.xml` `<excludes>`.
- Output: `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-green.log`

**Interfaces:**
- Consumes: Task 1 inventory.
- Produces: a green (or green-modulo-documented-exclusions) CDI Lite run; any engine fix here is a Vauban behavior change later relied on by Task 5's composite tests.

- [ ] **Step 1 (per family): reproduce one failing class in isolation**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban
mvn -q clean verify -pl vauban-tck-runner -Ptck \
  -Dit.test='<FailingClassName>' -Dtest='<FailingClassName>' 2>&1 | tail -40
```

Expected: the same failure as the full run, now isolated with its stack trace.

- [ ] **Step 2 (per family): apply the minimal root-cause fix**

For category (a): fix the engine in `vauban-core` (elegant, root-cause — no band-aids; a staff engineer must approve). For category (b): fix the SPI class under `io.vidocq.vauban.tck`. For category (c): add the class to the exclude list **with an inline comment citing the CDI 4.1 section that puts it out of Lite scope**.

- [ ] **Step 3 (per family): re-run that class, verify green**

```bash
mvn -q clean verify -pl vauban-tck-runner -Ptck -Dit.test='<FailingClassName>' 2>&1 | grep -E "Tests run|BUILD"
```

Expected: `Failures: 0, Errors: 0` for that class.

- [ ] **Step 4 (per family): commit**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban && git add -A
git commit -S --signoff -m "fix(cdi-tck): <one-line root cause> — <ClassName>"
```

- [ ] **Step 5: full-suite green gate**

```bash
mvn -q clean verify -pl vauban-tck-runner -Ptck \
  2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-green.log
grep -E "Tests run: .*Failures" /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/cdi-lite-green.log | tail -1
```

Expected: `Failures: 0, Errors: 0` (Skipped = only the documented category-(c) exclusions). Record the final `Tests run` number.

---

### Task 3: Add the atinject (Jakarta Dependency Injection 2.0) TCK runner

New in-reactor vauban module (mirrors `vauban-tck-runner`: parented to `vauban-parent`, `install.skip`, gated behind `-Ptck`). The atinject TCK builds a fully-wired `org.atinject.tck.auto.Car` graph through the container and runs a JUnit-3 `Test` suite via `Tck.testsFor(car, supportsStatic, supportsPrivate)`.

**Files:**
- Create: `vauban/vauban-atinject-tck-runner/pom.xml`
- Create: `vauban/vauban-atinject-tck-runner/src/test/java/io/vidocq/vauban/atinject/AtinjectTckTest.java`
- Create: `vauban/vauban-atinject-tck-runner/src/test/java/io/vidocq/vauban/atinject/AtinjectBeansConfig.java` (producers/qualifiers wiring the TCK graph)
- Modify: `vauban/pom.xml` `<modules>` — add `<module>vauban-atinject-tck-runner</module>`
- Output: `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/atinject.log`

**Interfaces:**
- Consumes: the Vauban bootstrap API used by `vauban-junit`/`vauban-test-suite` to start a container and resolve a bean (reuse the same entry point those modules use — inspect them for the exact `VaubanContainer`/bootstrap call).
- Produces: nothing downstream.

- [ ] **Step 1: Write the failing test**

`AtinjectTckTest.java` — boot Vauban with the atinject graph, resolve `Car`, run the suite:

```java
package io.vidocq.vauban.atinject;

import junit.framework.TestResult;
import org.atinject.tck.Tck;
import org.atinject.tck.auto.Car;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AtinjectTckTest {

    // NB: Tck.testsFor(...) returns a junit.framework.Test (JUnit 3) — keep it
    // fully-qualified so it does not collide with the JUnit 5 @Test import.
    @Test
    void jakarta_inject_2_0_tck_passes() {
        Car car = AtinjectBeansConfig.resolveCar(); // boots Vauban, returns the wired Car
        // supportsStatic = true, supportsPrivate = true (Vauban injects static + private members)
        junit.framework.Test suite = Tck.testsFor(car, true, true);
        TestResult result = new TestResult();
        suite.run(result);
        Assertions.assertEquals(0, result.errorCount() + result.failureCount(),
                () -> "atinject TCK failures: " + describe(result));
    }

    private static String describe(TestResult r) {
        StringBuilder sb = new StringBuilder();
        r.failures().asIterator().forEachRemaining(f -> sb.append("\nFAIL ").append(f));
        r.errors().asIterator().forEachRemaining(e -> sb.append("\nERR  ").append(e));
        return sb.toString();
    }
}
```

`AtinjectBeansConfig.java` — wire the TCK graph. The atinject TCK requires: `Car`→`Convertible`, `@Drivers Seat`→`DriversSeat`, `Seat`, `Tire`, `@Named("spare") Tire`→`SpareTire`, `Engine`→`V8Engine`, etc. In CDI terms these are the `org.atinject.tck.auto.*` classes plus producers for the qualified ones. Discover them through the Vauban indexer by adding `org.atinject.tck.auto` (and `.accessories`) to the bean archive, then resolve `Convertible` as `Car`:

```java
package io.vidocq.vauban.atinject;

import org.atinject.tck.auto.Car;
import org.atinject.tck.auto.Convertible;
// ... Vauban bootstrap imports (mirror vauban-test-suite's container startup)

final class AtinjectBeansConfig {
    private AtinjectBeansConfig() {}

    static Car resolveCar() {
        // Boot a Vauban container whose bean archive includes the org.atinject.tck.auto
        // packages; @Named/@Drivers qualifiers and the Seat/Tire/Engine bindings come
        // from the TCK classes' own annotations. Resolve and return the Car.
        return VaubanTestBoot.container("org.atinject.tck.auto", "org.atinject.tck.auto.accessories")
                .select(Convertible.class).get();
    }
}
```

(`VaubanTestBoot` stands for whatever helper `vauban-test-suite` uses — Step 3 replaces this line with the real call discovered there.)

- [ ] **Step 2: Create the pom and register the module**

`vauban-atinject-tck-runner/pom.xml` — clone `vauban-tck-runner/pom.xml`'s parent block + skip properties, then these deps (test scope):

```xml
<dependencies>
    <dependency><groupId>io.vidocq.vauban</groupId><artifactId>vauban-core</artifactId></dependency>
    <dependency><groupId>io.vidocq.vauban</groupId><artifactId>vauban-indexer</artifactId></dependency>
    <dependency>
        <groupId>jakarta.inject</groupId>
        <artifactId>jakarta.inject-tck</artifactId>
        <version>2.0.1</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope>
    </dependency>
    <dependency>
        <groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope>
    </dependency>
</dependencies>
```

Add `<module>vauban-atinject-tck-runner</module>` to `vauban/pom.xml`.

- [ ] **Step 3: Run to verify it fails for the right reason**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban
mvn -q clean test -pl vauban-atinject-tck-runner 2>&1 | tail -30
```

Expected: compile succeeds after wiring the real `VaubanTestBoot` call; test FAILS or errors listing specific atinject assertions (e.g. static/private field injection), OR passes. Read `vauban-test-suite`/`vauban-junit` to replace the `VaubanTestBoot.container(...)` placeholder with the actual bootstrap.

- [ ] **Step 4: Fix any genuine gap, then verify green**

Fix root causes in `vauban-core` if the TCK exposes real injection gaps (static/private/method injection, circular `Provider`, qualifier resolution). Re-run:

```bash
mvn -q clean test -pl vauban-atinject-tck-runner 2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/atinject.log | grep -E "Tests run|BUILD"
```

Expected: `Tests run: 1, Failures: 0, Errors: 0` (the single wrapper test; the atinject suite ran ~50 internal assertions inside it).

- [ ] **Step 5: Commit**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban && git add -A
git commit -S --signoff -m "test(tck): add Jakarta Dependency Injection 2.0 (atinject) TCK runner"
```

---

### Task 4: Add the Jakarta Annotations 3.0 TCK runner

New in-reactor vauban module. First resolve the exact TCK artifact (the modernized Annotations 3.0 TCK), then wire it like `vauban-tck-runner`. Annotations behavior under test (`@PostConstruct`, `@PreDestroy`, `@Priority`, common annotations) is driven by Vauban's lifecycle/injection engine.

**Files:**
- Create: `vauban/vauban-annotations-tck-runner/pom.xml`
- Create: `vauban/vauban-annotations-tck-runner/src/test/...` (harness/SPI as the TCK requires)
- Modify: `vauban/pom.xml` `<modules>`
- Output: `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/annotations.log`

**Interfaces:**
- Consumes: Vauban lifecycle-callback + `@Priority` support (already exercised by CDI TCK).
- Produces: nothing downstream.

- [ ] **Step 1: Resolve the TCK artifact coordinates**

The EFTL bundle is `jakarta-annotations-tck-3.0.0.zip` (SHA-256 `9421c6ca66274d32dfb408848f75a42d57f120599fe0d8403c5c5c1141d5ac4d`). Find its Maven-Central equivalent for dev:

```bash
# Probe Central for the annotations TCK test artifact
for a in jakarta.annotation:jakarta-annotations-tck jakarta.tck:jakarta-annotations-tck jakarta.annotation:jakarta.annotation-tck; do
  g=${a%%:*}; art=${a##*:};
  curl -s "https://search.maven.org/solrsearch/select?q=g:%22$g%22+AND+a:%22$art%22&rows=5&wt=json" \
    | grep -o "\"a\":\"[^\"]*\"\|\"latestVersion\":\"[^\"]*\"" ; echo "  ^ $a";
done
```

Expected: one coordinate resolves with a 3.0.x version. If none is on Central, extract the test jar from the EFTL zip into the local `~/.m2` (document the `install-tck.sh` step, mirroring `champollion/install-tck.sh`). Record the chosen coordinates.

- [ ] **Step 2: Create the pom + module registration**

Clone `vauban-tck-runner/pom.xml` skeleton (parent, skip props, arquillian/testng if the TCK is Arquillian-based — inspect the zip's `pom.xml`/`tck-suite.xml` to confirm the harness), depend on the coordinates from Step 1 (test scope), and `dependenciesToScan` the TCK jar. Add `<module>vauban-annotations-tck-runner</module>` to `vauban/pom.xml`.

- [ ] **Step 3: Run to establish the baseline**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban
mvn -q clean verify -pl vauban-annotations-tck-runner -Ptck 2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/annotations.log | grep -E "Tests run|BUILD"
```

Expected: a `Tests run: N` line with the initial pass/fail split.

- [ ] **Step 4: Fix genuine gaps, verify green**

Root-cause any failure in Vauban's annotation/lifecycle handling. Re-run until `Failures: 0, Errors: 0` (documented exclusions only for out-of-scope tests, with a spec citation).

- [ ] **Step 5: Commit**

```bash
cd /Users/yblazart/projects/perso/vidocq/vauban && git add -A
git commit -S --signoff -m "test(tck): add Jakarta Annotations 3.0 TCK runner"
```

---

### Task 5: Add the Core Profile 11 TCK runner on the assembled runtime

New vidocq module `vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile`, gated behind `-Ptck`, driving the profile bundle's **composite** tests through `vidocq-runtime-arquillian`. Signature tests are Task 6.

**Files:**
- Create: `vidocq/vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile/pom.xml`
- Create: `vidocq/.../vidocq-runtime-tck-coreprofile/src/test/resources/arquillian.xml`
- Modify: `vidocq/vidocq-runtime-integration-tests/pom.xml` — add the module to the `tck` profile `<modules>`
- Output: `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/coreprofile.log`

**Interfaces:**
- Consumes: `io.vidocq.runtime:vidocq-runtime-arquillian` (embedded container, qualifier `vidocq`, port `0`); the cassini + champollion + vauban extensions that make the runtime a Core Profile implementation.
- Produces: nothing downstream.

- [ ] **Step 1: Locate the Core Profile composite-test artifacts**

The profile bundle ships as `jakarta-core-profile-tck-11.0.0.zip`. Inspect its layout to find the composite-test jar + its Arquillian/TestNG harness:

```bash
cd /Users/yblazart/.claude/jobs/2f4bcfe2/tmp
curl -sL -o cp-tck.zip https://download.eclipse.org/jakartaee/coreprofile/11.0/jakarta-core-profile-tck-11.0.0.zip
echo "0357bfab7025972edb2bf50277b6b4206b499a2961bc94e783f34782cc4a9bda  cp-tck.zip" | shasum -a 256 -c
unzip -l cp-tck.zip | grep -iE "\.jar|pom.xml|suite|README|artifacts" | head -40
```

Expected: the SHA-256 matches; the listing reveals the test jar(s) (e.g. under `artifacts/`) and the runner mechanism. Determine whether the composite tests are also published on Central (`jakarta.tck:*`) for dev; if not, `mvn install:install-file` them into `~/.m2` (document in an `install-tck.sh`, mirroring `champollion/install-tck.sh`). Record the coordinates + the test package(s) to scan.

- [ ] **Step 2: Create `arquillian.xml`**

Identical to the MP runners:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<arquillian xmlns="http://jboss.org/schema/arquillian"
            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://jboss.org/schema/arquillian
                http://jboss.org/schema/arquillian/arquillian_1_0.xsd">
    <container qualifier="vidocq" default="true">
        <configuration>
            <property name="host">localhost</property>
            <property name="port">0</property>
        </configuration>
    </container>
</arquillian>
```

- [ ] **Step 3: Create the pom (fails first — no tests wired yet)**

Model on `vidocq-runtime-tck-knock-health/pom.xml`: parent `vidocq-runtime-integration-tests` `0.3.0-SNAPSHOT`, **no `module-info.java`**, depend on `vidocq-runtime-core`, the cassini/champollion/vauban extensions that constitute the profile, `vidocq-runtime-arquillian` (test), the Arquillian TestNG container (test), and the Core Profile composite-test artifact from Step 1 (test). Add `<dependenciesToScan>` for the composite-test jar. Register the module in the `tck` profile of `vidocq-runtime-integration-tests/pom.xml`:

```xml
<module>vidocq-runtime-tck-coreprofile</module>
```

- [ ] **Step 4: Run — establish the composite baseline**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
mvn -q clean test -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile -Ptck \
  2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/coreprofile.log | grep -E "Tests run|BUILD"
```

Expected: a `Tests run: N` line. Failures here are real integration gaps (CDI↔JAX-RS↔JSON-B composition) — triage and fix in the relevant extension (cassini/champollion wiring, or the container enrichment reused from the MP campaign).

- [ ] **Step 5: Fix composite failures, verify green**

Loop measure→fix→re-run until `Failures: 0, Errors: 0`, zero unjustified exclusion. Commit per fix family.

- [ ] **Step 6: Commit**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add -A
git commit -S --signoff -m "test(tck): add Jakarta EE Core Profile 11 composite TCK runner on the assembled runtime"
```

---

### Task 6: Wire the Core Profile signature tests

The bundle includes sigtest-based signature checks for the Core Profile API packages. They are mandatory for certification and verify the runtime exposes exactly the `jakarta.jakartaee-core-api:11.0.0` public surface.

**Files:**
- Modify: `vidocq/.../vidocq-runtime-tck-coreprofile/pom.xml` (add the sigtest execution)
- Create (if the bundle provides one): `vidocq/.../vidocq-runtime-tck-coreprofile/src/test/resources/<core-profile>.sig` (the recorded signature from the zip)
- Output: `/Users/yblazart/.claude/jobs/2f4bcfe2/tmp/coreprofile-sig.log`

**Interfaces:**
- Consumes: the `.sig` signature file shipped in the TCK bundle; `jakarta.tck:sigtest-maven-plugin`.
- Produces: nothing downstream.

- [ ] **Step 1: Extract the signature file + plugin invocation from the bundle**

```bash
cd /Users/yblazart/.claude/jobs/2f4bcfe2/tmp
unzip -o cp-tck.zip -d cp-tck-extract >/dev/null
find cp-tck-extract -name "*.sig" -o -iname "*signature*" | head
grep -rIl "sigtest\|SignatureTest" cp-tck-extract | head
```

Expected: a `.sig` file and the documented sigtest command/parameters (package list, version). Copy the `.sig` into `src/test/resources/`.

- [ ] **Step 2: Add the sigtest execution to the runner pom**

```xml
<plugin>
    <groupId>jakarta.tck</groupId>
    <artifactId>sigtest-maven-plugin</artifactId>
    <version>2.6</version>
    <executions>
        <execution>
            <id>coreprofile-signature-test</id>
            <phase>test</phase>
            <goals><goal>check</goal></goals>
            <configuration>
                <sigfile>${project.basedir}/src/test/resources/jakarta-core-profile.sig</sigfile>
                <packages><!-- the profile API packages listed by the bundle --></packages>
            </configuration>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 3: Run the signature check**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
mvn -q clean test -pl vidocq-runtime-integration-tests/vidocq-runtime-tck-coreprofile -Ptck \
  2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/coreprofile-sig.log | grep -iE "signature|Tests run|BUILD"
```

Expected: `Signature check passed`. A mismatch is a real API-jar version drift — fix the offending API dependency version (do **not** exclude packages).

- [ ] **Step 4: Commit**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add -A
git commit -S --signoff -m "test(tck): add Core Profile 11 API signature tests"
```

---

### Task 7: Write `CERTIFICATION.md`

The "regarde le processus de certification" deliverable. Documents the full Eclipse Jakarta EE certification process and Vidocq's prerequisites so the filing can happen after 0.3.0.

**Files:**
- Create: `vidocq/CERTIFICATION.md`

**Interfaces:**
- Consumes: the results from Tasks 1–6 (final `Tests run` numbers per suite).
- Produces: nothing downstream.

- [ ] **Step 1: Write the document**

Sections (each fully written, no placeholders):
1. **Prerequisites** — released product = Vidocq 0.3.0; target JDKs 21 and 25 (WildFly certified on 17+21).
2. **TCK binaries table** — Core Profile bundle + each constituent (Annotations, CDI, DI, JSON-P, JSON-B, REST) with EFTL download URL and SHA-256 (Core Profile `0357bfab…`, Annotations `9421c6ca…`; fill the rest from `download.eclipse.org`).
3. **Signature tests** — mandatory, part of each run.
4. **Public results summary** — one `.adoc` per JDK, shaped like `wildfly/certifications` (product, version, JDK, OS, per-suite pass counts). Include a filled template using the actual numbers from Tasks 1–6.
5. **Filing** — GitHub issue on `jakartaee/platform`, label `certification`, EFTL-acceptance checkbox, "all TCK requirements met" attestation; reference WildFly #978 / Open Liberty #975 as models.
6. **Approval** — lazy consensus after 2 weeks or majority vote; then the *Jakarta EE Compatible* logo.
7. **Dev vs certify** — Central artifacts for CI; EFTL-signed binaries for the certifying run.

- [ ] **Step 2: Self-check and commit**

Verify every SHA-256/URL is real (not invented) — any not yet confirmed is marked "to fetch from download.eclipse.org at cert time", not faked.

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add CERTIFICATION.md
git commit -S --signoff -m "docs: Jakarta EE Core Profile 11 certification process and prerequisites"
```

---

### Task 8: Update `TCK.md` with the Core Profile section

**Files:**
- Modify: `vidocq/vidocq-runtime-integration-tests/TCK.md`

**Interfaces:**
- Consumes: final numbers from Tasks 1–6.
- Produces: nothing downstream.

- [ ] **Step 1: Add the Core Profile section**

Add a "Jakarta EE Core Profile 11" section: the two-tier explanation, the runner (`vidocq-runtime-tck-coreprofile`, composites + signature, assembled runtime), the constituent-TCK table with final numbers (CDI Lite, atinject, Annotations, plus the referenced REST/JSON-P/JSON-B), and the dev-vs-EFTL note. Update the running total.

- [ ] **Step 2: Commit**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq && git add vidocq-runtime-integration-tests/TCK.md
git commit -S --signoff -m "docs(tck): document Jakarta EE Core Profile 11 coverage"
```

---

### Task 9: Full green gate + PR bodies

**Files:** none (verification + PRs).

- [ ] **Step 1: Full assembled TCK sweep**

```bash
cd /Users/yblazart/projects/perso/vidocq/vidocq
mvn -q clean install -DskipTests
mvn -q verify -pl vidocq-runtime-integration-tests -amd -Ptck 2>&1 | tee /Users/yblazart/.claude/jobs/2f4bcfe2/tmp/coreprofile-sweep.log | grep -E "Tests run|BUILD|FAIL"
cd /Users/yblazart/projects/perso/vidocq/vauban
mvn -q clean verify -pl vauban-tck-runner,vauban-atinject-tck-runner,vauban-annotations-tck-runner -Ptck 2>&1 | grep -E "Tests run|BUILD"
```

Expected: all suites `Failures: 0, Errors: 0`.

- [ ] **Step 2: Push branches, open PRs (vauban first, then vidocq)**

Push `pr/ybl/coreprofile-11-tck` on both repos to Codeberg (BOT credentials from `~/.config/vidocq/tokens.env` — never echo/commit them). Open the vauban PR first; after its CI is green and it merges, rebase/merge the vidocq PR (build-impacted will rebuild vidocq against the new vauban). PR bodies in English, no AI mention.

---

## Notes for the executor

- **Two genuine discovery points** are called out explicitly (Task 1 CDI failure inventory; Task 5 Step 1 Core Profile artifact layout). Everything downstream of them is data-driven — do the measurement before sizing the fix.
- **The TCK is the test.** For the constituent-spec tasks there is no hand-written failing unit test; the red state is the TCK suite output. Root-cause every failure in the engine — no band-aids, no silent skips (exclusions carry a spec citation).
- **Watch the split-package trap** (learned on cyrano): adding an automatic module can surface a latent split package on the module path. If a new test dep is an automatic module, verify the assembled module path still resolves.
- **`git -C <repo>` always** for git — the shell cwd is unstable between calls; never `git reset --hard` without `-C`.
