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
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Mode;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev.UnitWork.Outcome;
import io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.live.NoetherPersistenceLive.Unit;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult;
import io.vidocq.runtime.spi.devconsole.PanelAction.ActionResult.MenuItem;
import jakarta.persistence.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The <i>Explore</i> tab of one unit: {@value #PAGE_ACTION} shows a page of an entity's rows, the values of its
 * {@link Projection} sorted by its id, read in a transaction that is always rolled back, and {@value #EXPORT} gives
 * the same page as CSV, each id followed by the unit's suffix, {@code explore.page.2}. The rows of an entity with a
 * simple id offer a menu: <i>Delete these rows</i> and <i>Update these rows</i> fill the write of the unit's JPQL tab
 * with a statement on the selected ids, for the user to review and run.
 */
final class ExploreActions {

    static final String GROUP = "Explore";
    static final String PAGE_ACTION = "explore.page";
    static final String EXPORT = "explore.export";
    static final String ENTITY = "entity";
    static final String SIZE = "size";
    static final String PAGE = "page";
    static final List<String> SIZES = List.of("20", "50", "100");

    private static final Pattern PAGE_NUMBER = Pattern.compile("\\d{1,6}");

    /**
     * What a call asks.
     *
     * @param entity     the entity
     * @param projection its projection
     * @param size       how many rows a page holds
     * @param page       the page, from 1
     */
    private record Request(Entity entity, Projection projection, int size, int page) {

        /** {@code N rows of Note, page 2}. */
        String rows(int held) {
            return ResultRows.count(held, "row", "rows") + " of " + entity.name() + ", page " + page;
        }
    }

    private final UnitWork work;

    ExploreActions(UnitWork work) {
        this.work = Objects.requireNonNull(work, "work");
    }

    /**
     * The tab's two actions on one unit.
     *
     * @param unit     the unit argument, which allows that unit alone
     * @param entities the names of the unit's entities, in order
     * @param on       the call of an action on the unit its arguments name
     * @param suffix   what follows the id of each action, and of the JPQL write of the unit the menu fills
     */
    List<PanelAction> actions(PanelAction.Argument unit, List<String> entities,
                              Function<UnitCall, Function<Map<String, String>, ActionResult>> on, String suffix) {
        String write = JpqlActions.WRITE + suffix;
        List<PanelAction.Argument> arguments = List.of(unit,
                new PanelAction.Argument(ENTITY, "Entity", entities, null, null),
                new PanelAction.Argument(SIZE, "Rows per page", SIZES, null, null),
                new PanelAction.Argument(PAGE, "Page", null, "[0-9]{0,6}", null));
        return List.of(
                new PanelAction(PAGE_ACTION + suffix, "Show a page", null, arguments,
                        on.apply((found, given) -> showPage(found, given, write)), GROUP,
                        "A page of the entity's rows, sorted by its id, each relation as its target's id. Select rows"
                                + " and right-click them to delete or update them through the JPQL tab."),
                new PanelAction(EXPORT + suffix, "Export the page", null, arguments, on.apply(this::exportPage), GROUP,
                        "The same page as CSV."));
    }

    /** A page of an entity's rows, whose menu fills the JPQL action {@code write}. */
    ActionResult showPage(Unit unit, Map<String, String> given, String write) {
        return read(unit, given, read -> shown(read, write));
    }

    /** The same page as CSV. */
    ActionResult exportPage(Unit unit, Map<String, String> given) {
        return read(unit, given, ExploreActions::csv);
    }

    /**
     * A page read.
     *
     * @param request what the call asked
     * @param page    the page's rows, at most its size
     * @param more    whether a next page has rows
     * @param millis  how long the read took
     */
    private record Read(Request request, ResultRows.Table page, boolean more, long millis) {}

    /** Reads the page a call asks for, and answers it as {@code view} says. */
    private ActionResult read(Unit unit, Map<String, String> given, Function<Read, ActionResult> view) {
        long start = System.nanoTime();
        Request request;
        Outcome<ResultRows.Table> outcome;
        try {
            request = request(unit, given);
            outcome = work.run(unit, Mode.READ, em -> rows(em.createQuery(query(request.projection())), request));
        } catch (Refused refused) {
            return JpqlActions.refusal(refused);
        }
        if (outcome.failure() != null) {
            return JpqlActions.failure(outcome.failure());
        }
        ResultRows.Table table = outcome.value();
        boolean more = table.rows().size() > request.size();
        List<List<Object>> kept = more ? table.rows().subList(0, request.size()) : table.rows();
        return view.apply(new Read(request, new ResultRows.Table(table.columns(), kept), more,
                (System.nanoTime() - start) / 1_000_000));
    }

    private static ActionResult shown(Read read, String write) {
        ResultRows.Table page = read.page();
        List<MenuItem> menu = menu(read.request().entity(), read.request().projection(), write);
        int held = ActionResult.rowsHeld(page.columns(), page.rows(), menu);
        String summary = (held < page.rows().size() ? "first " : "") + read.request().rows(held)
                + (read.more() ? ", more on the next page" : "") + ", in " + read.millis() + " ms";
        return ActionResult.rowsWithMenu(summary, page.columns(), page.rows(), read.more(), menu);
    }

    private static ActionResult csv(Read read) {
        ResultRows.Table page = read.page();
        Csv.Written csv = Csv.write(page.columns(), page.rows(), ActionResult.MAX_CONTENT - 1);
        String summary = read.request().rows(csv.rows()) + (csv.rows() < page.rows().size()
                ? ": the next would pass 256 KiB" : "");
        return new ActionResult(summary, ActionResult.CSV, csv.body(), false, null);
    }

    /**
     * The menu of the rows of {@code entity}, which fills the JPQL action {@code write}: a delete of the selected ids,
     * and an update that sets its first basic value to itself, for the user to change; none for an entity without a
     * simple id.
     */
    static List<MenuItem> menu(Entity entity, Projection projection, String write) {
        if (projection.idPath() == null) {
            return List.of();
        }
        String id = projection.columns().get(projection.idColumn()).name();
        String alias = Projection.ALIAS;
        String where = " WHERE " + projection.idPath() + " IN (${" + id + "})";
        List<MenuItem> menu = new ArrayList<>();
        menu.add(item(write, "Delete these rows", "DELETE FROM " + entity.name() + " " + alias + where));
        entity.attributes().stream().filter(a -> a.kind() == Kind.BASIC).findFirst().ifPresent(basic -> {
            String path = alias + "." + basic.name();
            menu.add(item(write, "Update these rows", "UPDATE " + entity.name() + " " + alias + " SET " + path + " = "
                    + path + where));
        });
        return menu;
    }

    private static MenuItem item(String write, String label, String query) {
        return new MenuItem(label, write, Json.write(Json.object(JpqlActions.STATEMENT,
                Json.object("query", query))));
    }

    /** The projection's query, sorted by the entity's id. */
    private static String query(Projection projection) {
        return projection.select() + (projection.idPaths().isEmpty() ? ""
                : " ORDER BY " + String.join(", ", projection.idPaths()));
    }

    /** The page's rows, and the first of the next page, which says whether there is one. */
    private static ResultRows.Table rows(Query query, Request request) {
        query.setFirstResult((request.page() - 1) * request.size());
        query.setMaxResults(request.size() + 1);
        return ResultRows.projected(request.projection(), query.getResultList());
    }

    private static Request request(Unit unit, Map<String, String> given) throws Refused {
        String name = given.get(ENTITY);
        UnitDescription description;
        try {
            description = PersistenceLivePanel.describe(unit);
        } catch (RuntimeException failed) {
            throw new Refused("unit " + unit.name() + " has no description: " + Failures.text(failed));
        }
        Entity entity = description.entities().stream().filter(e -> e.name().equals(name)).findFirst()
                .orElseThrow(() -> new Refused(name + " is no entity of unit " + unit.name()));
        String size = given.get(SIZE);
        String page = given.getOrDefault(PAGE, "").strip();
        int number = 1;
        if (!page.isEmpty()) {
            number = PAGE_NUMBER.matcher(page).matches() ? Integer.parseInt(page) : 0;
        }
        if (number < 1) {
            throw new Refused(PAGE + ": not a page number");
        }
        return new Request(entity, Projection.of(entity, description),
                SIZES.contains(size) ? Integer.parseInt(size) : Integer.parseInt(SIZES.getFirst()), number);
    }
}
