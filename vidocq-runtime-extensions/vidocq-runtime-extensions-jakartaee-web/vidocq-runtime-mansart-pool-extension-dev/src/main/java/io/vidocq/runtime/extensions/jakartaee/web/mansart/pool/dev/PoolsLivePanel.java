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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import io.vidocq.mansart.pool.PoolConfig;
import io.vidocq.mansart.pool.PoolMetrics;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.live.MansartPoolsLive;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;

import java.util.ArrayList;
import java.util.List;

/**
 * The pool section, live: one group per open pool, from {@link MansartPoolsLive}, the holder the runtime extension
 * publishes once every pool is open and clears first thing in its {@code onStop}. No pool is opened or closed here;
 * this panel only reads what the extension already holds.
 *
 * <p>In a dev launch, each pool also gets a tab of actions (SQL spec §4, see {@link PoolActions}): its tables, the
 * columns and first rows of one, and SQL run in a transaction. What they need of the pool's tables is read once per
 * boot, by the first {@link #actions()}, and forgotten by {@link #start} and {@link #stop}. Each pool whose tables
 * were read offers its SQL editors the {@code sql-<pool>} language of them (see {@link SqlLanguage}).
 */
public final class PoolsLivePanel implements LivePanel {

    private static final List<Chart> CHARTS = List.of(
            new Chart("connections", "Connections", List.of(Series.area("active"), Series.stacked("idle"),
                    Series.line("waiting"), Series.ceiling("active"))),
            new Chart("throughput", "Throughput", List.of(Series.rate("borrows"), Series.rate("timeouts"))));

    private static final System.Logger LOG = System.getLogger(PoolsLivePanel.class.getName());

    /** The pools of this boot and what was read of their tables; {@code null} until the first call needs them. */
    private volatile List<PoolActions> pools;

    /** Created by the service loader. */
    public PoolsLivePanel() {}

    @Override
    public String id() {
        return "mansart-pool";
    }

    /** Forgets the previous boot's pools: a dev reload reads its own. */
    @Override
    public void start(ExtensionContext context) {
        pools = null;
    }

    @Override
    public void stop() {
        pools = null;
    }

    /** Each pool's tab of actions, {@code @Default} first, then the named pools in name order. */
    @Override
    public List<PanelAction> actions() {
        return pools().stream().flatMap(pool -> pool.actions().stream()).toList();
    }

    /**
     * The {@code sql-<pool>} language of each pool whose tables the boot read, from what it read; none for a pool
     * whose language is past the size a panel language may hold even at its leanest, which a WARNING says.
     */
    @Override
    public List<PanelLanguage> languages() {
        List<PanelLanguage> languages = new ArrayList<>();
        for (PoolActions pool : pools()) {
            if (pool.metadata() == null) {
                continue;
            }
            try {
                languages.add(new PanelLanguage(pool.languageId(), SqlLanguage.json(pool.label(), pool.metadata())));
            } catch (IllegalStateException tooBig) {
                // the page says "no vocabulary: not offered", and its editor colours the words it knows alone
                LOG.log(System.Logger.Level.WARNING, "Mansart pools: no SQL language for pool '" + pool.label()
                        + "': " + tooBig.getMessage());
            }
        }
        return languages;
    }

    /** The pools of this boot, their tables read now on the first call. */
    private synchronized List<PoolActions> pools() {
        List<PoolActions> read = pools;
        if (read == null) {
            read = PoolActions.of(MansartPoolsLive.pools());
            pools = read;
        }
        return read;
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    /**
     * One group per open pool, from its {@link io.vidocq.mansart.pool.core.MansartDataSource#snapshot()}: counters
     * kept in memory, read without a lock and without I/O, in two small allocations. Once the runtime extension's
     * {@code onStop} has cleared {@link MansartPoolsLive}, no pool.
     */
    @Override
    public void sample(PanelSample sample) {
        for (MansartPoolsLive.Pool v : MansartPoolsLive.pools()) {
            PoolConfig c = v.pool().config();
            PoolMetrics m = v.pool().snapshot();
            PanelSample pool = sample.group(v.label())
                    .gauge("active", m.active(), c.maxSize(), Unit.COUNT)
                    .gauge("idle", m.idle(), c.maxSize(), Unit.COUNT)
                    .gauge("waiting", m.waiting(), Unit.COUNT)
                    .counter("borrows", m.totalBorrows(), Unit.COUNT)
                    .counter("timeouts", m.totalTimeouts(), Unit.COUNT);
            if (c.leakDetectionThreshold().isZero()) {
                pool.absent("leaks", "leak detection off");
            } else {
                pool.counter("leaks", m.totalLeaks(), Unit.COUNT);
            }
            if (m.totalBorrows() == 0) {
                pool.absent("mean-borrow", "no borrow yet");
            } else {
                pool.duration("mean-borrow", m.meanBorrowDuration());
            }
        }
    }
}
