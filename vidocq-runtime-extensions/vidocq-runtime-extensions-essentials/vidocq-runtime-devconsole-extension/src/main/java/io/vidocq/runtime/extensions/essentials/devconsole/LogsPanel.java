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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * The console's own {@code logs} panel, in a dev launch only: the last records the application logged, how many
 * warnings and errors it logged since the boot, the loggers whose level is set, and an action that sets one.
 *
 * <ul>
 *   <li><b>Boot facts:</b> where the logs go, {@code java.util.logging} or the {@code System.LoggerFinder} that took
 *       them, how many records the panel keeps, and the level of the root logger.</li>
 *   <li><b>Live values:</b> {@code warnings} and {@code errors}, the WARNING and SEVERE records seen since the boot,
 *       plotted per second; {@code records}, the last {@value #MAX_ROWS} of the {@value LogRing#CAPACITY} kept,
 *       newest first; {@code levels}, the root logger and every logger whose level is set.</li>
 *   <li><b>Action:</b> {@code set-level}, the level of a logger, until the next dev reload.</li>
 * </ul>
 *
 * <p>The records come from a {@link LogRing} the panel adds to the root logger when the console starts, and removes
 * when it stops, so that a dev reload never stacks two. When {@code System.Logger} goes to another backend, an SLF4J
 * or Log4j {@code LoggerFinder}, the records the platform and Vidocq log never reach {@code java.util.logging}: the
 * panel says so, {@code records} absent with the reason, rather than showing an empty table that would read as a
 * quiet application.
 *
 * <p>A message may carry anything: the panel exists in a dev launch only, and removes the credentials of a URL as the
 * {@code config} panel does. The loggers whose level the action set are held here, since {@code java.util.logging}
 * holds its loggers weakly and would forget the level with the logger, and put back as they were on {@link #stop},
 * so that a reload starts from the configured levels.
 */
final class LogsPanel implements DevConsolePanel {

    /** The panel's id, reserved by the core for the console. */
    static final String ID = "logs";
    /** The rows of the records table: the console's own limit. */
    static final int MAX_ROWS = 100;
    /** The levels the action accepts, from the most severe. */
    static final List<String> LEVELS =
            List.of("SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL", "OFF");
    /** A logger name, or the empty string for the root logger. */
    static final String LOGGER_PATTERN = "([A-Za-z_$][A-Za-z0-9_$.]{0,199})?";
    /** The {@code System.LoggerFinder} classes of the JDK, which send {@code System.Logger} to JUL. */
    static final Set<String> JDK_FINDERS =
            Set.of("sun.util.logging.internal.LoggingProviderImpl", "jdk.internal.logger.DefaultLoggerFinder");

    private static final String ROOT = "(root)";
    private static final List<Chart> CHARTS = List.of(
            new Chart("problems", "Warnings and errors", List.of(Series.rate("warnings"), Series.rate("errors"))));
    private static final List<String> RECORD_COLUMNS = List.of("time", "level", "logger", "thread", "message");
    private static final List<String> LEVEL_COLUMNS = List.of("logger", "level", "set");

    /** A logger whose level the action set, held so that it keeps it, and the level it had before. */
    private record Change(Logger logger, Level previous) {}

    private final Logger root;
    /** The reason the records are not seen, or {@code null} when {@code System.Logger} goes to JUL. */
    private final String elsewhere;
    private final String backend;
    /** Guarded by itself; the action writes it, {@link #stop} empties it. */
    private final Map<String, Change> changes = new LinkedHashMap<>();

    private volatile LogRing ring;
    private boolean stopped;

    /**
     * @param finder the class name of the {@code System.LoggerFinder} in use
     * @param root   the root logger, which the ring is added to
     */
    LogsPanel(Supplier<String> finder, Logger root) {
        this.root = Objects.requireNonNull(root, "root");
        String name = finderName(finder);
        if (name == null || JDK_FINDERS.contains(name)) {
            this.backend = "java.util.logging";
            this.elsewhere = null;
        } else {
            this.backend = name;
            this.elsewhere = "logs go to " + simpleName(name) + ", not java.util.logging";
        }
    }

    /** Starts the panel of this JVM: its ring added to the root logger. */
    static LogsPanel start() {
        LogsPanel panel = new LogsPanel(() -> System.LoggerFinder.getLoggerFinder().getClass().getName(),
                LogManager.getLogManager().getLogger(""));
        panel.install();
        return panel;
    }

    /**
     * Adds a new ring to the root logger, once: a second call keeps the first ring. A ring left on the root logger by
     * a previous boot, one that never stopped, is removed first: a dev reload never stacks two.
     */
    void install() {
        if (ring != null) {
            return;
        }
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof LogRing) {
                root.removeHandler(handler);
            }
        }
        LogRing installed = new LogRing();
        root.addHandler(installed);
        ring = installed;
    }

    /**
     * Stops the panel: the ring removed from the root logger, and every level the action set put back as it was,
     * the loggers let go. Idempotent.
     */
    void stop() {
        LogRing installed = ring;
        ring = null;
        if (installed != null) {
            root.removeHandler(installed);
        }
        synchronized (changes) {
            stopped = true;
            List<Change> set = new ArrayList<>(changes.values());
            Collections.reverse(set);
            for (Change change : set) {
                change.logger().setLevel(change.previous());
            }
            changes.clear();
        }
    }

    /** The ring of this boot, or {@code null} once stopped. */
    LogRing ring() {
        return ring;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String title() {
        return "Logs";
    }

    /** Where the logs go, how many records the panel keeps, and the level of the root logger. */
    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        String rootLevel = levelName(root.getLevel());
        section.summary(elsewhere != null ? elsewhere
                        : "the last " + LogRing.CAPACITY + " records, root logger at " + rootLevel)
                .row("backend", backend)
                .row("kept", LogRing.CAPACITY + " records, in memory, for this boot")
                .row("root level", rootLevel);
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    /** {@code set-level}: the level of a logger, or of the root one, until the next dev reload. */
    @Override
    public List<PanelAction> actions() {
        return List.of(new PanelAction("set-level", "Set level", null,
                List.of(PanelAction.Argument.matching("logger", "Logger (empty for the root)", LOGGER_PATTERN),
                        PanelAction.Argument.oneOf("level", "Level", LEVELS.toArray(String[]::new))),
                this::setLevel));
    }

    /**
     * Sets the level of the logger the arguments name, holding it and remembering the level it had the first time.
     *
     * @param arguments {@code logger}, a name or empty for the root, and {@code level}, one of {@link #LEVELS}
     * @return what was done, such as {@code io.vidocq.cassini at FINE until the next reload}
     */
    String setLevel(Map<String, String> arguments) {
        String name = Objects.requireNonNull(arguments.get("logger"), "logger");
        Level level = Level.parse(Objects.requireNonNull(arguments.get("level"), "level"));
        Logger logger = name.isEmpty() ? root : Logger.getLogger(name);
        synchronized (changes) {
            if (stopped) {
                throw new IllegalStateException("the logs panel is stopped");
            }
            changes.putIfAbsent(name, new Change(logger, logger.getLevel()));
            logger.setLevel(level);
        }
        return (name.isEmpty() ? "the root logger" : name) + " at " + level.getName() + " until the next reload";
    }

    /** The counters, the last records, newest first, and the loggers whose level is set. */
    @Override
    public void sample(PanelSample sample) {
        LogRing current = ring;
        if (current == null) {
            return;
        }
        if (elsewhere != null) {
            sample.absent("warnings", elsewhere).absent("errors", elsewhere).absent("records", elsewhere);
        } else {
            sample.counter("warnings", current.warnings(), Unit.COUNT)
                    .counter("errors", current.errors(), Unit.COUNT);
            List<List<String>> rows = new ArrayList<>();
            for (LogRing.Line line : current.latest(MAX_ROWS)) {
                rows.add(List.of(line.time(), line.level(), LogRing.shortName(line.logger()), line.thread(),
                        line.message()));
            }
            sample.table("records", RECORD_COLUMNS, rows);
        }
        sample.table("levels", LEVEL_COLUMNS, levels());
    }

    /**
     * The root logger, then every logger whose level is set, by name, {@value #MAX_ROWS} at most; the third column
     * says which ones the action set.
     */
    List<List<String>> levels() {
        Set<String> changed;
        synchronized (changes) {
            changed = Set.copyOf(changes.keySet());
        }
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(ROOT, levelName(root.getLevel()), changed.contains("") ? "until the next reload" : ""));
        LogManager manager = LogManager.getLogManager();
        List<String> names = Collections.list(manager.getLoggerNames());
        Collections.sort(names);
        for (String name : names) {
            if (rows.size() >= MAX_ROWS) {
                break;
            }
            if (name.isEmpty()) {
                continue;
            }
            Logger logger = manager.getLogger(name);
            Level level = logger == null ? null : logger.getLevel();
            if (level != null) {
                rows.add(List.of(name, level.getName(), changed.contains(name) ? "until the next reload" : ""));
            }
        }
        return rows;
    }

    private static String levelName(Level level) {
        return level == null ? "not set" : level.getName();
    }

    private static String finderName(Supplier<String> finder) {
        try {
            return finder.get();
        } catch (RuntimeException | LinkageError unknown) {
            return null;
        }
    }

    private static String simpleName(String className) {
        return className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
    }
}
