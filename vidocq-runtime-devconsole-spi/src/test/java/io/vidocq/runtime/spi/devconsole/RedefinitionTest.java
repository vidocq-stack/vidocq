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
package io.vidocq.runtime.spi.devconsole;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedefinitionTest {

    @Test
    void theMethodsKeepTheirOrderAndCannotChange() {
        var methods = new LinkedHashSet<>(List.of("refresh", "lambda$new$0", "<init>"));
        var redefined = new RedefinedClass(String.class, methods);
        methods.add("added");

        assertEquals(List.of("refresh", "lambda$new$0", "<init>"), List.copyOf(redefined.methods()));
        Set<String> copy = redefined.methods();
        assertThrows(UnsupportedOperationException.class, () -> copy.add("other"));
    }

    @Test
    void theClassesCannotChange() {
        var classes = new ArrayList<>(List.of(new RedefinedClass(String.class, Set.of("length"))));
        var redefinition = new Redefinition(classes);
        classes.clear();

        assertEquals(1, redefinition.classes().size());
        List<RedefinedClass> copy = redefinition.classes();
        assertThrows(UnsupportedOperationException.class, copy::clear);
    }

    @Test
    void onlyLambdaBodiesWhenEveryMethodIsALambda() {
        assertTrue(new RedefinedClass(String.class, Set.of("lambda$new$0", "lambda$build$1")).onlyLambdaBodies());
        assertFalse(new RedefinedClass(String.class, Set.of("lambda$new$0", "<init>")).onlyLambdaBodies());
    }
}
