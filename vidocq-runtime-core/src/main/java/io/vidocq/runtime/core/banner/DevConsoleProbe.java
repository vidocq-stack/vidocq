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
package io.vidocq.runtime.core.banner;

import io.vidocq.runtime.spi.report.LaunchMode;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the core knows of the dev console of a boot before any extension is loaded: whether its extension is
 * there, whether it is on, and the address it was asked to listen on.
 *
 * <p>The banner prints in {@code VidocqBootstrap.configure()}, before the extensions are loaded, and once per
 * JVM, so the console cannot put its own segment on the context line: the core looks for it instead, the way it
 * looks for the bricks ({@link StartupIdentity#CLASS_PATH_ANCHORS}), by the name of its class, loaded without
 * being initialised. The core also has every contributor of a boot whose console is on write all its rows, since
 * the console shows them whatever level the report is logged at.
 *
 * <p>The enable and port rules are the console's too: each implements them, on purpose, and neither publishes
 * them. Both test suites read one table of cases ({@code devconsole-enabled.csv}, {@code devconsole-port.csv}).
 * The console, not the core, reports an invalid value ({@code VIDOCQ-DEVC-003}) and declares the keys.
 *
 * <p>Nothing here throws, and nothing does I/O: a probe that fails is a console that is not there.
 */
final class DevConsoleProbe {

    /** The dev console's extension, looked up by name: the core does not depend on it. */
    static final String CONSOLE_CLASS = "io.vidocq.runtime.extensions.essentials.devconsole.DevConsoleExtension";
    /** {@code auto} (the default: on in a dev launch), {@code true} or {@code false}. */
    static final String ENABLED_KEY = "vidocq.devconsole.enabled";
    /** The port the console listens on; {@code 0} for a free one. */
    static final String PORT_KEY = "vidocq.devconsole.port";
    /** The host the console listens on. */
    static final String HOST_KEY = "vidocq.devconsole.host";
    /** The port of an unset or invalid {@value #PORT_KEY}. */
    static final int DEFAULT_PORT = 8888;
    /** The host of an unset or blank {@value #HOST_KEY}: loopback only. */
    static final String DEFAULT_HOST = "127.0.0.1";

    private static final String SEGMENT = "devconsole ";
    private static final int MAX_PORT = 65_535;
    /** The longest host shown: a DNS name has at most 253 characters, an IPv6 literal far fewer. */
    private static final int MAX_HOST = 255;

    private DevConsoleProbe() {}

    /**
     * The dev console of a boot, or {@code null} when its extension is not there or is off.
     *
     * @param config the configuration of the boot
     * @param mode   the launch mode the boot resolved
     * @param loader the loader that sees the extensions of the boot: the context class loader, or {@code null}
     */
    static StartupBanner.DevConsole probe(Function<String, Optional<String>> config, LaunchMode mode,
                                          ClassLoader loader) {
        try {
            if (!present(loader) || !enabled(setting(config, ENABLED_KEY), mode)) {
                return null;
            }
            return new StartupBanner.DevConsole(host(setting(config, HOST_KEY)), port(setting(config, PORT_KEY)));
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * Whether the console's extension can be loaded by {@code loader}: its class, not initialised. A class whose
     * interfaces are missing, such as a console without its panel SPI, cannot run, and is not there either.
     */
    static boolean present(ClassLoader loader) {
        if (loader == null) {
            return false;
        }
        try {
            Class.forName(CONSOLE_CLASS, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /**
     * The enable rule: {@code true} and {@code false} force the console on or off; {@code auto}, an unset or
     * blank value, and any value it does not accept turn it on in a dev launch only. Case and surrounding blanks
     * are ignored.
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
     * The port rule: the configured port when it is ASCII digits, surrounding blanks ignored, from {@code 0}, a free
     * port, to {@code 65535}; otherwise, unset or invalid, {@value #DEFAULT_PORT}.
     *
     * @param value {@value #PORT_KEY}, or {@code null}
     */
    static int port(String value) {
        String digits = value == null ? "" : value.strip();
        if (digits.isEmpty() || digits.length() > 9 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return DEFAULT_PORT;
        }
        int port = Integer.parseInt(digits);
        return port <= MAX_PORT ? port : DEFAULT_PORT;
    }

    /**
     * The configured host, stripped, or {@value #DEFAULT_HOST} when it is unset or blank.
     *
     * @param value {@value #HOST_KEY}, or {@code null}
     */
    static String host(String value) {
        return value == null || value.isBlank() ? DEFAULT_HOST : value.strip();
    }

    /**
     * The banner segment of a console configured on {@code host} and {@code port}: {@code devconsole :8888} on a
     * loopback address, {@code devconsole dev.example.com:8888} or {@code devconsole [fe80::1]:8888} otherwise.
     * {@code null} for port {@code 0}, which only the bind knows, and for a host that has characters no host name
     * has, which could break the line.
     */
    static String segment(String host, int port) {
        if (port <= 0 || port > MAX_PORT || host == null || !printable(host)) {
            return null;
        }
        if (loopback(host)) {
            return SEGMENT + ":" + port;
        }
        boolean ipv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        return SEGMENT + (ipv6 ? "[" + host + "]" : host) + ":" + port;
    }

    /**
     * Whether {@code host} names this machine only: {@code localhost}, or an IPv4 or IPv6 loopback literal,
     * brackets allowed. Read from the text alone: no name is resolved.
     */
    static boolean loopback(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            return InetAddress.ofLiteral(literal).isLoopbackAddress();
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }

    /** Whether {@code host} has only the characters of a host name or an address literal, and a sane length. */
    private static boolean printable(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST) {
            return false;
        }
        return host.codePoints().allMatch(c -> Character.isLetterOrDigit(c) || ".-_:%[]".indexOf(c) >= 0);
    }

    /** {@code key}, stripped, or {@code null} when it is unset, blank or cannot be read. */
    private static String setting(Function<String, Optional<String>> config, String key) {
        try {
            return config.apply(key).map(String::strip).filter(value -> !value.isEmpty()).orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
