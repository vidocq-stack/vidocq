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
package io.vidocq.ravel.internal;

import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MP Config 3.1 §5.2 — implicit (automatic) converter détecté via patterns
 * publics (of, valueOf, parse, ctor).
 */
@DisplayName("ImplicitConverter — patterns §5.2 (of/valueOf/parse/(String))")
class ImplicitConverterTest {

    // ---------- patterns §5.2 ----------

    @Test
    void detects_static_of_string() {
        Converter<WithOf> c = ImplicitConverter.create(WithOf.class);
        assertNotNull(c);
        assertEquals("of:hi", c.convert("hi").value);
    }

    @Test
    void detects_static_valueOf_string() {
        Converter<WithValueOf> c = ImplicitConverter.create(WithValueOf.class);
        assertNotNull(c);
        assertEquals("valueOf:hi", c.convert("hi").value);
    }

    @Test
    void detects_static_parse_charsequence() {
        Converter<WithParse> c = ImplicitConverter.create(WithParse.class);
        assertNotNull(c);
        assertEquals("parse:hi", c.convert("hi").value);
    }

    @Test
    void detects_string_constructor() {
        Converter<WithCtor> c = ImplicitConverter.create(WithCtor.class);
        assertNotNull(c);
        assertEquals("ctor:hi", c.convert("hi").value);
    }

    @Test
    void priority_order_of_then_valueOf_then_parse_then_ctor() {
        // Si plusieurs patterns coexistent, l'ordre §5.2 doit être respecté :
        // of > valueOf > parse > (String).
        Converter<AllPatterns> cAll = ImplicitConverter.create(AllPatterns.class);
        assertNotNull(cAll);
        assertEquals("of", cAll.convert("x").pattern);

        // Sans 'of', valueOf gagne.
        Converter<NoOf> cNoOf = ImplicitConverter.create(NoOf.class);
        assertEquals("valueOf", cNoOf.convert("x").pattern);

        // Sans of/valueOf, parse gagne.
        Converter<OnlyParseAndCtor> cParse = ImplicitConverter.create(OnlyParseAndCtor.class);
        assertEquals("parse", cParse.convert("x").pattern);

        // Sans rien d'autre, ctor (String) reste.
        Converter<WithCtor> cCtor = ImplicitConverter.create(WithCtor.class);
        assertEquals("ctor:x", cCtor.convert("x").value);
    }

    // ---------- enum §5.2 ----------

    @Test
    void detects_enum() {
        Converter<Color> c = ImplicitConverter.create(Color.class);
        assertNotNull(c);
        assertEquals(Color.RED, c.convert("RED"));
        assertEquals(Color.GREEN, c.convert("GREEN"));
    }

    @Test
    void enum_invalid_value_throws_IAE() {
        Converter<Color> c = ImplicitConverter.create(Color.class);
        assertThrows(IllegalArgumentException.class, () -> c.convert("PURPLE"));
    }

    // ---------- types JDK courants ----------

    @Test
    void uuid_resolves_via_fromString_no_but_via_valueOf_no_but_via_parse() {
        // UUID a fromString(String) qui n'est pas dans les 4 patterns ; mais UUID a
        // une méthode statique factory ? Non, UUID n'a que fromString. On documente
        // ce cas : un converter custom est nécessaire.
        Converter<UUID> c = ImplicitConverter.create(UUID.class);
        // UUID n'a aucun des 4 patterns publics § 5.2 → pas de converter implicite.
        assertNull(c);
    }

    // ---------- non-applicable ----------

    @Test
    void returns_null_for_object_class() {
        // Object n'a aucun des patterns.
        assertNull(ImplicitConverter.create(Object.class));
    }

    @Test
    void returns_null_for_array_or_primitive() {
        assertNull(ImplicitConverter.create(int.class));
        assertNull(ImplicitConverter.create(int[].class));
    }

    @Test
    void empty_string_returns_null() {
        Converter<WithOf> c = ImplicitConverter.create(WithOf.class);
        assertNotNull(c);
        assertNull(c.convert(""));
    }

    // ---------- fixtures ----------

    public static final class WithOf {
        final String value;
        private WithOf(String v) { this.value = v; }
        public static WithOf of(String s) { return new WithOf("of:" + s); }
    }

    public static final class WithValueOf {
        final String value;
        private WithValueOf(String v) { this.value = v; }
        public static WithValueOf valueOf(String s) { return new WithValueOf("valueOf:" + s); }
    }

    public static final class WithParse {
        final String value;
        private WithParse(String v) { this.value = v; }
        public static WithParse parse(CharSequence s) { return new WithParse("parse:" + s); }
    }

    public static final class WithCtor {
        final String value;
        public WithCtor(String s) { this.value = "ctor:" + s; }
    }

    public static final class AllPatterns {
        public final String pattern;
        // Constructeur public (String) — pattern §5.2 #4
        public AllPatterns(String s) { this.pattern = "ctor"; }
        // Constructeur privé pour les factories — esquive la rentrée par le ctor public.
        private AllPatterns(String marker, boolean tag) { this.pattern = marker; }
        public static AllPatterns of(String s)          { return new AllPatterns("of", true); }
        public static AllPatterns valueOf(String s)     { return new AllPatterns("valueOf", true); }
        public static AllPatterns parse(CharSequence s) { return new AllPatterns("parse", true); }
    }

    public static final class NoOf {
        public final String pattern;
        public NoOf(String s) { this.pattern = "ctor"; }
        private NoOf(String marker, boolean tag) { this.pattern = marker; }
        public static NoOf valueOf(String s)     { return new NoOf("valueOf", true); }
        public static NoOf parse(CharSequence s) { return new NoOf("parse", true); }
    }

    public static final class OnlyParseAndCtor {
        public final String pattern;
        public OnlyParseAndCtor(String s) { this.pattern = "ctor"; }
        private OnlyParseAndCtor(String marker, boolean tag) { this.pattern = marker; }
        public static OnlyParseAndCtor parse(CharSequence s) { return new OnlyParseAndCtor("parse", true); }
    }

    public enum Color { RED, GREEN, BLUE }
}



