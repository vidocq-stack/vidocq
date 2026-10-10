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

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.stream.Collectors;

final class GrammarAssertions {
    private GrammarAssertions() {
    }

    static void assertShape(String source, String expected) {
        assertEquals(expected, shape(Parser.parseBody(source)), source);
    }

    static String shape(Ast ast) {
        return switch (ast) {
            case Ast.Literal n -> n.token().text();
            case Ast.Identifier n -> n.name();
            case Ast.Unary n -> "(" + n.operator().kind() + " " + shape(n.operand()) + ")";
            case Ast.Binary n -> "(" + n.operator().kind() + " " + shape(n.left()) + " " + shape(n.right()) + ")";
            case Ast.Conditional n -> "(?: " + shape(n.condition()) + " " + shape(n.whenTrue()) + " " + shape(n.whenFalse()) + ")";
            case Ast.Property n -> "property(" + shape(n.base()) + "," + shape(n.key()) + ")";
            case Ast.Call n -> "call(" + shape(n.target()) + arguments(n.arguments()) + ")";
            case Ast.Function n -> "function(" + n.prefix() + ":" + n.name() + arguments(n.arguments()) + ")";
            case Ast.Lambda n -> "lambda(" + String.join(",", n.parameters()) + " " + shape(n.body()) + ")";
            case Ast.ListLiteral n -> "list(" + n.elements().stream().map(GrammarAssertions::shape).collect(Collectors.joining(",")) + ")";
            case Ast.SetLiteral n -> "set(" + n.elements().stream().map(GrammarAssertions::shape).collect(Collectors.joining(",")) + ")";
            case Ast.MapLiteral n -> "map(" + n.entries().stream().map(e -> shape(e.key()) + ":" + shape(e.value())).collect(Collectors.joining(",")) + ")";
        };
    }

    private static String arguments(java.util.List<Ast> arguments) {
        return arguments.stream().map(n -> "," + shape(n)).collect(Collectors.joining());
    }
}

