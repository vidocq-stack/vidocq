# Servlet extension (Foy on Chappe) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Vidocq runtime extension that serves an application's Jakarta Servlet 6.1 components through Foy on the shared Chappe web server, with working request and session scopes, next to Cassini.

**Architecture:** `FoyExtension` (a `VidocqExtension`) deploys the application with `FoyChappeBoot` and mounts the returned handler on `ChappeMountPoint`; `FoyMountHandlerProvider` does the same for a declarative `type=servlet` mount. `ChappeMountPoint` is changed to route longest prefix first and to reject two mounts on one prefix, so a Foy mount on `/` never shadows the others. A pom-only `-codegen` bundle puts Foy's processor and build-compatible extensions on the processor path.

**Tech Stack:** Java 25, JPMS, Maven 3.9.16 (`./mvnw`), JUnit 5, Foy 0.4.0-SNAPSHOT (`foy-chappe`, `foy-cdi-vauban`, `foy-processor`), Vauban 0.4.0-SNAPSHOT (`vauban-webcontexts`), Chappe.

**Spec:** `docs/superpowers/specs/2026-10-11-servlet-extension-design.md`

## Global Constraints

- Every artifact in English: code, Javadoc, comments, commit messages, docs.
- Commits: Conventional Commits, `git commit -s -S`, trailers `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY`.
- No change to the Foy repository.
- Zero external dependency at runtime: only Vidocq bricks and Jakarta APIs. Run the `dependency-gatekeeper` agent on every pom change.
- JPMS strict: each new module has a `module-info.java`, minimal `exports`, no `opens`.
- TDD: each test is written and seen failing before its code.
- Version strings: never hard-code `0.4.0-SNAPSHOT` in a test (CI rewrites versions).
- Configuration keys exactly: `vidocq.servlet.context-path`, `vidocq.servlet.listener`, `vidocq.servlet.session-timeout`; declarative type `servlet`.
- Work only in the worktree `/private/tmp/claude-501/-Users-yblazart-projects-perso-vidocq/d7ea8f44-fd18-4b7c-9213-1d2b758c463b/scratchpad/wt-vidocq-servlet` (branch `feat/servlet-extension`); never in the main vidocq checkout.

## Review Focus

1. **`/` versus `""` prefixes.** Cassini mounts the root as `""`, a declarative mount may say `/`: both are the same prefix and must collide (Task 1 test `rootSpelledTwoWaysCollides`).
2. **A servlet mount on `/` registered before `/api`.** The longer prefix must still win whatever the order (Task 1 test `longerPrefixWinsWhateverTheRegistrationOrder`).
3. **Hot reload.** `onStop` must close every Foy deployment, auto and declarative, so `contextDestroyed` runs and the next boot starts clean (Task 3 test `stopClosesTheDeployment`, Task 4 test `stopClosesDeclarativeDeployments`).
4. **Declarative `type=servlet` on an application without web component.** Must fail with a clear message, not mount a dead handler (Task 4 test `declarativeMountWithoutComponentsFails`).
5. **Session timeout given as a bad number.** `vidocq.servlet.session-timeout=abc` must fail at configure time with the key in the message (Task 2 test `badSessionTimeoutNamesTheKey`).

---

## File map

Shared web server (`vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-chappe-webserver-extension/`):
- Modify `src/main/java/io/vidocq/runtime/extensions/essentials/chappe/ChappeMountPoint.java` — pending mounts per listener, duplicate check, longest-prefix application in `buildRouter`.
- Modify `src/test/java/io/vidocq/runtime/extensions/essentials/chappe/ChappeMountPointTest.java`, `ChappeServerEndToEndTest.java`.

New extension (`vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension/`), package `io.vidocq.runtime.extensions.jakartaee.web.foy`:
- `FoyExtension.java` — lifecycle, configuration, startup report section.
- `ServletSettings.java` — the three configuration keys, parsed.
- `FoyDeployments.java` — deploys with `FoyChappeBoot`, records live deployments, closes them.
- `MountTarget.java` — seam over `ChappeMountPoint.mount`, for tests.
- `FoyMountHandlerProvider.java` — `type=servlet`.
- `module-info.java`, `META-INF/services/*`, `pom.xml`, `README.md`.

New codegen bundle: `vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension-codegen/pom.xml`.

New IT: `vidocq-runtime-integration-tests/vidocq-runtime-it-foy-servlet/`.

Modify: root `pom.xml` (version property, `dependencyManagement`), `vidocq-runtime-extensions-jakartaee-web/pom.xml` (modules), `vidocq-runtime-integration-tests/pom.xml` (module), docs (Task 7).

---

### Task 1: Longest-prefix routing and duplicate-prefix rejection in `ChappeMountPoint`

