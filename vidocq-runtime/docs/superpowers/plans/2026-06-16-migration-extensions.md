# Schema Migration Extensions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a zero-dep `SchemaMigrator` SPI plus opt-in Flyway and Liquibase backend extensions that run DB migrations at Vidocq boot, replacing per-app `FlywayMigrator`/`SchemaInitializer` boilerplate.

**Architecture:** One runner `VidocqExtension` (priority 150, before the pool) reads `vidocq.pool[.<name>].url|username|password` from config, builds a `MigrationTarget` per datasource (`@Default` always, named ones opt-in via `vidocq.migration.<name>.locations`), resolves a single `SchemaMigrator` via `ServiceLoader`, and invokes it — fail-fast at boot. Flyway and Liquibase are separate opt-in modules each providing a `SchemaMigrator`. No runtime `DataSource` dependency.

**Tech Stack:** Java 25, Maven 3.9.16, JPMS (module-path), `flyway-core` + `flyway-database-postgresql` (Apache 2.0), `liquibase-core` (Apache 2.0), H2 + Testcontainers (tests).

**Conventions:** Every `.java`/`.xml` starts with the standard EPL/EUPL/GPL license header used across the repo (copy it from `vidocq-runtime-chappe-webserver-extension/src/main/java/module-info.java`). All code/comments/Javadoc in English. Branch: `feat/migration-extensions` (already created). Commits: English, no AI mention; the global hook adds the DCO `Signed-off-by` + GPG signature.

---

## File Structure

**New module `vidocq-runtime-migration-extension`** (`vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension/`):
- `pom.xml` — zero-dep extension module.
- `src/main/java/module-info.java` — exports the SPI package, provides the runner, `uses SchemaMigrator`.
- `src/main/java/io/vidocq/runtime/extensions/essentials/migration/SchemaMigrator.java` — SPI interface.
- `.../migration/MigrationTarget.java` — record.
- `.../migration/MigrationResult.java` — record.
- `.../migration/MigrationExtension.java` — the runner `VidocqExtension`.
- `src/test/java/.../migration/MigrationExtensionTest.java` — target-building + backend-selection unit tests.

**New module `vidocq-runtime-flyway-migration-extension`** (sibling dir):
- `pom.xml`, `src/main/java/module-info.java`, `.../migration/flyway/FlywaySchemaMigrator.java`.
- `src/test/java/.../migration/flyway/FlywaySchemaMigratorTest.java` (+ `FlywaySchemaMigratorPostgresIT.java`).
- `src/test/resources/db/testmigration/V1__create_widget.sql`.

**New module `vidocq-runtime-liquibase-migration-extension`** (sibling dir):
- `pom.xml`, `src/main/java/module-info.java`, `.../migration/liquibase/LiquibaseSchemaMigrator.java`.
- `src/test/java/.../migration/liquibase/LiquibaseSchemaMigratorTest.java` (+ `...PostgresIT.java`).
- `src/test/resources/db/testchangelog/db.changelog-master.xml`.

**Modified:**
- `vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/pom.xml` — add 3 `<module>`s.
- `pom.xml` (root) — add `flyway.version`/`liquibase.version` properties + `dependencyManagement` for the 5 new artifacts.
- `vidocq-runtime-examples/vidocq-runtime-mansart-h2-example/` — adopt the Flyway extension (Task 7).
- `DEV_SERVICES.md` — migration section (Task 8).

---

## Task 1: SPI module scaffold + types

**Files:**
- Create: `.../vidocq-runtime-migration-extension/pom.xml`
- Create: `.../vidocq-runtime-migration-extension/src/main/java/module-info.java`
- Create: `.../migration/SchemaMigrator.java`, `MigrationTarget.java`, `MigrationResult.java`
- Modify: `vidocq-runtime-extensions-essentials/pom.xml`, root `pom.xml`

- [ ] **Step 1: Create the SPI types** (license header on each file)

`.../migration/SchemaMigrator.java`:
```java
package io.vidocq.runtime.extensions.essentials.migration;

/**
 * A schema-migration backend. Discovered via {@link java.util.ServiceLoader}; an application puts
 * exactly one provider (the Flyway or the Liquibase extension) on its path.
 */
public interface SchemaMigrator {
    /** Backend id, e.g. {@code "flyway"} or {@code "liquibase"}. */
    String engine();

    /** Runs the migration against {@code target}; throws to abort the boot on failure. */
    MigrationResult migrate(MigrationTarget target);
}
```

