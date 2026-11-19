# Dev tools only under `vidocq:dev` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every live dev-console panel into a `-dev` companion module that only `vidocq:dev` adds, and make
sure no binary ever contains the console, its SPI, a `-dev` module or the dev services extension.

**Architecture:**
- **Console SPI:** a new `LivePanel` type (live values, charts, actions, no section of its own). The console matches
  each one to the startup-report section of the same id.
- **Runtime extensions:**
  - each keeps its section and loses its dependency on the console SPI;
  - what its panel needs is either recomputed from the `ExtensionContext`, or published in a small `.live` package
    exported only to its `-dev` module;
  - a one-line descriptor in its jar names its companion.
- **`vidocq:dev`:** resolves the companions with Maven's resolver and adds them, and the console, to the child's
  module path.
- **Every other goal:** drops any jar whose manifest says `Vidocq-Dev-Only: true`.

**Tech Stack:** Java 25, JPMS, Maven 3.9, a hand-written `plugin.xml`, Maven Resolver (Aether), the Vidocq dev
console SPI, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-26-dev-only-tooling-design.md`. Read it first; section numbers below refer
to it.

## Global Constraints

- **Toolchain.**
  - Java 25. Every Maven call starts with
    `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;`.
  - Use `mvn`, never `./mvnw`, and add `-o` unless a download is needed.
  - Run long Maven commands through a wrapper script, as in #122.
- **Branch.** `feat/143-dev-only-tooling`, created from `docs/143-dev-only-tooling-design`, in the main checkout.
- **Names, verbatim** (spec §2-§4):
  - the SPI type `io.vidocq.runtime.spi.devconsole.LivePanel`;
  - the descriptor `META-INF/vidocq/dev-module`, one line holding the companion's `artifactId`;
  - the manifest entry `Vidocq-Dev-Only: true`;
  - companion artifactIds `<runtime artifactId>-dev`, same groupId and version;
  - JPMS module and package `<runtime module>.dev`, with the runtime's shared package `<runtime module>.live`,
    exported `to <runtime module>.dev` only;
  - anomaly codes `VIDOCQ-DEVC-007` (two live panels for one id), `VIDOCQ-DEVC-008` (a live panel without its
    section) and `VIDOCQ-DEVC-009` (a section's contributor that is also a panel, with a live panel for the same id).
- **Section ids, unchanged:** `rest` (Cassini), `mansart-pool`, `metrics` (Dirac), `health` (Knock), `migration`,
  `mcp`.
- **Dev-only artifacts** (marked with the manifest entry):
  - `vidocq-runtime-devconsole-spi`;
  - `vidocq-runtime-devconsole-extension`;
  - `vidocq-runtime-devservices-extension`;
  - the six `-dev` modules.
- **Only `vidocq:dev` adds dev tools.** One exception is kept: `vidocq:run -Dvidocq.dev.devServices=true` still adds
  the dev services extension (#123).
- **A dev-only jar is never packaged,** even when declared. The WARNING text, verbatim:
  `<artifactId> is dev-only: not packaged; remove the dependency, vidocq:dev brings it`.
- **Warnings only:** `checkpom` warns and never fails for a dev-only dependency outside `test` scope.
- **`vidocq:idea` Maven kind:** three files, `<Name>.run.xml` (runs `vidocq:dev`), `<Name> (packaged).run.xml`
  (runs `vidocq:run`) and `<Name> (debug).run.xml` (Remote JVM Debug, default `127.0.0.1:5005`).
- **Code rules.** Code, Javadoc, commits and docs in English; lines at most 120 characters. Each new Java file starts
  with the 19-line license header of
  `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/RecompileRunner.java`, which the code blocks
  below omit.
- **Commits:** `git commit -S -F <msgfile>`, never `-m` or `-s`. The message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
- **Ports:** 18093-18099 only, checked free; never 8080, 8888 or 5005. Every `vidocq:dev` a test starts gets
  `-Dvidocq.dev.debug=false`. Stop every process you start.
- **Docs:** each new section is tagged `[.tag-new]#NEW#`, and gets a `whats-new.adoc` bullet.

## Rulings (where this plan settles what the spec leaves open)

1. **`LivePanel.start` runs lazily.** The console starts its live panels the first time it reads the written
   startup report, which comes after every extension's `onStart`. It stops them in its own `onStop`. A live panel
   therefore never depends on the order of the extensions' `onStart`.
2. **A panel's state comes from the context, or from a holder.**
   - A live panel recomputes what it needs from the `ExtensionContext` when it can: Knock, Dirac and MCP already
     build their live bean from `context.beanManager()`.
   - Otherwise it reads a static holder that its runtime extension publishes in `.live`: Cassini's `RestMounts`,
     Mansart's pools, Migration's control.
   - A holder is cleared in the extension's `onStop`, so that a dev reload never shows the previous boot.
3. **Migration keeps its operations in the runtime.** `migrate`, `clean-and-migrate` and the dev-launch listing of
   migrations need the extension's targets, locks and backend. The runtime exposes them through a small
   `MigrationControl` interface in `.live`; only the panel and its buttons move to `-dev`. No endpoint reaches these
   operations without the console.
4. **A companion never brings runtime code.** It is added with its transitive runtime dependencies that are marked
   dev-only. It is skipped, with a WARNING, when it needs any other artifact the application does not already have.
5. **The console needs Chappe.** It is added only when the application already has
   `vidocq-runtime-chappe-webserver-extension`. Without it, `vidocq:dev` logs one INFO line saying the console needs
   an HTTP server extension, and adds no dev tool: a CLI application must never start a listener because it runs
   under `vidocq:dev`.
6. **`ApplicationLaunch.modulePath` gains a `dropDevOnly` flag.** `vidocq:run` passes `true`; `vidocq:dev` passes
   `false`, keeping a declared console (spec §5.3).
7. **The companion end-to-end check (spec §10) is split in three.**
   - Resolution is covered by unit tests with a fake resolver.
   - The live match is covered by the Mansart example's own test.
   - One manual `vidocq:dev` run on the Mansart example covers the rest.

   A second IT fixture with a database pool would add a slow, fragile module for what these three already cover.

## Review Focus

1. **An application without the Chappe HTTP extension** must not get the console, or any listener, under
   `vidocq:dev`. Test in Task 4.
2. **A declared console (legacy compile scope) together with `vidocq:dev`** must not put the module on the path
   twice: a duplicate module stops the boot. Test in Task 4.
3. **A companion whose dependencies are not dev-only and are missing from the application** must be skipped, never
   silently add runtime code. Test in Task 4.
4. **A dev hot reload:**
   - the live panels are stopped and started again per boot;
   - a holder never shows the previous boot's pools or mounts.

   Tests in Tasks 2 and 10.
5. **A jar without a manifest, or unreadable, in the dev-only filter** is not dev-only, and never crashes a
   packaging goal. Test in Task 3.

---

## File structure

| Area | Files |
|---|---|
| SPI | `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole/LivePanel.java` (new), its `pom.xml` (manifest) |
| Console | `.../devconsole/LivePanels.java` (new), `LivePanelAdapter.java` (new), `Snapshot.java`, `DevConsoleExtension.java`, `module-info.java`, `pom.xml` (manifest) |
| Plugin | `.../maven/DevOnlyJars.java` (new), `.../maven/dev/DevModules.java` (new), `.../maven/dev/DevConsoleJars.java` (new), `ApplicationLaunch.java`, `VidocqRunMojo.java`, `VidocqPackageMojo.java`, `VidocqJlinkMojo.java`, `VidocqCheckPomMojo.java`, `dev/VidocqDevMojo.java`, `idea/RunConfigurationRenderer.java`, `idea/VidocqIdeaMojo.java`, `plugin.xml`, `pom.xml`, `src/it/idea-*` |
| Extensions | per extension: runtime module (descriptor, `.live` package, `module-info`, `pom`), new sibling `<artifactId>-dev` module |
| Dev services | `vidocq-runtime-devservices-extension/pom.xml` (manifest) |
| Consumers | Mansart H2 example `pom.xml`; IT poms (continuous-testing module and fixture, lc4jcdi-mcp, devservices); new IT tests |
| Docs | `dev-console.adoc`, `dev-console-panels.adoc`, `modules/vidocq-runtime-maven-plugin.adoc`, `migration.adoc`, `whats-new.adoc`, `CLAUDE.md` |

Abbreviations: `SPI/` = `vidocq-runtime-devconsole-spi/src/main/java/io/vidocq/runtime/spi/devconsole`; `CONSOLE/` =
`vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension/src/main/java/io/vidocq/runtime/extensions/essentials/devconsole`
(and `CONSOLE_TEST/`); `PLUGIN/` = `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven` (and
`PLUGIN_TEST/`).

The manifest marking, used by every dev-only module's `pom.xml` (Tasks 1, 2, 7-13):

```xml
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
    </plugins>
</build>
```

(Merge it into an existing `<build><plugins>` rather than adding a second one.)

---

### Task 1: The `LivePanel` SPI

**Files:**
- Create: `SPI/LivePanel.java`
- Modify: `vidocq-runtime-devconsole-spi/pom.xml` (manifest marking)
- Test: `vidocq-runtime-devconsole-spi/src/test/java/io/vidocq/runtime/spi/devconsole/LivePanelTest.java`

