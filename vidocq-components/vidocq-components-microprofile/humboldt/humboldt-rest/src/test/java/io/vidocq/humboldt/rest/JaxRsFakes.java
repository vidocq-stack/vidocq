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
package io.vidocq.humboldt.rest;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.MeterProvider;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Jakarta REST context fakes built with {@link Proxy}: only the methods the Humboldt filters call are
 * routed, every other method answers a neutral default for its return type.
 */
final class JaxRsFakes {

    private JaxRsFakes() {
    }

    /** A server request whose properties are kept in {@code props}. */
    static ContainerRequestContext containerRequest(String method, UriInfo uriInfo,
                                                    MultivaluedMap<String, String> headers,
                                                    Map<String, Object> props) {
        return proxy(ContainerRequestContext.class, (m, args) -> switch (m.getName()) {
            case "getMethod" -> method;
            case "getHeaders" -> headers;
            case "getUriInfo" -> uriInfo;
            case "getProperty" -> props.get(args[0]);
            case "setProperty" -> { props.put((String) args[0], args[1]); yield null; }
            case "removeProperty" -> { props.remove(args[0]); yield null; }
            case "getPropertyNames" -> props.keySet();
            default -> defaultFor(m);
        });
    }

    static ContainerRequestContext containerRequest(String method, UriInfo uriInfo) {
        return containerRequest(method, uriInfo, new MultivaluedHashMap<>(), new HashMap<>());
    }

    static ContainerResponseContext containerResponse(int status) {
        return proxy(ContainerResponseContext.class, (m, _) ->
                "getStatus".equals(m.getName()) ? status : defaultFor(m));
    }

    /**
     * @param baseUri    the application base URI (may be {@code null})
     * @param path       the path relative to the base URI, as {@link UriInfo#getPath()} returns it
     * @param requestUri the request URI
     */
    static UriInfo uriInfo(URI baseUri, String path, URI requestUri) {
        return proxy(UriInfo.class, (m, _) -> switch (m.getName()) {
            case "getBaseUri" -> baseUri;
            case "getPath" -> path;
            case "getRequestUri" -> requestUri;
            default -> defaultFor(m);
        });
    }

    static ClientRequestContext clientRequest(String method, URI uri, MultivaluedMap<String, Object> headers,
                                              Map<String, Object> props) {
        return proxy(ClientRequestContext.class, (m, args) -> switch (m.getName()) {
            case "getMethod" -> method;
            case "getUri" -> uri;
            case "getHeaders" -> headers;
            case "getProperty" -> props.get(args[0]);
            case "setProperty" -> { props.put((String) args[0], args[1]); yield null; }
            case "removeProperty" -> { props.remove(args[0]); yield null; }
            case "getPropertyNames" -> props.keySet();
            default -> defaultFor(m);
        });
    }

    static ClientResponseContext clientResponse(int status) {
        return proxy(ClientResponseContext.class, (m, _) ->
                "getStatus".equals(m.getName()) ? status : defaultFor(m));
    }

    /**
     * A {@link MeterProvider} whose histograms append every {@code record(value, attributes)} to {@code records}.
     */
    static MeterProvider recordingMeterProvider(List<Attributes> records) {
        Object histogram = proxy(io.opentelemetry.api.metrics.DoubleHistogram.class, (m, args) -> {
            if ("record".equals(m.getName()) && args.length >= 2) records.add((Attributes) args[1]);
            return null;
        });
        Object[] builder = new Object[1];
        builder[0] = proxy(io.opentelemetry.api.metrics.DoubleHistogramBuilder.class, (m, _) ->
                "build".equals(m.getName()) ? histogram : builder[0]);
        Object meter = proxy(io.opentelemetry.api.metrics.Meter.class, (m, _) ->
                "histogramBuilder".equals(m.getName()) ? builder[0] : defaultFor(m));
        return meterProviderReturning(meter);
    }

    /** A {@link MeterProvider} whose meters fail when an instrument is requested. */
    static MeterProvider failingMeterProvider() {
        Object meter = proxy(io.opentelemetry.api.metrics.Meter.class, (_, _) -> {
            throw new IllegalStateException("meter provider not ready");
        });
        return meterProviderReturning(meter);
    }

    private static MeterProvider meterProviderReturning(Object meter) {
        Object meterBuilder = proxy(io.opentelemetry.api.metrics.MeterBuilder.class, (m, _) ->
                "build".equals(m.getName()) ? meter : null);
        return proxy(MeterProvider.class, (m, _) ->
                "meterBuilder".equals(m.getName()) ? meterBuilder : meter);
    }

    @FunctionalInterface
    interface Handler {
        Object handle(Method m, Object[] args);
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(JaxRsFakes.class.getClassLoader(), new Class<?>[]{type},
                (p, m, args) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return switch (m.getName()) {
                            case "hashCode" -> System.identityHashCode(p);
                            case "equals" -> p == args[0];
                            default -> type.getSimpleName() + " fake";
                        };
                    }
                    return handler.handle(m, args == null ? new Object[0] : args);
                });
    }

    /** Neutral value based on the return type — avoids NPEs on methods the filters do not call. */
    static Object defaultFor(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == List.class) return List.of();
        if (r == Map.class) return Map.of();
        if (r == Set.class) return Set.of();
        if (MultivaluedMap.class.isAssignableFrom(r)) return new MultivaluedHashMap<>();
        return null;
    }
}
