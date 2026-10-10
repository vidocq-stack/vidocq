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
package io.vidocq.peano.bootstrap.exported;

/** This fixture package is exported only to the Jakarta API resolver. */
public record PublicRecord(long count) {
    @Deprecated
    public long count() {
        return count;
    }

    public static long twice(long value) {
        return value * 2;
    }

    public interface A {
    }

    public interface B {
    }

    public interface C extends A {
    }

    public record Argument() implements C, B {
    }

    public static String pick(A value) {
        return "A";
    }

    public static String pick(B value) {
        return "B";
    }

    public static String pick(C value) {
        return "C";
    }

    public static String boxed(int value) {
        return "primitive";
    }

    public static String boxed(Integer value) {
        return "reference";
    }
}

