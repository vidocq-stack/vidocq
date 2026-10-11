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

import jakarta.el.ELContext;
import jakarta.el.LambdaExpression;

/** Statically compiled Java platform adapters for EL 6.0 section 1.25.8. */
@SuppressWarnings({"rawtypes","unchecked"})
final class StandardFunctionalAdapters {
    private StandardFunctionalAdapters() {}

    static <T> T adapt(ELContext context,Object value,Class<T> type) {
        if (!(value instanceof LambdaExpression lambda)) return null;
        var invocation=new Invocation(lambda,context);
        Object adapter;
        if (type==java.util.function.BiConsumer.class) adapter=new BiConsumerAdapter(invocation);
        else if (type==java.util.function.BiFunction.class) adapter=new BiFunctionAdapter(invocation);
        else if (type==java.util.function.BinaryOperator.class) adapter=new BinaryOperatorAdapter(invocation);
        else if (type==java.util.function.BiPredicate.class) adapter=new BiPredicateAdapter(invocation);
        else if (type==java.util.function.BooleanSupplier.class) adapter=new BooleanSupplierAdapter(invocation);
        else if (type==java.util.function.Consumer.class) adapter=new ConsumerAdapter(invocation);
        else if (type==java.util.function.DoubleBinaryOperator.class) adapter=new DoubleBinaryOperatorAdapter(invocation);
        else if (type==java.util.function.DoubleConsumer.class) adapter=new DoubleConsumerAdapter(invocation);
        else if (type==java.util.function.DoubleFunction.class) adapter=new DoubleFunctionAdapter(invocation);
        else if (type==java.util.function.DoublePredicate.class) adapter=new DoublePredicateAdapter(invocation);
        else if (type==java.util.function.DoubleSupplier.class) adapter=new DoubleSupplierAdapter(invocation);
        else if (type==java.util.function.DoubleToIntFunction.class) adapter=new DoubleToIntFunctionAdapter(invocation);
        else if (type==java.util.function.DoubleToLongFunction.class) adapter=new DoubleToLongFunctionAdapter(invocation);
        else if (type==java.util.function.DoubleUnaryOperator.class) adapter=new DoubleUnaryOperatorAdapter(invocation);
        else if (type==java.util.function.Function.class) adapter=new FunctionAdapter(invocation);
        else if (type==java.util.function.IntBinaryOperator.class) adapter=new IntBinaryOperatorAdapter(invocation);
        else if (type==java.util.function.IntConsumer.class) adapter=new IntConsumerAdapter(invocation);
        else if (type==java.util.function.IntFunction.class) adapter=new IntFunctionAdapter(invocation);
        else if (type==java.util.function.IntPredicate.class) adapter=new IntPredicateAdapter(invocation);
        else if (type==java.util.function.IntSupplier.class) adapter=new IntSupplierAdapter(invocation);
        else if (type==java.util.function.IntToDoubleFunction.class) adapter=new IntToDoubleFunctionAdapter(invocation);
        else if (type==java.util.function.IntToLongFunction.class) adapter=new IntToLongFunctionAdapter(invocation);
        else if (type==java.util.function.IntUnaryOperator.class) adapter=new IntUnaryOperatorAdapter(invocation);
        else if (type==java.util.function.LongBinaryOperator.class) adapter=new LongBinaryOperatorAdapter(invocation);
        else if (type==java.util.function.LongConsumer.class) adapter=new LongConsumerAdapter(invocation);
        else if (type==java.util.function.LongFunction.class) adapter=new LongFunctionAdapter(invocation);
        else if (type==java.util.function.LongPredicate.class) adapter=new LongPredicateAdapter(invocation);
        else if (type==java.util.function.LongSupplier.class) adapter=new LongSupplierAdapter(invocation);
        else if (type==java.util.function.LongToDoubleFunction.class) adapter=new LongToDoubleFunctionAdapter(invocation);
        else if (type==java.util.function.LongToIntFunction.class) adapter=new LongToIntFunctionAdapter(invocation);
        else if (type==java.util.function.LongUnaryOperator.class) adapter=new LongUnaryOperatorAdapter(invocation);
        else if (type==java.util.function.ObjDoubleConsumer.class) adapter=new ObjDoubleConsumerAdapter(invocation);
        else if (type==java.util.function.ObjIntConsumer.class) adapter=new ObjIntConsumerAdapter(invocation);
        else if (type==java.util.function.ObjLongConsumer.class) adapter=new ObjLongConsumerAdapter(invocation);
        else if (type==java.util.function.Predicate.class) adapter=new PredicateAdapter(invocation);
        else if (type==java.util.function.Supplier.class) adapter=new SupplierAdapter(invocation);
        else if (type==java.util.function.ToDoubleBiFunction.class) adapter=new ToDoubleBiFunctionAdapter(invocation);
        else if (type==java.util.function.ToDoubleFunction.class) adapter=new ToDoubleFunctionAdapter(invocation);
        else if (type==java.util.function.ToIntBiFunction.class) adapter=new ToIntBiFunctionAdapter(invocation);
        else if (type==java.util.function.ToIntFunction.class) adapter=new ToIntFunctionAdapter(invocation);
        else if (type==java.util.function.ToLongBiFunction.class) adapter=new ToLongBiFunctionAdapter(invocation);
        else if (type==java.util.function.ToLongFunction.class) adapter=new ToLongFunctionAdapter(invocation);
        else if (type==java.util.function.UnaryOperator.class) adapter=new UnaryOperatorAdapter(invocation);
        else if (type==java.lang.Runnable.class) adapter=new RunnableAdapter(invocation);
        else if (type==java.util.concurrent.Callable.class) adapter=new CallableAdapter(invocation);
        else if (type==java.util.Comparator.class) adapter=new ComparatorAdapter(invocation);
        else return null;
        if (context!=null) context.setPropertyResolved(true);
        return (T)adapter;
    }

