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
package io.vidocq.runtime.core.config;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MicroProfile Config 3.1 property expressions (§7.2), as Ravel expands them (BUG-20261114-01). */
class PropertyExpressionsTest {

    @Test
    void aValueWithoutExpressionIsKeptAsWritten() {
        assertEquals("pa$$word", expand(Map.of(), "pa$$word"));
        assertEquals("plain", expand(Map.of(), "plain"));
    }

    @Test
    void anExpressionTakesTheValueOfItsKey() {
        assertEquals("orders", expand(Map.of("db.name", "orders"), "${db.name}"));
    }

    @Test
    void theDefaultAppliesWhenTheKeyHasNoValue() {
        assertEquals("8080", expand(Map.of(), "${DASHBOARD_PORT:8080}"));
        assertEquals("9090", expand(Map.of("DASHBOARD_PORT", "9090"), "${DASHBOARD_PORT:8080}"));
    }

    @Test
    void anEmptyDefaultGivesAnEmptyValue() {
        assertEquals("", expand(Map.of(), "${missing:}"));
    }

    @Test
    void textAndExpressionsMix() {
        Map<String, String> values = Map.of("host", "db.local");
        assertEquals("jdbc:postgresql://db.local:5432/orders",
                expand(values, "jdbc:postgresql://${host}:${port:5432}/orders"));
    }

    @Test
    void theValueOfTheKeyIsExpandedToo() {
        Map<String, String> values = Map.of("a", "${b}", "b", "${c}!", "c", "z");
        assertEquals("z!", expand(values, "${a}"));
    }

    @Test
    void anExpressionCanComposeTheKey() {
        Map<String, String> values = Map.of("target", "prod", "url.prod", "https://prod");
        assertEquals("https://prod", expand(values, "${url.${target}}"));
    }

    @Test
    void theDefaultCanHoldAnExpression() {
        assertEquals("fallback", expand(Map.of("other", "fallback"), "${missing:${other}}"));
    }

    @Test
    void aBackslashKeepsTheDollarLiteral() {
        assertEquals("${b}", expand(Map.of("b", "x"), "\\${b}"));
    }

    @Test
    void anExpressionWithoutDefaultWhoseKeyHasNoValueLeavesNoValue() {
        assertTrue(expressions(Map.of()).expand("k", "${missing}").isEmpty());
        assertTrue(expressions(Map.of("a", "${missing}")).expand("k", "x-${a}").isEmpty());
        assertTrue(expressions(Map.of()).expand("k", "${${missing}:default}").isEmpty());
    }

    @Test
    void aCircularExpressionIsRejected() {
        PropertyExpressions expressions = expressions(Map.of("a", "${b}", "b", "${a}"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> expressions.expand("a", "${b}"));
        assertEquals("Circular property expression: a -> b -> a", e.getMessage());
    }

    @Test
    void anExpressionWithoutItsClosingBraceIsRejected() {
        PropertyExpressions expressions = expressions(Map.of());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> expressions.expand("k", "x${port"));
        assertEquals("Unterminated property expression in: x${port", e.getMessage());
    }

    @Test
    void theMicroProfileKeyTurnsTheExpansionOff() {
        PropertyExpressions off = expressions(Map.of(PropertyExpressions.ENABLED_KEY, " FALSE ", "b", "x"));
        assertEquals(Optional.of("${b:8080}"), off.expand("k", "${b:8080}"));
        PropertyExpressions on = expressions(Map.of(PropertyExpressions.ENABLED_KEY, "true", "b", "x"));
        assertEquals(Optional.of("x"), on.expand("k", "${b:8080}"));
    }

    private static String expand(Map<String, String> values, String raw) {
        return expressions(values).expand("k", raw).orElseThrow();
    }

    private static PropertyExpressions expressions(Map<String, String> values) {
        return new PropertyExpressions(values::get);
    }
}
