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
            } catch (UnsupportedEncodingException | RuntimeException unsupported) {
                // keep the platform default
            }
        }
    }

    @Override
    public synchronized void publish(LogRecord record) {
        super.publish(record);
        flush();
    }

    /** Flushes, and never closes the underlying stream. */
    @Override
    public synchronized void close() {
        flush();
    }
}
