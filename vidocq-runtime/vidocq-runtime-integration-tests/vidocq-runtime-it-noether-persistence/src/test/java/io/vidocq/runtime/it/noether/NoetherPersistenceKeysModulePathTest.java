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
package io.vidocq.runtime.it.noether;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.vidocq.runtime.core.VidocqBootstrap;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.persistence.metamodel.Type;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The {@code vidocq.persistence.*} keys of {@code vidocq.properties} (Vidocq/vidocq#551): they declare the unit
 * {@code journal}, which no {@code persistence.xml} names, and set a property of the unit {@code ledger}, which the
 * {@code persistence.xml} declares.
 */
class NoetherPersistenceKeysModulePathTest {

    private static VidocqBootstrap bootstrap;

    @BeforeAll
    static void boot() throws Exception {
        bootstrap = VidocqBootstrap.create().configure().start();
    }

    @AfterAll
    static void shutdown() {
        if (bootstrap != null) {
            bootstrap.shutdown();
        }
    }

    @Test
    void a_unit_the_keys_declare_manages_the_entity_they_list_in_the_transactions_of_the_application() {
        Journal journal = CDI.current().select(Journal.class).get();

        journal.record("declared by the keys");

        assertEquals(Set.of(Memo.class), journal.factory().getMetamodel().getEntities().stream()
                .map(Type::getJavaType).collect(Collectors.toSet()));
        assertEquals(1, journal.count("declared by the keys"));
    }

    @Test
    void a_key_sets_a_property_of_the_unit_a_persistence_xml_declares() {
        Ledger ledger = CDI.current().select(Ledger.class).get();

        assertEquals("by-the-keys", ledger.factory().getProperties().get("io.vidocq.it.overridden"));
        assertEquals("drop-and-create",
                ledger.factory().getProperties().get("jakarta.persistence.schema-generation.database.action"),
                "the properties of the persistence.xml stay");
    }
}
