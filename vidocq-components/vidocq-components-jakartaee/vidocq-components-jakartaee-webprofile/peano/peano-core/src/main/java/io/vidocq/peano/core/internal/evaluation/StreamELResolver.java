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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import jakarta.el.LambdaExpression;
import jakarta.el.MethodNotFoundException;
import io.vidocq.peano.core.internal.syntax.TokenKind;

/**
 * Direct dispatch for the lazy collection operations in EL 6.0 chapter 2.
 * Internal stream and optional values never require exports or reflective invocation.
 */
public final class StreamELResolver extends ELResolver {
    private record Pipeline(Stream<Object> elements) {}
    private record Result(boolean present, Object value) {
        static Result empty() { return new Result(false, null); }
        static Result of(Object value) { return new Result(true, value); }
    }

    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] parameters) {
        String name = method instanceof String text ? text : "";
        Object[] args = parameters == null ? new Object[0] : parameters;
        boolean source = base instanceof Collection<?> || base != null && base.getClass().isArray();
        if (source && name.equals("stream")) {
            context.setPropertyResolved(base, method);
            arity(name, args, 0);
            if (base instanceof Collection<?> collection) {
                return new Pipeline(collection.stream().map(value -> value));
            }
            return new Pipeline(IntStream.range(0, Array.getLength(base)).mapToObj(i -> Array.get(base, i)));
        }
        if (base instanceof Pipeline pipeline) {
            context.setPropertyResolved(base, method);
            return pipeline(context, pipeline.elements(), name, args);
        }
        if (base instanceof Result result) {
            context.setPropertyResolved(base, method);
            return optional(context, result, name, args);
        }
        return null;
    }

    private Object pipeline(ELContext context, Stream<Object> stream, String name, Object[] args) {
        return switch (name) {
            case "filter" -> {
                arity(name, args, 1);
                LambdaExpression predicate = lambda(args[0]);
                yield new Pipeline(stream.filter(value -> truth(context, predicate, value)));
            }
            case "map" -> {
                arity(name, args, 1);
                LambdaExpression mapper = lambda(args[0]);
                yield new Pipeline(stream.map(value -> mapper.invoke(context, value)));
            }
            case "flatMap" -> {
                arity(name, args, 1);
                LambdaExpression mapper = lambda(args[0]);
                yield new Pipeline(stream.flatMap(value -> {
                    Object mapped = mapper.invoke(context, value);
                    if (mapped instanceof Pipeline nested) return nested.elements();
                    throw new ELException("flatMap requires an EL stream result");
                }));
            }
            case "distinct" -> {
                arity(name, args, 0);
                yield new Pipeline(stream.distinct());
            }
            case "sorted" -> {
                arity(name, args, 0, 1);
                yield new Pipeline(stream.sorted(comparator(context, args)));
            }
            case "peek", "forEach" -> {
                arity(name, args, 1);
                LambdaExpression consumer = lambda(args[0]);
                if (name.equals("peek")) {
                    yield new Pipeline(stream.peek(value -> consumer.invoke(context, value)));
                }
                stream.forEachOrdered(value -> consumer.invoke(context, value));
                yield null;
            }
            case "limit" -> {
                arity(name, args, 1);
                yield new Pipeline(stream.limit(Math.max(0, number(context, args[0]))));
            }
            case "substream" -> {
                arity(name, args, 1, 2);
                long start = number(context, args[0]);
                Stream<Object> skipped = stream.skip(Math.max(0, start));
                if (args.length == 2) {
                    long end = number(context, args[1]);
                    // Saturate subtraction to avoid overflowing a very large positive range.
                    long length = end <= start ? 0 : end - start;
                    skipped = skipped.limit(length < 0 ? Long.MAX_VALUE : length);
                }
                yield new Pipeline(skipped);
            }
            case "toArray" -> {
                arity(name, args, 0);
                yield stream.toArray();
            }
            case "toList" -> {
                arity(name, args, 0);
                yield stream.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            }
            case "iterator" -> {
                arity(name, args, 0);
                yield stream.iterator();
            }
            case "count" -> {
                arity(name, args, 0);
                // Traverse even sized sources: EL peek callbacks are observable.
                long count = 0;
                Iterator<Object> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    iterator.next();
                    count++;
                }
                yield count;
            }
            case "reduce" -> {
                arity(name, args, 1, 2);
                LambdaExpression reducer = lambda(args[args.length - 1]);
                Iterator<Object> iterator = stream.iterator();
                if (args.length == 1 && !iterator.hasNext()) yield Result.empty();
                Object value = args.length == 2 ? args[0] : iterator.next();
                while (iterator.hasNext()) value = reducer.invoke(context, value, iterator.next());
                yield args.length == 2 ? value : Result.of(value);
            }
            case "min", "max" -> {
                arity(name, args, 0, 1);
                Comparator<Object> comparison = comparator(context, args);
                Iterator<Object> iterator = stream.iterator();
                if (!iterator.hasNext()) yield Result.empty();
                Object best = iterator.next();
                // Even a singleton must satisfy the natural ordering contract.
                if (args.length == 0 && !(best instanceof Comparable<?>)) {
                    throw new ELException("Natural ordering requires Comparable elements");
                }
                while (iterator.hasNext()) {
                    Object next = iterator.next();
                    int order = comparison.compare(next, best);
                    if (name.equals("max") ? order > 0 : order < 0) best = next;
                }
                yield Result.of(best);
            }
            case "sum", "average" -> {
                arity(name, args, 0);
                Iterator<Object> iterator = stream.iterator();
                Number sum = 0L;
                long count = 0;
                while (iterator.hasNext()) {
                    sum = Arithmetic.binary(TokenKind.PLUS, sum, iterator.next(), context);
                    count++;
                }
                if (name.equals("sum")) yield sum;
                yield count == 0 ? Result.empty()
                        : Result.of(Arithmetic.binary(TokenKind.DIVIDE, sum, count, context));
            }
            case "anyMatch", "allMatch", "noneMatch" -> {
                arity(name, args, 1);
                LambdaExpression predicate = lambda(args[0]);
                Iterator<Object> iterator = stream.iterator();
                if (!iterator.hasNext()) yield Result.empty();
                boolean all = name.equals("allMatch");
                boolean matched = all;
                do {
                    boolean test = truth(context, predicate, iterator.next());
                    if (test != all) {
                        matched = !all;
                        break;
                    }
                } while (iterator.hasNext());
                yield Result.of(name.equals("noneMatch") ? !matched : matched);
            }
            case "findFirst" -> {
                arity(name, args, 0);
                Iterator<Object> iterator = stream.iterator();
                yield iterator.hasNext() ? Result.of(iterator.next()) : Result.empty();
            }
            default -> throw new MethodNotFoundException("Unknown EL stream operation: " + name);
        };
    }

    private Object optional(ELContext context, Result result, String name, Object[] args) {
        return switch (name) {
            case "get" -> {
                arity(name, args, 0);
                if (!result.present()) throw new ELException("The EL optional is empty");
                yield result.value();
            }
            case "orElse" -> {
                arity(name, args, 1);
                yield result.present() ? result.value() : args[0];
            }
            case "orElseGet" -> {
                arity(name, args, 1);
                LambdaExpression fallback = lambda(args[0]);
                yield result.present() ? result.value() : fallback.invoke(context);
            }
            case "ifPresent" -> {
                arity(name, args, 1);
                LambdaExpression consumer = lambda(args[0]);
                if (result.present()) consumer.invoke(context, result.value());
                yield null;
            }
            default -> throw new MethodNotFoundException("Unknown EL optional operation: " + name);
        };
    }

    private static LambdaExpression lambda(Object value) {
        if (value instanceof LambdaExpression expression) return expression;
        throw new ELException("The collection operation requires an EL lambda");
    }

    private static boolean truth(ELContext context, LambdaExpression predicate, Object value) {
        return context.convertToType(predicate.invoke(context, value), boolean.class);
    }

    private static long number(ELContext context, Object value) {
        return context.convertToType(value, long.class);
    }

    private static Comparator<Object> comparator(ELContext context, Object[] args) {
        if (args.length == 1) {
            LambdaExpression comparator = lambda(args[0]);
            return (a, b) -> context.convertToType(comparator.invoke(context, a, b), int.class);
        }
        return StreamELResolver::naturalCompare;
    }

    @SuppressWarnings("unchecked")
    private static int naturalCompare(Object a, Object b) {
        if (!(a instanceof Comparable<?> comparable) || !(b instanceof Comparable<?>)) {
            throw new ELException("Natural ordering requires Comparable elements");
        }
        return ((Comparable<Object>) comparable).compareTo(b);
    }

    private static void arity(String name, Object[] args, int... allowed) {
        for (int count : allowed) if (args.length == count) return;
        throw new MethodNotFoundException("Invalid argument count for EL operation " + name);
    }

    @Override public Object getValue(ELContext context, Object base, Object property) { return null; }
    @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
    @Override public void setValue(ELContext context, Object base, Object property, Object value) {}
    @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return false; }
    @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return null; }
}

