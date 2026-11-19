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
package io.vidocq.runtime.extensions.jakartaee.web.ibarra;

import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** vidocq#232: the startup report lists the JSP pages compiled into the application. */
class IbarraPagesExtensionTest {

    /** Records what the contributor writes. */
    private static final class Section implements StartupReportSection {
        String summary;
        final Map<String, String> rows = new LinkedHashMap<>();
        final Map<String, List<String>> lists = new LinkedHashMap<>();
        final List<String> anomalies = new ArrayList<>();
        @Override public StartupReportSection summary(String text) { summary = text; return this; }
        @Override public StartupReportSection row(String key, Object value) { rows.put(key, String.valueOf(value)); return this; }
        @Override public StartupReportSection list(String key, Collection<String> items) { lists.put(key, List.copyOf(items)); return this; }
        @Override public StartupReportSection secret(String key, boolean configured) { return this; }
        @Override public StartupReportSection listener(String name, String boundBaseUri) { return this; }
        @Override public StartupReportSection route(String listener, String method, String path, String handler) { return this; }
        @Override public StartupReportSection anomaly(String code, String message, String hint) { anomalies.add(code); return this; }
    }

    @Test
    void the_compiled_pages_are_counted_and_listed() {
        var extension = new IbarraPagesExtension(() -> List.of("/admin/panel.jsp", "/index.jsp"));
        extension.onStart(null);
        var section = new Section();

        extension.contribute(null, section);

        assertEquals("Jakarta Pages 4.0", section.summary);
        assertEquals("2", section.rows.get("pages"));
        assertEquals(List.of("/admin/panel.jsp", "/index.jsp"), section.lists.get("paths"));
        assertEquals(List.of(), section.anomalies);
    }

    @Test
    void no_compiled_page_is_an_anomaly_with_a_hint() {
        var extension = new IbarraPagesExtension(List::of);
        extension.onStart(null);
        var section = new Section();

        extension.contribute(null, section);

        assertEquals("none compiled", section.rows.get("pages"));
        assertEquals(List.of("ibarra-no-pages"), section.anomalies);
    }

    @Test
    void before_start_and_after_stop_nothing_is_claimed() {
        var extension = new IbarraPagesExtension(() -> List.of("/index.jsp"));
        var section = new Section();
        extension.contribute(null, section);
        assertEquals("not initialized", section.rows.get("pages"));

        extension.onStart(null);
        extension.onStop();
        var after = new Section();
        extension.contribute(null, after);
        assertEquals("not initialized", after.rows.get("pages"));
    }

    @Test
    void a_layer_without_page_index_has_no_page() {
        assertTrue(IbarraPagesExtension.pages(ModuleLayer.boot()).isEmpty());
    }
}
