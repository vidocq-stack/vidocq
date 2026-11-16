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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.PanelSelector;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The live half of the {@code noether-persistence} section: a <i>Persistence unit</i> dropdown, a group per unit
 * with the counts its description holds, and the actions of the Model, JPQL, Explore, Indexes and DDL tabs, each
 * taking the chosen unit. {@link #sample} reads memory only; every action that needs the database runs on a click.
 * The units come from {@link NoetherPersistenceLive}, which the extension clears at its stop: the panel holds no
 * factory of its own.
 */
public final class PersistenceLivePanel implements LivePanel {

    static final String ID = "noether-persistence";
    static final String UNIT = "unit";
    private static final PanelSelector SELECTOR = new PanelSelector(UNIT, "Persistence unit");
    private static final io.vidocq.runtime.spi.devconsole.Unit COUNT = io.vidocq.runtime.spi.devconsole.Unit.COUNT;

    private final Supplier<List<Unit>> units;

    /** The panel of the running boot's units. */
    public PersistenceLivePanel() {
        this(NoetherPersistenceLive::units);
    }

    PersistenceLivePanel(Supplier<List<Unit>> units) {
        this.units = units;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<PanelSelector> selector() {
        return Optional.of(SELECTOR);
    }

    @Override
    public void sample(PanelSample sample) {
        List<Unit> started = units.get();
        if (started.isEmpty()) {
            sample.absent("units", "no persistence unit");
            return;
        }
        for (Unit unit : started) {
            PanelSample group = sample.group(unit.name());
            try {
                UnitDescription description = describe(unit);
                group.gauge("entities", description.entities().size(), COUNT)
                        .gauge("embeddables", description.embeddables().size(), COUNT)
                        .gauge("named-queries", description.namedQueries().size(), COUNT)
                        .gauge("indexes", description.tables().stream().mapToInt(t -> t.indexes().size()).sum(),
                                COUNT);
            } catch (RuntimeException failed) {
                group.absent("entities", "no description: " + failed.getMessage());
            }
        }
    }

    @Override
    public List<PanelAction> actions() {
        List<Unit> started = units.get();
        if (started.isEmpty()) {
            return List.of();
        }
        PanelAction.Argument unit = new PanelAction.Argument(UNIT, "Persistence unit",
                started.stream().map(Unit::name).toList(), null, null);
        return List.of(new PanelAction("model", "Show the model", null, List.of(unit),
                onUnit(found -> ModelGraph.of(describe(found))), "Model",
                "The unit's entities, embeddables and mapped superclasses, their relations and their inheritance, "
                        + "read from memory. Click a box to list its table and its attributes."));
    }

    /** The call of an action on the chosen unit: an error when no unit of the boot has that name. */
    private Function<Map<String, String>, ActionResult> onUnit(Function<Unit, ActionResult> work) {
        return arguments -> {
            String name = arguments.get(UNIT);
            return units.get().stream().filter(u -> u.name().equals(name)).findFirst().map(work)
                    .orElseGet(() -> new ActionResult("no persistence unit named " + name + " in this boot", null, null,
                            true, null));
        };
    }

    /** The unit's description, which Noether builds once per factory. */
    static UnitDescription describe(Unit unit) {
        return unit.factory().unwrap(UnitDescription.class);
    }
}
