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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.el.ELException;
import jakarta.el.ELProcessor;
import jakarta.el.LambdaExpression;
import org.junit.jupiter.api.Test;

class LambdaExpressionTest {
    @Test
    void invokesImmediateAssignedRecursiveAndZeroArgumentLambdas() {
        var el = new ELProcessor();
        assertEquals(7L, el.<Object>eval("((x,y)->x+y)(3,4)"));
        assertEquals(64L, el.<Object>eval("(()->64)()"));
        assertEquals(7L, el.<Object>eval("f=x->x+1; f(6)"));
        assertEquals(120L, el.<Object>eval("fact=n->n==0?1:n*fact(n-1); fact(5)"));
        assertEquals(1L, el.<Object>eval("(x->x)(1,2)"));
        assertThrows(ELException.class, () -> el.eval("((x,y)->x+y)(1)"));
    }

    @Test
    void preservesNestedClosuresShadowingAndNullCapturedArguments() {
        var el = new ELProcessor();
        assertEquals(7L, el.<Object>eval("(x->y->x+y)(3)(4)"));
        assertEquals(4L, el.<Object>eval("(x->(x->x))(3)(4)"));
        assertNull(el.eval("(x->y->x)(null)(1)"));
        java.util.List<LambdaExpression> closures = el.eval("(x->[y->x+y])(3)");
        assertEquals(7L, closures.getFirst().invoke(4L));
    }

    @Test
    void restoresLambdaScopesAfterFailuresAndRejectsParameterAssignment() {
        var el = new ELProcessor();
        assertThrows(ELException.class, () -> el.eval("(x->1 mod 0)(7)"));
        assertThrows(jakarta.el.PropertyNotFoundException.class, () -> el.eval("x"));
        assertThrows(jakarta.el.PropertyNotWritableException.class, () -> el.eval("(x->(x=2))(1)"));
    }
}

