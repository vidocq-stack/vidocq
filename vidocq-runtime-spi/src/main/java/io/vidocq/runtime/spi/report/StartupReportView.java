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

import java.util.List;

/**
 * The startup report of one boot, read-only: what a development tool, such as the dev console, shows of
 * it while the application runs. An extension reads it through
 * {@link io.vidocq.runtime.spi.ExtensionContext#startupReport()}; nothing in it can be changed, so no
 * extension can add an anomaly or a section to a report that is written.
 *
 * <p>A view belongs to one boot and never changes: a dev reload boots again and publishes a view of its
 * own. It may be read from any thread. It holds text only, except for {@link #contributors()}.
 *
 * <p>Every value is plain text that the application and its libraries wrote: a reader escapes it for
 * where it shows it, such as a web page, and never renders it as markup.
 */
public interface StartupReportView {

    /**
     * How this JVM was launched, as this boot resolved it.
     *
     * @return the launch mode, never {@code null}
     */
    LaunchMode launchMode();

    /**
     * Why the launch mode was resolved that way, as the report's header prints it between parentheses,
     * such as {@code vidocq.launch.mode} or {@code auto: IntelliJ agent}.
     *
     * @return the reason, or {@code null} when the report gives none
     */
    String launchReason();

    /**
     * Every anomaly of this boot, the core's and the contributors', in the order they were logged. The
     * detailed report recalls only the first ones; this list has them all.
     *
     * @return the anomalies, an immutable list, empty when there were none
     */
    List<ReportAnomaly> anomalies();

    /**
     * The sections of the report, in the order the detailed report prints them: the core's, then the
     * contributors' in the order they were called. The anomalies the report recalls are not a section
     * here: {@link #anomalies()} has them.
     *
     * <p>A contributor's rows and lists are there as far as it wrote them: below
     * {@link Verbosity#DETAILED} it may skip them (see {@link StartupReportContext#verbosity()}).
     *
     * @return the sections, an immutable list
     */
    List<ReportSection> sections();

    /**
     * The whole report as the log prints it at {@link Verbosity#DETAILED}, whatever level this boot
     * logged it at, cleaned and cut to size like the record itself: the text a user copies into a bug
     * report. It may be rendered on the first call; every call returns the same text.
     *
     * @return the text, several lines, never {@code null}
     */
    String detailedText();

    /**
     * The contributors this boot called, the very instances, in the order of their sections: the
     * extensions that are contributors first, then the services. A reader may look for another interface
     * they implement, such as a dev console panel, but never calls
     * {@link StartupReportContributor#contribute contribute}: the report is written.
     *
     * <p>They belong to this boot: after a dev reload, the view of the next boot has its own.
     *
     * @return the contributors, an immutable list, empty when there were none
     */
    List<StartupReportContributor> contributors();
}
