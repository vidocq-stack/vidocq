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

/**
 * The ANSI SGR escape sequences Vidocq prints on the console. Whether they may be printed at all
 * is decided by {@link ConsoleSupport#colors(ConsoleSupport.ColorMode)}.
 */
public final class Ansi {

    /** Resets every attribute. */
    public static final String RESET = "[0m";
    /** Bold red. */
    public static final String BOLD_RED = "[1;31m";
    /** Yellow. */
    public static final String YELLOW = "[33m";
    /** Green. */
    public static final String GREEN = "[32m";
    /** Cyan. */
    public static final String CYAN = "[36m";
    /** Faint (dim). */
    public static final String FAINT = "[2m";

    private Ansi() {}

    /** {@code text} between {@code code} and {@link #RESET}. */
    public static String wrap(String code, String text) {
        return code + text + RESET;
    }
}
