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
import java.util.List;
import java.util.Iterator;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.el.ELException;
import jakarta.el.ELProcessor;
import org.junit.jupiter.api.Test;

/** Collection pipelines from EL 6.0 chapter 2. */
class StreamOperationsTest {
    @Test
    void applicationResolversKeepInvocationPrecedenceAndSourcesRemainUnchanged() {
        var factory = jakarta.el.ExpressionFactory.newInstance();
        var context = new jakarta.el.StandardELContext(factory);
        context.addELResolver(new jakarta.el.CompositeELResolver() {
            @Override
            public Object invoke(jakarta.el.ELContext current, Object base, Object method,
                    Class<?>[] types, Object[] args) {
                if (base instanceof List<?> && "stream".equals(method)) {
                    current.setPropertyResolved(base, method);
                    return "custom";
                }
                return null;
            }
        });
        assertEquals("custom", factory.createValueExpression(context,
                "${[1,2].stream()}", Object.class).getValue(context));
        var el = new ELProcessor();
        var source = new java.util.ArrayList<>(List.of(3,1,2));
        el.defineBean("source", source);
        List<Object> sorted = el.eval("source.stream().sorted().toList()");
        sorted.add(4);
        assertEquals(List.of(3,1,2), source);
    }

    @Test
    void mapsFiltersFlattensAndSorts() {
        var el = new ELProcessor();
        assertEquals(List.of(8L,4L), el.eval("[3,2,4,2].stream().filter(x->x%2==0).map(x->x*2).distinct().sorted((a,b)->b-a).toList()"));
        assertArrayEquals(new Object[]{1L,2L,3L}, el.eval("[[3,1],[2,1]].stream().flatMap(x->x.stream()).distinct().sorted().toArray()"));
    }

    @Test
    void streamsPrimitiveAndObjectArraysAndMapViews() {
        var el = new ELProcessor();
        el.defineBean("numbers", new int[]{1,2,3});
        el.defineBean("words", new String[]{"b","a"});
        assertEquals(6L, el.<Object>eval("numbers.stream().sum()"));
        assertEquals(List.of("a","b"), el.eval("words.stream().sorted().toList()"));
        assertEquals(3L, el.<Object>eval("{'a':1,'b':2}.values().stream().sum()"));
    }

    @Test
    void limitsAndSlicesIncludingEmptyBounds() {
        var el = new ELProcessor();
        assertEquals(List.of(3L,4L), el.eval("[1,2,3,4,5].stream().substream(2,4).toList()"));
        assertEquals(List.of(1L,2L), el.eval("[1,2,3].stream().substream(-2).limit(2).toList()"));
        assertEquals(List.of(), el.eval("[1,2].stream().limit(-1).toList()"));
        assertEquals(List.of(), el.eval("[1,2].stream().substream(8).toList()"));
    }

    @Test
    void defersCallbacksAndVisitsOnlyRequiredElements() {
        var el = new ELProcessor();
        var counter = new AtomicInteger();
        el.defineBean("counter", counter);
        el.eval("s=[1,2,3].stream().peek(x->counter.incrementAndGet()).map(x->x*2)");
        assertEquals(0, counter.get());
        assertEquals(List.of(2L), el.eval("s.limit(1).toList()"));
        assertEquals(1, counter.get());
        assertEquals(3L, el.<Object>eval("[1,2,3].stream().peek(x->counter.incrementAndGet()).count()"));
        assertEquals(4, counter.get());
        assertNull(el.eval("[1,2].stream().forEach(x->counter.incrementAndGet())"));
        assertEquals(6, counter.get());
    }

