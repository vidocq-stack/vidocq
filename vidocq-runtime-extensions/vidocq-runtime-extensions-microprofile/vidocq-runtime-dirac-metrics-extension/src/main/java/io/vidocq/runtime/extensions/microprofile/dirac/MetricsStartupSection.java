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
package io.vidocq.runtime.extensions.microprofile.dirac;

import io.vidocq.runtime.extensions.microprofile.dirac.MetricsScope.Entry;
import io.vidocq.runtime.extensions.microprofile.dirac.MetricsScope.Kind;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.List;
import java.util.Locale;

/**
 * Writes the {@code metrics} section of the startup report, which is also the boot facts of the {@code metrics}
 * panel of the dev console.
 *
 * <ul>
 *   <li>the summary: how many metrics each registry holds, {@code 3 application metrics, 13 base, 0 vendor};</li>
 *   <li>a row per registry: its metrics by kind;</li>
 *   <li>a list {@code <scope> metrics} per registry that holds any counter, timer or histogram: each one's key in the
 *       panel, its kind, then its name and tags, {@code checkout.shop-eu, checkout.shop-eu.mean = timer
 *       checkout{shop=eu}}, so that a key shortened or numbered by {@link MetricKeys} still reads;</li>
 *   <li>a list {@code <scope> gauges}: the gauges by name and tags. They are never called, here or in the panel.</li>
 * </ul>
 * The rows and the lists are written at {@link Verbosity#DETAILED} only.
 */
final class MetricsStartupSection {

    private MetricsStartupSection() {}

    /**
     * Writes the section.
     *
     * @param registries the registries of the existing producer, or {@code null} when there is none
     * @param absence    why there is none, such as {@code not created yet}; read only when {@code registries} is
     *                   {@code null}
     * @param context    the report being written
     * @param section    where the section goes
     */
    static void write(DiracRegistries registries, String absence, StartupReportContext context,
            StartupReportSection section) {
        if (registries == null) {
            section.summary("registries " + absence);
            return;
        }
        List<MetricsScope> scopes = registries.read();
        section.summary(scopes.get(0).size() + " application metrics, " + scopes.get(1).size() + " base, "
                + scopes.get(2).size() + " vendor");
        if (context.verbosity() != Verbosity.DETAILED) {
            return;
        }
        for (MetricsScope scope : scopes) {
            section.row(scope.scope(), plural(scope.count(Kind.COUNTER), "counter") + ", "
                    + plural(scope.count(Kind.TIMER), "timer") + ", "
                    + plural(scope.count(Kind.HISTOGRAM), "histogram") + ", "
                    + plural(scope.gauges().size(), "gauge"));
        }
        for (MetricsScope scope : scopes) {
            if (!scope.sampled().isEmpty()) {
                section.list(scope.scope() + " metrics", scope.sampled().stream().map(MetricsStartupSection::item)
                        .toList());
            }
            if (!scope.gauges().isEmpty()) {
                section.list(scope.scope() + " gauges", scope.gauges().stream().map(MetricsScope::describe).toList());
            }
        }
    }

    private static String item(Entry entry) {
        String keys = entry.kind() == Kind.TIMER ? entry.key() + ", " + entry.key() + MetricsScope.MEAN : entry.key();
        return keys + " = " + entry.kind().name().toLowerCase(Locale.ROOT) + " "
                + MetricsScope.describe(entry.id());
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
