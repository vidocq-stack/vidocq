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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitTransactions;
import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import jakarta.persistence.PersistenceUnitTransactionType;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The {@code noether-persistence} section of the startup report: the boot facts of each persistence unit. */
class PersistenceSectionTest {

    private static final UnitFacts ARCHIVE =
            new UnitFacts("archive", true, "archive", "create", 3, 1, 2, 4);
    private static final UnitFacts LEDGER =
            new UnitFacts("ledger", false, null, "none", 1, 0, 1, 1);

    @Test
    void noUnitSaysSo() {
        RecordingSection section = new RecordingSection();

        PersistenceSection.write(List.of(), LaunchMode.DEV, Verbosity.DETAILED, section);

        assertEquals("no persistence unit is injected", section.summary);
        assertTrue(section.rows.isEmpty());
    }

    @Test
    void theSummaryCountsTheUnitsAndTheirEntities() {
        RecordingSection section = new RecordingSection();

        PersistenceSection.write(List.of(ARCHIVE, LEDGER), LaunchMode.PROD, Verbosity.SUMMARY, section);

        assertEquals("2 persistence units, 4 entities", section.summary);
        assertTrue(section.rows.isEmpty(), "a production boot reads the summary only");
    }

    @Test
    void aDevBootDescribesEachUnit() {
        RecordingSection section = new RecordingSection();

        PersistenceSection.write(List.of(ARCHIVE, LEDGER), LaunchMode.DEV, Verbosity.SUMMARY, section);

        assertEquals(Map.of(
                "archive", "JTA on pool archive, schema action create, 3 entities, 1 embeddable, 2 named queries,"
                        + " 4 indexes",
                "ledger", "RESOURCE_LOCAL on the default pool, schema action none, 1 entity, 0 embeddables,"
                        + " 1 named query, 1 index"), section.rows);
    }

    @Test
    void aDetailedProductionBootDescribesEachUnitToo() {
        RecordingSection section = new RecordingSection();

        PersistenceSection.write(List.of(LEDGER), LaunchMode.PROD, Verbosity.DETAILED, section);

        assertEquals("1 persistence unit, 1 entity", section.summary);
        assertEquals(1, section.rows.size());
    }

    @Test
    void theFactsOfAUnitComeFromItsDescriptorItsDescriptionAndItsProperties() {
        UnitDescription.Table shelf = new UnitDescription.Table("SHELF", List.of(), List.of("ID"),
                List.of(new UnitDescription.Index("SHELF_LABEL", List.of("LABEL"), false),
                        new UnitDescription.Index(null, List.of("SERIAL"), true)), List.of());
        UnitDescription description = new UnitDescription("archive",
                List.of(new UnitDescription.Entity("Shelf", "app.Shelf", "SHELF", null,
                        UnitDescription.Inheritance.NONE, false, List.of())),
                List.of(new UnitDescription.Embeddable("app.Place", List.of())),
                List.of(new UnitDescription.NamedQuery("Shelf.all", "SELECT s FROM Shelf s", false)),
                List.of(shelf));

        UnitFacts facts = UnitFacts.of(description,
                new UnitTransactions(PersistenceUnitTransactionType.JTA, "archive", null),
                Map.of("jakarta.persistence.schema-generation.database.action", "drop-and-create"));

        assertEquals(new UnitFacts("archive", true, "archive", "drop-and-create", 1, 1, 1, 2), facts);
    }

    @Test
    void aUnitWithoutAPoolNameOrASchemaActionTakesTheDefaults() {
        UnitDescription description = new UnitDescription("ledger", List.of(), List.of(), List.of(), List.of());

        UnitFacts facts = UnitFacts.of(description,
                new UnitTransactions(PersistenceUnitTransactionType.RESOURCE_LOCAL, null, " "), Map.of());

        assertNull(facts.pool());
        assertEquals("none", facts.schemaAction());
    }

    /** Keeps the summary and the rows; the section writes nothing else. */
    static final class RecordingSection implements StartupReportSection {

        String summary;
        final Map<String, String> rows = new LinkedHashMap<>();

        @Override
        public StartupReportSection summary(String text) {
            summary = text;
            return this;
        }

        @Override
        public StartupReportSection row(String key, Object value) {
            rows.put(key, String.valueOf(value));
            return this;
        }

        @Override
        public StartupReportSection list(String key, Collection<String> items) {
            throw new AssertionError("the section writes no list");
        }

        @Override
        public StartupReportSection secret(String key, boolean configured) {
            throw new AssertionError("the section writes no secret");
        }

        @Override
        public StartupReportSection listener(String name, String boundBaseUri) {
            throw new AssertionError("the section starts no listener");
        }

        @Override
        public StartupReportSection route(String listener, String method, String path, String handler) {
            throw new AssertionError("the section serves no route");
        }

        @Override
        public StartupReportSection anomaly(String code, String message, String hint) {
            throw new AssertionError("the section reports no anomaly");
        }
    }
}
