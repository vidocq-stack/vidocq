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

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The JDWP agent of this JVM, when it runs with one: what a debugger has to attach to.
 *
 * <p>Read from the JVM input arguments ({@code -agentlib:jdwp=…}, or {@code -Xrunjdwp:…} for a
 * launcher that still writes it that way), never from a socket: the address is repeated exactly as
 * the agent was told it ({@code *:5005}, {@code 127.0.0.1:5005}).
 *
 * @param address the {@code address} option, or {@code null} when the agent was given none (the
 *                JVM then chooses a port and only its own message tells which)
 * @param suspend whether the JVM waits for the debugger before running
 */
public record DebugAgent(String address, boolean suspend) {

    static final String AGENTLIB = "-agentlib:jdwp=";
    static final String XRUNJDWP = "-Xrunjdwp:";

    /** The JDWP agent among {@code jvmArguments}, or empty when this JVM runs without one. */
    public static Optional<DebugAgent> detect(List<String> jvmArguments) {
        if (jvmArguments == null) {
            return Optional.empty();
        }
        for (String argument : jvmArguments) {
            String options = options(argument);
            if (options != null) {
                return Optional.of(parse(options));
            }
        }
        return Optional.empty();
    }

    /** {@code debug *:5005}, followed by {@code suspend=y} when the JVM is waiting. */
    public String segment() {
        return "debug" + (address == null ? "" : " " + address) + (suspend ? " suspend=y" : "");
    }

    /** The options of a JDWP argument ({@code transport=dt_socket,address=*:5005}), or {@code null}. */
    static String options(String argument) {
        if (argument == null) {
            return null;
        }
        if (argument.startsWith(AGENTLIB)) {
            return argument.substring(AGENTLIB.length());
        }
        return argument.startsWith(XRUNJDWP) ? argument.substring(XRUNJDWP.length()) : null;
    }

    /** {@code transport=dt_socket,server=y,suspend=n,address=*:5005}. */
    static DebugAgent parse(String options) {
        String address = null;
        boolean suspend = false;
        for (String option : options.split(",")) {
            int equals = option.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String name = option.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String value = option.substring(equals + 1).strip();
            if (name.equals("address") && !value.isEmpty()) {
                address = value;
            } else if (name.equals("suspend")) {
                suspend = value.equalsIgnoreCase("y") || value.equalsIgnoreCase("yes")
                        || value.equalsIgnoreCase("true");
            }
        }
        return new DebugAgent(address, suspend);
    }
}
