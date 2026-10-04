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
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

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
 *   <li>Resolves the OTel annotation on the method (then falls back to the class)</li>
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

    @AroundInvoke
    public Object aroundInvoke(InvocationContext ctx) throws Exception {
        Method method = ctx.getMethod();
        WithSpan annotation = resolveAnnotation(method);
        String spanName = (annotation == null || annotation.value().isEmpty())
                ? defaultName(method)
                : annotation.value();
        SpanKind kind = annotation == null ? SpanKind.INTERNAL : annotation.kind();

        Tracer t = tracer();
        SpanBuilder builder = t.spanBuilder(spanName)
                .setSpanKind(kind)
                .setAttribute(CODE_FUNCTION_NAME, method.getDeclaringClass().getName() + "." + method.getName());
        // instrumentation-annotations 2.30+: inheritContext = false makes Context.root() the parent, so the call
        // starts a new trace and runs without anything the caller put in its context (baggage included).
        boolean detached = annotation != null && !annotation.inheritContext();
        Context parent = detached ? Context.root() : Context.current();
        Span span = builder.setParent(parent).startSpan();
        applySpanAttributes(span, method, ctx.getParameters());
        try (Scope ignored = parent.with(span).makeCurrent()) {
            return ctx.proceed();
        } catch (Throwable th) {
            span.recordException(th);
            span.setStatus(StatusCode.ERROR,
                    th.getClass().getSimpleName() + ": " + (th.getMessage() != null ? th.getMessage() : ""));
            if (th instanceof Exception ex) throw ex;
            if (th instanceof Error er) throw er;
            throw new RuntimeException(th);
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

    private static WithSpan resolveAnnotation(Method method) {
        WithSpan onMethod = method.getAnnotation(WithSpan.class);
        if (onMethod != null) return onMethod;
        return method.getDeclaringClass().getAnnotation(WithSpan.class);
    }

    private static String defaultName(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /**
     * Scans the method parameters for {@link SpanAttribute @SpanAttribute} and
     * sets each non-null value on the span. If {@code @SpanAttribute.value()} is
     * empty, uses the parameter name (requires compilation with {@code -parameters}).
     * Values are converted via {@link String#valueOf(Object)} — conformant with the
     * OTel instrumentation annotations spec which requires attributes as Strings.
     */
    private static void applySpanAttributes(Span span, Method method, Object[] args) {
        if (args == null || args.length == 0) return;
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length && i < args.length; i++) {
            SpanAttribute attr = params[i].getAnnotation(SpanAttribute.class);
            if (attr == null) continue;
            Object value = args[i];
            if (value == null) continue;
            String key = attr.value().isEmpty() ? params[i].getName() : attr.value();
            span.setAttribute(key, String.valueOf(value));
        }
    }
}
