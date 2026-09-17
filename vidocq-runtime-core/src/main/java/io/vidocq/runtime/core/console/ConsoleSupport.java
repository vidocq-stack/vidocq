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

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * What Vidocq knows about the console it writes to, and whether it may colour that output.
 *
 * <p>Shared by everything Vidocq prints on the console (the log handler, the startup banner), so
 * that one policy decides colours everywhere:
 * <ul>
 *   <li>a non-empty {@code NO_COLOR} environment variable always wins: no colour;</li>
 *   <li>{@code vidocq.console.color=always} colours, {@code never} does not;</li>
 *   <li>{@code auto} (the default) colours when {@code TERM} is not {@code dumb} and either the
 *       JVM runs in IntelliJ's Run console (not a TTY, but it decodes ANSI escapes) or on a
 *       terminal on an operating system other than Windows.</li>
 * </ul>
 *
 * <p>The record components are the raw inputs of that decision: {@link #current()} reads them from
 * this JVM, tests build them directly.
 *
 * @param noColor      the {@code NO_COLOR} environment variable, or {@code null} when unset
 * @param term         the {@code TERM} environment variable, or {@code null} when unset
 * @param osName       the {@code os.name} system property, or {@code null}
 * @param terminal     whether {@code System.console()} is non-null <em>and</em>
 *                     {@code isTerminal()}; on JDK 22 and later a console can exist while the
 *                     streams are not a terminal, so both are required
 * @param jvmArguments the JVM input arguments ({@code RuntimeMXBean#getInputArguments()})
 */
public record ConsoleSupport(String noColor, String term, String osName, boolean terminal,
                             List<String> jvmArguments) {

    /** Configuration key of the colour policy: {@code auto}, {@code always} or {@code never}. */
    public static final String COLOR_KEY = "vidocq.console.color";

    /** The values of {@value #COLOR_KEY}. */
    public enum ColorMode {
        /** Colours on a terminal (not Windows) or in IntelliJ's Run console, unless {@code TERM=dumb}. */
        AUTO,
        /** Always colours (still overridden by a non-empty {@code NO_COLOR}). */
        ALWAYS,
        /** Never colours. */
        NEVER;

        /**
         * Parses a configured value, case-insensitively and ignoring surrounding blanks.
         *
         * @return the mode, or empty when the value is {@code null}, blank or unknown
         */
        public static Optional<ColorMode> parse(String value) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return switch (value.strip().toLowerCase(Locale.ROOT)) {
                case "auto" -> Optional.of(AUTO);
                case "always" -> Optional.of(ALWAYS);
                case "never" -> Optional.of(NEVER);
                default -> Optional.empty();
            };
        }
    }

    public ConsoleSupport {
        jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
    }

    /** Reads the inputs of this JVM: environment, {@code os.name}, console and JVM arguments. */
    public static ConsoleSupport current() {
        var console = System.console();
        List<String> arguments;
        try {
            arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError unavailable) {
            arguments = List.of();
        }
        return new ConsoleSupport(System.getenv("NO_COLOR"), System.getenv("TERM"),
                System.getProperty("os.name"), console != null && console.isTerminal(), arguments);
    }

    /** Whether {@code NO_COLOR} is set to a non-empty value, which disables colours whatever the mode. */
    public boolean noColorRequested() {
        return noColor != null && !noColor.isEmpty();
    }

    /** Whether {@code TERM=dumb}. */
    public boolean dumbTerminal() {
        return "dumb".equals(term);
    }

    /** Whether the operating system is Windows. */
    public boolean windows() {
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Whether the JVM runs in IntelliJ's Run console: IntelliJ launches it with a
     * {@code -javaagent:…idea_rt.jar} argument.
     */
    public boolean intellijConsole() {
        for (String argument : jvmArguments) {
            if (argument != null && argument.startsWith("-javaagent:") && argument.contains("idea_rt.jar")) {
                return true;
            }
        }
        return false;
    }

    /** Whether output may carry ANSI colours under {@code mode} ({@code null} means {@code AUTO}). */
    public boolean colors(ColorMode mode) {
        if (noColorRequested()) {
            return false;
        }
        return switch (mode == null ? ColorMode.AUTO : mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> !dumbTerminal() && (intellijConsole() || (terminal && !windows()));
        };
    }

    /**
     * Reads a Vidocq setting before the configuration exists (only {@code -D} and the environment
     * are visible that early): the system property, then the environment variable named by the
     * MicroProfile Config rules ({@code vidocq.console.color}, {@code vidocq_console_color},
     * {@code VIDOCQ_CONSOLE_COLOR}).
     *
     * @return the stripped value, or empty when unset or blank
     */
    public static Optional<String> earlySetting(String key) {
        return earlySetting(key, System::getProperty, System::getenv);
    }

    static Optional<String> earlySetting(String key, UnaryOperator<String> systemProperties,
                                         UnaryOperator<String> environment) {
        String value = systemProperties.apply(key);
        if (isBlank(value)) {
            value = environment.apply(key);
        }
        if (isBlank(value)) {
            String sanitized = sanitize(key);
            value = environment.apply(sanitized);
            if (isBlank(value)) {
                value = environment.apply(sanitized.toUpperCase(Locale.ROOT));
            }
        }
        return isBlank(value) ? Optional.empty() : Optional.of(value.strip());
    }

    /** The colour mode visible before configuration: {@code -Dvidocq.console.color} or its environment variable. */
    public static ColorMode earlyColorMode() {
        return earlySetting(COLOR_KEY).flatMap(ColorMode::parse).orElse(ColorMode.AUTO);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sanitize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }
}