**Interfaces:**
- Produces `public interface LivePanel`:
  - `String id()`;
  - `default void start(ExtensionContext)`, `default void stop()`;
  - `default List<Chart> charts()`;
  - `void sample(PanelSample)`;
  - `default List<PanelAction> actions()`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.runtime.spi.devconsole;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class LivePanelTest {

    @Test
    void onlyTheIdAndTheSampleAreRequired() {
        LivePanel panel = new LivePanel() {
            @Override
            public String id() {
                return "acme";
            }

            @Override
            public void sample(PanelSample sample) {}
        };

        assertEquals("acme", panel.id());
        assertEquals(List.of(), panel.charts());
        assertEquals(List.of(), panel.actions());
        assertDoesNotThrow(() -> panel.start(null));
        assertDoesNotThrow(panel::stop);
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -o -q -pl vidocq-runtime-devconsole-spi test -Dtest=LivePanelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE, `cannot find symbol: class LivePanel`.

- [ ] **Step 3: Write the implementation**

```java
package io.vidocq.runtime.spi.devconsole;

import io.vidocq.runtime.spi.ExtensionContext;

import java.util.List;

/**
 * The live half of a section of the startup report, from a {@code -dev} module (Vidocq/vidocq#143): values sampled
 * on every poll, charts and actions, for the section of the same {@link #id()}, which the runtime extension still
 * writes. A live panel writes no section of its own: the section gives the title and the boot facts.
 *
 * <p>Declared as a service by a module that only {@code vidocq:dev} adds, it never reaches a binary. The console
 * {@linkplain #start starts} it the first time it reads the written report, after every extension started, and
 * {@linkplain #stop stops} it with itself: once per boot, a dev reload included.
 *
 * <p>The rules of {@link DevConsolePanel} apply: {@link #sample} reads memory only, never blocks, never creates a
 * bean; a value never carries a secret.
 */
public interface LivePanel {

    /** The id of the startup-report section this panel makes live, such as {@code mansart-pool}. */
    String id();

    /** Called once per boot, before the first sample; the default does nothing. */
    default void start(ExtensionContext context) {}

    /** Called once per boot when the console stops; the default does nothing. */
    default void stop() {}

    /** The charts, in page order; none by default. */
    default List<Chart> charts() {
        return List.of();
    }

    /** Writes the current values, read from memory. */
    void sample(PanelSample sample);

    /** The actions, in page order, offered in a dev launch only; none by default. */
    default List<PanelAction> actions() {
        return List.of();
    }
}
```

Add the manifest marking to `vidocq-runtime-devconsole-spi/pom.xml` (File structure section).

- [ ] **Step 4: Run it to see it pass, and check the manifest**

Run: `mvn -o -q -pl vidocq-runtime-devconsole-spi install`, then
`unzip -p ~/.m2/repository/io/vidocq/runtime/vidocq-runtime-devconsole-spi/0.4.0-SNAPSHOT/vidocq-runtime-devconsole-spi-0.4.0-SNAPSHOT.jar META-INF/MANIFEST.MF | grep Vidocq`.
Expected: tests pass; `Vidocq-Dev-Only: true`.

- [ ] **Step 5: Commit** — `feat(devconsole-spi): LivePanel, the live half of a report section (#143)`.

---

### Task 2: The console matches live panels to sections

**Files:**
- Create: `CONSOLE/LivePanels.java`, `CONSOLE/LivePanelAdapter.java`
- Modify:
  - `CONSOLE/Snapshot.java`: the `read` method and the constructors;
  - `CONSOLE/DevConsoleExtension.java`: `onStart` and `onStop`;
  - `.../devconsole-extension/src/main/java/module-info.java`;
  - `.../devconsole-extension/pom.xml`: manifest marking.
- Test: `CONSOLE_TEST/LivePanelsTest.java`, `CONSOLE_TEST/SnapshotTest.java` (added tests)

**Interfaces:**
- Consumes: `LivePanel` (Task 1); `PanelEntry.contributed(StartupReportContributor, ReportSection, boolean)`;
  `StartupReportView.contributors()` and `sections()`.
- Produces:
  - `final class LivePanels`:
    - `static LivePanels load(ClassLoader)`, `static LivePanels of(List<? extends LivePanel>, Consumer<String> warn)`, `static final LivePanels NONE`;
    - `Optional<LivePanel> forSection(String id)`;
    - `void startAll(ExtensionContext)`, `void stopAll()`;
    - `Set<String> ids()`;
    - constants `DUPLICATE = "VIDOCQ-DEVC-007"`, `ORPHAN = "VIDOCQ-DEVC-008"`, `DOUBLE = "VIDOCQ-DEVC-009"`.
  - `final class LivePanelAdapter implements DevConsolePanel`, with `LivePanelAdapter(StartupReportContributor,
    LivePanel)`.

- [ ] **Step 1: Write the failing tests**

`CONSOLE_TEST/LivePanelsTest.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePanelsTest {

    static class Live implements LivePanel {
        final String id;
        final List<String> calls = new ArrayList<>();

        Live(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void start(io.vidocq.runtime.spi.ExtensionContext context) {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }

        @Override
        public void sample(PanelSample sample) {}
    }

    @Test
    void theFirstPanelOfAnIdWinsAndTheSecondIsReported() {
        Live first = new Live("rest");
        Live second = new Live("rest");
        List<String> warnings = new ArrayList<>();

        LivePanels panels = LivePanels.of(List.of(first, second), warnings::add);

        assertSame(first, panels.forSection("rest").orElseThrow());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith("[" + LivePanels.DUPLICATE + "]"), warnings.getFirst());
    }

    @Test
    void startAndStopReachEveryPanelOncePerBoot() {
        Live rest = new Live("rest");
        LivePanels panels = LivePanels.of(List.of(rest), warning -> {});

        panels.startAll(null);
        panels.startAll(null);
        panels.stopAll();
        panels.stopAll();

        assertEquals(List.of("start", "stop"), rest.calls);
    }

    @Test
    void aPanelThatFailsToStartIsDroppedNotFatal() {
        LivePanel broken = new Live("rest") {
            @Override
            public void start(io.vidocq.runtime.spi.ExtensionContext context) {
                throw new IllegalStateException("boom");
            }
        };
        LivePanels panels = LivePanels.of(List.of(broken), warning -> {});

        panels.startAll(null);

        assertEquals(Optional.empty(), panels.forSection("rest"));
    }
}
```

Add to `CONSOLE_TEST/SnapshotTest.java`. Reuse the file's own fixtures (`FakeReportView`, `TestPanels`), with a
static contributor `rest` that writes a section and is not a `DevConsolePanel`:

```java
    @Test
    void aLivePanelMakesAStaticSectionLive() {
        TestPanels.Static rest = new TestPanels.Static("rest");
        LivePanelsTest.Live live = new LivePanelsTest.Live("rest") {
            @Override
            public void sample(io.vidocq.runtime.spi.devconsole.PanelSample sample) {
                sample.counter("requests", 7, io.vidocq.runtime.spi.devconsole.Unit.COUNT);
            }
        };
        Snapshot snapshot = snapshotOf(FakeReportView.of(rest), LivePanels.of(List.of(live), w -> {}));

        PanelEntry entry = snapshot.panel("rest");

        assertEquals("rest", entry.id());
        assertEquals(live, ((LivePanelAdapter) entry.panel()).live());
        assertEquals(List.of("start"), live.calls, "started when the report is first read");
    }

    @Test
    void aLivePanelWithoutItsSectionIsNotShown() {
        List<String> warnings = new ArrayList<>();
        LivePanels panels = LivePanels.of(List.of(new LivePanelsTest.Live("absent")), warnings::add);
        Snapshot snapshot = snapshotOf(FakeReportView.of(new TestPanels.Static("rest")), panels);

        assertNull(snapshot.panel("absent"));
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("[" + LivePanels.ORPHAN + "]")), warnings.toString());
    }
```

If `TestPanels` has no static-contributor fixture, add
`static final class Static implements StartupReportContributor` writing `section.summary("static")`. Add the helper
`snapshotOf(StartupReportView, LivePanels)`, built like the file's existing snapshots, with the new constructor
argument.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension test -Dtest='LivePanelsTest,SnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE (`LivePanels` not found).

- [ ] **Step 3: Write the implementation**

`CONSOLE/LivePanels.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The live panels of this boot, from the {@code -dev} modules {@code vidocq:dev} added (Vidocq/vidocq#143), by the
 * id of the section each one makes live. The first panel found for an id wins: a second one is
 * {@value #DUPLICATE}. Started once, the first time the console reads the written report; stopped once, with the
 * console. A panel whose {@code start} throws is dropped: its section stays static.
 */
final class LivePanels {

    /** Two live panels for one section id: the second is skipped. */
    static final String DUPLICATE = "VIDOCQ-DEVC-007";
    /** A live panel whose section is missing from the report: not shown. */
    static final String ORPHAN = "VIDOCQ-DEVC-008";
    /** A section whose contributor is itself a panel, with a live panel for the same id: the live panel wins. */
    static final String DOUBLE = "VIDOCQ-DEVC-009";

    static final LivePanels NONE = new LivePanels(Map.of(), warning -> {});

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    private final Map<String, LivePanel> byId;
    private final Consumer<String> warn;
    private boolean started;
    private boolean stopped;

    private LivePanels(Map<String, LivePanel> byId, Consumer<String> warn) {
        this.byId = byId;
        this.warn = warn;
    }

    /** The live panels the service loader finds from {@code loader}, warnings to the console's logger. */
    static LivePanels load(ClassLoader loader) {
        List<LivePanel> found = new ArrayList<>();
        try {
            ServiceLoader.load(LivePanel.class, loader).forEach(found::add);
        } catch (java.util.ServiceConfigurationError broken) {
            LOG.log(System.Logger.Level.WARNING, "Dev console: a live panel could not be loaded: "
                    + broken.getClass().getName());
        }
        return of(found, message -> LOG.log(System.Logger.Level.WARNING, message));
    }

    static LivePanels of(List<? extends LivePanel> panels, Consumer<String> warn) {
        Map<String, LivePanel> byId = new LinkedHashMap<>();
        for (LivePanel panel : panels) {
            String id = panel.id();
            LivePanel first = byId.putIfAbsent(id, panel);
            if (first != null) {
                warn.accept("[" + DUPLICATE + "] Two live panels for the section '" + Texts.clean(id) + "': "
                        + first.getClass().getName() + " is kept, " + panel.getClass().getName() + " is skipped");
            }
        }
        return new LivePanels(byId, warn);
    }

    /** The live panel of the section {@code id}, if any is loaded and running. */
    synchronized Optional<LivePanel> forSection(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    synchronized Set<String> ids() {
        return Set.copyOf(byId.keySet());
    }

    /** Starts every panel, once; one whose start throws is dropped, with a WARNING. */
    synchronized void startAll(ExtensionContext context) {
        if (started || stopped) {
            return;
        }
        started = true;
        for (var it = byId.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            try {
                entry.getValue().start(context);
            } catch (RuntimeException | LinkageError failed) {
                it.remove();
                warn.accept("Dev console: the live panel '" + Texts.clean(entry.getKey()) + "' failed to start: "
                        + failed.getClass().getName());
            }
        }
    }

    /** Stops every started panel, once. */
    synchronized void stopAll() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (!started) {
            return;
        }
        for (LivePanel panel : byId.values()) {
            try {
                panel.stop();
            } catch (RuntimeException | LinkageError ignored) {
                // stopping never fails the console's own stop
            }
        }
    }

    /** Reports the panels no section of {@code sectionIds} took, {@value #ORPHAN}, once per boot. */
    synchronized void reportOrphans(Set<String> sectionIds) {
        for (String id : byId.keySet()) {
            if (!sectionIds.contains(id)) {
                warn.accept("[" + ORPHAN + "] The live panel '" + Texts.clean(id)
                        + "' has no section in the startup report: not shown");
            }
        }
    }

    /** {@value #DOUBLE}: the extension of {@code id} also ships its own panel. */
    void reportDouble(String id, Object contributor) {
        warn.accept("[" + DOUBLE + "] The section '" + Texts.clean(id) + "' is written by " + contributor.getClass()
                .getName() + ", itself a panel, and a live panel makes it live: the live panel wins; the extension"
                + " ships its panel twice");
    }
}
```

`CONSOLE/LivePanelAdapter.java`:

```java
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.List;

/**
 * A static section and its live panel, seen as one {@link DevConsolePanel}: the contributor gives the id, the title
 * and the section; the live panel gives the samples, the charts and the actions. What {@link PanelEntry} and
 * {@link Snapshot} already know how to show.
 */
final class LivePanelAdapter implements DevConsolePanel {

    private final StartupReportContributor contributor;
    private final LivePanel live;

    LivePanelAdapter(StartupReportContributor contributor, LivePanel live) {
        this.contributor = contributor;
        this.live = live;
    }

    LivePanel live() {
        return live;
    }

    @Override
    public String id() {
        return contributor.id();
    }

    @Override
    public String title() {
        return contributor.title();
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        contributor.contribute(context, section);
    }

    @Override
    public List<Chart> charts() {
        return live.charts();
    }

    @Override
    public List<PanelAction> actions() {
        return live.actions();
    }

    @Override
    public void sample(PanelSample sample) {
        live.sample(sample);
    }
}
```

`CONSOLE/Snapshot.java`:
1. Add the field `private final LivePanels livePanels;` and the field `private final ExtensionContext context;`,
   importing `io.vidocq.runtime.spi.ExtensionContext`.
2. Give the six-argument constructor (line ~170) two more parameters, `LivePanels livePanels` and
   `ExtensionContext context`. Assign `livePanels` with `Objects.requireNonNull`; `context` may be `null` in tests.
   Keep a six-argument overload that delegates with `LivePanels.NONE, null`, so existing tests compile.
3. Replace `read(StartupReportView, boolean)` (lines 378-396) with:

```java
    private Contributed read(StartupReportView view, boolean dev) {
        livePanels.startAll(context);
        List<ReportSection> sections = view.sections();
        List<PanelEntry> panels = new ArrayList<>();
        Set<String> panelIds = new HashSet<>();
        for (StartupReportContributor contributor : view.contributors()) {
            String id = id(contributor);
            if (id == null || id.equals(DevConsoleExtension.ID) || panelIds.contains(id)) {
                continue;
            }
            for (ReportSection section : sections) {
                if (section.id().equals(id)) {
                    StartupReportContributor shown = contributor;
                    var live = livePanels.forSection(id);
                    if (live.isPresent()) {
                        if (contributor instanceof DevConsolePanel) {
                            livePanels.reportDouble(id, contributor);
                        }
                        shown = new LivePanelAdapter(contributor, live.get());
                    }
                    panels.add(PanelEntry.contributed(shown, section, dev));
                    panelIds.add(id);
                    break;
                }
            }
        }
        livePanels.reportOrphans(panelIds);
        List<ReportSection> others = sections.stream().filter(s -> !panelIds.contains(s.id())).toList();
        return new Contributed(view, List.copyOf(panels), others);
    }
```

(It stops being `static`. The caller at line ~374 already calls it from an instance method. Import `DevConsolePanel`
if the file does not.)

`CONSOLE/DevConsoleExtension.java`:
- Add the field `private volatile LivePanels livePanels = LivePanels.NONE;`.
- In `onStart`, before `new Snapshot(...)`: `LivePanels live = LivePanels.load(DevConsoleExtension.class.getClassLoader());`,
  then `livePanels = live;`. Pass `live, context` to the `Snapshot` constructor.
- In `onStop`, after the ticker is joined: `LivePanels live = livePanels; livePanels = LivePanels.NONE; live.stopAll();`.

`module-info.java`: add `uses io.vidocq.runtime.spi.devconsole.LivePanel;`, with the comment
`// the live halves of report sections, from the -dev modules vidocq:dev adds (Vidocq/vidocq#143)`.

Add the manifest marking to the console's `pom.xml`.

- [ ] **Step 4: Run them to see them pass, then the whole module**

Run: the command of Step 2, then
`mvn -o -q -pl vidocq-runtime-devconsole-spi,vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-devconsole-extension install`.
Expected: all green, including the existing `SnapshotTest` and `DevConsoleExtensionTest`.

- [ ] **Step 5: Commit** — `feat(devconsole): make report sections live from LivePanel services (#143)`.

---

### Task 3: No binary contains a dev-only jar

**Files:**
- Create: `PLUGIN/DevOnlyJars.java`
- Modify:
  - `PLUGIN/ApplicationLaunch.java`: `modulePath`, with an overload;
  - `PLUGIN/VidocqRunMojo.java:206`;
  - `PLUGIN/VidocqPackageMojo.java:130-148`;
  - `PLUGIN/VidocqJlinkMojo.java:156-190`.
- Test: `PLUGIN_TEST/DevOnlyJarsTest.java`, `PLUGIN_TEST/ApplicationLaunchTest.java` (new)

**Interfaces:**
- Produces:
  - `DevOnlyJars.MANIFEST_ENTRY = "Vidocq-Dev-Only"`;
  - `static boolean isDevOnly(Path jar)`;
  - `static String droppedWarning(String artifactId)`;
  - `ApplicationLaunch.modulePath(MavenProject, Path, Path, boolean layerMode, Consumer<Path> modularized, boolean
    dropDevOnly, Consumer<String> dropped)`. The existing five-argument method delegates with
    `dropDevOnly = false`.

- [ ] **Step 1: Write the failing tests**

`PLUGIN_TEST/DevOnlyJarsTest.java`:

```java
package io.vidocq.runtime.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevOnlyJarsTest {

    static Path jar(Path dir, String name, String devOnly) throws Exception {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (devOnly != null) {
            manifest.getMainAttributes().putValue(DevOnlyJars.MANIFEST_ENTRY, devOnly);
        }
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jarOut = new JarOutputStream(out, manifest)) {
            jarOut.putNextEntry(new ZipEntry("a/A.class"));
            jarOut.closeEntry();
        }
        return jar;
    }

    @Test
    void aMarkedJarIsDevOnlyInAnyCase(@TempDir Path dir) throws Exception {
        assertTrue(DevOnlyJars.isDevOnly(jar(dir, "a.jar", "true")));
        assertTrue(DevOnlyJars.isDevOnly(jar(dir, "b.jar", "TRUE")));
        assertFalse(DevOnlyJars.isDevOnly(jar(dir, "c.jar", "false")));
        assertFalse(DevOnlyJars.isDevOnly(jar(dir, "d.jar", null)));
    }

    @Test
    void aJarWithoutManifestOrUnreadableIsNotDevOnly(@TempDir Path dir) throws Exception {
        Path noManifest = dir.resolve("e.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(noManifest))) {
            zip.putNextEntry(new ZipEntry("a/A.class"));
            zip.closeEntry();
        }
        Path garbage = Files.writeString(dir.resolve("f.jar"), "not a zip");

        assertFalse(DevOnlyJars.isDevOnly(noManifest));
        assertFalse(DevOnlyJars.isDevOnly(garbage));
        assertFalse(DevOnlyJars.isDevOnly(dir.resolve("absent.jar")));
        assertFalse(DevOnlyJars.isDevOnly(dir), "a directory, such as target/classes");
    }

    @Test
    void theWarningNamesTheArtifact() {
        assertEquals("vidocq-runtime-devconsole-extension is dev-only: not packaged; remove the dependency,"
                + " vidocq:dev brings it", DevOnlyJars.droppedWarning("vidocq-runtime-devconsole-extension"));
    }
}
```

`PLUGIN_TEST/ApplicationLaunchTest.java`: build a `MavenProject` with two artifacts, one marked jar and one plain,
using `org.apache.maven.artifact.DefaultArtifact` and `project.setArtifacts(Set.of(...))`. Look at
`VidocqRunMojoTest` for how this module builds projects in tests. Assert that:

```java
        List<String> dropped = new ArrayList<>();
        List<Path> kept = ApplicationLaunch.modulePath(project, build, classes, true, jar -> {}, true, dropped::add);
        assertEquals(List.of(plainJar), kept);
        assertEquals(List.of("vidocq-runtime-devconsole-extension"), dropped);
        assertEquals(List.of(plainJar, markedJar).size(),
                ApplicationLaunch.modulePath(project, build, classes, true, jar -> {}).size(),
                "vidocq:dev keeps a declared dev-only jar");
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='DevOnlyJarsTest,ApplicationLaunchTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE.

- [ ] **Step 3: Write the implementation**

`PLUGIN/DevOnlyJars.java`:

```java
package io.vidocq.runtime.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * The jars that are development tools (Vidocq/vidocq#143): their manifest says {@code Vidocq-Dev-Only: true}. Only
 * {@code vidocq:dev} adds them; {@code vidocq:run} and the packaging goals drop them, even when the project declares
 * them. A jar that cannot be read, or has no manifest, is not dev-only: the filter never fails a build.
 */
public final class DevOnlyJars {

    /** The manifest's main attribute that marks a dev-only jar. */
    public static final String MANIFEST_ENTRY = "Vidocq-Dev-Only";

    private DevOnlyJars() {}

    public static boolean isDevOnly(Path jar) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest != null
                    && "true".equalsIgnoreCase(manifest.getMainAttributes().getValue(MANIFEST_ENTRY));
        } catch (IOException | SecurityException unreadable) {
            return false;
        }
    }

    public static String droppedWarning(String artifactId) {
        return artifactId + " is dev-only: not packaged; remove the dependency, vidocq:dev brings it";
    }
}
```

`PLUGIN/ApplicationLaunch.java`: add the seven-argument `modulePath`. Its loop skips an artifact whose file is
dev-only when `dropDevOnly` is set, and calls `dropped.accept(artifact.getArtifactId())`. The five-argument method
becomes `return modulePath(project, buildDir, classesDir, layerMode, modularized, false, id -> {});`.

`PLUGIN/VidocqRunMojo.java:206`: call the seven-argument form with
`true, id -> getLog().warn(DevOnlyJars.droppedWarning(id))`.

`PLUGIN/VidocqPackageMojo.java` and `PLUGIN/VidocqJlinkMojo.java`: at the top of each artifact loop, after the null
and type check:

```java
                if (DevOnlyJars.isDevOnly(artifact.getFile().toPath())) {
                    getLog().warn(DevOnlyJars.droppedWarning(artifact.getArtifactId()));
                    continue;
                }
```

`VidocqDevMojo`'s call (via `buildModulePath`) is unchanged: it uses the five-argument form, which keeps declared
dev-only jars (Ruling 6).

- [ ] **Step 4: Run them to see them pass, then the plugin module**

Run: the command of Step 2, then `mvn -o -q -pl vidocq-runtime-maven-plugin test`.
Expected: all green.

- [ ] **Step 5: Commit** — `feat(plugin): no binary contains a dev-only jar (#143)`.

---

### Task 4: `vidocq:dev` adds the console and the companions

**Files:**
- Create: `PLUGIN/dev/DevModules.java`, `PLUGIN/dev/DevConsoleJars.java`
- Modify:
  - `PLUGIN/dev/VidocqDevMojo.java`: the dev services block (~line 300), plus new parameters;
  - `vidocq-runtime-maven-plugin/src/main/resources/META-INF/maven/plugin.xml`: the `dev` mojo gets three parameters
    and a `<requirements>` block;
  - `vidocq-runtime-maven-plugin/pom.xml`: a runtime dependency on the console.
- Test: `PLUGIN_TEST/dev/DevModulesTest.java`, `PLUGIN_TEST/dev/DevConsoleJarsTest.java`,
  `PLUGIN_TEST/dev/PluginDescriptorContinuousTestingTest.java` (added test)

**Interfaces:**
- Consumes: `DevOnlyJars.isDevOnly` (Task 3).
- Produces:
  - in `DevModules`:
    - `DESCRIPTOR = "META-INF/vidocq/dev-module"`;
    - `record Resolved(String key, Path file)`;
    - `interface Resolver { List<Resolved> resolve(String coordinates) throws Exception; }`;
    - `static Optional<String> companionOf(Path jar, Consumer<String> warn)`;
    - `static List<Path> collect(Collection<Artifact> projectArtifacts, Resolver resolver, Consumer<String> warn,
      Consumer<String> info)`;
  - `DevConsoleJars.resolve(Map<String, Artifact> pluginArtifacts, Collection<Artifact> projectArtifacts,
    Consumer<String> info)`, which returns a `List<Path>`, empty without Chappe.

- [ ] **Step 1: Write the failing tests**

`PLUGIN_TEST/dev/DevModulesTest.java`. Jars are built as in `DevOnlyJarsTest`, with a helper that also writes
`META-INF/vidocq/dev-module`, and projects hold `DefaultArtifact`s whose file is set:

```java
    @Test
    void theDescriptorNamesOneCompanion(@TempDir Path dir) throws Exception {
        assertEquals(Optional.of("vidocq-runtime-knock-health-extension-dev"),
                DevModules.companionOf(withDescriptor(dir, "a.jar", "# comment\n\nvidocq-runtime-knock-health-extension-dev\n"), w -> {}));
        assertEquals(Optional.empty(), DevModules.companionOf(plainJar(dir, "b.jar"), w -> {}));
        List<String> warnings = new ArrayList<>();
        assertEquals(Optional.empty(), DevModules.companionOf(withDescriptor(dir, "c.jar", "one\ntwo\n"), warnings::add));
        assertEquals(Optional.empty(), DevModules.companionOf(withDescriptor(dir, "d.jar", "bad id!"), warnings::add));
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void aCompanionAndItsDevOnlyDependenciesAreAddedOnce(@TempDir Path dir) throws Exception {
        Artifact knock = artifact("io.vidocq.runtime.extensions.microprofile", "vidocq-runtime-knock-health-extension",
                withDescriptor(dir, "knock.jar", "vidocq-runtime-knock-health-extension-dev"));
        Path dev = markedJar(dir, "knock-dev.jar");
        Path spi = markedJar(dir, "spi.jar");
        DevModules.Resolver resolver = coords -> List.of(
                new DevModules.Resolved("io.vidocq.runtime.extensions.microprofile:vidocq-runtime-knock-health-extension-dev", dev),
                new DevModules.Resolved("io.vidocq.runtime.extensions.microprofile:vidocq-runtime-knock-health-extension", knock.getFile().toPath()),
                new DevModules.Resolved("io.vidocq.runtime:vidocq-runtime-devconsole-spi", spi));

        List<Path> added = DevModules.collect(List.of(knock), resolver, w -> {}, i -> {});

        assertEquals(List.of(dev, spi), added, "the extension itself is already on the path");
    }

    @Test
    void aCompanionNeedingRuntimeCodeTheApplicationLacksIsSkipped(@TempDir Path dir) throws Exception {
        Artifact knock = artifact("g", "ext", withDescriptor(dir, "ext.jar", "ext-dev"));
        Path dev = markedJar(dir, "ext-dev.jar");
        Path runtimeLib = plainJar(dir, "lib.jar");
        List<String> warnings = new ArrayList<>();

        List<Path> added = DevModules.collect(List.of(knock), coords -> List.of(
                new DevModules.Resolved("g:ext-dev", dev), new DevModules.Resolved("g:lib", runtimeLib)),
                warnings::add, i -> {});

        assertEquals(List.of(), added);
        assertTrue(warnings.getFirst().contains("g:lib"), warnings.toString());
    }

    @Test
    void anUnresolvableCompanionWarnsAndNeverFails(@TempDir Path dir) throws Exception {
        Artifact knock = artifact("g", "ext", withDescriptor(dir, "ext.jar", "ext-dev"));
        List<String> warnings = new ArrayList<>();

        List<Path> added = DevModules.collect(List.of(knock), coords -> {
            throw new IllegalStateException("offline");
        }, warnings::add, i -> {});

        assertEquals(List.of(), added);
        assertTrue(warnings.getFirst().contains("g:ext-dev:1.0"), warnings.toString());
    }
```

(`pluginMapWithConsole(dir)` returns a `Map<String, Artifact>` keyed `groupId:artifactId` holding
`DevConsoleJars.CONSOLE_KEY` and `DevConsoleJars.SPI_KEY`, each an artifact whose file is a marked jar.
`artifact(groupId, artifactId, jar)` builds a `DefaultArtifact(groupId, artifactId, "1.0", "runtime", "jar", null,
new DefaultArtifactHandler("jar"))` and sets its file.)

`PLUGIN_TEST/dev/DevConsoleJarsTest.java`:

```java
    @Test
    void withoutChappeNoConsoleIsAdded(@TempDir Path dir) throws Exception {
        List<String> infos = new ArrayList<>();
        List<Path> jars = DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(), infos::add);

        assertEquals(List.of(), jars);
        assertTrue(infos.getFirst().contains("vidocq-runtime-chappe-webserver-extension"), infos.toString());
    }

    @Test
    void withChappeTheConsoleAndItsSpiAreAddedUnlessDeclared(@TempDir Path dir) throws Exception {
        Artifact chappe = artifact("io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-chappe-webserver-extension", plainJar(dir, "chappe.jar"));

        assertEquals(2, DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(chappe), i -> {}).size());
        Artifact declared = artifact("io.vidocq.runtime.extensions.essentials", "vidocq-runtime-devconsole-extension",
                markedJar(dir, "declared-console.jar"));
        assertEquals(1, DevConsoleJars.resolve(pluginMapWithConsole(dir), List.of(chappe, declared), i -> {}).size(),
                "never twice: the declared console is kept");
    }
```

Add to `PluginDescriptorContinuousTestingTest`:

```java
    @Test
    void devResolvesCompanionsThroughMavenResolver() throws Exception {
        assertParametersAreFields("dev", VidocqDevMojo.class);
        assertTrue(child(mojo("dev"), "requirements") != null, "the dev mojo needs the RepositorySystem");
    }
```

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='DevModulesTest,DevConsoleJarsTest,PluginDescriptorContinuousTestingTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE.

- [ ] **Step 3: Write the implementation**

`PLUGIN/dev/DevModules.java`:

```java
package io.vidocq.runtime.maven.dev;

import io.vidocq.runtime.maven.DevOnlyJars;
import org.apache.maven.artifact.Artifact;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * The {@code -dev} companions of the project's extensions (Vidocq/vidocq#143): each runtime extension with a live
 * panel names its companion in {@value #DESCRIPTOR}, same groupId and version. {@code vidocq:dev} adds each companion
 * with its dev-only runtime dependencies, never a module already on the path, and never a companion that would bring
 * runtime code the application does not have. A companion that cannot be resolved costs its live panel only.
 */
final class DevModules {

    static final String DESCRIPTOR = "META-INF/vidocq/dev-module";
    static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

    /** One resolved jar: {@code groupId:artifactId} and its file. */
    record Resolved(String key, Path file) {}

    /** Resolves {@code groupId:artifactId:version} and its runtime dependencies. */
    @FunctionalInterface
    interface Resolver {
        List<Resolved> resolve(String coordinates) throws Exception;
    }

    private DevModules() {}

    /** The companion artifactId {@code jar} names, if it has a readable descriptor. */
    static Optional<String> companionOf(Path jar, Consumer<String> warn) {
        if (jar == null || !Files.isRegularFile(jar)) {
            return Optional.empty();
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(DESCRIPTOR);
            if (entry == null) {
                return Optional.empty();
            }
            List<String> ids = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(entry), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    String id = line.strip();
                    if (!id.isEmpty() && !id.startsWith("#")) {
                        ids.add(id);
                    }
                }
            }
            if (ids.size() != 1 || !ARTIFACT_ID.matcher(ids.getFirst()).matches()) {
                warn.accept("Dev tools: " + DESCRIPTOR + " of " + jar.getFileName()
                        + " must hold one artifactId; that extension gets no dev module");
                return Optional.empty();
            }
            return Optional.of(ids.getFirst());
        } catch (IOException | SecurityException unreadable) {
            warn.accept("Dev tools: " + jar.getFileName() + " cannot be read; that extension gets no dev module");
            return Optional.empty();
        }
    }

    /** The jars to add to the child's module path, in the order of the project's artifacts. */
    static List<Path> collect(Collection<Artifact> projectArtifacts, Resolver resolver, Consumer<String> warn,
            Consumer<String> info) {
        Set<String> onPath = new HashSet<>();
        for (Artifact artifact : projectArtifacts) {
            onPath.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
        }
        List<Path> added = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Artifact artifact : projectArtifacts) {
            if (artifact.getFile() == null) {
                continue;
            }
            Optional<String> companion = companionOf(artifact.getFile().toPath(), warn);
            if (companion.isEmpty()) {
                continue;
            }
            String key = artifact.getGroupId() + ":" + companion.get();
            if (onPath.contains(key)) {
                continue;
            }
            String coordinates = key + ":" + artifact.getVersion();
            List<Resolved> resolved;
            try {
                resolved = resolver.resolve(coordinates);
            } catch (Exception unresolvable) {
                warn.accept("Dev tools: " + coordinates + " not found (" + unresolvable.getClass().getSimpleName()
                        + "); the " + artifact.getArtifactId() + " panel shows its boot facts only. Run the build"
                        + " once online, or mvn -U");
                continue;
            }
            List<Resolved> toAdd = new ArrayList<>();
            String foreign = null;
            for (Resolved jar : resolved) {
                if (onPath.contains(jar.key())) {
                    continue;
                }
                if (!DevOnlyJars.isDevOnly(jar.file())) {
                    foreign = jar.key();
                    break;
                }
                toAdd.add(jar);
            }
            if (foreign != null) {
                warn.accept("Dev tools: " + coordinates + " needs " + foreign
                        + ", which the application does not have; its panel shows its boot facts only");
                continue;
            }
            for (Resolved jar : toAdd) {
                onPath.add(jar.key());
                added.add(jar.file());
            }
            names.add(companion.get());
        }
        if (!names.isEmpty()) {
            info.accept("Dev tools: " + String.join(", ", names));
        }
        return added;
    }
}
```

`PLUGIN/dev/DevConsoleJars.java`:

```java
package io.vidocq.runtime.maven.dev;

import org.apache.maven.artifact.Artifact;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The dev console for {@code vidocq:dev} (Vidocq/vidocq#143), from this plugin's own dependencies, as
 * {@link DevServicesExtensionJar} does for the dev services: the console and its SPI, each unless the project already
 * has it. The console serves its page over Chappe: without the project's HTTP server extension, nothing is added, so
 * that a CLI application never starts a listener because it runs under {@code vidocq:dev}.
 */
final class DevConsoleJars {

    static final String CHAPPE_KEY = "io.vidocq.runtime.extensions.essentials:vidocq-runtime-chappe-webserver-extension";
    static final String CONSOLE_KEY = "io.vidocq.runtime.extensions.essentials:vidocq-runtime-devconsole-extension";
    static final String SPI_KEY = "io.vidocq.runtime:vidocq-runtime-devconsole-spi";

    private DevConsoleJars() {}

    static List<Path> resolve(Map<String, Artifact> pluginArtifacts, Collection<Artifact> projectArtifacts,
            Consumer<String> info) {
        Set<String> onPath = new HashSet<>();
        for (Artifact artifact : projectArtifacts) {
            onPath.add(artifact.getGroupId() + ":" + artifact.getArtifactId());
        }
        if (!onPath.contains(CHAPPE_KEY)) {
            info.accept("Dev tools: no dev console, it needs vidocq-runtime-chappe-webserver-extension");
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        for (String key : List.of(CONSOLE_KEY, SPI_KEY)) {
            if (onPath.contains(key)) {
                continue;
            }
            Artifact artifact = pluginArtifacts == null ? null : pluginArtifacts.get(key);
            if (artifact == null || artifact.getFile() == null) {
                throw new IllegalStateException("The vidocq plugin lacks " + key + ": a broken plugin installation");
            }
            jars.add(artifact.getFile().toPath());
        }
        return jars;
    }
}
```

`PLUGIN/dev/VidocqDevMojo.java`:
- Add the resolver fields, as `VidocqCheckPomMojo` has them (lines 105-112):

```java
    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private org.eclipse.aether.RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<org.eclipse.aether.repository.RemoteRepository> remoteRepos;

    @org.apache.maven.plugins.annotations.Component
    private org.eclipse.aether.RepositorySystem repoSystem;
```

- After the dev services block, before continuous testing, add the dev tools:

```java
        // Dev tools (Vidocq/vidocq#143): the console, then each extension's -dev companion, on the child's module
        // path only. No binary ever contains them.
        try {
            modulePath.addAll(DevConsoleJars.resolve(pluginArtifactMap, project.getArtifacts(), getLog()::info));
        } catch (IllegalStateException broken) {
            throw new MojoExecutionException(broken.getMessage(), broken);
        }
        modulePath.addAll(DevModules.collect(project.getArtifacts(), this::resolveRuntime, getLog()::warn,
                getLog()::info));
```

- Add the resolver method:

```java
    /** {@code coordinates} and its runtime dependencies, through Maven's own resolution and repositories. */
    private List<DevModules.Resolved> resolveRuntime(String coordinates) throws Exception {
        var root = new org.eclipse.aether.graph.Dependency(
                new org.eclipse.aether.artifact.DefaultArtifact(coordinates), "runtime");
        var collect = new org.eclipse.aether.collection.CollectRequest(root, remoteRepos);
        var request = new org.eclipse.aether.resolution.DependencyRequest(collect,
                org.eclipse.aether.util.filter.DependencyFilterUtils.classpathFilter("runtime"));
        List<DevModules.Resolved> jars = new ArrayList<>();
        for (var result : repoSystem.resolveDependencies(repoSession, request).getArtifactResults()) {
            var artifact = result.getArtifact();
            jars.add(new DevModules.Resolved(artifact.getGroupId() + ":" + artifact.getArtifactId(),
                    artifact.getFile().toPath()));
        }
        return jars;
    }
```

(Use imports rather than the qualified names if the file prefers. Check that `maven-resolver-util`, which holds
`DependencyFilterUtils`, is on the plugin's compile path: `mvn -o dependency:tree -pl vidocq-runtime-maven-plugin`.
If it is not, add it with `provided` scope, the version Maven 3.9.9 ships.)

`plugin.xml`, `dev` mojo: add the three parameters and their configuration, copied from the `checkpom` mojo (lines
~548-563), with the field names above: `repoSession`, `remoteRepos` and `repoSystem`. The `remoteRepos` default value
is `${project.remoteProjectRepositories}`. Add the `<requirements>` block with `<field-name>repoSystem</field-name>`.

`vidocq-runtime-maven-plugin/pom.xml`: next to the dev services extension, add:

```xml
        <!-- Runtime-only: DevConsoleJars adds it to the child's module path under vidocq:dev (Vidocq/vidocq#143).
             The plugin never calls into it. -->
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-devconsole-extension</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 4: Run them to see them pass**

Run: the command of Step 2, then `mvn -o -q -pl vidocq-runtime-maven-plugin -am install -DskipTests` and
`mvn -o -q -pl vidocq-runtime-maven-plugin test`.
Expected: all green.

- [ ] **Step 5: Commit** — `feat(dev): vidocq:dev adds the dev console and each extension's -dev module (#143)`.

---

### Task 5: `checkpom` warns about a declared dev-only dependency

**Files:**
- Modify: `PLUGIN/VidocqCheckPomMojo.java`
- Test: `PLUGIN_TEST/VidocqCheckPomMojoTest.java` (added test)

**Interfaces:**
- Consumes: `DevOnlyJars.isDevOnly`, `DevOnlyJars.droppedWarning`.
- Produces: `static List<String> findDevOnlyDeclarations(List<Dependency> declared, Function<Dependency,
  Optional<Path>> jarOf)`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void aDevOnlyDependencyOutsideTestScopeIsReported(@TempDir Path dir) throws Exception {
        Path marked = DevOnlyJarsTest.jar(dir, "console.jar", "true");
        Dependency compile = dependency("vidocq-runtime-devconsole-extension", null);
        Dependency test = dependency("vidocq-runtime-devconsole-extension", "test");
        Dependency plain = dependency("vidocq-runtime-core", "compile");

        List<String> issues = VidocqCheckPomMojo.findDevOnlyDeclarations(List.of(compile, test, plain),
                d -> d.getArtifactId().contains("devconsole") ? Optional.of(marked)
                        : Optional.of(DevOnlyJarsTest.jar(dir, "core.jar", null)));

        assertEquals(List.of(DevOnlyJars.droppedWarning("vidocq-runtime-devconsole-extension")), issues);
    }
```

(`dependency(artifactId, scope)` builds an `org.apache.maven.model.Dependency` with groupId
`io.vidocq.runtime.extensions.essentials`. `DevOnlyJarsTest.jar` throws `Exception`, so wrap the lambda in a helper
that rethrows unchecked.)

- [ ] **Step 2: Run it to see it fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest=VidocqCheckPomMojoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE.

- [ ] **Step 3: Write the implementation**

In `VidocqCheckPomMojo`:

```java
    /**
     * The dependencies on a dev-only jar declared in a scope other than {@code test} (Vidocq/vidocq#143): the
     * packaging goals drop them anyway, and vidocq:dev brings them. A warning, never a failure.
     */
    static List<String> findDevOnlyDeclarations(List<Dependency> declared, Function<Dependency, Optional<Path>> jarOf) {
        List<String> issues = new ArrayList<>();
        for (Dependency d : declared) {
            if ("test".equals(d.getScope()) || !isVidocqRuntimeGroup(d.getGroupId())) {
                continue;
            }
            if (jarOf.apply(d).map(DevOnlyJars::isDevOnly).orElse(false)) {
                issues.add(DevOnlyJars.droppedWarning(d.getArtifactId()));
            }
        }
        return issues;
    }
```

In `execute()`, after the three existing checks, and outside the `failOnMissing` branch, always warn:

```java
        for (String issue : findDevOnlyDeclarations(project.getDependencies(), this::resolvedJar)) {
            getLog().warn(issue);
        }
```

`resolvedJar(Dependency)` resolves `groupId:artifactId:jar:version` with `repoSystem.resolveArtifact(repoSession,
new ArtifactRequest(...))`, as `codegenArtifactExists` does (line ~291). It returns `Optional.empty()` on any
`ArtifactResolutionException`. Imports: `java.util.function.Function`, `java.util.Optional`, `java.nio.file.Path`,
`io.vidocq.runtime.maven.DevOnlyJars`.

- [ ] **Step 4: Run it to see it pass** — the command of Step 2; expected green.

- [ ] **Step 5: Commit** — `feat(plugin): checkpom warns about a dev-only dependency outside test scope (#143)`.

---

### Task 6: `vidocq:idea` writes Dev, packaged and debug configurations

**Files:**
- Modify: `PLUGIN/idea/RunConfigurationRenderer.java`, `PLUGIN/idea/VidocqIdeaMojo.java` (`targets`, lines ~273-294)
- Test:
  - `PLUGIN_TEST/idea/RunConfigurationRendererTest.java` and `PLUGIN_TEST/idea/VidocqIdeaMojoTest.java`, updated
    and extended;
  - `vidocq-runtime-maven-plugin/src/it/idea-*`: the expected files, and the `verify.groovy` scripts that name them.

**Interfaces:**
- Produces:
  - in `RunConfigurationRenderer`: `DEV_GOAL = "vidocq:dev"`, `PACKAGED_SUFFIX = " (packaged)"`,
    `DEBUG_SUFFIX = " (debug)"`;
  - `static String mavenBody(IdeaApplication application, String jre, String goal, String name)`;
  - `static String debugBody(String name, String host, int port)`.

- [ ] **Step 1: Write the failing tests**

In `RunConfigurationRendererTest`:

```java
    @Test
    void theDebugConfigurationAttachesToTheDevAgent() {
        String body = RunConfigurationRenderer.debugBody("ServerApp (debug)", "127.0.0.1", 5005);

        assertTrue(body.contains("name=\"ServerApp (debug)\" type=\"Remote\""), body);
        assertTrue(body.contains("<option name=\"HOST\" value=\"127.0.0.1\" />"), body);
        assertTrue(body.contains("<option name=\"PORT\" value=\"5005\" />"), body);
    }

    @Test
    void theMainMavenConfigurationRunsVidocqDevAndThePackagedOneVidocqRun() {
        String dev = RunConfigurationRenderer.mavenBody(application("ServerApp"), null, RunConfigurationRenderer.DEV_GOAL,
                "ServerApp");
        String packaged = RunConfigurationRenderer.mavenBody(application("ServerApp"), null,
                RunConfigurationRenderer.RUN_GOAL, "ServerApp (packaged)");

        assertTrue(dev.contains("<option value=\"vidocq:dev\" />"), dev);
        assertTrue(packaged.contains("<option value=\"vidocq:run\" />"), packaged);
        assertTrue(packaged.contains("name=\"ServerApp (packaged)\""), packaged);
    }
```

(Use the test file's existing `IdeaApplication` builder under whatever name it has, and adapt the goal assertion to
how the current Maven body writes its goal: find `RUN_GOAL` in `mavenBody`.)

In `VidocqIdeaMojoTest`, add a test that the default kind writes three files, `ServerApp.run.xml`,
`ServerApp (packaged).run.xml` and `ServerApp (debug).run.xml`, and that the Application kind still writes one. Build
it from the file's existing end-to-end test that writes a Maven-kind file.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl vidocq-runtime-maven-plugin test -Dtest='RunConfigurationRendererTest,VidocqIdeaMojoTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation FAILURE (`debugBody`, `DEV_GOAL`).

- [ ] **Step 3: Write the implementation**

In `RunConfigurationRenderer`:
- Add `DEV_GOAL`, `PACKAGED_SUFFIX` and `DEBUG_SUFFIX`.
- Turn the private `mavenBody(application, jre)` into `mavenBody(application, jre, goal, name)`. It writes `name` as
  the configuration name and `goal` as the goal, where it wrote `application.configurationName()` and `RUN_GOAL`. The
  existing call becomes `mavenBody(application, jre, RUN_GOAL, application.configurationName())`.
- Add:

```java
    /** A Remote JVM Debug configuration that attaches to the debug agent of vidocq:dev (Vidocq/vidocq#143). */
    static String debugBody(String name, String host, int port) {
        StringBuilder xml = new StringBuilder();
        xml.append("<component name=\"ProjectRunConfigurationManager\">\n");
        xml.append("  <configuration default=\"false\" name=\"").append(escapeAttribute(name))
                .append("\" type=\"Remote\">\n");
        xml.append("    <option name=\"USE_SOCKET_TRANSPORT\" value=\"true\" />\n");
        xml.append("    <option name=\"SERVER_MODE\" value=\"false\" />\n");
        xml.append("    <option name=\"SHMEM_ADDRESS\" />\n");
        xml.append("    <option name=\"HOST\" value=\"").append(escapeAttribute(host)).append("\" />\n");
        xml.append("    <option name=\"PORT\" value=\"").append(port).append("\" />\n");
        xml.append("    <option name=\"AUTO_RESTART\" value=\"false\" />\n");
        xml.append("    <RunnerSettings RunnerId=\"Debug\">\n");
        xml.append("      <option name=\"DEBUG_PORT\" value=\"").append(port).append("\" />\n");
        xml.append("      <option name=\"LOCAL\" value=\"false\" />\n");
        xml.append("    </RunnerSettings>\n");
        xml.append("    <method v=\"2\" />\n");
        xml.append("  </configuration>\n");
        xml.append("</component>\n");
        return xml.toString();
    }
```

In `VidocqIdeaMojo.targets(...)`, for the Maven kind, produce three targets per application instead of one, each
classified and written through the same ownership rules:
- `configurationName()` with `mavenBody(app, jre, DEV_GOAL, name)`;
- `configurationName() + PACKAGED_SUFFIX` with `mavenBody(app, jre, RUN_GOAL, name + PACKAGED_SUFFIX)`;
- `configurationName() + DEBUG_SUFFIX` with `debugBody(...)`. The host and port come from the application module's
  `vidocq.dev.debugHost` and `vidocq.dev.debugPort` properties, else `JdwpAgent.DEFAULT_HOST` and `5005`.

The Application kind is unchanged. Each target's file name goes through `RunConfigurationRenderer.fileName(...)`,
with the suffixed name.

Invoker ITs (`src/it/idea-*`):
- Regenerate each expected file: `mvn -o -Pit -pl vidocq-runtime-maven-plugin verify`, then copy
  `target/it/<name>/.run/*.run.xml` over `src/it/<name>/expected/*.txt`.
- Add the two new files to each `expected/`.
- Update the `verify.groovy` scripts that count or name files.
- Review each regenerated file by eye: `vidocq:dev` in the main one, `vidocq:run` in `(packaged)`, and `Remote` in
  `(debug)`.

- [ ] **Step 4: Run them to see them pass**

Run: the command of Step 2, then `mvn -o -Pit -pl vidocq-runtime-maven-plugin verify`.
Expected: all green, the `idea-*` invoker projects included.

- [ ] **Step 5: Commit** — `feat(idea): Dev, packaged and debug run configurations (#143)`.

---

### Task 7: Knock's panel moves to `vidocq-runtime-knock-health-extension-dev`

This is the fully worked recipe. Tasks 8 to 12 apply the same steps, with the specifics given there.

**Files:**
- Runtime module `vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/vidocq-runtime-knock-health-extension`:
  - move `KnockLiveBean.java` and `CheckKeys.java` to the new package
    `src/main/java/io/vidocq/runtime/extensions/microprofile/knock/live/`, and make them `public`, with their `of`,
    `read`, `absence`, `display` and `normalize` members and the `NONE` and `NOT_*` constants;
  - `KnockHealthExtension.java`: drop `DevConsolePanel`, `charts()` and `sample()`; import from `.live`;
  - `src/main/module-info/module-info.java`: drop the `requires transitive` on the console SPI; add
    `exports io.vidocq.runtime.extensions.microprofile.knock.live to io.vidocq.runtime.extensions.microprofile.knock.dev;`;
  - `pom.xml`: drop `vidocq-runtime-devconsole-spi`;
  - create `src/main/resources/META-INF/vidocq/dev-module`;
  - tests: `CheckKeysTest` moves to the `.live` package; `HealthPanelTest` and the live tests of
    `KnockHealthExtensionTest` move to the new module.
- New module `vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/vidocq-runtime-knock-health-extension-dev`:
  - `pom.xml`;
  - `src/main/java/module-info.java`;
  - `src/main/java/io/vidocq/runtime/extensions/microprofile/knock/dev/HealthLivePanel.java` and `HealthPanel.java`
    (moved);
  - `META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`;
  - tests.
- Modify `vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/pom.xml`: add
  `<module>vidocq-runtime-knock-health-extension-dev</module>`.
- Modify the root `pom.xml` `<dependencyManagement>`: add the new artifact next to the runtime one.

**Interfaces:**
- Consumes: `LivePanel` (Task 1).
- Produces:
  - the `.live` API: `KnockLiveBean`, a public record with `of(BeanManager)`, `NONE`, `beans()`, `read()` and
    `absence()`, and `CheckKeys` with `display(String)`;
  - the service `HealthLivePanel`, id `health`.

- [ ] **Step 1: Write the failing tests**

In the runtime module, `src/test/java/io/vidocq/runtime/extensions/microprofile/knock/KnockSectionWithoutPanelTest.java`:

```java
package io.vidocq.runtime.extensions.microprofile.knock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Vidocq/vidocq#143: the runtime extension writes its section, and is no dev console panel any more. */
class KnockSectionWithoutPanelTest {

    @Test
    void theExtensionWritesItsSectionAndIsNoPanel() {
        KnockHealthExtension extension = new KnockHealthExtension();

        assertEquals("health", extension.id());
        assertFalse(java.util.Arrays.stream(KnockHealthExtension.class.getInterfaces())
                .anyMatch(type -> type.getName().startsWith("io.vidocq.runtime.spi.devconsole")));
    }

    @Test
    void theJarNamesItsDevCompanion() throws Exception {
        try (var in = KnockHealthExtension.class.getClassLoader().getResourceAsStream("META-INF/vidocq/dev-module")) {
            assertEquals("vidocq-runtime-knock-health-extension-dev", new String(in.readAllBytes()).strip());
        }
    }
}
```

In the new module, `src/test/java/io/vidocq/runtime/extensions/microprofile/knock/dev/HealthLivePanelTest.java`:

```java
package io.vidocq.runtime.extensions.microprofile.knock.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthLivePanelTest {

    @Test
    void itMakesTheHealthSectionLiveWithItsChart() {
        HealthLivePanel panel = new HealthLivePanel();

        assertEquals("health", panel.id());
        assertEquals(HealthPanel.CHARTS, panel.charts());
    }
}
```

Move `HealthPanelTest.java` into this package unchanged, apart from its `package` line, and move the live-sample
tests of `KnockHealthExtensionTest` there too, retargeted at `HealthLivePanel`:
- `start(context)` replaces `onStart(context)`, and `stop()` replaces `onStop()`;
- the test fixtures they use (`FakeExtensionContext`, `RecordingSample`) are copied into the new module's test
  sources.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/vidocq-runtime-knock-health-extension test -Dtest=KnockSectionWithoutPanelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (the extension still implements `DevConsolePanel`, and there is no descriptor).

- [ ] **Step 3: Write the implementation**

Runtime `KnockHealthExtension`:
- keep `name`, `onStart`, `onStop`, `id`, `title` and `contribute`, with `live` still feeding `contribute`;
- delete `charts()` and `sample()`;
- declare `implements VidocqExtension, StartupReportContributor`, importing
  `io.vidocq.runtime.spi.report.StartupReportContributor`;
- remove the `devconsole` imports.

Does `VidocqExtension` already extend `StartupReportContributor` in this codebase? Check
`vidocq-runtime-spi/src/main/java/io/vidocq/runtime/spi/VidocqExtension.java`. If it does, list `VidocqExtension`
alone.

Descriptor `src/main/resources/META-INF/vidocq/dev-module`:

```
# Vidocq/vidocq#143: the live panel of the health section, which only vidocq:dev adds.
vidocq-runtime-knock-health-extension-dev
```

Runtime `module-info.java` (under `src/main/module-info/`):

```java
module io.vidocq.runtime.extensions.microprofile.knock {
    requires transitive io.vidocq.knock.api;
    requires transitive io.vidocq.knock.core;
    requires transitive io.vidocq.knock.cdi.vauban;
    requires transitive io.vidocq.knock.jaxrs;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    requires jakarta.cdi;
    requires jakarta.ws.rs;

    // What the health panel of the -dev module reads (Vidocq/vidocq#143); no other module sees it.
    exports io.vidocq.runtime.extensions.microprofile.knock.live to io.vidocq.runtime.extensions.microprofile.knock.dev;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.microprofile.knock.KnockHealthExtension;
}
```

The runtime module still needs `io.vidocq.runtime.spi` for `VidocqExtension` and the report types. Keep whatever
brought it transitively before, by adding `requires transitive io.vidocq.runtime.spi;` when the removed devconsole
line was what supplied it. Check with `mvn -o -q -pl <module> -am compile`.

New module `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.vidocq.runtime.extensions.microprofile</groupId>
        <artifactId>vidocq-runtime-extensions-microprofile</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </parent>

    <artifactId>vidocq-runtime-knock-health-extension-dev</artifactId>
    <name>Vidocq Runtime :: Extensions :: Knock health :: dev console panel</name>
    <description>The live health panel of the dev console. Only vidocq:dev adds it; no binary contains it
        (Vidocq/vidocq#143).</description>

    <dependencies>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.microprofile</groupId>
            <artifactId>vidocq-runtime-knock-health-extension</artifactId>
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

    <!-- the manifest marking of the File structure section -->
</project>
```

(Add the marking block, and any Knock or Jakarta test dependency the moved tests need: copy them from the runtime
module's `pom.xml`, `test` scope.)

New `module-info.java`:

```java
/** The live health panel of the dev console, which only vidocq:dev adds (Vidocq/vidocq#143). */
module io.vidocq.runtime.extensions.microprofile.knock.dev {
    requires io.vidocq.runtime.extensions.microprofile.knock;
    requires io.vidocq.runtime.spi.devconsole;
    requires jakarta.cdi;

    provides io.vidocq.runtime.spi.devconsole.LivePanel
            with io.vidocq.runtime.extensions.microprofile.knock.dev.HealthLivePanel;
}
```

`META-INF/services/io.vidocq.runtime.spi.devconsole.LivePanel`:
`io.vidocq.runtime.extensions.microprofile.knock.dev.HealthLivePanel`.

`HealthLivePanel.java`:

```java
package io.vidocq.runtime.extensions.microprofile.knock.dev;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.runtime.extensions.microprofile.knock.live.KnockLiveBean;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.time.ZoneId;
import java.util.List;

/**
 * The health section, live: the last answer of each check, read in memory. No check is called and no bean is
 * created: when the registry does not exist yet, a single absent {@code checks} says why.
 */
public final class HealthLivePanel implements LivePanel {

    private volatile KnockLiveBean live = KnockLiveBean.NONE;

    /** Created by the service loader. */
    public HealthLivePanel() {}

    @Override
    public String id() {
        return "health";
    }

    /** Resolves the registry bean, once, without creating it. */
    @Override
    public void start(ExtensionContext context) {
        live = KnockLiveBean.of(context.beanManager());
    }

    @Override
    public void stop() {
        live = KnockLiveBean.NONE;
    }

    @Override
    public List<Chart> charts() {
        return HealthPanel.CHARTS;
    }

    @Override
    public void sample(PanelSample sample) {
        KnockLiveBean read = live;
        if (read.beans() == null) {
            return;
        }
        HealthCheckRegistry registry = read.read();
        if (registry == null) {
            sample.absent("checks", read.absence());
        } else {
            HealthPanel.write(registry, ZoneId.systemDefault(), sample);
        }
    }
}
```

`HealthPanel.java`:
- moves here unchanged, apart from its package;
- it imports `CheckKeys` from `.live`;
- its members that `HealthLivePanel` and the moved tests use stay package-private: same package.

Add `<module>vidocq-runtime-knock-health-extension-dev</module>` to the microprofile parent. Add the artifact to the
root `pom.xml` `<dependencyManagement>`, `${project.version}`, next to `vidocq-runtime-knock-health-extension`.

- [ ] **Step 4: Run them to see them pass**

Run: `mvn -o -q -pl vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/vidocq-runtime-knock-health-extension,vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/vidocq-runtime-knock-health-extension-dev -am install`,
then check the manifest of the `-dev` jar as in Task 1.
Expected:
- all tests green in both modules, including every moved one;
- `Vidocq-Dev-Only: true` in the `-dev` jar, and not in the runtime jar.

- [ ] **Step 5: Commit** — `refactor(knock): the health panel moves to a -dev module (#143)`.

---

### Task 8: Cassini's panel moves to `vidocq-runtime-cassini-rest-extension-dev`

Apply every step of Task 7 with these specifics:

| Item | Value |
|---|---|
| Runtime module | `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-core/vidocq-runtime-cassini-rest-extension` (standard `src/main/java/module-info.java`) |
| Moves to `...cassini.live` (public) | `RestMounts` (with `all()`), `RestMount` (record, nested types) |
| Stays in the runtime | `CassiniExtension` (minus `charts`/`sample`), `CassiniMountHandlerProvider`, `RestStartupSection` |
| Moves to the `-dev` module | `RestPanel` (with `CHARTS` and `sample(List<RestMount>, PanelSample)`) |
| Live panel | `RestLivePanel`, id `rest`: `charts()` returns `RestPanel.CHARTS`; `sample(s)` is `RestPanel.sample(RestMounts.all(), s)`; no `start` (the registry is static, cleared by the runtime's `onStop`) |
| Runtime `module-info` | drop `requires transitive io.vidocq.runtime.spi.devconsole;`; keep `exports ...cassini;`; add `exports io.vidocq.runtime.extensions.jakartaee.core.cassini.live to io.vidocq.runtime.extensions.jakartaee.core.cassini.dev;` |
| `-dev` `module-info` | `requires io.vidocq.runtime.extensions.jakartaee.core.cassini; requires io.vidocq.runtime.spi.devconsole; requires io.vidocq.cassini.api;` (whatever `RestPanel` imports) and `provides ...LivePanel with ...cassini.dev.RestLivePanel` |
| Tests | `RestMountTest` goes to `.live`; `RestPanelTest` goes to `-dev`; `RestStartupSectionTest` and `CassiniExtensionTest` stay; add `CassiniSectionWithoutPanelTest` (id `rest`, no devconsole interface, descriptor `vidocq-runtime-cassini-rest-extension-dev`) |
| Parent to list the module | `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-core/pom.xml` |

`RestLivePanel`:

```java
package io.vidocq.runtime.extensions.jakartaee.core.cassini.dev;

import io.vidocq.runtime.extensions.jakartaee.core.cassini.live.RestMounts;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.util.List;

/** The rest section, live: requests, responses and timings per mount, from the registry the runtime fills. */
public final class RestLivePanel implements LivePanel {

    public RestLivePanel() {}

    @Override
    public String id() {
        return "rest";
    }

    @Override
    public List<Chart> charts() {
        return RestPanel.CHARTS;
    }

    @Override
    public void sample(PanelSample sample) {
        RestPanel.sample(RestMounts.all(), sample);
    }
}
```

Commit: `refactor(cassini): the rest panel moves to a -dev module (#143)`.

---

### Task 9: Dirac's panel moves to `vidocq-runtime-dirac-metrics-extension-dev`

Apply every step of Task 7 with these specifics:

| Item | Value |
|---|---|
| Runtime module | `vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/vidocq-runtime-dirac-metrics-extension` (`src/main/module-info/module-info.java`, as Knock) |
| Moves to `...dirac.live` (public) | `DiracLiveBean` (`of(BeanManager)`, `NONE`, `beans()`, `registries()`, `absence()`), `DiracRegistries`, `MetricsScope` (with `Entry`, `Kind`), `MetricKeys` |
| Stays in the runtime | `DiracMetricsExtension` (minus `sample`), `MetricsStartupSection` |
| Moves to the `-dev` module | `MetricsPanel` (`write(DiracRegistries, PanelSample)`) |
| Live panel | `MetricsLivePanel`, id `metrics`. `start(context)`: `live = DiracLiveBean.of(context.beanManager())`. `stop()`: `live = DiracLiveBean.NONE`. `sample(s)`: return if `live.beans() == null`; `s.absent("registries", live.absence())` when `live.registries() == null`; else `MetricsPanel.write(live.registries(), s)`. No charts. |
| Runtime `module-info` | drop `requires transitive io.vidocq.runtime.spi.devconsole;`; add `exports io.vidocq.runtime.extensions.microprofile.dirac.live to io.vidocq.runtime.extensions.microprofile.dirac.dev;` |
| `-dev` `module-info` | `requires io.vidocq.runtime.extensions.microprofile.dirac; requires io.vidocq.runtime.spi.devconsole; requires jakarta.cdi; requires io.vidocq.dirac.api;` and the `provides` |
| Tests | `MetricKeysTest` goes to `.live`; `MetricsPanelTest` and the live part of `DiracMetricsExtensionTest` go to `-dev` (as `MetricsLivePanelTest`); `MetricsStartupSectionTest` stays; add `DiracSectionWithoutPanelTest` |
| Parent | `vidocq-runtime-extensions/vidocq-runtime-extensions-microprofile/pom.xml` |

Commit: `refactor(dirac): the metrics panel moves to a -dev module (#143)`.

---

### Task 10: Mansart's panel moves to `vidocq-runtime-mansart-pool-extension-dev`

Apply every step of Task 7 with these specifics. Mansart keeps its pools in instance fields, so it publishes a
holder.

| Item | Value |
|---|---|
| Runtime module | `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-extension` |
| New in `...mansart.pool.live` (public) | `MansartPoolsLive`, the holder below |
| Runtime changes | publish at the line where `views = List.copyOf(opened)` is set (~255): `MansartPoolsLive.publish(opened.stream().map(v -> new MansartPoolsLive.Pool(v.label(), v.pool())).toList());`. In `onStop` (~304), first thing: `MansartPoolsLive.clear();`. Delete `CHARTS`, `charts()` and `sample()`. |
| Moves to the `-dev` module | the chart list and the sample loop, as `PoolsLivePanel` below |
| Runtime `module-info` | drop the devconsole `requires transitive`; add `exports io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live to io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;` |
| `-dev` `module-info` | `requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool; requires io.vidocq.runtime.spi.devconsole; requires io.vidocq.mansart.pool.api;` (where `MansartDataSource`, `PoolConfig` and `PoolMetrics` live; check the imports) and the `provides` |
| Tests | split `MansartPoolPanelTest`: its section tests stay (renamed `MansartPoolSectionTest`); `eachPoolIsAGroupOfLiveValues`, `everyChartPlotsValuesTheSampleWrites` and `onceStoppedThePanelShowsNoPool` go to `-dev` as `PoolsLivePanelTest`, driving the pools through `MansartPoolsLive.publish` / `clear`; add `MansartSectionWithoutPanelTest` |
| Parent | `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/pom.xml` |

`MansartPoolsLive`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live;

import io.vidocq.mansart.pool.MansartDataSource;

import java.util.List;

/**
 * The pools the Mansart extension opened, for its -dev panel only (Vidocq/vidocq#143): published when the pools are
 * open, cleared first when they close, so that a dev reload never shows the previous boot's pools.
 */
public final class MansartPoolsLive {

    /** An open pool and its label, such as {@code @Default}. */
    public record Pool(String label, MansartDataSource pool) {}

    private static volatile List<Pool> pools = List.of();

    private MansartPoolsLive() {}

    public static List<Pool> pools() {
        return pools;
    }

    public static void publish(List<Pool> opened) {
        pools = List.copyOf(opened);
    }

    public static void clear() {
        pools = List.of();
    }
}
```

(Check `MansartDataSource`'s package from the extension's imports, and fix the import if it differs.)

`PoolsLivePanel`: id `mansart-pool`. Its `charts()` holds the `CHARTS` list moved verbatim from
`MansartPoolExtension` (lines ~150-153). Its `sample(PanelSample)` is the extension's former `sample` body, with
`for (MansartPoolsLive.Pool v : MansartPoolsLive.pools())` as its loop and `v.label()` / `v.pool()` as before.

Add a holder test in `-dev`: after `MansartPoolsLive.clear()`, a sample writes no group. This covers the dev reload
(Review Focus 4).

Commit: `refactor(mansart): the pool panel moves to a -dev module (#143)`.

---

### Task 11: Migration's panel moves to `vidocq-runtime-migration-extension-dev`

Apply every step of Task 7 with these specifics. The operations stay in the runtime (Ruling 3).

| Item | Value |
|---|---|
| Runtime module | `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension` |
| New in `...migration.live` (public) | `MigrationControl` (interface) and `MigrationLive` (holder), below; `MigrationInfo` (record, with `Migration`) moves here |
| Runtime changes | `MigrationExtension` implements `MigrationControl` privately. It publishes itself with `MigrationLive.publish(this)` at the end of `configure` when a migrator is selected, and calls `MigrationLive.clear()` first in `onStop`. `sample()` and `actions()` go; `migrateNow`, `cleanAndMigrate`, `onStart`'s dev listing and `outcomes` stay. `cleanDisabledKey(String)` becomes reachable through the interface. |
| Moves to the `-dev` module | the `sample` body and the two `PanelAction`s, reading `MigrationLive.control()` |
| Runtime `module-info` | drop the devconsole `requires transitive`; keep `exports ...migration;` and `uses ...SchemaMigrator;`; add `exports io.vidocq.runtime.extensions.essentials.migration.live to io.vidocq.runtime.extensions.essentials.migration.dev;` |
| Tests | the live tests of `MigrationExtensionTest` (lines ~258-455, the panel and the actions) move to `-dev` as `MigrationLivePanelTest`, driving a runtime extension configured as today and published through `MigrationLive`; `thePasswordNeverReachesThePanel` keeps both its halves, one per module; add `MigrationSectionWithoutPanelTest` |
| Parent | `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/pom.xml` |

`MigrationControl`:

```java
package io.vidocq.runtime.extensions.essentials.migration.live;

import java.util.List;

/**
 * What the migration panel of the -dev module shows and does (Vidocq/vidocq#143). The runtime extension implements
 * it: the operations and their locks stay there.
 */
public interface MigrationControl {

    /** One datasource as the panel shows it. */
    record LiveOutcome(String dataSource, String version, String lastRun, boolean cleanDisabled,
                       String cleanDisabledKey, MigrationInfo info, String infoAbsent) {}

    List<LiveOutcome> outcomes();

    /** The datasource names the actions accept, in configuration order; empty when there is no migrator. */
    List<String> dataSources();

    /** Migrates {@code dataSource}; the line the action returns. */
    String migrateNow(String dataSource);

    /** Cleans and migrates {@code dataSource}, or says why it refused; the line the action returns. */
    String cleanAndMigrate(String dataSource);
}
```

`MigrationLive`: the same holder shape as `MansartPoolsLive`, holding a `MigrationControl`. `control()` returns
`null` when nothing is published.

`MigrationLivePanel` (id `migration`):
- `sample` groups by `LiveOutcome.dataSource()` with the same values as today: `version`, `last-run`, `clean`, and
  `applied` / `pending` tables or absences;
- `actions()` returns the two actions over `control.dataSources()`, labels and confirmation text moved verbatim, and
  `List.of()` when there is no control or no datasource.

In the runtime, `outcomes()` maps each `Outcome` to a `LiveOutcome`, with
`cleanDisabledKey(o.dataSourceName())`.

Commit: `refactor(migration): the migration panel moves to a -dev module (#143)`.

---

### Task 12: MCP's panel moves to `vidocq-runtime-langchain4j-cdi-mcp-extension-dev`

Apply every step of Task 7 with these specifics:

| Item | Value |
|---|---|
| Runtime module | `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-langchain4j-cdi-mcp-extension` |
| Moves to `...mcp.live` (public) | `McpInspection` (record, `of(BeanManager)`, `NOTHING`) |
| Stays in the runtime | `McpExtension` (minus `charts`, `sample` and the `live` field), `McpConfigMapping`, `McpServerConfigProducer`, `McpStartupSection`, `McpChecks` |
| Moves to the `-dev` module | `McpLiveBeans` (with `NONE`, `of(McpInspection, BeanManager)`, `sample(PanelSample)`), the `CHARTS` list |
| Live panel | `McpLivePanel`, id `mcp`. `start(context)`: `live = McpLiveBeans.of(McpInspection.of(context.beanManager()), context.beanManager())`. `stop()`: `live = McpLiveBeans.NONE`. `sample(s)`: `live.sample(s)`. `charts()` returns the moved `CHARTS`. |
| Runtime `module-info` | drop the devconsole `requires transitive`; keep the `opens` for `McpServerConfigProducer`; add `exports io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live to io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;` |
| `-dev` `module-info` | `requires io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp; requires io.vidocq.runtime.spi.devconsole; requires jakarta.cdi; requires dev.langchain4j.cdi.mcp.server;` (what `McpLiveBeans` imports) and the `provides` |
| `-dev` pom | same release properties as the runtime (`maven.deploy.skip`, `vidocq.checkpom.skip`, `skipPublishing`, …: copy them) while langchain4j-cdi is a SNAPSHOT |
| Tests | `McpInspectionTest` goes to `.live`; `McpPanelTest` goes to `-dev` as `McpLivePanelTest`, with its fixtures (`FixtureMcpBeans`, `McpTestContainers`, `FakeExtensionContext`, `RecordingSample`) copied; the startup-section tests stay; add `McpSectionWithoutPanelTest` |
| Parent | `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/pom.xml` |

This is the module the MCP inspector will be built in, in its own spec.

Commit: `refactor(mcp): the mcp panel moves to a -dev module (#143)`.

---

### Task 13: Mark the dev services, update the consumers, prove it end to end

**Files:**
- Modify:
  - `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/pom.xml`: manifest marking;
  - `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/pom.xml`: the console moves to `test` scope, together
    with `vidocq-runtime-mansart-pool-extension-dev` at `test` scope for `DevConsoleSnapshotTest`;
  - `vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing/src/it/fixture/pom.xml`: remove the console
    dependency;
  - `vidocq-runtime-integration-tests/vidocq-runtime-it-langchain4j-cdi-mcp/pom.xml`: the console moves from `runtime`
    to `test` scope, with the `-dev` module of MCP at `test` scope;
  - `vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/pom.xml`: the console moves to `test` scope;
  - `vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing/pom.xml`: `invoker:install` must stage
    the plugin's new dependency, which it does through the plugin's own tree; check the staged `it-repo` after a run.
- Create:
  - `vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing/src/test/java/io/vidocq/runtime/it/continuoustesting/DevToolsPackagingIT.java`;
  - no separate companion IT (Ruling 7): the companion path is covered by `DevModulesTest` (descriptor, resolution,
    never twice, skipped), by the Mansart example's `DevConsoleSnapshotTest` (the live `mansart-pool` panel matched
    from its `-dev` module), and by the manual `vidocq:dev` check on the Mansart example after the last task.

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Write the failing ITs**

In `ContinuousTestingDevGoalIT`, the existing test already asserts the `tests` panel under `vidocq:dev`. Once the
fixture no longer declares the console, it fails until Task 4's `DevConsoleJars` adds it. Add one assertion: the log
contains `Dev tools:`.

`DevToolsPackagingIT` (same helpers as `Fixture`):

```java
    @Test
    void aDeclaredConsoleIsNeverPackaged(@TempDir Path dir) throws Exception {
        Path project = Fixture.copy(dir);
        Fixture.addDependency(project, "io.vidocq.runtime.extensions.essentials",
                "vidocq-runtime-devconsole-extension", "compile");
        Path log = Fixture.logs().resolve("package.log");

        Process mvn = Fixture.start(project, log, "package", "vidocq:package", "-DskipTests");
        assertEquals(0, mvn.waitFor(), Fixture.read(log));

        assertTrue(Fixture.read(log).contains("vidocq-runtime-devconsole-extension is dev-only: not packaged"));
        try (var zip = new java.util.zip.ZipFile(Fixture.distZip(project).toFile())) {
            assertTrue(zip.stream().noneMatch(e -> e.getName().contains("devconsole")), "no dev console in the zip");
        }
    }
```

Add `Fixture.addDependency(project, groupId, artifactId, scope)`, which inserts a `<dependency>` before
`</dependencies>` of the copied `pom.xml`. Add `Fixture.distZip(project)`, which finds `target/*-dist.zip`.

In the unit tests of the plugin and extensions, add the JPMS check of the spec (§10): a module-path boot of a runtime
extension alone, with no companion, starts cleanly. `KnockSectionWithoutPanelTest` already loads the runtime module
with no `-dev` module. Confirm that its module-path test run passes with the qualified export to an absent module.
If `javac` rejects `exports … to <absent module>` under this build's lint flags, record a ruling and add
`-Xlint:-module` for that warning only in the runtime modules.

- [ ] **Step 2: Run them to see them fail**

Run: `mvn -o install -DskipTests` at the root, then
`mvn -o -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing verify`.
Expected: `DevToolsPackagingIT` fails until the fixture and the plugin changes are in, then passes. If Tasks 3-4 are
already in, both pass at once. The failing-first proof is then the unit tests of those tasks: say so in the ledger.

- [ ] **Step 3: Update the poms** as listed in *Files*, and add the marking to the dev services extension.

- [ ] **Step 4: Run the full build and the ITs**

Run:
1. `mvn -o install` at the root: every unit test;
2. `mvn -o -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-continuous-testing verify`;
3. `mvn -o -pl vidocq-runtime-examples/vidocq-runtime-mansart-h2-example test`: `DevConsoleSnapshotTest` still sees
   the console and the live `mansart-pool` panel at `test` scope.

Expected: all green, and no process left (`pgrep -fl 'vidocq:dev|continuous-testing-fixture'`).

- [ ] **Step 5: Commit** — `test(it): dev tools under vidocq:dev only, never packaged (#143)`.

---

### Task 14: Documentation

**Files:**
- Modify:
  - `docs/en/modules/ROOT/pages/dev-console.adoc`: *Getting it*, lines 7-40, and the anomalies after `#vidocq-devc-006`;
  - `docs/en/modules/ROOT/pages/dev-console-panels.adoc`: *Where the panel lives*, lines 61-183, and the Mansart
    worked example, lines 391-609;
  - `docs/en/modules/ROOT/pages/modules/vidocq-runtime-maven-plugin.adoc`: the Goals table rows for `dev`, `package`
    and `checkpom`, a new `[#dev-tools]` section, and `=== What it generates` under `vidocq:idea`;
  - `docs/en/modules/ROOT/pages/migration.adoc`: a new section before `== Next steps`;
  - `docs/en/modules/ROOT/pages/whats-new.adoc`;
  - `CLAUDE.md`: the plugin goals, and the extension model.

- [ ] **Step 1: Write the pages**

- `dev-console.adoc`, *Getting it*: rewrite it to say what applies now.
  - Nothing to declare: `mvn vidocq:dev` adds the console when the application has
    `vidocq-runtime-chappe-webserver-extension`, and it adds each extension's `-dev` panel module.
  - Tests that read the console declare it, and the `-dev` modules they need, at `<scope>test</scope>`.
  - `vidocq:run`, `package`, `jlink`, `jpackage` and `docker` never contain it. A declared console is dropped with
    the warning of §6.
  - Tag it `[.tag-new]#NEW#`.
- `dev-console.adoc`, anomalies: add `=== VIDOCQ-DEVC-007`, `-008` and `-009`, in the format of
  `#vidocq-devc-005`, each with its anchor and its meaning (spec §3).
- `dev-console-panels.adoc`: a new `[#dev-module]` section, *A `-dev` module, never packaged* `[.tag-new]#NEW#`,
  before *Where the panel lives*. It covers:
  - the three families of spec §2;
  - `LivePanel`, with its Javadoc's rules;
  - the descriptor;
  - the qualified export of a `.live` package, or recomputing from the `ExtensionContext`;
  - the manifest marking, with the POM block of the File structure section.

  Rewrite the Mansart worked example on `MansartPoolsLive` and `PoolsLivePanel`. Say that the all-in-one
  `DevConsolePanel` still works and ships in the binary.
- Plugin page:
  - the `vidocq:dev` row mentions the dev tools, and the `vidocq:package` and `vidocq:checkpom` rows mention the
    dev-only jar;
  - a new `[#dev-tools]` section, *Dev tools and binaries* `[.tag-new]#NEW#`, holds spec §5-§6 in prose: the INFO
    line, the Chappe condition, the never-twice rule, the warning text, the `vidocq:run` dev services exception, and
    the test-scope rule;
  - under `vidocq:idea`, `=== What it generates` lists the three files, with a note that the Application kind has no
    console.
- `migration.adoc`: a `[#dev-console-dependency]` section, *The dev console is no longer a dependency*
  `[.tag-new]#NEW#`. It says: remove `vidocq-runtime-devconsole-extension`, or move it to `test` scope for tests
  that read it.
- `whats-new.adoc`: one bullet, after the continuous-testing ones, linking `dev-console.adoc#getting-it` and the
  plugin page's `#dev-tools`.
- `CLAUDE.md`: in *Maven plugin goals*, say that `dev` adds the console and the `-dev` modules, and that the
  packaging goals drop dev-only jars. In *Extension model*, add one sentence on the `-dev` companion module.

- [ ] **Step 2: Check** — `./check-doc-versions.sh`, then grep that every new `xref:` and `<<…>>` anchor exists.

- [ ] **Step 3: Commit** — `docs: dev tools under vidocq:dev only, the -dev panel modules (#143)`.

---

## After the last task

- Run the full check: `mvn -o install` at the root, then the continuous-testing ITs with `-Pit`, and the `idea-*`
  invoker ITs with `-Pit -pl vidocq-runtime-maven-plugin verify`.
- Check by hand, on the Mansart H2 example:
  - `mvn vidocq:dev -Dvidocq.devconsole.port=18094 -Dvidocq.dev.debug=false`: the console is there, the
    `mansart-pool` panel is live, and the INFO line lists the `-dev` modules;
  - `mvn package vidocq:package -DskipTests`: the zip holds no `devconsole` jar, no `-dev` jar and no devconsole SPI.
- Then finish with superpowers:finishing-a-development-branch; open the PR on the user's go.
- Then `lc4jcdi-on-vidocq` (VidocqTools) drops its console dependency, or moves it to `test` scope, in a pull
  request of its own (spec §9).
- Then the MCP inspector gets its own spec (spec §11).
