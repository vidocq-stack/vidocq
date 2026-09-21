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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.report.ReportAnomaly;
import io.vidocq.runtime.spi.report.ReportLine;
import io.vidocq.runtime.spi.report.ReportSection;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The snapshot of one boot, {@code GET /api/snapshot}: one JSON document per poll of the page, always complete.
 *
 * <pre>
 * { "console": {"vidocq": "0.4.0-SNAPSHOT", "url": "http://127.0.0.1:8888/", "boot": "7f3a91c04be2d810",
 *               "time": 1789740602114, "pollMillis": 1000, "portTaken": null, "historyTruncated": false},
 *   "state": "ready",
 *   "startup": {"launchMode": "dev", "launchReason": "...", "anomalies": [{"code", "message", "hint", "source"}],
 *               "sections": [{"id": "layer", "headline": "...", "summary": "...", "lines": [["weaving", "none"]]}],
 *               "text": "Vidocq startup report\n..."},
 *   "panels": [{"id": "mansart-pool", "title": "Mansart pools", "live": true, "summary": "...",
 *               "lines": [["@Default", "jdbc:h2:mem:demo"]], "charts": [{"id", "title", "series": [{"key", "style"}]}],
 *               "sample": {"nanos": 38000, "slow": false, "truncated": false, "values": [...], "groups": [...]},
 *               "history": [{"group": "main", "key": "active", "kind": "gauge", "unit": "count",
 *                            "t": [1789740601114, 1789740602114], "v": [3, null], "max": [8, 8]}]}] }
 * </pre>
 *
 * <ul>
 *   <li>{@code console.time} is the server's clock at the poll: the page never uses its own. {@code console.boot}
 *       changes with every boot, a dev reload included: the page then forgets the history it drew and asks for the
 *       whole ring again. {@code console.portTaken} is {@code {"configured": 8888, "bound": 54213}} when the
 *       configured port was taken and the console listens on another one, {@code null} otherwise.
 *       {@code console.historyTruncated} is {@code true} once a series was refused for want of a slot.</li>
 *   <li>{@code panels[].history} is the {@link PanelHistory} of that panel: one entry per measured key, its points
 *       oldest first, in three arrays of the same length — {@code t} the server's clock, {@code v} the value,
 *       {@code null} for an absence, and {@code max} the ceiling of a gauge, present only when the series ever had
 *       one. {@code GET /api/snapshot?since=<t>} carries the points after {@code t} only; without {@code since},
 *       every point kept. That is what lets a tab that was hidden for three minutes redraw a complete curve.</li>
 *   <li>Until the boot has written its report, {@code state} is {@code booting}, {@code startup} is {@code null}
 *       and {@code panels} has only the console's own panels. Then {@code state} is {@code ready}.</li>
 *   <li>{@code startup.sections} has the sections of the report that are no panel: the header's and the core's,
 *       and the console's own. A line is an array, its key first, {@code null} for a row of a table or a line of
 *       text, then its values; the summary is not repeated among the lines.</li>
 *   <li>{@code panels} has one panel per contributor of the report, in report order, then the console's own. A
 *       contributor that is no {@link io.vidocq.runtime.spi.devconsole.DevConsolePanel} has boot facts only:
 *       {@code live} is {@code false}, its {@code charts} empty and its {@code sample} {@code null}.</li>
 *   <li>A panel whose {@code sample()} throws has {@code "sample": {"error": "<class>"}}: the simple name of the
 *       exception's class, never its message, which may carry a secret. The console logs
 *       {@code [VIDOCQ-DEVC-005]} once per panel and boot, with the stack trace at DEBUG, and calls the panel
 *       again on the next poll. A sample that took more than 5 ms is {@code slow}.</li>
 *   <li>Every string of the report and of the samples is {@linkplain Texts#clean cleaned and cut}; a section keeps
 *       {@value #MAX_LINES} lines of {@value #MAX_LINE_VALUES} values, flagged {@code "truncated": true} beyond.</li>
 * </ul>
 *
 * <p>Read from any number of request threads at once; {@link #bound} is written once, on the boot thread.
 */
final class Snapshot implements Handler {

    /** How often the page asks for a snapshot. */
    static final long POLL_MILLIS = 1000;
    /** A sample that takes longer is flagged {@code slow}. */
    static final long SLOW_NANOS = 5_000_000L;
    /** The code of a panel whose sample failed. */
    static final String SAMPLE_FAILED = "VIDOCQ-DEVC-005";
    /** The most lines of a section, and anomalies of the report, a snapshot carries. */
    static final int MAX_LINES = 500;
    /** The most values of one line a snapshot carries. */
    static final int MAX_LINE_VALUES = 100;

    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    private final String bootId;
    private final String vidocq;
    private final Supplier<Optional<StartupReportView>> report;
    private final List<PanelEntry> builtIns;
    private final LongSupplier clock;
    /** The panels whose failure was logged this boot. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** Five minutes of every measure, filled by {@link #tick()} whether or not a page is watching. */
    private final PanelHistory history = new PanelHistory();
    private volatile Bound bound;
    private volatile Contributed contributed;

    /**
     * Where the console listens.
     *
     * @param url        the URL of the page
     * @param configured the port configured, {@code 0} for any
     * @param port       the port bound
     */
    private record Bound(String url, int configured, int port) {

        boolean portTaken() {
            return configured != 0 && configured != port;
        }
    }

    /**
     * The panels of a written report, read once: the report never changes.
     *
     * @param view     the report
     * @param panels   one per contributor, in report order
     * @param sections the sections that are no panel
     */
    private record Contributed(StartupReportView view, List<PanelEntry> panels, List<ReportSection> sections) {}

    /**
     * @param bootId   the id of this boot, 64 random bits in hex
     * @param vidocq   the Vidocq version, or {@code null}
     * @param report   the startup report of this boot, empty until it is written
     * @param builtIns the console's own panels, shown last, from the first poll
     * @param clock    the server's clock, in epoch milliseconds
     */
    Snapshot(String bootId, String vidocq, Supplier<Optional<StartupReportView>> report, List<PanelEntry> builtIns,
             LongSupplier clock) {
        this.bootId = Objects.requireNonNull(bootId, "bootId");
        this.vidocq = vidocq;
        this.report = Objects.requireNonNull(report, "report");
        this.builtIns = List.copyOf(builtIns);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records where the console listens, once its listener is bound.
     *
     * @param url        the URL of the page, as the console printed it
     * @param configured the port configured, {@code 0} for any
     * @param port       the port bound
     */
    void bound(String url, int configured, int port) {
        this.bound = new Bound(url, configured, port);
    }

    /** The snapshot as JSON, {@code application/json; charset=utf-8}, never cached; a HEAD gets its headers. */
    @Override
    public Response handle(Request request) {
        return Response.builder()
                .status(StatusCode.OK)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Cache-Control", "no-store")
                .body(document(since(request)))
                .build();
    }

    /**
     * The point the page says it already holds, {@code -1} for "send everything": a {@code since} that is not a
     * number is one, since a page that cannot say what it has must be given all of it rather than nothing.
     */
    private static long since(Request request) {
        try {
            String asked = request.queryParams().get("since");
            return asked == null ? -1 : Long.parseLong(asked.trim());
        } catch (RuntimeException unusable) {
            return -1;
        }
    }

    /**
     * Samples every panel into the history. Called by the console's own thread, once a {@value #POLL_MILLIS}
     * milliseconds, whether or not a page is watching: that is the whole point of keeping the history here.
     *
     * <p>A panel that throws costs its own series this tick and nothing else, and is not logged — {@code sample()}
     * is called again by the next poll, and {@link #writeSample} reports the failure to whoever is looking.
     */
    void tick() {
        long time = clock.getAsLong();
        Contributed panels = view().map(this::contributed).orElse(null);
        if (panels != null) {
            for (PanelEntry panel : panels.panels()) {
                record(time, panel);
            }
        }
        for (PanelEntry panel : builtIns) {
            record(time, panel);
        }
    }

    private void record(long time, PanelEntry panel) {
        if (panel.panel() == null) {
            return;
        }
        RecordingSample sample = new RecordingSample();
        try {
            panel.panel().sample(sample);
        } catch (RuntimeException | LinkageError failure) {
            return;
        }
        history.record(time, panel.id(), sample);
    }

    /** The history this snapshot keeps, for the tests and for the extension that stops it. */
    PanelHistory history() {
        return history;
    }

    /** The snapshot document of this poll, with every point of the history. */
    String document() {
        return document(-1);
    }

    /**
     * The snapshot document of this poll.
     *
     * @param since the newest point the page already holds, negative for every point kept
     */
    String document(long since) {
        long time = clock.getAsLong();
        Optional<StartupReportView> view = view();
        JsonWriter out = new JsonWriter().beginObject();
        writeConsole(out, time);
        out.name("state").value(view.isPresent() ? "ready" : "booting");
        out.name("startup");
        Contributed panels = view.map(this::contributed).orElse(null);
        if (panels == null) {
            out.nullValue();
        } else {
            writeStartup(out, panels);
        }
        out.name("panels").beginArray();
        if (panels != null) {
            for (PanelEntry panel : panels.panels()) {
                writePanel(out, panel, since);
            }
        }
        for (PanelEntry panel : builtIns) {
            writePanel(out, panel, since);
        }
        return out.endArray().endObject().toString();
    }

    private Optional<StartupReportView> view() {
        try {
            Optional<StartupReportView> view = report.get();
            return view == null ? Optional.empty() : view;
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private void writeConsole(JsonWriter out, long time) {
        Bound where = bound;
        out.name("console").beginObject()
                .name("vidocq").value(vidocq)
                .name("url").value(where == null ? null : where.url())
                .name("boot").value(bootId)
                .name("time").value(time)
                .name("pollMillis").value(POLL_MILLIS)
                .name("portTaken");
        if (where != null && where.portTaken()) {
            out.beginObject().name("configured").value(where.configured()).name("bound").value(where.port())
                    .endObject();
        } else {
            out.nullValue();
        }
        out.name("historyTruncated").value(history.truncated());
        out.endObject();
    }

    /** The panels of {@code view}, read on the first poll that sees it. */
    private Contributed contributed(StartupReportView view) {
        Contributed current = contributed;
        if (current != null && current.view() == view) {
            return current;
        }
        synchronized (this) {
            current = contributed;
            if (current == null || current.view() != view) {
                current = read(view);
                contributed = current;
            }
            return current;
        }
    }

    private static Contributed read(StartupReportView view) {
        List<ReportSection> sections = view.sections();
        List<PanelEntry> panels = new ArrayList<>();
        Set<String> panelIds = new HashSet<>();
        for (StartupReportContributor contributor : view.contributors()) {
            String id = id(contributor);
            if (id == null || id.equals(DevConsoleExtension.ID) || panelIds.contains(id)) {
                continue;
            }
            for (ReportSection section : sections) {
                if (section.id().equals(id)) {
                    panels.add(PanelEntry.contributed(contributor, section));
                    panelIds.add(id);
                    break;
                }
            }
        }
        List<ReportSection> others = sections.stream().filter(s -> !panelIds.contains(s.id())).toList();
        return new Contributed(view, List.copyOf(panels), others);
    }

    private static String id(StartupReportContributor contributor) {
        try {
            return contributor.id();
        } catch (RuntimeException | LinkageError failed) {
            return null;
        }
    }

    private static void writeStartup(JsonWriter out, Contributed contributed) {
        StartupReportView view = contributed.view();
        out.beginObject()
                .name("launchMode").value(view.launchMode().label())
                .name("launchReason").value(Texts.clean(view.launchReason()))
                .name("anomalies").beginArray();
        List<ReportAnomaly> anomalies = view.anomalies();
        for (ReportAnomaly anomaly : anomalies.subList(0, Math.min(MAX_LINES, anomalies.size()))) {
            out.beginObject()
                    .name("code").value(Texts.clean(anomaly.code()))
                    .name("message").value(Texts.clean(anomaly.message()))
                    .name("hint").value(Texts.clean(anomaly.hint()))
                    .name("source").value(Texts.clean(anomaly.source()))
                    .endObject();
        }
        out.endArray();
        if (anomalies.size() > MAX_LINES) {
            out.name("truncated").value(true);
        }
        out.name("sections").beginArray();
        for (ReportSection section : contributed.sections()) {
            out.beginObject()
                    .name("id").value(Texts.clean(section.id()))
                    .name("headline").value(Texts.clean(section.headline()))
                    .name("summary").value(Texts.clean(section.summary()));
            writeLines(out, withoutSummary(section));
            out.endObject();
        }
        out.endArray();
        out.name("text").value(detailedText(view));
        out.endObject();
    }

    private static String detailedText(StartupReportView view) {
        try {
            return view.detailedText();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The members {@code lines} and, when some were dropped, {@code truncated}. */
    private static void writeLines(JsonWriter out, List<ReportLine> lines) {
        boolean truncated = lines.size() > MAX_LINES;
        out.name("lines").beginArray();
        for (ReportLine line : lines.subList(0, Math.min(MAX_LINES, lines.size()))) {
            out.beginArray().value(Texts.clean(line.key()));
            List<String> values = line.values();
            truncated |= values.size() > MAX_LINE_VALUES;
            for (String value : values.subList(0, Math.min(MAX_LINE_VALUES, values.size()))) {
                out.value(Texts.clean(value));
            }
            out.endArray();
        }
        out.endArray();
        if (truncated) {
            out.name("truncated").value(true);
        }
    }

    private void writePanel(JsonWriter out, PanelEntry panel, long since) {
        ReportSection section = panel.section();
        out.beginObject()
                .name("id").value(Texts.clean(panel.id()))
                .name("title").value(Texts.clean(panel.title()))
                .name("live").value(panel.panel() != null)
                .name("summary").value(Texts.clean(section.summary()));
        writeLines(out, withoutSummary(section));
        out.name("charts").beginArray();
        for (Chart chart : panel.charts()) {
            out.beginObject().name("id").value(chart.id()).name("title").value(Texts.clean(chart.title()))
                    .name("series").beginArray();
            for (Series series : chart.series()) {
                out.beginObject().name("key").value(series.key())
                        .name("style").value(series.style().name().toLowerCase(Locale.ROOT)).endObject();
            }
            out.endArray().endObject();
        }
        out.endArray();
        out.name("sample");
        if (panel.panel() == null) {
            out.nullValue();
        } else {
            writeSample(out, panel);
        }
        out.name("history");
        history.writeTo(out, panel.id(), since);
        out.endObject();
    }

    /** The lines of {@code section} but the first, when it is the summary, as the report prints it first. */
    private static List<ReportLine> withoutSummary(ReportSection section) {
        List<ReportLine> lines = section.lines();
        if (section.summary() != null && !lines.isEmpty() && lines.get(0).key() == null
                && lines.get(0).values().equals(List.of(section.summary()))) {
            return lines.subList(1, lines.size());
        }
        return lines;
    }

    /** Calls the panel with a fresh sample and writes it; a failure costs this sample only. */
    private void writeSample(JsonWriter out, PanelEntry panel) {
        RecordingSample sample = new RecordingSample();
        Throwable failure = null;
        long start = System.nanoTime();
        try {
            panel.panel().sample(sample);
        } catch (RuntimeException | LinkageError e) {
            failure = e;
        }
        long nanos = System.nanoTime() - start;
        if (failure != null) {
            failed(panel.id(), failure);
            out.beginObject().name("error").value(className(failure)).endObject();
            return;
        }
        out.beginObject().name("nanos").value(nanos).name("slow").value(nanos > SLOW_NANOS);
        sample.writeTo(out);
        out.endObject();
    }

    /** Logs {@value #SAMPLE_FAILED} the first time {@code id} fails this boot, its stack trace at DEBUG. */
    private void failed(String id, Throwable failure) {
        if (!failed.add(id)) {
            return;
        }
        String panel = Texts.clean(id);
        LOG.log(System.Logger.Level.WARNING, "[" + SAMPLE_FAILED + "] Panel '" + panel + "' failed to sample: "
                + className(failure));
        LOG.log(System.Logger.Level.DEBUG, "Dev console panel '" + panel + "' failed to sample", failure);
    }

    /** The simple name of the class of {@code failure}, which says what failed and carries nothing of the data. */
    private static String className(Throwable failure) {
        Class<?> type = failure.getClass();
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
