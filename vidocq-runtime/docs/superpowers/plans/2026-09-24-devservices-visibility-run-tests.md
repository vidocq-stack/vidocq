# Dev services: visibility, `vidocq.properties`, `vidocq:run` and tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make dev services visible to the application, let the application's files tune them, and host them under `vidocq:run` (opt-in) and JUnit tests as well as `vidocq:dev`.

**Architecture:** Providers keep running outside the application's module graph. A new class-path module,
`vidocq-runtime-devservices-host`, holds the session logic that `vidocq:dev`, `vidocq:run` and a JUnit
`LauncherSessionListener` share. The session writes a JSON state file with no secret values. A new JPMS runtime
extension, `vidocq-runtime-devservices-extension`, reads that file for a `devservices` report section and dev
console panel.

**Tech Stack:** Java 25, Maven 3.9, JPMS, Testcontainers (providers only), JUnit Platform 1.x `LauncherSessionListener`, Vidocq report and dev console SPIs.

**Spec:** `docs/superpowers/specs/2026-09-24-devservices-visibility-run-tests-design.md` (read it first; section numbers below refer to it).

## Global Constraints

- Java 25. Every Maven call starts with `export JAVA_HOME=~/.sdkman/candidates/java/25-tem PATH=~/.sdkman/candidates/java/25-tem/bin:$PATH;`. Use `mvn`, never `./mvnw`. Add `-o` unless a download is needed.
- Testcontainers and docker-java never enter the application's module graph. Only `vidocq-runtime-devservices-host` (class path) and the providers depend on them, through the providers.
- `vidocq-runtime-devservices-extension` depends only on `vidocq-runtime-spi` and `vidocq-runtime-devconsole-spi`. It has no Testcontainers, no Maven API, no plugin dependency, and no JSON library.
- Opt-out keys (`vidocq.pool[.<name>].url`, `mp.jwt.verify.issuer`) never come from the application's files. Only keys starting with `vidocq.dev.` do (spec §5).
- Secret values never enter the state file (spec §4.2). A key is secret when its last dot-separated segment, lower-cased, ends with one of `password`, `passwd`, `pwd`, `secret`, `token`, `key`, `credential`, `credentials`, `apikey`, `api-key`, `private-key`.
- `vidocq:run` starts no dev service unless `vidocq.dev.devServices=true` (spec §6). `vidocq:dev` keeps defaulting to on.
- The state file path: `<basedir>/target/vidocq-dev-services.json`. The system property: `vidocq.devservices.state`.
- Code, Javadoc, comments, commit messages and docs are in English. Lines are at most 120 characters. Every new source file gets the license header copied from a neighbouring file.
- Commits: `git commit -S -F <msgfile>`, never `-m` and never `-s`. The message ends with exactly:
  ```
  Signed-off-by: Yann Blazart <yann@durand-blazart.fr>
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_018BxAuEFDc5tnwscGdCnXnn
  ```
- Ports for anything started by hand: 18093-18099, checked free first. Never 8080 or 8888.
- Docs: every new section carries `[.tag-new]#NEW#` and gets a `whats-new.adoc` bullet.

## Review Focus

1. **A pre-existing `target/vidocq-dev-services.json` from a killed session** is read by a later plain run (no
   host). The extension must show "no dev service" when `vidocq.devservices.state` is unset, and must never scan for
   the file itself. Test in Task 5.
2. **A `vidocq.dev.*` value written with `${…}` in `vidocq.properties`** reaches a provider as the literal
   expression. The PostgreSQL port then fails to parse. The provider's error must name the key and value rather
   than throw a bare `NumberFormatException`. Test in Task 2.
3. **Two datasources** (`vidocq.dev.postgres.datasources=default,audit`) must give two endpoints and all six
   injected keys, with both passwords `configured`. Test in Task 2 (describe) and Task 4 (state file).
4. **Dev services under `vidocq:run` with Ctrl+C** must stop the containers exactly once, with no exception from a
   second `close()`. Test in Task 6, with a session whose close is idempotent.
5. **A JDBC URL with credentials in its query string**
   (`jdbc:postgresql://h/db?user=u&password=p`) must be written with `password=***`. Test in Task 4.

---

## File structure

| File | Responsibility |
|---|---|
| `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevServiceState.java` | New record: what a provider started |
| `…-spi/…/DevService.java` | Add `describe(Map)` default |
| `…-spi/…/DevServiceContext.java` | Javadoc: state the two kinds of keys |
| `…/vidocq-runtime-devservice-postgres/…/PostgresDevService.java` | `describe`: image and one endpoint per datasource; port parse error names the key |
| `…/vidocq-runtime-devservice-keycloak/…/KeycloakDevService.java` | `describe`: image, issuer, admin URL |
| `vidocq-runtime-devservices/vidocq-runtime-devservices-host/` (new module) | `DevServicesSession`, `DevServiceManager`, `DefaultDevServiceContext`, `DevServicesReport` (moved), `ApplicationFiles`, `SecretMasking`, `StateFile`, `DevServicesException` |
| `vidocq-runtime-maven-plugin/…/dev/VidocqDevMojo.java`, `…/VidocqRunMojo.java` | Delegate to the session; add the extension jar and the state property |
| `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/` (new module, JPMS) | `DevServicesExtension`, `StateReader`, `DevServicesSnapshot`, `DevServicesSection` |
| `vidocq-runtime-devservices/vidocq-runtime-devservices-junit/` (new module) | `DevServicesSessionListener` |
| `vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/` (new, profile `docker`) | End-to-end checks |
| `DEV_SERVICES.md`, `docs/en/modules/ROOT/pages/dev-services.adoc` (new), `nav.adoc`, `dev-console.adoc`, `whats-new.adoc` | Docs |

---

### Task 1: Spike — JUnit `LauncherSessionListener` and Testcontainers under Surefire's module path

Throwaway. Settles spec §7.1 before Tasks 7–8 are built on it.

**Files:**
- Create (never committed, deleted at the end of the task): `vidocq-runtime-integration-tests/spike-devservices-junit/`
  in the working tree. It is a throwaway module, not listed in any parent POM, and built with
  `mvn -f <its pom>`.

**Interfaces:** none produced. The output is the answer recorded in the spec.

- [ ] **Step 1: Create a minimal modular app with a test.** Create a module `io.vidocq.spike.app` whose
  `module-info.java` is `module io.vidocq.spike.app { requires java.sql; }`, and a test class
  `io.vidocq.spike.app.SpikeTest`:

```java
package io.vidocq.spike.app;

import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SpikeTest {
    @Test
    void theListenerStartedPostgres() throws Exception {
        String url = System.getProperty("spike.url");
        try (var c = DriverManager.getConnection(url, System.getProperty("spike.user"), System.getProperty("spike.pw"));
             var rs = c.createStatement().executeQuery("select 1")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }
}
```

- [ ] **Step 2: Add a listener on the test class path.** In the same module's test sources, but in a package the
  `module-info` does not mention (`spike.listener`), register it in
  `src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`:

```java
package spike.listener;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.testcontainers.containers.PostgreSQLContainer;

public class SpikeListener implements LauncherSessionListener {
    private PostgreSQLContainer<?> pg;
    @Override public void launcherSessionOpened(LauncherSession session) {
        pg = new PostgreSQLContainer<>("postgres:16-alpine");
        pg.start();
        System.setProperty("spike.url", pg.getJdbcUrl());
        System.setProperty("spike.user", pg.getUsername());
        System.setProperty("spike.pw", pg.getPassword());
    }
    @Override public void launcherSessionClosed(LauncherSession session) { if (pg != null) pg.stop(); }
}
```

  Test dependencies: `org.testcontainers:postgresql`, `org.postgresql:postgresql`, `org.junit.platform:junit-platform-launcher`, `junit-jupiter`, all `test` scope, with versions from the root pom's dependency management.

