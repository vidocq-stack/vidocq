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

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;

/**
 * The {@code metrics} section of the startup report and panel of the dev console: what Dirac's registries hold.
 *
 * <p>Dirac itself needs no Vidocq code: its build compatible extension and its JAX-RS resource do the integration
 * (see the module description). This class only reads what Dirac publishes, from the
 * {@code MetricRegistryProducerBean} instance the application created, and never creates it: see
 * {@link DiracLiveBean}. {@link MetricsStartupSection} writes the boot facts, {@link MetricsPanel} the live values.
 */
public final class DiracMetricsExtension implements VidocqExtension, DevConsolePanel {

    static final String NAME = "dirac-metrics";
    static final String SECTION_ID = "metrics";
    static final String SECTION_TITLE = "Metrics (Dirac)";

    /**
     * The producer bean {@link #sample} reads, resolved once in {@link #onStart}; {@link DiracLiveBean#NONE} before
     * it and after {@link #onStop}.
     */
    private volatile DiracLiveBean live = DiracLiveBean.NONE;

    /** Created by the {@link java.util.ServiceLoader}. */
    public DiracMetricsExtension() {}

    @Override
    public String name() {
        return NAME;
    }

    /** Resolves the producer bean, once, without creating it. */
    @Override
    public void onStart(ExtensionContext context) {
        live = DiracLiveBean.of(context.beanManager());
    }

    /** Clears what {@link #sample} reads first. */
    @Override
    public void onStop() {
        live = DiracLiveBean.NONE;
    }

    @Override
    public String id() {
        return SECTION_ID;
    }

    @Override
    public String title() {
        return SECTION_TITLE;
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        DiracLiveBean read = live;
        MetricsStartupSection.write(read.registries(), read.absence(), context, section);
    }

    /**
     * The live values of the registries: their maps, read in memory, and the counts their counters, timers and
     * histograms keep. No gauge is called and no bean is created: when the producer does not exist yet, a single
     * absent {@code registries} says why. Nothing before {@code onStart}, nothing after {@code onStop}.
     */
    @Override
    public void sample(PanelSample sample) {
        DiracLiveBean read = live;
        if (read.beans() == null) {
            return;
        }
        DiracRegistries registries = read.registries();
        if (registries == null) {
            sample.absent("registries", read.absence());
        } else {
            MetricsPanel.write(registries, sample);
        }
    }
}
