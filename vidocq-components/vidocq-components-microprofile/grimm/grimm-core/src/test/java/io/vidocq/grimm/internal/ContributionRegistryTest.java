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
package io.vidocq.grimm.internal;

import io.vidocq.grimm.spi.gen.OpenApiContribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ContributionRegistry}: compile-time {@code $$GrimmModel} companions first, the scan as
 * fallback (CG-06). The companions are generated with the Class-File API, as grimm-processor
 * would emit them, into a dedicated class loader.
 */
class ContributionRegistryTest {

    private static final String JSON = "{\"paths\":{}}";

    @BeforeEach
    void resetCounters() {
        ContributionRegistry.resetForTests();
    }

    @Test
    void aGeneratedCompanionIsUsed() {
        var fixtures = new ContributionFixtures();
        Class<?> resource = fixtures.resourceWithCompanion("fixture.Matching", null, JSON);

        OpenApiContribution contribution = ContributionRegistry.resolve(resource);

        assertNotNull(contribution);
        assertSame(resource, contribution.resourceClass());
        assertEquals(JSON, contribution.openApiJson());
        assertEquals(1, ContributionRegistry.preGeneratedHits());
        assertEquals(0, ContributionRegistry.serviceLoaderHits());
    }

    @Test
    void aStaleCompanionTargetingAnotherClassFallsBackToTheScan() {
        var fixtures = new ContributionFixtures();
        Class<?> resource = fixtures.resourceWithCompanion("fixture.Stale", String.class, JSON);

        assertNull(ContributionRegistry.resolve(resource));
        assertEquals(0, ContributionRegistry.preGeneratedHits());
    }

    @Test
    void aCompanionThatIsNotAContributionFallsBackToTheScan() {
        var fixtures = new ContributionFixtures();
        Class<?> resource = fixtures.resourceWithBrokenCompanion("fixture.Broken");

        assertNull(ContributionRegistry.resolve(resource));
        assertEquals(0, ContributionRegistry.preGeneratedHits());
    }

    @Test
    void aServiceLoaderProviderWinsOverTheNamingConvention(@TempDir Path directory) {
        var fixtures = new ContributionFixtures();
        Class<?> resource = fixtures.resourceWithCompanion("fixture.Provided", null, JSON);
        fixtures.registerProviders(directory, "fixture.Provided$$GrimmModel");

        OpenApiContribution contribution = ContributionRegistry.resolve(resource);

        assertSame(resource, contribution.resourceClass());
        assertEquals(1, ContributionRegistry.serviceLoaderHits());
        assertEquals(0, ContributionRegistry.preGeneratedHits());
    }

    @Test
    void aBrokenProviderFileFallsBackToTheNamingConvention(@TempDir Path directory) {
        var fixtures = new ContributionFixtures();
        Class<?> resource = fixtures.resourceWithCompanion("fixture.Misdeclared", null, JSON);
        fixtures.registerProviders(directory, "fixture.DoesNotExist");

        assertNotNull(ContributionRegistry.resolve(resource));
        assertEquals(0, ContributionRegistry.serviceLoaderHits());
        assertEquals(1, ContributionRegistry.preGeneratedHits());
    }

    @Test
    void aClassWithoutCompanionIsScanned() {
        assertNull(ContributionRegistry.resolve(ContributionRegistryTest.class));

        ContributionRegistry.noteScanFallback();
        assertEquals(1, ContributionRegistry.scanFallbacks());
    }
}
