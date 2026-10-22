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
package io.vidocq.peano.entryoverload;

import java.util.Map;
import java.util.Objects;
import jakarta.el.*;

/**
 * Allow mode: an application Map.Entry class with an extra getValue(String) overload behaves as with
 * the API BeanELResolver, although Peano's built-in Map.Entry table also matches it (Vidocq/bodoni BOD-001).
 */
public final class Main {
    public static void main(String[] args) {
        if (System.getProperty("peano.reflection") != null) throw new AssertionError("Fixture runs in allow mode");
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        var entry = new LocalizedEntry();
        context.getVariableMapper().setVariable("e", factory.createValueExpression(entry, Object.class));
        var oracle = new StandardELContext(factory);
        var bean = new BeanELResolver();
        check(bean.invoke(oracle, entry, "getValue", null, new Object[]{"fr"}),
                factory.createValueExpression(context, "${e.getValue('fr')}", Object.class).getValue(context), "getValue('fr')");
        check(bean.invoke(oracle, entry, "getValue", null, new Object[0]),
                factory.createValueExpression(context, "${e.getValue()}", Object.class).getValue(context), "getValue()");
        check(bean.getValue(oracle, entry, "label"),
                factory.createValueExpression(context, "${e.label}", Object.class).getValue(context), "label");
        var value = factory.createValueExpression(context, "${e.value}", Object.class);
        check(bean.getValue(oracle, entry, "value"), value.getValue(context), "value");
        check(bean.isReadOnly(oracle, entry, "value"), value.isReadOnly(context), "isReadOnly value");
        check(bean.getType(oracle, entry, "value"), value.getType(context), "getType value");
        if (!"fr:1".equals(factory.createValueExpression(context, "${e.getValue('fr')}", Object.class).getValue(context))) {
            throw new AssertionError("Overload did not reach the application method");
        }
        System.out.println("PEANO_ENTRY_OVERLOAD_OK");
    }

    private static void check(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": BeanELResolver gives " + expected + ", Peano gives " + actual);
        }
    }

    public static final class LocalizedEntry implements Map.Entry<String, Integer> {
        @Override public String getKey() { return "k"; }
        @Override public Integer getValue() { return 1; }
        public String getValue(String locale) { return locale + ":" + getValue(); }
        public String getLabel() { return "label"; }
        @Override public Integer setValue(Integer value) { throw new UnsupportedOperationException(); }
    }
}
