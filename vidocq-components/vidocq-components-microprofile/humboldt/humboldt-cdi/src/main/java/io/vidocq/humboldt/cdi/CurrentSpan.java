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
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * The injected {@link Span}: every call goes to {@link Span#current()} at the time of the call, not of the injection
 * (MP Telemetry 2.2 §"Access to the OpenTelemetry Tracing API", {@code SpanBeanTest.spanBeanChange}). Default methods
 * are forwarded too, so a span that overrides one keeps its behaviour, and {@link #storeInContext(Context)} stores the
 * current span itself. Equality is identity: two injected spans are not the current span.
 */
final class CurrentSpan implements Span {

    @Override
    public Span setAttribute(String key, String value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public Span setAttribute(String key, long value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public Span setAttribute(String key, double value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public Span setAttribute(String key, boolean value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public Span setAttribute(String key, Value<?> value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public Span setAttribute(AttributeKey<Long> key, int value) {
        Span.current().setAttribute(key, value);
        return this;
    }

    @Override
    public Span setAllAttributes(Attributes attributes) {
        Span.current().setAllAttributes(attributes);
        return this;
    }

    @Override
    public Span addEvent(String name) {
        Span.current().addEvent(name);
        return this;
    }

    @Override
    public Span addEvent(String name, long timestamp, TimeUnit unit) {
        Span.current().addEvent(name, timestamp, unit);
        return this;
    }

    @Override
    public Span addEvent(String name, Instant timestamp) {
        Span.current().addEvent(name, timestamp);
        return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
        Span.current().addEvent(name, attributes);
        return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
        Span.current().addEvent(name, attributes, timestamp, unit);
        return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, Instant timestamp) {
        Span.current().addEvent(name, attributes, timestamp);
        return this;
    }

    @Override
    public Span setStatus(StatusCode statusCode) {
        Span.current().setStatus(statusCode);
        return this;
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
        Span.current().setStatus(statusCode, description);
        return this;
    }

    @Override
    public Span recordException(Throwable exception) {
        Span.current().recordException(exception);
        return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
        Span.current().recordException(exception, additionalAttributes);
        return this;
    }

    @Override
    public Span updateName(String name) {
        Span.current().updateName(name);
        return this;
    }

    @Override
    public Span addLink(SpanContext spanContext) {
        Span.current().addLink(spanContext);
        return this;
    }

    @Override
    public Span addLink(SpanContext spanContext, Attributes attributes) {
        Span.current().addLink(spanContext, attributes);
        return this;
    }

    @Override
    public void end() {
        Span.current().end();
    }

    @Override
    public void end(long timestamp, TimeUnit unit) {
        Span.current().end(timestamp, unit);
    }

    @Override
    public void end(Instant timestamp) {
        Span.current().end(timestamp);
    }

    @Override
    public SpanContext getSpanContext() {
        return Span.current().getSpanContext();
    }

    @Override
    public boolean isRecording() {
        return Span.current().isRecording();
    }

    @Override
    public Context storeInContext(Context context) {
        return Span.current().storeInContext(context);
    }

    @Override
    public String toString() {
        return "CurrentSpan[" + Span.current() + "]";
    }
}
