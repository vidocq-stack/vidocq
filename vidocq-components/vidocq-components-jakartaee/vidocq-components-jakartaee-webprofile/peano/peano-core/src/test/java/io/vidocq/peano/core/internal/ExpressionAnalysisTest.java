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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.el.*;
import io.vidocq.peano.core.internal.evaluation.Bindings;
import org.junit.jupiter.api.Test;

class ExpressionAnalysisTest {
    @Test void capturesEachMapperInItsOriginalPhaseAndLookupOrder() {
        var analysis=ExpressionAnalysis.parse("\u0024{a+p:f(b)} text \u0024{b+p:f(a)+q:g(a)}");
        var context=new TrackingContext();
        var variables=Bindings.capture(analysis,context);
        var functions=Bindings.captureFunctions(analysis,context);
        assertEquals(List.of("getVariables","v:a","v:b","getFunctions","f:p:f","getFunctions","f:q:g"),context.calls);
        assertEquals(2,variables.size());
        assertEquals(context.abs,functions.get("p:f"));
        assertEquals(context.max,functions.get("q:g"));
        assertThrows(UnsupportedOperationException.class,variables::clear);
        assertThrows(UnsupportedOperationException.class,functions::clear);
    }

    @Test void excludesNestedLambdaParametersFromVariableAndFunctionBindings() {
        var analysis=ExpressionAnalysis.parse("\u0024{(x -> (y -> x(y)+a))(b)}");
        var context=new TrackingContext();
        Bindings.capture(analysis,context); Bindings.captureFunctions(analysis,context);
        assertEquals(List.of("getVariables","v:a","v:b","getFunctions"),context.calls);
    }

    @Test void reusedAnalysisNeverReusesBoundValues() {
        var analysis=ExpressionAnalysis.parse("\u0024{a}");
        var context=new TrackingContext();
        var first=Bindings.capture(analysis,context);
        var replacement=context.factory.createValueExpression(99L,Object.class);
        context.values.put("a",replacement);
        var second=Bindings.capture(analysis,context);
        assertNotSame(first.get("a"),second.get("a"));
        assertSame(replacement,second.get("a"));
        assertEquals(7L,first.get("a").<Object>getValue(context));
    }

    @Test void obtainsFunctionMapperForEveryEvaluationPartEvenWithoutFunctions() {
        var analysis=ExpressionAnalysis.parse("\u0024{a} and \u0024{b}");
        var context=new TrackingContext();
        assertTrue(Bindings.captureFunctions(analysis,context).isEmpty());
        assertEquals(List.of("getFunctions","getFunctions"),context.calls);
    }

    @Test void preservesUnmappedQualifiedFunctionFailureAfterVariableCapture() {
        var analysis=ExpressionAnalysis.parse("\u0024{missing:f(a)}");
        var context=new TrackingContext();
        Bindings.capture(analysis,context);
        var error=assertThrows(ELException.class,()->Bindings.captureFunctions(analysis,context));
        assertEquals("Unmapped function missing:f",error.getMessage());
        assertEquals(List.of("getVariables","v:a","getFunctions","f:missing:f"),context.calls);
    }

    @Test void canonicalIdentityRemainsEquivalentAcrossDelimitersAndSpacing() {
        var immediate=ExpressionAnalysis.parse("\u0024{a+1}");
        var deferred=ExpressionAnalysis.parse("#{ a + 1 }");
        assertEquals(immediate.identity(),deferred.identity());
        assertEquals(immediate.identity().hashCode(),deferred.identity().hashCode());
        var names=immediate.variableNames();
        assertThrows(UnsupportedOperationException.class,names::clear);
    }

    private static final class TrackingContext extends ELContext {
        final ExpressionFactory factory=new PeanoExpressionFactory();
        final List<String> calls=new ArrayList<>();
        final Map<String,ValueExpression> values=new HashMap<>();
        final Method abs;
        final Method max;
        TrackingContext() {
            try { abs=Math.class.getMethod("abs",long.class); max=Math.class.getMethod("max",long.class,long.class); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            putContext(ExpressionFactory.class,factory);
            values.put("a",factory.createValueExpression(7L,Object.class));
            values.put("b",factory.createValueExpression(8L,Object.class));
        }
        @Override public ELResolver getELResolver() { return new CompositeELResolver(); }
        @Override public VariableMapper getVariableMapper() {
            calls.add("getVariables");
            return new VariableMapper() {
                @Override public ValueExpression resolveVariable(String name) { calls.add("v:"+name); return values.get(name); }
                @Override public ValueExpression setVariable(String name,ValueExpression expression) { return values.put(name,expression); }
            };
        }
        @Override public FunctionMapper getFunctionMapper() {
            calls.add("getFunctions");
            return new FunctionMapper() {
                @Override public Method resolveFunction(String prefix,String name) {
                    calls.add("f:"+prefix+":"+name);
                    return prefix.equals("p") ? abs : prefix.equals("q") ? max : null;
                }
            };
        }
    }
}
