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
package io.vidocq.peano.core.internal.syntax;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import jakarta.el.ELException;
import org.junit.jupiter.api.Test;

class ParserErrorTest {
    @Test
    void rejectsIncompleteOrUnsupportedSyntaxWithoutDiscardingTokens() {
        for (String source : List.of("", "1 2", "1e+", "1 +", "(1", "1)", "a ?", "a ? b", "a.",
                "a[", "f(1,)", "[1,]", "{1,}", "{'a':}", "{1,'b':2}", "{'a':1,2}",
                "x->", "(x,)->x", "a instanceof b", "a ? b:f()", "a;", "1()", "true()",
                "''()", "[1](2)", "{}()", "(a)(1)", "a.b()()")) {
            ELException error = assertThrows(ELException.class, () -> Parser.parseBody(source), source);
            assertTrue(error.getMessage().contains("offset"), source + ": " + error.getMessage());
        }
    }

    @Test
    void reportsTheUnexpectedTokenAtItsOriginalOffset() {
        ELException error = assertThrows(ELException.class, () -> Parser.parseBody("1 + )"));
        assertTrue(error.getMessage().endsWith("offset 4"), error.getMessage());
    }

    @Test
    void collectionNodesDefensivelyCopyTheirLists() {
        var elements = new java.util.ArrayList<Ast>();
        elements.add(Parser.parseBody("1"));
        var ast = new Ast.ListLiteral(elements);
        elements.clear();
        org.junit.jupiter.api.Assertions.assertEquals(1, ast.elements().size());
        var astElements = ast.elements();
        assertThrows(UnsupportedOperationException.class, astElements::clear);
    }
}

