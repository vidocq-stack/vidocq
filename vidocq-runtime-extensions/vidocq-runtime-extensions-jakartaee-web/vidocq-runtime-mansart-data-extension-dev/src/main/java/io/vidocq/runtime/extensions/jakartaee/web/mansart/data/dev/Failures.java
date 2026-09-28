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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import java.util.regex.Pattern;

/**
 * What a failing method shows (spec §7): the class of its exception and its message, cut at {@value #MAX_MESSAGE}
 * characters, with any {@code user:password@} masked as {@code ***:***@} — a dev launch only, but a JDBC URL may
 * still carry a real password.
 */
final class Failures {

    /** The longest message shown. */
    static final int MAX_MESSAGE = 500;

    /** A user and a password before an {@code @}, as a URL carries them. */
    private static final Pattern CREDENTIALS = Pattern.compile("[^\\s/:@]+:[^\\s/@]+@");

    private Failures() {}

    /** {@code <exception class>: <message>}, masked then cut; the class alone when there is no message. */
    static String text(Throwable failure) {
        String name = failure.getClass().getName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return name;
        }
        return name + ": " + cut(mask(message), MAX_MESSAGE);
    }

    /** {@code text} with each {@code user:password@} replaced by {@code ***:***@}. */
    static String mask(String text) {
        return CREDENTIALS.matcher(text).replaceAll("***:***@");
    }

    /** {@code text} cut after {@code max} characters with {@code …}, never inside a surrogate pair. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
