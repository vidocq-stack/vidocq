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
package io.vidocq.grimm.cdi;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.junit.jupiter.api.Test;

/**
 * The document is serialized once per format and per document: serializing it walks the whole model by reflection,
 * which every request to /openapi repeated.
 */
class OpenApiResourceRenderOnceTest {

    private static OpenAPI document(String title) {
        return OASFactory.createObject(OpenAPI.class).openapi("3.1.0")
                .info(OASFactory.createObject(org.eclipse.microprofile.openapi.models.info.Info.class)
                        .title(title).version("1"));
    }

    @Test
    void theSameDocumentIsSerializedOncePerFormat() {
        OpenAPI doc = document("t");
        OpenApiResource resource = OpenApiResource.fromSupplier(() -> doc);

        String json = resource.render("json", null).body();
        String yaml = resource.render("yaml", null).body();

        assertSame(json, resource.render("json", null).body());
        assertSame(yaml, resource.render("yaml", null).body());
        assertTrue(json.contains("\"openapi\""));
        assertTrue(yaml.contains("openapi:"));
    }

    /** A new document, as after a reload in dev mode, is serialized again. */
    @Test
    void aNewDocumentIsSerializedAgain() {
        AtomicReference<OpenAPI> current = new AtomicReference<>(document("before"));
        OpenApiResource resource = OpenApiResource.fromSupplier(current::get);
        String before = resource.render("json", null).body();

        current.set(document("after"));
        String after = resource.render("json", null).body();

        assertNotSame(before, after);
        assertTrue(after.contains("after"));
    }
}
