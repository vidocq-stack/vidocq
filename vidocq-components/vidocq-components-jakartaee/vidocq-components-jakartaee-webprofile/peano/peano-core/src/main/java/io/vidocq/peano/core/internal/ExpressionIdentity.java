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

import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;

/** Parsed identity independent of whitespace, operator spelling and source offsets. */
final class ExpressionIdentity {
    private ExpressionIdentity() {}

    static Template template(Template template) {
        return new Template(template.kind() == Template.Kind.LITERAL ? Template.Kind.LITERAL : Template.Kind.IMMEDIATE,
                template.parts().stream().map(part -> switch (part) {
                    case Template.Text text -> (Template.Part) text;
                    case Template.Evaluation evaluation -> new Template.Evaluation(ast(evaluation.body()));
                }).toList());
    }

    private static Token literal(Token token) {
        String text = switch (token.kind()) {
            case INTEGER, FLOATING_POINT -> token.text();
            default -> token.kind().name();
        };
        return new Token(token.kind(), text, token.value(), 0, 0);
    }

    private static Token operator(Token token) {
        return new Token(token.kind(), token.kind().name(), null, 0, 0);
    }

    private static Ast ast(Ast node) {
        return switch (node) {
            case Ast.Literal value -> new Ast.Literal(literal(value.token()));
            case Ast.Identifier identifier -> identifier;
            case Ast.Unary unary -> new Ast.Unary(operator(unary.operator()), ast(unary.operand()));
            case Ast.Binary binary -> new Ast.Binary(operator(binary.operator()), ast(binary.left()), ast(binary.right()));
            case Ast.Conditional conditional -> new Ast.Conditional(ast(conditional.condition()),
                    ast(conditional.whenTrue()), ast(conditional.whenFalse()));
            case Ast.Property property -> new Ast.Property(ast(property.base()), ast(property.key()));
            case Ast.Call call -> new Ast.Call(ast(call.target()), call.arguments().stream().map(ExpressionIdentity::ast).toList());
            case Ast.Function function -> new Ast.Function(function.prefix(), function.name(),
                    function.arguments().stream().map(ExpressionIdentity::ast).toList());
            case Ast.Lambda lambda -> new Ast.Lambda(lambda.parameters(), ast(lambda.body()));
            case Ast.ListLiteral list -> new Ast.ListLiteral(list.elements().stream().map(ExpressionIdentity::ast).toList());
            case Ast.SetLiteral set -> new Ast.SetLiteral(set.elements().stream().map(ExpressionIdentity::ast).toList());
            case Ast.MapLiteral map -> new Ast.MapLiteral(map.entries().stream()
                    .map(entry -> new Ast.Entry(ast(entry.key()), ast(entry.value()))).toList());
        };
    }
}

