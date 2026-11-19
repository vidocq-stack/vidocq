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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jakarta.el.ELException;

/** Recursive-descent parser for EL 6.0 eval-expression bodies (sections 1.16 and 1.26). */
public final class Parser {
    // Lowest to highest binary precedence; aliases are normalized by the lexer.
    private static final List<Set<TokenKind>> BINARY_LEVELS = List.of(
            Set.of(TokenKind.OR),
            Set.of(TokenKind.AND),
            Set.of(TokenKind.EQUAL, TokenKind.NOT_EQUAL),
            Set.of(TokenKind.LESS, TokenKind.GREATER, TokenKind.LESS_EQUAL, TokenKind.GREATER_EQUAL),
            Set.of(TokenKind.CONCAT),
            Set.of(TokenKind.PLUS, TokenKind.MINUS),
            Set.of(TokenKind.MULTIPLY, TokenKind.DIVIDE, TokenKind.MODULO));

    private final List<Token> tokens;
    private int cursor;

    private Parser(String source, int sourceOffset) {
        tokens = Lexer.tokenize(source, sourceOffset);
    }

    /**
     * Parses a complete expression body, requiring every token to be consumed.
     *
     * @param source eval-expression body without delimiters
     * @return immutable expression tree
     * @throws ELException when the body does not match the grammar
     */
    public static Ast parseBody(String source) {
        return parseBody(source, 0);
    }

    static Ast parseBody(String source, int sourceOffset) {
        var parser = new Parser(source, sourceOffset);
        Ast ast = parser.sequence();
        parser.expect(TokenKind.EOF);
        return ast;
    }

    private Ast sequence() {
        Ast ast = assignment();
        while (at(TokenKind.SEMICOLON)) {
            Token operator = advance();
            ast = new Ast.Binary(operator, ast, assignment());
        }
        return ast;
    }

    private Ast assignment() {
        if (lambdaAhead()) {
            return lambda();
        }
        Ast ast = conditional();
        if (at(TokenKind.ASSIGN)) {
            Token operator = advance();
            return new Ast.Binary(operator, ast, assignment());
        }
        return ast;
    }

    private boolean lambdaAhead() {
        if (at(TokenKind.IDENTIFIER) && peek(1).kind() == TokenKind.ARROW) {
            return true;
        }
        if (!at(TokenKind.LPAREN)) {
            return false;
        }
        int offset = 1;
        if (peek(offset).kind() == TokenKind.IDENTIFIER) {
            offset++;
            while (peek(offset).kind() == TokenKind.COMMA) {
                offset++;
                if (peek(offset++).kind() != TokenKind.IDENTIFIER) {
                    return false;
                }
            }
        }
        return peek(offset).kind() == TokenKind.RPAREN && peek(offset + 1).kind() == TokenKind.ARROW;
    }

    private Ast lambda() {
        var parameters = new ArrayList<String>();
        if (take(TokenKind.LPAREN)) {
            if (!at(TokenKind.RPAREN)) {
                parameters.add(expect(TokenKind.IDENTIFIER).text());
                while (take(TokenKind.COMMA)) {
                    parameters.add(expect(TokenKind.IDENTIFIER).text());
                }
            }
            expect(TokenKind.RPAREN);
        } else {
            parameters.add(expect(TokenKind.IDENTIFIER).text());
        }
        expect(TokenKind.ARROW);
        // Assignment in a lambda body requires parentheses; the body is Lambda or Choice.
        return new Ast.Lambda(parameters, lambdaAhead() ? lambda() : conditional());
    }

    private Ast conditional() {
        Ast ast = binary(0);
        if (take(TokenKind.QUESTION)) {
            Ast whenTrue = conditional();
            expect(TokenKind.COLON);
            return new Ast.Conditional(ast, whenTrue, conditional());
        }
        return ast;
    }

    private Ast binary(int level) {
        if (level == BINARY_LEVELS.size()) {
            return unary();
        }
        Ast ast = binary(level + 1);
        while (BINARY_LEVELS.get(level).contains(peek(0).kind())) {
            Token operator = advance();
            ast = new Ast.Binary(operator, ast, binary(level + 1));
        }
        return ast;
    }

    private Ast unary() {
        if (at(TokenKind.MINUS) || at(TokenKind.NOT) || at(TokenKind.EMPTY)) {
            Token operator = advance();
            return new Ast.Unary(operator, unary());
        }
        return value();
    }

