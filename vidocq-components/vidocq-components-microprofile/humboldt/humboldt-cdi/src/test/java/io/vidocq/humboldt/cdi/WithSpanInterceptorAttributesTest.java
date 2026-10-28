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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.vidocq.humboldt.cdi.WithSpanInterceptorTest.TestInvocationContext;
import io.vidocq.humboldt.cdi.WithSpanInterceptorTest.TestableInterceptor;
import io.vidocq.humboldt.sdk.trace.SdkTracerProvider;
import io.vidocq.humboldt.sdk.trace.SimpleSpanProcessor;
import io.vidocq.humboldt.sdk.trace.data.SpanData;
import io.vidocq.humboldt.sdk.trace.export.InMemorySpanExporter;
import io.vidocq.humboldt.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @SpanAttribute} parameters and the exception paths of {@link WithSpanInterceptor}.
 */
class WithSpanInterceptorAttributesTest {

    private static final String TRACED = "traced";
    private static final String OK = "ok";
    private static final String FAILS = "fails";

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private TestableInterceptor interceptor;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        provider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        interceptor = new TestableInterceptor(provider);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void span_attributes_use_the_annotation_value_or_the_parameter_name() throws Exception {
        Method m = method(TRACED);
        interceptor.aroundInvoke(new TestInvocationContext(m, new Object[]{"alice", 42, "ignored"}, () -> OK));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals("alice", span.attributes().get(AttributeKey.stringKey("user.name")));
        assertEquals("42", span.attributes().get(AttributeKey.stringKey(m.getParameters()[1].getName())),
                "an empty @SpanAttribute uses the parameter name, the value through String.valueOf");
        assertNull(span.attributes().get(AttributeKey.stringKey(m.getParameters()[2].getName())),
                "a parameter without @SpanAttribute is not recorded");
    }

    @Test
    void null_arguments_and_missing_arguments_are_skipped() throws Exception {
        Method m = method(TRACED);
        interceptor.aroundInvoke(new TestInvocationContext(m, new Object[]{null}, () -> OK));
        interceptor.aroundInvoke(new TestInvocationContext(m, null, () -> OK));

        for (SpanData span : exporter.getFinishedSpans()) {
            assertNull(span.attributes().get(AttributeKey.stringKey("user.name")));
            assertEquals(1, span.attributes().size(), "only code.function.name is set");
        }
    }

    @Test
    void exception_without_message_has_an_empty_description_suffix() {
        TestInvocationContext ctx = new TestInvocationContext(method(FAILS), new Object[0], () -> {
            throw new IllegalArgumentException();
        });
        assertThrows(IllegalArgumentException.class, () -> interceptor.aroundInvoke(ctx));

        SpanData span = exporter.getFinishedSpans().getFirst();
        assertEquals(StatusCode.ERROR, span.status().code());
        assertEquals("IllegalArgumentException: ", span.status().description());
        assertTrue(span.hasEnded());
    }

    @Test
    void errors_are_rethrown_as_is() {
        AssertionError error = new AssertionError("fatal");
        TestInvocationContext ctx = new TestInvocationContext(method(FAILS), new Object[0], () -> {
            throw error;
        });
        AssertionError thrown = assertThrows(AssertionError.class, () -> interceptor.aroundInvoke(ctx));

        assertSame(error, thrown);
        assertEquals("AssertionError: fatal", exporter.getFinishedSpans().getFirst().status().description());
    }

    @Test
    void other_throwables_are_wrapped_in_an_undeclared_throwable_exception() {
        OddThrowable odd = new OddThrowable();
        TestInvocationContext ctx = new TestInvocationContext(method(FAILS), new Object[0], () -> {
            throw WithSpanInterceptorAttributesTest.<RuntimeException>sneaky(odd);
        });
        UndeclaredThrowableException thrown =
                assertThrows(UndeclaredThrowableException.class, () -> interceptor.aroundInvoke(ctx));

        assertSame(odd, thrown.getCause());
        assertEquals("OddThrowable: odd", exporter.getFinishedSpans().getFirst().status().description());
    }

    private static Method method(String name) {
        for (Method m : Service.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new IllegalArgumentException(name);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** A throwable that is neither an {@link Exception} nor an {@link Error}. */
    static final class OddThrowable extends Throwable {
        OddThrowable() {
            super("odd");
        }
    }

    /** Annotated target, never instantiated (no container). */
    static class Service {
        @WithSpan
        public String traced(@SpanAttribute("user.name") String user, @SpanAttribute int count, String other) {
            return user + count + other;
        }

        @WithSpan
        public String fails() {
            return "never";
        }
    }
}
