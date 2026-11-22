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
package io.vidocq.runtime.config.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeyPatternTest {

    @Test
    void a_literal_key_matches_itself_only() {
        KeyPattern p = KeyPattern.parse("vidocq.devconsole.port");
        assertTrue(p.match("vidocq.devconsole.port").isPresent());
        assertTrue(p.match("vidocq.devconsole.host").isEmpty());
        assertTrue(p.match("vidocq.devconsole.port.x").isEmpty());
        assertTrue(p.match("vidocq.devconsole").isEmpty());
    }

    @Test
    void a_name_is_one_segment() {
        KeyPattern p = KeyPattern.parse("vidocq.chappe.listener.<listener>.port");
        assertEquals(Map.of("listener", "admin"),
                p.match("vidocq.chappe.listener.admin.port").orElseThrow().names());
        assertTrue(p.match("vidocq.chappe.listener.port").isEmpty());
        assertTrue(p.match("vidocq.chappe.listener.a.b.port").isEmpty());
        assertEquals(List.of("listener"), p.families());
    }

    @Test
    void a_quoted_name_may_hold_dots_and_is_never_a_literal() {
        KeyPattern p = KeyPattern.parse("vidocq.persistence.<unit>.datasource");
        assertEquals(Map.of("unit", "my.unit"),
                p.match("vidocq.persistence.\"my.unit\".datasource").orElseThrow().names());
        assertTrue(KeyPattern.parse("vidocq.pool.url").match("vidocq.pool.\"url\"").isEmpty());
    }

    @Test
    void a_key_with_an_unclosed_quote_matches_nothing() {
        KeyPattern p = KeyPattern.parse("vidocq.persistence.<unit>.datasource");
        assertTrue(p.match("vidocq.persistence.\"ledger.datasource").isEmpty());
        assertTrue(p.match("vidocq.persistence.\"ledger\"x.datasource").isEmpty());
    }

    @Test
    void a_key_with_an_empty_segment_matches_nothing() {
        KeyPattern p = KeyPattern.parse("vidocq.pool.[<datasource>].url");
        assertTrue(p.match("vidocq.pool..url").isEmpty());
        assertTrue(p.match("vidocq.pool.url.").isEmpty());
        assertTrue(p.match("").isEmpty());
        assertTrue(p.match(null).isEmpty());
    }

    @Test
    void an_optional_name_may_be_absent() {
        KeyPattern p = KeyPattern.parse("vidocq.pool.[<datasource>].url");
        assertEquals(Map.of(), p.match("vidocq.pool.url").orElseThrow().names());
        assertEquals(Map.of("datasource", "audit"), p.match("vidocq.pool.audit.url").orElseThrow().names());
        assertTrue(p.match("vidocq.pool.a.b.url").isEmpty());
        assertEquals(List.of("datasource"), p.families());
    }

    @Test
    void a_tail_matches_one_segment_or_more() {
        KeyPattern p = KeyPattern.parse("vidocq.persistence.<unit>.properties.*");
        KeyPattern.Match match = p.match("vidocq.persistence.ledger.properties.hibernate.show_sql").orElseThrow();
        assertTrue(match.tail());
        assertEquals(Map.of("unit", "ledger"), match.names());
        assertTrue(p.match("vidocq.persistence.ledger.properties").isEmpty());
    }

    @Test
    void a_literal_is_better_than_a_name_and_a_described_key_than_a_tail() {
        String key = "vidocq.http.mount.ui.type";
        KeyPattern.Match exact = KeyPattern.parse("vidocq.http.mount.ui.type").match(key).orElseThrow();
        KeyPattern.Match named = KeyPattern.parse("vidocq.http.mount.<mount>.type").match(key).orElseThrow();
        KeyPattern.Match tail = KeyPattern.parse("vidocq.http.mount.<mount>.*").match(key).orElseThrow();
        assertTrue(exact.compareTo(named) < 0);
        assertTrue(named.compareTo(tail) < 0);
        assertTrue(tail.compareTo(exact) > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "vidocq..port", "vidocq.<>.x", "vidocq.*.x", "vidocq.<a.x", "vidocq.po|ol",
            "vidocq.[x].y", "vidocq.a b", "<listener>.port", "*", "vidocq.\"a\".b", "vidocq.port."})
    void a_pattern_that_cannot_be_read_is_refused(String text) {
        IllegalArgumentException refused =
                assertThrows(IllegalArgumentException.class, () -> KeyPattern.parse(text));
        assertTrue(refused.getMessage().contains("'" + text + "'"), refused.getMessage());
    }

    @Test
    void the_same_family_twice_binds_its_first_name() {
        KeyPattern p = KeyPattern.parse("a.<x>.b.<x>");
        assertEquals(Map.of("x", "1"), p.match("a.1.b.2").orElseThrow().names());
        assertEquals(List.of("x"), p.families());
    }

    @Test
    void a_pattern_is_its_text() {
        KeyPattern p = KeyPattern.parse("vidocq.pool.[<datasource>].url");
        assertEquals("vidocq.pool.[<datasource>].url", p.text());
        assertEquals(p, KeyPattern.parse("vidocq.pool.[<datasource>].url"));
        assertEquals(p.hashCode(), KeyPattern.parse("vidocq.pool.[<datasource>].url").hashCode());
        assertEquals("vidocq.pool.[<datasource>].url", p.toString());
    }

    @Test
    void the_sibling_of_a_pattern_replaces_its_last_segment() {
        assertEquals(KeyPattern.parse("vidocq.http.mount.<mount>.type"),
                KeyPattern.parse("vidocq.http.mount.<mount>.classpath").sibling("type"));
    }
}
