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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Index;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Table;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Mode;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Outcome;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.Column;
import jakarta.persistence.EntityManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The <i>Indexes</i> tab: {@value #DECLARED} lists the indexes and unique constraints the unit's mapping declares,
 * from memory, and {@value #COMPARE} sets them against those the database has, read through JDBC metadata on the
 * connection of an entity manager, in a transaction that is always rolled back.
 */
final class IndexActions {

    static final String GROUP = "Indexes";
    static final String DECLARED = "indexes.declared";
    static final String COMPARE = "indexes.compare";

    private static final List<Column> DECLARED_COLUMNS = List.of(IndexDiff.text("table"), IndexDiff.text("index"),
            IndexDiff.text("columns"), IndexDiff.text("unique"));

    private final UnitWork work;

    IndexActions(UnitWork work) {
        this.work = Objects.requireNonNull(work, "work");
    }

    /** The tab's two actions. */
    List<PanelAction> actions(PanelAction.Argument unit,
                              Function<UnitCall, Function<Map<String, String>, ActionResult>> on) {
        return List.of(
                new PanelAction(DECLARED, "Declared indexes", null, List.of(unit),
                        on.apply((found, _) -> declaredIndexes(found)), GROUP,
                        "The indexes and unique constraints of the unit's mapping, read from memory."),
                new PanelAction(COMPARE, "Compare with the database", null, List.of(unit),
                        on.apply((found, _) -> compareIndexes(found)), GROUP,
                        "Each declared index against the database's: same, missing in the database, extra in"
                                + " the database or different. Names are compared ignoring case; the indexes of"
                                + " primary and foreign keys are left out."));
    }

    /** The declared indexes, a row each. */
    ActionResult declaredIndexes(Unit unit) {
        UnitDescription description;
        try {
            description = PersistenceLivePanel.describe(unit);
        } catch (RuntimeException failed) {
            return JpqlActions.failure(failed);
        }
        List<List<String>> rows = new ArrayList<>();
        long tables = 0;
        for (Table table : description.tables()) {
            for (Index index : table.indexes()) {
                rows.add(List.of(table.name(), Objects.requireNonNullElse(index.name(), ""),
                        String.join(", ", index.columns()), index.unique() ? "yes" : "no"));
            }
            tables += table.indexes().isEmpty() ? 0 : 1;
        }
        return ActionResult.rows(ResultRows.count(rows.size(), "index", "indexes") + " on "
                + ResultRows.count(tables, "table", "tables"), DECLARED_COLUMNS, rows, false);
    }

    /** The declared indexes against the database's. */
    ActionResult compareIndexes(Unit unit) {
        Outcome<List<List<String>>> outcome = work.run(unit, Mode.READ,
                em -> compared(em, PersistenceLivePanel.describe(unit).tables()));
        if (outcome.failure() != null) {
            return JpqlActions.failure(outcome.failure());
        }
        List<List<String>> rows = outcome.value();
        return ActionResult.rows(IndexDiff.summary(rows), IndexDiff.COLUMNS, rows, false);
    }

    /** The rows comparing the declared indexes of {@code tables} with those {@code em}'s database holds. */
    private static List<List<String>> compared(EntityManager em, List<Table> tables) {
        try {
            return IndexDiff.rows(tables, DatabaseIndexes.read(em.unwrap(Connection.class), tables));
        } catch (SQLException unreadable) {
            throw new IllegalStateException("the database's indexes could not be read: " + unreadable.getMessage(),
                    unreadable);
        }
    }
}
