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

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The {@code java.util.logging} handler of the {@code logs} panel: the last {@value #CAPACITY} records published on
 * the root logger, each written once into strings, and how many WARNING and SEVERE records it saw since it was
 * installed.
 *
 * <p><b>Cheap, and never blocking.</b> {@link #publish} runs on the thread that logs. It formats the record into a
 * {@link Line} of strings, then takes a slot of a fixed array with one atomic increment and writes it there: no lock,
 * no allocation beyond the line, never a wait. A reader, {@link #latest}, walks back from the last slot taken and
 * skips a slot a later record has overwritten since, so it never shows a line twice nor out of order.
 *
 * <p><b>Strings only.</b> The ring never keeps the {@link LogRecord}, its parameters or its {@link Throwable}: they
 * may be objects of the application, which would keep its class loader alive past a dev reload. The message is
 * formatted as {@link Formatter#formatMessage} formats it; a throwable adds its class name and the first line of its
 * message, never its stack. The credentials of a URL in the message are removed as the {@code config} panel removes
 * them ({@link ConfigValues#withoutCredentials}), and the message is cut at {@value #MAX_MESSAGE} characters.
 */
final class LogRing extends Handler {

    /** How many records the ring keeps. */
    static final int CAPACITY = 500;
    /** The longest message kept, in characters. */
    static final int MAX_MESSAGE = 500;
    /**
     * How much of a message is read before its credentials are removed: enough that a URL starting before the cut
     * at {@value #MAX_MESSAGE} is masked whole, and bounded so that a huge message costs the logging thread little.
     */
    private static final int MASKED_PREFIX = 2 * MAX_MESSAGE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * One record, as the panel shows it: strings only.
     *
     * @param sequence its rank among the records the ring saw, from 0
     * @param time     when it was logged, {@code HH:mm:ss.SSS} in the zone of the JVM
     * @param level    the name of its level, such as {@code WARNING}
     * @param logger   the name of its logger, empty for the root one
     * @param thread   the name of the thread that logged it
     * @param message  its message, formatted, without credentials, at most {@value #MAX_MESSAGE} characters
     */
    record Line(long sequence, String time, String level, String logger, String thread, String message) {}

    /** Formats a message with the parameters and the resource bundle of its record, never the whole record. */
    private final Formatter formatter = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };
    private final AtomicReferenceArray<Line> slots = new AtomicReferenceArray<>(CAPACITY);
    private final AtomicLong next = new AtomicLong();
    private final LongAdder warnings = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final ZoneId zone = ZoneId.systemDefault();

    /** A ring that takes every level: what reaches it is what the loggers let through. */
    LogRing() {
        setLevel(Level.ALL);
    }

    /** Keeps {@code record} as a line of strings, and counts it when it is a WARNING or a SEVERE one. Never throws. */
    @Override
    public void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }
        try {
            int level = record.getLevel().intValue();
            if (level >= Level.SEVERE.intValue()) {
                errors.increment();
            } else if (level >= Level.WARNING.intValue()) {
                warnings.increment();
            }
            String time = LocalTime.ofInstant(record.getInstant(), zone).format(TIME);
            String logger = record.getLoggerName() == null ? "" : record.getLoggerName();
            String thread = Thread.currentThread().getName();
            if (thread.isEmpty()) {
                thread = "#" + Thread.currentThread().threadId();
            }
            long sequence = next.getAndIncrement();
            store(new Line(sequence, time, record.getLevel().getName(), logger, thread, message(record)));
        } catch (RuntimeException | LinkageError unreadable) {
            // a record the ring cannot read is left out: logging never fails because of the console
        }
    }

    /**
     * Puts a line in its slot, unless the slot already holds a newer one. Two publishers {@value #CAPACITY} records
     * apart share a slot and may store in either order: the newer line must win, or the older one would overwrite it
     * and the reader, finding a stale sequence, would drop both.
     */
    void store(Line line) {
        slots.accumulateAndGet((int) (line.sequence() % CAPACITY), line,
                (stored, offered) -> stored == null || stored.sequence() < offered.sequence() ? offered : stored);
    }

    /**
     * The message of {@code record} as the panel shows it: formatted, followed by the class and the first line of
     * the message of its throwable, without the credentials of a URL, cut at {@value #MAX_MESSAGE} characters.
     */
    String message(LogRecord record) {
        String text;
        try {
            text = formatter.formatMessage(record);
        } catch (RuntimeException formatting) {
            text = record.getMessage();
        }
        StringBuilder message = new StringBuilder(text == null ? "" : text);
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            message.append(message.isEmpty() ? "" : " - ").append(thrown.getClass().getName());
            String first = firstLine(thrown.getMessage());
            if (first != null) {
                message.append(": ").append(first);
            }
        }
        String prefix = message.length() > MASKED_PREFIX ? message.substring(0, MASKED_PREFIX) : message.toString();
        String masked = ConfigValues.withoutCredentials(prefix);
        return masked.length() > MAX_MESSAGE ? masked.substring(0, MAX_MESSAGE) : masked;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int end = text.indexOf('\n');
        int cr = text.indexOf('\r');
        if (cr >= 0 && (end < 0 || cr < end)) {
            end = cr;
        }
        return end < 0 ? text : text.substring(0, end);
    }

    /**
     * The last records kept, newest first.
     *
     * @param max how many at most
     * @return the lines, newest first; at most {@code max} and {@value #CAPACITY}
     */
    List<Line> latest(int max) {
        long last = next.get() - 1;
        long oldest = Math.max(0, last - CAPACITY + 1);
        List<Line> lines = new ArrayList<>(Math.min(max, CAPACITY));
        for (long sequence = last; sequence >= oldest && lines.size() < max; sequence--) {
            Line line = slots.get((int) (sequence % CAPACITY));
            // a slot a later record took since, or one still being written: skipped
            if (line != null && line.sequence() == sequence) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** How many records the ring saw, kept or since overwritten. */
    long seen() {
        return next.get();
    }

    /** The WARNING records seen since the ring was installed, and those between WARNING and SEVERE. */
    long warnings() {
        return warnings.sum();
    }

    /** The SEVERE records seen since the ring was installed, and those above. */
    long errors() {
        return errors.sum();
    }

    /**
     * The name of {@code logger} as the table shows it: every package segment cut to its first letter, the last
     * segment whole, such as {@code i.v.c.CassiniExtension} for {@code io.vidocq.cassini.CassiniExtension};
     * {@code (root)} for the root logger.
     */
    static String shortName(String logger) {
        if (logger == null || logger.isEmpty()) {
            return "(root)";
        }
        int last = logger.lastIndexOf('.');
        if (last < 0) {
            return logger;
        }
        StringBuilder shortened = new StringBuilder();
        int start = 0;
        while (start < last) {
            int dot = logger.indexOf('.', start);
            if (dot > start) {
                shortened.append(logger.charAt(start)).append('.');
            }
            start = dot + 1;
        }
        return shortened.append(logger, last + 1, logger.length()).toString();
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        // nothing to release: the lines go with the ring
    }
}