    private record Invocation(LambdaExpression lambda,ELContext context) {
        Object call(Object... arguments) {
            return context==null ? lambda.invoke(arguments) : lambda.invoke(context,arguments);
        }
        <T> T convert(Object value,Class<T> type) {
            return context==null ? Coercions.convert(value,type) : context.convertToType(value,type);
        }
    }

    private record BiConsumerAdapter(Invocation invocation) implements java.util.function.BiConsumer {
        @Override public void accept(Object a0,Object a1) {
            invocation.call(a0,a1);
        }
    }

    private record BiFunctionAdapter(Invocation invocation) implements java.util.function.BiFunction {
        @Override public Object apply(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),Object.class);
        }
    }

    private record BinaryOperatorAdapter(Invocation invocation) implements java.util.function.BinaryOperator {
        @Override public Object apply(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),Object.class);
        }
    }

    private record BiPredicateAdapter(Invocation invocation) implements java.util.function.BiPredicate {
        @Override public boolean test(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),boolean.class);
        }
    }

    private record BooleanSupplierAdapter(Invocation invocation) implements java.util.function.BooleanSupplier {
        @Override public boolean getAsBoolean() {
            return invocation.convert(invocation.call(),boolean.class);
        }
    }

    private record ConsumerAdapter(Invocation invocation) implements java.util.function.Consumer {
        @Override public void accept(Object a0) {
            invocation.call(a0);
        }
    }

    private record DoubleBinaryOperatorAdapter(Invocation invocation) implements java.util.function.DoubleBinaryOperator {
        @Override public double applyAsDouble(double a0,double a1) {
            return invocation.convert(invocation.call(a0,a1),double.class);
        }
    }

    private record DoubleConsumerAdapter(Invocation invocation) implements java.util.function.DoubleConsumer {
        @Override public void accept(double a0) {
            invocation.call(a0);
        }
    }

    private record DoubleFunctionAdapter(Invocation invocation) implements java.util.function.DoubleFunction {
        @Override public Object apply(double a0) {
            return invocation.convert(invocation.call(a0),Object.class);
        }
    }

    private record DoublePredicateAdapter(Invocation invocation) implements java.util.function.DoublePredicate {
        @Override public boolean test(double a0) {
            return invocation.convert(invocation.call(a0),boolean.class);
        }
    }

    private record DoubleSupplierAdapter(Invocation invocation) implements java.util.function.DoubleSupplier {
        @Override public double getAsDouble() {
            return invocation.convert(invocation.call(),double.class);
        }
    }

    private record DoubleToIntFunctionAdapter(Invocation invocation) implements java.util.function.DoubleToIntFunction {
        @Override public int applyAsInt(double a0) {
            return invocation.convert(invocation.call(a0),int.class);
        }
    }

    private record DoubleToLongFunctionAdapter(Invocation invocation) implements java.util.function.DoubleToLongFunction {
        @Override public long applyAsLong(double a0) {
            return invocation.convert(invocation.call(a0),long.class);
        }
    }

    private record DoubleUnaryOperatorAdapter(Invocation invocation) implements java.util.function.DoubleUnaryOperator {
        @Override public double applyAsDouble(double a0) {
            return invocation.convert(invocation.call(a0),double.class);
        }
    }

    private record FunctionAdapter(Invocation invocation) implements java.util.function.Function {
        @Override public Object apply(Object a0) {
            return invocation.convert(invocation.call(a0),Object.class);
        }
    }

    private record IntBinaryOperatorAdapter(Invocation invocation) implements java.util.function.IntBinaryOperator {
        @Override public int applyAsInt(int a0,int a1) {
            return invocation.convert(invocation.call(a0,a1),int.class);
        }
    }

    private record IntConsumerAdapter(Invocation invocation) implements java.util.function.IntConsumer {
        @Override public void accept(int a0) {
            invocation.call(a0);
        }
    }

    private record IntFunctionAdapter(Invocation invocation) implements java.util.function.IntFunction {
        @Override public Object apply(int a0) {
            return invocation.convert(invocation.call(a0),Object.class);
        }
    }

    private record IntPredicateAdapter(Invocation invocation) implements java.util.function.IntPredicate {
        @Override public boolean test(int a0) {
            return invocation.convert(invocation.call(a0),boolean.class);
        }
    }

    private record IntSupplierAdapter(Invocation invocation) implements java.util.function.IntSupplier {
        @Override public int getAsInt() {
            return invocation.convert(invocation.call(),int.class);
        }
    }

    private record IntToDoubleFunctionAdapter(Invocation invocation) implements java.util.function.IntToDoubleFunction {
        @Override public double applyAsDouble(int a0) {
            return invocation.convert(invocation.call(a0),double.class);
        }
    }

    private record IntToLongFunctionAdapter(Invocation invocation) implements java.util.function.IntToLongFunction {
        @Override public long applyAsLong(int a0) {
            return invocation.convert(invocation.call(a0),long.class);
        }
    }

    private record IntUnaryOperatorAdapter(Invocation invocation) implements java.util.function.IntUnaryOperator {
        @Override public int applyAsInt(int a0) {
            return invocation.convert(invocation.call(a0),int.class);
        }
    }

    private record LongBinaryOperatorAdapter(Invocation invocation) implements java.util.function.LongBinaryOperator {
        @Override public long applyAsLong(long a0,long a1) {
            return invocation.convert(invocation.call(a0,a1),long.class);
        }
    }

    private record LongConsumerAdapter(Invocation invocation) implements java.util.function.LongConsumer {
        @Override public void accept(long a0) {
            invocation.call(a0);
        }
    }

    private record LongFunctionAdapter(Invocation invocation) implements java.util.function.LongFunction {
        @Override public Object apply(long a0) {
            return invocation.convert(invocation.call(a0),Object.class);
        }
    }

    private record LongPredicateAdapter(Invocation invocation) implements java.util.function.LongPredicate {
        @Override public boolean test(long a0) {
            return invocation.convert(invocation.call(a0),boolean.class);
        }
    }

    private record LongSupplierAdapter(Invocation invocation) implements java.util.function.LongSupplier {
        @Override public long getAsLong() {
            return invocation.convert(invocation.call(),long.class);
        }
    }

    private record LongToDoubleFunctionAdapter(Invocation invocation) implements java.util.function.LongToDoubleFunction {
        @Override public double applyAsDouble(long a0) {
            return invocation.convert(invocation.call(a0),double.class);
        }
    }

    private record LongToIntFunctionAdapter(Invocation invocation) implements java.util.function.LongToIntFunction {
        @Override public int applyAsInt(long a0) {
            return invocation.convert(invocation.call(a0),int.class);
        }
    }

    private record LongUnaryOperatorAdapter(Invocation invocation) implements java.util.function.LongUnaryOperator {
        @Override public long applyAsLong(long a0) {
            return invocation.convert(invocation.call(a0),long.class);
        }
    }

    private record ObjDoubleConsumerAdapter(Invocation invocation) implements java.util.function.ObjDoubleConsumer {
        @Override public void accept(Object a0,double a1) {
            invocation.call(a0,a1);
        }
    }

    private record ObjIntConsumerAdapter(Invocation invocation) implements java.util.function.ObjIntConsumer {
        @Override public void accept(Object a0,int a1) {
            invocation.call(a0,a1);
        }
    }

    private record ObjLongConsumerAdapter(Invocation invocation) implements java.util.function.ObjLongConsumer {
        @Override public void accept(Object a0,long a1) {
            invocation.call(a0,a1);
        }
    }

    private record PredicateAdapter(Invocation invocation) implements java.util.function.Predicate {
        @Override public boolean test(Object a0) {
            return invocation.convert(invocation.call(a0),boolean.class);
        }
    }

    private record SupplierAdapter(Invocation invocation) implements java.util.function.Supplier {
        @Override public Object get() {
            return invocation.convert(invocation.call(),Object.class);
        }
    }

    private record ToDoubleBiFunctionAdapter(Invocation invocation) implements java.util.function.ToDoubleBiFunction {
        @Override public double applyAsDouble(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),double.class);
        }
    }

    private record ToDoubleFunctionAdapter(Invocation invocation) implements java.util.function.ToDoubleFunction {
        @Override public double applyAsDouble(Object a0) {
            return invocation.convert(invocation.call(a0),double.class);
        }
    }

    private record ToIntBiFunctionAdapter(Invocation invocation) implements java.util.function.ToIntBiFunction {
        @Override public int applyAsInt(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),int.class);
        }
    }

    private record ToIntFunctionAdapter(Invocation invocation) implements java.util.function.ToIntFunction {
        @Override public int applyAsInt(Object a0) {
            return invocation.convert(invocation.call(a0),int.class);
        }
    }

    private record ToLongBiFunctionAdapter(Invocation invocation) implements java.util.function.ToLongBiFunction {
        @Override public long applyAsLong(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),long.class);
        }
    }

    private record ToLongFunctionAdapter(Invocation invocation) implements java.util.function.ToLongFunction {
        @Override public long applyAsLong(Object a0) {
            return invocation.convert(invocation.call(a0),long.class);
        }
    }

    private record UnaryOperatorAdapter(Invocation invocation) implements java.util.function.UnaryOperator {
        @Override public Object apply(Object a0) {
            return invocation.convert(invocation.call(a0),Object.class);
        }
    }

    private record RunnableAdapter(Invocation invocation) implements java.lang.Runnable {
        @Override public void run() {
            invocation.call();
        }
    }

    private record CallableAdapter(Invocation invocation) implements java.util.concurrent.Callable {
        @Override public Object call() {
            return invocation.convert(invocation.call(),Object.class);
        }
    }

    private record ComparatorAdapter(Invocation invocation) implements java.util.Comparator {
        @Override public int compare(Object a0,Object a1) {
            return invocation.convert(invocation.call(a0,a1),int.class);
        }
    }
}

