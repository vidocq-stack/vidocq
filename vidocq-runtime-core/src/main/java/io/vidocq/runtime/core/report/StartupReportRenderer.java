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
package io.vidocq.runtime.core.report;

import io.vidocq.runtime.core.banner.LaunchModeResolver;
import io.vidocq.runtime.core.report.Section.Cells;
import io.vidocq.runtime.core.report.Section.Items;
import io.vidocq.runtime.core.report.Section.Line;
import io.vidocq.runtime.core.report.Section.Row;
import io.vidocq.runtime.core.report.Section.Text;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link StartupReport} as the text of its single log record. A pure function of the report.
 *
 * <p>{@code summary}: the title, then one line per section, {@code "  " + id + value}, the value in column 14
 * (two spaces after a longer id); a section without a summary value is left out.
 *
 * <pre>
 * Vidocq startup report
 *   launch      prod (auto: no dev or test signal) | report summary | details -Dvidocq.startup.report=detailed
 *   layer       4 modules (vidocq.app.path=.../app)
 *   extensions  chappe-engine, rest-cassini, chappe-mount-config, chappe-bootstrap
 *   anomalies   none
 * </pre>
 *
 * <p>{@code detailed}: the header block ({@code launch}, {@code vidocq}, {@code phases}) indented by two, then
 * every section with its id in column 0 and its headline in column 14, followed by the time the section took to
 * write when it was measured ({@code MCP server | 3 ms}, {@code | 62 ms, slow} beyond {@value #SLOW_MILLIS} ms),
 * then its lines indented by two. The values of the rows and lists of a section start in one column: two spaces
 * after its widest key, never before column 14; a value longer than the line wraps in that column, at a space,
 * within {@value #WIDTH} columns. The rows of a table share their column widths, two spaces wider than their
 * widest cell.
 *
 * <p>ASCII, two-space indents, no colour: the report is read in log files and Windows consoles. Every value
 * is {@linkplain #clean(String) cleaned} (no control character can break a line or forge a record) and cut to
 * {@value #MAX_VALUE} characters; a list or a table stops after {@value #MAX_ITEMS} items with how many more
 * there are.
 */
public final class StartupReportRenderer {

    /** The first line of the report. */
    public static final String TITLE = "Vidocq startup report";
    /** The widest line a wrapped value makes. */
    public static final int WIDTH = 105;
    /** The longest value printed; a longer one ends with {@code ...}. */
    public static final int MAX_VALUE = 200;
    /** The most items of a list, or rows of a table, that are printed. */
    public static final int MAX_ITEMS = 50;
    /** A section that took longer than this to write is marked {@code slow}; it is not an anomaly. */
    public static final long SLOW_MILLIS = 50;

    /** Between the parts of a header line: {@code dev (reason) | report detailed | override ...}. */
    static final String SEPARATOR = " | ";
    /** The indent of every line but a section's first. */
    static final int INDENT = 2;
    /** The narrowest key column, gap included: values never start before column 14. */
    static final int KEY_WIDTH = 12;
    /** The column of a section's headline, and of the values of the header block. */
    static final int HEADLINE_COLUMN = INDENT + KEY_WIDTH;
    /** The spaces between a key, or a table cell, and what follows it. */
    static final int GAP = 2;
    /** What ends a value that was cut. */
    static final String CUT = "...";
    /** What replaces a character that must not reach the log. */
    static final char REPLACEMENT = '?';

    private StartupReportRenderer() {}

    /** The report at its own {@link StartupReport#verbosity() verbosity}; empty at {@code off}. */
    public static String render(StartupReport report) {
        return switch (report.verbosity()) {
            case OFF -> "";
            case SUMMARY -> summary(report);
            case DETAILED -> detailed(report);
        };
    }

    /**
     * The first line of the warning a failed boot logs: {@code Vidocq startup failed in build after 71 ms
     * (jakarta.enterprise.inject.spi.DeploymentException); anomalies already logged: none}.
     *
     * @param partial       the partial report, its {@link StartupReport#failedPhase()} set
     * @param elapsedNanos  the time since the boot began
     * @param exceptionType the class name of what was thrown
     */
    public static String failure(StartupReport partial, long elapsedNanos, String exceptionType) {
        List<String> codes = codes(partial.anomalies());
        return "Vidocq startup failed in " + clean(partial.failedPhase()) + " after " + elapsedNanos / 1_000_000
                + " ms (" + clean(exceptionType) + "); anomalies already logged: "
                + (codes.isEmpty() ? "none" : String.join(", ", codes));
    }

    // -------------------------------------------------------------------------------------------- summary

    private static String summary(StartupReport report) {
        List<String> out = new ArrayList<>();
        out.add(title(report));
        out.add(summaryLine("launch", launch(report)));
        for (Section section : report.sections()) {
            if (section.summary() != null) {
                out.add(summaryLine(section.id(), section.summary()));
            }
        }
        List<String> codes = codes(report.anomalies());
        out.add(summaryLine("anomalies", codes.isEmpty() ? "none"
                : report.anomalies().size() + ": " + String.join(", ", capped(codes)) + " (logged above)"));
        return String.join("\n", out);
    }

    /** {@code "  " + id + value}, the value in column 14 or two spaces after a longer id; never wrapped. */
    private static String summaryLine(String id, String value) {
        String key = clean(id);
        return " ".repeat(INDENT) + key + " ".repeat(Math.max(GAP, KEY_WIDTH - key.length())) + clean(value);
    }

    // ------------------------------------------------------------------------------------------- detailed

    private static String detailed(StartupReport report) {
        List<String> out = new ArrayList<>();
        out.add(title(report));
        List<Line> header = new ArrayList<>();
        header.add(new Row("launch", launch(report)));
        if (report.runtime() != null) {
            header.add(new Row("vidocq", report.runtime()));
        }
        if (!report.phases().isEmpty()) {
            List<String> phases = new ArrayList<>();
            for (Phase phase : report.phases()) {
                phases.add(clean(phase.name()) + " " + phase.millis() + " ms");
            }
            header.add(new Row("phases", String.join(SEPARATOR, phases)));
        }
        lines(header, out);
        for (Section section : report.sections()) {
            out.addAll(headline(section.id(), timed(section)));
            lines(section.lines(), out);
        }
        List<Line> anomalies = new ArrayList<>();
        int column = INDENT + keyWidth(report.anomalies().stream().map(Anomaly::code).toList());
        for (Anomaly anomaly : report.anomalies()) {
            anomalies.add(new Row(anomaly.code(), oneLine(firstSentence(clean(anomaly.message())), WIDTH - column)));
        }
        out.addAll(headline("anomalies", anomalies.isEmpty() ? "none" : anomalies.size() + " (logged above)"));
        lines(anomalies, out);
        return String.join("\n", out);
    }

    /**
     * A section's first line: its id in column 0, then its headline, already {@linkplain #clean(String) clean}, in
     * column 14 or two spaces further.
     */
    private static List<String> headline(String id, String headline) {
        String key = clean(id);
        if (headline == null) {
            return List.of(key);
        }
        String lead = key + " ".repeat(Math.max(GAP, HEADLINE_COLUMN - key.length()));
        return wrap(lead, headline);
    }

    /**
     * The clean headline of {@code section}, then how long it took to write when that was measured, as
     * contributors' sections are: {@code MCP server | 3 ms}, {@code 62 ms, slow}.
     */
    private static String timed(Section section) {
        String headline = section.headline() == null ? null : clean(section.headline());
        if (section.nanos() < 0) {
            return headline;
        }
        long millis = section.nanos() / 1_000_000;
        String took = millis + " ms" + (section.nanos() > SLOW_MILLIS * 1_000_000 ? ", slow" : "");
        return headline == null ? took : headline + SEPARATOR + took;
    }

    /** The lines of a section, indented by two: rows and lists aligned on one column, tables on theirs. */
    private static void lines(List<Line> lines, List<String> out) {
        List<String> keys = new ArrayList<>();
        for (Line line : lines) {
            if (line instanceof Row row) {
                keys.add(row.key());
            } else if (line instanceof Items items && !items.items().isEmpty()) {
                keys.add(items.key());
            }
        }
        int width = keyWidth(keys);
        List<Cells> table = new ArrayList<>();
        for (Line line : lines) {
            if (!(line instanceof Cells)) {
                // a table ends where another kind of line starts
                table(table, out);
            }
            switch (line) {
                case Cells cells -> table.add(cells);
                case Row row -> out.addAll(row(row.key(), clean(row.value()), width));
                case Items items -> {
                    if (!items.items().isEmpty()) {
                        List<String> cleaned = items.items().stream().map(StartupReportRenderer::clean).toList();
                        out.addAll(row(items.key(), String.join(", ", capped(cleaned)), width));
                    }
                }
                case Text text -> out.addAll(wrap(" ".repeat(INDENT), clean(text.text())));
            }
        }
        table(table, out);
    }

    /** The key column of rows with these keys: two spaces wider than the widest, and never under 12. */
    private static int keyWidth(List<String> keys) {
        int widest = 0;
        for (String key : keys) {
            widest = Math.max(widest, clean(key).length());
        }
        return Math.max(KEY_WIDTH, widest + GAP);
    }

    private static List<String> row(String key, String value, int width) {
        String cleanKey = clean(key);
        return wrap(" ".repeat(INDENT) + cleanKey + " ".repeat(width - cleanKey.length()), value);
    }

    /** Prints and empties {@code table}: at most {@value #MAX_ITEMS} rows, the last cell of each unpadded. */
    private static void table(List<Cells> table, List<String> out) {
        if (table.isEmpty()) {
            return;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Cells cells : table.subList(0, Math.min(MAX_ITEMS, table.size()))) {
            rows.add(cells.cells().stream().map(StartupReportRenderer::clean).toList());
        }
        List<Integer> widths = new ArrayList<>();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                int length = row.get(i).length();
                if (i == widths.size()) {
                    widths.add(length);
                } else if (length > widths.get(i)) {
                    widths.set(i, length);
                }
            }
        }
        for (List<String> row : rows) {
            StringBuilder line = new StringBuilder(" ".repeat(INDENT));
            for (int i = 0; i < row.size(); i++) {
                line.append(row.get(i));
                if (i < row.size() - 1) {
                    line.repeat(' ', widths.get(i) + GAP - row.get(i).length());
                }
            }
            out.add(line.toString().stripTrailing());
        }
        if (table.size() > MAX_ITEMS) {
            out.add(" ".repeat(INDENT) + more(table.size() - MAX_ITEMS));
        }
        table.clear();
    }

    /**
     * {@code lead + value}, the value wrapped at spaces so that no line passes {@value #WIDTH} columns unless a
     * single word does; the following lines start in the column of the value, never with a {@code |}.
     */
    static List<String> wrap(String lead, String value) {
        int column = lead.length();
        int room = Math.max(20, WIDTH - column);
        List<String> lines = new ArrayList<>();
        String rest = value;
        String prefix = lead;
        while (rest.length() > room) {
            int cut = breakBefore(rest, room);
            if (cut <= 0) {
                cut = rest.indexOf(' ', room);
                if (cut < 0) {
                    break;
                }
            }
            lines.add((prefix + rest.substring(0, cut)).stripTrailing());
            rest = rest.substring(cut + 1);
            prefix = " ".repeat(column);
        }
        lines.add((prefix + rest).stripTrailing());
        return lines;
    }

    /**
     * Where to break {@code text} to keep its first line within {@code room} columns: after the last
     * {@code |} separator that fits, so that the parts it separates stay whole, else at the last space that
     * does not put a {@code |} first on the next line; {@code -1} when there is none.
     */
    private static int breakBefore(String text, int room) {
        int separator = text.lastIndexOf(SEPARATOR, room - 2);
        if (separator > 0) {
            return separator + 2;
        }
        int cut = text.lastIndexOf(' ', room);
        if (cut > 0 && text.startsWith("| ", cut + 1)) {
            // after the separator when it fits, else before the word it follows
            cut = cut + 2 <= room ? cut + 2 : text.lastIndexOf(' ', cut - 1);
        }
        return cut;
    }

    // ------------------------------------------------------------------------------------------- header

    private static String title(StartupReport report) {
        return report.failed() ? TITLE + " (partial)" : TITLE;
    }

    /**
     * {@code <mode> (<reason>) | report <level> | <hint>}: how to see more in {@code summary}, how to force the
     * other mode in {@code detailed}.
     */
    private static String launch(StartupReport report) {
        LaunchMode mode = report.launchMode();
        String level = report.verbosity().name().toLowerCase(Locale.ROOT);
        String hint = report.verbosity() == Verbosity.DETAILED
                ? "override -D" + LaunchModeResolver.MODE_KEY + "=" + (mode == LaunchMode.PROD ? "dev" : "prod")
                : "details -D" + VerbosityResolver.KEY + "=detailed";
        String reason = report.launchReason() == null ? "" : " (" + report.launchReason() + ")";
        return mode.label() + reason + SEPARATOR + "report " + level + SEPARATOR + hint;
    }

    // ------------------------------------------------------------------------------------------- values

    /**
     * {@code value} safe for one log line: every control character (line breaks, {@code ESC}, C1 controls), every
     * invisible formatting character (bidirectional overrides) and the Unicode line and paragraph separators
     * replaced by {@code ?}, then cut to {@value #MAX_VALUE} characters, the last three being {@code ...}.
     * {@code null} gives an empty string.
     */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), MAX_VALUE + 1));
        for (int i = 0; i < value.length() && cleaned.length() <= MAX_VALUE; ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            if (unsafe(codePoint)) {
                cleaned.append(REPLACEMENT);
            } else {
                cleaned.appendCodePoint(codePoint);
            }
        }
        if (cleaned.length() <= MAX_VALUE) {
            return cleaned.toString();
        }
        int end = MAX_VALUE - CUT.length();
        if (Character.isLowSurrogate(cleaned.charAt(end))) {
            end--;
        }
        return cleaned.substring(0, end) + CUT;
    }

    private static boolean unsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }

    /** The first {@value #MAX_ITEMS} items, then {@code ... and N more}. */
    static List<String> capped(List<String> items) {
        if (items.size() <= MAX_ITEMS) {
            return items;
        }
        List<String> capped = new ArrayList<>(items.subList(0, MAX_ITEMS));
        capped.add(more(items.size() - MAX_ITEMS));
        return capped;
    }

    private static String more(int count) {
        return CUT + " and " + count + " more";
    }

    /** The codes, each once in the order first logged, {@code x2} after one logged twice. */
    private static List<String> codes(List<Anomaly> anomalies) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Anomaly anomaly : anomalies) {
            counts.merge(clean(anomaly.code()), 1, Integer::sum);
        }
        List<String> codes = new ArrayList<>();
        counts.forEach((code, count) -> codes.add(count == 1 ? code : code + " x" + count));
        return codes;
    }

    /** Up to the end of the first sentence: the whole message was logged when it happened. */
    private static String firstSentence(String message) {
        int end = message.indexOf(". ");
        return end < 0 ? message : message.substring(0, end + 1);
    }

    /** {@code text} cut to {@code room} columns, with {@code ...}. */
    private static String oneLine(String text, int room) {
        return text.length() <= room ? text : text.substring(0, Math.max(0, room - CUT.length())) + CUT;
    }
}
