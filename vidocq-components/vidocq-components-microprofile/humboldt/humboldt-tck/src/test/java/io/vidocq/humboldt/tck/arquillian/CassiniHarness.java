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
package io.vidocq.humboldt.tck.arquillian;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.internal.DefaultCassiniHttpAdapter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Cassini-on-Chappe harness — a simplified version of
 * cassini-tck's {@code CassiniTestHarness}, adapted for the
 * Humboldt Arquillian runner.
 *
 * <p>Starts a Chappe server on a free port, mounts a Cassini dispatcher
 * on the provided {@code @Path}/{@code @Provider} classes, and
 * exposes the {@code baseUrl} for {@code @ArquillianResource URL url}.</p>
 *
 * <p>No direct reuse of cassini-tck (out of reactor + not
 * installed in the local M2). The code is aligned but reduced to the strict minimum
 * for the MP Telemetry HTTP TCKs.</p>
 */
public final class CassiniHarness implements AutoCloseable {

    private final Server server;
    private final int port;
    private final String baseUrl;

    private CassiniHarness(Server server, int port, String baseUrl) {
        this.server = server;
        this.port = port;
        this.baseUrl = baseUrl;
    }

    public int port() { return port; }
    public String baseUrl() { return baseUrl; }

    @Override public void close() {
        try { server.stop(); } catch (RuntimeException ignored) {}
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final Map<Class<?>, Object> beans = new HashMap<>();
        private final Set<Class<?>> perRequestClasses = new LinkedHashSet<>();
        private final FilterRegistry filters = new FilterRegistry();
        private final ExceptionMapperRegistry exceptionMappers = new ExceptionMapperRegistry();
        private final MessageBodyRegistry bodies = new MessageBodyRegistry();
        private String contextPath = "/";

        public Builder resourceClass(Class<?> cls) {
            if (!java.lang.reflect.Modifier.isPublic(cls.getModifiers())) return this;
            if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) return this;
            perRequestClasses.add(cls);
            return this;
        }

        public Builder provider(Object instance) {
            filters.register(instance);
            if (instance instanceof ExceptionMapper<?> em) registerExceptionMapper(em);
            if (instance instanceof MessageBodyReader<?> r) bodies.addReader(r);
            if (instance instanceof MessageBodyWriter<?> w) bodies.addWriter(w);
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void registerExceptionMapper(ExceptionMapper em) {
            for (var iface : em.getClass().getGenericInterfaces()) {
                if (iface instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() == ExceptionMapper.class
                        && pt.getActualTypeArguments().length == 1
                        && pt.getActualTypeArguments()[0] instanceof Class<?> c
                        && Throwable.class.isAssignableFrom(c)) {
                    exceptionMappers.register((Class) c, em);
                    return;
                }
            }
        }

        public Builder contextPath(String path) {
            this.contextPath = path == null || path.isEmpty() ? "/" : path;
            return this;
        }

        public CassiniHarness start() {
            Set<Class<?>> allClasses = new LinkedHashSet<>(beans.keySet());
            allClasses.addAll(perRequestClasses);
            List<ResourceMethod> routes = ResourceScanner.discover(allClasses.toArray(Class<?>[]::new));
            filters.applyDynamicFeatures(routes);
            UriRouter router = new UriRouter(routes);
            // Retrieves the current Vauban container (initialized by
            // HumboldtDeployableContainer.deploy()) so resolution of resources/providers
            // goes through CDI — ensuring that @Inject on resource fields
            // (Tracer, Span, Baggage, OpenTelemetry...) is wired when the class
            // is a Vauban bean.
            //
            // Current limitation: the Cassini BCE (cassini-cdi-vauban
            // CassiniScopeExtension) that adds @RequestScoped to @Path classes without
            // an explicit scope does NOT apply to classes added through addBeanClass()
            // at runtime — only to beans discovered at compile time via APT.
            // Consequence: TCK resources such as BaggageResource (an inner class without
            // scope) fall back to `new` and their @Inject fields stay null.
            // Complete fix: apply BCEs at runtime on the Vauban side (separate work)
            // or pre-process @Path classes in HumboldtDeployableContainer to
            // add a synthetic @RequestScoped before addBeanClass().
            io.vidocq.vauban.core.container.VaubanContainer cdi =
                    io.vidocq.vauban.core.container.VaubanContainer.current();
            java.util.function.Function<Class<?>, Object> resolver = cls -> {
                Object fixed = beans.get(cls);
                if (fixed != null) return fixed;
                if (cdi != null) {
                    try {
                        return cdi.select(cls);
                    } catch (RuntimeException ignored) {
                        // Fallback if the class is not known to Vauban (BCE not applied).
                    }
                }
                try {
                    return cls.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate " + cls, e);
                }
            };
            Invoker invoker = new Invoker(resolver, bodies, exceptionMappers);
            invoker.setFilters(filters);
            DefaultCassiniHttpAdapter engine = new DefaultCassiniHttpAdapter(router, invoker);
            ChappeHttpAdapter bridge = new ChappeHttpAdapter(engine);
            final String prefix = "/".equals(contextPath) ? "" : contextPath;
            Handler rootHandler = prefix.isEmpty()
                    ? bridge
                    : new ContextStrippingHandler(prefix, bridge);
            // HBT-2 resolved in cassini-cdi-vauban: VaubanRequestScopeFilter
            // self-registers through VaubanBeanProvider.getResourceClasses() and enables /
            // disables the RequestContext around each dispatch — no need for the
            // RequestScopeActivatingHandler workaround here anymore.

            RuntimeException last = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                int port;
                try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                catch (Exception e) { throw new RuntimeException(e); }
                try {
                    Server server = Server.builder()
                            .host("127.0.0.1").port(port).handler(rootHandler).build();
                    server.start();
                    String url = "http://127.0.0.1:" + port + (prefix.isEmpty() ? "/" : prefix + "/");
                    return new CassiniHarness(server, port, url);
                } catch (RuntimeException e) {
                    last = e;
                }
            }
            throw last;
        }
    }

    /** Strips the contextPath before delegating to the Cassini bridge. */
    private record ContextStrippingHandler(String prefix, Handler delegate) implements Handler {
        @Override public Response handle(Request request) throws Exception {
            String path = request.path();
            if (path == null) path = "/";
            if (!path.startsWith(prefix)) {
                return Response.builder().status(StatusCode.NOT_FOUND).body(Body.empty()).build();
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) stripped = "/";
            final String newPath = stripped;
            Request remapped = new Request() {
                @Override public HttpMethod method() { return request.method(); }
                @Override public URI uri() { return request.uri(); }
                @Override public String path() { return newPath; }
                @Override public String query() { return request.query(); }
                @Override public HttpVersion version() { return request.version(); }
                @Override public Headers headers() { return request.headers(); }
                @Override public Body body() { return request.body(); }
                @Override public Map<String, String> pathParams() { return request.pathParams(); }
                @Override public Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return prefix; }
                @Override public String pathInfo() { return newPath; }
            };
            return delegate.handle(remapped);
        }
    }
}
