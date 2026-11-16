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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;
import java.util.List;

/**
 * Writes the {@code noether-persistence} section of the startup report from the facts of the started units: a summary
 * in every boot, and a row per unit in a {@code dev} boot or a detailed report.
 */
final class PersistenceSection {

    private PersistenceSection() {
    }

    static void write(List<UnitFacts> units, LaunchMode mode, Verbosity verbosity, StartupReportSection section) {
        if (units.isEmpty()) {
            section.summary("no persistence unit is injected");
            return;
        }
        section.summary(count(units.size(), "persistence unit", "persistence units") + ", "
                + count(units.stream().mapToInt(UnitFacts::entities).sum(), "entity", "entities"));
        if (mode != LaunchMode.DEV && verbosity != Verbosity.DETAILED) {
            return;
        }
        for (UnitFacts unit : units) {
            section.row("unit " + unit.name(), (unit.jta() ? "JTA" : "RESOURCE_LOCAL")
                    + (unit.pool() == null ? " on the default pool" : " on pool " + unit.pool())
                    + ", schema action " + unit.schemaAction()
                    + ", " + count(unit.entities(), "entity", "entities")
                    + ", " + count(unit.embeddables(), "embeddable", "embeddables")
                    + ", " + count(unit.namedQueries(), "named query", "named queries")
                    + ", " + count(unit.indexes(), "index", "indexes"));
        }
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }
}
