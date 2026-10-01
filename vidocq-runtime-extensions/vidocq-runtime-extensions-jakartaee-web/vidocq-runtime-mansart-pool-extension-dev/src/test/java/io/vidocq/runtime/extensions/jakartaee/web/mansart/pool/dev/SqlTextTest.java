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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.dev;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the pools panel reads of a SQL text before it runs it (SQL spec §4.2), never in a string or a comment. */
class SqlTextTest {

    @Test
    void theFirstWordSkipsBlanksCommentsAndOpeningParentheses() {
        assertEquals("SELECT", SqlText.firstWord("  select 1", '"'));
        assertEquals("DELETE", SqlText.firstWord("/* SELECT */ -- SELECT\n delete from tasks", '"'),
                "a write hidden after comments");
        assertEquals("SELECT", SqlText.firstWord("(SELECT 1) UNION (SELECT 2)", '"'));
        assertEquals("WITH", SqlText.firstWord("WITH x AS (DELETE FROM tasks RETURNING *) SELECT * FROM x", '"'));
        assertEquals("", SqlText.firstWord("'SELECT'", '"'), "a string is no word");
        assertEquals("", SqlText.firstWord("-- only a comment", '"'));
    }

    @Test
    void aSemicolonInAStringAnIdentifierOrACommentOrAtTheEndIsNoSecondStatement() {
        assertFalse(SqlText.severalStatements("SELECT ';' AS \"a;b\" -- ;\n FROM t /* ; */;  -- done", '"'));
        assertFalse(SqlText.severalStatements("SELECT 'it''s; fine'", '"'), "a doubled quote stays in the string");
        assertTrue(SqlText.severalStatements("SELECT 1; DELETE FROM tasks", '"'));
        assertTrue(SqlText.severalStatements("SELECT 1;;", '"'));
        assertTrue(SqlText.severalStatements("SELECT `a;` FROM t; DROP TABLE t", '`'), "MySQL's quote");
        assertFalse(SqlText.severalStatements("SELECT `a;` FROM t", '`'));
    }

    @Test
    void aNamedParameterBecomesAQuestionMarkInTheCodeOnly() {
        SqlText.Named named = SqlText.named("SELECT :a, ':b', \"c:d\", x::text, :_e1 -- :f\n/* :g */ WHERE y = :a",
                '"');

        assertEquals("SELECT ?, ':b', \"c:d\", x::text, ? -- :f\n/* :g */ WHERE y = ?", named.sql());
        assertEquals(List.of("a", "_e1", "a"), named.names(), "one name per question mark, in order");
        assertEquals(List.of(), SqlText.named("SELECT 1 :: int, :1", '"').names(), "no cast, no number is a name");
    }
}