- [ ] **Step 3: Run it on the module path (Surefire's default for a modular project).**
  Run: `mvn -ntp -f vidocq-runtime-integration-tests/spike-devservices-junit/pom.xml test`
  Expected: either PASS (the listener ran from the unnamed module and the test read the properties), or a failure.
  Record the exact error.

- [ ] **Step 4: If it failed, retry with `<useModulePath>false</useModulePath>`** in the spike's Surefire
  configuration. Record the result.

- [ ] **Step 5: Record the answer in the spec.** Replace the last paragraph of spec §7.1 with the outcome:
  - either "Verified on 2026-09-…: the listener and Testcontainers work from the unnamed module; no Surefire change
    is needed";
  - or "Fails on the module path with `<error>`; applications that use dev services in tests set
    `<useModulePath>false</useModulePath>`", in which case Task 7's docs step must say so.

- [ ] **Step 6: Delete the spike directory** (`rm -r vidocq-runtime-integration-tests/spike-devservices-junit`) and
  commit the spec change only:

```bash
git add docs/superpowers/specs/2026-09-24-devservices-visibility-run-tests-design.md
git commit -S -F msg.txt   # "docs(spec): record the JPMS-under-Surefire spike result (#123)"
```

---

### Task 2: SPI — `DevServiceState`, `describe()`, and the providers' descriptions

**Files:**
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevServiceState.java`
- Modify: `…-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevService.java` (add `describe`)
- Modify: `…-spi/src/main/java/io/vidocq/runtime/devservices/spi/DevServiceContext.java` (Javadoc, spec §5)
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservice-postgres/src/main/java/io/vidocq/runtime/devservices/postgres/PostgresDevService.java`
- Modify: `vidocq-runtime-devservices/vidocq-runtime-devservice-keycloak/src/main/java/io/vidocq/runtime/devservices/keycloak/KeycloakDevService.java`
- Test: `…-spi/src/test/java/io/vidocq/runtime/devservices/spi/DevServiceStateTest.java` (create; add JUnit to the SPI pom with `test` scope if absent)
- Test: `…-postgres/src/test/java/io/vidocq/runtime/devservices/postgres/PostgresDevServiceTest.java` (extend)

**Interfaces — Produces:**
- `record DevServiceState(String id, String image, Map<String, String> endpoints, List<String> injectedKeys)`, with
  `static DevServiceState minimal(String id, Collection<String> injectedKeys)`. `endpoints` is an immutable
  `LinkedHashMap` copy and `injectedKeys` a sorted immutable list; `image` may be `null`.
- `default DevServiceState describe(Map<String, String> injected)` on `DevService`.

- [ ] **Step 1: Write the failing SPI test**

```java
package io.vidocq.runtime.devservices.spi;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DevServiceStateTest {

    @Test
    void minimalKeepsTheIdAndSortsTheKeys() {
        DevServiceState s = DevServiceState.minimal("x", List.of("b.url", "a.url"));
        assertEquals("x", s.id());
        assertNull(s.image());
        assertEquals(Map.of(), s.endpoints());
        assertEquals(List.of("a.url", "b.url"), s.injectedKeys());
    }

    @Test
    void aProviderThatDoesNotDescribeItselfGetsTheMinimalState() {
        DevService p = new DevService() {
            public String id() { return "third-party"; }
            public boolean appliesWhen(DevServiceContext ctx) { return true; }
            public Map<String, String> start(DevServiceContext ctx) { return Map.of(); }
            public void stop() {}
        };
        assertEquals(DevServiceState.minimal("third-party", List.of("k")), p.describe(Map.of("k", "v")));
    }

    @Test
    void theStateIsImmutable() {
        DevServiceState s = new DevServiceState("x", "img", new java.util.LinkedHashMap<>(Map.of("a", "h:1")),
                new java.util.ArrayList<>(List.of("k")));
        assertThrows(UnsupportedOperationException.class, () -> s.endpoints().put("b", "h:2"));
        assertThrows(UnsupportedOperationException.class, () -> s.injectedKeys().add("z"));
    }
}
```

- [ ] **Step 2: Run it and check it fails to compile**

Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-spi test`
Expected: compilation failure, `cannot find symbol DevServiceState`.

- [ ] **Step 3: Implement `DevServiceState` and `describe`**

```java
package io.vidocq.runtime.devservices.spi;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link DevService} started, as the host reports it to the application: its id, the image it ran, the
 * addresses it exposes and the keys it injected. Plain values, written to the dev services state file.
 *
 * @param id           the provider's {@link DevService#id() id}
 * @param image        the container image, or {@code null} when the service is not a container
 * @param endpoints    what a person can reach, by name: {@code "default" -> "localhost:54321"}, or a URL
 * @param injectedKeys the keys {@link DevService#start} returned, sorted
 */
public record DevServiceState(String id, String image, Map<String, String> endpoints, List<String> injectedKeys) {

    public DevServiceState {
        Objects.requireNonNull(id, "id");
        endpoints = endpoints == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
        injectedKeys = injectedKeys == null ? List.of() : injectedKeys.stream().sorted().toList();
    }

    /** The state of a provider that says nothing about itself: its id and its keys. */
    public static DevServiceState minimal(String id, Collection<String> injectedKeys) {
        return new DevServiceState(id, null, Map.of(), List.copyOf(injectedKeys));
    }
}
```

  Add to `DevService`, after `stop()`:

```java
    /**
     * What this provider started, for the application's startup report and dev console. Called once, after
     * {@link #start}, with what {@code start} returned. The default names the provider and its keys; a provider
     * that runs a container should say which image and where it listens.
     *
     * @param injected what {@link #start} returned
     * @return the state, never {@code null}
     */
    default DevServiceState describe(Map<String, String> injected) {
        return DevServiceState.minimal(id(), injected.keySet());
    }
```

  Replace the second paragraph of `DevServiceContext`'s class Javadoc with:

```java
 * <p>Configuration is resolved from, in decreasing precedence: properties already collected from earlier providers
 * and the goal's explicit {@code -D} / {@code vidocq.dev.systemProperties}, then the host JVM's system properties,
 * then environment variables. Keys starting with {@code vidocq.dev.} are also read from the application's
 * {@code vidocq.properties} and {@code application.properties}, after those; every other key never is, so that a
 * baked-in default such as {@code vidocq.pool.url} does not switch a dev service off.</p>
```

- [ ] **Step 4: Run the SPI tests**

Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-spi install`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write the failing provider tests.** Append to `PostgresDevServiceTest`. It already has fakes for
  the context; reuse its helper that builds a `DatasourcePlan` from properties and its context double, and read the
  existing test first:

```java
    @Test
    void describeGivesTheImageAndOneEndpointPerDatasource() {
        Map<String, String> injected = new java.util.LinkedHashMap<>();
        injected.put("vidocq.pool.url", "jdbc:postgresql://localhost:54321/vidocq");
        injected.put("vidocq.pool.username", "vidocq");
        injected.put("vidocq.pool.password", "vidocq");
        injected.put("vidocq.pool.audit.url", "jdbc:postgresql://localhost:54322/vidocq");
        injected.put("vidocq.pool.audit.username", "vidocq");
        injected.put("vidocq.pool.audit.password", "vidocq");

        DevServiceState s = PostgresDevService.describe(injected, "postgres:16-alpine");

        assertEquals("postgres", s.id());
        assertEquals("postgres:16-alpine", s.image());
        assertEquals(Map.of("default", "localhost:54321", "audit", "localhost:54322"), s.endpoints());
        assertEquals(6, s.injectedKeys().size());
    }

    @Test
    void aPortThatIsNotANumberNamesItsKey() {
        FakeContext ctx = new FakeContext(Map.of("vidocq.dev.postgres.port", "${db.port}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PostgresDevService.plan(ctx, "default"));
        assertTrue(e.getMessage().contains("vidocq.dev.postgres.port"), e.getMessage());
        assertTrue(e.getMessage().contains("${db.port}"), e.getMessage());
    }
```

  If the existing test names the context double or the plan factory differently, use those names and keep the
  assertions.

- [ ] **Step 6: Run and check they fail.**
  Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservice-postgres test`.
  Expected: compilation error on `describe(Map, String)`, or the port test failing with `NumberFormatException`.

- [ ] **Step 7: Implement.** In `PostgresDevService`:
  - Keep the images of the started datasources in a `List<String> images` field.
  - Implement the instance `describe(Map)` as
    `return describe(injected, images.isEmpty() ? DEFAULT_IMAGE : images.getFirst());`.
  - Add the static helper:

```java
    /** The state for what {@link #start} returned: one endpoint per datasource, from its JDBC URL's host and port. */
    static DevServiceState describe(Map<String, String> injected, String image) {
        Map<String, String> endpoints = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : injected.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("vidocq.pool.") || !key.endsWith(".url")) {
                continue;
            }
            String middle = key.substring("vidocq.pool.".length(), key.length() - ".url".length());
            String name = middle.isEmpty() ? DEFAULT_NAME : middle;
            java.net.URI uri = java.net.URI.create(e.getValue().substring("jdbc:".length()));
            endpoints.put(name, uri.getHost() + ":" + uri.getPort());
        }
        return new DevServiceState("postgres", image, endpoints, List.copyOf(injected.keySet()));
    }
