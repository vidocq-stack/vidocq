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

import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.DevConsolePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;
import io.vidocq.runtime.spi.devconsole.Unit;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The console's own {@code tests} panel (#122, spec §3), in a dev launch run by {@code vidocq:dev} with continuous
 * testing: the last test run the Maven plugin wrote to {@code target/vidocq-dev-tests.json}, and two actions.
 *
 * <ul>
 *   <li><b>Reading:</b> the daemon thread {@code vidocq-devconsole-tests} looks at the file's modification time every
 *       {@value #POLL_MILLIS} ms and, when it changed, parses it into an immutable {@link View} held in a volatile
 *       field; {@link #sample} reads that field only, never the disk. The plugin names the file with
 *       {@code -Dvidocq.dev.tests.results}; without it there is no panel.</li>
 *   <li><b>Values:</b> {@code state} as text, such as {@code failed (change)}; {@code run}, {@code failures},
 *       {@code errors} and {@code skipped} as gauges, those of the previous run while one is {@code running}; the
 *       table {@code failed-tests}, {@value #MAX_ROWS} rows at most. The chart <i>Tests</i> plots failures and
 *       errors.</li>
 *   <li><b>Actions:</b> {@code run-all} and {@code rerun-failed}, which write the one-word request file the plugin
 *       polls and answer {@value #QUEUED}. Neither takes an argument: the console never names a test (ADR 0001),
 *       the plugin computes the rerun itself.</li>
 * </ul>
 * A file that cannot be read keeps the previous view, and {@code state} says {@value #UNREADABLE}. Messages come
 * masked from the plugin.
 */
final class TestsPanel implements DevConsolePanel {

    /** The panel's id, reserved by the core for the console. */
    static final String ID = "tests";
    /** The system property that names the results file. */
    static final String PROPERTY = "vidocq.dev.tests.results";
    /** How often the file's modification time is looked at. */
    static final long POLL_MILLIS = 500;
    /** The rows of the failures table: the console's own limit. */
    static final int MAX_ROWS = 100;
    static final String QUEUED = "queued";
    static final String NOTHING_TO_RERUN = "no failed test to rerun";
    static final String UNREADABLE = "results unreadable";

    private static final List<Chart> CHARTS = List.of(
            new Chart("tests", "Tests", List.of(Series.line("failures"), Series.line("errors"))));
    private static final List<String> FAILURE_COLUMNS = List.of("test", "type", "message");
    private static final System.Logger LOG = System.getLogger(DevConsoleExtension.LOGGER_NAME);

    /** The counts shown. */
    record Counts(long run, long failures, long errors, long skipped) {}

    /**
     * What the panel shows of the file: its state and trigger, and the counts and failures of the run it shows —
     * the previous one while a run is {@code running} or after one was {@code cancelled}.
     */
    record View(String state, String trigger, String startedAt, long durationMillis, Counts counts,
            List<List<String>> failures, String log) {}

    private final Path results;
    private final Path request;
    private volatile View view;
    private volatile boolean unreadable;
    private volatile Thread reader;
    /** Guarded by this: the modification time last read, or -1. */
    private long lastModified = -1;
    /** Guarded by this: the size last read, or -1. */
    private long lastSize = -1;

    TestsPanel(Path results) {
        this.results = Objects.requireNonNull(results, "results");
        this.request = results.resolveSibling(TestRequestFile.FILE_NAME);
    }

    /**
     * The panel of this JVM, reading already, when {@code property} — {@link #PROPERTY}'s value — names a file;
     * empty otherwise.
     */
    static Optional<TestsPanel> start(String property) {
        if (property == null || property.isBlank()) {
            return Optional.empty();
        }
        TestsPanel panel = new TestsPanel(Path.of(property.strip()));
        panel.refresh();
        panel.startReading();
        return Optional.of(panel);
    }

    private void startReading() {
        reader = Thread.ofPlatform().name("vidocq-devconsole-tests").daemon(true).start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                refresh();
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException stopping) {
                    return;
                }
            }
        });
    }

    /** Stops the reader thread; idempotent. */
    void stop() {
        Thread running = reader;
        reader = null;
        if (running != null) {
            running.interrupt();
            try {
                running.join(1_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The reader thread, or {@code null} once stopped. */
    Thread reader() {
        return reader;
    }

    /**
     * Reads the file when its modification time or its size changed: {@code running} and the outcome of a run that
     * ended at once can be written within one millisecond (#138). The reader thread calls it, and so do the tests.
     */
    synchronized void refresh() {
        long modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(results).toMillis();
            size = Files.size(results);
        } catch (IOException noFileYet) {
            return;
        }
        if (modified == lastModified && size == lastSize) {
            return;
        }
        lastModified = modified;
        lastSize = size;
        try {
            view = parse(Files.readString(results, StandardCharsets.UTF_8));
            unreadable = false;
        } catch (IOException | RuntimeException failed) {
            unreadable = true;
            LOG.log(System.Logger.Level.DEBUG, "Dev console panel 'tests' cannot read " + results + ": "
                    + failed.getClass().getName());
        }
    }

    static View parse(String text) {
        if (!(JsonValues.parse(text, 8) instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        String state = string(document, "state");
        if (state.isEmpty()) {
            throw new IllegalArgumentException("no state");
        }
        Map<?, ?> shown = document;
        if (("running".equals(state) || "cancelled".equals(state))
                && document.get("previous") instanceof Map<?, ?> previous) {
            shown = previous;
        }
        Map<?, ?> counts = shown.get("counts") instanceof Map<?, ?> c ? c : Map.of();
        List<List<String>> rows = new ArrayList<>();
        if (shown.get("failures") instanceof List<?> failures) {
            for (Object each : failures) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                if (each instanceof Map<?, ?> failure) {
                    rows.add(List.of(string(failure, "test"), string(failure, "type"), string(failure, "message")));
                }
            }
        }
        return new View(state, string(document, "trigger"), string(shown, "startedAt"),
                number(shown, "durationMillis"), new Counts(number(counts, "run"), number(counts, "failures"),
                number(counts, "errors"), number(counts, "skipped")), List.copyOf(rows), string(document, "log"));
    }

    private static String string(Map<?, ?> map, String key) {
        return map.get(key) instanceof String value ? value : "";
    }

    private static long number(Map<?, ?> map, String key) {
        return map.get(key) instanceof Number value ? value.longValue() : 0;
    }

    private static String stateText(View view) {
        return view.state() + (view.trigger().isEmpty() ? "" : " (" + view.trigger() + ")");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Tests";
    }

    /** The last run when the console starts, where its results and log are. */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        View current = view;
        section.summary(current == null ? "continuous testing, no run yet" : "last run " + stateText(current))
                .row("results", results.toString())
                .row("log", current == null || current.log().isEmpty() ? "target/vidocq-dev-tests.log"
                        : current.log());
        if (current != null) {
            section.row("last run", current.startedAt() + ", " + current.durationMillis() + " ms");
        }
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    @Override
    public List<PanelAction> actions() {
        return List.of(
                new PanelAction("run-all", "Run all tests", null, arguments -> request("run-all")),
                new PanelAction("rerun-failed", "Rerun failed tests", null, arguments -> rerunFailed()));
    }

    private String rerunFailed() {
        View current = view;
        if (current == null || current.failures().isEmpty()) {
            return NOTHING_TO_RERUN;
        }
        return request("rerun-failed");
    }

    private String request(String word) {
        try {
            TestRequestFile.write(request, word);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return QUEUED;
    }

    @Override
    public void sample(PanelSample sample) {
        View current = view;
        if (current == null) {
            sample.text("state", unreadable ? UNREADABLE : "no run yet");
            return;
        }
        sample.text("state", unreadable ? UNREADABLE : stateText(current))
                .gauge("run", current.counts().run(), Unit.COUNT)
                .gauge("failures", current.counts().failures(), Unit.COUNT)
                .gauge("errors", current.counts().errors(), Unit.COUNT)
                .gauge("skipped", current.counts().skipped(), Unit.COUNT)
                .table("failed-tests", FAILURE_COLUMNS, current.failures());
    }
}
