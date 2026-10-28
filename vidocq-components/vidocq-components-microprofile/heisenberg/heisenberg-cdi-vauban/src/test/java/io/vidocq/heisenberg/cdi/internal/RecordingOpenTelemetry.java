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
package io.vidocq.heisenberg.cdi.internal;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-only OpenTelemetry whose metrics API records every measurement, so the tests can read back
 * what {@link OtelFtMetrics} publishes without an OpenTelemetry SDK. Every metrics interface
 * (provider, meter, builders, instruments) is a {@link Proxy} that knows the instrument it builds.
 */
final class RecordingOpenTelemetry {

    /** One {@code add} or {@code record} call on an instrument. */
    record Point(String instrument, double value, Attributes attributes) {

        String attribute(String key) {
            return attributes.get(AttributeKey.stringKey(key));
        }
    }

    /** The calls that name a meter rather than an instrument. */
    private static final List<String> METER_LOOKUPS = List.of("get", "getMeter", "meterBuilder");

    private final List<Point> points = new CopyOnWriteArrayList<>();
    private final Map<String, String> units = new ConcurrentHashMap<>();
    private final Map<String, List<Double>> bucketAdvice = new ConcurrentHashMap<>();
    private final List<String> meterNames = new CopyOnWriteArrayList<>();

    OpenTelemetry openTelemetry() {
        return proxy(OpenTelemetry.class, null);
    }

    List<Point> points(String instrument) {
        return points.stream().filter(p -> p.instrument().equals(instrument)).toList();
    }

    /** The points of {@code instrument} whose string attribute {@code key} equals {@code value}. */
    List<Point> points(String instrument, String key, String value) {
        return points(instrument).stream().filter(p -> value.equals(p.attribute(key))).toList();
    }

    double sum(String instrument, String key, String value) {
        return points(instrument, key, value).stream().mapToDouble(Point::value).sum();
    }

    String unit(String instrument) {
        return units.get(instrument);
    }

    List<Double> bucketAdvice(String instrument) {
        return bucketAdvice.get(instrument);
    }

    List<String> meterNames() {
        return meterNames;
    }

    private <T> T proxy(Class<T> type, String instrument) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> handle(self, type, instrument, method, args)));
    }

    @SuppressWarnings("unchecked")
    private Object handle(Object self, Class<?> type, String instrument, Method method, Object[] args) {
        switch (method.getName()) {
            case "toString":
                return type.getSimpleName() + "[" + instrument + "]";
            case "hashCode":
                return System.identityHashCode(self);
            case "equals":
                return self == args[0];
            case "getTracerProvider":
                return TracerProvider.noop();
            case "getPropagators":
                return ContextPropagators.noop();
            case "setUnit":
                units.put(instrument, (String) args[0]);
                return self;
            case "setExplicitBucketBoundariesAdvice":
                bucketAdvice.put(instrument, List.copyOf((List<Double>) args[0]));
                return self;
            case "add", "record":
                Attributes attributes = args.length > 1 ? (Attributes) args[1] : Attributes.empty();
                points.add(new Point(instrument, ((Number) args[0]).doubleValue(), attributes));
                return null;
            default:
                return chain(self, type, instrument, method, args);
        }
    }

    private Object chain(Object self, Class<?> type, String instrument, Method method, Object[] args) {
        Class<?> returnType = method.getReturnType();
        if (returnType == type) {
            return self;
        }
        if (!returnType.isInterface()) {
            throw new UnsupportedOperationException(method.toString());
        }
        String name = instrument;
        if (args != null && args.length == 1 && args[0] instanceof String named) {
            if (METER_LOOKUPS.contains(method.getName())) {
                meterNames.add(named);
            } else {
                name = named;
            }
        }
        return proxy(returnType, name);
    }
}