```

  Note: `"vidocq.pool.url"` gives `middle = ""` only if the key is exactly `vidocq.pool.url`. Handle it before the
  `substring`: when `key.equals("vidocq.pool.url")`, use `DEFAULT_NAME`.

  Where the plan parses `fixedPort`, wrap it:

```java
        String portText = ctx.property(devPrefix + "port").or(() -> ctx.property("vidocq.dev.postgres.port")).orElse(null);
        Integer fixedPort = null;
        if (portText != null) {
            try {
                fixedPort = Integer.valueOf(portText.trim());
            } catch (NumberFormatException notANumber) {
                throw new IllegalArgumentException("vidocq.dev.postgres port is not a number: "
                        + devPrefix + "port or vidocq.dev.postgres.port = '" + portText + "'", notANumber);
            }
        }
```

  In `KeycloakDevService`, keep `image` and `issuer` in fields set by `start`, and add:

```java
    @Override
    public DevServiceState describe(Map<String, String> injected) {
        Map<String, String> endpoints = new java.util.LinkedHashMap<>();
        if (container != null) {
            String base = "http://" + container.getHost() + ":" + container.getMappedPort(KC_PORT);
            endpoints.put("issuer", issuer);
            endpoints.put("admin", base + "/admin");
        }
        return new DevServiceState(id(), image, endpoints, List.copyOf(injected.keySet()));
    }
```

- [ ] **Step 8: Run the provider tests.**
  Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservice-postgres,vidocq-runtime-devservices/vidocq-runtime-devservice-keycloak install`
  Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add vidocq-runtime-devservices
git commit -S -F msg.txt   # "feat(devservices): providers describe what they started (#123)"
```

---

### Task 3: `vidocq-runtime-devservices-host` — move the manager and context, and read `vidocq.dev.*` from the application's files

**Files:**
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-host/pom.xml`. Add the module to `vidocq-runtime-devservices/pom.xml` `<modules>`, after `-spi`, and to the root pom's `dependencyManagement` next to the other devservices artifacts.
- Move (`git mv`) from `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/` to `…-host/src/main/java/io/vidocq/runtime/devservices/host/`: `DevServiceManager.java`, `DefaultDevServiceContext.java`, `DevServicesReport.java`. Move their tests the same way (`vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/dev/DevServiceManagerTest.java` etc., whichever exist).
- Create: `…-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesException.java`
- Create: `…-host/src/main/java/io/vidocq/runtime/devservices/host/ApplicationFiles.java`
- Test: `…-host/src/test/java/io/vidocq/runtime/devservices/host/DefaultDevServiceContextTest.java` (extend or create)
- Modify: `vidocq-runtime-maven-plugin/pom.xml` (depend on `vidocq-runtime-devservices-host`) and every import of the moved classes in the plugin.

**Interfaces — Produces:**
- `public final class DevServiceManager implements AutoCloseable` with
  - `public static DevServiceManager start(DefaultDevServiceContext ctx, System.Logger log) throws DevServicesException`
    (ServiceLoader on `DevServiceManager.class.getClassLoader()`);
  - `static DevServiceManager start(List<DevService> providers, DefaultDevServiceContext ctx, System.Logger log)`
    (package-private);
  - `public Map<String, String> collectedProperties()`, `public Map<String, String> providers()`;
  - **new** `public List<DevServiceState> states()`, one per started provider, in start order, from
    `p.describe(propsOfThatProvider)`;
  - `public void close()`, idempotent.
- `public final class DefaultDevServiceContext implements DevServiceContext` with
  `public DefaultDevServiceContext(Path basedir, Map<String, String> seed)` and
  `public DefaultDevServiceContext(Path basedir, Map<String, String> seed, Function<String, Optional<String>> applicationFiles)`,
  plus `public void merge(Map<String, String>)`.
- `public final class ApplicationFiles` with `public static Function<String, Optional<String>> of(Path classesDir)`.
  It returns a lookup reading `vidocq.properties` then `application.properties` from `classesDir`, plus the
  external file if configured, through `io.vidocq.runtime.core.config.PropertiesFileConfigSource` and
  `ExternalFileConfigSource`. It answers keys starting with `vidocq.dev.` only, and `Optional.empty()` for any other.
- `public final class DevServicesException extends Exception` with `(String message, Throwable cause)`.
- `public final class DevServicesReport` (public now): `public static List<Coordinates> datasources(Map<String,String>)`, `public record Coordinates(...)`, and the existing console-block and properties-file renderers made `public`.

- [ ] **Step 1: Create the module and move the classes**
  - Create `pom.xml`, copied from `vidocq-runtime-devservices-spi/pom.xml`, with artifactId `vidocq-runtime-devservices-host`.
  - Dependencies:
    - `vidocq-runtime-devservices-spi` (compile);
    - `io.vidocq.runtime:vidocq-runtime-core` (compile, for the config sources);
    - `junit-jupiter` (test).
  - No module-info: the module is class path only, like the SPI.
  - `git mv` the three classes and their tests. Change their package to `io.vidocq.runtime.devservices.host`, make
    them and the members listed above `public`, and replace:
    - `org.apache.maven.plugin.logging.Log` with `System.Logger` (`log.info(x)` becomes
      `log.log(System.Logger.Level.INFO, x)`);
    - `MojoExecutionException` with `DevServicesException`.

- [ ] **Step 2: Run the moved tests to prove the move is behaviour-neutral**

Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host -am install`
Expected: PASS with the same test count as before the move.

- [ ] **Step 3: Write the failing context tests**

```java
    @Test
    void aTuningKeyComesFromTheApplicationFilesAfterEveryOtherSource() {
        Function<String, Optional<String>> files = key -> key.equals("vidocq.dev.postgres.port")
                ? Optional.of("55432") : Optional.empty();
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(), files);
        assertEquals(Optional.of("55432"), ctx.property("vidocq.dev.postgres.port"));
    }

    @Test
    void aSeedWinsOverTheApplicationFiles() {
        Function<String, Optional<String>> files = key -> Optional.of("from-file");
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."),
                Map.of("vidocq.dev.postgres.image", "from-seed"), files);
        assertEquals(Optional.of("from-seed"), ctx.property("vidocq.dev.postgres.image"));
    }

    @Test
    void anOptOutKeyNeverComesFromTheApplicationFiles() {
        Function<String, Optional<String>> files = key -> Optional.of("jdbc:postgresql://localhost:5432/app");
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(), files);
        assertEquals(Optional.empty(), ctx.property("vidocq.pool.url"),
                "a baked-in URL must not switch the dev service off (spec §5)");
    }

    @Test
    void applicationFilesReadVidocqPropertiesFromTheClassesDirectory(@TempDir Path classes) throws Exception {
        Files.writeString(classes.resolve("vidocq.properties"),
                "vidocq.dev.postgres.port=55432\nvidocq.pool.url=jdbc:postgresql://x/y\n");
        Function<String, Optional<String>> files = ApplicationFiles.of(classes);
        assertEquals(Optional.of("55432"), files.apply("vidocq.dev.postgres.port"));
        assertEquals(Optional.empty(), files.apply("vidocq.pool.url"));
    }
```

- [ ] **Step 4: Run and check they fail**

Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host test`
Expected: compilation error (constructor and `ApplicationFiles` missing).

- [ ] **Step 5: Implement.** In `DefaultDevServiceContext`, keep a `Function<String, Optional<String>> applicationFiles`
  field (the two-argument constructor passes `key -> Optional.empty()`). At the end of `property(key)`, after the
  environment variable:

