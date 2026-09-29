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
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.runtime.spi.devconsole.PanelAction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The CSV actions of the <i>JDQL</i> tab (CSV spec §3-§6). {@value #EXPORT} runs a JDQL query and answers its whole
 * result as a CSV file, which the page shows and downloads; {@value #IMPORT} reads a CSV file the page sends, builds
 * one entity per row, then saves them all in one transaction, rolled back unless {@code commit} is asked. Nothing is
 * read from or written to a file of the server's.
 *
 * <p>Both find the entity, its model and the {@code RepositoryRuntime} bean as the tab's queries do, through
 * {@link JdqlActions}, and keep their calls in the panel's {@link CallHistory} under the tab's title.
 */
final class CsvActions {

    static final String EXPORT = "jdql.export";
    static final String IMPORT = "jdql.import";
    static final String EXPORT_LABEL = "Export CSV";
    static final String IMPORT_LABEL = "Import CSV";
    /** The json argument of an export: {@code query}, {@code params} and {@code separator}. */
    static final String STATEMENT = JdqlActions.STATEMENT;
    /** The json argument of an import: {@code entity}, {@code csv} and {@code separator}. */
    static final String FILE = "file";
    static final String CONFIRMATION = "Saves these CSV rows against the database.";
    /** The longest export, in UTF-8 bytes. */
    static final int MAX_BYTES = 256 * 1024;
    /** The most rows an import reads. */
    static final int MAX_ROWS = 5000;
    /** How many actions the CSV adds to the tab. */
    static final int COUNT = 2;

    private static final String QUERY_MEMBER = "query";
    private static final String PARAMS_MEMBER = "params";
    private static final String SEPARATOR_MEMBER = "separator";
    private static final String ENTITY_MEMBER = "entity";
    private static final String CSV_MEMBER = "csv";
    private static final List<String> SEPARATORS = List.of(",", ";");
    private static final System.Logger LOG = System.getLogger(CsvActions.class.getName());

    /**
     * An export's arguments.
     *
     * @param query     the statement, stripped
     * @param params    its named parameters
     * @param separator between the fields
     */
    private record Export(String query, Map<String, Object> params, char separator) {}

    /**
     * A CSV written.
     *
     * @param text  the file
     * @param rows  its rows, the header left out
     * @param bytes its length in UTF-8 bytes
     */
    record Written(String text, long rows, long bytes) {}

    private final JdqlActions jdql;
    private final EntityJson json;
    private final TransactionRunner transactions;
    private final JdqlRunner runner;
    private final EntitySaver saver;
    private final CallHistory history;
    private final String group;

    /**
     * @param jdql         the tab's JDQL actions, whose lookups these share
     * @param json         how entities are read and built
     * @param transactions the transactions of a call
     * @param runner       runs an export's statement, {@link JdqlRunner#MANSART} outside tests
     * @param saver        saves an imported entity, {@link EntitySaver#MANSART} outside tests
     * @param history      the panel's history
     * @param group        the tab's title
     */
    CsvActions(JdqlActions jdql, EntityJson json, TransactionRunner transactions, JdqlRunner runner,
               EntitySaver saver, CallHistory history, String group) {
        this.jdql = Objects.requireNonNull(jdql, "jdql");
        this.json = Objects.requireNonNull(json, "json");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.saver = Objects.requireNonNull(saver, "saver");
        this.history = Objects.requireNonNull(history, "history");
        this.group = Objects.requireNonNull(group, "group");
    }

    /** The tab's CSV actions: {@value #EXPORT}, then {@value #IMPORT}. */
    List<PanelAction> actions() {
        return List.of(exportAction(), importAction());
    }

    private PanelAction importAction() {
        return new PanelAction(IMPORT, IMPORT_LABEL, CONFIRMATION, List.of(
                PanelAction.Argument.json(FILE, "File", importSchema(jdql.names())),
                new PanelAction.Argument(RepositoryActions.TRANSACTION, "Transaction", transactions.modes(), null,
                        null)), this::importCsv, group,
                Failures.cut("Saves each row of a CSV file as an entity, in one transaction rolled back unless commit "
                        + "is asked: an insert when its id is empty, else an update or an insert with that id. The "
                        + "header names the attributes; an empty field is null, \"\" the empty text. At most 5000 "
                        + "rows, a file of at most 60 KiB.", PanelAction.MAX_DESCRIPTION - 1));
    }

    private PanelAction exportAction() {
        return new PanelAction(EXPORT, EXPORT_LABEL, null,
                List.of(PanelAction.Argument.json(STATEMENT, "Statement", exportSchema())), this::exportCsv, group,
                Failures.cut("A JDQL query whose whole result is downloaded as a CSV file: FROM Product for every "
                        + "attribute of an entity, SELECT name, price FROM Product for some. Every row, the file at "
                        + "most 256 KiB; an empty field is null, \"\" the empty text.\nEntities: "
                        + jdql.entityNames(), PanelAction.MAX_DESCRIPTION - 1));
    }

    /** The JSON Schema of an export's {@value #STATEMENT}: the query, its parameters, the separator. */
    static String exportSchema() {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        QUERY_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "FROM Product ORDER BY id, or SELECT name, price FROM Product"),
                        PARAMS_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "description", "the named parameters, a JSON object: {\"min\": 3}"),
                        SEPARATOR_MEMBER, separatorSchema()),
                "required", List.of(QUERY_MEMBER)));
    }

    /** {@code ,}, the default, or {@code ;}. */
    static Map<String, Object> separatorSchema() {
        return Scalars.object("type", "string", "enum", SEPARATORS, "default", ",",
                "description", "between the fields: , or ; as a spreadsheet of a decimal-comma locale reads it");
    }

    /** Runs an export with the arguments the console checked, and keeps the call. */
    PanelAction.ActionResult exportCsv(Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(STATEMENT, "{}");
        Object parsed = null;
        Export export = null;
        Class<?> entity = null;
        PanelAction.ActionResult result;
        try {
            parsed = JdqlActions.parse(STATEMENT, sent);
            export = readStatement(parsed);
            if (JdqlExecutor.isWrite(export.query())) {
                throw new JdqlActions.Refused("an UPDATE or DELETE: export a query");
            }
            entity = jdql.entity(export.query());
            EntityModel<?> model = jdql.model(entity);
            Object runtime = jdql.runtime();
            result = runExport(export, entity, model, runtime, start);
        } catch (JdqlActions.Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    exportDetails(entity, export));
        }
        history.add(group, System.currentTimeMillis(), EXPORT_LABEL,
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed),
                parsed instanceof Map<?, ?> ? EXPORT + " " + Json.write(Scalars.object(STATEMENT, parsed)) : "");
        return result;
    }

    private PanelAction.ActionResult runExport(Export export, Class<?> entity, EntityModel<?> model, Object runtime,
                                               long start) {
        String details = exportDetails(entity, export);
        // As a query: in a transaction always rolled back when there is a manager, read-only whatever runs.
        String runs = transactions.available() ? TransactionRunner.ROLLBACK : null;
        TransactionRunner.Outcome<Written> outcome = transactions.run(runs,
                () -> write(runner.run(export.query(), export.params(), model, runtime), entity, export.separator()));
        if (outcome.failure() != null) {
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + EXPORT + " failed: "
                    + outcome.failure().getClass().getName());
            String text = Failures.text(outcome.failure());
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        Written written = outcome.value();
        if (written == null) {
            return new PanelAction.ActionResult("larger than 256 KiB: narrow the query", null, null, true, details);
        }
        return new PanelAction.ActionResult(ResultJson.count(written.rows()) + " · " + size(written.bytes()) + " in "
                + millis(start) + " ms", PanelAction.ActionResult.CSV, written.text(), false, details);
    }

    /**
     * The CSV of a statement's result (CSV spec §3, §5): entities as their attributes in model order, a projection as
     * its columns, a count as {@code count}, an aggregate as {@code value}; {@code null} past {@value #MAX_BYTES}
     * UTF-8 bytes, never a part of it.
     */
    Written write(JdqlResult result, Class<?> entity, char separator) {
        Csv.Writer out = new Csv.Writer(separator, MAX_BYTES);
        boolean fits = switch (result) {
            case JdqlResult.Entities found -> out.add(json.names(entity)) && writeEntities(found.entities(), out);
            case JdqlResult.Rows rows -> out.add(rows.columns()) && writeRows(rows, out);
            case JdqlResult.Count count -> out.add(List.of("count"))
                    && out.add(List.of(Long.toString(count.count())));
            case JdqlResult.Value value -> out.add(List.of("value"))
                    && out.add(Collections.singletonList(cell(value.value())));
        };
        return fits ? new Written(out.text(), out.records() - 1, out.bytes()) : null;
    }

    private boolean writeEntities(List<?> entities, Csv.Writer out) {
        for (Object entity : entities) {
            List<String> fields = new ArrayList<>();
            for (Object value : json.toJson(entity).values()) {
                fields.add(text(value));
            }
            if (!out.add(fields)) {
                return false;
            }
        }
        return true;
    }

    private boolean writeRows(JdqlResult.Rows rows, Csv.Writer out) {
        int columns = rows.columns().size();
        for (Object[] row : rows.rows()) {
            List<String> fields = new ArrayList<>(columns);
            for (int i = 0; i < columns; i++) {
                fields.add(i < row.length ? cell(row[i]) : null);
            }
            if (!out.add(fields)) {
                return false;
            }
        }
        return true;
    }

    /** A projection's or an aggregate's value as text: an entity as its id, anything else as its JSON value. */
    private String cell(Object value) {
        if (value != null && json.isEntity(value.getClass())) {
            return text(Scalars.toJson(json.idOf(value)));
        }
        return text(Scalars.toJson(value));
    }

    /** A JSON value as the text of a field: {@code null} as it is, a {@code BigDecimal} plain, the rest as text. */
    static String text(Object json) {
        if (json == null) {
            return null;
        }
        return json instanceof BigDecimal decimal ? decimal.toPlainString() : json.toString();
    }

    /** {@code 90 B} under 1024 bytes, {@code 3.1 KiB} from it. */
    static String size(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    /** An export's members: {@code query}, {@code params} optional, {@code separator} optional; nothing else. */
    private static Export readStatement(Object parsed) throws JdqlActions.Refused {
        Map<?, ?> members = members(parsed, STATEMENT, Set.of(QUERY_MEMBER, PARAMS_MEMBER, SEPARATOR_MEMBER));
        String query = text(members, QUERY_MEMBER).strip();
        return new Export(query, JdqlActions.params(members.get(PARAMS_MEMBER)), separator(members));
    }

    /** {@code parsed} as a JSON object whose members are all {@code allowed}. */
    private static Map<?, ?> members(Object parsed, String argument, Set<String> allowed) throws JdqlActions.Refused {
        if (!(parsed instanceof Map<?, ?> members)) {
            throw new JdqlActions.Refused(argument + ": not a JSON object");
        }
        for (Object name : members.keySet()) {
            if (!allowed.contains(name)) {
                throw new JdqlActions.Refused(name + ": unknown argument");
            }
        }
        return members;
    }

    /** A member that must be a text that is not blank. */
    private static String text(Map<?, ?> members, String name) throws JdqlActions.Refused {
        Object value = members.get(name);
        if (value != null && !(value instanceof String)) {
            throw new JdqlActions.Refused(name + ": not a string");
        }
        if (value == null || ((String) value).isBlank()) {
            throw new JdqlActions.Refused(name + ": missing");
        }
        return (String) value;
    }

    /** {@code separator}: absent for {@code ,}, else {@code ,} or {@code ;}. */
    private static char separator(Map<?, ?> members) throws JdqlActions.Refused {
        Object value = members.get(SEPARATOR_MEMBER);
        if (value == null) {
            return ',';
        }
        if (!SEPARATORS.contains(value)) {
            throw new JdqlActions.Refused(SEPARATOR_MEMBER + ": \",\" or \";\"");
        }
        return ((String) value).charAt(0);
    }

    /** What the page shows under "Exchange" for an export: the entity, the statement, its parameters, the separator. */
    private static String exportDetails(Class<?> entity, Export export) {
        return Json.write(Scalars.object("entity", entity == null ? null : entity.getName(),
                "query", export == null ? null : export.query(),
                "params", export == null ? Map.of() : export.params(),
                "separator", export == null ? null : String.valueOf(export.separator())));
    }

    /**
     * An import's arguments.
     *
     * @param entity    the entity, as the catalogue names it
     * @param csv       the CSV text
     * @param separator between the fields
     */
    private record Load(String entity, String csv, char separator) {}

    /**
     * One row read.
     *
     * @param line   the line of the text it starts on
     * @param entity the entity it built
     */
    private record Row(int line, Object entity) {}

    /** The JSON Schema of {@value #FILE}: the entity, one of {@code entities}; the CSV text; the separator. */
    static String importSchema(List<String> entities) {
        return Json.write(Scalars.object("type", "object", "properties", Scalars.object(
                        ENTITY_MEMBER, Scalars.object("type", "string", "enum", entities,
                                "description", "the entity each row becomes"),
                        CSV_MEMBER, Scalars.object("type", "string", "format", "textarea",
                                "contentMediaType", "text/csv",
                                "description", "a header of attribute names, then one row per entity"),
                        SEPARATOR_MEMBER, separatorSchema()),
                "required", List.of(ENTITY_MEMBER, CSV_MEMBER)));
    }

    /**
     * Reads the CSV file the page sent, then saves its rows (CSV spec §4), with the arguments the console checked;
     * keeps the call. Nothing is saved, nor any transaction begun, before every row is read.
     */
    PanelAction.ActionResult importCsv(Map<String, String> given) {
        long start = System.nanoTime();
        String sent = given.getOrDefault(FILE, "{}");
        String mode = given.getOrDefault(RepositoryActions.TRANSACTION, transactions.modes().getFirst());
        Object parsed = null;
        Load load = null;
        Class<?> entity = null;
        List<Row> rows = null;
        PanelAction.ActionResult result;
        try {
            parsed = JdqlActions.parse(FILE, sent);
            load = readFile(parsed);
            entity = jdql.named(load.entity());
            EntityModel<?> model = jdql.model(entity);
            rows = read(load, entity);
            Object runtime = jdql.runtime();
            result = save(entity, model, rows, runtime, mode, importDetails(entity, rows, load, mode));
        } catch (JdqlActions.Refused refused) {
            result = new PanelAction.ActionResult(Failures.line(refused.getMessage()), null, null, true,
                    importDetails(entity, rows, load, mode));
        }
        history.add(group, System.currentTimeMillis(), IMPORT_LABEL,
                (result.error() ? "error: " : "") + result.summary(), millis(start),
                parsed == null ? sent : Json.write(parsed), parsed instanceof Map<?, ?>
                        ? IMPORT + " " + Json.write(Scalars.object(FILE, parsed, RepositoryActions.TRANSACTION, mode))
                        : "");
        return result;
    }

    /** An import's members: {@code entity}, {@code csv}, {@code separator} optional; nothing else. */
    private static Load readFile(Object parsed) throws JdqlActions.Refused {
        Map<?, ?> members = members(parsed, FILE, Set.of(ENTITY_MEMBER, CSV_MEMBER, SEPARATOR_MEMBER));
        return new Load(text(members, ENTITY_MEMBER).strip(), text(members, CSV_MEMBER), separator(members));
    }

    /** Phase 1: the header, then each row built into an entity; the first failure stops it with its line. */
    private List<Row> read(Load load, Class<?> entity) throws JdqlActions.Refused {
        List<Csv.Record> records;
        try {
            records = Csv.read(load.csv(), load.separator());
        } catch (Csv.Malformed malformed) {
            throw new JdqlActions.Refused(malformed.getMessage());
        }
        if (records.isEmpty()) {
            throw new JdqlActions.Refused(CSV_MEMBER + ": missing");
        }
        List<String> header = header(records.getFirst().fields(), entity);
        List<Csv.Record> lines = records.subList(1, records.size());
        if (lines.isEmpty()) {
            throw new JdqlActions.Refused("no row: the file has a header only");
        }
        if (lines.size() > MAX_ROWS) {
            throw new JdqlActions.Refused("more than " + MAX_ROWS + " rows: split the file");
        }
        EntityJson.TextRows build;
        try {
            build = json.textRows(entity, header);
        } catch (ArgumentException refused) {
            throw new JdqlActions.Refused("header: " + refused.getMessage());
        } catch (RuntimeException | LinkageError unreadable) {
            throw new JdqlActions.Refused(Failures.text(unreadable));
        }
        List<Row> rows = new ArrayList<>(lines.size());
        for (Csv.Record record : lines) {
            int count = record.fields().size();
            if (count != header.size()) {
                throw new JdqlActions.Refused("line " + record.line() + ": " + count
                        + (count == 1 ? " field" : " fields") + ", the header has " + header.size());
            }
            try {
                rows.add(new Row(record.line(), build.build(record.fields())));
            } catch (ArgumentException refused) {
                throw new JdqlActions.Refused("line " + record.line() + ", " + refused.getMessage());
            } catch (RuntimeException | LinkageError failed) {
                throw new JdqlActions.Refused("line " + record.line() + ": " + Failures.text(failed));
            }
        }
        return rows;
    }

    /** The header: each name an attribute an import may set, once each. */
    private List<String> header(List<String> names, Class<?> entity) throws JdqlActions.Refused {
        List<String> columns;
        List<String> known;
        try {
            columns = json.columns(entity);
            known = json.names(entity);
        } catch (RuntimeException | LinkageError unreadable) {
            throw new JdqlActions.Refused(Failures.text(unreadable));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isEmpty()) {
                throw new JdqlActions.Refused("header: column " + (i + 1) + " has no name");
            }
            if (!columns.contains(name)) {
                throw new JdqlActions.Refused("header: " + (known.contains(name) ? name + " cannot be imported"
                        : "unknown attribute " + name) + "; attributes: " + String.join(", ", columns));
            }
            if (!seen.add(name)) {
                throw new JdqlActions.Refused("header: " + name + " twice");
            }
        }
        return names;
    }

    /** Phase 2: every entity saved, in order, in one transaction; a save that throws rolls it back with its line. */
    private PanelAction.ActionResult save(Class<?> entity, EntityModel<?> model, List<Row> rows, Object runtime,
                                          String mode, String details) {
        int[] saved = {0};
        TransactionRunner.Outcome<Integer> outcome = transactions.run(mode, () -> {
            for (Row row : rows) {
                try {
                    saver.save(model, row.entity(), runtime);
                } catch (RuntimeException | LinkageError failed) {
                    throw new RowFailed(row.line(), failed);
                }
                saved[0]++;
            }
            return saved[0];
        });
        if (outcome.failure() != null) {
            Throwable failure = outcome.failure();
            LOG.log(System.Logger.Level.DEBUG, "Mansart Data: " + IMPORT + " failed: "
                    + failure.getClass().getName());
            String text = failure instanceof RowFailed row
                    ? "line " + row.line + ": " + Failures.text(row.getCause()) : Failures.text(failure);
            if (!transactions.available() && saved[0] > 0) {
                // no transaction to roll back: each row was committed as it was saved
                text += saved[0] == 1 ? " · 1 row before it stays committed"
                        : " · " + saved[0] + " rows before it stay committed";
            }
            return new PanelAction.ActionResult(Failures.line(text), PanelAction.ActionResult.TEXT, text, true,
                    details);
        }
        int count = outcome.value();
        String summary = ResultJson.count(count) + " saved" + (outcome.state() == null ? "" : " · " + outcome.state());
        return new PanelAction.ActionResult(summary, PanelAction.ActionResult.JSON, Json.write(Scalars.object(
                "entity", entity.getName(), "saved", count, "transaction", mode)), false, details);
    }

    /** What the page shows under "Exchange" for an import: the entity, the rows read, the separator, the transaction. */
    private static String importDetails(Class<?> entity, List<Row> rows, Load load, String mode) {
        return Json.write(Scalars.object("entity", entity == null ? null : entity.getName(),
                "rows", rows == null ? null : rows.size(),
                "separator", load == null ? null : String.valueOf(load.separator()),
                "transaction", mode));
    }

    /** A save that threw: the line of its row, and what it threw as the cause. */
    private static final class RowFailed extends Exception {

        private static final long serialVersionUID = 1L;

        private final int line;

        RowFailed(int line, Throwable cause) {
            super(null, cause, false, false);
            this.line = line;
        }
    }

    private static long millis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
