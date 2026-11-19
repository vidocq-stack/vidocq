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

package io.vidocq.bodoni.core.tags.core;

/**
 * Implemented by the actions that accept nested {@code <c:param>} (Tags 3.0 §7.7):
 * {@code <c:url>}, {@code <c:redirect>} and {@code <c:import>}.
 */
interface ParamParent {

    /**
     * Records a request parameter. The name is never empty; the value is never {@code null}.
     * Encoding is applied when the URL is built.
     *
     * @throws IllegalStateException if the action cannot take parameters in its current form
     */
    void addParameter(String name, String value);
}
