/*
 * Copyright 2026 Vidocq contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
