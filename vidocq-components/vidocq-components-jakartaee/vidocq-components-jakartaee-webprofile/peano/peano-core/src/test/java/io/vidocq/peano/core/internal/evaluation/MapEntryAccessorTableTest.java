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
package io.vidocq.peano.core.internal.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import jakarta.el.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The built-in Map.Entry table against the API BeanELResolver, the reflective oracle
 * (AGENTS.md constraint 3), in allow mode.
 */
class MapEntryAccessorTableTest {
    private static final String POLICY = "peano.reflection";
    private final ExpressionFactory factory = ExpressionFactory.newInstance();
    private String previous;

    @BeforeEach
    void allow() {
        previous = System.getProperty(POLICY);
        System.setProperty(POLICY, "allow");
    }

    @AfterEach
    void restore() {
        if (previous == null) System.clearProperty(POLICY);
        else System.setProperty(POLICY, previous);
    }

    private static Map.Entry<String, Object> hashMapEntry() {
        var map = new HashMap<String, Object>();
        map.put("b", 2);
        return map.entrySet().iterator().next();
    }

    private static List<Supplier<Map.Entry<String, Object>>> entries() {
        return List.of(MapEntryAccessorTableTest::hashMapEntry,
                () -> Map.<String, Object>of("a", 1).entrySet().iterator().next(),
                () -> new AbstractMap.SimpleEntry<>("s", 3));
    }

    @FunctionalInterface
    private interface Operation {
        Object apply(ELResolver resolver, ELContext context, Object base);
    }

    /** One operation's result, resolution flag and exception type. */
    private record Outcome(Object value, boolean resolved, Class<?> failure) {}

    private Outcome outcome(ELResolver resolver, Object base, Operation operation) {
        var context = new StandardELContext(factory);
        try {
            Object value = operation.apply(resolver, context, base);
            return new Outcome(value, context.isPropertyResolved(), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, context.isPropertyResolved(), failure.getClass());
        }
    }

    private void same(Supplier<Map.Entry<String, Object>> entry, String label, Operation operation) {
        Map.Entry<String, Object> direct = entry.get();
        Map.Entry<String, Object> reflective = entry.get();
        Outcome table = outcome(new GeneratedAccessorELResolver(List.of()), direct, operation);
        Outcome oracle = outcome(new BeanELResolver(), reflective, operation);
        String where = direct.getClass().getName() + " " + label;
        assertTrue(table.resolved() || table.failure() != null, where + " unresolved by the table");
        assertEquals(oracle.failure(), table.failure(), where);
        assertEquals(oracle.value(), table.value(), where);
        assertEquals(reflective, direct, where + " side effects");
    }

    @Test
    void matchesBeanELResolverForPropertiesAndMethods() {
        for (var entry : entries()) {
            for (String property : List.of("key", "value")) {
                same(entry, "getValue " + property, (resolver, context, base) -> resolver.getValue(context, base, property));
                same(entry, "getType " + property, (resolver, context, base) -> resolver.getType(context, base, property));
                same(entry, "isReadOnly " + property, (resolver, context, base) -> resolver.isReadOnly(context, base, property));
                same(entry, "setValue " + property, (resolver, context, base) -> {
                    resolver.setValue(context, base, property, 9);
                    return null;
                });
            }
            same(entry, "getKey()", (resolver, context, base) -> resolver.invoke(context, base, "getKey", null, new Object[0]));
            same(entry, "getValue()", (resolver, context, base) -> resolver.invoke(context, base, "getValue", null, new Object[0]));
            same(entry, "setValue(9)", (resolver, context, base) ->
                    resolver.invoke(context, base, "setValue", null, new Object[]{9}));
        }
    }

    @Test
    void leavesOtherMethodShapesToTheRestOfTheChain() {
        var resolver = new GeneratedAccessorELResolver(List.of());
        var context = new StandardELContext(factory);
        var entry = new LocalizedEntry();
        assertNull(resolver.invoke(context, entry, "getValue", null, new Object[]{"fr"}));
        assertFalse(context.isPropertyResolved());
        assertNull(resolver.invoke(context, entry, "setValue", null, new Object[]{1, 2}));
        assertFalse(context.isPropertyResolved());
        assertNull(resolver.invoke(context, entry, "getKey", new Class<?>[]{String.class}, new Object[]{"x"}));
        assertFalse(context.isPropertyResolved());
        assertNull(GeneratedAccessorELResolver.reference(context, entry, "getValue",
                new Class<?>[]{String.class}, new Object[]{"fr"}));
        assertNotNull(GeneratedAccessorELResolver.reference(context, entry, "getValue", null, new Object[0]));
        assertEquals((Object) 1, resolver.invoke(context, entry, "getValue", null, new Object[0]));
        assertTrue(context.isPropertyResolved());
    }

    // The allow-mode comparison with BeanELResolver for an application overload needs a package
    // exported to jakarta.el: JavaModuleBootstrapTest runs it in the entry-overload module fixture.

    public static final class LocalizedEntry implements Map.Entry<String, Integer> {
        @Override public String getKey() { return "k"; }
        @Override public Integer getValue() { return 1; }
        public String getValue(String locale) { return locale + ":" + getValue(); }
        @Override public Integer setValue(Integer value) { throw new UnsupportedOperationException(); }
    }
}
