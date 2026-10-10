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
import java.util.function.Predicate;
import jakarta.el.ELException;
import jakarta.el.ELProcessor;
import org.junit.jupiter.api.Test;

class PredicateConversionTest {
    @Test
    void convertsElLambdasToTheStandardJavaPredicateInterface() {
        var el = new ELProcessor();
        Predicate<Object> predicate = el.getValue("x->x>5", Predicate.class);
        assertTrue(predicate.test(6));
        assertFalse(predicate.test(5));
        el.defineBean("predicate",predicate);
        assertEquals(true,el.eval("predicate.test(10)"));
    }

    @Test
    void coercesTheLambdaReturnValueToPrimitiveBoolean() {
        var el = new ELProcessor();
        Predicate<Object> truth = el.getValue("x->'true'", Predicate.class);
        Predicate<Object> empty = el.getValue("x->null", Predicate.class);
        assertTrue(truth.test(new Object()));
        assertFalse(empty.test(new Object()));
        Predicate<Object> invalid = el.getValue("x->42", Predicate.class);
        assertThrows(ELException.class, ()->invalid.test(1));
    }

    @Test
    void preservesClosuresAndRestoresScopesAfterAdapterFailures() {
        var el = new ELProcessor();
        Predicate<Object> closure = el.getValue("(limit->(x->x>limit))(5)", Predicate.class);
        assertTrue(closure.test(7));
        assertFalse(closure.test(3));
        Predicate<Object> failure = el.getValue("x->missing", Predicate.class);
        assertThrows(ELException.class, ()->failure.test(1));
        assertFalse(el.getELManager().getELContext().isLambdaArgument("x"));
        Predicate<Object> existing = ignored -> true;
        assertSame(existing, el.getELManager().getExpressionFactory().coerceToType(existing, Predicate.class));
    }
}

