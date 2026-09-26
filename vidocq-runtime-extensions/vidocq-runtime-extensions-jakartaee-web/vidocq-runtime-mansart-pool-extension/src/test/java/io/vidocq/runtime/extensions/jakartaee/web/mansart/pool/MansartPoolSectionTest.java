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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.RecordedSection.Anomaly;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.TestContexts.ReportContext;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.TestContexts.StartContext;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mansart-pool} section of the startup report {@link MansartPoolExtension} writes. Its boot facts are
 * written into a {@link RecordedSection}, as the report would receive them. Since Vidocq/vidocq#143, its live
 * values are read by the {@code vidocq-runtime-mansart-pool-extension-dev} companion instead — see
 * {@code PoolsLivePanelTest} there.
 */
class MansartPoolSectionTest {

    /** The password of the pools configured below: it must never be written anywhere. */
    private static final String PASSWORD = "pw-4udit-1";

    private final MansartPoolExtension ext = new MansartPoolExtension();

    @AfterEach
    void stop() {
        ext.onStop();
    }

    private static String h2(String name) {
        return "jdbc:h2:mem:section-" + name + "-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    /** Configures the extension and opens its pools, as Vidocq does before the container starts. */
    private void boot(String... keysAndValues) {
        ext.configure(TestContexts.config(keysAndValues));
        ext.beforeStart(new VaubanContainerBuilder());
    }

    private RecordedSection facts(LaunchMode mode) {
        return facts(ReportContext.detailed(mode));
    }

    private RecordedSection facts(ReportContext context) {
        RecordedSection section = new RecordedSection();
        ext.contribute(context, section);
        return section;
    }

    @Test
    void theExtensionContributesItsSection() {
        assertInstanceOf(StartupReportContributor.class, ext);
        assertEquals("mansart-pool", ext.id());
        assertEquals("Mansart pools", ext.title());
    }

    @Test
    void withNoPoolTheSectionSaysWhyItIsIdle() {
        boot();

        RecordedSection section = facts(LaunchMode.DEV);

        assertEquals("idle: no vidocq.pool[.<name>].url", section.summary());
        assertEquals(List.of(), section.rows());
        assertEquals(List.of(), section.anomalies());
    }

    /** A @Default H2 pool and a named PostgreSQL pool whose URL carries credentials; neither is connected. */
    private void bootTwoPools() {
        boot("vidocq.pool.url", h2("default"),
                "vidocq.pool.username", "sa",
                "vidocq.pool.maxSize", "8",
                "vidocq.pool.xa", "true",
                "vidocq.pool.audit.url",
                "jdbc:postgresql://audit:s3cret@db.example:5432/audit?sslpassword=k3y&ssl=true",
                "vidocq.pool.audit.username", "auditor",
                "vidocq.pool.audit.password", PASSWORD,
                "vidocq.pool.audit.maxSize", "4",
                "vidocq.pool.audit.acquireTimeout", "PT2S",
                "vidocq.pool.audit.idleTimeout", "PT5M",
                "vidocq.pool.audit.validationQuery", "SELECT 1",
                "vidocq.pool.audit.leakDetectionThreshold", "PT30S");
    }

    @Test
    void inDevTheBootFactsShowTheUrlWithoutItsCredentialsAndTheUser() {
        bootTwoPools();

        RecordedSection section = facts(LaunchMode.DEV);

        assertEquals("2 pools (@Default, audit), 12 connections max", section.summary());
        assertEquals(List.of(
                "@Default", "@Default user", "@Default size", "@Default timeouts", "@Default checks", "@Default xa",
                "@Default password",
                "audit", "audit user", "audit size", "audit timeouts", "audit checks", "audit password",
                "audit url credentials"), section.keys());
        assertTrue(section.value("@Default").startsWith("jdbc:h2:mem:section-default-"), section.value("@Default"));
        assertEquals("sa", section.value("@Default user"));
        assertEquals("min idle 0 (boot only), max 8", section.value("@Default size"));
        assertEquals("acquire PT5S, idle PT10M, lifetime PT30M", section.value("@Default timeouts"));
        assertEquals("validation PERIODIC PT1S, leaks off", section.value("@Default checks"));
        assertEquals("org.h2.jdbcx.JdbcDataSource", section.value("@Default xa"));
        assertEquals("not configured", section.value("@Default password"));
        assertEquals("jdbc:postgresql://db.example:5432/audit?ssl=true", section.value("audit"));
        assertEquals("auditor", section.value("audit user"));
        assertEquals("min idle 0 (boot only), max 4", section.value("audit size"));
        assertEquals("acquire PT2S, idle PT5M, lifetime PT30M", section.value("audit timeouts"));
        assertEquals("validation ON_BORROW PT1S, leaks after PT30S", section.value("audit checks"));
        assertEquals("configured", section.value("audit password"));
        assertEquals("configured", section.value("audit url credentials"));
        assertEquals(List.of(), section.anomalies());
        for (String secret : List.of("s3cret", "k3y", PASSWORD)) {
            assertFalse(section.everything().contains(secret), secret + " written: " + section.everything());
        }
    }

    @Test
    void outsideDevTheBootFactsNameTheDatabaseKindOnly() {
        bootTwoPools();

        for (LaunchMode mode : List.of(LaunchMode.PROD, LaunchMode.TEST)) {
            RecordedSection section = facts(mode);

            assertEquals("2 pools (@Default, audit), 12 connections max", section.summary());
            assertEquals(List.of(
                    "@Default", "@Default size", "@Default timeouts", "@Default checks", "@Default xa",
                    "@Default password",
                    "audit", "audit size", "audit timeouts", "audit checks", "audit password",
                    "audit url credentials"), section.keys(), mode + ": no user, no URL");
            assertEquals("h2 mem", section.value("@Default"), mode.toString());
            assertEquals("postgresql", section.value("audit"), mode.toString());
            assertFalse(section.everything().contains("jdbc:"), section.everything());
            assertFalse(section.everything().contains("db.example"), section.everything());
            assertFalse(section.everything().contains("auditor"), section.everything());
        }
    }

    @Test
    void belowDetailedOnlyTheSummaryAndTheAnomaliesAreWritten() {
        boot("vidocq.pool.url", "jdbc:vidocq-no-driver:nowhere", "vidocq.pool.minIdle", "1");
        ext.onStart(StartContext.withNamedBeans());

        for (Verbosity verbosity : List.of(Verbosity.SUMMARY, Verbosity.OFF)) {
            RecordedSection section = facts(new ReportContext(LaunchMode.DEV, verbosity));

            assertEquals("1 pool (@Default), 10 connections max", section.summary());
            assertEquals(List.of(), section.rows(), verbosity + ": the rows would not be printed");
            assertEquals(List.of("MANSART-POOL-001"), section.anomalies().stream().map(Anomaly::code).toList(),
                    verbosity + ": an anomaly is never lost");
        }
    }

    @Test
    void aPoolADevServiceProvidedSaysSoInDevOnly() {
        boot("vidocq.pool.url", h2("default"),
                "vidocq.pool.orders.url", "jdbc:postgresql://localhost:54213/vidocq?loggerLevel=OFF",
                "vidocq.pool.orders.maxSize", "1",
                "vidocq.dev.provided.vidocq.pool.orders.url", "postgres");

        RecordedSection dev = facts(LaunchMode.DEV);
        assertEquals("dev service postgres, localhost:54213", dev.value("orders source"));
        assertNull(dev.value("@Default source"), "the @Default pool came from the configuration");

        RecordedSection prod = facts(LaunchMode.PROD);
        assertNull(prod.value("orders source"), "a dev service is a development fact");
    }

    @Test
    void theSummaryCountsThePoolsAndTheirConnections() {
        boot("vidocq.pool.url", h2("single"), "vidocq.pool.maxSize", "1");

        assertEquals("1 pool (@Default), 1 connection max", facts(LaunchMode.DEV).summary());
    }

    @Test
    void theUnnamedPoolIsDefaultEvenBesideAPoolNamedDefault() {
        boot("vidocq.pool.url", h2("unnamed"), "vidocq.pool.default.url", h2("named"));

        assertEquals("2 pools (@Default, default), 20 connections max", facts(LaunchMode.DEV).summary());
        assertEquals(List.of("@Default", "default"),
                MansartPoolsLive.pools().stream().map(MansartPoolsLive.Pool::label).toList(),
                "the holder the -dev panel reads sees the same labels");
    }

    @Test
    void aPreFillThatOpenedNothingIsAnAnomaly() {
        boot("vidocq.pool.url", "jdbc:vidocq-no-driver:nowhere", "vidocq.pool.minIdle", "2",
                "vidocq.pool.audit.url", "jdbc:vidocq-no-driver:elsewhere", "vidocq.pool.audit.minIdle", "1",
                "vidocq.pool.healthy.url", h2("healthy"), "vidocq.pool.healthy.minIdle", "1",
                "vidocq.pool.lazy.url", "jdbc:vidocq-no-driver:lazy");
        ext.onStart(StartContext.withNamedBeans("audit", "healthy", "lazy"));

        List<Anomaly> anomalies = facts(LaunchMode.DEV).anomalies();

        assertEquals(List.of("MANSART-POOL-001", "MANSART-POOL-001"), anomalies.stream().map(Anomaly::code).toList(),
                "the healthy pool opened its connection, the lazy one opens none at boot");
        assertEquals("Pool '@Default' opened none of the 2 connections its minIdle asks for at boot: the pool gave up"
                + " at the first failure and opens connections on demand only", anomalies.get(0).message());
        assertEquals("Check vidocq.pool.url, the credentials and the JDBC driver on the module path",
                anomalies.get(0).hint());
        assertEquals("Pool 'audit' opened none of the 1 connection its minIdle asks for at boot: the pool gave up"
                + " at the first failure and opens connections on demand only", anomalies.get(1).message());
        assertEquals("Check vidocq.pool.audit.url, the credentials and the JDBC driver on the module path",
                anomalies.get(1).hint());
    }

    @Test
    void aNamedPoolWithoutItsBeanIsAnAnomaly() {
        boot("vidocq.pool.url", h2("default"),
                "vidocq.pool.analytics.url", h2("analytics"),
                "vidocq.pool.audit.url", h2("audit"));
        StartContext context = StartContext.withNamedBeans("analytics");

        ext.onStart(context);

        assertEquals(List.of("analytics", "audit"), context.asked(), "the @Default pool has a bean of its own");
        List<Anomaly> anomalies = facts(LaunchMode.PROD).anomalies();
        assertEquals(List.of(new Anomaly("MANSART-POOL-002",
                "Named pool 'audit' is open, but no @Named(\"audit\") DataSource bean serves it: an injection or"
                        + " a repository routed to it fails on first use",
                "Add vidocq-runtime-mansart-pool-datasources-codegen to the annotationProcessorPaths of the"
                        + " application")), anomalies);
    }

    @Test
    void whenTheBeansCannotBeListedNoAnomalyIsGuessed() {
        boot("vidocq.pool.audit.url", h2("audit"));

        assertDoesNotThrow(() -> ext.onStart(StartContext.failing(new IllegalStateException("no container"))));

        assertEquals(List.of(), facts(LaunchMode.DEV).anomalies());
    }
}
