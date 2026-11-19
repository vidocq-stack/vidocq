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

package io.vidocq.bodoni.core.internal;

import jakarta.servlet.jsp.PageContext;

/** Maps the {@code scope} attribute of JSTL tags to {@link PageContext} scope constants. */
public final class Scopes {

    private Scopes() {
    }

    /**
     * Resolves a scope name.
     *
     * @param scope {@code page}, {@code request}, {@code session}, {@code application}
     *              or {@code null} (page scope)
     * @return the {@link PageContext} scope constant
     * @throws IllegalArgumentException for any other value; tag handlers rethrow it as a
     *                                  {@code JspTagException}
     */
    public static int of(String scope) {
        if (scope == null) {
            return PageContext.PAGE_SCOPE;
        }
        return switch (scope) {
            case "page" -> PageContext.PAGE_SCOPE;
            case "request" -> PageContext.REQUEST_SCOPE;
            case "session" -> PageContext.SESSION_SCOPE;
            case "application" -> PageContext.APPLICATION_SCOPE;
            default -> throw new IllegalArgumentException("Invalid scope: " + scope);
        };
    }
}
