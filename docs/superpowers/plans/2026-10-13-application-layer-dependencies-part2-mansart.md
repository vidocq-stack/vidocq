# Application layer holds the application's dependencies — Implementation Plan (part 2: Mansart pool, mansart#42)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Mansart pool finds its JDBC driver through the application's class loader, so that a driver living
in Vidocq's application layer works while the pool lives in the runtime layer.

**Architecture:**
- A new package-private `DriverLocator` in `mansart-pool-core` resolves a `java.sql.Driver` for a JDBC URL:
  - first `ServiceLoader.load(Driver.class, loader)`, where `loader` is the context class loader captured when
    the pool is built, falling back to the pool's own loader;
  - then `DriverManager.getDriver(url)`, so that drivers registered by hand keep working.
- `ConnectionPool` resolves the driver once, lazily, at its first `open()`, and opens with
  `driver.connect(url, props)` instead of `DriverManager.getConnection`.
- `MansartDataSource.resolveDriverLoader()` and the pool share the same loader rule.

**Tech Stack:** Java 25, JDBC (`java.sql`), JUnit 5 and AssertJ (as in `mansart-pool-tests`), H2 at test scope,
Maven 3.9.16 (`./mvnw` in the mansart repository).

**Spec:** `docs/superpowers/specs/2026-10-13-application-layer-dependencies-design.md` (vidocq repo, branch
`docs/app-layer-dependencies-design`), section 5, first row of the table.

## Where this sits

This is plan 2 of 6 (see part 1, `2026-10-13-application-layer-dependencies.md`). It does not depend on Vauban's
part 1: the fix is correct on a plain class path as well. The Liquibase migrator and `MigrationExtension` live in
the vidocq repository, so they belong to plan 3.

## Global Constraints

- Java 25 (Temurin) and Maven 3.9.16, through `./mvnw` in the mansart repository.
- JPMS strict: `mansart-pool-core` declares `uses java.sql.Driver;`. No new `opens`.
- Zero external dependency in `mansart-pool-core`: JDK only. H2 stays test scope.
- TDD: write the test, watch it fail for the stated reason, implement, watch it pass.
- English for code, comments, Javadoc, commits and docs. Commits are signed with `git commit -s -S`, and their
  message ends with these two trailers:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY
  ```
- Every visible change gets a What's new entry and a `[.tag-new]#NEW#` badge on the documented section. Every
  reproducible bug gets a `BUG.md` entry, using the next free `MANSART-0NN` id in the file's format.
- Verification runs in an isolated local Maven repository (`-Dmaven.repo.local=<scratch>/m2-…`), never `~/.m2`.
  The final check is the full `CI=true ./mvnw install` of the mansart reactor.

## Review Focus

1. **A plain class path keeps working**: the pool, the driver and the test all on one loader. The existing
   `MansartDataSourceTest` and `LeakDetectionTest` must stay green unchanged.
2. **No context class loader** (`null`, as on some framework threads): fall back to the pool's own loader, never
   a `NullPointerException`. Pinned in Task 1.
3. **Several drivers on the path**: the first one whose `acceptsURL(url)` is true wins, and a driver that throws
   while being loaded or probed (`ServiceConfigurationError`, `SQLException` from `acceptsURL`) is skipped, not
   fatal. Pinned in Task 1.
4. **`minIdle > 0`**: the pre-fill in the constructor opens connections. The driver must therefore resolve with
   the loader captured at construction, before any later thread changes its context loader. Pinned in Task 2.
5. **Error message**: when no driver accepts the URL, the `PoolException` (`CONNECT_FAILED`) names the URL,
   the loader searched and `DriverManager`. It never prints the password or the driver properties. Pinned in
   Task 2.

---

### Task 1: `DriverLocator`

**Files:**
- Create: `mansart-pool/mansart-pool-core/src/main/java/io/vidocq/mansart/pool/core/DriverLocator.java`
- Modify: `mansart-pool/mansart-pool-core/src/main/java/module-info.java` (add `uses java.sql.Driver;`)
- Modify: `mansart-pool/mansart-pool-core/src/main/java/io/vidocq/mansart/pool/core/MansartDataSource.java`:
  `resolveDriverLoader()` delegates to `DriverLocator.applicationLoader(MansartDataSource.class)`
- Test: `mansart-pool/mansart-pool-tests/src/test/java/io/vidocq/mansart/pool/DriverLocatorTest.java` (create).
  The tests module runs on the class path and reaches `DriverLocator` through `MansartDataSource`. The locator
  itself is package-private, so this task tests it through a public seam: see Interfaces.

