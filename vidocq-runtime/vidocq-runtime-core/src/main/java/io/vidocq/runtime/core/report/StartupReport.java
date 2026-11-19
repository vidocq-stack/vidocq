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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one boot reports about itself: the model {@link StartupReportRenderer} prints as the single INFO
 * record {@code Vidocq startup report}. It holds strings and numbers only, never a class: the application
 * layer of a dev reload stays collectable.
 *
 * <p>It is complete at every {@link Verbosity}, {@link Verbosity#OFF} included, where it is simply not
 * printed. A boot that fails leaves a partial report: its {@link #failedPhase()} names the phase that
 * failed, and only the phases before it and the sections already known are there.
 *
 * @param launchMode   the launch mode of the boot
 * @param launchReason why, as the header prints it between parentheses ({@code auto: IntelliJ agent},
 *                     {@code vidocq.launch.mode}), or {@code null}
 * @param verbosity    how much the report shows
 * @param phases       the phases that ended, in order
 * @param sections     the sections, in the order they are printed
 * @param anomalies    the anomalies already logged, in order
 * @param failedPhase  the phase that failed, such as {@code build} or {@code onStart chappe-bootstrap}, or
 *                     {@code null} when the boot succeeded
 * @param runtime      the Vidocq version and the JVM, {@code 0.4.0-SNAPSHOT on Java 25}, or {@code null} when
 *                     neither the report nor the banner needed it
 */
public record StartupReport(LaunchMode launchMode, String launchReason, Verbosity verbosity, List<Phase> phases,
                            List<Section> sections, List<Anomaly> anomalies, String failedPhase, String runtime) {

    public StartupReport {
        Objects.requireNonNull(launchMode, "launchMode");
        Objects.requireNonNull(verbosity, "verbosity");
        phases = phases == null ? List.of() : List.copyOf(phases);
        sections = sections == null ? List.of() : List.copyOf(sections);
        anomalies = anomalies == null ? List.of() : List.copyOf(anomalies);
    }

    /** Whether the boot failed, so that this report stops at {@link #failedPhase()}. */
    public boolean failed() {
        return failedPhase != null;
    }

    /** The codes of the {@link #anomalies()}, in order, repeated when an anomaly was. */
    public List<String> anomalyCodes() {
        return anomalies.stream().map(Anomaly::code).toList();
    }

    /** The section with this id, if the report has one. */
    public Optional<Section> section(String id) {
        return sections.stream().filter(section -> section.id().equals(id)).findFirst();
    }
}
