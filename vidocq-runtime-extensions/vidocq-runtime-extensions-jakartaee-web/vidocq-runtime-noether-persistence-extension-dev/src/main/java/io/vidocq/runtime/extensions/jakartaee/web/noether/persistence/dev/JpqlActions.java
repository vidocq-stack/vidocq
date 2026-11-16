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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Mode;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Outcome;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import jakarta.persistence.Query;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The <i>JPQL</i> tab: {@value #QUERY} runs a {@code SELECT} and shows its rows, {@value #WRITE} an {@code UPDATE}
 * or a {@code DELETE}, which asks first and is rolled back unless {@code commit} is asked, and {@value #EXPORT} gives
 * a query's rows as CSV. A read runs in a transaction that is always rolled back. Each takes the unit and the
 * {@value #STATEMENT} json argument, its {@code query} in the query editor with the {@value JpqlLanguage#ID} language.
 */
final class JpqlActions {

    static final String GROUP = "JPQL";
    static final String QUERY = "jpql.query";
    static final String WRITE = "jpql.write";
    static final String EXPORT = "jpql.export";
    static final String STATEMENT = Statement.ARGUMENT;
    static final String TRANSACTION = "transaction";
    static final String ROLLBACK = "rollback";
    static final String COMMIT = "commit";

    /** The most rows an export reads. */
    static final int EXPORT_ROWS = 10_000;

    private final UnitWork work;

    JpqlActions(UnitWork work) {
        this.work = Objects.requireNonNull(work, "work");
    }

    /**
     * The tab's three actions.
     *
     * @param unit    the unit argument every action of the panel takes
     * @param on      the call of an action on the unit its arguments name
     * @param example the query the editor shows as an example, {@link #example}
     */
    List<PanelAction> actions(PanelAction.Argument unit, Function<UnitCall, Function<Map<String, String>,
            ActionResult>> on, String example) {
        PanelAction.Argument statement = PanelAction.Argument.json(STATEMENT, "Statement", schema(example));
        String parameters = "\nA named parameter, :name, takes the member of params of that name, converted to the"
                + " type the query gives it.";
        return List.of(
                new PanelAction(QUERY, "Query", null, List.of(unit, statement), on.apply(this::query), GROUP,
                        "A JPQL SELECT, or FROM <Entity>, run in a transaction that is always rolled back: at most "
                                + ResultRows.MAX_ROWS + " rows are shown, an entity as the values it holds."
                                + parameters),
                new PanelAction(WRITE, "Update / Delete", "Runs this JPQL statement against the database.",
                        List.of(unit, statement, new PanelAction.Argument(TRANSACTION, "Transaction",
                                List.of(ROLLBACK, COMMIT), null, null)), on.apply(this::write), GROUP,
                        "A JPQL UPDATE or DELETE, run in a transaction rolled back unless commit is asked. JPQL has"
                                + " no INSERT." + parameters),
                new PanelAction(EXPORT, "Export CSV", null, List.of(unit, statement), on.apply(this::export), GROUP,
                        "The rows of a JPQL SELECT as CSV, at most " + EXPORT_ROWS + " rows and 256 KiB, read in a"
                                + " transaction that is always rolled back." + parameters));
    }

    /**
     * The JSON Schema of {@value #STATEMENT}: {@code query}, which the page shows in its query editor, and
     * {@code params}, in its JSON editor whose members follow the query's parameters.
     */
    static String schema(String example) {
        return Json.write(Json.object("type", "object", "properties", Json.object(
                "query", Json.object("type", "string", "format", "textarea", "contentMediaType",
                        JpqlLanguage.MEDIA_TYPE, "x-language", JpqlLanguage.ID, "description", example),
                "params", Json.object("type", "string", "format", "textarea", "x-parameters-of", "query",
                        "description", "the named parameters, a JSON object: {\"id\": 3}")),
                "required", List.of("query")));
    }

    /**
     * The example of the query editor: the first entity in name order, ordered by its id when it has a simple one;
     * {@code SELECT e FROM <Entity> e} without any.
     */
    static String example(List<UnitDescription> units) {
        return units.stream().flatMap(unit -> unit.entities().stream()).min(Comparator.comparing(Entity::name))
                .map(entity -> "SELECT e FROM " + entity.name() + " e" + Projection.simpleId(entity)
                        .map(id -> " ORDER BY e." + id.name()).orElse(""))
                .orElse("SELECT e FROM <Entity> e");
    }

    /** Runs a query and shows its rows. */
    ActionResult query(Unit unit, Map<String, String> given) {
        long start = System.nanoTime();
        Statement statement;
        try {
            statement = read(given, false);
        } catch (Refused refused) {
            return refusal(refused);
        }
        Outcome<ResultRows.Table> outcome = work.run(unit, Mode.READ, em -> rows(unit, statement, em,
                ResultRows.MAX_ROWS + 1));
        return outcome.failure() != null ? failure(outcome.failure())
                : ResultRows.shown(outcome.value(), millis(start));
    }

    /** Runs an update or a delete, committed when {@value #TRANSACTION} is {@value #COMMIT}. */
    ActionResult write(Unit unit, Map<String, String> given) {
        Statement statement;
        try {
            statement = read(given, true);
        } catch (Refused refused) {
            return refusal(refused);
        }
        Mode mode = COMMIT.equals(given.get(TRANSACTION)) ? Mode.COMMIT : Mode.ROLLBACK;
        Outcome<Integer> outcome = work.run(unit, mode, em -> {
            Query query = em.createQuery(statement.query());
            statement.bind(query);
            return query.executeUpdate();
        });
        if (outcome.failure() != null) {
            return failure(outcome.failure());
        }
        return ActionResult.of(ResultRows.count(outcome.value(), "row", "rows")
                + (statement.isDelete() ? " deleted" : " updated") + " · " + outcome.state());
    }

    /** Runs a query and gives its rows as CSV. */
    ActionResult export(Unit unit, Map<String, String> given) {
        Statement statement;
        try {
            statement = read(given, false);
        } catch (Refused refused) {
            return refusal(refused);
        }
        Outcome<ResultRows.Table> outcome = work.run(unit, Mode.READ, em -> rows(unit, statement, em,
                EXPORT_ROWS + 1));
        if (outcome.failure() != null) {
            return failure(outcome.failure());
        }
        ResultRows.Table table = outcome.value();
        boolean capped = table.rows().size() > EXPORT_ROWS;
        List<List<Object>> rows = capped ? table.rows().subList(0, EXPORT_ROWS) : table.rows();
        Csv.Written csv = Csv.write(table.columns(), rows, ActionResult.MAX_CONTENT - 1);
        String count = ResultRows.count(csv.rows(), "row", "rows");
        String summary;
        if (csv.rows() < rows.size()) {
            summary = "first " + count + ": the next would pass 256 KiB";
        } else {
            summary = capped ? "first " + count : count;
        }
        return new ActionResult(summary, ActionResult.CSV, csv.body(), false, null);
    }

    private static ResultRows.Table rows(Unit unit, Statement statement, jakarta.persistence.EntityManager em,
                                         int max) {
        Query query = em.createQuery(statement.query());
        statement.bind(query);
        query.setMaxResults(max);
        return ResultRows.table(statement.query(), query.getResultList(), em, PersistenceLivePanel.describe(unit),
                item -> unit.factory().getPersistenceUnitUtil().getIdentifier(item));
    }

    /** The statement of a call, refused to the action that does not run its kind. */
    private static Statement read(Map<String, String> given, boolean write) throws Refused {
        Statement statement = Statement.read(given.get(STATEMENT));
        if (statement.isWrite() != write) {
            throw new Refused(write ? "not an UPDATE or a DELETE: use Query"
                    : "an UPDATE or a DELETE: use Update / Delete");
        }
        return statement;
    }

    static ActionResult refusal(Refused refused) {
        return new ActionResult(Failures.line(refused.getMessage()), null, null, true, null);
    }

    static ActionResult failure(RuntimeException failed) {
        String text = Failures.text(failed);
        return new ActionResult(Failures.line(text), ActionResult.TEXT, text, true, null);
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
