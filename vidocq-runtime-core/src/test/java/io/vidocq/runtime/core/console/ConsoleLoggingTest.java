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

import io.vidocq.runtime.core.config.VidocqConfigImpl;
import io.vidocq.runtime.core.console.ConsoleLogging.Mode;
import io.vidocq.runtime.core.console.ConsoleSupport.ColorMode;
import io.vidocq.runtime.spi.config.ConfigSource;
import io.vidocq.runtime.spi.config.VidocqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.XMLFormatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The installer changes JVM-wide state (the root logger's handlers): every test starts from a
 * fresh JDK default console handler and puts the original handlers, level and system properties
 * back afterwards, because the test classes share one JVM.
 */
class ConsoleLoggingTest {

    private static final List<String> TOUCHED_PROPERTIES = List.of(
            ConsoleLogging.LOG_CONSOLE_KEY, ConsoleSupport.COLOR_KEY,
            "java.util.logging.config.file", "java.util.logging.config.class",
            "java.util.logging.manager", "java.util.logging.SimpleFormatter.format");

    private final Map<String, String> savedProperties = new HashMap<>();
    private Handler[] savedHandlers;
    private Level savedLevel;
    private ConsoleHandler jdkDefault;

    private static Logger root() {
        return LogManager.getLogManager().getLogger("");
    }

    @BeforeEach
    void startFromTheJdkDefault() {
        for (String key : TOUCHED_PROPERTIES) {
            savedProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        Logger root = root();
        savedHandlers = root.getHandlers();
        savedLevel = root.getLevel();
        for (Handler h : savedHandlers) {
            root.removeHandler(h);
        }
        jdkDefault = new ConsoleHandler();
        root.addHandler(jdkDefault);
        ConsoleLogging.forget();
    }

    @AfterEach
    void restoreTheGlobalLoggingState() {
        Logger root = root();
        for (Handler h : root.getHandlers()) {
            root.removeHandler(h);
            h.flush();
        }
        for (Handler h : savedHandlers) {
            root.addHandler(h);
        }
        root.setLevel(savedLevel);
        savedProperties.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
        ConsoleLogging.forget();
    }

    @Test
    void replacesTheJdkDefaultWithOneStdoutHandler() {
        jdkDefault.setLevel(Level.FINE);

        assertTrue(ConsoleLogging.installIfDefault());

        Handler[] handlers = root().getHandlers();
        assertEquals(1, handlers.length);
        ConsoleLogHandler handler = assertInstanceOf(ConsoleLogHandler.class, handlers[0]);
        assertEquals(Level.FINE, handler.getLevel(), "the level of the replaced handler is kept");
        assertInstanceOf(ConsoleLogFormatter.class, handler.getFormatter());
        assertTrue(ConsoleLogging.installed());
    }

    @Test
    void installingTwiceChangesNothing() {
        assertTrue(ConsoleLogging.installIfDefault());
        Handler[] first = root().getHandlers();

        assertTrue(ConsoleLogging.installIfDefault());

        assertArrayEquals(first, root().getHandlers());
    }

    @Test
    void recordsGoToStandardOutput() {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            assertTrue(ConsoleLogging.installIfDefault());
            System.getLogger("io.vidocq.runtime.core.console.Sample")
                    .log(System.Logger.Level.WARNING, "on stdout, {0}", "formatted");
        } finally {
            System.setOut(original);
        }

        String out = captured.toString(StandardCharsets.UTF_8);
        assertTrue(out.startsWith("[WARN ]["), out);
        assertTrue(out.endsWith("] : on stdout, formatted" + System.lineSeparator()), out);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "java.util.logging.config.file", "java.util.logging.config.class",
            "java.util.logging.manager", "java.util.logging.SimpleFormatter.format"})
    void anApplicationLoggingPropertyKeepsTheJdkDefault(String property) {
        System.setProperty(property, "set-by-the-application");

        assertFalse(ConsoleLogging.installIfDefault());

        assertArrayEquals(new Handler[] {jdkDefault}, root().getHandlers());
    }

    @Test
    void vidocqLogConsoleJdkKeepsTheJdkDefault() {
        System.setProperty(ConsoleLogging.LOG_CONSOLE_KEY, "jdk");

        assertFalse(ConsoleLogging.installIfDefault());

        assertArrayEquals(new Handler[] {jdkDefault}, root().getHandlers());
    }

    @Test
    void anotherRootHandlerKeepsTheJdkDefault() {
        Handler bridge = new Handler() {
            @Override public void publish(LogRecord record) {}
            @Override public void flush() {}
            @Override public void close() {}
        };
        root().addHandler(bridge);

        assertFalse(ConsoleLogging.installIfDefault());

        assertEquals(Set.of(jdkDefault, bridge), Set.of(root().getHandlers()));
    }

    @Test
    void anotherFormatterKeepsTheJdkDefault() {
        jdkDefault.setFormatter(new XMLFormatter());

        assertFalse(ConsoleLogging.installIfDefault());

        assertArrayEquals(new Handler[] {jdkDefault}, root().getHandlers());
    }

    @Test
    void aConsoleHandlerSubclassKeepsTheJdkDefault() {
        root().removeHandler(jdkDefault);
        ConsoleHandler custom = new ConsoleHandler() {};
        root().addHandler(custom);

        assertFalse(ConsoleLogging.installIfDefault());

        assertArrayEquals(new Handler[] {custom}, root().getHandlers());
    }

    @Test
    void noRootHandlerKeepsTheJdkDefault() {
        root().removeHandler(jdkDefault);

        assertFalse(ConsoleLogging.installIfDefault());

        assertEquals(0, root().getHandlers().length);
    }

    @Test
    void theApplicationDecidesTable() {
        Handler[] jdk = {new ConsoleHandler()};
        String finder = ConsoleLogging.JDK_LOGGER_FINDER;

        assertEquals(Optional.empty(), ConsoleLogging.reasonToKeepJdkDefault(Mode.AUTO, key -> null, finder, jdk));
        assertTrue(ConsoleLogging.reasonToKeepJdkDefault(Mode.JDK, key -> null, finder, jdk).isPresent());
        assertTrue(ConsoleLogging.reasonToKeepJdkDefault(Mode.AUTO,
                key -> key.equals("java.util.logging.config.file") ? "logging.properties" : null, finder, jdk)
                .isPresent());
        assertEquals(Optional.of("System.LoggerFinder is org.slf4j.jdk.platform.logging.SLF4JSystemLoggerFinder"),
                ConsoleLogging.reasonToKeepJdkDefault(Mode.AUTO, key -> null,
                        "org.slf4j.jdk.platform.logging.SLF4JSystemLoggerFinder", jdk));
        assertTrue(ConsoleLogging.reasonToKeepJdkDefault(Mode.AUTO, key -> null, finder, new Handler[0]).isPresent());
    }

    @Test
    void theHandlerNeverClosesItsStream() {
        TrackingStream stream = new TrackingStream();
        ConsoleLogHandler handler = new ConsoleLogHandler(stream, "UTF-8", new ConsoleLogFormatter(false));

        handler.publish(new LogRecord(Level.INFO, "before close"));
        handler.close();
        handler.publish(new LogRecord(Level.INFO, "after close"));

        assertFalse(stream.closed);
        String written = stream.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("] : before close"), written);
        assertTrue(written.contains("] : after close"), "flushed per record, still usable: " + written);
    }

    @Test
    void jdkFromConfigurationPutsTheJdkHandlerBack() {
        assertTrue(ConsoleLogging.installIfDefault());

        ConsoleLogging.applyConfiguration(config(Map.of(ConsoleLogging.LOG_CONSOLE_KEY, "jdk")));

        assertArrayEquals(new Handler[] {jdkDefault}, root().getHandlers());
        assertFalse(ConsoleLogging.installed());
        // a dev reload boots again in this JVM: the configured choice holds
        assertFalse(ConsoleLogging.installIfDefault());
        assertArrayEquals(new Handler[] {jdkDefault}, root().getHandlers());
    }

    @Test
    void autoFromConfigurationInstallsAgainAfterJdk() {
        assertTrue(ConsoleLogging.installIfDefault());
        ConsoleLogging.applyConfiguration(config(Map.of(ConsoleLogging.LOG_CONSOLE_KEY, "jdk")));

        ConsoleLogging.applyConfiguration(config(Map.of(ConsoleLogging.LOG_CONSOLE_KEY, "auto")));

        assertInstanceOf(ConsoleLogHandler.class, root().getHandlers()[0]);
        assertTrue(ConsoleLogging.installed());
    }

    @Test
    void colourPolicyFromConfiguration() {
        assertTrue(ConsoleLogging.installIfDefault());
        ConsoleSupport console = ConsoleSupport.current();

        ConsoleLogging.applyConfiguration(config(Map.of(ConsoleSupport.COLOR_KEY, "always")));
        assertEquals(console.colors(ColorMode.ALWAYS), formatter().colors());

        ConsoleLogging.applyConfiguration(config(Map.of(ConsoleSupport.COLOR_KEY, "never")));
        assertFalse(formatter().colors());
    }

    @Test
    void anUnknownValueFallsBackToAuto() {
        assertTrue(ConsoleLogging.installIfDefault());

        ConsoleLogging.applyConfiguration(config(Map.of(ConsoleLogging.LOG_CONSOLE_KEY, "slf4j")));

        assertTrue(ConsoleLogging.installed());
    }

    @Test
    void configurationThatFailsNeverFailsTheBoot() {
        VidocqConfig broken = new VidocqConfigImpl(List.of(new MapSource(Map.of()) {
            @Override
            public String getValue(String key) {
                throw new IllegalStateException("broken source");
            }
        }));

        assertDoesNotThrow(() -> ConsoleLogging.applyConfiguration(broken));
    }

    private static ConsoleLogFormatter formatter() {
        return assertInstanceOf(ConsoleLogFormatter.class, root().getHandlers()[0].getFormatter());
    }

    private static VidocqConfig config(Map<String, String> values) {
        return new VidocqConfigImpl(List.of(new MapSource(values)));
    }

    private static class MapSource implements ConfigSource {
        private final Map<String, String> values;

        MapSource(Map<String, String> values) {
            this.values = values;
        }

        @Override public String getName() { return "test"; }
        @Override public int getOrdinal() { return 100; }
        @Override public String getValue(String key) { return values.get(key); }
        @Override public Set<String> getPropertyNames() { return values.keySet(); }
    }

    private static final class TrackingStream extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean closed;

        @Override public void write(int b) { bytes.write(b); }
        @Override public void write(byte[] b, int off, int len) { bytes.write(b, off, len); }
        @Override public void close() { closed = true; }

        String toString(java.nio.charset.Charset charset) {
            return bytes.toString(charset);
        }
    }
}