**Interfaces:**
- Produces, in package `io.vidocq.mansart.pool.core`:
  - `final class DriverLocator` (package-private) with:
    - `static ClassLoader applicationLoader(Class<?> anchor)`: the current thread's context class loader, or
      `anchor.getClassLoader()` when it is `null`;
    - `static java.sql.Driver find(String jdbcUrl, ClassLoader loader) throws java.sql.SQLException`: the first
      `ServiceLoader.load(Driver.class, loader)` provider whose `acceptsURL(jdbcUrl)` is true, else
      `DriverManager.getDriver(jdbcUrl)`. It throws `SQLException` when neither accepts the URL, with the
      message `No JDBC driver accepts <jdbcUrl>: searched the java.sql.Driver providers visible to <loader> and the drivers registered with DriverManager`.
  - A public test seam on `MansartDataSource`: `public static String driverClassFor(String jdbcUrl) throws SQLException`
    returns `DriverLocator.find(jdbcUrl, DriverLocator.applicationLoader(MansartDataSource.class)).getClass().getName()`.
    Its Javadoc says it reports which driver a pool built now on this thread would use, for diagnostics and
    tests.
- Consumed by Task 2: `DriverLocator.applicationLoader` and `DriverLocator.find`.

- [ ] **Step 1: Write the failing tests**

```java
/*
 * (license header: copy the 18-line header of MansartDataSourceTest.java verbatim)
 */
package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which JDBC driver a pool uses (mansart#42): a {@code java.sql.Driver} provider visible to the application's
 * class loader, else one registered with {@code DriverManager}.
 */
class DriverLocatorTest {

    @Test
    void theH2ProviderOnTheClassPathIsFound() throws SQLException {
        assertThat(MansartDataSource.driverClassFor("jdbc:h2:mem:locator")).isEqualTo("org.h2.Driver");
    }

    @Test
    void aNullContextLoaderFallsBackToThePoolsOwnLoader() throws SQLException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(null);
        try {
            assertThat(MansartDataSource.driverClassFor("jdbc:h2:mem:locator")).isEqualTo("org.h2.Driver");
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void aUrlNoDriverAcceptsNamesTheUrlAndWhereItLooked() {
        assertThatThrownBy(() -> MansartDataSource.driverClassFor("jdbc:nosuchdb:x"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("No JDBC driver accepts jdbc:nosuchdb:x")
                .hasMessageContaining("java.sql.Driver providers visible to")
                .hasMessageContaining("DriverManager");
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -B -ntp -pl mansart-pool/mansart-pool-tests -am install -Dtest=DriverLocatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure, `cannot find symbol … method driverClassFor(String)`.

- [ ] **Step 3: Implement**

`DriverLocator.java`:

```java
/*
 * (license header: copy the 18-line header of ConnectionPool.java verbatim)
 */
