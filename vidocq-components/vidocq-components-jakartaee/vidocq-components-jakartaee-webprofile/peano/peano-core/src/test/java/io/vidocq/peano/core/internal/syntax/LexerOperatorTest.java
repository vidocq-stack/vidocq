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
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.List;
import jakarta.el.ELException;
import org.junit.jupiter.api.Test;

class LexerOperatorTest {
    @Test
    void recognizesPunctuationAndUsesTheLongestOperator() {
        assertEquals(List.of(TokenKind.DOT, TokenKind.LPAREN, TokenKind.RPAREN,
                TokenKind.LBRACKET, TokenKind.RBRACKET, TokenKind.LBRACE, TokenKind.RBRACE,
                TokenKind.COMMA, TokenKind.COLON, TokenKind.SEMICOLON, TokenKind.QUESTION,
                TokenKind.PLUS, TokenKind.MINUS, TokenKind.MULTIPLY, TokenKind.DIVIDE,
                TokenKind.MODULO, TokenKind.CONCAT, TokenKind.ASSIGN, TokenKind.ARROW,
                TokenKind.EQUAL, TokenKind.NOT_EQUAL, TokenKind.LESS, TokenKind.GREATER,
                TokenKind.LESS_EQUAL, TokenKind.GREATER_EQUAL, TokenKind.AND,
                TokenKind.OR, TokenKind.NOT, TokenKind.EOF),
                Lexer.tokenize(". ( ) [ ] { } , : ; ? + - * / % += = -> == != < > <= >= && || !")
                .stream().map(Token::kind).toList());
    }

    @Test
    void distinguishesAdjacentOperatorsWithoutWhitespace() {
        assertEquals(List.of(TokenKind.CONCAT, TokenKind.PLUS, TokenKind.ARROW, TokenKind.MINUS,
                        TokenKind.EQUAL, TokenKind.ASSIGN, TokenKind.NOT_EQUAL, TokenKind.NOT,
                        TokenKind.EOF),
                Lexer.tokenize("+=+->-===!=!").stream().map(Token::kind).toList());
    }

    @Test
    void reportsIllegalCharactersAtTheirSourceOffset() {
        for (String symbol : List.of("@", "&", "|", "\\", "€")) {
            ELException error = assertThrows(ELException.class, () -> Lexer.tokenize("a " + symbol));
            assertEquals("Illegal character at offset 2", error.getMessage());
        }
    }

    @Test
    void skipsOnlyTheWhitespaceDefinedByTheGrammarAndPreservesSpans() {
        var tokens = Lexer.tokenize(" \t\n\rbean += 'x'");
        assertEquals(new Token(TokenKind.IDENTIFIER, "bean", null, 4, 8), tokens.get(0));
        assertEquals(new Token(TokenKind.CONCAT, "+=", null, 9, 11), tokens.get(1));
        assertEquals(new Token(TokenKind.STRING, "'x'", "x", 12, 15), tokens.get(2));
        assertEquals(new Token(TokenKind.EOF, "", null, 15, 15), tokens.get(3));
        assertThrows(ELException.class, () -> Lexer.tokenize("\u000c"));
    }

    @Test
    void returnsAnImmutableTokenListIncludingEndOfInput() {
        var tokens = Lexer.tokenize("");
        assertEquals(List.of(new Token(TokenKind.EOF, "", null, 0, 0)), tokens);
        assertThrows(UnsupportedOperationException.class, () -> tokens.clear());
    }
}