`.../migration/MigrationTarget.java`:
```java
package io.vidocq.runtime.extensions.essentials.migration;

import java.util.List;

/**
 * One datasource to migrate. {@code locations} are Flyway locations or, for Liquibase, the changelog
 * path in element 0; an empty list means "use the backend default".
 */
public record MigrationTarget(String dataSourceName, String jdbcUrl, String username, String password,
                              List<String> locations) {
}
```

`.../migration/MigrationResult.java`:
```java
package io.vidocq.runtime.extensions.essentials.migration;

/** Outcome summary for logging. {@code applied} = number of migrations/changesets run. */
public record MigrationResult(int applied, String version) {
}
```

- [ ] **Step 2: Create `module-info.java`**

```java
module io.vidocq.runtime.extensions.essentials.migration {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;

    exports io.vidocq.runtime.extensions.essentials.migration;

    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.essentials.migration.MigrationExtension;

    uses io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
}
```
(`MigrationExtension` is created in Task 2 — this module-info will not compile until then; that is expected and fixed in Task 2. To keep Task 1 self-contained, temporarily omit the `provides` line and add it in Task 2 Step 1.)

For Task 1 only, use this `module-info.java` (no `provides` yet):
```java
module io.vidocq.runtime.extensions.essentials.migration {
    requires transitive io.vidocq.runtime.spi;
    requires io.vidocq.vauban.core;
    exports io.vidocq.runtime.extensions.essentials.migration;
    uses io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
}
```

- [ ] **Step 3: Create the module `pom.xml`** (mirror `vidocq-runtime-mansart-pool-extension/pom.xml` dependency style)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.vidocq.runtime.extensions.essentials</groupId>
        <artifactId>vidocq-runtime-extensions-essentials</artifactId>
        <version>0.2.0-SNAPSHOT</version>
    </parent>
    <artifactId>vidocq-runtime-migration-extension</artifactId>
    <name>Vidocq :: Core Extensions :: Schema Migration SPI</name>
    <description>Zero-dep SchemaMigrator SPI + the runner extension that applies migrations at boot from
        vidocq.pool[.&lt;name&gt;].* coordinates. Backends (Flyway, Liquibase) are separate opt-in modules.</description>
    <dependencies>
        <dependency>
            <groupId>io.vidocq.runtime</groupId>
            <artifactId>vidocq-runtime-spi</artifactId>
        </dependency>
        <dependency>
            <groupId>io.vidocq.vauban</groupId>
            <artifactId>vauban-core</artifactId>
        </dependency>
        <!-- Same caveat as the other extensions: vauban-core's module-info requires (non-static)
             classloader-spi, mandatory at module-path resolution even though optional in vauban-core's pom. -->
        <dependency>
            <groupId>io.vidocq.vauban</groupId>
            <artifactId>vauban-classloader-spi</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: Register the module + depMgmt**