**Files:**
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-chappe-webserver-extension/src/main/java/io/vidocq/runtime/extensions/essentials/chappe/ChappeMountPoint.java`
- Test: `.../src/test/java/io/vidocq/runtime/extensions/essentials/chappe/ChappeMountPointTest.java`, `.../ChappeServerEndToEndTest.java`

**Interfaces:**
- Produces: unchanged public API (`mount(String, Handler)`, `mount(String, String, Handler)`, `mount(String, String, Handler, boolean)`); new behaviour: `IllegalStateException` on a duplicate prefix; package-private `List<String> mountPrefixes(String listenerName)` returning prefixes in routing order (longest first), for tests.

- [ ] **Step 1: Write the failing unit tests** (append to `ChappeMountPointTest`)

```java
    @Test
    void mountsAreRoutedLongestPrefixFirst() {
        mp.mount(ChappeListener.DEFAULT, "", _ -> Response.ok("root"));
        mp.mount(ChappeListener.DEFAULT, "/api", _ -> Response.ok("api"));
        mp.mount(ChappeListener.DEFAULT, "/api/v2", _ -> Response.ok("v2"));

        assertEquals(List.of("/api/v2", "/api", ""), mp.mountPrefixes(ChappeListener.DEFAULT));
    }

    @Test
    void samePrefixTwiceOnOneListenerFails() {
        mp.mount(ChappeListener.DEFAULT, "/api", _ -> Response.ok("a"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mp.mount(ChappeListener.DEFAULT, "/api", _ -> Response.ok("b")));
        assertTrue(e.getMessage().contains("'/api'"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.servlet.context-path"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.rest.context-path"), e.getMessage());
    }

    @Test
    void rootSpelledTwoWaysCollides() {
        mp.mount(ChappeListener.DEFAULT, "", _ -> Response.ok("a"));

        assertThrows(IllegalStateException.class,
                () -> mp.mount(ChappeListener.DEFAULT, "/", _ -> Response.ok("b")));
    }

    @Test
    void samePrefixOnTwoListenersIsFine() {
        mp.mount("admin", "", _ -> Response.ok("a"));
        mp.mount(ChappeListener.DEFAULT, "", _ -> Response.ok("d"));

        assertEquals(List.of(""), mp.mountPrefixes("admin"));
        assertEquals(List.of(""), mp.mountPrefixes(ChappeListener.DEFAULT));
    }
```

Add `import java.util.List;` if missing.

- [ ] **Step 2: Write the failing end-to-end test** (append to `ChappeServerEndToEndTest`)

```java
    @Test
    void longerPrefixWinsWhateverTheRegistrationOrder() throws Exception {
        ChappeMountPoint mp = ChappeMountPoint.instance();
        mp.mount(ChappeListener.DEFAULT, "", _ -> Response.ok("servlet"));
        mp.mount(ChappeListener.DEFAULT, "/api", _ -> Response.ok("rest"));

        var ctx = new FakeExtensionContext(TestConfig.of(Map.of(
                "vidocq.chappe.listener.default.port", Integer.toString(port))));
        bootstrap.onStart(ctx);

        assertEquals("rest", get("http://127.0.0.1:" + port + "/api/items").body());
        assertEquals("servlet", get("http://127.0.0.1:" + port + "/hello").body());
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run (from the worktree): `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-chappe-webserver-extension test -Dtest='ChappeMountPointTest,ChappeServerEndToEndTest'`
Expected: compilation failure on `mountPrefixes`; after a stub, `longerPrefixWinsWhateverTheRegistrationOrder` gets `servlet` for `/api/items` and the duplicate tests do not throw.

- [ ] **Step 4: Implement**

In `ChappeMountPoint`, add the field and record next to `routers`:

```java
    /** Mounts by listener, applied to the router longest prefix first when the server starts. */
    private final Map<String, List<PendingMount>> mounts = new LinkedHashMap<>();

    private record PendingMount(String prefix, Handler handler, boolean stripPrefix, String owner) {}
```

Replace the body of `mount(String listenerName, String prefix, Handler handler, boolean stripPrefix)`:

```java
    public void mount(String listenerName, String prefix, Handler handler, boolean stripPrefix) {
        Objects.requireNonNull(listenerName, "listenerName");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(handler, "handler");
        ensureOpen();
        String owner = caller();
        List<PendingMount> listenerMounts = mounts.computeIfAbsent(listenerName, n -> new ArrayList<>());
        for (PendingMount existing : listenerMounts) {
            if (routingKey(existing.prefix()).equals(routingKey(prefix))) {
                throw new IllegalStateException("listener '" + listenerName + "': prefix '"
                        + (routingKey(prefix).isEmpty() ? "/" : prefix) + "' is mounted twice, by "
                        + existing.owner() + " and by " + owner + "; move one of them "
                        + "(vidocq.rest.context-path, vidocq.servlet.context-path, vidocq.http.mount.<n>.path)");
            }
        }
        listenerMounts.add(new PendingMount(prefix, handler, stripPrefix, owner));
        routers.computeIfAbsent(listenerName, n -> Router.builder());
    }

    /** The root is spelled {@code ""} or {@code "/"}; a trailing slash never changes the prefix. */
    private static String routingKey(String prefix) {
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /** The first class outside this one on the stack: the extension that mounted. */
    private static String caller() {
        return CALLER.walk(frames -> frames.map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != ChappeMountPoint.class)
                .findFirst().map(Class::getName).orElse("?"));
    }

    /** Prefixes of a listener's mounts, in routing order (longest first). */
    List<String> mountPrefixes(String listenerName) {
        return ordered(listenerName).stream().map(PendingMount::prefix).toList();
    }

    private List<PendingMount> ordered(String listenerName) {
        List<PendingMount> list = new ArrayList<>(mounts.getOrDefault(listenerName, List.of()));
        // Chappe's router takes the first matching mount: the longest prefix must come first. Stable sort.
        list.sort(Comparator.comparingInt((PendingMount m) -> routingKey(m.prefix()).length()).reversed());
        return list;
    }
```

Replace `buildRouter`:

```java
    Router buildRouter(String listenerName) {
        Router.Builder b = routers.computeIfAbsent(listenerName, n -> Router.builder());
        // Applied once: the pending mounts move into the router.
        List<PendingMount> pending = ordered(listenerName);
        mounts.remove(listenerName);
        for (PendingMount m : pending) {
            b.mount(m.prefix(), m.handler(), m.stripPrefix());
        }
        return b.build();
    }
```

Add `import java.util.Comparator;`. Check that `CALLER` is declared before use (it already is).

- [ ] **Step 5: Run the module's whole test suite**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-chappe-webserver-extension test`
Expected: BUILD SUCCESS, no failure (the existing tests keep passing).

- [ ] **Step 6: Check that no in-repo caller mounts the same prefix twice**

Run: `./mvnw -o -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-cassini-rest,vidocq-runtime-integration-tests/vidocq-runtime-it-grimm-openapi -am install -DskipTests=false -Dtest='*' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-chappe-webserver-extension
git commit -s -S -m "fix(http): route mounts longest prefix first and reject a duplicate prefix" -m "Chappe's router takes the first matching mount in registration order, so a mount on / shadowed every later one, and a second mount on a prefix was silently unreachable." -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 2: Module skeleton, versions and configuration

**Files:**
- Modify: root `pom.xml` (properties near `cassini.version`; `dependencyManagement`)
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/pom.xml`
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension/pom.xml`
- Create: `.../src/main/java/module-info.java`
- Create: `.../src/main/java/io/vidocq/runtime/extensions/jakartaee/web/foy/ServletSettings.java`
- Create: `.../src/main/java/io/vidocq/runtime/extensions/jakartaee/web/foy/FoyExtension.java`
- Create: `.../src/main/resources/META-INF/services/io.vidocq.runtime.spi.VidocqExtension`
- Test: `.../src/test/java/io/vidocq/runtime/extensions/jakartaee/web/foy/ServletSettingsTest.java`, `MapConfiguration.java`

**Interfaces:**
- Produces: `record ServletSettings(String contextPath /* null = Foy decides */, String listener, int sessionTimeoutSeconds)` with `static ServletSettings from(VidocqConfiguration config)` and constants `CONTEXT_PATH`, `LISTENER`, `SESSION_TIMEOUT`; `FoyExtension` with `name()` = `"servlet-foy"`, `priority()` = `600`, `configKeys()`.

- [ ] **Step 1: Root pom**

In the `<properties>` of the root `pom.xml`, next to `<cassini.version>`, add `<foy.version>0.4.0-SNAPSHOT</foy.version>`. In `<dependencyManagement>`, next to the Cassini entries, add:

```xml
            <!-- Foy: Jakarta Servlet 6.1 on Chappe (Servlet extension) -->
            <dependency>
                <groupId>io.vidocq.foy</groupId>
                <artifactId>foy-api</artifactId>
                <version>${foy.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.foy</groupId>
                <artifactId>foy-core</artifactId>
                <version>${foy.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.foy</groupId>
                <artifactId>foy-chappe</artifactId>
                <version>${foy.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.foy</groupId>
                <artifactId>foy-cdi-vauban</artifactId>
                <version>${foy.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.foy</groupId>
                <artifactId>foy-processor</artifactId>
                <version>${foy.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.vauban</groupId>
                <artifactId>vauban-webcontexts</artifactId>
                <version>${vauban.version}</version>
            </dependency>
```

If `jakarta.servlet:jakarta.servlet-api` is not yet managed (`grep -n servlet-api pom.xml`), add it with version `6.1.0`. Also check that the release tooling that bumps `*.version` properties (`grep -rn "cassini.version" --include=*.sh --include=*.js --include=*.yml .`) lists `foy.version` too, and add it where it lists `cassini.version`.

- [ ] **Step 2: Module pom** — `vidocq-runtime-foy-servlet-extension/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
        <artifactId>vidocq-runtime-extensions-jakartaee-web</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </parent>

    <artifactId>vidocq-runtime-foy-servlet-extension</artifactId>
    <name>Vidocq :: Web Extensions :: Servlet (Foy)</name>
    <description>Vidocq Runtime extension that serves the application's Jakarta Servlet 6.1 components
        through Foy on the shared Chappe web server, with the CDI request and session scopes</description>

    <dependencies>
        <!-- Foy on Chappe: FoyChappeBoot deploys the application and returns a mountable handler. -->
        <dependency>
            <groupId>io.vidocq.foy</groupId>
            <artifactId>foy-chappe</artifactId>
        </dependency>
        <!-- Runtime only: Foy's CDI adapter for Vauban (web components as beans, session context
             through vauban-webcontexts), found by ServiceLoader. -->
        <dependency>
            <groupId>io.vidocq.foy</groupId>
            <artifactId>foy-cdi-vauban</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-spi</artifactId>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-chappe-webserver-extension</artifactId>
        </dependency>
        <dependency>
            <groupId>io.vidocq.chappe</groupId>
            <artifactId>chappe-api</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.enterprise</groupId>
            <artifactId>jakarta.enterprise.cdi-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!-- The unit tests deploy test servlets through FoyChappeBoot, which instantiates them from
                 another module: they run on a class path. -->
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

Add `<module>vidocq-runtime-foy-servlet-extension</module>` to `vidocq-runtime-extensions-jakartaee-web/pom.xml`.

- [ ] **Step 3: Write the failing test** — `ServletSettingsTest.java` and its helper `MapConfiguration.java`

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.spi.VidocqConfiguration;

import java.util.Map;
import java.util.Optional;

/** A {@link VidocqConfiguration} over a map. */
record MapConfiguration(Map<String, String> values) implements VidocqConfiguration {
    @Override
    public Optional<String> property(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public Iterable<String> propertyNames() {
        return values.keySet();
    }
}
```

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.extensions.essentials.chappe.ChappeListener;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServletSettingsTest {

    @Test
    void defaultsLetFoyPickTheContextPath() {
        ServletSettings s = ServletSettings.from(new MapConfiguration(Map.of()));

        assertNull(s.contextPath());
        assertEquals(ChappeListener.DEFAULT, s.listener());
        assertEquals(30 * 60, s.sessionTimeoutSeconds());
    }

    @Test
    void readsTheThreeKeys() {
        ServletSettings s = ServletSettings.from(new MapConfiguration(Map.of(
                "vidocq.servlet.context-path", "/app",
                "vidocq.servlet.listener", "admin",
                "vidocq.servlet.session-timeout", "120")));

        assertEquals("/app", s.contextPath());
        assertEquals("admin", s.listener());
        assertEquals(120, s.sessionTimeoutSeconds());
    }

    @Test
    void badSessionTimeoutNamesTheKey() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ServletSettings.from(new MapConfiguration(Map.of("vidocq.servlet.session-timeout", "abc"))));
        assertTrue(e.getMessage().contains("vidocq.servlet.session-timeout"), e.getMessage());
    }

    @Test
    void theExtensionDeclaresItsKeys() {
        assertEquals(java.util.Set.of("vidocq.servlet.context-path", "vidocq.servlet.listener",
                "vidocq.servlet.session-timeout"), new FoyExtension().configKeys());
        assertEquals("servlet-foy", new FoyExtension().name());
        assertEquals(600, new FoyExtension().priority());
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension -am install -DskipTests && ./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension test`
Expected: compilation errors (`ServletSettings`, `FoyExtension` missing). If the first command fails resolving `io.vidocq.foy:*:0.4.0-SNAPSHOT` offline, run it once without `-o`.

- [ ] **Step 5: Implement**

`ServletSettings.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.extensions.essentials.chappe.ChappeListener;
import io.vidocq.runtime.spi.VidocqConfiguration;

/**
 * The Servlet extension's configuration.
 *
 * @param contextPath           {@code vidocq.servlet.context-path}; {@code null} lets Foy use the
 *                              {@code web.xml} {@code <default-context-path>}, else {@code /}
 * @param listener              {@code vidocq.servlet.listener}: the Chappe listener of the mount
 * @param sessionTimeoutSeconds {@code vidocq.servlet.session-timeout}: used when no descriptor
 *                              declares a {@code <session-timeout>}
 */
record ServletSettings(String contextPath, String listener, int sessionTimeoutSeconds) {

    static final String CONTEXT_PATH = "vidocq.servlet.context-path";
    static final String LISTENER = "vidocq.servlet.listener";
    static final String SESSION_TIMEOUT = "vidocq.servlet.session-timeout";
    static final int DEFAULT_SESSION_TIMEOUT_SECONDS = 30 * 60;

    static ServletSettings from(VidocqConfiguration config) {
        String timeout = config.property(SESSION_TIMEOUT).orElse(null);
        int seconds;
        try {
            seconds = timeout == null ? DEFAULT_SESSION_TIMEOUT_SECONDS : Integer.parseInt(timeout.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(SESSION_TIMEOUT + " must be a number of seconds, got '"
                    + timeout + "'", e);
        }
        return new ServletSettings(config.property(CONTEXT_PATH).orElse(null),
                config.property(LISTENER, ChappeListener.DEFAULT), seconds);
    }
}
```

`FoyExtension.java` (first version; Task 3 adds `onStart`/`onStop`, Task 5 the report):

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;

import java.util.Set;

/**
 * Serves the application's Jakarta Servlet components through Foy on the shared Chappe web
 * server. Runs after the REST extension (500) and before the declarative mounts (7000) and the
 * server start (10000).
 */
public final class FoyExtension implements VidocqExtension {

    private ServletSettings settings;

    @Override
    public String name() {
        return "servlet-foy";
    }

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.settings = ServletSettings.from(config);
    }

    @Override
    public Set<String> configKeys() {
        return Set.of(ServletSettings.CONTEXT_PATH, ServletSettings.LISTENER, ServletSettings.SESSION_TIMEOUT);
    }
}
```

`module-info.java` (license header copied from `CassiniExtension`'s module-info):

```java
import io.vidocq.runtime.extensions.jakartaee.web.foy.FoyExtension;

module io.vidocq.runtime.extensions.jakartaee.web.foy {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.runtime.extensions.essentials.chappe;
    requires io.vidocq.foy.chappe;
    requires io.vidocq.chappe.api;
    requires jakarta.servlet;
    requires jakarta.cdi;

    exports io.vidocq.runtime.extensions.jakartaee.web.foy;

    provides io.vidocq.runtime.spi.VidocqExtension
            with FoyExtension;
}
```

`META-INF/services/io.vidocq.runtime.spi.VidocqExtension`: one line, `io.vidocq.runtime.extensions.jakartaee.web.foy.FoyExtension`.

- [ ] **Step 6: Run the tests**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension install`
Expected: BUILD SUCCESS, 4 tests pass.

- [ ] **Step 7: Dependency review** — run the `dependency-gatekeeper` agent on the root `pom.xml` diff and the new module pom; fix what it reports.

- [ ] **Step 8: Commit**

```bash
git add pom.xml vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web
git commit -s -S -m "feat(servlet): Servlet extension module and its configuration" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 3: Deploy and mount Foy on start, close on stop

**Files:**
- Create: `.../foy/MountTarget.java`, `.../foy/FoyDeployments.java`
- Modify: `.../foy/FoyExtension.java`
- Test: `.../foy/FoyExtensionTest.java`, `src/test/java/io/vidocq/runtime/extensions/jakartaee/web/foy/fixture/HelloServlet.java`, `.../fixture/LifecycleListener.java`

**Interfaces:**
- Consumes: `ServletSettings` (Task 2).
- Produces:
  - `@FunctionalInterface interface MountTarget { void mount(String listener, String prefix, Handler handler); static MountTarget chappe(); }`
  - `final class FoyDeployments` with `static Optional<FoyChappeBoot.Mounted> deploy(BeanManager bm, ClassLoader loader, String contextPath, int sessionTimeoutSeconds)`, `static void record(String name, String listener, FoyChappeBoot.Mounted mounted)`, `static List<Live> all()`, `static void closeAll()`, and `record Live(String name, String listener, FoyChappeBoot.Mounted mounted)`.
  - `FoyExtension(MountTarget target)` package-private constructor; package-private `void start(BeanManager bm, VidocqConfig config, ClassLoader loader)`.
  - Name of the auto mount: `FoyExtension.AUTO_MOUNT = "vidocq.servlet"`.

- [ ] **Step 1: Test fixtures**

`fixture/HelloServlet.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy.fixture;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class HelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write("hello");
    }
}
```

`fixture/LifecycleListener.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy.fixture;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import java.util.concurrent.atomic.AtomicInteger;

public class LifecycleListener implements ServletContextListener {
    public static final AtomicInteger DESTROYED = new AtomicInteger();

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        DESTROYED.incrementAndGet();
    }
}
```

- [ ] **Step 2: Write the failing test** — `FoyExtensionTest.java`

The test builds a class loader whose only extra resource is a `WEB-INF/web.xml` in a temp dir, so Foy finds exactly the fixtures it names; the "empty" case uses a temp dir without descriptor and turns pluggability discovery off implicitly by having nothing to find.

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.chappe.api.Handler;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FoyExtensionTest {

    record Mount(String listener, String prefix, Handler handler) {}

    private final List<Mount> mounts = new ArrayList<>();

    @AfterEach
    void forget() {
        FoyDeployments.closeAll();
    }

    @Test
    void noWebComponentMountsNothing(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of()), loaderWith(root, null));

        assertTrue(mounts.isEmpty());
        assertTrue(FoyDeployments.all().isEmpty());
    }

    @Test
    void mountsTheDeploymentOnTheConfiguredListenerAndPrefix(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of(
                "vidocq.servlet.context-path", "/app", "vidocq.servlet.listener", "admin"));

        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));

        assertEquals(1, mounts.size());
        assertEquals("admin", mounts.getFirst().listener());
        assertEquals("/app", mounts.getFirst().prefix());
        assertEquals(1, FoyDeployments.all().size());
    }

    @Test
    void rootContextPathMountsOnTheEmptyPrefix(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));

        assertEquals("", mounts.getFirst().prefix());
    }

    @Test
    void declarativeServletMountSkipsTheAutoMount(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());

        extension.start(null, config(Map.of("vidocq.http.mount.web.type", "servlet")), loaderWith(root, WEB_XML));

        assertTrue(mounts.isEmpty());
    }

    @Test
    void stopClosesTheDeployment(@TempDir Path root) throws Exception {
        FoyExtension extension = configured(Map.of());
        extension.start(null, config(Map.of()), loaderWith(root, WEB_XML));
        int before = LifecycleListener.DESTROYED.get();

        extension.onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
        assertTrue(FoyDeployments.all().isEmpty());
    }

    private static final String WEB_XML = """
            <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.1">
              <listener>
                <listener-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener</listener-class>
              </listener>
              <servlet>
                <servlet-name>hello</servlet-name>
                <servlet-class>io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.HelloServlet</servlet-class>
              </servlet>
              <servlet-mapping>
                <servlet-name>hello</servlet-name>
                <url-pattern>/hello</url-pattern>
              </servlet-mapping>
            </web-app>
            """;

    private FoyExtension configured(Map<String, String> values) {
        FoyExtension extension = new FoyExtension((l, p, h) -> mounts.add(new Mount(l, p, h)));
        extension.configure(new MapConfiguration(values));
        return extension;
    }

    /** A child of the test loader that adds {@code root}, holding {@code WEB-INF/web.xml} when given. */
    private static ClassLoader loaderWith(Path root, String webXml) throws Exception {
        if (webXml != null) {
            Files.createDirectories(root.resolve("WEB-INF"));
            Files.writeString(root.resolve("WEB-INF/web.xml"), webXml);
        }
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, FoyExtensionTest.class.getClassLoader());
    }

    private static VidocqConfig config(Map<String, String> values) {
        return new VidocqConfig() {
            @Override public Optional<String> getValue(String key) { return Optional.ofNullable(values.get(key)); }
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> getValue(String key, Class<T> type) { return (Optional<T>) getValue(key); }
            @Override public <T> List<T> getValues(String key, Class<T> elementType) { return List.of(); }
            @Override public Iterable<String> getPropertyNames() { return values.keySet(); }
            @Override public Iterable<ConfigSource> getConfigSources() { return List.of(); }
        };
    }
}
```

If `VidocqConfig` declares other abstract methods, implement them returning empty values (check with `git show HEAD:vidocq-runtime-spi/src/main/java/io/vidocq/runtime/spi/config/VidocqConfig.java`).

Note for the empty case: the test class path holds no `WEB-INF/web.xml`, `META-INF/web.xml`, `web-fragment.xml` or `ServletContainerInitializer` service. If Foy still finds one (from a dependency jar), assert instead that the mounted deployment declares no servlet, and say so in the commit message.

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension test -Dtest=FoyExtensionTest`
Expected: compilation errors (`FoyExtension(MountTarget)`, `start`, `FoyDeployments` missing).

- [ ] **Step 4: Implement**

`MountTarget.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.chappe.api.Handler;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint;

/** Where the extension mounts Foy: the shared web server, or a recorder in tests. */
@FunctionalInterface
interface MountTarget {

    void mount(String listener, String prefix, Handler handler);

    static MountTarget chappe() {
        return (listener, prefix, handler) -> ChappeMountPoint.instance().mount(listener, prefix, handler);
    }
}
```

`FoyDeployments.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.foy.chappe.FoyChappeBoot;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deploys the application with Foy and keeps the live deployments of this boot: the startup
 * report reads them, {@link FoyExtension#onStop()} closes them (an in-JVM hot reload then starts
 * from a fresh deployment on the new class loader).
 */
final class FoyDeployments {

    /** A deployment mounted under {@code name} ({@code vidocq.servlet}, or a declarative mount's name). */
    record Live(String name, String listener, FoyChappeBoot.Mounted mounted) {}

    private static final List<Live> LIVE = new ArrayList<>();

    private FoyDeployments() {}

    /**
     * @param contextPath {@code null}: the descriptors' {@code <default-context-path>}, else {@code /}
     * @return empty when the application has no servlet, filter, listener nor initializer
     */
    static Optional<FoyChappeBoot.Mounted> deploy(BeanManager beanManager, ClassLoader loader,
                                                  String contextPath, int sessionTimeoutSeconds) {
        FoyChappeBoot.Builder builder = FoyChappeBoot.builder()
                .beanManager(beanManager)
                .classLoader(loader)
                .sessionTimeoutSeconds(sessionTimeoutSeconds);
        if (contextPath != null) builder.contextPath(contextPath);
        try {
            return builder.build();
        } catch (ServletException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    static synchronized void record(String name, String listener, FoyChappeBoot.Mounted mounted) {
        LIVE.add(new Live(name, listener, mounted));
    }

    static synchronized List<Live> all() {
        return List.copyOf(LIVE);
    }

    /** Closes every deployment of this boot, the last mounted first. */
    static synchronized void closeAll() {
        RuntimeException failure = null;
        for (Live live : LIVE.reversed()) {
            try {
                live.mounted().close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        LIVE.clear();
        if (failure != null) throw failure;
    }
}
```

Complete `FoyExtension.java`:

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Optional;
import java.util.Set;

/**
 * Serves the application's Jakarta Servlet components through Foy on the shared Chappe web
 * server. Runs after the REST extension (500) and before the declarative mounts (7000) and the
 * server start (10000). Foy activates the CDI request context itself and, through
 * {@code foy-cdi-vauban}, the session context: the dispatch is not wrapped here.
 */
public final class FoyExtension implements VidocqExtension {

    static final String AUTO_MOUNT = "vidocq.servlet";
    private static final System.Logger LOG = System.getLogger(FoyExtension.class.getName());

    private final MountTarget target;
    private ServletSettings settings;

    public FoyExtension() {
        this(MountTarget.chappe());
    }

    FoyExtension(MountTarget target) {
        this.target = target;
    }

    @Override
    public String name() {
        return "servlet-foy";
    }

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.settings = ServletSettings.from(config);
    }

    @Override
    public Set<String> configKeys() {
        return Set.of(ServletSettings.CONTEXT_PATH, ServletSettings.LISTENER, ServletSettings.SESSION_TIMEOUT);
    }

    @Override
    public void onStart(ExtensionContext context) {
        start(context.beanManager(), context.config(), Thread.currentThread().getContextClassLoader());
    }

    void start(BeanManager beanManager, VidocqConfig config, ClassLoader loader) {
        if (hasDeclarativeServletMount(config)) {
            LOG.log(System.Logger.Level.INFO,
                    "A declarative vidocq.http.mount.<n>.type=servlet is configured, skipping the auto-mount");
            return;
        }
        Optional<FoyChappeBoot.Mounted> deployed = FoyDeployments.deploy(beanManager, loader,
                settings.contextPath(), settings.sessionTimeoutSeconds());
        if (deployed.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "No web component discovered, Foy Servlet extension inactive");
            return;
        }
        FoyChappeBoot.Mounted mounted = deployed.get();
        FoyDeployments.record(AUTO_MOUNT, settings.listener(), mounted);
        target.mount(settings.listener(), mounted.mountPrefix(), mounted.handler());
        LOG.log(System.Logger.Level.INFO, "Foy Servlet extension mounted on listener={0} prefix={1}",
                settings.listener(), mounted.mountPrefix().isEmpty() ? "/" : mounted.mountPrefix());
    }

    @Override
    public void onStop() {
        FoyDeployments.closeAll();
    }

    private static boolean hasDeclarativeServletMount(VidocqConfig config) {
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith("vidocq.http.mount.") || !key.endsWith(".type")) continue;
            if ("servlet".equals(config.getValue(key, String.class, ""))) return true;
        }
        return false;
    }
}
```

The deployment is recorded before mounting so that a mount failure (duplicate prefix, Task 1) still leaves it to `onStop`.

- [ ] **Step 5: Run the module's tests**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension install`
Expected: BUILD SUCCESS, 9 tests pass.

- [ ] **Step 6: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension
git commit -s -S -m "feat(servlet): deploy the application with Foy and mount it on the web server" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 4: Declarative `type=servlet` mount

**Files:**
- Create: `.../foy/FoyMountHandlerProvider.java`
- Create: `.../src/main/resources/META-INF/services/io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider`
- Modify: `.../src/main/java/module-info.java`
- Test: `.../foy/FoyMountHandlerProviderTest.java`

**Interfaces:**
- Consumes: `FoyDeployments.deploy`, `FoyDeployments.record`, `FoyDeployments.closeAll` (Task 3), `MountConfig(String name, String prefix, String listener, int priority, VidocqConfig config, ExtensionContext context)`.
- Produces: `FoyMountHandlerProvider` with `type()` = `"servlet"`, package-private `Handler create(MountConfig cfg, BeanManager beanManager, ClassLoader loader)`.

- [ ] **Step 1: Write the failing test**

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.jakartaee.web.foy.fixture.LifecycleListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FoyMountHandlerProviderTest {

    @AfterEach
    void forget() {
        FoyDeployments.closeAll();
    }

    @Test
    void servesTheServletType() {
        assertEquals("servlet", new FoyMountHandlerProvider().type());
    }

    @Test
    void deploysWithTheMountPrefixAsContextPath(@TempDir Path root) throws Exception {
        MountConfig cfg = new MountConfig("web", "/web", "default", 0, FoyExtensionTest.config(Map.of()), null);

        assertNotNull(new FoyMountHandlerProvider().create(cfg, null, loaderWith(root)));

        FoyDeployments.Live live = FoyDeployments.all().getFirst();
        assertEquals("web", live.name());
        assertEquals("/web", live.mounted().servletContext().getContextPath());
    }

    @Test
    void declarativeMountWithoutComponentsFails(@TempDir Path root) throws Exception {
        MountConfig cfg = new MountConfig("web", "/web", "default", 0, FoyExtensionTest.config(Map.of()), null);
        ClassLoader empty = new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new FoyMountHandlerProvider().create(cfg, null, empty));
        assertTrue(e.getMessage().contains("vidocq.http.mount.web.type=servlet"), e.getMessage());
    }

    @Test
    void stopClosesDeclarativeDeployments(@TempDir Path root) throws Exception {
        MountConfig cfg = new MountConfig("web", "/web", "default", 0, FoyExtensionTest.config(Map.of()), null);
        new FoyMountHandlerProvider().create(cfg, null, loaderWith(root));
        int before = LifecycleListener.DESTROYED.get();

        new FoyExtension((l, p, h) -> {}).onStop();

        assertEquals(before + 1, LifecycleListener.DESTROYED.get());
    }

    private static ClassLoader loaderWith(Path root) throws Exception {
        Files.createDirectories(root.resolve("WEB-INF"));
        Files.writeString(root.resolve("WEB-INF/web.xml"), FoyExtensionTest.WEB_XML);
        return new URLClassLoader(new URL[]{root.toUri().toURL()}, FoyMountHandlerProviderTest.class.getClassLoader());
    }
}
```

In `FoyExtensionTest`, change `WEB_XML` and `config(...)` from `private` to package-private (`static final String WEB_XML`, `static VidocqConfig config(...)`).

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension test -Dtest=FoyMountHandlerProviderTest`
Expected: compilation error, `FoyMountHandlerProvider` missing.

- [ ] **Step 3: Implement**

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.chappe.api.Handler;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * {@code vidocq.http.mount.<n>.type=servlet}: mounts the application's servlets on a declared
 * prefix and listener. The prefix is the context path; {@code vidocq.http.mount.<n>.session-timeout}
 * overrides {@code vidocq.servlet.session-timeout} for this mount.
 */
public final class FoyMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "servlet";
    }

    @Override
    public Handler create(MountConfig cfg) {
        return create(cfg, cfg.extensionContext().beanManager(), Thread.currentThread().getContextClassLoader());
    }

    Handler create(MountConfig cfg, BeanManager beanManager, ClassLoader loader) {
        int global = cfg.vidocqConfig().getValue(ServletSettings.SESSION_TIMEOUT, Integer.class)
                .orElse(ServletSettings.DEFAULT_SESSION_TIMEOUT_SECONDS);
        int timeout = cfg.property("session-timeout", Integer.class, global);
        String contextPath = cfg.prefix().isEmpty() || "/".equals(cfg.prefix()) ? "/" : cfg.prefix();
        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(beanManager, loader, contextPath, timeout)
                .orElseThrow(() -> new IllegalStateException("vidocq.http.mount." + cfg.name()
                        + ".type=servlet, but the application has no servlet, filter, listener, web.xml "
                        + "nor ServletContainerInitializer"));
        FoyDeployments.record(cfg.name(), cfg.listener(), mounted);
        return mounted.handler();
    }
}
```

Add to `module-info.java`:

```java
    provides io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider
            with io.vidocq.runtime.extensions.jakartaee.web.foy.FoyMountHandlerProvider;
```

Services file `META-INF/services/io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider`: `io.vidocq.runtime.extensions.jakartaee.web.foy.FoyMountHandlerProvider`.

If `cfg.vidocqConfig().getValue(key, Integer.class)` is not supported by the test `VidocqConfig` stub (it casts strings), parse it with `Integer.parseInt` on `getValue(key)` instead, in both the code and the stub, and keep the test.

- [ ] **Step 4: Run the module's tests**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension install`
Expected: BUILD SUCCESS, 13 tests pass.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension
git commit -s -S -m "feat(servlet): declarative type=servlet mount" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 5: Startup report section

**Files:**
- Modify: `.../foy/FoyExtension.java` (implements `StartupReportContributor`)
- Test: `.../foy/ServletSectionTest.java`, `.../foy/RecordingSection.java`

**Interfaces:**
- Consumes: `FoyDeployments.all()` (Task 3).
- Produces: section id `"servlet"`, title `"Servlet (Foy)"`. Summary format: `"<n> servlet(s), <m> filter(s) at <prefixes joined by ', '>"`, or `"inactive: no web component"`.

- [ ] **Step 1: Test helper** — `RecordingSection.java` implementing every `StartupReportSection` method: `summary` stores the text in a public field `summary`, `row` puts into a `public final Map<String, Object> rows = new LinkedHashMap<>()`, all others return `this`.

- [ ] **Step 2: Write the failing test**

```java
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServletSectionTest {

    @AfterEach
    void forget() {
        FoyDeployments.closeAll();
    }

    @Test
    void contributesTheServletSection() {
        FoyExtension extension = new FoyExtension((l, p, h) -> {});
        assertEquals("servlet", extension.id());
        assertEquals("Servlet (Foy)", extension.title());
    }

    @Test
    void inactiveWithoutDeployment() {
        RecordingSection section = new RecordingSection();

        new FoyExtension((l, p, h) -> {}).contribute(null, section);

        assertEquals("inactive: no web component", section.summary);
    }

    @Test
    void summarizesTheLiveDeployments(@TempDir Path root) throws Exception {
        FoyExtension extension = new FoyExtension((l, p, h) -> {});
        extension.configure(new MapConfiguration(Map.of("vidocq.servlet.session-timeout", "120")));
        extension.start(null, FoyExtensionTest.config(Map.of()), FoyExtensionTest.loaderWith(root, FoyExtensionTest.WEB_XML));
        RecordingSection section = new RecordingSection();

        extension.contribute(null, section);

        assertEquals("1 servlet(s), 0 filter(s) at /", section.summary);
        assertEquals("default", section.rows.get("Listener"));
        assertEquals("2 min", section.rows.get("Session timeout"));
    }
}
```

Make `FoyExtensionTest.loaderWith` package-private.

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension test -Dtest=ServletSectionTest`
Expected: compilation error, `id()`/`contribute` missing.

- [ ] **Step 4: Implement** — in `FoyExtension`, `implements VidocqExtension, StartupReportContributor`, and add:

```java
    @Override
    public String id() {
        return "servlet";
    }

    @Override
    public String title() {
        return "Servlet (Foy)";
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        List<FoyDeployments.Live> live = FoyDeployments.all();
        if (live.isEmpty()) {
            section.summary("inactive: no web component");
            return;
        }
        int servlets = 0;
        int filters = 0;
        List<String> prefixes = new ArrayList<>();
        for (FoyDeployments.Live l : live) {
            ServletContext sc = l.mounted().servletContext();
            servlets += sc.getServletRegistrations().size();
            filters += sc.getFilterRegistrations().size();
            prefixes.add(l.mounted().mountPrefix().isEmpty() ? "/" : l.mounted().mountPrefix());
        }
        section.summary(servlets + " servlet(s), " + filters + " filter(s) at " + String.join(", ", prefixes));
        FoyDeployments.Live first = live.getFirst();
        section.row("Listener", first.listener());
        section.row("Session timeout", first.mounted().servletContext().getSessionTimeout() + " min");
    }
```

Imports: `io.vidocq.runtime.spi.report.StartupReportContext`, `io.vidocq.runtime.spi.report.StartupReportContributor`, `io.vidocq.runtime.spi.report.StartupReportSection`, `jakarta.servlet.ServletContext`, `java.util.ArrayList`, `java.util.List`. Check how the runtime finds contributors (`git grep -n "StartupReportContributor" vidocq-runtime-core/src/main`): if it uses `instanceof` on extensions, nothing more; if it uses `ServiceLoader`, add `provides io.vidocq.runtime.spi.report.StartupReportContributor with FoyExtension` and its services file.

- [ ] **Step 5: Run the module's tests**

Run: `./mvnw -o -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension install`
Expected: BUILD SUCCESS, 16 tests pass.

- [ ] **Step 6: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension
git commit -s -S -m "feat(servlet): startup report section" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 6: Codegen bundle and module-path integration test

**Files:**
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension-codegen/pom.xml`
- Modify: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/pom.xml` (module)
- Create: `vidocq-runtime-integration-tests/vidocq-runtime-it-foy-servlet/pom.xml`
- Create: `.../src/main/java/module-info.java` and, in package `io.vidocq.runtime.it.foyservlet`: `Cart.java`, `CartServlet.java`, `RequestTicket.java`, `TicketServlet.java`, `PoweredByFilter.java`, `SessionEvents.java`, `SessionEventsServlet.java`, `ItemsApplication.java`, `ItemsResource.java`
- Create: `.../src/test/java/io/vidocq/runtime/it/foyservlet/FoyServletModulePathTest.java`
- Modify: `vidocq-runtime-integration-tests/pom.xml` (module)

**Interfaces:**
- Consumes: the extension (Tasks 2–5), the REST extension and its codegen bundle.
- Produces: artifact `vidocq-runtime-foy-servlet-extension-codegen` (type `pom`), used by applications in `<annotationProcessorPaths>`.

- [ ] **Step 1: Codegen bundle**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.vidocq.runtime.extensions.jakartaee.web</groupId>
        <artifactId>vidocq-runtime-extensions-jakartaee-web</artifactId>
        <version>0.4.0-SNAPSHOT</version>
    </parent>

    <artifactId>vidocq-runtime-foy-servlet-extension-codegen</artifactId>
    <packaging>pom</packaging>
    <name>Vidocq :: Web Extensions :: Servlet (Foy) :: Codegen</name>
    <description>Annotation processor path of the Servlet extension: foy-processor generates the web
        component factories; foy-cdi-vauban carries the build-compatible extensions (web components
        as beans, the session context through vauban-webcontexts) that Vauban's processor must run.</description>

    <dependencies>
        <dependency>
            <groupId>io.vidocq.foy</groupId>
            <artifactId>foy-processor</artifactId>
        </dependency>
        <dependency>
            <groupId>io.vidocq.foy</groupId>
            <artifactId>foy-cdi-vauban</artifactId>
        </dependency>
    </dependencies>
</project>
```

Copy any `<build>` section the Cassini codegen bundle has (for example a skipped deploy or publishing setting) so the two bundles are published the same way.

- [ ] **Step 2: IT pom** — copy `vidocq-runtime-integration-tests/vidocq-runtime-it-cyrano-jpms/pom.xml`, then:
  - artifactId `vidocq-runtime-it-foy-servlet`, name `Vidocq :: Integration Tests :: Servlet (Foy) on the module path`, a header comment explaining that the application is a named module and the runtime boots on the module path;
  - dependencies: `vidocq-runtime-core`, `vidocq-runtime-foy-servlet-extension`, `vidocq-runtime-cassini-rest-extension`, `jakarta.servlet-api`, `jakarta.ws.rs-api` (version `4.0.0` as in `it-cassini-rest`), `jakarta.enterprise.cdi-api`, `junit-jupiter` (test);
  - `annotationProcessorPaths` (append): `vidocq-runtime-foy-servlet-extension-codegen` and `vidocq-runtime-cassini-rest-extension-codegen`, both `<type>pom</type>`, version `${project.version}`;
  - test compile and surefire: replace `jdk.httpserver` with `java.net.http` in `--add-modules` and `--add-reads io.vidocq.runtime.it.foyservlet=java.net.http`;
  - keep the publishing skip of the original.

Add `<module>vidocq-runtime-it-foy-servlet</module>` to `vidocq-runtime-integration-tests/pom.xml`.

- [ ] **Step 3: Test application** (package `io.vidocq.runtime.it.foyservlet`, license header as in the other modules)

```java
@SessionScoped
public class Cart implements Serializable {
    private final List<String> items = new ArrayList<>();
    public void add(String item) { items.add(item); }
    public List<String> items() { return List.copyOf(items); }
}
```

```java
@WebServlet("/cart")
public class CartServlet extends HttpServlet {
    @Inject Cart cart;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String add = req.getParameter("add");
        if (add != null) cart.add(add);
        if (req.getParameter("invalidate") != null) {
            req.getSession().invalidate();
            resp.getWriter().write("invalidated");
            return;
        }
        resp.getWriter().write(String.join(",", cart.items()));
    }
}
```

```java
@RequestScoped
public class RequestTicket {
    private final String id = UUID.randomUUID().toString();
    public String id() { return id; }
}
```

```java
@WebServlet("/ticket")
public class TicketServlet extends HttpServlet {
    @Inject RequestTicket ticket;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // Read twice: the same request sees the same instance.
        String first = ticket.id();
        String second = ticket.id();
        resp.getWriter().write(first.equals(second) ? first : "MISMATCH");
    }
}
```

```java
@WebFilter("/*")
public class PoweredByFilter extends HttpFilter {
    @Override
    protected void doFilter(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        resp.setHeader("X-Powered-By", "foy");
        chain.doFilter(req, resp);
    }
}
```

```java
@ApplicationScoped
public class SessionEvents {
    private final AtomicInteger destroyed = new AtomicInteger();
    void onDestroyed(@Observes @Destroyed(SessionScoped.class) Object event) { destroyed.incrementAndGet(); }
    public int destroyed() { return destroyed.get(); }
}
```

```java
@WebServlet("/session-events")
public class SessionEventsServlet extends HttpServlet {
    @Inject SessionEvents events;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.getWriter().write(Integer.toString(events.destroyed()));
    }
}
```

```java
@ApplicationPath("/api")
public class ItemsApplication extends Application {}
```

```java
@Path("/items")
public class ItemsResource {
    @GET
    @Produces("text/plain")
    public String items() { return "rest"; }
}
```

- [ ] **Step 4: module-info, first version**

```java
module io.vidocq.runtime.it.foyservlet {
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.servlet;
    requires jakarta.ws.rs;
    requires io.vidocq.runtime.core;
    requires io.vidocq.runtime.extensions.jakartaee.web.foy;
    requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
    requires io.vidocq.vauban.api;

    exports io.vidocq.runtime.it.foyservlet;
}
```

Build once (`./mvnw -o -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-foy-servlet -am install -DskipTests`), then list `target/classes/META-INF/services/*`. For every services file the processors wrote, add the matching `provides <service> with <implementations>;` to the module-info (the test boots without the application layer that synthesizes them, as in `it-cyrano-jpms`). Expect at least `io.vidocq.vauban.api.VaubanComponentProvider` (`_VaubanComponents`) and `io.vidocq.foy.spi.gen.WebComponent` (the `$$FoyComponent` classes), plus Cassini's generated providers. Comment the block as `it-cyrano-jpms` does. If the Vauban processor warns that a build-compatible extension is not on the processor path, fix the codegen bundle (Step 1), not the IT.

- [ ] **Step 5: Write the test** — `FoyServletModulePathTest.java`

```java
package io.vidocq.runtime.it.foyservlet;

import io.vidocq.runtime.core.VidocqBootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class FoyServletModulePathTest {

    private static VidocqBootstrap bootstrap;
    private static String base;

    @BeforeAll
    static void boot() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        System.setProperty("vidocq.chappe.listener.default.host", "127.0.0.1");
        System.setProperty("vidocq.chappe.listener.default.port", Integer.toString(port));
        base = "http://127.0.0.1:" + port;
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) bootstrap.shutdown();
        System.clearProperty("vidocq.chappe.listener.default.host");
        System.clearProperty("vidocq.chappe.listener.default.port");
    }

    @Test
    void runsOnTheModulePath() {
        assertTrue(FoyServletModulePathTest.class.getModule().isNamed());
        assertTrue(ModuleLayer.boot().findModule("io.vidocq.foy.chappe").isPresent());
    }

    @Test
    void servletAnswersThroughTheFilter() throws Exception {
        HttpResponse<String> r = get(HttpClient.newHttpClient(), "/cart");
        assertEquals(200, r.statusCode());
        assertEquals("foy", r.headers().firstValue("X-Powered-By").orElse(null));
    }

    @Test
    void cartIsKeptPerSession() throws Exception {
        HttpClient alice = cookieClient();
        HttpClient bob = cookieClient();

        get(alice, "/cart?add=apple");
        get(bob, "/cart?add=pear");

        assertEquals("apple,banana", get(alice, "/cart?add=banana").body());
        assertEquals("pear", get(bob, "/cart").body());
    }

    @Test
    void invalidateDestroysTheSessionBean() throws Exception {
        HttpClient client = cookieClient();
        get(client, "/cart?add=apple");
        int before = Integer.parseInt(get(client, "/session-events").body());

        assertEquals("invalidated", get(client, "/cart?invalidate=1").body());

        assertEquals(before + 1, Integer.parseInt(get(client, "/session-events").body()));
        assertEquals("", get(client, "/cart").body());
    }

    @Test
    void eachConcurrentRequestHasItsOwnRequestScopedInstance() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<String>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> get(client, "/ticket").body());
            Set<String> ids = new HashSet<>();
            for (Future<String> f : pool.invokeAll(calls)) {
                String id = f.get();
                assertNotEquals("MISMATCH", id);
                ids.add(id);
            }
            assertEquals(8, ids.size());
        }
    }

    @Test
    void restAndServletShareThePort() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        assertEquals("rest", get(client, "/api/items").body());
        assertEquals(200, get(client, "/cart").statusCode());
    }

    private static HttpClient cookieClient() {
        return HttpClient.newBuilder().cookieHandler(new java.net.CookieManager()).build();
    }

    private static HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
```

Check the package of `VidocqBootstrap` with `git grep -n "class VidocqBootstrap" vidocq-runtime-core` and fix the import if it differs. If the runtime reads the port from another key, copy the keys `VidocqEmbeddedContainer` sets (`vidocq-runtime-integration-tests/vidocq-runtime-arquillian/.../VidocqEmbeddedContainer.java` lines 136–140).

- [ ] **Step 6: Run the IT**

Run: `./mvnw -o -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-foy-servlet -am install`
Expected: BUILD SUCCESS, 6 tests pass. A failure here is a real integration bug: diagnose it (module-info `provides`, processor path, class loader), and if it lies in Foy, stop and report it rather than editing the Foy repository.

- [ ] **Step 7: Order sensitivity** — run the IT once more with `-Dsurefire.runOrder=reversealphabetical`. Expected: same result.

- [ ] **Step 8: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web vidocq-runtime-integration-tests
git commit -s -S -m "test(servlet): Servlet extension on the module path, next to REST" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 7: Documentation

**Files:**
- Modify: `docs/en/modules/ROOT/pages/modules/vidocq-runtime-extensions.adoc` (line with "Planned: a Jakarta Servlet 6.1 extension")
- Modify: `README.md` (line with "Brick only: no extension in this repository" for Servlet)
- Modify: `WEB-PROFILE.md` (Servlet row)
- Modify: the runtime's What's new page (`git grep -ln "What's new" docs/en`), and the page documenting `vidocq.http.mount.*` (`git grep -ln "vidocq.http.mount" docs/en`)
- Create: `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension/README.md`

- [ ] **Step 1: Extensions page** — replace the "Planned" sentence with a section `=== Servlet (Foy) [.tag-new]#NEW#` holding:
  - the two dependencies (`io.vidocq.runtime.extensions.jakartaee.web:vidocq-runtime-foy-servlet-extension`, and `vidocq-runtime-foy-servlet-extension-codegen` of type `pom` in `<annotationProcessorPaths>`), as XML snippets;
  - the configuration table from the spec;
  - "Request and session scopes": Foy activates them; `@SessionScoped` beans live as long as the `HttpSession`; passivation is not supported;
  - "Next to REST": two mounts on one server, longest prefix wins, a duplicate prefix fails at startup; servlet filters and the `HttpSession` do not apply to REST resources; running REST inside Foy is tracked by https://codefloe.com/Vidocq/vidocq/issues/215;
  - "Declarative mount": `vidocq.http.mount.<n>.type=servlet`, with `path`, `listener`, `session-timeout`.

- [ ] **Step 2: Mount documentation** — on the page that documents `vidocq.http.mount.*`, add `servlet` to the list of types, and a sentence: "Mounts are routed longest prefix first; two mounts on the same prefix of one listener fail at startup." Mark it `[.tag-new]#NEW#`.

- [ ] **Step 3: What's new** — one entry for the Servlet extension, one for the routing change ("a duplicate mount prefix used to leave one mount silently unreachable; it now fails at startup").

- [ ] **Step 4: README.md and WEB-PROFILE.md** — README: the Servlet line names the extension artifact. WEB-PROFILE.md: Servlet row status "✅ delivered, official TCK passes; runtime extension `vidocq-runtime-foy-servlet-extension`".

- [ ] **Step 5: Module README** — short: what it does, the two dependencies, the keys, link to the docs page.

- [ ] **Step 6: Validate the AsciiDoc** — render the changed page to HTML (`asciidoctor -o - <file> | grep -c "Servlet (Foy)"`, expect ≥ 1) and check the table renders as a table.

- [ ] **Step 7: Commit**

```bash
git add docs README.md WEB-PROFILE.md vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-foy-servlet-extension/README.md
git commit -s -S -m "docs(servlet): Servlet extension, mount routing" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY"
```

---

### Task 8: Whole-repository check and PR

- [ ] **Step 1: Clean build of the repository with tests**

Run: `./mvnw -o -ntp clean install` from the worktree.
Expected: BUILD SUCCESS. The known pre-existing failure `vidocq-runtime-it-cyrano-jpms` `FindException` in a full reactor (see the memory note "Gate mvn test complet vidocq cassé") does not count: compare with `origin/main` in a separate worktree before blaming the branch.

- [ ] **Step 2: Existing ITs that mount on the web server** — `vidocq-runtime-it-cassini-rest`, `it-grimm-openapi`, `it-humboldt-cassini`, `it-cervantes-jwt` green (already in Step 1; read their surefire reports).

- [ ] **Step 3: Push and open the PR** against `main` on Codefloe, title `feat(servlet): Servlet extension (Foy on Chappe)`, body: summary, the routing change, test results, links to vidocq#215, #216, #217, ending with the Claude Code line and the session URL.
