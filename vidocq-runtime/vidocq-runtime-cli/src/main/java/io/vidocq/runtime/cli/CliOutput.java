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
package io.vidocq.runtime.cli;

/**
 * Thin ANSI output helper. Degrades gracefully when NO_COLOR is set or
 * stdout is not a real terminal (e.g. CI pipes).
 */
public final class CliOutput {

    private static final boolean ANSI =
            System.getenv("NO_COLOR") == null && System.console() != null;

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String RED    = "\033[31m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN   = "\033[36m";

    private CliOutput() {}

    public static void info(String msg)    { System.out.println(styled(CYAN,   "ℹ ") + msg); }
    public static void success(String msg) { System.out.println(styled(GREEN,  "✔ ") + msg); }
    public static void warning(String msg) { System.out.println(styled(YELLOW, "⚠ ") + msg); }
    public static void error(String msg)   { System.err.println(styled(RED,    "✘ ") + msg); }
    public static void println(String msg) { System.out.println(msg); }
    public static void println()           { System.out.println(); }

    public static String bold(String s)   { return ANSI ? BOLD   + s + RESET : s; }
    public static String dim(String s)    { return ANSI ? DIM    + s + RESET : s; }
    public static String green(String s)  { return ANSI ? GREEN  + s + RESET : s; }
    public static String red(String s)    { return ANSI ? RED    + s + RESET : s; }
    public static String cyan(String s)   { return ANSI ? CYAN   + s + RESET : s; }
    public static String yellow(String s) { return ANSI ? YELLOW + s + RESET : s; }

    private static String styled(String code, String s) {
        return ANSI ? code + s + RESET : s;
    }
}
