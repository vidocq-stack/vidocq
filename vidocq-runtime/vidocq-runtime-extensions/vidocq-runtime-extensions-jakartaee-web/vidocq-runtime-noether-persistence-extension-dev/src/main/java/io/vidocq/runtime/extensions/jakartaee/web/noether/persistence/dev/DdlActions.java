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

import io.vidocq.noether.jpa.core.spi.SchemaScripts;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The <i>DDL</i> tab: the statements Noether writes for the unit's schema in its dialect, {@value #CREATE} to create
 * its tables, sequences, indexes and constraints and {@value #DROP} to drop them, as SQL the page offers to download.
 * Nothing runs against the database.
 */
final class DdlActions {

    static final String GROUP = "DDL";
    static final String CREATE = "ddl.create";
    static final String DROP = "ddl.drop";

    /** The tab's two actions. */
    List<PanelAction> actions(PanelAction.Argument unit,
                              Function<UnitCall, Function<Map<String, String>, ActionResult>> on) {
        String nothing = " Nothing runs against the database: copy or download the script.";
        return List.of(
                new PanelAction(CREATE, "Create script", null, List.of(unit),
                        on.apply((found, _) -> createScript(found)), GROUP,
                        "The statements that create the unit's tables, sequences, indexes and constraints, in its"
                                + " dialect." + nothing),
                new PanelAction(DROP, "Drop script", null, List.of(unit), on.apply((found, _) -> dropScript(found)),
                        GROUP,
                        "The statements that drop them." + nothing));
    }

    /** The create script. */
    ActionResult createScript(Unit unit) {
        return script(unit, SchemaScripts::createScript);
    }

    /** The drop script. */
    ActionResult dropScript(Unit unit) {
        return script(unit, SchemaScripts::dropScript);
    }

    private static ActionResult script(Unit unit, Function<SchemaScripts, List<String>> which) {
        List<String> statements;
        try {
            statements = which.apply(unit.factory().unwrap(SchemaScripts.class));
        } catch (RuntimeException failed) {
            return JpqlActions.failure(failed);
        }
        StringBuilder body = new StringBuilder();
        for (String statement : statements) {
            String text = statement.strip();
            body.append(text.endsWith(";") ? text.substring(0, text.length() - 1) : text).append(";\n");
        }
        return new ActionResult(ResultRows.count(statements.size(), "statement", "statements"), ActionResult.SQL,
                body.toString(), false, null);
    }
}
