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

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ring of the {@code logs} panel: bounded, newest first, strings only, counters over every record it saw.
 */
class LogRingTest {

    private static LogRecord record(Level level, String logger, String message, Object... parameters) {
        LogRecord record = new LogRecord(level, message);
        record.setLoggerName(logger);
        record.setParameters(parameters.length == 0 ? null : parameters);
        return record;
    }

    /**
     * The race a build machine hit once: two publishers {@value LogRing#CAPACITY} records apart share a slot, and the
     * older one stores last. The slot must keep the newer line, or the reader skips it as stale and a line is lost.
     */
    @Test
    void anOlderLineStoredLastNeverReplacesTheNewerOneOfItsSlot() {
        LogRing ring = new LogRing();
        for (int i = 0; i <= LogRing.CAPACITY; i++) {
            ring.publish(record(Level.INFO, "io.vidocq.test", "record " + i));
        }
        // record 0 and record CAPACITY share slot 0; replay the older one arriving late.
        ring.store(new LogRing.Line(0, "00:00:00.000", "INFO", "io.vidocq.test", "late", "record 0"));

        List<LogRing.Line> all = ring.latest(Integer.MAX_VALUE);
        assertEquals(LogRing.CAPACITY, all.size(), "no line lost to the late store");
        assertEquals("record " + LogRing.CAPACITY, all.getFirst().message());
    }

    @Test
    void itKeepsTheLastRecordsNewestFirst() {
        LogRing ring = new LogRing();
        for (int i = 0; i < LogRing.CAPACITY + 20; i++) {
            ring.publish(record(Level.INFO, "io.vidocq.test", "record " + i));
        }

        List<LogRing.Line> all = ring.latest(Integer.MAX_VALUE);
        assertEquals(LogRing.CAPACITY, all.size(), "bounded");
        assertEquals("record " + (LogRing.CAPACITY + 19), all.getFirst().message(), "newest first");
        assertEquals("record 20", all.getLast().message(), "the oldest kept: the first 20 are gone");
        assertEquals(List.of("record 519", "record 518", "record 517"),
                ring.latest(3).stream().map(LogRing.Line::message).toList());
        assertEquals(LogRing.CAPACITY + 20, ring.seen());
    }

    @Test
    void anEmptyRingHasNoLine() {
        assertEquals(List.of(), new LogRing().latest(100));
    }

    @Test
    void theCountersCountEveryWarningAndErrorSinceItWasInstalledNotOnlyThoseKept() {
        LogRing ring = new LogRing();
        for (int i = 0; i < 600; i++) {
            ring.publish(record(Level.WARNING, "a", "w"));
        }
        ring.publish(record(Level.SEVERE, "a", "e"));
        ring.publish(record(Level.INFO, "a", "i"));
        ring.publish(record(Level.FINE, "a", "f"));

        assertEquals(600, ring.warnings());
        assertEquals(1, ring.errors());
    }

    @Test
    void aLineIsWrittenOnceInStrings() {
        LogRing ring = new LogRing();
        ring.publish(record(Level.WARNING, "io.vidocq.cassini.CassiniExtension", "{0} routes on {1}", 3, "default"));

        LogRing.Line line = ring.latest(1).getFirst();
        assertEquals("WARNING", line.level());
        assertEquals("io.vidocq.cassini.CassiniExtension", line.logger());
        assertEquals("3 routes on default", line.message(), "formatted as Formatter.formatMessage does");
        assertEquals(Thread.currentThread().getName(), line.thread());
        assertTrue(line.time().matches("[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}"), line.time());
    }

    @Test
    void theRingHoldsNoRecordNoParameterAndNoThrowable() {
        // a line is strings and its rank: nothing that could be an object of the application
        Set<Class<?>> allowed = Set.of(String.class, long.class);
        for (RecordComponent component : LogRing.Line.class.getRecordComponents()) {
            assertTrue(allowed.contains(component.getType()), component + " is no string");
        }
        // and the ring itself keeps lines, counters and its formatter, nothing of a record
        for (Field field : LogRing.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertFalse(Arrays.asList(LogRecord.class, Object[].class, Throwable.class).contains(field.getType()),
                    field.toString());
        }
    }

    @Test
    void aThrowableAddsItsClassAndTheFirstLineOfItsMessageNeverItsStack() {
        LogRing ring = new LogRing();
        LogRecord record = record(Level.SEVERE, "a", "Request failed");
        record.setThrown(new IllegalStateException("first line\nsecond line\n\tat com.acme.Secret.run"));
        ring.publish(record);

        assertEquals("Request failed - java.lang.IllegalStateException: first line",
                ring.latest(1).getFirst().message());
    }

    @Test
    void theCredentialsOfAUrlAreRemovedAsTheConfigPanelRemovesThem() {
        LogRing ring = new LogRing();
        ring.publish(record(Level.INFO, "a", "Connecting to jdbc:postgresql://admin:s3cr3t@db/orders?password=pw2"));

        String message = ring.latest(1).getFirst().message();
        assertEquals("Connecting to jdbc:postgresql://***@db/orders?password=***", message);
    }

    @Test
    void aLongMessageIsCut() {
        LogRing ring = new LogRing();
        ring.publish(record(Level.INFO, "a", "x".repeat(10_000)));

        assertEquals(LogRing.MAX_MESSAGE, ring.latest(1).getFirst().message().length());
    }

    @Test
    void aParameterWhoseToStringFailsCostsTheFormattingNotTheRecord() {
        LogRing ring = new LogRing();
        Object failing = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("no");
            }
        };
        ring.publish(record(Level.INFO, "a", "value {0}", failing));

        assertEquals("value {0}", ring.latest(1).getFirst().message());
    }

    @Test
    void concurrentPublishersNeverShowALineTwiceNorOutOfOrder() throws Exception {
        LogRing ring = new LogRing();
        int threads = 8;
        int each = 2_000;
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Thread> publishers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread publisher = Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException interrupted) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    ring.publish(record(Level.INFO, "a", "m"));
                    if (i % 100 == 0) {
                        try {
                            assertOrdered(ring.latest(LogRing.CAPACITY));
                        } catch (AssertionError failed) {
                            failures.add(failed);
                        }
                    }
                }
            });
            publishers.add(publisher);
        }
        go.countDown();
        for (Thread publisher : publishers) {
            publisher.join();
        }

        assertEquals(List.of(), failures, "a reader while they publish");
        assertEquals((long) threads * each, ring.seen());
        List<LogRing.Line> lines = ring.latest(LogRing.CAPACITY);
        assertEquals(LogRing.CAPACITY, lines.size());
        assertOrdered(lines);
    }

    private static void assertOrdered(List<LogRing.Line> lines) {
        for (int i = 1; i < lines.size(); i++) {
            assertTrue(lines.get(i - 1).sequence() > lines.get(i).sequence(), "newest first, each once");
        }
    }

    @Test
    void aLoggerNameIsShortenedToItsPackageInitialsAndItsClass() {
        assertEquals("i.v.c.CassiniExtension", LogRing.shortName("io.vidocq.cassini.CassiniExtension"));
        assertEquals("i.v.devconsole", LogRing.shortName("io.vidocq.devconsole"));
        assertEquals("orders", LogRing.shortName("orders"));
        assertEquals("(root)", LogRing.shortName(""));
        assertEquals("(root)", LogRing.shortName(null));
    }
}
