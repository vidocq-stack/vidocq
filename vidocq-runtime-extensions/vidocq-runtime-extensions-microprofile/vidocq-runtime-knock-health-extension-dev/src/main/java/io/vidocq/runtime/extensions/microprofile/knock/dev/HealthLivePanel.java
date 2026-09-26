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
package io.vidocq.runtime.extensions.microprofile.knock.dev;

import io.vidocq.knock.spi.HealthCheckRegistry;
import io.vidocq.runtime.extensions.microprofile.knock.live.KnockLiveBean;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;

import java.time.ZoneId;
import java.util.List;

/**
 * The health section, live: the last answer of each check, read in memory. No check is called and no bean is
 * created: when the registry does not exist yet, a single absent {@code checks} says why.
 */
public final class HealthLivePanel implements LivePanel {

    private volatile KnockLiveBean live = KnockLiveBean.NONE;

    /** Created by the service loader. */
    public HealthLivePanel() {}

    @Override
    public String id() {
        return "health";
    }

    /** Resolves the registry bean, once, without creating it. */
    @Override
    public void start(ExtensionContext context) {
        live = KnockLiveBean.of(context.beanManager());
    }

    @Override
    public void stop() {
        live = KnockLiveBean.NONE;
    }

    @Override
    public List<Chart> charts() {
        return HealthPanel.CHARTS;
    }

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
