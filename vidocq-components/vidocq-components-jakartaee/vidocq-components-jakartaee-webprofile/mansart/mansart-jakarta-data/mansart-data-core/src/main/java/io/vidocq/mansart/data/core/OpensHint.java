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
package io.vidocq.mansart.data.core;

/** Builds the message of the errors raised when Mansart cannot reach a package of a named module. */
final class OpensHint {

    private OpensHint() { }

    /**
     * The message for a failed {@code privateLookupIn} on {@code target}: the exact {@code opens} line to write in
     * the module that owns the package, and the compile-time paths that need none.
     */
    static String message(Class<?> target) {
        Module mansart = OpensHint.class.getModule();
        String to = mansart.isNamed() ? mansart.getName() : "ALL-UNNAMED";
        String pkg = target.getPackageName();
        return "Cannot privateLookupIn " + target.getName() + ": package " + pkg + " is not open to " + to
                + ". Add `opens " + pkg + " to " + to + ";` to the module-info of " + target.getModule()
                + ". The compile-time path needs no opens: put the mansart-data-processor annotation processor on"
                + " that module, or run vidocq:generate on a dependency jar.";
    }
}
