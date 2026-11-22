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
package io.vidocq.runtime.config.keys;

/**
 * What a value written for a configuration key has against the description of that key.
 *
 * @param kind        what is wrong
 * @param key         the key, as the application writes it
 * @param value       the value, as the application gives it
 * @param description the description the key was checked against
 * @param message     what is wrong, to be read after the key or the value: {@code is not an int}
 */
public record ValueProblem(Kind kind, String key, String value, KeyDescription description, String message) {

    /** What a value, or the key itself, has against a description. */
    public enum Kind {
        /** The value, or an item of a list, cannot be read as the type of the key. */
        NOT_OF_TYPE,
        /** The value, or an item of a list, is none of the values the key allows. */
        NOT_ALLOWED,
        /** The key should no longer be used. */
        DEPRECATED
    }
}
