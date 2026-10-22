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

package io.vidocq.bodoni.core.testing;

import jakarta.servlet.jsp.PageContext;

/**
 * The body of a tag under test. Implementations write through {@code pc.getOut()} so that,
 * while a {@code BodyContent} is pushed, the output lands in it.
 */
@FunctionalInterface
public interface Body {

    /**
     * A present but empty body ({@code <x></x>}): the container still runs the body
     * lifecycle (for a buffered body: {@code pushBody}, {@code setBodyContent},
     * {@code doInitBody}, {@code doAfterBody}).
     */
    Body EMPTY = pc -> {
    };

    /**
     * No body at all: an empty element ({@code <x/>}). The harness skips the whole body
     * lifecycle, as a Pages container does: no {@code setBodyContent}, no
     * {@code doInitBody}, no {@code doAfterBody}, whatever {@code doStartTag} returned.
     */
    Body NONE = new Body() {
        @Override
        public void render(PageContext pc) {
            throw new IllegalStateException("an empty element has no body to render");
        }
    };

    void render(PageContext pc) throws Exception;
}
