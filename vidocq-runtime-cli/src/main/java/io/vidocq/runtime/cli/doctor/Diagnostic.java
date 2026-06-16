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
package io.vidocq.runtime.cli.doctor;

/**
 * Result of a single environment / project health check.
 *
 * <p>Pure data type — produced by {@link Diagnostics} and rendered by the
 * {@code doctor} command. The {@code hint} is optional remediation advice,
 * shown for failing/warning checks (or for all checks in verbose mode).
 *
 * @param name   short label of the check (e.g. {@code "Java version"})
 * @param status outcome of the check
 * @param detail human-readable detail of what was found
 * @param hint   remediation advice, or {@code null} when none applies
 */
public record Diagnostic(String name, Status status, String detail, String hint) {

    /** Severity of a {@link Diagnostic}, in increasing order. */
    public enum Status {
        /** Everything is fine. */
        OK,
        /** Non-fatal: the CLI works but something is sub-optimal. */
        WARN,
        /** Fatal for the checked capability; {@code doctor} exits non-zero. */
        FAIL
    }

    public static Diagnostic ok(String name, String detail) {
        return new Diagnostic(name, Status.OK, detail, null);
    }

    public static Diagnostic warn(String name, String detail, String hint) {
        return new Diagnostic(name, Status.WARN, detail, hint);
    }

    public static Diagnostic fail(String name, String detail, String hint) {
        return new Diagnostic(name, Status.FAIL, detail, hint);
    }
}
