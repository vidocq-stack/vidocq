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
package io.vidocq.runtime.extensions.jakartaee.web.noether.persistence.dev;

import java.util.regex.Pattern;

/**
 * A failure as the page shows it: the exception's simple name and its message, then the root cause's when it says
 * something else, a password and the user of a URL masked, cut after {@value #MAX_MESSAGE} characters each.
 */
final class Failures {

    /** The longest message of one exception. */
    static final int MAX_MESSAGE = 500;

    /** How many causes are followed at most, should a chain loop. */
    private static final int MAX_CAUSES = 20;

    /** {@code password=…} or {@code pwd=…}, up to its separator. */
    private static final Pattern PASSWORD = Pattern.compile("(?i)\\b(password|pwd)=[^;&\\s]*");

    /** The user, and its password, of a URL: {@code //user:password@host}. */
    private static final Pattern USER = Pattern.compile("//[^\\s/@]+@");

    private static final Pattern BREAKS = Pattern.compile("\\s*\\R\\s*");

    private Failures() {
    }

    /** {@code failure}, then its root cause when its message differs. */
    static String text(Throwable failure) {
        Throwable root = failure;
        for (int i = 0; i < MAX_CAUSES && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        String text = one(failure);
        String message = failure.getMessage();
        boolean told = message != null && root.getMessage() != null && message.contains(root.getMessage());
        return root == failure || told ? text : text + "\ncaused by " + one(root);
    }

    /** {@code text} on one line: each line break and the blanks around it become one space. */
    static String line(String text) {
        return BREAKS.matcher(text).replaceAll(" ");
    }

    private static String one(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return name;
        }
        String masked = USER.matcher(PASSWORD.matcher(message).replaceAll("$1=***")).replaceAll("//***@");
        return name + ": " + (masked.length() <= MAX_MESSAGE ? masked : masked.substring(0, MAX_MESSAGE) + "…");
    }
}