    private Ast value() {
        Ast ast = primary();
        while (true) {
            if (take(TokenKind.DOT)) {
                Token name = expect(TokenKind.IDENTIFIER);
                ast = new Ast.Property(ast, new Ast.Literal(new Token(
                        TokenKind.STRING, "'" + name.text() + "'", name.text(), name.start(), name.end())));
                if (at(TokenKind.LPAREN)) {
                    ast = new Ast.Call(ast, arguments());
                }
            } else if (take(TokenKind.LBRACKET)) {
                Ast key = sequence();
                expect(TokenKind.RBRACKET);
                ast = new Ast.Property(ast, key);
                if (at(TokenKind.LPAREN)) {
                    ast = new Ast.Call(ast, arguments());
                }
            } else {
                return ast;
            }
        }
    }

    private Ast primary() {
        Token token = peek(0);
        return switch (token.kind()) {
            case INTEGER, FLOATING_POINT, STRING, TRUE, FALSE, NULL -> {
                advance();
                yield new Ast.Literal(token);
            }
            case IDENTIFIER -> {
                advance();
                // Qualified functions take precedence over the conditional colon.
                if (at(TokenKind.COLON) && peek(1).kind() == TokenKind.IDENTIFIER
                        && peek(2).kind() == TokenKind.LPAREN) {
                    advance();
                    String name = advance().text();
                    yield calls(new Ast.Function(token.text(), name, arguments()));
                }
                if (at(TokenKind.LPAREN)) {
                    yield calls(new Ast.Call(new Ast.Identifier(token.text()), arguments()));
                }
                yield new Ast.Identifier(token.text());
            }
            case LPAREN -> {
                advance();
                Ast ast = sequence();
                expect(TokenKind.RPAREN);
                yield ast instanceof Ast.Lambda ? calls(ast) : ast;
            }
            case LBRACKET -> list();
            case LBRACE -> collection();
            default -> throw error("Expected an expression");
        };
    }

    private Ast calls(Ast target) {
        while (at(TokenKind.LPAREN)) {
            target = new Ast.Call(target, arguments());
        }
        return target;
    }

    private List<Ast> arguments() {
        expect(TokenKind.LPAREN);
        var arguments = new ArrayList<Ast>();
        if (!at(TokenKind.RPAREN)) {
            arguments.add(sequence());
            while (take(TokenKind.COMMA)) {
                arguments.add(sequence());
            }
        }
        expect(TokenKind.RPAREN);
        return arguments;
    }

    private Ast list() {
        expect(TokenKind.LBRACKET);
        var elements = new ArrayList<Ast>();
        if (!at(TokenKind.RBRACKET)) {
            elements.add(sequence());
            while (take(TokenKind.COMMA)) {
                elements.add(sequence());
            }
        }
        expect(TokenKind.RBRACKET);
        return new Ast.ListLiteral(elements);
    }

    private Ast collection() {
        expect(TokenKind.LBRACE);
        if (take(TokenKind.RBRACE)) {
            return new Ast.SetLiteral(List.of());
        }
        Ast first = sequence();
        if (take(TokenKind.COLON)) {
            var entries = new ArrayList<Ast.Entry>();
            entries.add(new Ast.Entry(first, sequence()));
            while (take(TokenKind.COMMA)) {
                Ast key = sequence();
                expect(TokenKind.COLON);
                entries.add(new Ast.Entry(key, sequence()));
            }
            expect(TokenKind.RBRACE);
            return new Ast.MapLiteral(entries);
        }
        var elements = new ArrayList<Ast>();
        elements.add(first);
        while (take(TokenKind.COMMA)) {
            elements.add(sequence());
        }
        expect(TokenKind.RBRACE);
        return new Ast.SetLiteral(elements);
    }

    private boolean at(TokenKind kind) {
        return peek(0).kind() == kind;
    }

    private boolean take(TokenKind kind) {
        if (at(kind)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenKind kind) {
        if (!at(kind)) {
            throw error("Expected " + kind + ", found " + peek(0).kind());
        }
        return advance();
    }

    private Token advance() {
        return tokens.get(cursor++);
    }

    private Token peek(int offset) {
        return tokens.get(Math.min(cursor + offset, tokens.size() - 1));
    }

    private ELException error(String message) {
        return new ELException(message + " at offset " + peek(0).start());
    }
}