In `vidocq-runtime-extensions-essentials/pom.xml`, inside `<modules>`, after `vidocq-runtime-chappe-webserver-extension`:
```xml
        <module>vidocq-runtime-migration-extension</module>
        <module>vidocq-runtime-flyway-migration-extension</module>
        <module>vidocq-runtime-liquibase-migration-extension</module>
```
In root `pom.xml` `<properties>`, add:
```xml
        <flyway.version>11.1.0</flyway.version>
        <liquibase.version>4.31.1</liquibase.version>
```
In root `pom.xml` `<dependencyManagement><dependencies>`, add (group the three vidocq modules near the other `io.vidocq.runtime.extensions.essentials` entries, the libs anywhere):
```xml
            <dependency>
                <groupId>io.vidocq.runtime.extensions.essentials</groupId>
                <artifactId>vidocq-runtime-migration-extension</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.runtime.extensions.essentials</groupId>
                <artifactId>vidocq-runtime-flyway-migration-extension</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>io.vidocq.runtime.extensions.essentials</groupId>
                <artifactId>vidocq-runtime-liquibase-migration-extension</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-core</artifactId>
                <version>${flyway.version}</version>
            </dependency>
            <dependency>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-database-postgresql</artifactId>
                <version>${flyway.version}</version>
            </dependency>
            <dependency>
                <groupId>org.liquibase</groupId>
                <artifactId>liquibase-core</artifactId>
                <version>${liquibase.version}</version>
            </dependency>
```
> The flyway/liquibase modules do not exist yet (Tasks 3/5). To let the reactor parse now, create empty placeholder dirs is NOT needed — instead, add only `vidocq-runtime-migration-extension` to `<modules>` in this task, and add the other two `<module>` lines in Tasks 3 and 5 respectively. Do the same for depMgmt: add the flyway/liquibase entries in Tasks 3/5. (Keep this task's reactor buildable.)

- [ ] **Step 5: Build the SPI module**

Run: `./mvnw -ntp -q -o install -pl :vidocq-runtime-migration-extension -DskipTests` (drop `-o` if it needs to resolve).
Expected: `BUILD SUCCESS`, the jar installs to local M2.

- [ ] **Step 6: Commit**

```bash
git add vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/vidocq-runtime-migration-extension \
        vidocq-runtime-extensions/vidocq-runtime-extensions-essentials/pom.xml pom.xml
git commit -m "feat(migration): SchemaMigrator SPI + zero-dep migration-extension module skeleton"
```

---

## Task 2: The runner `MigrationExtension` (TDD)

**Files:**
- Create: `.../migration/MigrationExtension.java`
- Test: `.../migration/MigrationExtensionTest.java`
- Modify: `module-info.java` (add the `provides`)

- [ ] **Step 1: Write the failing test**

`src/test/java/io/vidocq/runtime/extensions/essentials/migration/MigrationExtensionTest.java`:
```java
package io.vidocq.runtime.extensions.essentials.migration;

import io.vidocq.runtime.spi.VidocqConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationExtensionTest {

    @Test
    void defaultTargetIsBuiltFromPoolUrl() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:x", "vidocq.pool.username", "sa")));
        assertEquals(1, targets.size());
        assertEquals("default", targets.get(0).dataSourceName());
        assertEquals("jdbc:h2:mem:x", targets.get(0).jdbcUrl());
        assertEquals("sa", targets.get(0).username());
        assertTrue(targets.get(0).locations().isEmpty(), "empty → backend default");
    }

    @Test
    void namedDatasourceIsOptInViaLocations() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit",
                "vidocq.migration.audit.locations", "classpath:db/audit")));
        assertEquals(List.of("default", "audit"),
                targets.stream().map(MigrationTarget::dataSourceName).toList());
        var audit = targets.stream().filter(t -> t.dataSourceName().equals("audit")).findFirst().orElseThrow();
        assertEquals(List.of("classpath:db/audit"), audit.locations());
    }

    @Test
    void namedDatasourceWithoutLocationsIsSkipped() {
        var targets = MigrationExtension.buildTargets(MapConfig.of(Map.of(
                "vidocq.pool.url", "jdbc:h2:mem:def",
                "vidocq.pool.audit.url", "jdbc:h2:mem:audit")));
        assertEquals(List.of("default"), targets.stream().map(MigrationTarget::dataSourceName).toList());
    }

    @Test
    void selectFailsWhenNoBackend() {
        assertThrows(IllegalStateException.class,
                () -> MigrationExtension.select(List.of(), Optional.empty()));
    }

    @Test
    void selectReturnsTheSoleBackend() {
        SchemaMigrator m = fake("flyway");
        assertSame(m, MigrationExtension.select(List.of(m), Optional.empty()));
    }

    @Test
    void selectDisambiguatesByEngineWhenSeveral() {
        SchemaMigrator fly = fake("flyway");
        SchemaMigrator liq = fake("liquibase");
        assertSame(liq, MigrationExtension.select(List.of(fly, liq), Optional.of("liquibase")));
        assertThrows(IllegalStateException.class,
                () -> MigrationExtension.select(List.of(fly, liq), Optional.empty()));
    }

    private static SchemaMigrator fake(String engine) {
        return new SchemaMigrator() {
            @Override public String engine() { return engine; }
            @Override public MigrationResult migrate(MigrationTarget t) { return new MigrationResult(0, "x"); }
        };
    }

    /** Test double for VidocqConfiguration backed by a plain map. */
    private record MapConfig(Map<String, String> data) implements VidocqConfiguration {
        static MapConfig of(Map<String, String> data) { return new MapConfig(data); }
        @Override public Optional<String> property(String key) { return Optional.ofNullable(data.get(key)); }
        @Override public Iterable<String> propertyNames() { return data.keySet(); }
    }
}
```

- [ ] **Step 2: Run it — expect compile failure** (`MigrationExtension` not found)

Run: `./mvnw -ntp -q test -pl :vidocq-runtime-migration-extension`
Expected: compilation failure (`MigrationExtension` / `buildTargets` / `select` do not exist).

- [ ] **Step 3: Implement `MigrationExtension`**

`src/main/java/io/vidocq/runtime/extensions/essentials/migration/MigrationExtension.java`:
```java
package io.vidocq.runtime.extensions.essentials.migration;

import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * Applies schema migrations at boot. Priority 150 — after the Chappe transport (100), before the
 * Mansart pool (200) — so the schema is ready before anything connects. Migrates from the
 * {@code vidocq.pool[.<name>].url|username|password} coordinates directly (Flyway/Liquibase manage
 * their own connection), so it needs no runtime {@link javax.sql.DataSource} and is decoupled from
 * mansart-pool. The {@code @Default} datasource is migrated whenever {@code vidocq.pool.url} is set;
 * a named datasource is migrated only when it sets {@code vidocq.migration.<name>.locations}.
 */
public final class MigrationExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(MigrationExtension.class.getName());

    private static final String PREFIX = "vidocq.migration.";
    private static final String POOL_PREFIX = "vidocq.pool.";
    private static final String LOCATIONS_SUFFIX = ".locations";

    private SchemaMigrator migrator;
    private List<MigrationTarget> targets = List.of();

    @Override public String name() { return "migration"; }
    @Override public int priority() { return 150; }

    @Override
    public void configure(VidocqConfiguration cfg) {
        if (!cfg.property(PREFIX + "enabled").map(Boolean::parseBoolean).orElse(true)) {
            LOG.log(System.Logger.Level.DEBUG, "Migration disabled (vidocq.migration.enabled=false)");
            return;
        }
        this.targets = buildTargets(cfg);
        if (targets.isEmpty()) {
            return;
        }
        List<SchemaMigrator> found = new ArrayList<>();
        ServiceLoader.load(SchemaMigrator.class, MigrationExtension.class.getClassLoader())
                .forEach(found::add);
        this.migrator = select(found, cfg.property(PREFIX + "engine"));
        LOG.log(System.Logger.Level.INFO,
                "Migration configured: engine=" + migrator.engine()
                        + " datasources=" + targets.stream().map(MigrationTarget::dataSourceName).toList());
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        if (migrator == null || targets.isEmpty()) {
            return;
        }
        for (MigrationTarget t : targets) {
            MigrationResult r = migrator.migrate(t); // throws on failure → boot aborts (fail fast)
            LOG.log(System.Logger.Level.INFO,
                    "Migration[" + migrator.engine() + "] '" + t.dataSourceName()
                            + "': applied " + r.applied() + ", version " + r.version());
        }
    }

    // ---- pure helpers (visible for tests) ----

    static List<MigrationTarget> buildTargets(VidocqConfiguration cfg) {
        List<MigrationTarget> out = new ArrayList<>();
        cfg.property(POOL_PREFIX + "url").ifPresent(url ->
                out.add(target(cfg, "default", POOL_PREFIX, PREFIX + "locations")));
        for (String name : namedWithLocations(cfg)) {
            String poolPrefix = POOL_PREFIX + name + ".";
            if (cfg.property(poolPrefix + "url").isPresent()) {
                out.add(target(cfg, name, poolPrefix, PREFIX + name + LOCATIONS_SUFFIX));
            }
        }
        return out;
    }

    private static MigrationTarget target(VidocqConfiguration cfg, String name, String poolPrefix, String locKey) {
        return new MigrationTarget(name,
                cfg.property(poolPrefix + "url").orElseThrow(),
                cfg.property(poolPrefix + "username").orElse(null),
                cfg.property(poolPrefix + "password").orElse(null),
                cfg.property(locKey).map(s -> List.of(s.split("\\s*,\\s*"))).orElse(List.of()));
    }

    private static TreeSet<String> namedWithLocations(VidocqConfiguration cfg) {
        TreeSet<String> names = new TreeSet<>();
        for (String key : cfg.propertyNames()) {
            if (key.startsWith(PREFIX) && key.endsWith(LOCATIONS_SUFFIX)) {
                String name = key.substring(PREFIX.length(), key.length() - LOCATIONS_SUFFIX.length());
                if (!name.isEmpty() && name.indexOf('.') < 0) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    static SchemaMigrator select(List<SchemaMigrator> found, Optional<String> engine) {
        if (found.isEmpty()) {
            throw new IllegalStateException("vidocq.migration: migrations configured but no SchemaMigrator "
                    + "backend on the path — add the flyway or liquibase migration extension");
        }
        if (found.size() == 1) {
            return found.get(0);
        }
        return engine
                .flatMap(e -> found.stream().filter(m -> m.engine().equalsIgnoreCase(e)).findFirst())
                .orElseThrow(() -> new IllegalStateException("vidocq.migration: multiple backends present "
                        + found.stream().map(SchemaMigrator::engine).toList()
                        + " — set vidocq.migration.engine to one of them"));
    }
}
```

- [ ] **Step 4: Add the `provides` line back to `module-info.java`**

```java
    provides io.vidocq.runtime.spi.VidocqExtension
            with io.vidocq.runtime.extensions.essentials.migration.MigrationExtension;
```

- [ ] **Step 5: Run tests — expect PASS**

Run: `./mvnw -ntp -q clean install -pl :vidocq-runtime-migration-extension`
Expected: `Tests run: 6, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add vidocq-runtime-extensions/.../vidocq-runtime-migration-extension
git commit -m "feat(migration): runner extension — config→targets, backend selection, fail-fast boot"
```

---

## Task 3: Flyway backend module (TDD on H2)

**Files:**
- Create module dir `vidocq-runtime-flyway-migration-extension/` with `pom.xml`, `module-info.java`, `FlywaySchemaMigrator.java`.
- Test: `FlywaySchemaMigratorTest.java`, resource `db/testmigration/V1__create_widget.sql`.
- Modify: essentials `pom.xml` (the `<module>` line — if not already added in Task 1), root `pom.xml` depMgmt (flyway entries).

- [ ] **Step 1: Create the module `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.vidocq.runtime.extensions.essentials</groupId>
        <artifactId>vidocq-runtime-extensions-essentials</artifactId>
        <version>0.2.0-SNAPSHOT</version>
    </parent>
    <artifactId>vidocq-runtime-flyway-migration-extension</artifactId>
    <name>Vidocq :: Core Extensions :: Flyway Migration</name>
    <description>Flyway SchemaMigrator backend (Apache 2.0). Opt-in: add this jar to migrate at boot.</description>
    <dependencies>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-migration-extension</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <version>2.3.232</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```
If not done in Task 1: add `<module>vidocq-runtime-flyway-migration-extension</module>` to essentials `pom.xml`, and the flyway depMgmt entries (Task 1 Step 4 block) to the root `pom.xml`.

- [ ] **Step 2: Create `module-info.java`**

```java
module io.vidocq.runtime.extensions.essentials.migration.flyway {
    requires io.vidocq.runtime.extensions.essentials.migration;
    requires flyway.core;
    requires flyway.database.postgresql;

    provides io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator
            with io.vidocq.runtime.extensions.essentials.migration.flyway.FlywaySchemaMigrator;
}
```

- [ ] **Step 3: Write the failing test + resource**

`src/test/resources/db/testmigration/V1__create_widget.sql`:
```sql
CREATE TABLE widget (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL);
```

`src/test/java/io/vidocq/runtime/extensions/essentials/migration/flyway/FlywaySchemaMigratorTest.java`:
```java
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywaySchemaMigratorTest {

    @Test
    void migratesH2FromClasspathLocation() throws Exception {
        String url = "jdbc:h2:mem:flyway-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        MigrationResult r = new FlywaySchemaMigrator().migrate(
                new MigrationTarget("default", url, "sa", "", List.of("classpath:db/testmigration")));
        assertEquals(1, r.applied());
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM widget")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }
}
```
> Surefire for this module must run on the classpath (Flyway scans `classpath:` locations and resolves the H2 driver more predictably off the module path). Add to the module `pom.xml` `<build><plugins>`: the `maven-surefire-plugin` with `<useModulePath>false</useModulePath>` (copy the block from `vidocq-runtime-mansart-pool-datasources-codegen/pom.xml`).

- [ ] **Step 4: Run — expect compile failure** (`FlywaySchemaMigrator` not found)

Run: `./mvnw -ntp -q test -pl :vidocq-runtime-flyway-migration-extension -am`
Expected: compilation failure.

- [ ] **Step 5: Implement `FlywaySchemaMigrator`**

```java
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** Flyway-backed {@link SchemaMigrator}. */
public final class FlywaySchemaMigrator implements SchemaMigrator {

    private static final String DEFAULT_LOCATION = "classpath:db/migration";

    @Override
    public String engine() {
        return "flyway";
    }

    @Override
    public MigrationResult migrate(MigrationTarget t) {
        String[] locations = t.locations().isEmpty()
                ? new String[]{DEFAULT_LOCATION}
                : t.locations().toArray(String[]::new);
        MigrateResult r = Flyway.configure(getClass().getClassLoader())
                .dataSource(t.jdbcUrl(), t.username(), t.password())
                .locations(locations)
                .load()
                .migrate();
        return new MigrationResult(r.migrationsExecuted,
                r.targetSchemaVersion == null ? "(none)" : r.targetSchemaVersion);
    }
}
```

- [ ] **Step 6: Run tests — expect PASS**

Run: `./mvnw -ntp -q clean install -pl :vidocq-runtime-flyway-migration-extension -am`
Expected: `Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add vidocq-runtime-extensions/.../vidocq-runtime-flyway-migration-extension \
        vidocq-runtime-extensions/.../vidocq-runtime-extensions-essentials/pom.xml pom.xml
git commit -m "feat(migration): Flyway SchemaMigrator backend (opt-in, Apache 2.0)"
```

---

## Task 4: Flyway Postgres IT (Docker-gated)

**Files:** Create `FlywaySchemaMigratorPostgresIT.java`; add `org.testcontainers:testcontainers-postgresql` (test) to the flyway module `pom.xml`. ⚠️ Testcontainers **2.x** artifact names are `testcontainers-postgresql` (see `reference_testcontainers_orbstack_macos`). Use the version managed by the root BOM; if none, pin `2.0.3`.

- [ ] **Step 1: Add the test dependency** to the flyway module `pom.xml`:
```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <version>2.0.3</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Write the Docker-gated IT**

```java
package io.vidocq.runtime.extensions.essentials.migration.flyway;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FlywaySchemaMigratorPostgresIT {

    @Test
    @Timeout(240)
    void migratesPostgres() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping");
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
            pg.start();
            MigrationResult r = new FlywaySchemaMigrator().migrate(new MigrationTarget(
                    "default", pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(),
                    List.of("classpath:db/testmigration")));
            assertEquals(1, r.applied());
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
                 var s = c.createStatement();
                 var rs = s.executeQuery("SELECT COUNT(*) FROM widget")) {
                assertTrue(rs.next());
            }
        }
    }
}
```

- [ ] **Step 3: Run** (skips cleanly without Docker)

Run: `./mvnw -ntp -q test -pl :vidocq-runtime-flyway-migration-extension`
Expected: with Docker → `Tests run: 2` (1 + IT); without → IT `Skipped`. `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add vidocq-runtime-extensions/.../vidocq-runtime-flyway-migration-extension
git commit -m "test(migration): Docker-gated Postgres IT for the Flyway backend"
```

---

## Task 5: Liquibase backend module — POC first, then impl (TDD on H2)

⚠️ **Liquibase + JPMS is the riskiest piece.** Step 5 is a module-path smoke check (the POC) before relying on it.

**Files:** module dir with `pom.xml`, `module-info.java`, `LiquibaseSchemaMigrator.java`; test `LiquibaseSchemaMigratorTest.java`; resources `db/testchangelog/db.changelog-master.xml` + `db/testchangelog/V1.sql`.

- [ ] **Step 1: Create the module `pom.xml`** (mirror Task 3 Step 1, swap artifactId → `vidocq-runtime-liquibase-migration-extension`, name → "Liquibase Migration", and replace the two flyway deps with one `org.liquibase:liquibase-core`; keep junit + h2 test deps + `<useModulePath>false</useModulePath>` surefire). Add its `<module>` line + the `liquibase-core` depMgmt entry (Task 1 Step 4) if not present.

- [ ] **Step 2: Create `module-info.java`**

```java
module io.vidocq.runtime.extensions.essentials.migration.liquibase {
    requires io.vidocq.runtime.extensions.essentials.migration;
    requires liquibase.core;
    requires java.sql;

