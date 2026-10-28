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
package io.vidocq.grimm.internal.merger;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link FragmentMerger}: the same-priority, additive merge of compile-time {@code $$GrimmModel}
 * fragments (CG-06), equivalent to one annotation scan over all the classes.
 */
class FragmentMergerTest {

    private static final String PATH = "/pets";
    private static final String PET = "Pet";

    private static OpenAPI fragment(PathItem item) {
        return OASFactory.createOpenAPI().paths(OASFactory.createPaths().addPathItem(PATH, item));
    }

    @Test
    void nullFragmentLeavesTheTargetUnchanged() {
        OpenAPI target = OASFactory.createOpenAPI();

        assertSame(target, FragmentMerger.mergeInto(target, null));
        assertNull(target.getPaths());
    }

    @Test
    void fragmentWithoutPathsOrSchemasAddsNothing() {
        OpenAPI target = OASFactory.createOpenAPI();

        FragmentMerger.mergeInto(target, OASFactory.createOpenAPI().components(OASFactory.createComponents()));

        assertNull(target.getPaths());
        assertNull(target.getComponents());
    }

    @Test
    void newPathsAndSchemasAreAdded() {
        OpenAPI target = OASFactory.createOpenAPI();
        OpenAPI fragment = fragment(OASFactory.createPathItem().GET(OASFactory.createOperation()))
                .components(OASFactory.createComponents().addSchema(PET, OASFactory.createSchema()));

        FragmentMerger.mergeInto(target, fragment);

        assertEquals(1, target.getPaths().getPathItems().size());
        assertEquals(1, target.getComponents().getSchemas().size());
    }

    @Test
    void sharedPathTemplateCollectsTheOperationsAndParametersOfEveryFragment() {
        OpenAPI target = fragment(OASFactory.createPathItem().GET(OASFactory.createOperation().operationId("list")));
        PathItem other = OASFactory.createPathItem()
                .PUT(OASFactory.createOperation()).POST(OASFactory.createOperation())
                .DELETE(OASFactory.createOperation()).OPTIONS(OASFactory.createOperation())
                .HEAD(OASFactory.createOperation()).PATCH(OASFactory.createOperation())
                .TRACE(OASFactory.createOperation())
                .addParameter(OASFactory.createParameter().name("id"));

        FragmentMerger.mergeInto(target, fragment(other));

        PathItem merged = target.getPaths().getPathItem(PATH);
        assertEquals(8, merged.getOperations().size());
        assertEquals("list", merged.getGET().getOperationId());
        assertEquals(1, merged.getParameters().size());
    }

    @Test
    void anOperationOfTheLaterFragmentReplacesTheSameMethod() {
        OpenAPI target = fragment(OASFactory.createPathItem().GET(OASFactory.createOperation().operationId("a")));

        FragmentMerger.mergeInto(target,
                fragment(OASFactory.createPathItem().GET(OASFactory.createOperation().operationId("b"))));

        assertEquals("b", target.getPaths().getPathItem(PATH).getGET().getOperationId());
    }

    @Test
    void anExistingSchemaIsKept() {
        Schema first = OASFactory.createSchema().description("first");
        OpenAPI target = OASFactory.createOpenAPI().components(OASFactory.createComponents().addSchema(PET, first));
        OpenAPI fragment = OASFactory.createOpenAPI().components(OASFactory.createComponents()
                .addSchema(PET, OASFactory.createSchema().description("second"))
                .addSchema("Owner", OASFactory.createSchema()));

        FragmentMerger.mergeInto(target, fragment);

        assertSame(first, target.getComponents().getSchemas().get(PET));
        assertEquals(2, target.getComponents().getSchemas().size());
    }

    @Test
    void schemasAreAddedToComponentsWithoutSchemas() {
        OpenAPI target = OASFactory.createOpenAPI().components(OASFactory.createComponents());

        FragmentMerger.mergeInto(target, OASFactory.createOpenAPI()
                .components(OASFactory.createComponents().addSchema(PET, OASFactory.createSchema())));

        assertEquals(1, target.getComponents().getSchemas().size());
    }
}
