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
package io.vidocq.humboldt.cdi;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * CDI interceptor that wraps each method annotated with
 * {@link io.opentelemetry.instrumentation.annotations.WithSpan @WithSpan}
 * (the standard OpenTelemetry public API annotation) in an OTel span.
 *
 * <p>Bound via {@link SpanBinding} (internal marker added automatically by
 * {@link HumboldtBuildCompatibleExtension} at CDI build time). The end user
 * therefore only writes one annotation: {@code @WithSpan} from OTel.</p>
 *
 * <p>Invocation lifecycle:</p>
 * <ol>
 *   <li>Resolves the OTel annotation on the method (then falls back to the class), once per method</li>
 *   <li>Derives the span name — {@code @WithSpan.value()} if non-empty,
 *       otherwise {@code Class.simpleName + "." + methodName}</li>
 *   <li>Creates the span via {@link Tracer#spanBuilder(String)}, with the {@code code.function.name}
 *       attribute ({@code <binary class name>.<method>}, mandatory since MicroProfile Telemetry 2.2)</li>
 *   <li>Parent context: {@code Context.current()} by default; {@code Context.root()} when
 *       {@code @WithSpan(inheritContext = false)} — the span starts a new trace and the call runs
 *       without the caller's context (no caller baggage either)</li>
 *   <li>{@code try (Scope = parent.with(span).makeCurrent()) { proceed(); }}</li>
 *   <li>On exception: {@code span.recordException(t)} + ERROR status, rethrow</li>
 *   <li>{@code span.end()} in finally</li>
 * </ol>
 *
 * <p>Priority: {@link Interceptor.Priority#APPLICATION} + 1 — executed after
 * platform interceptors (transaction, security) but before user-defined business
 * interceptors.</p>
 */
@Interceptor
@SpanBinding
@Priority(Interceptor.Priority.APPLICATION + 1)
public class WithSpanInterceptor {

    /**
     * Attribute name {@code code.function.name} as defined by the OpenTelemetry semantic conventions (no semconv
     * dependency needed) — mandatory on @WithSpan spans since MP Telemetry 2.2.
     */
    private static final AttributeKey<String> CODE_FUNCTION_NAME = AttributeKey.stringKey("code.function.name");

    /** The plan of each intercepted method, per declaring class so that an undeployed class can be collected. */
    private static final ClassValue<Map<Method, SpanPlan>> PLANS = new ClassValue<>() {
        @Override
        protected Map<Method, SpanPlan> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    @AroundInvoke
    public Object aroundInvoke(InvocationContext ctx) throws Exception {
        Method method = ctx.getMethod();
        SpanPlan plan = PLANS.get(method.getDeclaringClass()).computeIfAbsent(method, SpanPlan::of);

        SpanBuilder builder = tracer().spanBuilder(plan.name())
                .setSpanKind(plan.kind())
                .setAttribute(CODE_FUNCTION_NAME, plan.function());
        // instrumentation-annotations 2.30+: inheritContext = false makes Context.root() the parent, so the call
        // starts a new trace and runs without anything the caller put in its context (baggage included).
        Context parent = plan.detached() ? Context.root() : Context.current();
        Span span = builder.setParent(parent).startSpan();
        plan.applyAttributes(span, ctx.getParameters());
        try (var _ = parent.with(span).makeCurrent()) {
            return ctx.proceed();
        } catch (Throwable th) {
            span.recordException(th);
            span.setStatus(StatusCode.ERROR,
                    th.getClass().getSimpleName() + ": " + (th.getMessage() != null ? th.getMessage() : ""));
            if (th instanceof Exception ex) throw ex;
            if (th instanceof Error er) throw er;
            throw new UndeclaredThrowableException(th);
        } finally {
            span.end();
        }
    }

    /**
     * Overridable in a subclass to supply a non-global Tracer (typically
     * via CDI {@code @Inject} in an M6d variant).
     */
    protected Tracer tracer() {
        return openTelemetry().getTracer("io.vidocq.humboldt.cdi");
    }

    /**
     * Indirection hook to {@link GlobalOpenTelemetry} — allows tests to
     * override and avoid global initialisation.
     */
    protected OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }

    /**
     * What an intercepted method's spans share, read once from its annotations: the span name ({@code @WithSpan.value()}
     * if non-empty, otherwise {@code Class.simpleName + "." + methodName}), its kind, whether it detaches from the
     * caller's context, its {@code code.function.name}, and the {@code @SpanAttribute} parameters.
     */
    private record SpanPlan(String name, SpanKind kind, boolean detached, String function,
                            List<AttributeBinding> attributes) {

        static SpanPlan of(Method method) {
            WithSpan annotation = method.getAnnotation(WithSpan.class);
            if (annotation == null) annotation = method.getDeclaringClass().getAnnotation(WithSpan.class);
            String name = annotation == null || annotation.value().isEmpty()
                    ? method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    : annotation.value();
            return new SpanPlan(name,
                    annotation == null ? SpanKind.INTERNAL : annotation.kind(),
                    annotation != null && !annotation.inheritContext(),
                    method.getDeclaringClass().getName() + "." + method.getName(),
                    AttributeBinding.of(method));
        }

        void applyAttributes(Span span, Object[] args) {
            if (args == null) return;
            for (AttributeBinding binding : attributes) {
                if (binding.index() < args.length && args[binding.index()] != null) {
                    binding.setter().accept(span, args[binding.index()]);
                }
            }
        }
    }

    /**
     * A {@link SpanAttribute @SpanAttribute} parameter: its index and how its value is set on the span. If
     * {@code @SpanAttribute.value()} is empty, the key is the parameter name (requires compilation with
     * {@code -parameters}). As in the OpenTelemetry instrumentation annotations, the parameter's type picks the
     * attribute's: integral numbers are {@code long}, floating ones {@code double}, {@code boolean} stays boolean,
     * arrays of those and of {@code String} are array attributes, and any other value is set through
     * {@link String#valueOf(Object)}.
     */
    private record AttributeBinding(int index, BiConsumer<Span, Object> setter) {

        static List<AttributeBinding> of(Method method) {
            Parameter[] params = method.getParameters();
            List<AttributeBinding> bindings = new ArrayList<>();
            for (int i = 0; i < params.length; i++) {
                SpanAttribute attr = params[i].getAnnotation(SpanAttribute.class);
                if (attr != null) {
                    String key = attr.value().isEmpty() ? params[i].getName() : attr.value();
                    bindings.add(new AttributeBinding(i, setter(key, params[i].getType())));
                }
            }
            return List.copyOf(bindings);
        }

        private static BiConsumer<Span, Object> setter(String name, Class<?> type) {
            Class<?> boxed = type.isPrimitive() ? MethodType.methodType(type).wrap().returnType() : type;
            if (boxed == Long.class || boxed == Integer.class || boxed == Short.class || boxed == Byte.class) {
                AttributeKey<Long> key = AttributeKey.longKey(name);
                return (span, value) -> span.setAttribute(key, ((Number) value).longValue());
            }
            if (boxed == Double.class || boxed == Float.class) {
                AttributeKey<Double> key = AttributeKey.doubleKey(name);
                return (span, value) -> span.setAttribute(key, ((Number) value).doubleValue());
            }
            if (boxed == Boolean.class) {
                AttributeKey<Boolean> key = AttributeKey.booleanKey(name);
                return (span, value) -> span.setAttribute(key, (Boolean) value);
            }
            if (type == String[].class) {
                AttributeKey<List<String>> key = AttributeKey.stringArrayKey(name);
                return (span, value) -> span.setAttribute(key, Arrays.asList((String[]) value));
            }
            if (type == long[].class) {
                AttributeKey<List<Long>> key = AttributeKey.longArrayKey(name);
                return (span, value) -> span.setAttribute(key, Arrays.stream((long[]) value).boxed().toList());
            }
            if (type == int[].class) {
                AttributeKey<List<Long>> key = AttributeKey.longArrayKey(name);
                return (span, value) -> span.setAttribute(key,
                        Arrays.stream((int[]) value).asLongStream().boxed().toList());
            }
            if (type == double[].class) {
                AttributeKey<List<Double>> key = AttributeKey.doubleArrayKey(name);
                return (span, value) -> span.setAttribute(key, Arrays.stream((double[]) value).boxed().toList());
            }
            if (type == boolean[].class) {
                AttributeKey<List<Boolean>> key = AttributeKey.booleanArrayKey(name);
                return (span, value) -> {
                    boolean[] values = (boolean[]) value;
                    List<Boolean> list = new ArrayList<>(values.length);
                    for (boolean b : values) list.add(b);
                    span.setAttribute(key, list);
                };
            }
            AttributeKey<String> key = AttributeKey.stringKey(name);
            return (span, value) -> span.setAttribute(key, String.valueOf(value));
        }
    }
}
