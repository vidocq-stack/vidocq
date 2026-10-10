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
import java.io.*;
import java.util.List;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class ExpressionSerializationTest {
    @SuppressWarnings("unchecked")
    static <T> T roundTrip(T expression) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(expression); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) input.readObject();
        }
    }

    @Test
    void roundTripsConstantCompositeAndCollectionPipelineExpressions() throws Exception {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        for (String source : List.of("literal", "${1+2}", "sum=${1+2}",
                "${[1,2,3].stream().map(x->x*2).toList()}")) {
            var original = factory.createValueExpression(context, source, Object.class);
            ValueExpression copy = roundTrip(original);
            assertEquals(original, copy);
            assertEquals(original.hashCode(), copy.hashCode());
            assertEquals(original.<Object>getValue(context), copy.<Object>getValue(new StandardELContext(factory)));
            assertEquals(original.getExpressionString(), copy.getExpressionString());
            assertEquals(original.isLiteralText(), copy.isLiteralText());
        }
    }

    @Test
    void retainsCapturedVariableBindingsAndUsesTheNewEvaluationContext() throws Exception {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("x", factory.createValueExpression(7L, Object.class));
        var original = factory.createValueExpression(context, "${x+1}", Object.class);
        context.getVariableMapper().setVariable("x", factory.createValueExpression(99L, Object.class));
        ValueExpression copy = roundTrip(original);
        assertEquals(original, copy);
        var fresh = new StandardELContext(factory);
        fresh.getVariableMapper().setVariable("x", factory.createValueExpression(100L, Object.class));
        assertEquals(8L, copy.<Object>getValue(fresh));
        var events = new EvaluationEventsTest.Events();
        fresh.addEvaluationListener(events);
        copy.getValue(fresh);
        assertEquals(List.of("before:${x+1}", "after:${x+1}"), events.calls);
    }

    @Test
    void retainsPublicStaticFunctionDescriptorsIncludingOverloads() throws Exception {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        context.getFunctionMapper().mapFunction("f","pick", Math.class.getMethod("max", int.class,int.class));
        var original = factory.createValueExpression(context, "${f:pick(3,7)}", Object.class);
        context.getFunctionMapper().mapFunction("f","pick", Math.class.getMethod("min", long.class,long.class));
        ValueExpression copy = roundTrip(original);
        assertEquals(original, copy);
        assertEquals(7, copy.<Object>getValue(new StandardELContext(factory)));
    }

    @Test
    void roundTripsInlineBareLiteralAndAliasedMethodExpressions() throws Exception {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("text", factory.createValueExpression("Peano",Object.class));
        var inline = factory.createMethodExpression(context, "#{text.substring(1,3)}", String.class, null);
        var bare = factory.createMethodExpression(context, "#{text.substring}", String.class, new Class<?>[]{int.class,int.class});
        var literal = factory.createMethodExpression(context, "42", long.class, new Class<?>[0]);
        context.getVariableMapper().setVariable("method", factory.createValueExpression(inline, Object.class));
        var alias = factory.createMethodExpression(context, "#{method}", String.class, new Class<?>[0]);
        for (var original : List.of(inline,bare,literal,alias)) {
            MethodExpression copy = roundTrip(original);
            var fresh = new StandardELContext(factory);
            Object[] args = new Object[]{1,3};
            assertEquals(original, copy);
            assertEquals(original.hashCode(), copy.hashCode());
            assertEquals(original.isParametersProvided(), copy.isParametersProvided());
            assertEquals(original.invoke(context,args),copy.invoke(fresh,args));
            assertEquals(original.getMethodInfo(context).getReturnType(), copy.getMethodInfo(fresh).getReturnType());
            if (!copy.isLiteralText()) assertNotNull(copy.getMethodReference(fresh));
        }
    }

    @Test
    void roundTripsObjectAndNullLiteralsAndRejectsNonserializableValues() throws Exception {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        for (Object value : new Object[]{null, "Peano", List.of(1L,2L)}) {
            var original = factory.createValueExpression(value,Object.class);
            ValueExpression copy = roundTrip(original);
            assertEquals(original,copy);
            assertEquals(value,copy.getValue(context));
        }
        assertThrows(NotSerializableException.class,
                ()->roundTrip(factory.createValueExpression(new Object(),Object.class)));
    }
}