```java
        if (key.startsWith("vidocq.dev.")) {
            return applicationFiles.apply(key).filter(DefaultDevServiceContext::isPresent);
        }
        return Optional.empty();
```

  Update the class Javadoc to the rule of spec §5, replacing the "deliberately NOT consulted" paragraph with the two
  kinds of keys.

  `ApplicationFiles`: `PropertiesFileConfigSource()` (no argument) reads `vidocq.properties` and
  `application.properties` through the **thread's context class loader**, when it is constructed.
  `ExternalFileConfigSource()` finds its directory from `vidocq.config.dir`, then `VIDOCQ_CONFIG_DIR`, then
  `java.home`. In the plugin JVM, `java.home` is Maven's JDK, so it is used only when one of the first two is set.
  Both implement MicroProfile `ConfigSource` (`getPropertyNames()`, `getValue(key)`).

```java
public final class ApplicationFiles {

    private ApplicationFiles() {}

    /**
     * The {@code vidocq.dev.*} keys of the application's files, as the application will read them: the external file
     * when {@code vidocq.config.dir} or {@code VIDOCQ_CONFIG_DIR} names one, over {@code vidocq.properties} and
     * {@code application.properties} of {@code classesDir}. Other keys are never answered (spec §5).
     */
    public static Function<String, Optional<String>> of(Path classesDir) {
        Map<String, String> tuning = new java.util.HashMap<>();
        // Lowest precedence first: a later source overwrites.
        copyTuning(classpathSource(classesDir), tuning);
        if (System.getProperty("vidocq.config.dir") != null || System.getenv("VIDOCQ_CONFIG_DIR") != null) {
            copyTuning(new io.vidocq.runtime.core.config.ExternalFileConfigSource(), tuning);
        }
        Map<String, String> frozen = Map.copyOf(tuning);
        return key -> Optional.ofNullable(frozen.get(key));
    }

    /** {@code vidocq.properties} and {@code application.properties} of {@code classesDir} only, never the plugin's. */
    private static org.eclipse.microprofile.config.spi.ConfigSource classpathSource(Path classesDir) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[] {classesDir.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            thread.setContextClassLoader(loader);
            return new io.vidocq.runtime.core.config.PropertiesFileConfigSource();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot read the application's files in " + classesDir, e);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static void copyTuning(org.eclipse.microprofile.config.spi.ConfigSource source, Map<String, String> into) {
        for (String key : source.getPropertyNames()) {
            if (key.startsWith("vidocq.dev.")) {
                String value = source.getValue(key);
                if (value != null) {
                    into.put(key, value);
                }
            }
        }
    }
}
```

  Check that `PropertiesFileConfigSource` loads its properties **in the constructor**, which its line 74 suggests.
  If it loads lazily on the first `getValue`, call `getPropertyNames()` inside the `try`, before restoring the
  context loader, and copy the values there. If the `ConfigSource` type is not
  `org.eclipse.microprofile.config.spi.ConfigSource`, use the interface the two classes implement, as their
  `implements` clause shows.

- [ ] **Step 6: Point the plugin at the host module.** In `VidocqDevMojo`:
  - pass `ApplicationFiles.of(classesDir.toPath())` as the third constructor argument;
  - pass `System.getLogger("vidocq.dev.devservices")` instead of `getLog()`;
  - wrap the `DevServicesException` in a `MojoExecutionException` with the same message.

  Fix the imports.

- [ ] **Step 7: Run the host and plugin tests**

Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host,vidocq-runtime-maven-plugin install`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add vidocq-runtime-devservices vidocq-runtime-maven-plugin pom.xml
git commit -S -F msg.txt   # "refactor(devservices): a host module shared by every host; tuning keys from vidocq.properties (#123)"
```

---

### Task 4: The state file — `SecretMasking`, `StateFile`, `DevServicesSession`

**Files:**
- Create: `…-host/src/main/java/io/vidocq/runtime/devservices/host/SecretMasking.java`
- Create: `…-host/src/main/java/io/vidocq/runtime/devservices/host/StateFile.java`
- Create: `…-host/src/main/java/io/vidocq/runtime/devservices/host/DevServicesSession.java`
- Test: `…-host/src/test/java/io/vidocq/runtime/devservices/host/SecretMaskingTest.java`, `StateFileTest.java`, `DevServicesSessionTest.java`

**Interfaces:**
- Consumes: `DevServiceManager`, `DefaultDevServiceContext`, `DevServiceState`, `DevServicesReport` (Task 3, Task 2).
- Produces:
  - `public final class SecretMasking`, with `public static boolean isSecret(String key)` and
    `public static String withoutCredentials(String value)`.
  - `public final class StateFile`, with:
    - `public static final String FILE_NAME = "vidocq-dev-services.json"`;
    - `public static final String PROPERTY = "vidocq.devservices.state"`;
    - `public static String json(String host, String state, java.time.Instant startedAt, List<DevServiceState> services, Map<String, String> injected)`;
    - `public static void write(Path file, String json) throws java.io.IOException` (atomic).
  - `public final class DevServicesSession implements AutoCloseable`, with:
    - `public static DevServicesSession open(String host, Path basedir, Map<String, String> seed, Function<String, Optional<String>> applicationFiles, System.Logger log) throws DevServicesException`;
    - `static DevServicesSession open(String host, Path basedir, List<DevService> providers, DefaultDevServiceContext ctx, System.Logger log, java.time.Clock clock) throws DevServicesException` (package-private, for tests);
    - `public static DevServicesSession forTesting(String host, Path basedir, List<DevService> providers, System.Logger log) throws DevServicesException`,
      which opens a session on the given providers with an empty seed and the system clock. It is public for
      `vidocq-runtime-devservices-junit`'s tests (Task 7), and its Javadoc says it is for tests;
    - `public Map<String, String> injected()`, which returns the collected properties;
    - `public Map<String, String> providers()`, which returns the key → provider id map;
    - `public Path stateFile()`, which returns `basedir/target/vidocq-dev-services.json`;
    - `public void close()`, idempotent: it stops the providers, then rewrites the file with `"state":"stopped"`.

- [ ] **Step 1: Write `SecretMaskingTest`**

```java
class SecretMaskingTest {

    @ParameterizedTest
    @ValueSource(strings = {"vidocq.pool.password", "vidocq.pool.audit.password", "a.passwd", "a.pwd", "a.secret",
            "a.token", "mp.jwt.verify.publickey", "a.credentials", "a.credential", "a.apikey", "a.api-key",
            "a.private-key", "a.adminPassword", "A.PASSWORD"})
    void aKeyNamingASecretIsSecret(String key) {
        assertTrue(SecretMasking.isSecret(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"vidocq.pool.url", "vidocq.pool.username", "mp.jwt.verify.issuer", "a.keystore-type",
            "a.passwords-policy"})
    void otherKeysAreNot(String key) {
        assertFalse(SecretMasking.isSecret(key));
    }

    @Test
    void aUrlLosesItsCredentials() {
        assertEquals("jdbc:postgresql://***@localhost:5432/app",
                SecretMasking.withoutCredentials("jdbc:postgresql://app:s3cret@localhost:5432/app"));
        assertEquals("jdbc:postgresql://h/db?user=u&password=***&ssl=true",
                SecretMasking.withoutCredentials("jdbc:postgresql://h/db?user=u&password=p&ssl=true"));
        assertEquals("plain value", SecretMasking.withoutCredentials("plain value"));
        assertNull(SecretMasking.withoutCredentials(null));
    }
}
```

  Note on `a.keystore-type`: its last segment is `keystore-type`, which does not end with `key`, so it is not a
  secret. `a.passwords-policy` ends with `policy`, so it is not a secret either.

- [ ] **Step 2: Run, check it fails** (`SecretMasking` missing).

- [ ] **Step 3: Implement `SecretMasking`**

