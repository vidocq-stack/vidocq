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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.Broken;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.CatalogueFixtures.OrderRepository;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.RecordedSection.Anomaly;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataLive;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.Verbosity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code mansart-data} section of the startup report, written by {@link MansartDataIntegrationExtension} from the
 * catalogue it builds once per boot and publishes in {@link MansartDataLive} for the {@code -dev} panel.
 */
class MansartDataSectionTest {

    private final MansartDataIntegrationExtension ext = new MansartDataIntegrationExtension();

    @AfterEach
    void stop() {
        ext.onStop();
    }

    private RecordedSection section(ReportContext context) {
        RecordedSection section = new RecordedSection();
        ext.contribute(context, section);
        return section;
    }

    private void buildFixtures() {
        ext.catalogue(CatalogueFixtures.REPOSITORIES, CatalogueFixtures::model);
    }

    @Test
    void theExtensionContributesItsSection() {
        assertInstanceOf(StartupReportContributor.class, ext);
        assertEquals("mansart-data", ext.id());
        assertEquals("Mansart Data", ext.title());
        assertEquals("mansart-data", ext.name());
    }

    @Test
    void beforeOnStartThereIsNoCatalogue() {
        RecordedSection section = section(ReportContext.detailed());

        assertEquals("no catalogue: the extension did not start", section.summary());
        assertEquals(List.of(), section.keys());
        assertEquals(List.of(), section.anomalies());
    }

    @Test
    void theCatalogueIsPublishedForTheDevPanel() {
        buildFixtures();

        assertTrue(MansartDataLive.catalogue().isPresent());
        assertEquals(4, MansartDataLive.catalogue().orElseThrow().entityCount());
    }

    @Test
    void theSummaryAndTheDetailedRows() {
        buildFixtures();

        RecordedSection section = section(ReportContext.detailed());

        assertEquals("4 entities, 5 repositories, 14 methods", section.summary());
        assertEquals(List.of("Broken", "Customer", "Gadget", "Order",
                "BrokenRepository", "CustomerRepository", "GadgetRepository", "OrderRepository",
                "ReportQueries"), section.keys());
        assertEquals("model unavailable (io.vidocq.mansart.data.core.MansartDataException)", section.value("Broken"));
        assertEquals("customers, 2 columns", section.value("Customer"));
        assertEquals("gadgets, 3 columns", section.value("Gadget"));
        assertEquals("shop.orders, 6 columns", section.value("Order"));
        assertEquals("Broken, 0 methods", section.value("BrokenRepository"));
        assertEquals("Customer, 1 method", section.value("CustomerRepository"));
        assertEquals("Gadget, 10 methods", section.value("GadgetRepository"));
        assertEquals("Order, 1 method", section.value("OrderRepository"));
        assertEquals("no primary entity, 2 methods", section.value("ReportQueries"));
    }

    @Test
    void anEntityWhoseModelFailsRaisesOneAnomalyWithoutItsMessage() {
        buildFixtures();

        List<Anomaly> anomalies = section(ReportContext.detailed()).anomalies();

        assertEquals(List.of(new Anomaly("MANSART-DATA-001",
                "The model of entity " + Broken.class.getName()
                        + " could not be read (io.vidocq.mansart.data.core.MansartDataException)",
                "Check its mapping annotations; Mansart could not build its model, so its repositories may fail"
                        + " too.")), anomalies);
        assertFalse(anomalies.getFirst().message().contains("@Id"), "the exception's message is never shown");
    }

    @Test
    void belowDetailedOnlyTheSummaryAndTheAnomaliesAreWritten() {
        buildFixtures();

        for (Verbosity verbosity : List.of(Verbosity.SUMMARY, Verbosity.OFF)) {
            RecordedSection section = section(new ReportContext(LaunchMode.DEV, verbosity));

            assertEquals("4 entities, 5 repositories, 14 methods", section.summary());
            assertEquals(List.of(), section.keys(), verbosity + ": the rows would not be printed");
            assertEquals(1, section.anomalies().size(), verbosity + ": an anomaly is never lost");
        }
    }

    @Test
    void singularFormsForOne() {
        ext.catalogue(List.of(OrderRepository.class), CatalogueFixtures::model);

        assertEquals("1 entity, 1 repository, 1 method", section(ReportContext.detailed()).summary());
    }

    @Test
    void pastALimitARowCountsTheRest() {
        ext.catalogue(CatalogueFixtures.REPOSITORIES, CatalogueFixtures::model, new CatalogueBuilder(1, 1, 200, 1_000));

        RecordedSection section = section(ReportContext.detailed());

        assertEquals("4 entities, 5 repositories, 14 methods", section.summary());
        assertEquals(List.of("Broken", "more entities", "BrokenRepository", "more repositories"), section.keys());
        assertEquals("and 3 more", section.value("more entities"));
        assertEquals("and 4 more", section.value("more repositories"));
    }

    @Test
    void aCatalogueThatCannotBeBuiltNeverFailsTheBoot() {
        ext.catalogue(null, CatalogueFixtures::model);

        assertEquals("no catalogue (java.lang.NullPointerException)", section(ReportContext.detailed()).summary());
        assertTrue(MansartDataLive.catalogue().isEmpty());
    }

    @Test
    void onStopClearsTheCatalogueFirst() {
        buildFixtures();
        var published = MansartDataLive.catalogue().orElseThrow();
        assertSame(published, MansartDataLive.catalogue().orElseThrow());

        ext.onStop();

        assertTrue(MansartDataLive.catalogue().isEmpty(), "a dev console poll now reads no catalogue");
        assertEquals("no catalogue: the extension did not start", section(ReportContext.detailed()).summary());
    }
}
