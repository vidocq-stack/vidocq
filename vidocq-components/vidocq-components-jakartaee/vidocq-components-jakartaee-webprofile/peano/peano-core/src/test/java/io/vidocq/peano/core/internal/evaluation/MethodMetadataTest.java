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
package io.vidocq.peano.core.internal.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.el.ELClass;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import org.junit.jupiter.api.Test;

/** The reflective metadata fallback: which public method a name and parameter types select. */
class MethodMetadataTest {
    private final StandardELContext context = new StandardELContext(ExpressionFactory.newInstance());

    @Test
    void keepsTheCovariantMethodOverItsPublicBridges() {
        // StringBuilder.append(CharSequence) has public bridges returning Appendable and AbstractStringBuilder.
        var method = MethodMetadata.find(new StringBuilder("ab"), "append", new Class<?>[]{CharSequence.class},
                null, context);
        assertEquals(StringBuilder.class, method.getReturnType());
        assertFalse(method.isBridge());
    }

    @Test
    void selectsOnlyStaticMethodsOnAClass() {
        var method = MethodMetadata.find(new ELClass(Integer.class), "toString", new Class<?>[]{int.class},
                null, context);
        assertEquals(String.class, method.getReturnType());
        assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
    }

    @Test
    void selectsTheInstanceMethodOnAnInstance() {
        var method = MethodMetadata.find(7, "toString", new Class<?>[0], null, context);
        assertFalse(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
    }
}