```java
public final class SecretMasking {

    private static final List<String> SECRET_ENDINGS = List.of("password", "passwd", "pwd", "secret", "token", "key",
            "credential", "credentials", "apikey", "api-key", "private-key");
    private static final java.util.regex.Pattern USER_INFO = java.util.regex.Pattern.compile("(//)[^/@\\s]+@");
    private static final java.util.regex.Pattern SECRET_PARAM = java.util.regex.Pattern.compile(
            "([?&;](?:password|passwd|pwd|secret|token|apikey|api-key)=)[^&;]*", java.util.regex.Pattern.CASE_INSENSITIVE);

    private SecretMasking() {}

    /** Whether the last dot-separated segment of {@code key} names a secret (spec §4.2). */
    public static boolean isSecret(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return SECRET_ENDINGS.stream().anyMatch(last::endsWith);
    }

    /** {@code value} without the {@code user:password@} of a URL and with secret query parameters as {@code ***}. */
    public static String withoutCredentials(String value) {
        if (value == null) {
            return null;
        }
        String out = USER_INFO.matcher(value).replaceAll("$1***@");
        return SECRET_PARAM.matcher(out).replaceAll("$1***");
    }
}
```

- [ ] **Step 4: Run, check it passes.**

- [ ] **Step 5: Write `StateFileTest`**

```java
class StateFileTest {

    @Test
    void secretsAreNeverWrittenAndUrlsLoseTheirCredentials() {
        Map<String, String> injected = new java.util.LinkedHashMap<>();
        injected.put("vidocq.pool.url", "jdbc:postgresql://h/db?user=u&password=p");
        injected.put("vidocq.pool.username", "vidocq");
        injected.put("vidocq.pool.password", "s3cret-value");
        DevServiceState pg = new DevServiceState("postgres", "postgres:16-alpine",
                Map.of("default", "localhost:54321"), List.copyOf(injected.keySet()));

        String json = StateFile.json("vidocq:dev", "running", Instant.parse("2026-09-24T10:12:03Z"),
                List.of(pg), injected);

        assertFalse(json.contains("s3cret-value"), json);
        assertFalse(json.contains("password=p"), json);
        assertTrue(json.contains("\"key\":\"vidocq.pool.password\",\"configured\":true"), json);
        assertTrue(json.contains("\"value\":\"jdbc:postgresql://h/db?user=u&password=***\""), json);
        assertTrue(json.contains("\"host\":\"vidocq:dev\""), json);
        assertTrue(json.contains("\"startedAt\":\"2026-09-24T10:12:03Z\""), json);
        assertTrue(json.contains("\"endpoints\":{\"default\":\"localhost:54321\"}"), json);
    }

    @Test
    void twoDatasourcesGiveBothEndpointsAndBothPasswordsConfigured() {
        Map<String, String> injected = new java.util.LinkedHashMap<>();
        for (String p : List.of("vidocq.pool.", "vidocq.pool.audit.")) {
            injected.put(p + "url", "jdbc:postgresql://localhost:5/x");
            injected.put(p + "username", "u");
            injected.put(p + "password", "pw");
        }
        DevServiceState pg = new DevServiceState("postgres", "img",
                Map.of("default", "localhost:5", "audit", "localhost:6"), List.copyOf(injected.keySet()));
        String json = StateFile.json("test", "running", Instant.EPOCH, List.of(pg), injected);
        assertEquals(2, json.split("\"configured\":true", -1).length - 1, json);
        assertFalse(json.contains("\"pw\""), json);
    }

    @Test
    void writeIsAtomic(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("target").resolve(StateFile.FILE_NAME);
        StateFile.write(file, "{\"a\":1}");
        assertEquals("{\"a\":1}", Files.readString(file));
        try (var s = Files.list(file.getParent())) {
            assertEquals(1, s.count(), "no temporary file left behind");
        }
    }

    @Test
    void jsonEscapesQuotesAndControlCharacters() {
        DevServiceState s = new DevServiceState("x", "img\"\n", Map.of(), List.of());
        String json = StateFile.json("t", "running", Instant.EPOCH, List.of(s), Map.of());
        assertTrue(json.contains("\"image\":\"img\\\"\\n\""), json);
    }
}
```

- [ ] **Step 6: Run, check it fails.**

- [ ] **Step 7: Implement `StateFile`.** Hand-written JSON with no library, one object per line-free string, keys
  in the order of spec §4.2:

```java
public final class StateFile {

    public static final String FILE_NAME = "vidocq-dev-services.json";
    public static final String PROPERTY = "vidocq.devservices.state";

    private StateFile() {}

    public static String json(String host, String state, java.time.Instant startedAt, List<DevServiceState> services,
                              Map<String, String> injected) {
        StringBuilder b = new StringBuilder(256);
        b.append("{\"host\":").append(str(host)).append(",\"state\":").append(str(state))
                .append(",\"startedAt\":").append(str(startedAt.toString())).append(",\"services\":[");
        for (int i = 0; i < services.size(); i++) {
            DevServiceState s = services.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":").append(str(s.id())).append(",\"image\":").append(s.image() == null ? "null" : str(s.image()))
                    .append(",\"endpoints\":{");
            int j = 0;
            for (Map.Entry<String, String> e : s.endpoints().entrySet()) {
                if (j++ > 0) b.append(',');
                b.append(str(e.getKey())).append(':').append(str(SecretMasking.withoutCredentials(e.getValue())));
            }
            b.append("},\"injected\":[");
            for (int k = 0; k < s.injectedKeys().size(); k++) {
                String key = s.injectedKeys().get(k);
                if (k > 0) b.append(',');
                b.append("{\"key\":").append(str(key));
                if (SecretMasking.isSecret(key)) {
                    b.append(",\"configured\":true}");
                } else {
                    b.append(",\"value\":").append(str(SecretMasking.withoutCredentials(injected.get(key)))).append('}');
                }
            }
            b.append("]}");
        }
        return b.append("]}").toString();
    }

    public static void write(Path file, String json) throws java.io.IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        Path tmp = java.nio.file.Files.createTempFile(file.getParent(), ".vidocq-dev-services", ".tmp");
        try {
            java.nio.file.Files.writeString(tmp, json, java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    private static String str(String v) {
        if (v == null) return "null";
        StringBuilder b = new StringBuilder(v.length() + 2).append('"');
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
```

- [ ] **Step 8: Run, check it passes.**

- [ ] **Step 9: Write `DevServicesSessionTest`.** Use two fake providers, the second failing to start in one test:

```java
class DevServicesSessionTest {

    static final class Fake implements DevService {
        final String id; final boolean fail; int stops;
        Fake(String id, boolean fail) { this.id = id; this.fail = fail; }
        public String id() { return id; }
        public boolean appliesWhen(DevServiceContext ctx) { return true; }
        public Map<String, String> start(DevServiceContext ctx) throws Exception {
            if (fail) throw new IllegalStateException("no docker");
            return Map.of(id + ".url", "jdbc:x://h:1/" + id, id + ".password", "pw");
        }
        public void stop() { stops++; }
    }

    private static DefaultDevServiceContext ctx(Path basedir) { return new DefaultDevServiceContext(basedir, Map.of()); }
    private static final System.Logger LOG = System.getLogger("test");
    private static final java.time.Clock CLOCK = java.time.Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), java.time.ZoneOffset.UTC);

    @Test
    void openWritesARunningStateAndCloseAStoppedOneOnce(@TempDir Path basedir) throws Exception {
        Fake a = new Fake("a", false);
        DevServicesSession s = DevServicesSession.open("vidocq:run", basedir, List.of(a), ctx(basedir), LOG, CLOCK);
        String running = Files.readString(s.stateFile());
        assertTrue(running.contains("\"state\":\"running\""), running);
        assertTrue(running.contains("\"host\":\"vidocq:run\""), running);
        assertEquals("jdbc:x://h:1/a", s.injected().get("a.url"));

        s.close();
        s.close();

        assertEquals(1, a.stops, "stopped exactly once");
        assertTrue(Files.readString(s.stateFile()).contains("\"state\":\"stopped\""));
    }

    @Test
    void aProviderThatFailsStopsTheOnesAlreadyStartedAndNamesItself(@TempDir Path basedir) {
        Fake a = new Fake("a", false);
        Fake b = new Fake("b", true);
        DevServicesException e = assertThrows(DevServicesException.class,
                () -> DevServicesSession.open("test", basedir, List.of(a, b), ctx(basedir), LOG, CLOCK));
        assertTrue(e.getMessage().contains("'b'"), e.getMessage());
        assertTrue(e.getMessage().contains("vidocq.dev.devServices=false"), e.getMessage());
        assertEquals(1, a.stops);
    }
}
```

- [ ] **Step 10: Run, check it fails.**