    provides io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator
            with io.vidocq.runtime.extensions.essentials.migration.liquibase.LiquibaseSchemaMigrator;
}
```
> If `liquibase-core` is **not** an automatic module named `liquibase.core` (check `jar --describe-module --file <liquibase-core.jar>` in M2), use the real Automatic-Module-Name it reports and adjust this `requires`. Record the finding in `BUG.md` if it differs.

- [ ] **Step 3: Create the changelog test resources**

`src/test/resources/db/testchangelog/db.changelog-master.xml`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
            http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
    <changeSet id="1" author="vidocq">
        <createTable tableName="widget">
            <column name="id" type="INT"><constraints primaryKey="true"/></column>
            <column name="name" type="VARCHAR(100)"><constraints nullable="false"/></column>
        </createTable>
    </changeSet>
</databaseChangeLog>
```

- [ ] **Step 4: Write the failing test**

```java
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquibaseSchemaMigratorTest {

    @Test
    void migratesH2FromChangelog() throws Exception {
        String url = "jdbc:h2:mem:lb-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        MigrationResult r = new LiquibaseSchemaMigrator().migrate(new MigrationTarget(
                "default", url, "sa", "", List.of("db/testchangelog/db.changelog-master.xml")));
        assertEquals(1, r.applied());
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM widget")) {
            assertTrue(rs.next());
        }
    }
}
```

