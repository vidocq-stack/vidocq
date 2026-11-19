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
import java.util.List;
import org.junit.jupiter.api.Test;

class LexerNumericLiteralTest {
    @Test
    void recognizesDecimalIntegersWithoutConvertingTheirValues() {
        var tokens = Lexer.tokenize("0 007 9223372036854775808");
        assertEquals(List.of(TokenKind.INTEGER, TokenKind.INTEGER, TokenKind.INTEGER, TokenKind.EOF),
                tokens.stream().map(Token::kind).toList());
        assertEquals("9223372036854775808", tokens.get(2).text());
    }

    @Test
    void recognizesEveryFloatingPointProduction() {
        for (String literal : List.of("1.", "1.0", ".5", "1.e2", ".5E-2", "1e3", "1E+3")) {
            var tokens = Lexer.tokenize(literal);
            assertEquals(TokenKind.FLOATING_POINT, tokens.getFirst().kind(), literal);
            assertEquals(literal, tokens.getFirst().text());
            assertEquals(2, tokens.size(), literal);
        }
    }

    @Test
    void leavesSignsAndIncompleteExponentsForTheParser() {
        assertEquals(List.of(TokenKind.MINUS, TokenKind.INTEGER, TokenKind.PLUS,
                        TokenKind.INTEGER, TokenKind.IDENTIFIER, TokenKind.PLUS, TokenKind.EOF),
                Lexer.tokenize("-1 + 2e+").stream().map(Token::kind).toList());
    }

    @Test
    void separatesPropertyDotsFromLeadingDecimalPoints() {
        assertEquals(List.of(TokenKind.IDENTIFIER, TokenKind.DOT, TokenKind.IDENTIFIER,
                        TokenKind.FLOATING_POINT, TokenKind.EOF),
                Lexer.tokenize("bean.value .5").stream().map(Token::kind).toList());
    }
}

