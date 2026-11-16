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
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelLanguage;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.PanelSelector;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final System.Logger LOG = System.getLogger(PersistenceLivePanel.class.getName());

    private final Supplier<List<Unit>> units;
    private final AtomicReference<BeanManager> beans = new AtomicReference<>();
    private final JpqlActions jpql;

    /** The panel of the running boot's units, its transactions those of the application's transaction manager. */
    public PersistenceLivePanel() {
        this.units = NoetherPersistenceLive::units;
        this.jpql = new JpqlActions(new UnitWork(this::transactionManager));
    }

    /** A panel of {@code units}, whose JTA work runs in the transactions of {@code transactions}. */
    PersistenceLivePanel(Supplier<List<Unit>> units, Supplier<TransactionManager> transactions) {
        this.units = units;
        this.jpql = new JpqlActions(new UnitWork(transactions));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<PanelSelector> selector() {
        return Optional.of(SELECTOR);
    }

    /** Keeps the bean manager the transaction manager of a JTA unit is resolved with. */
    @Override
    public void start(ExtensionContext context) {
        try {
            beans.set(context.beanManager());
        } catch (RuntimeException | LinkageError unavailable) {
            beans.set(null);
            LOG.log(System.Logger.Level.DEBUG, "Noether Persistence: no bean manager, a JTA unit cannot work: "
                    + unavailable.getClass().getName());
        }
    }

    /** Drops the bean manager: nothing of this boot outlives a dev reload. */
    @Override
    public void stop() {
        beans.set(null);
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
        List<PanelAction> actions = new ArrayList<>();
        actions.add(new PanelAction("model", "Show the model", null, List.of(unit),
                onUnit((found, given) -> ModelGraph.of(describe(found))), "Model",
                "The unit's entities, embeddables and mapped superclasses, their relations and their inheritance, "
                        + "read from memory. Click a box to list its table and its attributes."));
        actions.addAll(jpql.actions(unit, this::onUnit, JpqlActions.example(descriptions(started))));
        return actions;
    }

    /** The {@value JpqlLanguage#ID} language of the query editor, of the entities of every unit. */
    @Override
    public List<PanelLanguage> languages() {
        List<UnitDescription> described = descriptions(units.get());
        return described.isEmpty() ? List.of()
                : List.of(new PanelLanguage(JpqlLanguage.ID, JpqlLanguage.json(described)));
    }

    /** The call of an action on the chosen unit: an error when no unit of the boot has that name. */
    Function<Map<String, String>, ActionResult> onUnit(UnitCall work) {
        return arguments -> {
            String name = arguments.get(UNIT);
            return units.get().stream().filter(u -> u.name().equals(name)).findFirst()
                    .map(found -> work.call(found, arguments))
                    .orElseGet(() -> new ActionResult("no persistence unit named " + name + " in this boot", null, null,
                            true, null));
        };
    }

    /** The descriptions of {@code started}, leaving out a unit that cannot describe itself. */
    private static List<UnitDescription> descriptions(List<Unit> started) {
        List<UnitDescription> described = new ArrayList<>();
        for (Unit unit : started) {
            try {
                described.add(describe(unit));
            } catch (RuntimeException failed) {
                LOG.log(System.Logger.Level.DEBUG, "Noether Persistence: unit " + unit.name()
                        + " has no description: " + failed.getClass().getName());
            }
        }
        return described;
    }

    /** The application's transaction manager, resolved with the bean manager of this boot; {@code null} without. */
    private TransactionManager transactionManager() {
        BeanManager manager = beans.get();
        if (manager == null) {
            return null;
        }
        Set<Bean<?>> found = manager.getBeans(TransactionManager.class);
        if (found.isEmpty()) {
            return null;
        }
        Bean<?> bean = manager.resolve(found);
        return (TransactionManager) manager.getReference(bean, TransactionManager.class,
                manager.createCreationalContext(bean));
    }

    /** The unit's description, which Noether builds once per factory. */
    static UnitDescription describe(Unit unit) {
        return unit.factory().unwrap(UnitDescription.class);
    }
}
