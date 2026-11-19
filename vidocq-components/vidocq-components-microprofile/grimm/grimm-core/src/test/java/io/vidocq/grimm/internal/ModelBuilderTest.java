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

import io.vidocq.grimm.internal.config.GrimmConfig;
import io.vidocq.grimm.internal.reader.StaticFileReader;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ModelBuilder}: the pipeline of the MP OpenAPI 4.2 "Processing rules" (§4.4) — reader,
 * static file, compile-time fragments and scan, merge, default info.
 */
class ModelBuilderTest {

    private static final String DEFAULT_TITLE = "Generated API";

    /** Reader of the test: a starting model with a path and a blank info. */
    public static final class BlankInfoReader implements OASModelReader {
        @Override
        public OpenAPI buildModel() {
            Paths paths = OASFactory.createObject(Paths.class).addPathItem("/from-reader",
                    OASFactory.createObject(PathItem.class));
            return OASFactory.createObject(OpenAPI.class)
                    .info(OASFactory.createObject(Info.class).title(" "))
                    .paths(paths);
        }
    }

    @AfterEach
    void cleanup() {
        StaticFileReader.clearExplicitDocument();
    }

    @Test
    void nullInputsBuildADefaultDocument() {
        StaticFileReader.setExplicitDocument("META-INF/openapi.yaml", "info: {}\n");

        OpenAPI model = new ModelBuilder().build(null, null);

        assertEquals("3.1.0", model.getOpenapi());
        assertEquals(DEFAULT_TITLE, model.getInfo().getTitle());
        assertEquals("1.0", model.getInfo().getVersion());
    }

    @Test
    void theReaderModelIsTheStartingPointAndItsBlankInfoIsCompleted() {
        StaticFileReader.setExplicitDocument("META-INF/openapi.yaml", "info: {}\n");
        GrimmConfig config = GrimmConfig.fromMap(Map.of("mp.openapi.model.reader", BlankInfoReader.class.getName()));

        OpenAPI model = new ModelBuilder().build(config, List.of());

        assertNotNull(model.getPaths().getPathItem("/from-reader"));
        assertEquals(DEFAULT_TITLE, model.getInfo().getTitle());
    }

    @Test
    void aCompileTimeFragmentReplacesTheScanOfItsResource() {
        // CG-06: a $$GrimmModel companion contributes its JSON fragment; the class is not scanned.
        ContributionRegistry.resetForTests();
        Class<?> resource = new ContributionFixtures().resourceWithCompanion("fixture.Fragmented", null,
                "{\"paths\":{\"/generated\":{\"get\":{\"operationId\":\"fromFragment\"}}}}");

        OpenAPI model = new ModelBuilder().build(GrimmConfig.defaults(), List.of(resource));

        assertEquals("fromFragment", model.getPaths().getPathItem("/generated").getGET().getOperationId());
        assertEquals(1, ContributionRegistry.preGeneratedHits());
        assertEquals(0, ContributionRegistry.scanFallbacks());
    }

    /**
     * A class with a compile-time fragment is not scanned, yet its {@code @OpenAPIDefinition} is still read (grimm
     * BUG-20261030-02): the fragment carries paths only. The test class path holds a static {@code openapi.yaml}
     * whose info wins, as the specification orders, so the definition's tags tell.
     */
    @Test
    void theDefinitionOfAClassWithACompanionIsRead() {
        OpenAPI model = new ModelBuilder().build(GrimmConfig.defaults(), List.of(DefinedResource.class));

        assertEquals(List.of("library"), model.getTags().stream().map(Tag::getName).toList());
        assertNotNull(model.getPaths().getPathItem("/defined"));
    }
}
