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
 * Something wrong in a file of key descriptions, or between two of them.
 *
 * @param origin  the file, as its reader was told to name it
 * @param line    the line, from 1; 0 when the problem is not one line's
 * @param kind    whether the file is wrong or newer than this reader
 * @param message what is wrong
 */
public record Problem(String origin, int line, Kind kind, String message) {

    /** What a tool makes of a problem. */
    public enum Kind {
        /** The file is wrong, whatever the version of its reader. */
        WRONG,
        /**
         * The file uses an attribute or a format this reader does not know. Wrong in a file written for this
         * version, expected in a jar that comes from a later one.
         */
        NEWER
    }
}
