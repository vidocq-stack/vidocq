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
package io.vidocq.runtime.core.console;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * One aligned line per record:
 * {@code [LEVEL][yyyy-MM-dd HH:mm:ss.SSS][thread         ][source                                       ] : message}.
 *
 * <ul>
 *   <li>The level uses the {@code System.Logger} names Vidocq's code logs with ({@code ERROR},
 *       {@code WARN}, {@code INFO}, {@code DEBUG}, {@code TRACE}, plus {@code CONF} for JUL's
 *       {@code CONFIG}), derived from {@link Level#intValue()} so they are never localized. Only
 *       the level is coloured.</li>
 *   <li>The timestamp is in local time.</li>
 *   <li>The thread is the one publishing the record, read at format time: the handler publishes
 *       synchronously on the logging thread. An unnamed virtual thread shows
 *       {@code virtual-<id>}. A name wider than {@value #THREAD_WIDTH} columns keeps its end,
 *       after a {@code ~}.</li>
 *   <li>The source is {@code class#method} with the packages always abbreviated to their first
 *       letter, so a class always looks the same; see {@link #source(String, String, String)} for
 *       what happens when it is wider than {@value #SOURCE_WIDTH} columns.</li>
 *   <li>The message is {@link #formatMessage(LogRecord) formatted} ({@code {0}} parameters); its
 *       extra lines and the stack trace follow unchanged.</li>
 * </ul>
 */
public final class ConsoleLogFormatter extends Formatter {

    /** Width of the thread column. */
    public static final int THREAD_WIDTH = 15;
    /** Width of the source column. */
    public static final int SOURCE_WIDTH = 45;
    /** Width of the level column. */
    static final int LEVEL_WIDTH = 5;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private final boolean colors;
    /** {@code null}: the system default zone, read for every record like {@code SimpleFormatter}. */
    private final ZoneId zone;

    /** A formatter that colours the level when {@code colors} is true. */
    public ConsoleLogFormatter(boolean colors) {
        this(colors, null);
    }

    ConsoleLogFormatter(boolean colors, ZoneId zone) {
        this.colors = colors;
        this.zone = zone;
    }

    /** Whether the level is coloured. */
    public boolean colors() {
        return colors;
    }

    @Override
    public String format(LogRecord record) {
        Level level = record.getLevel() == null ? Level.INFO : record.getLevel();
        String name = levelName(level);
        StringBuilder line = new StringBuilder(192).append('[');
        line.append(colors ? Ansi.wrap(levelColor(level), name) : name);
        line.repeat(' ', LEVEL_WIDTH - name.length());
        line.append("][")
                .append(TIMESTAMP.format(record.getInstant().atZone(zone == null ? ZoneId.systemDefault() : zone)))
                .append("][")
                .append(fitEnd(threadName(Thread.currentThread()), THREAD_WIDTH))
                .append("][")
                .append(source(record.getSourceClassName(), record.getSourceMethodName(), record.getLoggerName()))
                .append("] : ")
                .append(formatMessage(record))
                .append(System.lineSeparator());
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            StringWriter trace = new StringWriter();
            try (PrintWriter writer = new PrintWriter(trace)) {
                thrown.printStackTrace(writer);
            }
            line.append(trace);
        }
        return line.toString();
    }

    /**
     * The level name: {@code ERROR} from {@code SEVERE}, {@code WARN} from {@code WARNING},
     * {@code INFO}, {@code CONF} from {@code CONFIG}, {@code DEBUG} from {@code FINE} and
     * {@code TRACE} below; a custom level takes the name of the standard level it reaches.
     */
    static String levelName(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= Level.INFO.intValue()) {
            return "INFO";
        }
        if (value >= Level.CONFIG.intValue()) {
            return "CONF";
        }
        if (value >= Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    /** ERROR bold red, WARN yellow, INFO green, CONF cyan, DEBUG and TRACE faint. */
    static String levelColor(Level level) {
        int value = level.intValue();
        if (value >= Level.SEVERE.intValue()) {
            return Ansi.BOLD_RED;
        }
        if (value >= Level.WARNING.intValue()) {
            return Ansi.YELLOW;
        }
        if (value >= Level.INFO.intValue()) {
            return Ansi.GREEN;
        }
        if (value >= Level.CONFIG.intValue()) {
            return Ansi.CYAN;
        }
        return Ansi.FAINT;
    }

    /** The thread's name, or {@code virtual-<id>} / {@code thread-<id>} when it has none. */
    static String threadName(Thread thread) {
        String name = thread.getName();
        if (name == null || name.isEmpty()) {
            return (thread.isVirtual() ? "virtual-" : "thread-") + thread.threadId();
        }
        return name;
    }

    /**
     * The source column, padded to {@value #SOURCE_WIDTH}. Each step applies only when the previous
     * one is still too wide:
     * <ol>
     *   <li>{@code i.v.r.c.VidocqBootstrap#configure}: packages abbreviated to their first
     *       letter;</li>
     *   <li>{@code VidocqBootstrap#configure}: the simple class name and the method;</li>
     *   <li>{@code McpInvokerBuildCompatibleExtension#registerI~}: the method cut at its end;</li>
     *   <li>the class name alone, when there is no room left for {@code #} and {@code ~};</li>
     *   <li>{@code ~ryLongClassName}: a class name that does not fit keeps its end.</li>
     * </ol>
     * When the source class is unknown, the logger name stands for it, without a method.
     */
    static String source(String className, String methodName, String loggerName) {
        String type = className;
        String method = methodName;
        if (type == null || type.isEmpty()) {
            type = loggerName == null ? "" : loggerName;
            method = null;
        }
        String suffix = method == null ? "" : "#" + method;
        String abbreviated = abbreviatePackages(type) + suffix;
        if (abbreviated.length() <= SOURCE_WIDTH) {
            return pad(abbreviated, SOURCE_WIDTH);
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        String simpleWithMethod = simple + suffix;
        if (simpleWithMethod.length() <= SOURCE_WIDTH) {
            return pad(simpleWithMethod, SOURCE_WIDTH);
        }
        if (method != null && simple.length() + 2 <= SOURCE_WIDTH) {
            return simpleWithMethod.substring(0, SOURCE_WIDTH - 1) + "~";
        }
        if (simple.length() <= SOURCE_WIDTH) {
            return pad(simple, SOURCE_WIDTH);
        }
        return fitEnd(simple, SOURCE_WIDTH);
    }

    /** {@code io.vidocq.runtime.core.VidocqBootstrap} becomes {@code i.v.r.c.VidocqBootstrap}. */
    static String abbreviatePackages(String type) {
        int last = type.lastIndexOf('.');
        if (last < 0) {
            return type;
        }
        StringBuilder abbreviated = new StringBuilder(type.length());
        int start = 0;
        while (start <= last) {
            int dot = type.indexOf('.', start);
            if (dot > start) {
                abbreviated.append(type.charAt(start));
            }
            abbreviated.append('.');
            start = dot + 1;
        }
        return abbreviated.append(type, last + 1, type.length()).toString();
    }

    /** Pads {@code value} to {@code width}; a longer value keeps its end, after a {@code ~}. */
    static String fitEnd(String value, int width) {
        if (value.length() <= width) {
            return pad(value, width);
        }
        return "~" + value.substring(value.length() - width + 1);
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
