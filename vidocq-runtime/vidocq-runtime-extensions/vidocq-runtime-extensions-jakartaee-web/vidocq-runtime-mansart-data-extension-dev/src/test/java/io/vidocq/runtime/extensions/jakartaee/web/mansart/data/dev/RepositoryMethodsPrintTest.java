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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data.dev;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a type a repository method names is printed: simple names, type variables bound. */
class RepositoryMethodsPrintTest {

    /** Return types of each shape {@link RepositoryMethods#print} reads. */
    interface Shapes<T extends Number, U> {

        int[] array();

        List<String> list();

        T bounded();

        U unbounded();

        List<T>[] genericArray();

        List<? super Integer> lower();

        List<? extends CharSequence> upper();

        List<?> any();
    }

    private static String printed(String method) throws NoSuchMethodException {
        return RepositoryMethods.print(Shapes.class.getMethod(method).getGenericReturnType(), Map.of(), 0);
    }

    @Test
    void classesArraysAndParameterizedTypesInSimpleNames() throws NoSuchMethodException {
        assertEquals("int[]", printed("array"));
        assertEquals("List<String>", printed("list"));
        assertEquals("List<Number>[]", printed("genericArray"));
    }

    @Test
    void aTypeVariableIsItsBindingItsBoundOrItsName() throws NoSuchMethodException {
        assertEquals("Number", printed("bounded"));
        assertEquals("U", printed("unbounded"));
        TypeVariable<?> t = Shapes.class.getTypeParameters()[0];
        assertEquals("Long", RepositoryMethods.print(t, Map.of(t, Long.class), 0));
    }

    @Test
    void wildcardsShowTheirBound() throws NoSuchMethodException {
        assertEquals("List<? super Integer>", printed("lower"));
        assertEquals("List<? extends CharSequence>", printed("upper"));
        assertEquals("List<?>", printed("any"));
    }

    @Test
    void anAnonymousClassIsItsNameAnUnknownTypeItsTypeNameTooDeepAQuestionMark() {
        Object anonymous = new Object() {
            @Override
            public String toString() {
                return "anonymous";
            }
        };
        Type custom = new Type() {
            @Override
            public String getTypeName() {
                return "Custom";
            }
        };

        assertEquals(anonymous.getClass().getName(), RepositoryMethods.print(anonymous.getClass(), Map.of(), 0));
        assertEquals("Custom", RepositoryMethods.print(custom, Map.of(), 0));
        assertEquals("?", RepositoryMethods.print(String.class, Map.of(), 17));
    }
}
