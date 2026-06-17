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
package io.vidocq.runtime.codegen.commons;

import java.util.Set;
import java.util.TreeSet;

/**
 * A single JPMS directive a Vidocq extension needs its consumer module to declare, plus a short
 * human reason used in diagnostics. Either a {@code requires <module>;} or an
 * {@code opens <package>[ to <modules>];}.
 *
 * @param kind   whether this is a {@code requires} or an {@code opens}
 * @param name   the required module name (for {@code requires}) or the opened package (for {@code opens})
 * @param to     for a qualified {@code opens}, the target modules; empty means an unqualified {@code opens}
 * @param reason a short explanation surfaced in the diagnostic (why the directive is needed)
 */
public record Requirement(Kind kind, String name, Set<String> to, String reason) {

    /** The directive flavour. */
    public enum Kind {REQUIRES, OPENS}

    public Requirement {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("requirement name must not be blank");
        }
        to = Set.copyOf(to == null ? Set.of() : to);
        reason = reason == null ? "" : reason;
    }

    /** A {@code requires <module>;} requirement. */
    public static Requirement requires(String module, String reason) {
        return new Requirement(Kind.REQUIRES, module, Set.of(), reason);
    }

    /** An unqualified {@code opens <package>;} requirement. */
    public static Requirement opens(String pkg, String reason) {
        return new Requirement(Kind.OPENS, pkg, Set.of(), reason);
    }

    /** A qualified {@code opens <package> to <modules>;} requirement. */
    public static Requirement opensTo(String pkg, Set<String> targetModules, String reason) {
        return new Requirement(Kind.OPENS, pkg, targetModules, reason);
    }

    /** Renders this requirement as the exact module-info source line to add (copy-pasteable). */
    public String directive() {
        return switch (kind) {
            case REQUIRES -> "requires " + name + ";";
            case OPENS -> to.isEmpty()
                    ? "opens " + name + ";"
                    : "opens " + name + " to " + String.join(", ", new TreeSet<>(to)) + ";";
        };
    }
}