- [ ] **Step 5: Implement `LiquibaseSchemaMigrator`**

```java
package io.vidocq.runtime.extensions.essentials.migration.liquibase;

import io.vidocq.runtime.extensions.essentials.migration.MigrationResult;
import io.vidocq.runtime.extensions.essentials.migration.MigrationTarget;
import io.vidocq.runtime.extensions.essentials.migration.SchemaMigrator;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;

/** Liquibase-backed {@link SchemaMigrator}. {@code locations.get(0)} is the changelog path. */
public final class LiquibaseSchemaMigrator implements SchemaMigrator {

    private static final String DEFAULT_CHANGELOG = "db/changelog/db.changelog-master.xml";

    @Override
    public String engine() {
        return "liquibase";
    }

    @Override
    public MigrationResult migrate(MigrationTarget t) {
        String changelog = t.locations().isEmpty() ? DEFAULT_CHANGELOG : t.locations().get(0);
        try (Connection conn = DriverManager.getConnection(t.jdbcUrl(), t.username(), t.password())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(changelog,
                    new ClassLoaderResourceAccessor(getClass().getClassLoader()), database)) {
                int toRun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).size();
                liquibase.update(new Contexts(), new LabelExpression());
                return new MigrationResult(toRun, "(liquibase)");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Liquibase migration failed for '" + t.dataSourceName() + "': " + e.getMessage(), e);
        }
    }
}
```
> Liquibase 4.x classic API. If the installed `liquibase-core` removed/changed `new Liquibase(String, ResourceAccessor, Database)` or `listUnrunChangeSets`, switch to the `CommandScope("update")` API and keep the same `MigrationResult` shape (set `applied` to `0` if the count is not cheaply available). The test is the oracle.

