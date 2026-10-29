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
import jakarta.el.*;
import java.util.function.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@SuppressWarnings({"rawtypes","unchecked"})
class StandardFunctionalConversionTest {
    @FunctionalInterface interface Invocation { Object call(Object adapter) throws Exception; }

    static Stream<Arguments> adapters() {
        return Stream.of(
        Arguments.of(java.util.function.BiConsumer.class,"(a,b)->a+b",(Invocation) adapter -> { ((java.util.function.BiConsumer) adapter).accept(2,3); return null; },null),
        Arguments.of(java.util.function.BiFunction.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.BiFunction) adapter).apply(2,3); },5L),
        Arguments.of(java.util.function.BinaryOperator.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.BinaryOperator) adapter).apply(2,3); },5L),
        Arguments.of(java.util.function.BiPredicate.class,"(a,b)->a>b",(Invocation) adapter -> { return ((java.util.function.BiPredicate) adapter).test(2,3); },false),
        Arguments.of(java.util.function.BooleanSupplier.class,"()->true",(Invocation) adapter -> { return ((java.util.function.BooleanSupplier) adapter).getAsBoolean(); },true),
        Arguments.of(java.util.function.Consumer.class,"a->a+1",(Invocation) adapter -> { ((java.util.function.Consumer) adapter).accept(2); return null; },null),
        Arguments.of(java.util.function.DoubleBinaryOperator.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.DoubleBinaryOperator) adapter).applyAsDouble(2.0,3.0); },5.0),
        Arguments.of(java.util.function.DoubleConsumer.class,"a->a+1",(Invocation) adapter -> { ((java.util.function.DoubleConsumer) adapter).accept(2.0); return null; },null),
        Arguments.of(java.util.function.DoubleFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.DoubleFunction) adapter).apply(2.0); },3.0),
        Arguments.of(java.util.function.DoublePredicate.class,"a->a>0",(Invocation) adapter -> { return ((java.util.function.DoublePredicate) adapter).test(2.0); },true),
        Arguments.of(java.util.function.DoubleSupplier.class,"()->7",(Invocation) adapter -> { return ((java.util.function.DoubleSupplier) adapter).getAsDouble(); },7.0),
        Arguments.of(java.util.function.DoubleToIntFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.DoubleToIntFunction) adapter).applyAsInt(2.0); },3),
        Arguments.of(java.util.function.DoubleToLongFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.DoubleToLongFunction) adapter).applyAsLong(2.0); },3L),
        Arguments.of(java.util.function.DoubleUnaryOperator.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.DoubleUnaryOperator) adapter).applyAsDouble(2.0); },3.0),
        Arguments.of(java.util.function.Function.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.Function) adapter).apply(2); },3L),
        Arguments.of(java.util.function.IntBinaryOperator.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.IntBinaryOperator) adapter).applyAsInt(2,3); },5),
        Arguments.of(java.util.function.IntConsumer.class,"a->a+1",(Invocation) adapter -> { ((java.util.function.IntConsumer) adapter).accept(2); return null; },null),
        Arguments.of(java.util.function.IntFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.IntFunction) adapter).apply(2); },3L),
        Arguments.of(java.util.function.IntPredicate.class,"a->a>0",(Invocation) adapter -> { return ((java.util.function.IntPredicate) adapter).test(2); },true),
        Arguments.of(java.util.function.IntSupplier.class,"()->7",(Invocation) adapter -> { return ((java.util.function.IntSupplier) adapter).getAsInt(); },7),
        Arguments.of(java.util.function.IntToDoubleFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.IntToDoubleFunction) adapter).applyAsDouble(2); },3.0),
        Arguments.of(java.util.function.IntToLongFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.IntToLongFunction) adapter).applyAsLong(2); },3L),
        Arguments.of(java.util.function.IntUnaryOperator.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.IntUnaryOperator) adapter).applyAsInt(2); },3),
        Arguments.of(java.util.function.LongBinaryOperator.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.LongBinaryOperator) adapter).applyAsLong(2L,3L); },5L),
        Arguments.of(java.util.function.LongConsumer.class,"a->a+1",(Invocation) adapter -> { ((java.util.function.LongConsumer) adapter).accept(2L); return null; },null),
        Arguments.of(java.util.function.LongFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.LongFunction) adapter).apply(2L); },3L),
        Arguments.of(java.util.function.LongPredicate.class,"a->a>0",(Invocation) adapter -> { return ((java.util.function.LongPredicate) adapter).test(2L); },true),
        Arguments.of(java.util.function.LongSupplier.class,"()->7",(Invocation) adapter -> { return ((java.util.function.LongSupplier) adapter).getAsLong(); },7L),
        Arguments.of(java.util.function.LongToDoubleFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.LongToDoubleFunction) adapter).applyAsDouble(2L); },3.0),
        Arguments.of(java.util.function.LongToIntFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.LongToIntFunction) adapter).applyAsInt(2L); },3),
        Arguments.of(java.util.function.LongUnaryOperator.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.LongUnaryOperator) adapter).applyAsLong(2L); },3L),
        Arguments.of(java.util.function.ObjDoubleConsumer.class,"(a,b)->a+b",(Invocation) adapter -> { ((java.util.function.ObjDoubleConsumer) adapter).accept(2,3.0); return null; },null),
        Arguments.of(java.util.function.ObjIntConsumer.class,"(a,b)->a+b",(Invocation) adapter -> { ((java.util.function.ObjIntConsumer) adapter).accept(2,3); return null; },null),
        Arguments.of(java.util.function.ObjLongConsumer.class,"(a,b)->a+b",(Invocation) adapter -> { ((java.util.function.ObjLongConsumer) adapter).accept(2,3L); return null; },null),
        Arguments.of(java.util.function.Predicate.class,"a->a>0",(Invocation) adapter -> { return ((java.util.function.Predicate) adapter).test(2); },true),
        Arguments.of(java.util.function.Supplier.class,"()->7",(Invocation) adapter -> { return ((java.util.function.Supplier) adapter).get(); },7L),
        Arguments.of(java.util.function.ToDoubleBiFunction.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.ToDoubleBiFunction) adapter).applyAsDouble(2,3); },5.0),
        Arguments.of(java.util.function.ToDoubleFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.ToDoubleFunction) adapter).applyAsDouble(2); },3.0),
        Arguments.of(java.util.function.ToIntBiFunction.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.ToIntBiFunction) adapter).applyAsInt(2,3); },5),
        Arguments.of(java.util.function.ToIntFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.ToIntFunction) adapter).applyAsInt(2); },3),
        Arguments.of(java.util.function.ToLongBiFunction.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.function.ToLongBiFunction) adapter).applyAsLong(2,3); },5L),
        Arguments.of(java.util.function.ToLongFunction.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.ToLongFunction) adapter).applyAsLong(2); },3L),
        Arguments.of(java.util.function.UnaryOperator.class,"a->a+1",(Invocation) adapter -> { return ((java.util.function.UnaryOperator) adapter).apply(2); },3L),
        Arguments.of(java.lang.Runnable.class,"()->7",(Invocation) adapter -> { ((java.lang.Runnable) adapter).run(); return null; },null),
        Arguments.of(java.util.concurrent.Callable.class,"()->7",(Invocation) adapter -> { return ((java.util.concurrent.Callable) adapter).call(); },7L),
        Arguments.of(java.util.Comparator.class,"(a,b)->a+b",(Invocation) adapter -> { return ((java.util.Comparator) adapter).compare(2,3); },5)
        );
    }

    @ParameterizedTest(name="{0}")
    @MethodSource("adapters")
    void convertsEveryStandardDescriptor(Class<?> type,String expression,Invocation invocation,Object expected) throws Exception {
        var el=new ELProcessor();
        Object adapter=el.getValue(expression,type);
        assertTrue(type.isAnnotationPresent(FunctionalInterface.class));
        assertTrue(type.isInstance(adapter));
        assertEquals(expected,invocation.call(adapter));
        var factory=el.getELManager().getExpressionFactory();
        Object lambda=el.eval(expression);
        assertEquals(expected,invocation.call(factory.coerceToType(lambda,type)));
        assertSame(adapter,factory.coerceToType(adapter,type));
        assertNull(factory.coerceToType(null,type));
    }

    @Test
    void invokesConsumersAndRunnablesExactlyOnceAndPreservesDefaultComposition() {
        var el=new ELProcessor();
        el.defineBean("state",new java.util.HashMap<String,Object>());
        el.eval("state.calls=0");
        Consumer<Object> consumer=el.getValue("x->(state.calls=state.calls+1;state.value=x)",Consumer.class);
        consumer.andThen(consumer).accept("done");
        assertEquals("done",el.eval("state.value"));
        assertEquals(2L,(Object)el.eval("state.calls"));
        Runnable runnable=el.getValue("()->(state.value='run')",Runnable.class);
        runnable.run();
        assertEquals("run",el.eval("state.value"));
        Function<Object,Object> function=el.getValue("x->x+1",Function.class);
        assertEquals(5L,function.andThen(function).apply(3));
        IntUnaryOperator operation=el.getValue("x->x+1",IntUnaryOperator.class);
        assertEquals(5,operation.andThen(operation).applyAsInt(3));
        java.util.Comparator<Object> comparator=el.getValue("(a,b)->a-b",java.util.Comparator.class);
        assertEquals(-2,comparator.reversed().compare(7,5));
    }

    @Test
    void coercesPrimitiveResultsAndPreservesClosuresAndFailures() {
        var el=new ELProcessor();
        IntSupplier number=el.getValue("()->'7'",IntSupplier.class);
        assertEquals(7,number.getAsInt());
        BooleanSupplier empty=el.getValue("()->null",BooleanSupplier.class);
        assertFalse(empty.getAsBoolean());
        LongUnaryOperator closure=el.getValue("(offset->(x->x+offset))(5)",LongUnaryOperator.class);
        assertEquals(12L,closure.applyAsLong(7L));
        Function<Object,Object> length=el.getValue("values->values.length",Function.class);
        assertEquals(2,length.apply(new String[]{"a","b"}));
        Function<Object,Object> failure=el.getValue("x->missing",Function.class);
        assertThrows(ELException.class,()->failure.apply(1));
        assertFalse(el.getELManager().getELContext().isLambdaArgument("x"));
    }

    @Test
    void preservesCustomTargetAndResultConverters() {
        var el=new ELProcessor();
        el.getELManager().addELResolver(new ELResolver() {
            public Object getValue(ELContext c,Object b,Object p) { return null; }
            public Class<?> getType(ELContext c,Object b,Object p) { return null; }
            @Override public void setValue(ELContext c,Object b,Object p,Object v) {
                // This resolver only converts values: nothing to write.
            }
            public boolean isReadOnly(ELContext c,Object b,Object p) { return false; }
            public Class<?> getCommonPropertyType(ELContext c,Object b) { return null; }
            public <T> T convertToType(ELContext c,Object value,Class<T> target) {
                if (target==IntSupplier.class) { c.setPropertyResolved(true); return (T)(IntSupplier)()->99; }
                if (target==long.class) { c.setPropertyResolved(true); return (T)Long.valueOf(23); }
                return null;
            }
        });
        IntSupplier overridden=el.getValue("()->7",IntSupplier.class);
        assertEquals(99,overridden.getAsInt());
        LongSupplier result=el.getValue("()->7",LongSupplier.class);
        assertEquals(23L,result.getAsLong());
    }
}

