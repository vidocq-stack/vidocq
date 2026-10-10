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
import java.util.Map;
import java.util.Objects;
import jakarta.el.ELException;

/**
 * Hand-written lexer for an eval-expression body (EL 6.0 section 1.26).
 * Expression delimiters and composite text belong to the expression parser.
 * Each invocation owns its cursor; no evaluation state is shared.
 */
public final class Lexer {
    private static final Map<String, TokenKind> WORDS = Map.ofEntries(
            Map.entry("true", TokenKind.TRUE), Map.entry("false", TokenKind.FALSE),
            Map.entry("null", TokenKind.NULL), Map.entry("empty", TokenKind.EMPTY),
            Map.entry("instanceof", TokenKind.INSTANCEOF),
            Map.entry("and", TokenKind.AND), Map.entry("or", TokenKind.OR),
            Map.entry("not", TokenKind.NOT), Map.entry("eq", TokenKind.EQUAL),
            Map.entry("ne", TokenKind.NOT_EQUAL), Map.entry("lt", TokenKind.LESS),
            Map.entry("gt", TokenKind.GREATER), Map.entry("le", TokenKind.LESS_EQUAL),
            Map.entry("ge", TokenKind.GREATER_EQUAL), Map.entry("div", TokenKind.DIVIDE),
            Map.entry("mod", TokenKind.MODULO));

    private final String source;
    private int cursor;

    private Lexer(String source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Tokenizes a body without converting numeric literals.
     *
     * @param source eval-expression body, without its delimiters
     * @return immutable tokens including an end-of-input token
     * @throws ELException for an illegal character or malformed string
     */
    public static List<Token> tokenize(String source) {
        return new Lexer(source).scan();
    }

    private List<Token> scan() {
        var tokens = new ArrayList<Token>();
        while (cursor < source.length()) {
            char ch = source.charAt(cursor);
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                cursor++;
                continue;
            }
            int start = cursor;
            TokenKind kind;
            String value = null;
            if (ch == '\'' || ch == '"') {
                value = string();
                kind = TokenKind.STRING;
            } else if (digit(ch) || (ch == '.' && cursor + 1 < source.length()
                    && digit(source.charAt(cursor + 1)))) {
                kind = number();
            } else if (letter(ch) || ch == '#') {
                cursor++;
                while (cursor < source.length()
                        && (letter(source.charAt(cursor)) || identifierDigit(source.charAt(cursor)))) {
                    cursor++;
                }
                kind = WORDS.getOrDefault(source.substring(start, cursor), TokenKind.IDENTIFIER);
            } else {
                kind = operator();
            }
            tokens.add(new Token(kind, source.substring(start, cursor), value, start, cursor));
        }
        tokens.add(new Token(TokenKind.EOF, "", null, cursor, cursor));
        return List.copyOf(tokens);
    }

    private String string() {
        int start = cursor;
        char quote = source.charAt(cursor++);
        var value = new StringBuilder();
        while (cursor < source.length()) {
            char ch = source.charAt(cursor++);
            if (ch == quote) {
                return value.toString();
            }
            if (ch == '\\') {
                int escape = cursor - 1;
                if (cursor == source.length()) {
                    throw error("Unterminated string", start);
                }
                ch = source.charAt(cursor++);
                // Section 1.3 permits escaping either quote in either delimiter.
                if (ch != '\\' && ch != '\'' && ch != '"') {
                    throw error("Invalid string escape", escape);
                }
            }
            value.append(ch);
        }
        throw error("Unterminated string", start);
    }

    private TokenKind number() {
        consumeDigits();
        boolean floating = false;
        if (cursor < source.length() && source.charAt(cursor) == '.') {
            floating = true;
            cursor++;
            consumeDigits();
        }
        // Only consume an exponent when its mandatory digit is present.
        if (cursor < source.length() && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
            int end = cursor + 1;
            if (end < source.length() && (source.charAt(end) == '+' || source.charAt(end) == '-')) {
                end++;
            }
            if (end < source.length() && digit(source.charAt(end))) {
                cursor = end;
                consumeDigits();
                floating = true;
            }
        }
        return floating ? TokenKind.FLOATING_POINT : TokenKind.INTEGER;
    }

