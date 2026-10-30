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
package io.vidocq.runtime.maven.dev;

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dev services hosted by a goal log through the build's log: one {@code [INFO]} line like the others, never the
 * JDK's two-line format in the machine's language (BUG-20261030-07).
 */
class MavenSystemLoggerTest {

    private static final class CapturingLog extends SystemStreamLog {
        final List<String> lines = new ArrayList<>();
        boolean debug;

        @Override public boolean isDebugEnabled() { return debug; }
        @Override public void debug(CharSequence content) { lines.add("debug " + content); }
        @Override public void info(CharSequence content) { lines.add("info " + content); }
        @Override public void warn(CharSequence content) { lines.add("warn " + content); }
        @Override public void warn(CharSequence content, Throwable error) { lines.add("warn " + content + " | " + error.getMessage()); }
        @Override public void error(CharSequence content) { lines.add("error " + content); }
        @Override public void error(CharSequence content, Throwable error) { lines.add("error " + content + " | " + error.getMessage()); }
    }

    @Test
    void eachLevelGoesToItsMavenLevelOnOneLine() {
        CapturingLog log = new CapturingLog();
        System.Logger logger = new MavenSystemLogger(log);

        logger.log(System.Logger.Level.INFO, "Postgres dev service 'default' ready");
        logger.log(System.Logger.Level.WARNING, "slow");
        logger.log(System.Logger.Level.ERROR, "down", new IllegalStateException("boom"));
        logger.log(System.Logger.Level.DEBUG, "hidden");

        assertEquals(List.of("info Postgres dev service 'default' ready", "warn slow", "error down | boom"), log.lines);
    }

    @Test
    void parametersAreFormattedTheSameWhateverTheLocale() {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.FRANCE);
        try {
            CapturingLog log = new CapturingLog();
            new MavenSystemLogger(log).log(System.Logger.Level.INFO, "{0} ready in {1} ms", "default", 12345);

            assertEquals(List.of("info default ready in 12,345 ms"), log.lines);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void debugAndTraceFollowMavensDebugSwitch() {
        CapturingLog log = new CapturingLog();
        MavenSystemLogger logger = new MavenSystemLogger(log);

        assertFalse(logger.isLoggable(System.Logger.Level.DEBUG));
        assertTrue(logger.isLoggable(System.Logger.Level.INFO));
        log.debug = true;
        logger.log(System.Logger.Level.TRACE, "trace");
        assertEquals(List.of("debug trace"), log.lines);
    }
}
