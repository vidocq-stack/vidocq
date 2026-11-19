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
package io.vidocq.humboldt.sdk.common;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceTest {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> HOST_NAME = AttributeKey.stringKey("host.name");
    private static final AttributeKey<Long> PROCESS_PID = AttributeKey.longKey("process.pid");

    @Test
    void empty_is_singleton_and_has_no_attributes() {
        assertSame(Resource.empty(), Resource.empty());
        assertTrue(Resource.empty().attributes().isEmpty());
        assertNull(Resource.empty().schemaUrl());
    }

    @Test
    void create_preserves_attributes_and_schema_url() {
        Attributes attrs = Attributes.of(SERVICE_NAME, "humboldt-test");
        Resource r = Resource.create(attrs, "https://opentelemetry.io/schemas/1.27.0");
        assertEquals(attrs, r.attributes());
        assertEquals("https://opentelemetry.io/schemas/1.27.0", r.schemaUrl());
    }

    @Test
    void merge_other_wins_on_conflict() {
        Resource base = Resource.create(Attributes.of(
                SERVICE_NAME, "old-name",
                HOST_NAME, "node-01"));
        Resource overlay = Resource.create(Attributes.of(
                SERVICE_NAME, "new-name",
                PROCESS_PID, 4242L));

        Resource merged = base.merge(overlay);
        assertEquals("new-name", merged.attributes().get(SERVICE_NAME));
        assertEquals("node-01", merged.attributes().get(HOST_NAME));
        assertEquals(4242L, merged.attributes().get(PROCESS_PID));
    }

    @Test
    void merge_null_returns_self() {
        Resource r = Resource.create(Attributes.of(SERVICE_NAME, "x"));
        assertSame(r, r.merge(null));
    }

    @Test
    void merge_schema_url_overlay_wins_when_present() {
        Resource base = Resource.create(Attributes.empty(), "https://schema/base");
        Resource overlay = Resource.create(Attributes.of(SERVICE_NAME, "x"), "https://schema/overlay");
        assertEquals("https://schema/overlay", base.merge(overlay).schemaUrl());
    }

    @Test
    void merge_schema_url_keeps_base_when_overlay_null() {
        Resource base = Resource.create(Attributes.of(HOST_NAME, "h"), "https://schema/base");
        Resource overlay = Resource.create(Attributes.of(SERVICE_NAME, "x"));
        assertEquals("https://schema/base", base.merge(overlay).schemaUrl());
    }

    @Test
    void equals_and_hashCode_by_value() {
        Resource a = Resource.create(Attributes.of(SERVICE_NAME, "s"), "u");
        Resource b = Resource.create(Attributes.of(SERVICE_NAME, "s"), "u");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