- [ ] **Step 11: Implement `DevServicesSession`.** `open`:
  1. `DevServiceManager.start(providers, ctx, log)`;
  2. `states = mgr.states()`;
  3. `startedAt = clock.instant()`;
  4. write `StateFile.json(host, "running", startedAt, states, mgr.collectedProperties())` to
     `basedir/target/vidocq-dev-services.json`;
  5. log the connection block with `DevServicesReport`, and write `vidocq-dev-services.properties` as the mojo did
     (move that code out of `VidocqDevMojo`).

  An `IOException` while writing becomes a `DevServicesException` after `mgr.close()`.

  `close()`: use an `AtomicBoolean`. The first call runs `mgr.close()` then writes the stopped state, catching and
  logging an `IOException`; later calls return.

  The public `open(host, basedir, seed, applicationFiles, log)` builds the context and uses
  `DevServiceManager.start(ctx, log)` (ServiceLoader), with `Clock.systemUTC()`.

  In `DevServiceManager.start`, the failure message must contain `"DevService '" + p.id() + "' failed to start: "`
  and `"set -Dvidocq.dev.devServices=false to skip"`, as today. Build `states()` by recording, for each started
  provider, `p.describe(propsOfThatProvider)`, where `propsOfThatProvider` is the map it returned (empty map if
  `null`). A provider whose `describe` throws falls back to `DevServiceState.minimal(p.id(), props.keySet())`.

- [ ] **Step 12: Run all host tests.**
  Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-host install`.
  Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add vidocq-runtime-devservices/vidocq-runtime-devservices-host
git commit -S -F msg.txt   # "feat(devservices): a session writes a state file with no secret value (#123)"
```

---

### Task 5: `vidocq-runtime-devservices-extension` — the `devservices` section and panel

**Files:**
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-extension/pom.xml`. It depends on `vidocq-runtime-spi`, `vidocq-runtime-devconsole-spi` and `junit-jupiter` (test); add it to the devservices parent `<modules>` and the root `dependencyManagement`.
- Create: `…-extension/src/main/java/module-info.java`:

```java
module io.vidocq.runtime.devservices.extension {
    requires transitive io.vidocq.runtime.spi.devconsole;
    exports io.vidocq.runtime.devservices.extension;
    provides io.vidocq.runtime.spi.VidocqExtension with io.vidocq.runtime.devservices.extension.DevServicesExtension;
}
```

- Create: `…-extension/src/main/resources/META-INF/services/io.vidocq.runtime.spi.VidocqExtension` (one line: the class name)
- Create: `…/extension/StateReader.java`, `DevServicesSnapshot.java`, `DevServicesSection.java`, `DevServicesExtension.java`
- Test: `…/extension/StateReaderTest.java`, `DevServicesSectionTest.java`, plus `RecordingSection.java` and `FakeReportContext.java` copied from `vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-core/vidocq-runtime-cassini-rest-extension/src/test/java/io/vidocq/runtime/extensions/jakartaee/core/cassini/` (change the package)

**Interfaces:**
- Consumes: the JSON format of Task 4 and `StateFile.PROPERTY`. The extension must not depend on the host module:
  copy the property name as a constant `static final String STATE_PROPERTY = "vidocq.devservices.state";`.
- Produces:
  - `record DevServicesSnapshot(String host, String state, String startedAt, List<Service> services)`, with a nested
    `record Service(String id, String image, Map<String,String> endpoints, List<Injected> injected)` and
    `record Injected(String key, String value, boolean configured)`;
  - `static final DevServicesSnapshot NONE`.
  - `final class StateReader` with `static DevServicesSnapshot parse(String json)`, which throws
    `IllegalArgumentException` on malformed input.
  - `final class DevServicesSection` with
    `static void write(DevServicesSnapshot s, String readProblem, StartupReportContext ctx, StartupReportSection section)`.

- [ ] **Step 1: Write `StateReaderTest`.** Use the exact JSON `StateFile.json` produces (copy one output from
  Task 4's test into a text block):

```java
    static final String JSON = """
        {"host":"vidocq:dev","state":"running","startedAt":"2026-09-24T10:12:03Z","services":[{"id":"postgres",\
        "image":"postgres:16-alpine","endpoints":{"default":"localhost:54321"},"injected":[{"key":"vidocq.pool.password",\
        "configured":true},{"key":"vidocq.pool.url","value":"jdbc:postgresql://localhost:54321/vidocq"}]}]}""";

    @Test
    void parsesTheHostsFile() {
        DevServicesSnapshot s = StateReader.parse(JSON);
        assertEquals("vidocq:dev", s.host());
        assertEquals("running", s.state());
        assertEquals(1, s.services().size());
        DevServicesSnapshot.Service pg = s.services().getFirst();
        assertEquals("postgres:16-alpine", pg.image());
        assertEquals(Map.of("default", "localhost:54321"), pg.endpoints());
        assertEquals(new DevServicesSnapshot.Injected("vidocq.pool.password", null, true), pg.injected().getFirst());
    }

    @Test
    void rejectsWhatItCannotRead() {
        assertThrows(IllegalArgumentException.class, () -> StateReader.parse("{\"host\":"));
        assertThrows(IllegalArgumentException.class, () -> StateReader.parse("[]"));
    }

    @Test
    void readsEscapes() {
        DevServicesSnapshot s = StateReader.parse("{\"host\":\"a\\\"b\\n\",\"state\":\"running\",\"startedAt\":\"x\",\"services\":[]}");
        assertEquals("a\"b\n", s.host());
    }
```

- [ ] **Step 2: Run, check it fails.**

- [ ] **Step 3: Implement `StateReader`.** It is a minimal recursive-descent reader, about 120 lines:
  - it reads objects, arrays, strings (with the escapes `\" \\ \/ \b \f \n \r \t \uXXXX`), `true`, `false`,
    `null` and numbers (kept as text);
  - it throws `IllegalArgumentException("dev services state: <what> at <index>")` on anything else, and limits the
    depth to 16;
  - it maps the result to `DevServicesSnapshot`, where a missing optional field becomes `null` or empty.

  Write the value reader as a private static nested class `Cursor` holding `String text; int pos;`.

- [ ] **Step 4: Run, check it passes.**

- [ ] **Step 5: Write `DevServicesSectionTest`**

```java
    @Test
    void summarisesEachServiceAndTheHost() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(StateReader.parse(StateReaderTest.JSON), null, new FakeReportContext(Verbosity.DETAILED), section);
        assertEquals("1 service: postgres (postgres:16-alpine at localhost:54321) — vidocq:dev", section.summary);
        assertEquals("postgres:16-alpine, default localhost:54321", section.rows.get("postgres"));
        assertEquals(Boolean.TRUE, section.secrets.get("postgres vidocq.pool.password"));
        assertEquals("jdbc:postgresql://localhost:54321/vidocq", section.rows.get("postgres vidocq.pool.url"));
        assertEquals("2026-09-24T10:12:03Z by vidocq:dev", section.rows.get("started"));
    }

    @Test
    void noHostMeansNoDevServiceAndNoAnomaly() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(DevServicesSnapshot.NONE, null, new FakeReportContext(Verbosity.DETAILED), section);
        assertEquals("no dev service: not started by vidocq:dev, vidocq:run or the test launcher", section.summary);
        assertTrue(section.anomalies.isEmpty());
    }

    @Test
    void anUnreadableFileIsAnAnomalyAndTheBootGoesOn() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(DevServicesSnapshot.NONE, "IllegalArgumentException",
                new FakeReportContext(Verbosity.DETAILED), section);
        assertEquals(List.of("VIDOCQ-DEVS-001"), section.codes());
    }

    @Test
    void valuesAreShownInADevLaunchOnly() {
        RecordingSection section = new RecordingSection();
        DevServicesSection.write(StateReader.parse(StateReaderTest.JSON), null,
                new FakeReportContext(Verbosity.DETAILED, LaunchMode.TEST), section);
        assertNull(section.rows.get("postgres vidocq.pool.url"), "keys only outside dev");
        assertTrue(section.lists.get("postgres keys").contains("vidocq.pool.url"));
    }
```

  Give the copied `FakeReportContext` a second constructor `(Verbosity, LaunchMode)`, defaulting to `LaunchMode.DEV`.

- [ ] **Step 6: Run, check it fails.**

