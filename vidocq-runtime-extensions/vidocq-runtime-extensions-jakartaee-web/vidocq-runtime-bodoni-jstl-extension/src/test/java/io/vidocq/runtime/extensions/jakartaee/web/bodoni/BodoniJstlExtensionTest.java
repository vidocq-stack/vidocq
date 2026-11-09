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
package io.vidocq.runtime.extensions.jakartaee.web.bodoni;

import io.vidocq.runtime.spi.report.StartupReportSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** vidocq#266: the startup report lists the tag libraries of Bodoni that the pages can use. */
class BodoniJstlExtensionTest {

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
    void the_tag_libraries_are_counted_and_listed() {
        var extension = new BodoniJstlExtension(() -> List.of("c", "fmt", "fn"));
        extension.onStart(null);
        var section = new Section();

        extension.contribute(null, section);

        assertEquals("Jakarta Standard Tag Library 3.0", section.summary);
        assertEquals("3", section.rows.get("taglibs"));
        assertEquals(List.of("c", "fmt", "fn"), section.lists.get("prefixes"));
        assertEquals(List.of(), section.anomalies);
    }

    @Test
    void no_tag_library_is_an_anomaly_with_a_hint() {
        var extension = new BodoniJstlExtension(List::of);
        extension.onStart(null);
        var section = new Section();

        extension.contribute(null, section);

        assertEquals("none found", section.rows.get("taglibs"));
        assertEquals(List.of("bodoni-no-taglibs"), section.anomalies);
    }

    @Test
    void before_start_and_after_stop_nothing_is_claimed() {
        var extension = new BodoniJstlExtension(() -> List.of("c"));
        var section = new Section();
        extension.contribute(null, section);
        assertEquals("not initialized", section.rows.get("taglibs"));

        extension.onStart(null);
        extension.onStop();
        var after = new Section();
        extension.contribute(null, after);
        assertEquals("not initialized", after.rows.get("taglibs"));
    }

    @Test
    void the_five_libraries_of_bodoni_are_found_in_its_module() {
        var libraries = BodoniJstlExtension.libraries(ModuleLayer.boot());
        // Surefire runs this module on the module path; on a class path run Bodoni is not a named module.
        if (ModuleLayer.boot().findModule(BodoniJstlExtension.BODONI_MODULE).isPresent()) {
            assertEquals(List.of("c", "fmt", "fn", "sql", "x"), libraries);
        }
    }

    @Test
    void a_layer_without_bodoni_has_no_library() {
        assertEquals(List.of(), BodoniJstlExtension.libraries(ModuleLayer.empty()));
    }
}
