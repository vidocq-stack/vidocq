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
import java.util.HashMap;
import java.util.List;
import jakarta.el.*;
import io.vidocq.peano.core.internal.evaluation.Bindings;
import io.vidocq.peano.core.internal.syntax.Ast;
import io.vidocq.peano.core.internal.syntax.Parser;
import org.junit.jupiter.api.Test;

class LambdaCaptureEffectsTest {
    @Test
    @org.junit.jupiter.api.Disabled("API-004: ELContext skips an inner null and exposes the outer argument")
    void nullInnerScopeMustMaskAnOuterValue() {
        var context=new CaptureContext();
        context.enterLambdaScope(java.util.Map.of("seed",7L));
        var inner=new HashMap<String,Object>(); inner.put("seed",null);
        context.enterLambdaScope(inner);
        try { assertNull(context.getLambdaArgument("seed")); }
        finally { context.exitLambdaScope(); context.exitLambdaScope(); }
    }

    @Test void visitsFreeNamesOnceAndExcludesShadowedParameters() {
        var context=new CaptureContext();
        var scope=new HashMap<String,Object>(); scope.put("seed",7L);
        context.enterLambdaScope(scope);
        try {
            var lambda=(Ast.Lambda)Parser.parseBody("(x -> seed+seed+(seed -> seed)(1)+x)");
            var captured=Bindings.captureLambdaArguments(lambda,context);
            assertEquals(List.of("seed"),context.visited);
            assertEquals(7L,captured.get("seed"));
            scope.put("seed",99L);
            assertEquals(7L,captured.get("seed"));
            assertThrows(UnsupportedOperationException.class,()->captured.put("seed",0L));
        } finally { context.exitLambdaScope(); }
    }

    @Test void capturesOuterArgumentsFromEveryNestedSyntaxForm() {
        var context=new CaptureContext();
        var scope=new HashMap<String,Object>();
        for (String name:List.of("a","b","c","d","e","f","g","h")) scope.put(name,name.toUpperCase());
        context.enterLambdaScope(scope);
        try {
            var lambda=(Ast.Lambda)Parser.parseBody("(x -> -a + fn:call(b) + {c} + {d: e} + [f] + (g ? x.y : h[x]) + 'z')");
            var captured=Bindings.captureLambdaArguments(lambda,context);
            assertEquals(List.of("a","b","c","d","e","f","g","h"),context.visited);
            assertEquals(java.util.Map.of("a","A","b","B","c","C","d","D","e","E","f","F","g","G","h","H"),captured);
        } finally { context.exitLambdaScope(); }
    }

    @Test void nullableCapturesRemainPresentAndImmutable() {
        var context=new CaptureContext();
        var scope=new HashMap<String,Object>(); scope.put("seed",null);
        context.enterLambdaScope(scope);
        try {
            var lambda=(Ast.Lambda)Parser.parseBody("(x -> seed)");
            var captured=Bindings.captureLambdaArguments(lambda,context);
            assertTrue(captured.containsKey("seed")); assertNull(captured.get("seed"));
            scope.put("seed",99L); assertNull(captured.get("seed"));
            assertThrows(UnsupportedOperationException.class,captured::clear);
        } finally { context.exitLambdaScope(); }
    }

    @Test void retainedClosuresKeepTheirSnapshotAndRestoreScopesAfterFailure() {
        var context=new CaptureContext();
        var scope=new HashMap<String,Object>(); scope.put("seed",7L);
        context.enterLambdaScope(scope);
        LambdaExpression closure;
        try {
            closure=context.factory.createValueExpression(context,"\u0024{x -> seed}",Object.class).getValue(context);
            scope.put("seed",99L);
        } finally { context.exitLambdaScope(); }
        assertEquals(7L,closure.invoke(context,1L));
        assertFalse(context.isLambdaArgument("seed"));
        var failed=context.factory.createValueExpression(context,"\u0024{x -> 1 div 0}",Object.class).<LambdaExpression>getValue(context);
        failed.invoke(context,1L);
        assertFalse(context.isLambdaArgument("x"));
        var error=context.factory.createValueExpression(context,"\u0024{x -> 1 mod 0}",Object.class).<LambdaExpression>getValue(context);
        assertThrows(ELException.class,()->error.invoke(context,1L));
        assertFalse(context.isLambdaArgument("x"));
    }

    private static final class CaptureContext extends StandardELContext {
        final List<String> visited=new ArrayList<>();
        final ExpressionFactory factory;
        CaptureContext() { this(new PeanoExpressionFactory()); }
        private CaptureContext(ExpressionFactory factory) {
            super(factory); this.factory=factory; putContext(ExpressionFactory.class,factory);
        }
        @Override public boolean isLambdaArgument(String name) { visited.add(name); return super.isLambdaArgument(name); }
    }
}
