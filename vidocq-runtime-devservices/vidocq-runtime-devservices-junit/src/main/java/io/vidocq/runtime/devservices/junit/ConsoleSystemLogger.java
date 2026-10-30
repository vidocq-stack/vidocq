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
package io.vidocq.runtime.devservices.junit;

import java.io.PrintStream;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * A {@link System.Logger} for the dev services of a {@code mvn test} run: one line in Maven's format ({@code [INFO]},
 * {@code [WARNING]}, {@code [ERROR]}), its parameters formatted the same in every locale, as the goals log them
 * (BUG-20261030-07). Through the JDK's default logger they came out in two lines, the date and the level in the
 * machine's language (BUG-20261030-21). Debug messages are dropped.
 */
final class ConsoleSystemLogger implements System.Logger {

    private final PrintStream out;

    ConsoleSystemLogger(PrintStream out) {
        this.out = out;
    }

    @Override
    public String getName() {
        return "vidocq.test.devservices";
    }

    @Override
    public boolean isLoggable(Level level) {
        return level.getSeverity() >= Level.INFO.getSeverity() && level != Level.OFF;
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
        write(level, msg, thrown);
    }

    @Override
    public void log(Level level, ResourceBundle bundle, String format, Object... params) {
        write(level, params == null || params.length == 0 ? format
                : new MessageFormat(format, Locale.ROOT).format(params), null);
    }

    private void write(Level level, String message, Throwable thrown) {
        if (!isLoggable(level)) {
            return;
        }
        String tag = switch (level) {
            case ERROR -> "[ERROR] ";
            case WARNING -> "[WARNING] ";
            default -> "[INFO] ";
        };
        synchronized (out) {
            out.println(tag + message);
            if (thrown != null) {
                thrown.printStackTrace(out);
            }
        }
    }
}
