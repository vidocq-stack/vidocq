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
package io.vidocq.runtime.it.ibarrapages;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The port the application's default listener bound, read from the line Chappe logs once it listens. The tests boot
 * the application on port {@code 0}, so the system picks the port as the listener binds it: a port picked beforehand,
 * then closed and bound by the listener, could be taken in between by another process (BUG-20261114-09).
 */
final class BoundPort implements AutoCloseable {

    private static final String CHAPPE_LOGGER = "io.vidocq.runtime.extensions.essentials.chappe";
    private static final Pattern STARTED = Pattern.compile("Chappe listener 'default' started on http://[^/]+:(\\d+)/");

    /** Kept strongly: JUL holds its loggers weakly, and the handler would go with a collected one. */
    private final Logger logger = Logger.getLogger(CHAPPE_LOGGER);
    private final List<String> messages = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler() {
        private final SimpleFormatter formatter = new SimpleFormatter();

        @Override
        public void publish(LogRecord logRecord) {
            messages.add(formatter.formatMessage(logRecord));
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

    private BoundPort() {
        logger.addHandler(handler);
    }

    /** Listens to Chappe's log: call it before the boot. */
    static BoundPort watch() {
        return new BoundPort();
    }

    /** The port of the default listener's last start line. */
    int port() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Matcher started = STARTED.matcher(messages.get(i));
            if (started.find()) {
                return Integer.parseInt(started.group(1));
            }
        }
        throw new AssertionError("no start line of the default listener on " + CHAPPE_LOGGER + ": " + messages);
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
    }
}
