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
package io.vidocq.runtime.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** The records a logger and its children publish while this is open; the root logger's for {@code ""}. */
final class LogRecords implements AutoCloseable {

    private final Logger logger;
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            synchronized (records) {
                records.add(record);
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing to release
        }
    };

    LogRecords(String loggerName) {
        logger = Logger.getLogger(loggerName);
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
    }

    /** Every record, in order. */
    List<LogRecord> all() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /** The formatted messages of the records of exactly {@code loggerName} at {@code level}. */
    List<String> messages(String loggerName, Level level) {
        SimpleFormatter formatter = new SimpleFormatter();
        return all().stream()
                .filter(r -> loggerName.equals(r.getLoggerName()) && r.getLevel() == level)
                .map(formatter::formatMessage)
                .toList();
    }

    /** The formatted message of one record. */
    static String message(LogRecord record) {
        return new SimpleFormatter().formatMessage(record);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
