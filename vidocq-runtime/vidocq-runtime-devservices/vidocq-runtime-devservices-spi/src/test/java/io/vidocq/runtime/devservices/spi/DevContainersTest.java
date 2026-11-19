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
package io.vidocq.runtime.devservices.spi;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevContainersTest {

    private static DevServiceContext context(Path basedir, Map<String, String> properties) {
        return new DevServiceContext() {
            @Override public Optional<String> property(String key) { return Optional.ofNullable(properties.get(key)); }
            @Override public Map<String, String> properties() { return properties; }
            @Override public Path basedir() { return basedir; }
            @Override public Path resolve(String relative) { return basedir.resolve(relative); }
            @Override public System.Logger log() { return System.getLogger("test"); }
        };
    }

    private static final DevServiceContext APP = context(Path.of("/work/mcp-tasks-server"), Map.of());

    @Test
    void aNameStartsWithVidocqDevThenTheApplicationAndTheService() {
        String name = DevContainers.name(APP, "postgres", null, null);

        assertTrue(name.matches("vidocq-dev-mcp-tasks-server-postgres-[0-9a-f]{8}"), name);
    }

    @Test
    void theQualifierComesAfterTheService() {
        String name = DevContainers.name(APP, "postgres", "analytics", null);

        assertTrue(name.matches("vidocq-dev-mcp-tasks-server-postgres-analytics-[0-9a-f]{8}"), name);
    }

    @Test
    void thePrefixIsAProperty() {
        DevServiceContext ctx = context(Path.of("/work/shop"), Map.of("vidocq.dev.container-prefix", "acme-"));

        assertTrue(DevContainers.name(ctx, "keycloak", null, null).matches("acme-shop-keycloak-[0-9a-f]{8}"));
    }

    @Test
    void aContainerThatIsNotReusedGetsANewNameEachTime() {
        assertNotEquals(DevContainers.name(APP, "postgres", null, null), DevContainers.name(APP, "postgres", null, null));
    }

    @Test
    void aReusedContainerKeepsItsNameAsLongAsItsConfigurationStays() {
        assertEquals(DevContainers.name(APP, "postgres", null, "postgres:16|vidocq"),
                DevContainers.name(APP, "postgres", null, "postgres:16|vidocq"));
    }

    @Test
    void aReusedContainerChangesNameWithItsConfigurationOrItsApplication() {
        String name = DevContainers.name(APP, "postgres", null, "postgres:16|vidocq");

        assertNotEquals(name, DevContainers.name(APP, "postgres", null, "postgres:17|vidocq"));
        DevServiceContext twin = context(Path.of("/elsewhere/mcp-tasks-server"), Map.of());
        assertNotEquals(name, DevContainers.name(twin, "postgres", null, "postgres:16|vidocq"));
    }

    @Test
    void whatDockerRefusesInANameBecomesADash() {
        DevServiceContext ctx = context(Path.of("/work/My App (v2)"), Map.of());

        assertTrue(DevContainers.name(ctx, "postgres", "Orders DB", null)
                .matches("vidocq-dev-my-app-v2-postgres-orders-db-[0-9a-f]{8}"));
    }

    @Test
    void aRelativeBasedirIsNamedAfterItsDirectory() {
        DevServiceContext ctx = context(Path.of("."), Map.of());
        String directory = Path.of("").toAbsolutePath().getFileName().toString().toLowerCase();

        assertTrue(DevContainers.name(ctx, "postgres", null, null).contains(directory.replaceAll("[^a-z0-9_.-]+", "-")));
    }

    @Test
    void aPrefixDockerWouldRefuseIsNamed() {
        DevServiceContext ctx = context(Path.of("/work/shop"), Map.of("vidocq.dev.container-prefix", "-bad/"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DevContainers.name(ctx, "postgres", null, null));
        assertTrue(e.getMessage().contains("vidocq.dev.container-prefix"), e.getMessage());
    }

    @Test
    void theLabelsNameVidocqTheApplicationAndTheService() {
        assertEquals(Map.of("io.vidocq.dev", "true", "io.vidocq.dev.app", "mcp-tasks-server",
                "io.vidocq.dev.service", "postgres"), DevContainers.labels(APP, "postgres"));
    }
}
