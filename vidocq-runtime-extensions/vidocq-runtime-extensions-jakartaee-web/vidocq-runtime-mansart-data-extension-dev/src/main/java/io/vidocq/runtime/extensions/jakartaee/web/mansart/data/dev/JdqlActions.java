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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import io.vidocq.mansart.data.core.JdqlExecutor;
import io.vidocq.mansart.data.core.JdqlResult;
import io.vidocq.mansart.data.core.RepositoryRuntime;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.extensions.jakartaee.web.mansart.data.live.MansartDataCatalogue;
import io.vidocq.runtime.spi.devconsole.PanelAction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The <i>JDQL</i> tab of the Mansart Data panel (JDQL console spec §3): two actions that run a JDQL statement typed in
 * the page, {@value #QUERY} for a read and {@value #WRITE} for an {@code UPDATE} or a {@code DELETE}, which asks first
 * and runs in a transaction rolled back unless {@code commit} is asked. Their json argument, {@value #STATEMENT}, holds
 * {@code query}, the statement, and {@code params}, its named parameters as a JSON object or the text of one.
 *
 * <p>A call reads the statement, refuses it to the wrong action, finds the entity it names among the catalogue's, its
 * model and the {@code RepositoryRuntime} bean — all before anything runs — then runs it through a {@link JdqlRunner},
 * for a write in a transaction, and turns its result into JSON inside that transaction. Its calls go into the panel's
 * {@link CallHistory}, with the repositories' calls, under this tab's title.
 */
final class JdqlActions {

    static final String GROUP = "JDQL";
    static final String QUERY = "jdql.query";
    static final String WRITE = "jdql.write";
    static final String QUERY_LABEL = "Query";
    static final String WRITE_LABEL = "Update / Delete";
    static final String STATEMENT = "statement";
    static final String CONFIRMATION = "Runs this JDQL statement against the database.";
    /** How many actions the tab adds to the panel's. */
    static final int COUNT = 2;

    private static final String QUERY_MEMBER = "query";
    private static final String PARAMS_MEMBER = "params";
    private static final System.Logger LOG = System.getLogger(JdqlActions.class.getName());

    /**
     * The statement of a call.
     *
     * @param query  its text, stripped
     * @param params its named parameters by name, in the order given
     */
    private record Statement(String query, Map<String, Object> params) {}

    private final List<MansartDataCatalogue.Entity> entities;
    private final Function<String, Class<?>> classes;
    private final Function<Class<?>, EntityModel<?>> models;
    private final EntityJson json;
    private final BeanLookup beans;
    private final TransactionRunner transactions;
    private final JdqlRunner runner;
    private final CallHistory history;
    private final String group;

    /**
     * @param entities     the catalogue's entities, those a statement may name
     * @param classes      the class of an entity by its full name, as the application loads it
     * @param models       the model of an entity class, {@code EntityModels.of} outside tests
     * @param json         how entities are written
     * @param beans        the beans, resolved at each call: the {@code RepositoryRuntime}
     * @param transactions the transactions of a write
     * @param runner       runs a statement, {@link JdqlRunner#MANSART} outside tests
     * @param history      the panel's history, which the repositories' actions write too
     * @param group        the tab's title, {@value #GROUP} unless a repository's tab has it already
     */
    JdqlActions(List<MansartDataCatalogue.Entity> entities, Function<String, Class<?>> classes,
                Function<Class<?>, EntityModel<?>> models, EntityJson json, BeanLookup beans,
                TransactionRunner transactions, JdqlRunner runner, CallHistory history, String group) {
        this.entities = List.copyOf(entities);
        this.classes = Objects.requireNonNull(classes, "classes");
        this.models = Objects.requireNonNull(models, "models");
        this.json = Objects.requireNonNull(json, "json");
        this.beans = Objects.requireNonNull(beans, "beans");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.history = Objects.requireNonNull(history, "history");
        this.group = Objects.requireNonNull(group, "group");
    }

    /** The two actions, {@value #QUERY} then {@value #WRITE}. */
    List<PanelAction> actions() {
        String names = entityNames();
        PanelAction.Argument statement = PanelAction.Argument.json(STATEMENT, "Statement", schema());
        PanelAction query = new PanelAction(QUERY, QUERY_LABEL, null, List.of(statement),
                given -> call(false, given), group, description("A JDQL query: FROM …, SELECT a, b FROM …, "
                        + "SELECT COUNT(this) FROM … or an aggregate such as SELECT MAX(price) FROM …; at most 100 "
                        + "rows are shown.", names));
        PanelAction write = new PanelAction(WRITE, WRITE_LABEL, CONFIRMATION, List.of(statement,
                new PanelAction.Argument(RepositoryActions.TRANSACTION, "Transaction", transactions.modes(), null,
                        null)), given -> call(true, given), group, description("A JDQL UPDATE … SET … or DELETE "
                        + "FROM …, rolled back unless commit is asked. JDQL has no INSERT: use a repository's save.",
                        names));
        return List.of(query, write);
    }

    /** The JSON Schema of {@value #STATEMENT}: {@code query} and {@code params}, each a field of several lines. */
    static String schema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "FROM Product WHERE price > :min ORDER BY name"),
                        PARAMS_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "the named parameters, a JSON object: {\"min\": 3}")),
                "required", List.of(QUERY_MEMBER)));
    }

    private static String description(String what, String names) {
        return Failures.cut(what + "\nA named parameter, :name, takes the member name of params, converted to the "
                + "type of the attribute it is compared to.\nEntities: " + names, PanelAction.MAX_DESCRIPTION - 1);
    }

    /** Runs a statement with the arguments the console checked, and keeps the call. */
    PanelAction.ActionResult call(boolean write, Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(STATEMENT, "{}");
        String mode = write ? given.getOrDefault(RepositoryActions.TRANSACTION, transactions.modes().getFirst())
                : null;
        Object parsed = null;
        Statement statement = null;
        Class<?> entity = null;
        PanelAction.ActionResult result;
        try {
            parsed = parse(sent);
            statement = statement(parsed);
            refuseTheWrongAction(write, statement.query());
            entity = entity(statement.query());
            EntityModel<?> model = model(entity);
            Object runtime = runtime();
            result = run(write, statement, model, runtime, mode, start, details(entity, statement, mode));
        } catch (Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    details(entity, statement, mode));
        }
        history.add(group, System.currentTimeMillis(), write ? WRITE_LABEL : QUERY_LABEL,
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed), replay(write, parsed, mode));
        return result;
    }

    private PanelAction.ActionResult run(boolean write, Statement statement, EntityModel<?> model, Object runtime,
                                         String mode, long start, String details) {
        // A query runs in a transaction that is always rolled back, when there is a transaction manager: it is
        // read-only whatever it holds, should a statement that writes ever pass for a query.
        String runs = write ? mode : transactions.available() ? TransactionRunner.ROLLBACK : null;
        TransactionRunner.Outcome<ResultJson.Result> outcome = transactions.run(runs,
                () -> answer(runner.run(statement.query(), statement.params(), model, runtime), write, json));
        if (outcome.failure() != null) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + (write ? WRITE : QUERY) + " failed: "
                    + outcome.failure().getClass().getName());
            String text = Failures.text(outcome.failure());
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        ResultJson.Result value = outcome.value();
        String summary = value.what() + (value.rows() ? " in " + millis(start) + " ms" : "")
                + (!write || outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, PanelAction.ActionResult.JSON, value.body(), false, details);
    }

    /**
     * What a statement's result shows (spec §3): entities and rows as arrays of at most {@value ResultJson#MAX_ROWS}
     * objects, a count as its number — a write's as {@code N rows} —, an aggregate as its value.
     */
    static ResultJson.Result answer(JdqlResult result, boolean write, EntityJson entities) {
        return switch (result) {
            case JdqlResult.Entities found -> ResultJson.of(found.entities(), false, entities);
            case JdqlResult.Rows rows -> ResultJson.table(rows.columns(), rows.rows(), entities);
            case JdqlResult.Count count -> new ResultJson.Result(Long.toString(count.count()),
                    write ? ResultJson.count(count.count()) : Long.toString(count.count()), false);
            case JdqlResult.Value value -> {
                Object node = ResultJson.node(value.value(), entities);
                yield new ResultJson.Result(Json.write(node), String.valueOf(node), false);
            }
        };
    }

    private static Object parse(String sent) throws Refused {
        try {
            return Json.parse(sent);
        } catch (IllegalArgumentException unreadable) {
            throw new Refused(STATEMENT + ": " + unreadable.getMessage());
        }
    }

    /** The statement's members: {@code query}, a text, and {@code params}, optional; nothing else. */
    private static Statement statement(Object parsed) throws Refused {
        if (!(parsed instanceof Map<?, ?> members)) {
            throw new Refused(STATEMENT + ": not a JSON object");
        }
        for (Object name : members.keySet()) {
            if (!QUERY_MEMBER.equals(name) && !PARAMS_MEMBER.equals(name)) {
                throw new Refused(name + ": unknown argument");
            }
        }
        Object query = members.get(QUERY_MEMBER);
        if (query != null && !(query instanceof String)) {
            throw new Refused(QUERY_MEMBER + ": not a string");
        }
        if (query == null || ((String) query).isBlank()) {
            throw new Refused(QUERY_MEMBER + ": missing");
        }
        return new Statement(((String) query).strip(), params(members.get(PARAMS_MEMBER)));
    }

    /** {@code params}: absent, blank, a JSON object or the text of one; each member a value or a list of values. */
    private static Map<String, Object> params(Object given) throws Refused {
        Object value = given;
        if (value instanceof String text) {
            if (text.isBlank()) {
                return Map.of();
            }
            try {
                value = Json.parse(text);
            } catch (IllegalArgumentException unreadable) {
                throw new Refused(PARAMS_MEMBER + ": " + unreadable.getMessage());
            }
        }
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> members)) {
            throw new Refused(PARAMS_MEMBER + ": not a JSON object");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> member : members.entrySet()) {
            Object parameter = member.getValue();
            boolean nested = parameter instanceof Map<?, ?> || parameter instanceof List<?> list
                    && list.stream().anyMatch(element -> element instanceof Map<?, ?> || element instanceof List<?>);
            if (nested) {
                throw new Refused(PARAMS_MEMBER + "." + member.getKey() + ": not a value or a list of values");
            }
            out.put(String.valueOf(member.getKey()), parameter);
        }
        return out;
    }

    /** A query in the write action, or a write in the query action, never runs. */
    private static void refuseTheWrongAction(boolean write, String query) throws Refused {
        boolean isWrite = JdqlExecutor.isWrite(query);
        if (write && !isWrite) {
            throw new Refused("not an UPDATE or DELETE: use Query");
        }
        if (!write && isWrite) {
            throw new Refused("an UPDATE or DELETE: use Update / Delete");
        }
    }

    /** The class of the entity {@code query} names: by simple class name, then by full class name. */
    private Class<?> entity(String query) throws Refused {
        Optional<String> named = JdqlExecutor.target(query);
        if (named.isEmpty()) {
            throw new Refused("no entity: name it, FROM <Entity>, UPDATE <Entity> or DELETE FROM <Entity>");
        }
        String name = named.get();
        List<MansartDataCatalogue.Entity> found = entities.stream()
                .filter(entity -> simpleName(entity.className()).equals(name)).toList();
        if (found.isEmpty()) {
            found = entities.stream().filter(entity -> entity.className().equals(name)).toList();
        }
        if (found.isEmpty()) {
            throw new Refused("unknown entity " + name + "; entities: " + entityNames());
        }
        if (found.size() > 1) {
            throw new Refused("entity " + name + " is ambiguous: " + found.stream()
                    .map(MansartDataCatalogue.Entity::className).collect(Collectors.joining(", "))
                    + "; use its full name");
        }
        try {
            return classes.apply(found.getFirst().className());
        } catch (RuntimeException | LinkageError missing) {
            throw new Refused("entity " + name + ": " + Failures.text(missing));
        }
    }

    private EntityModel<?> model(Class<?> entity) throws Refused {
        try {
            EntityModel<?> model = models.apply(entity);
            if (model == null) {
                throw new Refused("no model of " + entity.getName());
            }
            return model;
        } catch (RuntimeException | LinkageError unreadable) {
            throw new Refused(Failures.text(unreadable));
        }
    }

    /** The default {@code RepositoryRuntime} bean, resolved now: the data store of the {@code @Default} datasource. */
    private Object runtime() throws Refused {
        try {
            return beans.reference(RepositoryRuntime.class);
        } catch (RuntimeException | LinkageError none) {
            throw new Refused("no RepositoryRuntime bean");
        }
    }

    /** The catalogue's names of its entities, in alphabetical order. */
    private String entityNames() {
        return entities.stream().map(MansartDataCatalogue.Entity::name).sorted().collect(Collectors.joining(", "));
    }

    /** {@code io.x.Outer$Gizmo} → {@code Gizmo}: the name a statement uses. */
    private static String simpleName(String className) {
        String name = className.substring(className.lastIndexOf('.') + 1);
        return name.substring(name.lastIndexOf('$') + 1);
    }

    /** What the page shows under "Exchange": the entity, the statement, its parameters, the transaction asked. */
    private static String details(Class<?> entity, Statement statement, String mode) {
        return Json.write(Scalars.object("entity", entity == null ? null : entity.getName(),
                "query", statement == null ? null : statement.query(),
                "params", statement == null ? Map.of() : statement.params(),
                "transaction", mode == null ? "read-only" : mode));
    }

    /** {@code <action id> {"statement": {...}, "transaction": "..."}}, or empty when the statement was no object. */
    private static String replay(boolean write, Object parsed, String mode) {
        if (!(parsed instanceof Map<?, ?>)) {
            return "";
        }
        Map<String, Object> values = write
                ? Scalars.object(STATEMENT, parsed, RepositoryActions.TRANSACTION, mode)
                : Scalars.object(STATEMENT, parsed);
        return (write ? WRITE : QUERY) + " " + Json.write(values);
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** A call refused before the statement runs: its message is the summary. */
    private static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }
}
