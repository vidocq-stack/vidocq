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
import java.util.concurrent.Executors;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class ParsedExpressionCacheTest {
    @Test void exactSourceChecksSurviveHashCollisionsAndRetainTheLocalMiss() {
        var cache=new ParsedExpressionCache(8,128,4096);
        String first="\u0024{Aa+1}",second="\u0024{BB+1}";
        assertEquals(first.hashCode(),second.hashCode());
        var left=cache.parse(first);
        var right=cache.parse(second);
        assertEquals(List.of("Aa"),left.variableNames());
        assertEquals(List.of("BB"),right.variableNames());
        assertEquals(left.identity(),cache.parse(first).identity());
        assertEquals(right.identity(),cache.parse(second).identity());
        assertEquals(1,cache.retainedEntries());
    }

    @Test void retentionIsBoundedAndOversizedEntriesDoNotReplaceHotSources() {
        var cache=new ParsedExpressionCache(8,128,4096);
        var hot=cache.parse("\u0024{a}");
        for (int i=0;i<300;i++) cache.parse("\u0024{a+"+i+"}");
        assertTrue(cache.retainedEntries()<=8);
        assertTrue(cache.retainedEstimatedBytes()<=8L*4096);
        var tiny=new ParsedExpressionCache(8,128,1);
        tiny.parse("\u0024{a}"); assertEquals(0,tiny.retainedEntries());
        var shortSources=new ParsedExpressionCache(8,8,4096);
        shortSources.parse("\u0024{a}");
        shortSources.parse("\u0024{'"+"x".repeat(200)+"'}");
        assertEquals(1,shortSources.retainedEntries());
        assertEquals(hot.identity(),shortSources.parse("\u0024{a}").identity());
        var disabled=new ParsedExpressionCache(0,128,4096);
        disabled.parse("\u0024{a}"); assertEquals(0,disabled.retainedEntries());
    }

    @Test void expressionInstancesOwnNonemptyStringsWhileEachValueRemainsStable() {
        var factory=new PeanoExpressionFactory();
        var context=new StandardELContext(factory); context.putContext(ExpressionFactory.class,factory);
        String[] sources={"\u0024{'hello'}","\u0024{true ? 'hello' : 'other'}","\u0024{[{'key':'hello'}][0]['key']}"};
        for (String source:sources) {
            var first=factory.createValueExpression(context,source,Object.class);
            var second=factory.createValueExpression(context,source,Object.class);
            Object value=first.getValue(context);
            assertEquals("hello",value);
            assertSame(value,first.getValue(context));
            assertNotSame(value,second.getValue(context));
            assertEquals(first,second);
            assertEquals(first.hashCode(),second.hashCode());
        }
    }

    @Test void warmSyntaxStillCapturesEveryContextsOwnBindingsAndExpectedType() {
        var factory=new PeanoExpressionFactory();
        var first=new StandardELContext(factory); var second=new StandardELContext(factory);
        first.putContext(ExpressionFactory.class,factory); second.putContext(ExpressionFactory.class,factory);
        first.getVariableMapper().setVariable("a",factory.createValueExpression(11L,Long.class));
        second.getVariableMapper().setVariable("a",factory.createValueExpression(22L,Long.class));
        var left=factory.createValueExpression(first,"\u0024{a+1}",Object.class);
        var right=factory.createValueExpression(second,"\u0024{a+1}",String.class);
        first.getVariableMapper().setVariable("a",factory.createValueExpression(99L,Long.class));
        assertEquals(12L,left.<Object>getValue(first));
        assertEquals("23",right.<Object>getValue(second));
        assertNotEquals(left,right);
    }

    @Test void cachesNoFailureOrThrowableAndPreservesLazyNumericErrors() {
        var cache=new ParsedExpressionCache(8,128,4096);
        var first=assertThrows(ELException.class,()->cache.parse("\u0024{a+}"));
        var second=assertThrows(ELException.class,()->cache.parse("\u0024{a+}"));
        assertNotSame(first,second); assertEquals(0,cache.retainedEntries());
        var factory=new PeanoExpressionFactory();
        var context=new StandardELContext(factory); context.putContext(ExpressionFactory.class,factory);
        for (int i=0;i<2;i++) {
            assertEquals(true,factory.createValueExpression(context,"\u0024{true or 9223372036854775808}",Object.class).<Object>getValue(context));
            assertEquals(Long.MIN_VALUE,factory.createValueExpression(context,"\u0024{-09223372036854775808}",Object.class).<Object>getValue(context));
        }
        var expression=factory.createValueExpression(context,"\u0024{9223372036854775808}",Object.class);
        var error1=assertThrows(ELException.class,()->expression.getValue(context));
        var error2=assertThrows(ELException.class,()->expression.getValue(context));
        assertNotSame(error1.getCause(),error2.getCause());
        assertInstanceOf(NumberFormatException.class,error1.getCause());
        assertEquals(error1.getMessage(),error2.getMessage());
    }

    @Test void concurrentSameKeysAndCollisionsRemainBoundedAndContextIndependent() throws Exception {
        var cache=new ParsedExpressionCache(8,128,4096);
        var factory=new PeanoExpressionFactory();
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for (int worker=0;worker<32;worker++) {
                final long value=worker;
                futures.add(executor.submit(()-> {
                    var context=new StandardELContext(factory); context.putContext(ExpressionFactory.class,factory);
                    context.getVariableMapper().setVariable("a",factory.createValueExpression(value,Long.class));
                    for (int i=0;i<80;i++) {
                        String source=i%2==0 ? "\u0024{Aa+1}" : "\u0024{BB+1}";
                        assertEquals(List.of(i%2==0 ? "Aa" : "BB"),cache.parse(source).variableNames());
                        assertEquals(value+1,factory.createValueExpression(context,"\u0024{a+1}",Object.class).<Object>getValue(context));
                    }
                }));
            }
            for (var future:futures) future.get();
        }
        assertTrue(cache.retainedEntries()<=8);
        assertTrue(cache.retainedEstimatedBytes()<=8L*4096);
    }
}
