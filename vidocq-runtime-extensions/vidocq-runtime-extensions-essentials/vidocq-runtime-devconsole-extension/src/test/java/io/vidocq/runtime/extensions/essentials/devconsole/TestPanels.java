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

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The contributors and panels the snapshot tests show. */
final class TestPanels {

    private TestPanels() {}

    /** A panel with boot facts, one chart and a value per pool, counting its samples. */
    static final class PoolPanel implements DevConsolePanel {

        final AtomicInteger samples = new AtomicInteger();

        @Override
        public String id() {
            return "acme-pool";
        }

        @Override
        public String title() {
            return "Acme pools";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("1 pool, 8 connections max")
                    .row("main", "jdbc:h2:mem:acme")
                    .secret("main password", true);
        }

        @Override
        public List<Chart> charts() {
            return List.of(new Chart("connections", "Connections",
                    List.of(Series.area("active"), Series.ceiling("active"))));
        }

        @Override
        public void sample(PanelSample sample) {
            samples.incrementAndGet();
            sample.group("main").gauge("active", 3, 8, Unit.COUNT);
        }
    }

    /** A panel whose samples fail with a message that holds a secret. */
    static final class BrokenPanel implements DevConsolePanel {

        @Override
        public String id() {
            return "broken";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("boot facts survive");
        }

        @Override
        public List<Chart> charts() {
            throw new IllegalStateException("no charts either");
        }

        @Override
        public void sample(PanelSample sample) {
            sample.gauge("half", 1, Unit.COUNT);
            throw new IllegalStateException("password=hunter2");
        }
    }

    /** A panel whose samples fail with an error, not an exception. */
    static final class LinkagePanel implements DevConsolePanel {

        @Override
        public String id() {
            return "linkage";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("a class is missing");
        }

        @Override
        public void sample(PanelSample sample) {
            throw new NoClassDefFoundError("com/acme/Missing");
        }
    }

    /** A contributor that is no panel: boot facts only. */
    static final class FactsOnly implements StartupReportContributor {

        @Override
        public String id() {
            return "facts";
        }

        @Override
        public String title() {
            return "Facts only";
        }

        @Override
        public void contribute(StartupReportContext context, StartupReportSection section) {
            section.summary("2 things").list("things", List.of("a", "b"));
        }
    }
}