- [ ] **Step 7: Implement `DevServicesSection` and `DevServicesExtension`.**
  - `DevServicesSection.write`, in order:
    1. If `readProblem != null`, write
       `section.anomaly("VIDOCQ-DEVS-001", "The dev services state file could not be read: " + readProblem + ".", "Rerun the goal: the Vidocq Maven plugin writes it.")`,
       then the summary `"state file unreadable"`, and return.
    2. With no service, write the summary `no dev service: not started by vidocq:dev, vidocq:run or the test launcher`
       and return.
    3. Otherwise, write the summary `<n> service(s): id (image at firstEndpoint), … — <host>`, then, when the state
       is `stopped`, append `, stopped`.
    4. Always write `section.row("started", startedAt + " by " + host)`: an old file from a killed host then shows
       its age (spec §8).
    5. At DETAILED, for each service:
       - `section.row(id, image + ", " + endpoints joined as "name value")`;
       - in a DEV launch, for each injected key: `section.secret(id + " " + key, true)` when `configured`, else
         `section.row(id + " " + key, value)`;
       - outside DEV, `section.list(id + " keys", keys)`.
  - `DevServicesExtension implements VidocqExtension, DevConsolePanel`:
    - `name()` is `"devservices"` and `priority()` is `150`;
    - `id()` is `"devservices"` and `title()` is `"Dev services"`;
    - `volatile DevServicesSnapshot snapshot = DevServicesSnapshot.NONE; volatile String readProblem;`
    - `onStart`: read `System.getProperty(STATE_PROPERTY)`. If it is null or blank, keep `NONE`. Otherwise
      `Files.readString(Path.of(p))` and `StateReader.parse`. On `IOException` or `IllegalArgumentException`, set
      `readProblem = e.getClass().getSimpleName()`. A missing file at a given path is `NONE` with no anomaly: check
      `Files.exists` first.
    - `onStop`: `snapshot = NONE; readProblem = null;`
    - `contribute` → `DevServicesSection.write(snapshot, readProblem, context, section)`;
    - `sample(PanelSample out)`: write nothing. `charts()` is empty. The panel shows boot facts only.
  - **Review Focus 1:** the extension never looks for the file itself. Add a test that, with `STATE_PROPERTY`
    unset and a file present in `target/`, the snapshot stays `NONE`. Set and clear the system property in the
    test with try/finally.

- [ ] **Step 8: Run the module tests.**
  Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-extension -am install`.
  Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add vidocq-runtime-devservices pom.xml
git commit -S -F msg.txt   # "feat(devservices): a devservices section and dev console panel from the state file (#123)"
```

---

### Task 6: The plugin — both goals use the session, pass the state file and the extension jar

