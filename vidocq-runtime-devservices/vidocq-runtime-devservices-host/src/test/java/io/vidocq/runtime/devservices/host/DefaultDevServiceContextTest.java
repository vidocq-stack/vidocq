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
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDevServiceContextTest {

    /**
     * The providers log through the host's logger: under Maven, the build's log, instead of the JDK's default
     * two-line, localized format (BUG-20261030-07).
     */
    @Test
    void theProvidersLogThroughTheLoggerTheHostGives() {
        System.Logger host = System.getLogger("host.under.test");
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of(), key -> Optional.empty(),
                key -> Optional.empty(), className -> false, host);

        assertSame(host, ctx.log());
    }

    /**
     * BUG-20261114-08: the tests of a {@code vidocq:dev} session have a session of their own, beside the dev one. A
     * reused container is found again by its name, which the configuration alone decides, and a pinned host port is
     * the dev container's already: the isolated context reuses nothing and pins no port, whatever source sets them.
     */
    @Test
    void anIsolatedContextReusesNoContainerAndPinsNoHostPort() {
        Map<String, String> files = Map.of("vidocq.dev.keycloak.port", "18180");
        System.Logger log = System.getLogger("isolated.under.test");
        Path basedir = Path.of("/tmp/project");
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(basedir,
                Map.of("vidocq.dev.reuse", "true", "vidocq.dev.postgres.port", "55432",
                        "vidocq.dev.postgres.audit.port", "55433", "vidocq.dev.postgres.image", "postgres:17",
                        "vidocq.pool.url", "jdbc:postgresql://db/app"),
                key -> Optional.ofNullable(files.get(key)), key -> Optional.of("from the files"),
                "org.postgresql.Driver"::equals, log);

        DefaultDevServiceContext isolated = ctx.isolated();

        assertEquals(Optional.of("false"), isolated.property("vidocq.dev.reuse"));
        assertEquals(Optional.empty(), isolated.property("vidocq.dev.postgres.port"));
        assertEquals(Optional.empty(), isolated.property("vidocq.dev.postgres.audit.port"));
        assertEquals(Optional.empty(), isolated.property("vidocq.dev.keycloak.port"));
        assertEquals(Optional.of("postgres:17"), isolated.property("vidocq.dev.postgres.image"), "another tuning key");
        assertEquals(Optional.of("jdbc:postgresql://db/app"), isolated.property("vidocq.pool.url"), "an opt-out key");
        assertEquals(Optional.of("from the files"), isolated.applicationProperty("vidocq.pool.url"));
        assertTrue(isolated.onApplicationClasspath("org.postgresql.Driver"));
        assertEquals(basedir, isolated.basedir());
        assertSame(log, isolated.log());
        assertEquals(Optional.of("true"), ctx.property("vidocq.dev.reuse"), "the context it came from is unchanged");
        assertEquals(Optional.of("55432"), ctx.property("vidocq.dev.postgres.port"));
        assertEquals(Optional.of("18180"), ctx.property("vidocq.dev.keycloak.port"));
    }

    @Test
    void seededOverrideBeatsSystemProperty() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of("k", "fromSeed"));
        System.setProperty("k", "fromSys");
        try {
            assertEquals("fromSeed", ctx.property("k").orElseThrow());
        } finally {
            System.clearProperty("k");
        }
    }

    @Test
    void fallsBackToSystemPropertyWhenNotSeeded() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of());
        System.setProperty("vidocq.dev.test.fallback", "sysval");
        try {
            assertEquals("sysval", ctx.property("vidocq.dev.test.fallback").orElseThrow());
        } finally {
            System.clearProperty("vidocq.dev.test.fallback");
        }
    }

    @Test
    void mergedProviderOutputBecomesVisible() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of());
        assertTrue(ctx.property("m").isEmpty());
        ctx.merge(Map.of("m", "v"));
        assertEquals("v", ctx.property("m").orElseThrow());
    }

    @Test
    void blankValuesAreTreatedAsAbsent() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of("blank.key", "   "));
        assertTrue(ctx.property("blank.key").isEmpty());
    }

    @Test
    void resolveAbsolutizesRelativeToBasedir() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("/tmp/project"), Map.of());
        assertEquals(Path.of("/tmp/project/docker/keycloak/realm.json"),
                ctx.resolve("docker/keycloak/realm.json"));
    }

    @Test
    void resolveKeepsAbsolutePaths() {
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("/tmp/project"), Map.of());
        assertEquals(Path.of("/etc/realm.json"), ctx.resolve("/etc/realm.json"));
    }

    @Test
    void appPropertiesFileIsNotConsultedForApplicabilityDecisions() {
        // The context must ignore vidocq.properties: a baked default like vidocq.pool.url
        // must NOT be visible here (else a DevService would wrongly opt out). Only seed/system/env count.
        DefaultDevServiceContext ctx = new DefaultDevServiceContext(Path.of("."), Map.of());
        assertFalse(ctx.property("vidocq.pool.url").isPresent());
    }

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
        Files.writeString(classes.resolve("vidocq.properties"),
                "vidocq.dev.postgres.port=55432\nvidocq.pool.url=jdbc:postgresql://x/y\n");
        Function<String, Optional<String>> files = ApplicationFiles.of(classes);
        assertEquals(Optional.of("55432"), files.apply("vidocq.dev.postgres.port"));
        assertEquals(Optional.empty(), files.apply("vidocq.pool.url"));
    }
}
