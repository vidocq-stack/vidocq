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

import java.util.Locale;
import java.util.Optional;

/** What a file of key descriptions can say about a key, or about a family of names. */
public enum Attribute {

    /** The type of the value; {@code string} when the file gives none. */
    TYPE,
    /** The value the key takes when the application gives none. */
    DEFAULT,
    /** {@code true} when the application must give the key. */
    REQUIRED,
    /** The allowed values, separated by commas. */
    VALUES,
    /** One line of documentation. */
    DOC,
    /** {@code <sibling>=<value>[,<value>]}: the key exists only when that sibling key has one of these values. */
    WHEN,
    /** A family: the value, or each item of a list, is a name of that family. */
    REF,
    /** {@code true} when the key should no longer be used. */
    DEPRECATED,
    /** The class and the member that declare the key, in a generated file. */
    SOURCE,
    /** On a family only: the names that exist without being written in the application's configuration. */
    NAMES;

    /** The attribute as a file writes it: {@code type}, {@code default}, ... */
    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The attribute a file names.
     *
     * @param text the attribute as written, in lower case
     * @return the attribute, or empty when this version of the format has none of that name
     */
    public static Optional<Attribute> of(String text) {
        for (Attribute attribute : values()) {
            if (attribute.text().equals(text)) {
                return Optional.of(attribute);
            }
        }
        return Optional.empty();
    }
}
