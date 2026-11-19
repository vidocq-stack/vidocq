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
 * How much the startup report shows on this boot, resolved by Vidocq from {@code vidocq.startup.report}
 * and the {@link LaunchMode}.
 *
 * <p>Every level calls the {@linkplain StartupReportContributor contributors}: only the rendering
 * changes, and an {@linkplain StartupReportSection#anomaly anomaly} is logged whatever the level.
 */
public enum Verbosity {

    /** No report: only the anomalies are logged, each as its own warning. */
    OFF,
    /** One line per section: a contributor's {@linkplain StartupReportSection#summary summary line}. */
    SUMMARY,
    /** Every section in full: a contributor's summary line, then its rows and lists. */
    DETAILED
}
