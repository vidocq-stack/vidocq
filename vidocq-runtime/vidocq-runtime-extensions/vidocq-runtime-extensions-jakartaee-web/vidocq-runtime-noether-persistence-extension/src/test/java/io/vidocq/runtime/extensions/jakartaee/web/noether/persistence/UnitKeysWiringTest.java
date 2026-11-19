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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.noether.jpa.core.spi.PersistenceUnitDeclaration;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.units.VidocqPersistenceUnits;
import io.vidocq.runtime.spi.VidocqConfiguration;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The extension hands Noether the {@code vidocq.persistence.*} keys of the application that starts, as its
 * configuration resolves them (Vidocq/vidocq#551).
 */
class UnitKeysWiringTest {

    private final NoetherPersistenceExtension extension = new NoetherPersistenceExtension();

    @AfterEach
    void stop() {
        extension.onStop();
    }

    @Test
    void the_keys_of_the_configuration_are_those_noether_reads_until_the_stop() {
        extension.configure(configuration(Map.of(
                "vidocq.persistence.ledger.datasource", "production",
                "vidocq.pool.url", "jdbc:h2:mem:ledger")));

        assertEquals(List.of("ledger"), units());
        assertEquals(Optional.of("production"), new VidocqPersistenceUnits().units(loader()).getFirst().dataSource());

        extension.onStop();

        assertFalse(units().contains("ledger"), "a stopped application's keys are forgotten");
    }

    /** The value of a key is the one the configuration resolves, an environment variable included. */
    @Test
    void the_value_of_a_key_is_the_resolved_one() {
        extension.configure(new VidocqConfiguration() {
            @Override
            public Optional<String> property(String key) {
                return Optional.of(key.endsWith(".datasource") ? "from-the-environment" : "x");
            }

            @Override
            public Iterable<String> propertyNames() {
                return List.of("vidocq.persistence.ledger.datasource");
            }
        });

        assertEquals(Optional.of("from-the-environment"),
                new VidocqPersistenceUnits().units(loader()).getFirst().dataSource());
    }

    @Test
    void the_extension_claims_the_keys_it_reads() {
        assertTrue(extension.configKeys().contains("vidocq.persistence.*"), extension.configKeys()::toString);
    }

    @Test
    void a_module_of_the_boot_layer_has_its_root() {
        ApplicationModules modules = new ApplicationModules();

        assertEquals("jrt:/java.logging", modules.of("java.logging").orElseThrow().toExternalForm());
        assertEquals(Optional.empty(), modules.of("acme.absent"));
        assertEquals(Optional.empty(), modules.application(), "no runtime names an application module here");
    }

    @Test
    void a_unit_nothing_declares_names_the_keys_that_would() {
        var bootstrap = new VidocqPersistenceUnitBootstrap(null, null, null);

        PersistenceException refused = assertThrows(PersistenceException.class,
                () -> bootstrap.create("absent", null));

        assertTrue(refused.getMessage().contains("vidocq.persistence.absent.*"), refused.getMessage());
        assertTrue(refused.getMessage().contains("META-INF/persistence.xml"), refused.getMessage());
    }

    private static List<String> units() {
        return new VidocqPersistenceUnits().units(loader()).stream().map(PersistenceUnitDeclaration::name).toList();
    }

    private static ClassLoader loader() {
        return UnitKeysWiringTest.class.getClassLoader();
    }

    private static VidocqConfiguration configuration(Map<String, String> keys) {
        return new VidocqConfiguration() {
            @Override
            public Optional<String> property(String key) {
                return Optional.ofNullable(keys.get(key));
            }

            @Override
            public Iterable<String> propertyNames() {
                return keys.keySet();
            }
        };
    }
}
