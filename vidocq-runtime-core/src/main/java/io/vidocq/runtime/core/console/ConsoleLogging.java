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

import io.vidocq.runtime.core.console.ConsoleSupport.ColorMode;
import io.vidocq.runtime.spi.config.VidocqConfig;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Replaces the JDK's default console output (stderr, two lines per record) with one aligned line
 * per record on stdout, <strong>only when the application has not chosen anything</strong>.
 *
 * <p>The JDK default stays untouched when any of these holds:
 * <ul>
 *   <li>{@code java.util.logging.config.file}, {@code java.util.logging.config.class},
 *       {@code java.util.logging.manager} or {@code java.util.logging.SimpleFormatter.format} is
 *       set;</li>
 *   <li>{@code System.LoggerFinder} is not the JDK's JUL-backed one (an SLF4J or Log4j bridge,
 *       for example);</li>
 *   <li>the root logger's handlers are not exactly one {@link ConsoleHandler} with a
 *       {@link SimpleFormatter} (after {@code SLF4JBridgeHandler.install()} or
 *       {@code LogManager.readConfiguration}, for example);</li>
 *   <li>{@value #LOG_CONSOLE_KEY}{@code =jdk}.</li>
 * </ul>
 *
 * <p>{@link #installIfDefault()} runs first thing in {@code Vidocq.main}, {@code Vidocq.run} and
 * {@code VidocqBootstrap.create()}, when only {@code -D} and the environment are visible.
 * {@link #applyConfiguration(VidocqConfig)} runs once the configuration exists and applies
 * {@value #LOG_CONSOLE_KEY} and {@value ConsoleSupport#COLOR_KEY} from it. Both are idempotent,
 * thread-safe and never throw: console output is never a reason to fail a boot.
 */
public final class ConsoleLogging {

    /** Configuration key: {@code auto} (the default) or {@code jdk} to keep the JDK console output. */
    public static final String LOG_CONSOLE_KEY = "vidocq.log.console";

    /** The system properties through which an application configures {@code java.util.logging}. */
    static final List<String> APPLICATION_LOGGING_PROPERTIES = List.of(
            "java.util.logging.config.file",
            "java.util.logging.config.class",
            "java.util.logging.manager",
            "java.util.logging.SimpleFormatter.format");

    /** The JDK's {@code System.LoggerFinder} when {@code System.Logger} goes to {@code java.util.logging}. */
    static final String JDK_LOGGER_FINDER = "sun.util.logging.internal.LoggingProviderImpl";

    private static final System.Logger LOG = System.getLogger(ConsoleLogging.class.getName());

    /** The values of {@value #LOG_CONSOLE_KEY}. */
    public enum Mode {
        /** Vidocq's console output, unless the application configured logging itself. */
        AUTO,
        /** The JDK's console output, untouched. */
        JDK;

        /**
         * Parses a configured value, case-insensitively and ignoring surrounding blanks.
         *
         * @return the mode, or empty when the value is {@code null}, blank or unknown
         */
        public static Optional<Mode> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "jdk" -> Optional.of(JDK);
                default -> Optional.empty();
            };
        }
    }

    /** Vidocq's handler while it is installed. */
    private static ConsoleLogHandler handler;
    /** The JDK console handler it replaced, put back by {@code vidocq.log.console=jdk}. */
    private static Handler replaced;
    /** The configuration chose {@code vidocq.log.console=jdk}: later boots in this JVM keep the JDK output. */
    private static boolean configuredJdk;

    private ConsoleLogging() {}

    /**
     * Installs Vidocq's console handler over the JDK default, unless the application decides.
     *
     * @return whether Vidocq's handler is installed on return
     */
    public static synchronized boolean installIfDefault() {
        try {
            Mode mode = ConsoleSupport.earlySetting(LOG_CONSOLE_KEY).flatMap(Mode::parse).orElse(Mode.AUTO);
            return install(mode, ConsoleSupport.earlyColorMode());
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Applies {@value #LOG_CONSOLE_KEY} and {@value ConsoleSupport#COLOR_KEY} from the loaded
     * configuration: {@code jdk} puts the JDK console handler back when Vidocq's is installed;
     * otherwise the colours follow the configured policy.
     */
    public static synchronized void applyConfiguration(VidocqConfig config) {
        try {
            Mode mode = setting(config, LOG_CONSOLE_KEY, Mode::parse, Mode.AUTO, "auto or jdk");
            ColorMode colorMode = setting(config, ConsoleSupport.COLOR_KEY, ColorMode::parse, ColorMode.AUTO,
                    "auto, always or never");
            if (mode == Mode.JDK) {
                configuredJdk = true;
                restoreJdkHandler();
                return;
            }
            configuredJdk = false;
            if (install(mode, colorMode)) {
                boolean colors = ConsoleSupport.current().colors(colorMode);
                if (!(handler.getFormatter() instanceof ConsoleLogFormatter formatter)
                        || formatter.colors() != colors) {
                    handler.setFormatter(new ConsoleLogFormatter(colors));
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // console output is never a reason to fail the boot
        }
    }

    /** Whether Vidocq's console handler is installed on the root logger. */
    public static synchronized boolean installed() {
        return handler != null && isRootHandler(handler);
    }

    /**
     * Why the JDK default console output must stay, or empty when Vidocq may replace it.
     *
     * @param mode              the {@value #LOG_CONSOLE_KEY} setting
     * @param systemProperties  system property lookup
     * @param loggerFinderClass the class name of {@code System.LoggerFinder.getLoggerFinder()}
     * @param rootHandlers      the root logger's handlers
     */
    static Optional<String> reasonToKeepJdkDefault(Mode mode, UnaryOperator<String> systemProperties,
                                                   String loggerFinderClass, Handler[] rootHandlers) {
        if (mode == Mode.JDK) {
            return Optional.of(LOG_CONSOLE_KEY + "=jdk");
        }
        for (String property : APPLICATION_LOGGING_PROPERTIES) {
            if (systemProperties.apply(property) != null) {
                return Optional.of(property + " is set");
            }
        }
        if (!JDK_LOGGER_FINDER.equals(loggerFinderClass)) {
            return Optional.of("System.LoggerFinder is " + loggerFinderClass);
        }
        if (rootHandlers.length != 1
                || rootHandlers[0].getClass() != ConsoleHandler.class
                || rootHandlers[0].getFormatter() == null
                || rootHandlers[0].getFormatter().getClass() != SimpleFormatter.class) {
            return Optional.of("the root logger's handlers are not the JDK default");
        }
        return Optional.empty();
    }

    /** Forgets what this class installed, for tests that restore the root handlers themselves. */
    static synchronized void forget() {
        handler = null;
        replaced = null;
        configuredJdk = false;
    }

    /** Installs Vidocq's handler when allowed; a no-op returning {@code true} when it is already there. */
    private static boolean install(Mode mode, ColorMode colorMode) {
        Logger root = LogManager.getLogManager().getLogger("");
        if (handler != null && isRootHandler(handler)) {
            return true;
        }
        handler = null;
        replaced = null;
        if (configuredJdk) {
            return false;
        }
        Handler[] rootHandlers = root.getHandlers();
        String finder = System.LoggerFinder.getLoggerFinder().getClass().getName();
        if (reasonToKeepJdkDefault(mode, System::getProperty, finder, rootHandlers).isPresent()) {
            return false;
        }
        Handler jdkConsole = rootHandlers[0];
        ConsoleLogHandler vidocq = ConsoleLogHandler.stdout(
                new ConsoleLogFormatter(ConsoleSupport.current().colors(colorMode)));
        vidocq.setLevel(jdkConsole.getLevel());
        root.removeHandler(jdkConsole);
        root.addHandler(vidocq);
        handler = vidocq;
        replaced = jdkConsole;
        return true;
    }

    private static void restoreJdkHandler() {
        if (handler == null) {
            return;
        }
        if (isRootHandler(handler)) {
            Handler jdkConsole = replaced;
            if (jdkConsole == null) {
                jdkConsole = new ConsoleHandler();
                jdkConsole.setLevel(handler.getLevel());
            }
            Logger root = LogManager.getLogManager().getLogger("");
            handler.flush();
            root.removeHandler(handler);
            root.addHandler(jdkConsole);
        }
        handler = null;
        replaced = null;
    }

    private static boolean isRootHandler(Handler candidate) {
        for (Handler h : LogManager.getLogManager().getLogger("").getHandlers()) {
            if (h == candidate) {
                return true;
            }
        }
        return false;
    }

    private static <T> T setting(VidocqConfig config, String key, Function<String, Optional<T>> parser,
                                 T defaultValue, String expected) {
        Optional<String> raw = config.getValue(key).filter(v -> !v.isBlank());
        if (raw.isEmpty()) {
            return defaultValue;
        }
        Optional<T> parsed = parser.apply(raw.get());
        if (parsed.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "Configuration key ''{0}'' has an unknown value ''{1}''"
                    + " (expected {2}); using {3}", key, raw.get(), expected,
                    defaultValue.toString().toLowerCase(Locale.ROOT));
            return defaultValue;
        }
        return parsed.get();
    }
}
