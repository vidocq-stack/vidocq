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

import io.vidocq.runtime.spi.ApplicationLayer;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Jakarta Standard Tag Library 3.0 through Bodoni. Ibarra compiles the pages and the Servlet extension serves
 * them; this extension puts Bodoni's tag handlers and its Peano accessor tables in the module graph, and
 * reports the tag libraries available to the pages. The startup report reads a snapshot taken at start.
 */
public final class BodoniJstlExtension implements VidocqExtension, StartupReportContributor {

    /** The id of this extension, and the row of its report section. */
    private static final String TAGLIBS = "taglibs";

    /** The Java module of Bodoni's tag handlers. */
    static final String BODONI_MODULE = "io.vidocq.bodoni.core";

    /** The package of Bodoni that each tag library lives in, and the prefix the specification gives it. */
    private static final Map<String, String> LIBRARIES = Map.of(
            "io.vidocq.bodoni.core.tags.core", "c",
            "io.vidocq.bodoni.core.tags.fmt", "fmt",
            "io.vidocq.bodoni.core.functions", "fn",
            "io.vidocq.bodoni.core.tags.xml", "x",
            "io.vidocq.bodoni.core.tags.sql", "sql");

    private final Supplier<List<String>> catalog;
    private final AtomicReference<List<String>> libraries = new AtomicReference<>();

    /** Created by the runtime's service loader: the tag libraries of the application layer. */
    public BodoniJstlExtension() {
        this(() -> {
            var found = ApplicationLayer.current().map(BodoniJstlExtension::libraries).orElse(List.of());
            return found.isEmpty() ? libraries(ModuleLayer.boot()) : found;
        });
    }

    BodoniJstlExtension(Supplier<List<String>> catalog) {
        this.catalog = catalog;
    }

    @Override public String name() { return "bodoni-jstl"; }
    @Override public String id() { return "jstl"; }
    @Override public String title() { return "Jakarta Standard Tag Library (Bodoni)"; }

    @Override
    public void onStart(ExtensionContext context) {
        libraries.set(catalog.get());
    }

    @Override
    public void onStop() {
        libraries.set(null);
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        List<String> available = libraries.get();
        section.summary("Jakarta Standard Tag Library 3.0");
        if (available == null) {
            section.row(TAGLIBS, "not initialized");
        } else if (available.isEmpty()) {
            section.row(TAGLIBS, "none found")
                    .anomaly("bodoni-no-taglibs", "Bodoni's tag libraries are not in the module graph",
                            "require io.vidocq.bodoni.core in the application's module-info.java");
        } else {
            section.row(TAGLIBS, available.size()).list("prefixes", available);
        }
    }

    /** The prefixes of the tag libraries that Bodoni's module exports in {@code layer}. */
    static List<String> libraries(ModuleLayer layer) {
        Optional<Module> bodoni = layer.findModule(BODONI_MODULE);
        var prefixes = new TreeSet<String>();
        bodoni.ifPresent(module -> module.getDescriptor().exports().stream()
                .map(export -> LIBRARIES.get(export.source()))
                .filter(java.util.Objects::nonNull)
                .forEach(prefixes::add));
        return List.copyOf(prefixes);
    }
}
