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

class LexerIdentifierTest {
    @Test
    void recognizesIdentifiersAndImplicitObjectsFromTheCollectedSyntax() {
        for (String name : List.of("bean", "_value", "$value", "café", "名", "#", "#implicit",
                "value١", "trueValue", "andrew", "True")) {
            Token token = Lexer.tokenize(name).getFirst();
            assertEquals(TokenKind.IDENTIFIER, token.kind(), name);
            assertEquals(name, token.text());
        }
    }

    @Test
    void reservesEveryWordFromSection117() {
        assertEquals(List.of(TokenKind.AND, TokenKind.OR, TokenKind.NOT,
                TokenKind.EQUAL, TokenKind.NOT_EQUAL, TokenKind.LESS, TokenKind.GREATER,
                TokenKind.LESS_EQUAL, TokenKind.GREATER_EQUAL, TokenKind.TRUE,
                TokenKind.FALSE, TokenKind.NULL, TokenKind.EMPTY, TokenKind.DIVIDE,
                TokenKind.MODULO, TokenKind.INSTANCEOF, TokenKind.EOF),
                Lexer.tokenize("and or not eq ne lt gt le ge true false null empty div mod instanceof")
                .stream().map(Token::kind).toList());
    }

    @Test
    void doesNotAcceptArbitraryJavaIdentifierCharactersOutsideTheGrammar() {
        assertThrows(ELException.class, () -> Lexer.tokenize("€value"));
    }

    @Test
    void treatsTheImplicitObjectPrefixAsAnIdentifierStartOnly() {
        assertEquals(List.of("value", "#next", ""), Lexer.tokenize("value#next")
                .stream().map(Token::text).toList());
    }
}

