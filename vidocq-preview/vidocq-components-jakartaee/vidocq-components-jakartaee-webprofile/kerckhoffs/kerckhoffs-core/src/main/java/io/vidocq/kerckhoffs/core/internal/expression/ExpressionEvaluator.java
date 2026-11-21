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
package io.vidocq.kerckhoffs.core.internal.expression;

import jakarta.el.ELException;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;

import java.util.Map;

/**
 * Evaluates the {@code *Expression} attributes of the security annotations (Jakarta Security 4.0, section 3.8),
 * which are Jakarta Expression Language. Kerckhoffs depends on the {@code jakarta.el} API only: the engine is the one
 * {@link ExpressionFactory#newInstance()} finds, Peano under Vidocq.
 *
 * <p>An expression is evaluated at the moment it is needed, with the variables of that call (for a mechanism,
 * {@code self} and {@code httpMessageContext}); an expression that cannot be evaluated is refused, never read as a
 * default: a security decision does not fall back silently.
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    /**
     * Evaluates {@code expression} as a value of {@code type}.
     *
     * @param expression a {@code ${...}} or {@code #{...}} expression, or a text that is its own value
     * @param type the type of the result
     * @param variables the variables the expression may name
     * @param <T> the type of the result
     * @return the value of the expression
     * @throws IllegalArgumentException when the expression cannot be parsed or evaluated
     */
    public static <T> T evaluate(String expression, Class<T> type, Map<String, Object> variables) {
        ExpressionFactory factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        variables.forEach((name, value) ->
                context.getVariableMapper().setVariable(name, factory.createValueExpression(value, Object.class)));
        try {
            return type.cast(factory.createValueExpression(context, expression, type).getValue(context));
        } catch (ELException | ClassCastException e) {
            throw new IllegalArgumentException("The expression " + expression + " cannot be evaluated", e);
        }
    }
}
