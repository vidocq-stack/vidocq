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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.data;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link TypeNames}: a generic type in simple names, as a developer writes it. */
class TypeNamesTest {

    interface Shapes<T> {
        List<String> list();

        Optional<Integer> optional();

        long primitive();

        void nothing();

        int[] ints();

        Map<String, ? extends Number> bounded();

        List<? super Integer> lower();

        List<?> any();

        T variable();

        List<T>[] genericArray();

        Map.Entry<String, Long> nested();
    }

    private static String returnOf(String method) throws NoSuchMethodException {
        return TypeNames.of(Shapes.class.getMethod(method).getGenericReturnType());
    }

    @Test
    void genericTypesInSimpleNames() throws Exception {
        assertEquals("List<String>", returnOf("list"));
        assertEquals("Optional<Integer>", returnOf("optional"));
        assertEquals("long", returnOf("primitive"));
        assertEquals("void", returnOf("nothing"));
        assertEquals("int[]", returnOf("ints"));
        assertEquals("Map<String, ? extends Number>", returnOf("bounded"));
        assertEquals("List<? super Integer>", returnOf("lower"));
        assertEquals("List<?>", returnOf("any"));
        assertEquals("T", returnOf("variable"));
        assertEquals("List<T>[]", returnOf("genericArray"));
        assertEquals("Entry<String, Long>", returnOf("nested"));
    }

    @Test
    void aTypeThatCannotBePrintedShowsItsRawName() {
        Type odd = new Type() {
            @Override
            public String getTypeName() {
                return "com.acme.Odd";
            }
        };
        assertEquals("com.acme.Odd", TypeNames.of(odd));
        assertEquals("?", TypeNames.of(null));
    }
}
