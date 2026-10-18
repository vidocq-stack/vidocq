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

import io.vidocq.ibarra.spi.PageDescriptor;
import io.vidocq.ibarra.spi.PageIndex;
import io.vidocq.runtime.spi.ApplicationLayer;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.List;
import java.util.ServiceLoader;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Jakarta Pages 4.0 through Ibarra (vidocq#232). The Servlet extension's Foy finds Ibarra's JSP engine
 * itself, in the application layer; this extension puts Ibarra and Peano in the module graph and reports
 * the pages {@code vidocq:generate} compiled. The startup report reads a snapshot taken at start.
 */
public final class IbarraPagesExtension implements VidocqExtension, StartupReportContributor {

    private final Supplier<List<String>> catalog;
    private volatile List<String> pages;

    /** Created by the runtime's service loader: the pages of the application layer. */
    public IbarraPagesExtension() {
        this(() -> pages(ApplicationLayer.current().orElse(ModuleLayer.boot())));
    }

    IbarraPagesExtension(Supplier<List<String>> catalog) {
        this.catalog = catalog;
    }

    @Override public String name() { return "ibarra-pages"; }
    @Override public String id() { return "pages"; }
    @Override public String title() { return "Jakarta Pages (Ibarra)"; }

    @Override
    public void onStart(ExtensionContext context) {
        pages = catalog.get();
    }

    @Override
    public void onStop() {
        pages = null;
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        List<String> compiled = pages;
        section.summary("Jakarta Pages 4.0");
        if (compiled == null) {
            section.row("pages", "not initialized");
        } else if (compiled.isEmpty()) {
            section.row("pages", "none compiled")
                    .anomaly("ibarra-no-pages", "No JSP page was compiled into the application",
                            "put the pages under src/main/resources/META-INF/resources/, require io.vidocq.ibarra.api "
                                    + "in module-info.java, and build with vidocq:generate");
        } else {
            section.row("pages", compiled.size()).list("paths", compiled);
        }
    }

    /** The paths of the pages that the page indexes of {@code layer}'s own modules list. */
    static List<String> pages(ModuleLayer layer) {
        var paths = new TreeSet<String>();
        ServiceLoader.load(layer, PageIndex.class).stream()
                .filter(provider -> layer.modules().contains(provider.type().getModule()))
                .map(ServiceLoader.Provider::get)
                .flatMap(index -> index.pages().stream())
                .map(PageDescriptor::path)
                .forEach(paths::add);
        return List.copyOf(paths);
    }
}