**Files:**
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/dev/VidocqDevMojo.java` (lines ~250–260 and 279, 350: the dev services block)
- Modify: `vidocq-runtime-maven-plugin/src/main/java/io/vidocq/runtime/maven/VidocqRunMojo.java` (`execute`, `await`)
- Modify: `vidocq-runtime-maven-plugin/pom.xml`: add `vidocq-runtime-devservices-extension` as a dependency with `<scope>runtime</scope>`, so that its jar is in the plugin's artifacts. It was created in Task 5.
- Test: `vidocq-runtime-maven-plugin/src/test/java/io/vidocq/runtime/maven/VidocqRunMojoTest.java` (extend the existing test of `await` / flags)

**Interfaces:**
- Consumes: `DevServicesSession.open(String, Path, Map, Function, System.Logger)`, `session.injected()`,
  `session.providers()`, `session.stateFile()`, `StateFile.PROPERTY`, `ApplicationFiles.of(Path)`.
- Produces: `VidocqRunMojo` parameter
  `@Parameter(property = "vidocq.dev.devServices", defaultValue = "false") boolean devServices;`, and the extension
  jar path resolved by `DevServicesExtensionJar.find(Map<String, Artifact> pluginArtifacts)`, a small new helper in
  `io.vidocq.runtime.maven.dev` that throws `MojoExecutionException` if missing.

- [ ] **Step 1: Write the failing tests**
  - `vidocq:run` starts nothing by default: a test that runs the mojo's dev services decision with the default
    field value and asserts no session is opened. Extract a package-private
    `boolean devServicesEnabled(Map<String,String> sysProps, Function<String,Optional<String>> files)` returning
    `devServices || "true".equals(files.apply("vidocq.dev.devServices").orElse(null))`, and test that it is `false`
    by default, `true` with the field set, and `true` with the file key.
  - `DevServicesExtensionJar.find` returns the jar of artifact `io.vidocq.runtime:vidocq-runtime-devservices-extension`
    from a fake artifact map, and throws with a message naming the artifact when it is absent.

- [ ] **Step 2: Run, check they fail.**

- [ ] **Step 3: Implement.**
  - In `VidocqDevMojo`, replace the block `DevServiceManager devs = null; if (devServices) {…}` with:

```java
        DevServicesSession devs = null;
        if (devServices) {
            try {
                devs = DevServicesSession.open("vidocq:dev", projectDir, sysProps,
                        ApplicationFiles.of(classesDir.toPath()), System.getLogger("vidocq.dev.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            foldDevServiceProperties(sysProps, devs.injected(), devs.providers());
            sysProps.putIfAbsent(StateFile.PROPERTY, devs.stateFile().toAbsolutePath().toString());
            modulePath.add(DevServicesExtensionJar.find(pluginArtifactMap));
        }
```

  - Change `foldDevServiceProperties` to take the two maps rather than the manager. The shutdown hook and `finally`
    call `devs.close()` (idempotent). Remove the now-duplicated connection-block code, which moved into the session.
  - Inject the plugin's artifacts in both mojos:
    `@Parameter(defaultValue = "${plugin.artifactMap}", readonly = true) private Map<String, Artifact> pluginArtifactMap;`
  - In `VidocqRunMojo.execute`, after `buildSystemProperties()`:

```java
        DevServicesSession devs = null;
        if (devServicesEnabled(systemProperties, ApplicationFiles.of(classes))) {
            try {
                devs = DevServicesSession.open("vidocq:run", projectDir, systemProperties, ApplicationFiles.of(classes),
                        System.getLogger("vidocq.run.devservices"));
            } catch (DevServicesException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            devs.injected().forEach(systemProperties::putIfAbsent);
            devs.providers().forEach((k, id) -> systemProperties.putIfAbsent("vidocq.dev.provided." + k, id));
            systemProperties.putIfAbsent(StateFile.PROPERTY, devs.stateFile().toAbsolutePath().toString());
            modulePath.add(DevServicesExtensionJar.find(pluginArtifactMap));
        }
        try {
            await(ChildJvm.of(modulePath, appPath, mainModule, mainClass, jvmArgs, systemProperties,
                    projectDir, splitArgs(appArgs)));
        } finally {
            if (devs != null) {
                devs.close();
            }
        }
```

  `modulePath` must be mutable. Wrap it in `new ArrayList<>(…)` if `ApplicationLaunch.modulePath` returns an
  immutable list. Ctrl+C: the `vidocq-run-shutdown` hook stops the child, `await` returns, and the `finally` closes
  the session. Also call `devs.close()` from the hook: it is idempotent, and covers a JVM that exits before
  `finally`. To do that, pass `devs` into `await` through an `AtomicReference<DevServicesSession>` field.

- [ ] **Step 4: Run the plugin tests.**
  Run: `mvn -ntp -o -pl vidocq-runtime-maven-plugin install`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-maven-plugin
git commit -S -F msg.txt   # "feat(maven-plugin): vidocq:run hosts dev services on opt-in; both goals hand the app the state file (#123)"
```

---

### Task 7: `vidocq-runtime-devservices-junit` — dev services for tests

**Files:**
- Create: `vidocq-runtime-devservices/vidocq-runtime-devservices-junit/pom.xml`. Dependencies (compile scope, since the module itself is a test dependency of applications):
  - `vidocq-runtime-devservices-host`;
  - `vidocq-runtime-devservices-extension`;
  - `org.junit.platform:junit-platform-launcher`, with the version from dependency management; `provided` if Surefire brings it, otherwise compile. Check what Surefire 3.5.x puts on the path, and pick `provided` if the launcher is already there.
- Create: `…-junit/src/main/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListener.java`
- Create: `…-junit/src/main/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener`
- Test: `…-junit/src/test/java/io/vidocq/runtime/devservices/junit/DevServicesSessionListenerTest.java`

**Interfaces:**
- Consumes: `DevServicesSession.open(...)`, `ApplicationFiles.of(Path)`, `StateFile.PROPERTY`.
- Produces: the listener, with a package-private constructor
  `DevServicesSessionListener(Function<Path, DevServicesSession> opener)` for tests.

- [ ] **Step 1: Write the failing test.** Drive the listener with a fake opener that returns a session built from
  fake providers, through the package-private `DevServicesSession.open(...)` of Task 4. To reach it, put the test in
  package `io.vidocq.runtime.devservices.host`, or add a public test-support factory
  `DevServicesSession.forTesting(host, basedir, providers, log)`, declared in Task 4. Pick the factory: it keeps
  packages clean. Assertions:
  - after `launcherSessionOpened`, `System.getProperty("a.url")` is set and `System.getProperty(StateFile.PROPERTY)`
    points to an existing file;
  - a key already set as a system property before opening keeps its value;
  - after `launcherSessionClosed`, the fake provider stopped once;
  - with `-Dvidocq.dev.devServices=false` (set in the test, cleared in `finally`), the opener is never called.

  Restore every system property the test touched in `@AfterEach`.

- [ ] **Step 2: Run, check it fails.**

- [ ] **Step 3: Implement**

```java
public final class DevServicesSessionListener implements LauncherSessionListener {

    private final Function<Path, DevServicesSession> opener;
    private DevServicesSession session;

    /** Used by the JUnit Platform, through {@code META-INF/services}. */
    public DevServicesSessionListener() {
        this(basedir -> {
            try {
                return DevServicesSession.open("test", basedir, Map.of(),
                        ApplicationFiles.of(basedir.resolve("target").resolve("classes")),
                        System.getLogger("vidocq.test.devservices"));
            } catch (DevServicesException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    DevServicesSessionListener(Function<Path, DevServicesSession> opener) {
        this.opener = opener;
    }

    @Override
    public void launcherSessionOpened(LauncherSession launcherSession) {
        Path basedir = Path.of(System.getProperty("basedir", System.getProperty("user.dir")));
        Function<String, Optional<String>> files = ApplicationFiles.of(basedir.resolve("target").resolve("classes"));
        String flag = Optional.ofNullable(System.getProperty("vidocq.dev.devServices"))
                .or(() -> files.apply("vidocq.dev.devServices")).orElse("true");
        if ("false".equalsIgnoreCase(flag.trim())) {
            return;
        }
        session = opener.apply(basedir);
        session.injected().forEach((k, v) -> {
            if (System.getProperty(k) == null) {
                System.setProperty(k, v);
            }
        });
        session.providers().forEach((k, id) -> {
            if (System.getProperty("vidocq.dev.provided." + k) == null) {
                System.setProperty("vidocq.dev.provided." + k, id);
            }
        });
        System.setProperty(StateFile.PROPERTY, session.stateFile().toAbsolutePath().toString());
    }

    @Override
    public void launcherSessionClosed(LauncherSession launcherSession) {
        if (session != null) {
            session.close();
            session = null;
        }
    }
}
```

  **No container runtime** (spec §8): the provider's Testcontainers exception already reaches
  `DevServicesException`'s message through the manager, which names the provider and the switch-off key. Make sure
  the `IllegalStateException` rethrown here keeps that message. JUnit then reports it as a launcher failure.

- [ ] **Step 4: Run the tests.**
  Run: `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-junit -am install`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-devservices pom.xml
git commit -S -F msg.txt   # "feat(devservices): a JUnit launcher session hosts dev services for tests (#123)"
```

---

### Task 8: End-to-end IT and documentation

**Files:**
- Create: `vidocq-runtime-integration-tests/vidocq-runtime-it-devservices/`. It is a modular app with one datasource used through `java.sql` directly, plus tests. Add it to `vidocq-runtime-integration-tests/pom.xml` **inside a profile `docker`** (`<profiles><profile><id>docker</id><modules><module>vidocq-runtime-it-devservices</module></modules></profile></profiles>`), so that a plain build never needs Docker.
- Modify: `DEV_SERVICES.md`
- Create: `docs/en/modules/ROOT/pages/dev-services.adoc`; modify `docs/en/modules/ROOT/nav.adoc`, `dev-console.adoc` (panels table row before `*MCP server*`), `whats-new.adoc` (bullet before `* **Buttons in the dev console`)

- [ ] **Step 1: Write the IT tests**
  - `DevServicesTestHostIT`, run by Surefire with the app's test dependencies `vidocq-runtime-devservices-junit`
    and `vidocq-runtime-devservice-postgres` (test scope):
    - boots the app with `VidocqBootstrap.create().configure().start()`;
    - asserts that `select 1` works on `System.getProperty("vidocq.pool.url")`;
    - asserts that the startup report's `devservices` section summary contains `postgres (postgres:16-alpine at localhost:`
      and `— test`. Read it through `ExtensionContext.startupReport()`, or through the dev console snapshot with
      `-Dvidocq.launch.mode=dev` and `vidocq.devconsole.port=0`, whichever the mansart-h2 `DevConsoleSnapshotTest`
      already does. Copy its approach.
  - `DevServicesRunGoalIT`: uses `maven-invoker-plugin` or a `ProcessBuilder` running
    `mvn vidocq:run -Dvidocq.dev.devServices=true -Dvidocq.chappe.listener.default.port=<free 18093-18099>`, like
    the existing IT modules that launch a server. Read the log for `devservices` and
    `1 service: postgres (postgres:16-alpine at`, and assert that the password from
    `target/vidocq-dev-services.properties` appears nowhere in the log or in `target/vidocq-dev-services.json`.
  - If the Task 1 spike required `useModulePath=false`, set it in this module's Surefire configuration, with a
    comment linking spec §7.1.

- [ ] **Step 2: Run them.**
  Run: `mvn -ntp -Pdocker -pl vidocq-runtime-integration-tests/vidocq-runtime-it-devservices -am verify`
  (needs Docker). Expected: PASS. Stop anything you started.

- [ ] **Step 3: Write the docs.**
  - `DEV_SERVICES.md`:
    - a section "Where configuration comes from", with the two kinds of keys of spec §5 and the `vidocq.pool.url`
      counter-example;
    - the stable-port example now stated as working from `vidocq.properties`;
    - "vidocq:run" (opt-in);
    - "In tests" (the dependency, `vidocq.dev.devServices=false`, and the spike's Surefire note);
    - "The state file" (no secrets; the `.properties` file keeps passwords and is local).
  - `dev-services.adoc` covers the same for the docs site, with an example `devservices` section output copied
    from the IT log, and the anomaly `VIDOCQ-DEVS-001`.
  - `nav.adoc` gets an entry.
  - `dev-console.adoc` gets a panels row: *Dev services* — "What `vidocq:dev`, `vidocq:run` or the test launcher
    started: image and addresses of each service, the keys it injected, secrets as `configured`. Boot facts only."
  - `whats-new.adoc` gets one bullet linking `dev-services.adoc`.

  Every new section carries `[.tag-new]#NEW#`.

- [ ] **Step 4: Full build without Docker.**
  Run: `mvn -ntp -o install -DskipTests` then
  `mvn -ntp -o -pl vidocq-runtime-devservices/vidocq-runtime-devservices-spi,vidocq-runtime-devservices/vidocq-runtime-devservices-host,vidocq-runtime-devservices/vidocq-runtime-devservices-extension,vidocq-runtime-devservices/vidocq-runtime-devservices-junit,vidocq-runtime-maven-plugin test`.
  Expected: PASS, and the `docker` profile module is not built.

- [ ] **Step 5: Commit**

```bash
git add vidocq-runtime-integration-tests DEV_SERVICES.md docs
git commit -S -F msg.txt   # "test(devservices): end-to-end checks behind -Pdocker; docs (#123)"
```

- [ ] **Step 6: Check the runners.** Find whether the organisation's CI runners (label `vidocq-runner`) have a
  Docker daemon, via the existing CI workflow that uses Testcontainers or the runner docs. Report the answer: it
  decides whether CI can run `-Pdocker`. Do not change CI in this plan.