    @Test
    void reducesAndComputesNumericAggregates() {
        var el = new ELProcessor();
        assertEquals(6L, el.<Object>eval("[1,2,3].stream().reduce((a,b)->a+b).get()"));
        assertEquals(16L, el.<Object>eval("[1,2,3].stream().reduce(10,(a,b)->a+b)"));
        assertEquals(0L, el.<Object>eval("[].stream().sum()"));
        assertEquals(2.0, el.<Object>eval("[1,2,3].stream().average().get()"));
        el.defineBean("decimals", List.of(new BigDecimal("1.0"), new BigDecimal("2.0")));
        assertEquals(new BigDecimal("3.0"), el.eval("decimals.stream().sum()"));
        assertEquals(new BigDecimal("1.5"), el.eval("decimals.stream().average().get()"));
    }

    @Test
    void computesExtremaWithNaturalAndLambdaOrdering() {
        var el = new ELProcessor();
        assertEquals(3L, el.<Object>eval("[2,3,1].stream().max().get()"));
        assertEquals(1L, el.<Object>eval("[2,3,1].stream().min().get()"));
        assertEquals(1L, el.<Object>eval("[2,3,1].stream().max((a,b)->b-a).get()"));
        assertEquals(3L, el.<Object>eval("[2,3,1].stream().min((a,b)->b-a).get()"));
    }

    @Test
    void matchesReturnOptionalsAndShortCircuit() {
        var el = new ELProcessor();
        assertEquals(true, el.eval("[1,2,3].stream().anyMatch(x->x==1 ? true : missing).get()"));
        assertEquals(false, el.eval("[1,2,3].stream().allMatch(x->x==1 ? false : missing).get()"));
        assertEquals(false, el.eval("[1,2,3].stream().noneMatch(x->x==1 ? true : missing).get()"));
        for (String method : List.of("anyMatch", "allMatch", "noneMatch")) {
            assertEquals("empty", el.eval("[].stream()."+method+"(x->true).orElse('empty')"));
        }
        assertEquals(false, el.eval("[1,2].stream().anyMatch(x->false).get()"));
        assertEquals(true, el.eval("[1,2].stream().allMatch(x->true).get()"));
        assertEquals(true, el.eval("[1,2].stream().noneMatch(x->false).get()"));
        assertEquals(List.of(), el.eval("[1,2].stream().filter(x->null).toList()"));
        assertEquals(List.of(), el.eval("[1,2].stream().limit(null).toList()"));
        assertEquals(List.of(1L,2L), el.eval("[1,2].stream().sorted((a,b)->null).toList()"));
    }

    @Test
    void optionalFallbacksAreLazyAndNullElementsRemainValues() {
        var el = new ELProcessor();
        for (String method : List.of("findFirst()", "max()", "min()", "average()", "reduce((a,b)->a+b)")) {
            assertThrows(ELException.class, ()->el.eval("[].stream()."+method+".get()"));
            assertEquals(42L, el.<Object>eval("[].stream()."+method+".orElseGet(()->42)"));
        }
        assertEquals(1L, el.<Object>eval("[1,2].stream().findFirst().orElseGet(()->missing)"));
        assertNull(el.eval("[null].stream().findFirst().get()"));
        var counter = new AtomicInteger();
        el.defineBean("counter", counter);
        assertNull(el.eval("[].stream().findFirst().ifPresent(x->counter.incrementAndGet())"));
        assertNull(el.eval("[1].stream().findFirst().ifPresent(x->counter.incrementAndGet())"));
        assertEquals(1, counter.get());
    }

    @Test
    void exposesIteratorAndRejectsInvalidOperations() {
        var el = new ELProcessor();
        Iterator<?> iterator = el.eval("[1,2].stream().iterator()");
        assertEquals(1L, iterator.next());
        assertEquals(2L, iterator.next());
        assertFalse(iterator.hasNext());
        assertThrows(ELException.class, ()->el.eval("[1].stream().filter(42).toList()"));
        assertThrows(ELException.class, ()->el.eval("[1].stream().map().toList()"));
        assertThrows(ELException.class, ()->el.eval("[1].stream().unknown()"));
        assertThrows(ELException.class, ()->el.eval("[1].stream(42)"));
    }
}

