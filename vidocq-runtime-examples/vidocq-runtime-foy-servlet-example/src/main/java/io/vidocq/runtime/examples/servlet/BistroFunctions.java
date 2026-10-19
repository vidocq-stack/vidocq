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
package io.vidocq.runtime.examples.servlet;

/** The functions of the {@code bistro} tag library, {@code WEB-INF/tlds/bistro.tld}. */
public final class BistroFunctions {

    private BistroFunctions() {
    }

    /** {@code ${bistro:euros(1850)}}: a price in euro cents, formatted. */
    public static String euros(int cents) {
        return Html.euros(cents);
    }

    /** {@code ${bistro:escape(param.from)}}: text made safe for HTML. */
    public static String escape(String text) {
        return text == null ? "" : Html.escape(text);
    }
}
