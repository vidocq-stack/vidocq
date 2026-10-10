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

class LexerStringLiteralTest {
    @Test
    void decodesOnlyTheEscapesAllowedBySection13() {
        for (char quote : new char[] {'\'', '"'}) {
            String source = quote + "a\\\\b\\'c\\\"d" + quote;
            Token token = Lexer.tokenize(source).getFirst();
            assertEquals(TokenKind.STRING, token.kind());
            assertEquals(source, token.text());
            assertEquals("a\\b'c\"d", token.value());
        }
    }

    @Test
    void preservesEmptyStringsUnicodeNewlinesAndExpressionMarkers() {
        for (String value : List.of("", "Peano é", "\n", "$" + "{x} #{y}", "\"")) {
            assertEquals(value, Lexer.tokenize("'" + value + "'").getFirst().value());
        }
    }

    @Test
    void rejectsJavaStyleEscapesAtTheBackslashOffset() {
        for (String escape : List.of("\\n", "\\t", "\\u0041", "\\x")) {
            ELException error = assertThrows(ELException.class,
                    () -> Lexer.tokenize("  'a" + escape + "'"));
            assertEquals("Invalid string escape at offset 4", error.getMessage());
        }
    }

    @Test
    void reportsUnterminatedStringsAtTheirOpeningQuote() {
        for (String source : List.of(" 'abc", " 'abc\\")) {
            ELException error = assertThrows(ELException.class, () -> Lexer.tokenize(source));
            assertEquals("Unterminated string at offset 1", error.getMessage());
        }
    }
}

