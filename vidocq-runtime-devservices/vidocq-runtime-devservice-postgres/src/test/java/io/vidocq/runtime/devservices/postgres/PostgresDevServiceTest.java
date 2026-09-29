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
package io.vidocq.runtime.devservices.postgres;

import io.vidocq.runtime.devservices.spi.DevServiceContext;
import io.vidocq.runtime.devservices.spi.DevServiceState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PostgresDevServiceTest {

    @Test
    void appliesWhenNoPoolUrlIsConfigured() {
        assertTrue(new PostgresDevService().appliesWhen(ctx(Map.of())));
    }

    @Test
    void optsOutWhenPoolUrlIsAlreadyConfigured() {
        DevServiceContext ctx = ctx(Map.of("vidocq.pool.url", "jdbc:postgresql://db.example:5432/app"));
        assertFalse(new PostgresDevService().appliesWhen(ctx));
    }

    @Test
    @Timeout(180)
    void startsContainerAndPublishesAReachablePool() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping");

        PostgresDevService svc = new PostgresDevService();
        try {
            Map<String, String> props = svc.start(ctx(Map.of("vidocq.dev.postgres.db", "arago")));

            String url = props.get("vidocq.pool.url");
            assertTrue(url != null && url.startsWith("jdbc:postgresql://"), "got " + url);
            assertEquals("vidocq", props.get("vidocq.pool.username"));
            assertEquals("vidocq", props.get("vidocq.pool.password"));

            // Prove the endpoint is live without dragging in a JDBC driver: open a TCP socket
            // to the host:port encoded in the JDBC URL.
            URI uri = URI.create(url.substring("jdbc:".length())); // postgresql://host:port/db?...
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 5000);
                assertTrue(s.isConnected());
            }
        } finally {
            svc.stop();
        }
    }

    // ---- planning (pure, no Docker) ----

    @Test
    void planHasOnlyTheDefaultDatasourceByDefault() {
        List<PostgresDevService.DatasourcePlan> plan = PostgresDevService.plan(ctx(Map.of()));
        assertEquals(1, plan.size());
        assertEquals("default", plan.get(0).name());
        assertEquals("vidocq.pool.", plan.get(0).poolPrefix());
        assertEquals("vidocq", plan.get(0).db());
        assertNull(plan.get(0).fixedPort(), "random host port by default");
    }

    @Test
    void planAddsNamedDatasourcesFromTheList() {
        List<PostgresDevService.DatasourcePlan> plan = PostgresDevService.plan(ctx(Map.of(
                "vidocq.dev.postgres.datasources", "analytics, audit")));
        assertEquals(List.of("default", "analytics", "audit"),
                plan.stream().map(PostgresDevService.DatasourcePlan::name).toList());
        PostgresDevService.DatasourcePlan analytics = named(plan, "analytics");
        assertEquals("vidocq.pool.analytics.", analytics.poolPrefix());
        assertEquals("analytics", analytics.db(), "db defaults to the datasource name");
    }

    @Test
    void planExcludesExplicitlyConfiguredDatasources() {
        List<PostgresDevService.DatasourcePlan> plan = PostgresDevService.plan(ctx(Map.of(
                "vidocq.pool.url", "jdbc:postgresql://ext/app",            // @Default configured → skip
                "vidocq.dev.postgres.datasources", "analytics,audit",
                "vidocq.pool.audit.url", "jdbc:postgresql://ext/audit"))); // audit configured → skip
        assertEquals(List.of("analytics"),
                plan.stream().map(PostgresDevService.DatasourcePlan::name).toList());
    }

    @Test
    void planHonoursPerNameAndGlobalOverrides() {
        List<PostgresDevService.DatasourcePlan> plan = PostgresDevService.plan(ctx(Map.of(
                "vidocq.dev.postgres.datasources", "analytics",
                "vidocq.dev.postgres.analytics.db", "metrics",
                "vidocq.dev.postgres.analytics.port", "55432",
                "vidocq.dev.postgres.image", "postgres:15-alpine")));
        PostgresDevService.DatasourcePlan analytics = named(plan, "analytics");
        assertEquals("metrics", analytics.db());
        assertEquals(55432, analytics.fixedPort());
        assertEquals("postgres:15-alpine", analytics.image(), "global image applies to named datasources too");
    }

    private static PostgresDevService.DatasourcePlan named(
            List<PostgresDevService.DatasourcePlan> plan, String name) {
        return plan.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aPortThatIsNotANumberNamesItsKey() {
        DevServiceContext ctx = ctx(Map.of("vidocq.dev.postgres.port", "${db.port}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PostgresDevService.plan(ctx));
        assertTrue(e.getMessage().contains("vidocq.dev.postgres.port"), e.getMessage());
        assertTrue(e.getMessage().contains("${db.port}"), e.getMessage());
    }

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

    /** A wrapper driver in front of PostgreSQL (tracing, logging, cloud) is PostgreSQL; Testcontainers' is not. */
    @Test
    void aWrapperDriverInFrontOfPostgresStartsAContainer() {
        for (String url : List.of("jdbc:otel:postgresql://prod:5432/db", "jdbc:p6spy:postgresql://prod/db",
                "JDBC:aws-wrapper:PostgreSQL://prod/db")) {
            assertEquals(List.of("default"),
                    names(PostgresDevService.plan(ctx(Map.of(), Map.of("vidocq.pool.url", url), false))), url);
        }
        assertEquals("vidocq.pool.url is jdbc:tc, not PostgreSQL", new PostgresDevService()
                .skipReason(ctx(Map.of(), Map.of("vidocq.pool.url", "jdbc:tc:postgresql:16:///db"), true)));
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

    @Test
    void describeGivesTheImageAndOneEndpointPerDatasource() {
        Map<String, String> injected = new LinkedHashMap<>();
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

    // ---- provisioning (Docker-gated) ----

    @Test
    @Timeout(240)
    void startsNamedContainersAndPublishesDistinctReachablePools() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available — skipping");

        PostgresDevService svc = new PostgresDevService();
        try {
            Map<String, String> props = svc.start(ctx(Map.of(
                    "vidocq.dev.postgres.datasources", "analytics")));

            String defUrl = props.get("vidocq.pool.url");
            String anUrl = props.get("vidocq.pool.analytics.url");
            assertTrue(defUrl != null && defUrl.startsWith("jdbc:postgresql://"), "got " + defUrl);
            assertTrue(anUrl != null && anUrl.startsWith("jdbc:postgresql://"), "got " + anUrl);
            assertNotEquals(defUrl, anUrl, "the named datasource must be a distinct container");
            assertEquals("vidocq", props.get("vidocq.pool.analytics.username"));

            for (String url : List.of(defUrl, anUrl)) {
                URI uri = URI.create(url.substring("jdbc:".length()));
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 5000);
                    assertTrue(s.isConnected());
                }
            }
        } finally {
            svc.stop();
        }
    }

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
}
