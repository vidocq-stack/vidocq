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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import jakarta.el.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Map entries under reflection denial (Vidocq/bodoni BOD-001): JDK entry classes are private,
 * so only the built-in Map.Entry table, reached by supertype lookup, can serve them.
 */
class MapEntryAccessTest {
    private static final String POLICY = "peano.reflection";
    private final ExpressionFactory factory = ExpressionFactory.newInstance();
    private final StandardELContext context = new StandardELContext(factory);
    private String previous;

    @BeforeEach
    void deny() {
        previous = System.getProperty(POLICY);
        System.setProperty(POLICY, "deny");
    }

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty(POLICY);
        else System.setProperty(POLICY, previous);
    }

    private ValueExpression expression(Object entry, String text) {
        context.getVariableMapper().setVariable("e", factory.createValueExpression(entry, Object.class));
        return factory.createValueExpression(context, text, Object.class);
    }

    private Object eval(Object entry, String text) {
        return expression(entry, text).getValue(context);
    }

    private static <K, V> Map.Entry<K, V> only(Map<K, V> map) {
        return map.entrySet().iterator().next();
    }

    @Test
    void readsKeyAndValueOfPrivateJdkEntries() {
        var mutable = new HashMap<String, Integer>();
        mutable.put("b", 2);
        assertEquals("a=1", eval(only(Map.of("a", 1)), "${e.key}=${e.value}"));
        assertEquals("b=2", eval(only(mutable), "${e.key}=${e.value}"));
        assertEquals("b=2", eval(only(Collections.unmodifiableMap(mutable)), "${e.key}=${e.value}"));
        assertEquals("c=3", eval(Map.entry("c", 3), "${e['key']}=${e['value']}"));
    }

    @Test
    void invokesTheEntryAccessorMethods() {
        var entry = only(Map.of("a", 1));
        assertEquals("a", eval(entry, "${e.getKey()}"));
        assertEquals(1, eval(entry, "${e.getValue()}"));
    }

    @Test
    void keyAndValueAreReadOnlyLikeBeanELResolver() {
        // Map.Entry.setValue returns V: not a JavaBeans setter, so value is read-only (Vidocq/bodoni BOD-001 review I1).
        var map = new HashMap<String, Object>();
        map.put("b", 2);
        for (String property : new String[]{"key", "value"}) {
            var expression = expression(only(map), "${e." + property + "}");
            assertTrue(expression.isReadOnly(context), property);
            assertNull(expression.getType(context), property);
            assertThrows(PropertyNotWritableException.class, () -> expression.setValue(context, 5), property);
        }
        var unmodifiable = expression(only(Collections.unmodifiableMap(map)), "${e.value}");
        assertTrue(unmodifiable.isReadOnly(context));
        assertThrows(PropertyNotWritableException.class, () -> unmodifiable.setValue(context, 5));
        assertEquals(2, map.get("b"));
    }

    @Test
    void setValueStaysCallableAsAMethod() {
        var map = new HashMap<String, Object>();
        map.put("b", 2);
        assertEquals(2, eval(only(map), "${e.setValue(5)}"));
        assertEquals(5L, map.get("b"));
    }

    @Test
    void typesWithoutATableStillHitTheDenyGuard() {
        var plain = new Plain();
        var failure = assertThrows(ELException.class, () -> eval(plain, "${e.name}"));
        assertEquals("Reflection fallback denied for " + Plain.class.getName()
                + "; register a generated accessor provider or a custom resolver", failure.getMessage());
        var entry = only(Map.of("a", 1));
        var unknown = assertThrows(ELException.class, () -> eval(entry, "${e.other}"));
        assertEquals("Reflection fallback denied for " + entry.getClass().getName()
                + "; register a generated accessor provider or a custom resolver", unknown.getMessage());
    }

    @Test
    void commonPropertyTypeIsMetadataNotMemberAccess() {
        // EL-020: CompositeELResolver asks every resolver, so the deny guard runs after the table answered.
        var entry = only(Map.of("a", 1));
        assertEquals(Object.class, context.getELResolver().getCommonPropertyType(context, entry));
        assertEquals(Object.class, context.getELResolver().getCommonPropertyType(context, new Plain()));
    }

    public static final class Plain {
        public String getName() { return "plain"; }
    }
}
