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
package io.vidocq.runtime.spi.report;

/**
 * Adds one section to the startup report, the single INFO record Vidocq logs at the end of a boot,
 * just before {@code Vidocq - Started in}.
 *
 * <p>A contributor is normally a Vidocq extension: a {@link io.vidocq.runtime.spi.VidocqExtension}
 * that also implements this interface is found with no second declaration, and its section comes
 * first, in extension priority order. A library that is not an extension declares its contributor
 * as a service, twice: {@code provides} in its {@code module-info} for the module path, and a
 * {@code META-INF/services} file for the class path, where the JDK ignores the former. Those
 * sections follow the extensions', by {@link #order()} then {@link #id()}.
 *
 * <p>The application layer can load a library a second time, so the same contributor class may be
 * found twice: Vidocq keeps one per class name, before instantiating either.
 *
 * <p>A contributor only describes. It never formats, pads, truncates or logs: Vidocq renders every
 * value, sanitizes it and cuts it to size (see {@link StartupReportSection}).
 */
public interface StartupReportContributor {

    /**
     * A stable lowercase identifier, such as {@code mcp}: the section's name in the report, and what
     * keeps a section from appearing twice. A second contributor of another class with the same id
     * is skipped with a {@code VIDOCQ-RPT-002} warning; a {@code null} or blank id skips the
     * contributor with {@code VIDOCQ-RPT-001}.
     *
     * @return the id, neither {@code null} nor blank
     */
    String id();

    /**
     * The section title, such as {@code MCP server (langchain4j-cdi)}.
     *
     * @return the title; the {@link #id()} by default
     */
    default String title() {
        return id();
    }

    /**
     * The position of this section among the contributors declared as services, ascending, ties
     * broken by {@link #id()}. Extensions come before all of them, in priority order, whatever this
     * returns.
     *
     * @return the position; {@code 1000} by default
     */
    default int order() {
        return 1000;
    }

    /**
     * Writes this contributor's section.
     *
     * <p>Called once per boot, on the booting thread, after every
     * {@link io.vidocq.runtime.spi.VidocqExtension#onStart} and before {@code Vidocq - Started in} —
     * at every {@link Verbosity}, {@link Verbosity#OFF} included, so that an
     * {@linkplain StartupReportSection#anomaly anomaly} is never lost. It must read memory only: no
     * I/O, no network, no blocking call, no bean it does not need; its duration is measured and shown
     * in the {@link Verbosity#DETAILED detailed} report.
     *
     * <p>A {@link RuntimeException} or a {@link LinkageError} thrown here drops the section with a
     * {@code VIDOCQ-RPT-001} warning, the anomalies already reported stay, and the boot goes on.
     *
     * @param context what the contributor may read: the level, the launch mode, the beans, the routes
     * @param section where the contributor writes
     */
    void contribute(StartupReportContext context, StartupReportSection section);
}