    private void consumeDigits() {
        while (cursor < source.length() && digit(source.charAt(cursor))) {
            cursor++;
        }
    }

    private TokenKind operator() {
        int start = cursor;
        char ch = source.charAt(cursor++);
        return switch (ch) {
            case '.' -> TokenKind.DOT;
            case '(' -> TokenKind.LPAREN;
            case ')' -> TokenKind.RPAREN;
            case '[' -> TokenKind.LBRACKET;
            case ']' -> TokenKind.RBRACKET;
            case '{' -> TokenKind.LBRACE;
            case '}' -> TokenKind.RBRACE;
            case ',' -> TokenKind.COMMA;
            case ':' -> TokenKind.COLON;
            case ';' -> TokenKind.SEMICOLON;
            case '?' -> TokenKind.QUESTION;
            case '*' -> TokenKind.MULTIPLY;
            case '/' -> TokenKind.DIVIDE;
            case '%' -> TokenKind.MODULO;
            case '+' -> take('=') ? TokenKind.CONCAT : TokenKind.PLUS;
            case '-' -> take('>') ? TokenKind.ARROW : TokenKind.MINUS;
            case '=' -> take('=') ? TokenKind.EQUAL : TokenKind.ASSIGN;
            case '!' -> take('=') ? TokenKind.NOT_EQUAL : TokenKind.NOT;
            case '<' -> take('=') ? TokenKind.LESS_EQUAL : TokenKind.LESS;
            case '>' -> take('=') ? TokenKind.GREATER_EQUAL : TokenKind.GREATER;
            case '&' -> {
                if (!take('&')) {
                    throw error("Illegal character", start);
                }
                yield TokenKind.AND;
            }
            case '|' -> {
                if (!take('|')) {
                    throw error("Illegal character", start);
                }
                yield TokenKind.OR;
            }
            default -> throw error("Illegal character", start);
        };
    }

    private boolean take(char expected) {
        if (cursor < source.length() && source.charAt(cursor) == expected) {
            cursor++;
            return true;
        }
        return false;
    }

    private static boolean digit(char ch) {
        return ch >= '0' && ch <= '9';
    }

    // Use the collected grammar's ranges, independently of the JVM's Unicode version.
    private static boolean letter(char ch) {
        return ch == '$' || ch == '_' || between(ch, 0x41, 0x5a) || between(ch, 0x61, 0x7a)
                || between(ch, 0xc0, 0xd6) || between(ch, 0xd8, 0xf6) || between(ch, 0xf8, 0xff)
                || between(ch, 0x100, 0x1fff) || between(ch, 0x3040, 0x318f)
                || between(ch, 0x3300, 0x337f) || between(ch, 0x3400, 0x3d2d)
                || between(ch, 0x4e00, 0x9fff) || between(ch, 0xf900, 0xfaff);
    }

    private static boolean identifierDigit(char ch) {
        return digit(ch) || between(ch, 0x660, 0x669) || between(ch, 0x6f0, 0x6f9)
                || between(ch, 0x966, 0x96f) || between(ch, 0x9e6, 0x9ef)
                || between(ch, 0xa66, 0xa6f) || between(ch, 0xae6, 0xaef)
                || between(ch, 0xb66, 0xb6f) || between(ch, 0xbe7, 0xbef)
                || between(ch, 0xc66, 0xc6f) || between(ch, 0xce6, 0xcef)
                || between(ch, 0xd66, 0xd6f) || between(ch, 0xe50, 0xe59)
                || between(ch, 0xed0, 0xed9) || between(ch, 0x1040, 0x1049);
    }

    private static boolean between(char ch, int lower, int upper) {
        return ch >= lower && ch <= upper;
    }

    private static ELException error(String message, int offset) {
        return new ELException(message + " at offset " + offset);
    }
}