- [ ] **Step 6: Run tests — expect PASS**

Run: `./mvnw -ntp -q clean install -pl :vidocq-runtime-liquibase-migration-extension -am`
Expected: `Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`. **If the build fails on module resolution** (`liquibase.core` not found / split package / reads), this is the JPMS POC surfacing: fix `requires`/the module name, document in `BUG.md`, and re-run before continuing.

- [ ] **Step 7: Commit**

```bash
git add vidocq-runtime-extensions/.../vidocq-runtime-liquibase-migration-extension \
        vidocq-runtime-extensions/.../vidocq-runtime-extensions-essentials/pom.xml pom.xml
git commit -m "feat(migration): Liquibase SchemaMigrator backend (opt-in, Apache 2.0)"
```

---

## Task 6: Liquibase Postgres IT (Docker-gated)

- [ ] **Step 1:** Add `org.testcontainers:testcontainers-postgresql:2.0.3` (test) to the liquibase module `pom.xml`.
- [ ] **Step 2:** Create `LiquibaseSchemaMigratorPostgresIT.java` — copy Task 4 Step 2 verbatim, replacing `FlywaySchemaMigrator` → `LiquibaseSchemaMigrator` and the locations arg with `List.of("db/testchangelog/db.changelog-master.xml")`.
- [ ] **Step 3:** Run `./mvnw -ntp -q test -pl :vidocq-runtime-liquibase-migration-extension` — Docker present → 2 tests; absent → IT skipped. `BUILD SUCCESS`.
- [ ] **Step 4:** Commit: `git commit -m "test(migration): Docker-gated Postgres IT for the Liquibase backend"`.

