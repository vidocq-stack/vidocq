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
package io.vidocq.runtime.extensions.jakartaee.web.foy.dev;

import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMount;
import io.vidocq.runtime.extensions.jakartaee.web.foy.live.ServletMounts;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The servlet section, live: the active sessions of each servlet deployment of this boot, one group per
 * mount, named as the startup report names it. The section itself gives the context path, the servlets,
 * the filters and the listeners.
 *
 * <p>Reading the count reads the session store's size: no lock, no I/O, no instance created.
 */
public final class ServletLivePanel implements LivePanel {

    private static final String SESSIONS = "sessions";

    /** What the page plots, repeated for every mount. */
    static final List<Chart> CHARTS = List.of(
            new Chart(SESSIONS, "Active sessions", List.of(Series.area(SESSIONS))));

    /** The boot's container, the key of its mounts; set by {@link #start}. */
    private final AtomicReference<Object> boot = new AtomicReference<>();

    /** Created by the service loader. */
    public ServletLivePanel() {}

    ServletLivePanel(Object boot) {
        this.boot.set(boot);
    }

    @Override
    public String id() {
        return "servlet";
    }

    @Override
    public void start(ExtensionContext context) {
        boot.set(context.container());
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    @Override
    public void sample(PanelSample sample) {
        Object container = boot.get();
        List<ServletMount> mounts = container == null ? List.of() : ServletMounts.of(container);
        if (mounts.isEmpty()) {
            sample.absent(SESSIONS, "no servlet deployed");
            return;
        }
        for (ServletMount mount : mounts) {
            sample.group(mount.name()).gauge(SESSIONS, mount.activeSessions().getAsInt(), Unit.COUNT);
        }
    }
}
