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

import java.util.List;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Template;
import io.vidocq.peano.core.internal.syntax.Token;

/** Parsed identity independent of whitespace, operator spelling and source offsets. */
final class ExpressionIdentity {
    private ExpressionIdentity() {}

    static Template template(Template template) {
        return new Template(template.kind() == Template.Kind.LITERAL ? Template.Kind.LITERAL : Template.Kind.IMMEDIATE,
                template.parts().stream().map(ExpressionIdentity::part).toList());
    }

    private static Template.Part part(Template.Part part) {
        return switch (part) {
            case Template.Text text -> text;
            case Template.Evaluation(Ast body) -> new Template.Evaluation(ast(body));
        };
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
            case Ast.Literal(Token token) -> new Ast.Literal(literal(token));
            case Ast.Identifier identifier -> identifier;
            case Ast.Unary(Token operator, Ast operand) -> new Ast.Unary(operator(operator), ast(operand));
            case Ast.Binary(Token operator, Ast left, Ast right) -> new Ast.Binary(operator(operator), ast(left), ast(right));
            case Ast.Conditional(Ast condition, Ast whenTrue, Ast whenFalse) -> new Ast.Conditional(ast(condition),
                    ast(whenTrue), ast(whenFalse));
            case Ast.Property(Ast base, Ast key) -> new Ast.Property(ast(base), ast(key));
            case Ast.Call(Ast target, List<Ast> arguments) -> new Ast.Call(ast(target), asts(arguments));
            case Ast.Function(String prefix, String name, List<Ast> arguments) -> new Ast.Function(prefix, name,
                    asts(arguments));
            case Ast.Lambda(List<String> parameters, Ast body) -> new Ast.Lambda(parameters, ast(body));
            case Ast.ListLiteral(List<Ast> elements) -> new Ast.ListLiteral(asts(elements));
            case Ast.SetLiteral(List<Ast> elements) -> new Ast.SetLiteral(asts(elements));
            case Ast.MapLiteral(List<Ast.Entry> entries) -> new Ast.MapLiteral(entries.stream()
                    .map(entry -> new Ast.Entry(ast(entry.key()), ast(entry.value()))).toList());
        };
    }

    private static List<Ast> asts(List<Ast> nodes) {
        return nodes.stream().map(ExpressionIdentity::ast).toList();
    }
}

