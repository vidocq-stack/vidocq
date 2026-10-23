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
package io.vidocq.runtime.tooling;

/**
 * Where the shared project editing reports what it does: the Vidocq CLI prints it, the Maven plugin logs it.
 * Messages are plain text; each tool styles them its own way.
 */
public interface Reporter {

    /** A change made to the project; {@code detail}, possibly empty, qualifies it (a coordinate, a file part). */
    void success(String message, String detail);

    /** A secondary line about the previous one: a skip, a hint. */
    void note(String message);

    /** Something the user should act on; the edit goes on. */
    void warning(String message);

    /** Why the edit stopped. */
    void error(String message);
}