---

## Task 7: Adopt the extension in `mansart-h2-example` (E2E)

Replace the `@Default` half of `SchemaInitializer` with Flyway via the extension. Keep the named-`audit` schema in `SchemaInitializer` (named-DS migration is opt-in and out of this example's scope), but drop the `products` DDL.

**Files:**
- Modify: `vidocq-runtime-mansart-h2-example/pom.xml` (add the flyway migration extension dep).
- Modify: `vidocq-runtime-mansart-h2-example/src/main/java/module-info.java` (`requires` the two extension modules + `opens db.migration;`).
- Create: `vidocq-runtime-mansart-h2-example/src/main/resources/db/migration/V1__products.sql`.
- Modify: `SchemaInitializer.java` (remove the `products` DDL + seed; keep the `audit` table).

- [ ] **Step 1:** Add to `mansart-h2-example/pom.xml` `<dependencies>`:
```xml
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-migration-extension</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.vidocq.runtime.extensions.essentials</groupId>
            <artifactId>vidocq-runtime-flyway-migration-extension</artifactId>
            <version>${project.version}</version>
        </dependency>
```

- [ ] **Step 2:** Create `src/main/resources/db/migration/V1__products.sql`:
```sql
CREATE TABLE "products" (
  "id" BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  "name" VARCHAR(200) NOT NULL,
  "price" DOUBLE NOT NULL
);
INSERT INTO "products"("name", "price") VALUES ('Espresso', 2.50), ('Cappuccino', 3.50), ('Latte', 4.00);
```

- [ ] **Step 3:** In `mansart-h2-example/src/main/java/module-info.java`, add:
```java
    requires io.vidocq.runtime.extensions.essentials.migration;
    requires io.vidocq.runtime.extensions.essentials.migration.flyway;
    // Flyway (automatic module) reads SQL resources; a named module encapsulates them, so open the package.
    opens db.migration;
```

- [ ] **Step 4:** In `SchemaInitializer.java`, delete the `products` DROP/CREATE/INSERT statements (Flyway now owns that table); keep the `audit_log` block (named DS, not migrated). Leave the `@Default` `dataSourceInstance` only if still used elsewhere — if the `products` removal makes it unused, also remove `dataSourceInstance` and its `Instance<DataSource>` import.

- [ ] **Step 5:** Build the example E2E.

Run: `./mvnw -ntp -q clean install -pl :vidocq-runtime-mansart-h2-example -am -DskipTests`
Expected: `BUILD SUCCESS`. (Boots are validated manually with `mvn vidocq:dev`; not part of CI here.)

- [ ] **Step 6:** Commit:
```bash
git add vidocq-runtime-examples/vidocq-runtime-mansart-h2-example
git commit -m "docs(example): migrate the @Default schema via the Flyway extension (drop ad-hoc DDL)"
```

---

## Task 8: Docs + full-reactor verification

- [ ] **Step 1:** Add a **"Schema migrations"** section to `DEV_SERVICES.md` (root) documenting: add one backend module (`vidocq-runtime-flyway-migration-extension` **or** `-liquibase-`), put scripts under `src/main/resources/db/migration` (Flyway) / a changelog (Liquibase), **`opens db.migration;`** in the app's `module-info`, config keys (`vidocq.migration.enabled|engine|locations|<name>.locations`), JDBC coords reused from `vidocq.pool[.<name>].*`, fail-fast at boot, one backend per app.

- [ ] **Step 2:** Reactor-wide checkpom + compile sanity:

Run: `./mvnw -ntp -q validate` (checkpom on every module — confirms no `*-extension-codegen` convention was tripped).
Expected: `BUILD SUCCESS`, no `[ERROR]`.

- [ ] **Step 3:** Build the three new modules + the example together:

Run: `./mvnw -ntp clean install -pl :vidocq-runtime-migration-extension,:vidocq-runtime-flyway-migration-extension,:vidocq-runtime-liquibase-migration-extension,:vidocq-runtime-mansart-h2-example -am`
Expected: all green (`MigrationExtensionTest` 6, Flyway 1–2, Liquibase 1–2, example builds).

- [ ] **Step 4:** Commit docs:
```bash
git add DEV_SERVICES.md
git commit -m "docs(devservices): document the schema-migration extensions"
```

---

## Self-Review

**Spec coverage:** §4.1 SPI+runner → Tasks 1–2; §4.2 Flyway → Tasks 3–4; §4.3 Liquibase (POC-first) → Task 5; §5 config keys → Task 2 (`buildTargets`/`select`) + Task 8 docs; §6 JPMS (`opens db.migration`) → Tasks 5(note)/7; §7 fail-fast/absent-scripts → Task 2 (`migrate` throws) + Liquibase note; §8 licenses → poms (Apache 2.0 deps); §9 testing → Tasks 2/3/4/5/6/7; §10 staging order → task order. All covered.

**Placeholder scan:** No "TBD/TODO". The "if liquibase API differs" / "if module name differs" notes are explicit fallbacks with the test as oracle, not placeholders. The Liquibase version `4.31.1` and Flyway `11.1.0` are concrete (Arago uses Flyway 11.1.0); the executing agent pins the nearest resolvable if absent.

**Type consistency:** `SchemaMigrator.engine()/migrate()`, `MigrationTarget(dataSourceName,jdbcUrl,username,password,locations)`, `MigrationResult(applied,version)`, `MigrationExtension.buildTargets()/select(List,Optional)` are used identically across Tasks 1, 2, 3, 5. `vidocq.migration.<name>.locations` / `vidocq.pool[.<name>].url|username|password` consistent throughout.

**Note on Task 1 vs 3/5 reactor staging:** add each module's `<module>` line and its depMgmt entries in the task that creates it (not all up front), so every task's reactor parses and builds.
