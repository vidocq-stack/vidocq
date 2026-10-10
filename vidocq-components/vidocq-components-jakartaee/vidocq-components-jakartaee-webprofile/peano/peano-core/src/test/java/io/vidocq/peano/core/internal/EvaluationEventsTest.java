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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class EvaluationEventsTest {
    static class Events extends EvaluationListener {
        final List<String> calls = new ArrayList<>();
        @Override public void beforeEvaluation(ELContext context, String expression) { calls.add("before:"+expression); }
        @Override public void afterEvaluation(ELContext context, String expression) { calls.add("after:"+expression); }
        @Override public void propertyResolved(ELContext context, Object base, Object property) { calls.add("property:"+property); }
    }

    @Test
    void bracketsSuccessfulValueEvaluationIncludingFinalConversion() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        var events = new Events();
        context.addEvaluationListener(events);
        var expression = factory.createValueExpression(context, "${1+2}", String.class);
        assertEquals("3", expression.getValue(context));
        assertEquals(List.of("before:${1+2}", "after:${1+2}"), events.calls);
        events.calls.clear();
        expression.getType(context);
        expression.isReadOnly(context);
        expression.getValueReference(context);
        assertEquals(List.of(), events.calls);
    }

    @Test
    void leavesPropertyNotificationsToResolversAndDoesNotDuplicateThem() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        var model = new java.util.HashMap<>(Map.of("name","Peano"));
        context.getVariableMapper().setVariable("model", factory.createValueExpression(model, Object.class));
        var events = new Events();
        context.addEvaluationListener(events);
        var expression = factory.createValueExpression(context, "${model.name}", Object.class);
        assertEquals("Peano", expression.getValue(context));
        assertEquals(List.of("before:${model.name}", "property:name", "after:${model.name}"), events.calls);
        events.calls.clear();
        expression.setValue(context, "changed");
        assertFalse(events.calls.stream().anyMatch(x->x.startsWith("before:") || x.startsWith("after:")));
        assertEquals("changed", model.get("name"));
    }

    @Test
    void bracketsMethodInvocationAndReferenceButNotSignatureLookup() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("text", factory.createValueExpression("Peano", Object.class));
        var expression = factory.createMethodExpression(context, "#{text.substring(1,3)}", String.class, null);
        var events = new Events();
        context.addEvaluationListener(events);
        assertEquals("ea", expression.invoke(context, null));
        assertEquals("before:#{text.substring(1,3)}", events.calls.getFirst());
        assertEquals("after:#{text.substring(1,3)}", events.calls.getLast());
        events.calls.clear();
        assertNotNull(expression.getMethodReference(context));
        assertEquals("before:#{text.substring(1,3)}", events.calls.getFirst());
        assertEquals("after:#{text.substring(1,3)}", events.calls.getLast());
        events.calls.clear();
        expression.getMethodInfo(context);
        assertFalse(events.calls.stream().anyMatch(x->x.startsWith("before:") || x.startsWith("after:")));
    }

    @Test
    void failedEvaluationDoesNotReportSuccessfulCompletion() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        var events = new Events();
        context.addEvaluationListener(events);
        var expression = factory.createValueExpression(context, "${missing}", Object.class);
        assertThrows(PropertyNotFoundException.class, ()->expression.getValue(context));
        assertEquals(List.of("before:${missing}"), events.calls);
        events.calls.clear();
        var conversion = factory.createValueExpression(context, "${'not-a-number'}", int.class);
        assertThrows(ELException.class, ()->conversion.getValue(context));
        assertEquals(List.of("before:${'not-a-number'}"), events.calls);
        assertFalse(context.isLambdaArgument("x"));
    }

    @Test
    void methodAliasesNestInvocationEventsAndFailuresDoNotComplete() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("text", factory.createValueExpression("Peano", Object.class));
        var target = factory.createMethodExpression(context, "#{text.substring(1,3)}", String.class, null);
        context.getVariableMapper().setVariable("method", factory.createValueExpression(target, Object.class));
        var alias = factory.createMethodExpression(context, "#{method}", String.class, new Class<?>[0]);
        var events = new Events();
        context.addEvaluationListener(events);
        assertEquals("ea", alias.invoke(context, null));
        assertEquals(List.of("before:#{method}", "before:#{text.substring(1,3)}",
                "after:#{text.substring(1,3)}", "after:#{method}"),
                events.calls.stream().filter(x->!x.startsWith("property:")).toList());
        events.calls.clear();
        var missing = factory.createMethodExpression(context, "#{text.missing()}", null, null);
        assertThrows(ELException.class, ()->missing.invoke(context, null));
        assertEquals("before:#{text.missing()}", events.calls.getFirst());
        assertFalse(events.calls.stream().anyMatch(x->x.startsWith("after:")));
        events.calls.clear();
        assertThrows(ELException.class, ()->missing.getMethodReference(context));
        assertEquals(List.of("before:#{text.missing()}"), events.calls);
    }

    @Test
    void standaloneProcessorUsesItsManagersListenerContext() {
        var processor = new ELProcessor();
        var events = new Events();
        processor.getELManager().getELContext().addEvaluationListener(events);
        assertEquals(3L, processor.<Object>eval("1+2"));
        assertEquals(List.of("before:${1+2}", "after:${1+2}"), events.calls);
    }
}

