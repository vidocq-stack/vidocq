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
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.List;

/**
 * A static section and its live panel, seen as one {@link DevConsolePanel}: the contributor gives the id, the title
 * and the section; the live panel gives the samples, the charts, the actions and the languages. What
 * {@link PanelEntry} and {@link Snapshot} already know how to show.
 */
final class LivePanelAdapter implements DevConsolePanel {

    private final StartupReportContributor contributor;
    private final LivePanel live;

    LivePanelAdapter(StartupReportContributor contributor, LivePanel live) {
        this.contributor = contributor;
        this.live = live;
    }

    LivePanel live() {
        return live;
    }

    @Override
    public String id() {
        return contributor.id();
    }

    @Override
    public String title() {
        return contributor.title();
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        contributor.contribute(context, section);
    }

    @Override
    public List<Chart> charts() {
        return live.charts();
    }

    @Override
    public List<PanelAction> actions() {
        return live.actions();
    }

    @Override
    public List<PanelLanguage> languages() {
        return live.languages();
    }

    @Override
    public void sample(PanelSample sample) {
        live.sample(sample);
    }
}
