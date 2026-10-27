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
package io.vidocq.runtime.extensions.microprofile.dirac.dev;

import io.vidocq.runtime.extensions.microprofile.dirac.live.DiracLiveBean;
import io.vidocq.runtime.extensions.microprofile.dirac.live.DiracRegistries;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;

/**
 * The metrics section, live: the values Dirac's registries hold, read in memory. No gauge is called and no bean is
 * created: when the producer does not exist yet, a single absent {@code registries} says why.
 */
public final class MetricsLivePanel implements LivePanel {

    private volatile DiracLiveBean live = DiracLiveBean.NONE;

    /** Created by the service loader. */
    public MetricsLivePanel() {}

    @Override
    public String id() {
        return "metrics";
    }

    /** Resolves the producer bean, once, without creating it. */
    @Override
    public void start(ExtensionContext context) {
        live = DiracLiveBean.of(context.beanManager());
    }

    @Override
    public void stop() {
        live = DiracLiveBean.NONE;
    }

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
