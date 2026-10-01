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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.runtime.spi.ApplicationLayer;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The console's own {@code cdi} panel, shown after the contributed panels and before the {@linkplain JvmPanel JVM}:
 * what Vauban, the runtime's CDI container, holds for this boot, as Quarkus's ArC card shows it.
 *
 * <ul>
 *   <li><b>Boot facts:</b> a summary, such as {@code 142 beans (121 dependent, 18 application-scoped, 3
 *       request-scoped), 4 interceptors, 0 decorators, 12 observers}; then {@code beans}, {@code interceptors} and
 *       {@code observers}, each with how many are the application's; {@code scopes}; {@code decorators}, always
 *       none, since Vauban implements CDI Lite; {@code application}, how the application's classes were told apart;
 *       {@code beans codegen}, {@code interceptors codegen}, {@code observers codegen}: how many rows each
 *       code-generation verdict has; {@code <table> left out} when a table could not show every row; and
 *       {@code source}.</li>
 *   <li><b>Tables:</b> {@code beans} (class, kind, scope, qualifiers without {@code @Any}, alternative, from),
 *       {@code interceptors} (class, bindings, priority, from) and {@code observers} (event type, qualifiers,
 *       declaring class and method, sync or async, from), then, for every row, codegen (the generator that covers
 *       it, partial, reflection, unknown or n/a) and by reflection (what falls back); the application's rows first,
 *       {@value CdiInventory#MAX_ROWS} rows each at most. A table with no row is written absent.</li>
 * </ul>
 *
 * <p>Everything is read once, in the console's {@code onStart}, from metadata only: the bean manager's
 * {@code getBeans(Object.class, @Any)}, the interceptor manager and the event dispatcher. No bean is created, no
 * context is read. The tables are written by {@link #sample}, not as boot facts, because a sample's table has column
 * heads and the boot facts of a panel do not; they are computed once, so {@code sample} only hands the same immutable
 * rows to the page. The panel keeps strings only, in a {@link CdiInventory}, and is dropped with the console's snapshot
 * in {@code onStop}.
 */
final class CdiPanel implements DevConsolePanel {

    /** The panel's id, reserved by the core for the console. */
    static final String ID = "cdi";

    private static final String NONE = "none";

    /** What the container held at boot, or {@code null} when there was no container to read. */
    private final CdiInventory inventory;

    /** @param inventory what the container held at boot, or {@code null} when there was none */
    CdiPanel(CdiInventory inventory) {
        this.inventory = inventory;
    }

    /**
     * The panel of {@code container}, read now.
     *
     * @param container the running container, or {@code null}
     */
    static CdiPanel of(VaubanContainer container) {
        if (container == null) {
            return new CdiPanel(null);
        }
        CdiInventory.ApplicationClasses application = CdiInventory.ApplicationClasses.of(ApplicationLayer.current(),
                System.getProperty("jdk.module.main"));
        return new CdiPanel(CdiInventory.read(container, application));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "CDI (Vauban)";
    }

    /** The counts, by scope for the beans, and how many rows each table left out. */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        CdiInventory held = inventory;
        if (held == null) {
            section.summary("not available: no container");
            return;
        }
        StringBuilder summary = new StringBuilder(count(held.beans(), "bean"));
        if (!held.scopes().isEmpty()) {
            summary.append(held.scopes().entrySet().stream().map(CdiPanel::scopeCount)
                    .collect(Collectors.joining(", ", " (", ")")));
        }
        summary.append(", ").append(count(held.interceptors(), "interceptor"))
                .append(", 0 decorators, ").append(count(held.observers(), "observer"));
        section.summary(summary.toString())
                .row("beans", sides(held.beans(), held.applicationBeans()))
                .list("scopes", held.scopes().entrySet().stream().map(CdiPanel::scopeCount).toList())
                .row("interceptors", sides(held.interceptors(), held.applicationInterceptors()))
                .row("decorators", "none: Vauban implements CDI Lite, which has no decorators")
                .row("observers", sides(held.observers(), held.applicationObservers()))
                .row("application", held.applicationRule());
        codegen(section, "beans", held.beanCodegen());
        codegen(section, "interceptors", held.interceptorCodegen());
        codegen(section, "observers", held.observerCodegen());
        leftOut(section, "beans", held.beans(), held.beanRows());
        leftOut(section, "interceptors", held.interceptors(), held.interceptorRows());
        leftOut(section, "observers", held.observers(), held.observerRows());
        section.row("source", "Vauban's metadata, read once at boot: no bean created");
    }

    /** The three tables, as they were read at boot. */
    @Override
    public void sample(PanelSample sample) {
        CdiInventory held = inventory;
        if (held == null) {
            return;
        }
        table(sample, "beans", CdiInventory.BEAN_COLUMNS, held.beanRows());
        table(sample, "interceptors", CdiInventory.INTERCEPTOR_COLUMNS, held.interceptorRows());
        table(sample, "observers", CdiInventory.OBSERVER_COLUMNS, held.observerRows());
    }

    private static void table(PanelSample sample, String key, List<String> columns, List<List<String>> rows) {
        if (rows.isEmpty()) {
            sample.absent(key, NONE);
        } else {
            sample.table(key, columns, rows);
        }
    }

    private static void codegen(StartupReportSection section, String table, String summary) {
        if (!summary.isEmpty()) {
            section.row(table + " codegen", summary);
        }
    }

    private static void leftOut(StartupReportSection section, String table, int total, List<List<String>> rows) {
        if (total > rows.size()) {
            section.row(table + " left out", (total - rows.size()) + ", past the " + CdiInventory.MAX_ROWS
                    + " rows the table shows");
        }
    }

    private static String sides(int total, int application) {
        return total + ": " + application + " of the application, " + (total - application) + " of the libraries";
    }

    private static String scopeCount(Map.Entry<String, Integer> scope) {
        return scope.getValue() + " " + scope.getKey();
    }

    private static String count(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