package io.vidocq.mansart.pool.core;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds the JDBC driver of a pool (mansart#42). {@code DriverManager} hands out a driver only to a caller whose
 * class loader can load it. Under Vidocq the pool lives in the runtime layer while the driver lives with the
 * application, so the pool asks the application's class loader for {@code java.sql.Driver} providers itself, as
 * servlet containers do, and keeps {@code DriverManager} for drivers registered by hand.
 */
final class DriverLocator {

    private DriverLocator() {
    }

    /** The current thread's context class loader, or {@code anchor}'s loader when the thread has none. */
    static ClassLoader applicationLoader(Class<?> anchor) {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : anchor.getClassLoader();
    }

    /**
     * The first {@code java.sql.Driver} provider visible to {@code loader} that accepts {@code jdbcUrl}, else the
     * driver {@code DriverManager} returns for it. A provider that fails to load or to answer is skipped.
     */
    static Driver find(String jdbcUrl, ClassLoader loader) throws SQLException {
        Iterator<Driver> providers = ServiceLoader.load(Driver.class, loader).iterator();
        while (true) {
            Driver driver;
            try {
                if (!providers.hasNext()) {
                    break;
                }
                driver = providers.next();
            } catch (ServiceConfigurationError broken) {
                continue; // a provider that cannot be loaded does not hide the next one
            }
            try {
                if (driver.acceptsURL(jdbcUrl)) {
                    return driver;
                }
            } catch (SQLException refused) {
                // this driver cannot even judge the URL: try the next one
            }
        }
        try {
            return DriverManager.getDriver(jdbcUrl);
        } catch (SQLException none) {
            throw new SQLException("No JDBC driver accepts " + jdbcUrl + ": searched the java.sql.Driver providers"
                    + " visible to " + loader + " and the drivers registered with DriverManager", "08001", none);
        }
    }
}
```

`module-info.java`: add after `requires java.sql;`:

```java
    uses java.sql.Driver;       // the pool finds its driver through the application's loader (mansart#42)
```

`MansartDataSource.java`:
- replace the body of `resolveDriverLoader()` with `return DriverLocator.applicationLoader(MansartDataSource.class);`;
- add the seam:

```java
    /**
     * The class of the JDBC driver that a pool built now, on this thread, would use for {@code jdbcUrl}: a
     * {@code java.sql.Driver} provider visible to the context class loader, else one registered with
     * {@code DriverManager}. For diagnostics and tests.
     */
    public static String driverClassFor(String jdbcUrl) throws SQLException {
        return DriverLocator.find(jdbcUrl, DriverLocator.applicationLoader(MansartDataSource.class))
                .getClass().getName();
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B -ntp -pl mansart-pool/mansart-pool-tests -am install -Dtest=DriverLocatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 3/3.

- [ ] **Step 5: Commit**

```bash
git add mansart-pool/mansart-pool-core/src/main/java/io/vidocq/mansart/pool/core/DriverLocator.java \
        mansart-pool/mansart-pool-core/src/main/java/module-info.java \
        mansart-pool/mansart-pool-core/src/main/java/io/vidocq/mansart/pool/core/MansartDataSource.java \
        mansart-pool/mansart-pool-tests/src/test/java/io/vidocq/mansart/pool/DriverLocatorTest.java
git commit -s -S -m "feat(pool): find the JDBC driver through the application's class loader (#42)"
```

(with the two trailers of the Global Constraints in the message body)

---

### Task 2: The pool opens connections with the located driver

**Files:**
- Modify: `mansart-pool/mansart-pool-core/src/main/java/io/vidocq/mansart/pool/core/ConnectionPool.java`
  (constructor, `open()`, imports)
- Test: `mansart-pool/mansart-pool-tests/src/test/java/io/vidocq/mansart/pool/DriverInApplicationLoaderTest.java` (create)
- Docs: `docs/en/modules/ROOT/pages/modules/mansart-pool.adoc`, `docs/en/modules/ROOT/pages/whats-new.adoc`, `BUG.md`

**Interfaces:**
- Consumes: `DriverLocator.applicationLoader(Class<?>)` and `DriverLocator.find(String, ClassLoader)` from Task 1.
- Produces: no new API.

- [ ] **Step 1: Write the failing test**

The test reproduces the layered situation on a class path with two `URLClassLoader`s:
- `poolLoader` holds only `mansart-pool-api` and `mansart-pool-core`, with the platform loader as parent, so it
  cannot see H2;
- `appLoader` holds the H2 jar, with `poolLoader` as parent;
- the pool is built and used with `appLoader` as the context class loader, as Vidocq does when the application
  layer is installed.

```java
/*
 * (license header: copy the 18-line header of MansartDataSourceTest.java verbatim)
 */
package io.vidocq.mansart.pool;

import io.vidocq.mansart.pool.core.MansartDataSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The pool in one class loader, the JDBC driver in a child one: the shape of Vidocq's runtime layer and
 * application layer (mansart#42). {@code DriverManager} refuses a driver its caller cannot load, so the pool
 * must find the driver through the context class loader.
 */
class DriverInApplicationLoaderTest {

    @Test
    void aDriverOnlyTheApplicationLoaderSeesIsUsed() throws Exception {
        try (var poolLoader = poolLoader(); var appLoader = new URLClassLoader("app",
                new URL[]{location(org.h2.Driver.class)}, poolLoader)) {
            Object ds = withContextLoader(appLoader, () -> dataSource(poolLoader,
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", 0));
            try {
                assertThat(selectFortyTwo(appLoader, ds)).isEqualTo(42);
            } finally {
                ds.getClass().getMethod("close").invoke(ds);
            }
        }
    }

    @Test
    void minIdleOpensWithTheLoaderCapturedAtConstruction() throws Exception {
        try (var poolLoader = poolLoader(); var appLoader = new URLClassLoader("app",
                new URL[]{location(org.h2.Driver.class)}, poolLoader)) {
            Object ds = withContextLoader(appLoader, () -> dataSource(poolLoader,
                    "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", 2));
            try {
                Object metrics = ds.getClass().getMethod("snapshot").invoke(ds);
                assertThat(metrics.getClass().getMethod("idle").invoke(metrics)).isEqualTo(2);
                // borrowed later, on a thread whose context loader cannot see H2
                assertThat(selectFortyTwo(ClassLoader.getPlatformClassLoader(), ds)).isEqualTo(42);
            } finally {
                ds.getClass().getMethod("close").invoke(ds);
            }
        }
    }

    @Test
    void noDriverForTheUrlFailsWithAClearMessageAndNoPassword() throws Exception {
        try (var poolLoader = poolLoader()) {
            Object ds = withContextLoader(poolLoader, () -> dataSource(poolLoader, "jdbc:nosuchdb:x", 0));
            try {
                assertThatThrownBy(() -> {
                    try {
                        ds.getClass().getMethod("getConnection").invoke(ds);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }).hasMessageContaining("No JDBC driver accepts jdbc:nosuchdb:x")
                  .hasMessageNotContaining("s3cret");
            } finally {
                ds.getClass().getMethod("close").invoke(ds);
            }
        }
    }

    private static URLClassLoader poolLoader() throws Exception {
        return new URLClassLoader("pool", new URL[]{location(PoolConfig.class), location(MansartDataSource.class)},
                ClassLoader.getPlatformClassLoader());
    }

    /** A MansartDataSource built through {@code poolLoader}'s own copies of the pool classes. */
    private static Object dataSource(ClassLoader poolLoader, String url, int minIdle) throws Exception {
        Class<?> configType = poolLoader.loadClass("io.vidocq.mansart.pool.PoolConfig");
        Object builder = configType.getMethod("builder").invoke(null);
        Class<?> builderType = configType.getMethod("builder").getReturnType();
        builderType.getMethod("jdbcUrl", String.class).invoke(builder, url);
        builderType.getMethod("username", String.class).invoke(builder, "sa");
        builderType.getMethod("password", String.class).invoke(builder, "s3cret");
        builderType.getMethod("minIdle", int.class).invoke(builder, minIdle);
        builderType.getMethod("maxSize", int.class).invoke(builder, 4);
        Object config = builderType.getMethod("build").invoke(builder);
        Class<?> dataSourceType = poolLoader.loadClass("io.vidocq.mansart.pool.core.MansartDataSource");
        return dataSourceType.getMethod("of", configType).invoke(null, config);
    }

    private static int selectFortyTwo(ClassLoader contextLoader, Object ds) throws Exception {
        return withContextLoader(contextLoader, () -> {
            try (Connection c = (Connection) ds.getClass().getMethod("getConnection").invoke(ds);
                 Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT 42")) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }

    private interface Action<T> {
        T run() throws Exception;
    }

    private static <T> T withContextLoader(ClassLoader loader, Action<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return action.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private static URL location(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
```

The H2 password in the test is `s3cret`: the H2 in-memory database accepts any password on creation, and the
third test checks that the message never contains it.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -B -ntp -pl mansart-pool/mansart-pool-tests -am install -Dtest=DriverInApplicationLoaderTest -Dsurefire.failIfNoSpecifiedTests=false`

Expected:
- `aDriverOnlyTheApplicationLoaderSeesIsUsed` fails with an `InvocationTargetException` whose cause is
  `PoolException` "failed to open new connection: No suitable driver found for jdbc:h2:mem:…";
- `minIdleOpensWithTheLoaderCapturedAtConstruction` fails: idle is 0, because the pre-fill hit the same error
  and broke out silently;
- `noDriverForTheUrlFailsWithAClearMessageAndNoPassword` fails: the message is "No suitable driver …", not the
  new one.

- [ ] **Step 3: Implement**

In `ConnectionPool.java`:
- replace the import `java.sql.DriverManager` with `java.sql.Driver`;
- add two fields:

```java
    /** The application's class loader when the pool was built: where its JDBC driver is looked up (mansart#42). */
    private final ClassLoader                      driverLoader;
    /** The JDBC driver, resolved at the first {@link #open()} and kept for the pool's lifetime. */
    private volatile Driver                        driver;
```

- in the constructor, before `this.housekeeper = …`, add
  `this.driverLoader = DriverLocator.applicationLoader(ConnectionPool.class);`;
- replace `open()` and add `driver()`:

```java
    private PooledEntry open() throws SQLException {
        try {
            Connection raw = driver().connect(config.jdbcUrl(), driverProps);
            if (raw == null) {
                throw new SQLException("The JDBC driver " + driver.getClass().getName() + " accepted "
                        + config.jdbcUrl() + " but opened no connection", "08001");
            }
            return new PooledEntry(raw);
        } catch (SQLException e) {
            throw new PoolException(PoolException.Reason.CONNECT_FAILED,
                    "failed to open new connection: " + e.getMessage(), e);
        }
    }

    /** The pool's JDBC driver, found once through the loader captured at construction. */
    private Driver driver() throws SQLException {
        Driver found = driver;
        if (found == null) {
            found = DriverLocator.find(config.jdbcUrl(), driverLoader);
            driver = found;
        }
        return found;
    }
```

A race between two first opens resolves the same driver twice. That is harmless: both writes store an
equivalent driver, so no lock is needed.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -B -ntp -pl mansart-pool/mansart-pool-tests -am install`
Expected:
- BUILD SUCCESS;
- `DriverInApplicationLoaderTest` 3/3;
- `DriverLocatorTest` 3/3;
- the existing `MansartDataSourceTest`, `LeakDetectionTest`, `PoolConfigBuilderTest` and
  `XaDataSourceExposureTest` green and unchanged (Review Focus 1).

- [ ] **Step 5: Docs and BUG.md**

`mansart-pool.adoc`: add a section after the configuration section (use the file's heading level):

```asciidoc
[#finding-the-driver]
=== Finding the JDBC driver [.tag-new]#NEW#

The pool looks its JDBC driver up itself, through the class loader that was the thread's context class loader
when the pool was built: the first `java.sql.Driver` provider there that accepts the URL wins. Otherwise, the
pool falls back to a driver registered with `DriverManager`. It resolves the driver at the first connection and
keeps it.

`DriverManager.getConnection` would refuse a driver that the pool's own class loader cannot load. That is the
case under Vidocq when the driver jar sits with the application and the pool with the runtime. Before, that
failed with "No suitable driver" (mansart#42).

When no driver accepts the URL, the pool fails with a message that names the URL and where it looked:
`No JDBC driver accepts jdbc:… : searched the java.sql.Driver providers visible to … and the drivers registered
with DriverManager`. `MansartDataSource.driverClassFor(url)` tells which driver a pool built now would use.
```

`whats-new.adoc`: under a `== Pool` section (create it after the extraction note if it does not exist, at the
same level as `== Jakarta Data`), add:

```asciidoc
* **The pool finds its driver with the application** — the JDBC driver is looked up through the application's class loader, then `DriverManager`, so a driver that only the application sees works; under Vidocq this used to fail with "No suitable driver" (mansart#42). xref:modules/mansart-pool.adoc#finding-the-driver[Finding the JDBC driver].
```

`BUG.md`: append an entry with the next free `MANSART-0NN` id, in the format of the last entry. The entry:
- symptom: "No suitable driver found" when the pool's class loader cannot load the driver class, as under
  Vidocq with the driver in the application layer;
- repro: `DriverInApplicationLoaderTest`;
- cause: `DriverManager`'s caller check;
- status: FIXED (mansart#42).

- [ ] **Step 6: Commit**

```bash
git add mansart-pool/mansart-pool-core/src/main/java/io/vidocq/mansart/pool/core/ConnectionPool.java \
        mansart-pool/mansart-pool-tests/src/test/java/io/vidocq/mansart/pool/DriverInApplicationLoaderTest.java \
        docs/en/modules/ROOT/pages/modules/mansart-pool.adoc docs/en/modules/ROOT/pages/whats-new.adoc BUG.md
git commit -s -S -m "fix(pool): open connections with the driver found through the application's loader (#42)"
```

(with the two trailers of the Global Constraints in the message body)

---

### Task 3: Full verification and PR

- [ ] **Step 1: Full build, as CI runs it**

Run `CI=true ./mvnw -B -ntp install` in a fresh local repository (`-Dmaven.repo.local=<scratch>/m2-mansart42`).
Expected: BUILD SUCCESS with 0 failures. The reactor includes `mansart-data-tests` (`MansartPoolInteropTest`)
and `mansart-transactions-tests`, which use the pool with H2.

- [ ] **Step 2: Render the docs**

Render `mansart-pool.adoc` and `whats-new.adoc` to HTML (`asciidoctor -o -`), with no warnings. Then check
that `#finding-the-driver` exists and that the What's new xref points to it.

- [ ] **Step 3: Open the PR on Vidocq/mansart**

- Title: `fix(pool): find the JDBC driver through the application's class loader (#42)`.
- Body:
  - the problem;
  - the two tasks;
  - the tests, with their red reasons;
  - the full-build result;
  - a note that the Liquibase migrator (vidocq repo) is handled in Vidocq/vidocq#247;
  - the closing lines:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_01RxAdNG7RnZkC5SjtbSvMwY
```

Do not merge: the maintainer merges.
