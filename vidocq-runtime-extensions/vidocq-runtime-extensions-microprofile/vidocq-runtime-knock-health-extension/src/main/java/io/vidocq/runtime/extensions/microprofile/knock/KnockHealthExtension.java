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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.time.ZoneId;
import java.util.List;

/**
 * The {@code health} section of the startup report and panel of the dev console: Knock's checks by probe, and the
 * last answer of each.
 *
 * <p>Knock itself needs no Vidocq code: its CDI integration and its JAX-RS resource do the integration (see the
 * module description). This class only reads what Knock keeps in memory, from the {@link HealthCheckRegistry}
 * instance the application created, and never creates it: see {@link KnockLiveBean}. It never calls a check either:
 * a check is application code and may do I/O, so the panel shows each check's last answer to a probe request.
 * {@link HealthStartupSection} writes the boot facts, {@link HealthPanel} the live values.
 */
public final class KnockHealthExtension implements VidocqExtension, DevConsolePanel {

    static final String NAME = "knock-health";
    static final String SECTION_ID = "health";
    static final String SECTION_TITLE = "Health (Knock)";

    /**
     * The registry bean {@link #sample} reads, resolved once in {@link #onStart}; {@link KnockLiveBean#NONE} before
     * it and after {@link #onStop}.
     */
    private volatile KnockLiveBean live = KnockLiveBean.NONE;

    /** Created by the {@link java.util.ServiceLoader}. */
    public KnockHealthExtension() {}

    @Override
    public String name() {
        return NAME;
    }

    /** Resolves the registry bean, once, without creating it. */
    @Override
    public void onStart(ExtensionContext context) {
        live = KnockLiveBean.of(context.beanManager());
    }

    /** Clears what {@link #sample} reads first. */
    @Override
    public void onStop() {
        live = KnockLiveBean.NONE;
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
        KnockLiveBean read = live;
        HealthStartupSection.write(read.read(), read.absence(), context, section);
    }

    @Override
    public List<Chart> charts() {
        return HealthPanel.CHARTS;
    }

    /**
     * The last answer of each check, read in memory. No check is called and no bean is created: when the registry
     * does not exist yet, a single absent {@code checks} says why. Nothing before {@code onStart}, nothing after
     * {@code onStop}.
     */
    @Override
    public void sample(PanelSample sample) {
        KnockLiveBean read = live;
        if (read.beans() == null) {
            return;
        }
        HealthCheckRegistry registry = read.read();
        if (registry == null) {
            sample.absent("checks", read.absence());
        } else {
            HealthPanel.write(registry, ZoneId.systemDefault(), sample);
        }
    }
}
