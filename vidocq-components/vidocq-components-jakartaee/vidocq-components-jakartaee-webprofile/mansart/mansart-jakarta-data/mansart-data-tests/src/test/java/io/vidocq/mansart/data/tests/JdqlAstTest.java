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
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.JdqlAst;
import io.vidocq.mansart.data.core.JdqlAst.ParseException;
import io.vidocq.mansart.data.core.JdqlAst.Stmt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The run-time JDQL parser: the statement tree it builds, and the errors it reports. */
class JdqlAstTest {

    private static final String ENTITY = "Gadget";
    private static final String NAME = "name";
    private static final String STOCK = "stock";
    private static final Set<String> ATTRIBUTES = Set.of(NAME, "price", STOCK, "active", "category");

    private static Stmt parse(String jdql) {
        return JdqlAst.parse(jdql, ATTRIBUTES, ENTITY);
    }

    @Test
    void parsesLiteralsOfEveryKind() {
        var and = (JdqlAst.And) parse("WHERE price >= 1.5d AND stock <> 10L AND name = 'it''s' AND active = TRUE"
                + " AND stock != 3").where;

        assertThat(and.children()).hasSize(5).allSatisfy(p -> assertThat(p).isInstanceOf(JdqlAst.Cmp.class));
        var literals = and.children().stream().map(p -> ((JdqlAst.Cmp) p).arg().literal).toList();
        assertThat(literals).containsExactly(1.5, 10, "it's", true, 3);
        assertThat(((JdqlAst.Cmp) and.children().get(1)).op()).isEqualTo(JdqlAst.Op.NE);
    }

    @Test
    void parsesInfixNegationsAndFunctionPredicates() {
        var or = (JdqlAst.Or) parse("WHERE name NOT LIKE :p OR stock NOT BETWEEN ?1 AND ?2 OR category NOT IN ('a', 'b')"
                + " OR UPPER(name) IS NOT NULL OR LOWER(category) NOT IN :cats OR (ABS(stock) < 5)").where;

        assertThat(or.children()).hasSize(6);
        assertThat(or.children().subList(0, 3)).allSatisfy(p -> assertThat(p).isInstanceOf(JdqlAst.Not.class));
        assertThat(or.children().get(3)).isEqualTo(new JdqlAst.FnIsNull("UPPER", NAME, true));
        assertThat(or.children().get(4)).isInstanceOf(JdqlAst.Not.class);
        assertThat(or.children().get(5)).isInstanceOf(JdqlAst.FnCmp.class);
    }

    @Test
    void parsesSetExpressions() {
        Stmt stmt = parse("UPDATE Gadget SET price = (price - 1) / 2, name = CONCAT(name, '-', :suffix), stock = stock * 3"
                + " WHERE active = true");

        assertThat(stmt.kind).isEqualTo(Stmt.Kind.UPDATE);
        assertThat(stmt.setAssignments).extracting(JdqlAst.SetAssign::attr).containsExactly("price", NAME, STOCK);
        assertThat(stmt.setAssignments.get(0).value()).isInstanceOf(JdqlAst.ExprBin.class);
        assertThat(stmt.setAssignments.get(1).value()).isInstanceOf(JdqlAst.ExprFunc.class);
        assertThat(parse("DELETE FROM Gadget WHERE stock = 0").kind).isEqualTo(Stmt.Kind.DELETE);
        assertThat(parse("DELETE WHERE stock = 0").kind).isEqualTo(Stmt.Kind.DELETE);
    }

    @Test
    void parsesSelectClauses() {
        assertThat(parse("SELECT COUNT(*) FROM Gadget").kind).isEqualTo(Stmt.Kind.COUNT);
        assertThat(parse("SELECT COUNT(this)").kind).isEqualTo(Stmt.Kind.COUNT);
        Stmt avg = parse("SELECT AVG(price) WHERE active = true");
        assertThat(avg.kind).isEqualTo(Stmt.Kind.AGGREGATE);
        assertThat(avg.aggregateOp).isEqualTo("AVG");
        assertThat(parse("SELECT name, stock ORDER BY name DESC").projectAttrs).containsExactly(NAME, STOCK);
        assertThat(parse("SELECT this FROM Gadget ORDER BY stock ASC, name").orderBy).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "WHERE name ! 'x'                      | Unexpected '!'",
            "WHERE name = :                        | Empty named parameter",
            "WHERE name = ?                        | must be followed by digits",
            "WHERE name = 'open                    | Unterminated string literal",
            "WHERE name = #                        | Unexpected '#'",
            "SELECT COUNT name                     | Expected '(' after COUNT",
            "SELECT COUNT(name)                    | Expected COUNT(*) or COUNT(this)",
            "SELECT COUNT(* name                   | Expected ')' after COUNT",
            "SELECT SUM price                      | Expected '('",
            "SELECT SUM(price                      | Expected ')'",
            "SELECT WHERE                          | Unexpected token after SELECT",
            "UPDATE Other SET name = 'x'           | UPDATE Other",
            "DELETE FROM Other                     | DELETE FROM Other",
            "UPDATE Gadget SET name 'x'            | Expected '=' in SET",
            "UPDATE Gadget SET name = (stock       | Expected ')'",
            "UPDATE Gadget SET name = UPPER(name   | Expected ')' after UPPER",
            "UPDATE Gadget SET name = owner        | Unknown attribute 'owner' in SET expression",
            "UPDATE Gadget SET name = UPPER(name, name) | UPPER expects 1 argument",
            "UPDATE Gadget SET name = CONCAT(name) | CONCAT expects 2+ arguments",
            "WHERE (name = 'x'                     | Expected ')'",
            "WHERE UPPER(name = 'x'                | Expected ')' after UPPER",
            "WHERE UPPER = 'x'                     | Unknown attribute 'UPPER'",
            "WHERE name NOT = 'x'                  | Unexpected NOT after 'name'",
            "WHERE name IN ('a' 'b')               | Expected ')'",
            "WHERE name >                          | Expected literal, :name, or ?N",
            "WHERE name 'x'                        | Expected operator after 'name'",
            "WHERE owner = 'x'                     | Unknown attribute 'owner'",
            "FROM Gadget WHERE                     | Expected"
    })
    void reportsSyntaxErrors(String jdql, String message) {
        assertThatThrownBy(() -> parse(jdql)).isInstanceOf(ParseException.class).hasMessageContaining(message);
    }
}
