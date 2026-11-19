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

import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.logging.Formatter;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;

/**
 * Writes every record, whatever its level, to standard output, flushed after each record.
 *
 * <p>One stream keeps the banner and the logs in order, and an IDE no longer shows a healthy run
 * in red. The handler publishes synchronously on the logging thread, which is what lets the
 * formatter read the thread name at format time. {@link #close()} only flushes: the handler does
 * not own {@code System.out}, and {@code LogManager.reset()} closes every root handler at
 * shutdown.
 *
 * <p>The JDK's own shutdown hook, {@code LogManager}'s {@value #JDK_LOGGING_SHUTDOWN_HOOK}, resets
 * every logger while the other hooks run, Vidocq's among them: without a handler on the root, the
 * stop of the application logged nothing, or only its first lines (BUG-20261115-07). Closed by that
 * hook, the handler puts itself back on the root logger, so the console keeps the lines until the
 * JVM ends.</p>
 */
public final class ConsoleLogHandler extends StreamHandler {

    /** A handler on {@code System.out}, encoding with {@code stdout.encoding}. */
    public static ConsoleLogHandler stdout(Formatter formatter) {
        String encoding = System.getProperty("stdout.encoding");
        return new ConsoleLogHandler(System.out,
                encoding == null || encoding.isBlank() ? System.out.charset().name() : encoding, formatter);
    }

    ConsoleLogHandler(OutputStream out, String encoding, Formatter formatter) {
        super(out, formatter);
        if (encoding != null) {
            try {
                setEncoding(encoding);
            } catch (UnsupportedEncodingException | RuntimeException _) {
                // keep the platform default
            }
        }
    }

    @Override
    public synchronized void publish(LogRecord entry) {
        super.publish(entry);
        flush();
    }

    /** The name of the thread {@code LogManager} registers as a shutdown hook to reset the loggers. */
    static final String JDK_LOGGING_SHUTDOWN_HOOK = "Logging-Cleaner";

    /**
     * Flushes, and never closes the underlying stream. Closed by the JDK's logging shutdown hook,
     * goes back on the root logger.
     */
    @Override
    public synchronized void close() {
        flush();
        if (JDK_LOGGING_SHUTDOWN_HOOK.equals(Thread.currentThread().getName())) {
            LogManager.getLogManager().getLogger("").addHandler(this);
        }
    }
}
