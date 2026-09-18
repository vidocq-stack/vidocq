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

import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The console's settings for one boot, read from {@value #ENABLED_KEY}, {@value #PORT_KEY} and {@value #HOST_KEY}
 * and the launch mode.
 *
 * <p>The enable, port and host rules are the core's too: the core reads them before any extension is loaded, to
 * promise the console's address on the banner and to have every row of the report collected for it. Each implements
 * them, on purpose, and neither publishes them; both test suites read one table of cases per rule, the core's
 * {@code devconsole-enabled.csv}, {@code devconsole-port.csv} and {@code devconsole-host.csv}. The console, not the
 * core, reports a value it does not accept ({@code VIDOCQ-DEVC-003}); both use the default instead.
 *
 * @param enabled    {@code auto}, {@code true} or {@code false}, as resolved: an invalid value reads {@code auto}
 * @param on         whether the console starts on this boot
 * @param launchMode the launch mode of the boot
 * @param host       the host to listen on
 * @param port       the port to listen on; {@code 0} for a free one
 * @param invalid    the message of each value that was not accepted, in key order: enabled, port, host
 */
record DevConsoleSettings(String enabled, boolean on, LaunchMode launchMode, String host, int port,
                          List<String> invalid) {

    /** {@code auto} (the default: on in a dev launch), {@code true} or {@code false}. */
    static final String ENABLED_KEY = "vidocq.devconsole.enabled";
    /** The port the console listens on; {@code 0} for a free one. */
    static final String PORT_KEY = "vidocq.devconsole.port";
    /** The host the console listens on. */
    static final String HOST_KEY = "vidocq.devconsole.host";
    /** The port of an unset or invalid {@value #PORT_KEY}. */
    static final int DEFAULT_PORT = 8888;
    /** The host of an unset or invalid {@value #HOST_KEY}: loopback only. */
    static final String DEFAULT_HOST = "127.0.0.1";

    private static final int MAX_PORT = 65_535;
    /** The longest host accepted: a DNS name has at most 253 characters, an IPv6 literal far fewer. */
    private static final int MAX_HOST = 255;

    DevConsoleSettings {
        invalid = List.copyOf(invalid);
    }

    /**
     * The settings of a boot.
     *
     * @param config the configuration of the boot
     * @param mode   the launch mode of the boot
     */
    static DevConsoleSettings resolve(VidocqConfig config, LaunchMode mode) {
        String enabled = setting(config, ENABLED_KEY);
        String port = setting(config, PORT_KEY);
        String host = setting(config, HOST_KEY);
        List<String> invalid = new ArrayList<>();
        if (!validEnabled(enabled)) {
            invalid.add(invalidValue(ENABLED_KEY, enabled, "auto, true, false", "auto"));
        }
        if (!validPort(port)) {
            invalid.add(invalidValue(PORT_KEY, port, "0 to 65535", String.valueOf(DEFAULT_PORT)));
        }
        if (!validHost(host)) {
            invalid.add(invalidValue(HOST_KEY, host, "a host name or an IP address", DEFAULT_HOST));
        }
        String resolved = switch (validEnabled(enabled) && enabled != null ? enabled.toLowerCase(Locale.ROOT) : "") {
            case "true" -> "true";
            case "false" -> "false";
            default -> "auto";
        };
        return new DevConsoleSettings(resolved, enabled(enabled, mode), mode, host(host), port(port), invalid);
    }

    /**
     * The enable rule: {@code true} and {@code false} force the console on or off; {@code auto}, an unset or blank
     * value, and any value it does not accept turn it on in a dev launch only. Case and surrounding blanks are
     * ignored.
     *
     * @param value {@value #ENABLED_KEY}, or {@code null}
     * @param mode  the launch mode of the boot
     */
    static boolean enabled(String value, LaunchMode mode) {
        return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> mode == LaunchMode.DEV;
        };
    }

    /**
     * Whether {@code value} is one {@value #ENABLED_KEY} accepts: unset, blank, {@code auto}, {@code true} or
     * {@code false}, case and surrounding blanks ignored.
     */
    static boolean validEnabled(String value) {
        return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
            case "", "auto", "true", "false" -> true;
            default -> false;
        };
    }

    /**
     * The port rule: the configured port when it is ASCII digits, surrounding blanks ignored, from {@code 0}, a free
     * port, to {@code 65535}; otherwise, unset or invalid, {@value #DEFAULT_PORT}.
     *
     * @param value {@value #PORT_KEY}, or {@code null}
     */
    static int port(String value) {
        String digits = value == null ? "" : value.strip();
        if (digits.isEmpty() || !validPort(digits)) {
            return DEFAULT_PORT;
        }
        return Integer.parseInt(digits);
    }

    /** Whether {@code value} is one {@value #PORT_KEY} accepts: unset, blank, or ASCII digits from 0 to 65535. */
    static boolean validPort(String value) {
        String digits = value == null ? "" : value.strip();
        if (digits.isEmpty()) {
            return true;
        }
        if (digits.length() > 9 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        return Integer.parseInt(digits) <= MAX_PORT;
    }

    /**
     * The host rule: the configured host, stripped, an IPv6 literal without its brackets, when it is one
     * {@linkplain #validHost accepted}; otherwise, unset, blank or invalid, {@value #DEFAULT_HOST}.
     *
     * @param value {@value #HOST_KEY}, or {@code null}
     */
    static String host(String value) {
        if (value == null || value.isBlank() || !validHost(value)) {
            return DEFAULT_HOST;
        }
        String host = value.strip();
        return host.length() > 2 && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * Whether {@code value} is one {@value #HOST_KEY} accepts: unset, blank, or the characters of a host name or an
     * address literal only, ASCII letters and digits and {@code .-_:%[]}, at most 255 of them. The name is not
     * resolved; one with a letter outside ASCII is written in its ASCII form, {@code xn--…}.
     */
    static boolean validHost(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String host = value.strip();
        return host.length() <= MAX_HOST
                && host.codePoints().allMatch(c -> c < 0x80
                        && (Character.isLetterOrDigit(c) || ".-_:%[]".indexOf(c) >= 0));
    }

    /**
     * Why the console is off, as its section says it: {@code off (auto, launch mode prod)} or
     * {@code off (vidocq.devconsole.enabled=false)}.
     */
    String offReason() {
        return "false".equals(enabled) ? "off (" + ENABLED_KEY + "=false)"
                : "off (auto, launch mode " + launchMode.label() + ")";
    }

    /** {@code Invalid value 'yes' for vidocq.devconsole.enabled (auto, true, false): using auto}. */
    private static String invalidValue(String key, String value, String accepted, String used) {
        return "Invalid value '" + value + "' for " + key + " (" + accepted + "): using " + used;
    }

    /** {@code key}, stripped, or {@code null} when it is unset, blank or cannot be read. */
    private static String setting(VidocqConfig config, String key) {
        try {
            Optional<String> value = config.getValue(key);
            return value.map(String::strip).filter(v -> !v.isEmpty()).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
