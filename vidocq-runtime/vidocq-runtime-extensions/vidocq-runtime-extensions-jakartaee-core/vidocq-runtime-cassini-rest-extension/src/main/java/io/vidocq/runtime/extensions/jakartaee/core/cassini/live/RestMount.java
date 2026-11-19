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
package io.vidocq.runtime.extensions.jakartaee.core.cassini.live;

import io.vidocq.cassini.spi.http.CassiniStatistics;
import io.vidocq.cassini.spi.http.RouteDescription;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.DynamicFeature;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One Cassini stack mounted on Chappe, as the startup report and the dev console describe it. The record holds no
 * {@link Class} and no object of the application — names, and the stack's own counters — so keeping it across a dev
 * reload keeps no class of the previous application alive.
 *
 * <p>Public since Vidocq/vidocq#143: the runtime extension's {@code RestStartupSection} and the {@code -dev}
 * companion's {@code RestPanel} both read it from outside this package.
 *
 * @param name           {@code vidocq.rest} for the automatic mount, else the name of the declarative mount
 * @param listener       the Chappe listener it is mounted on
 * @param prefix         the mount prefix, empty for the root
 * @param stripPrefix    whether Chappe removes the prefix before Cassini sees the path
 * @param resources      the binary names of its {@code @Path} classes, sorted
 * @param routes         its routes, in match order
 * @param providerGroups its {@code @Provider} classes by the JAX-RS contracts they implement
 * @param statistics     the counters the stack keeps, {@code null} when it was built without them
 */
public record RestMount(String name, String listener, String prefix, boolean stripPrefix, List<String> resources,
                        List<RouteDescription> routes, List<ProviderGroup> providerGroups,
                        CassiniStatistics statistics) {

    /**
     * The {@code @Provider} classes that implement one JAX-RS contract, sorted by binary name. A class that implements
     * several contracts is in several groups.
     *
     * @param kind    what the contract is, such as {@code exception mappers}
     * @param classes the binary names of the classes
     */
    public record ProviderGroup(String kind, List<String> classes) {
        public ProviderGroup {
            classes = List.copyOf(classes);
        }
    }

    /** A contract a provider can implement, and how the report names its group. */
    private record Kind(String name, Class<?> contract) {}

    /** The contracts, in the order the report lists them: the request path first, then error handling. */
    private static final List<Kind> KINDS = List.of(
            new Kind("request filters", ContainerRequestFilter.class),
            new Kind("response filters", ContainerResponseFilter.class),
            new Kind("reader interceptors", ReaderInterceptor.class),
            new Kind("writer interceptors", WriterInterceptor.class),
            new Kind("body readers", MessageBodyReader.class),
            new Kind("body writers", MessageBodyWriter.class),
            new Kind("param converters", ParamConverterProvider.class),
            new Kind("context resolvers", ContextResolver.class),
            new Kind("exception mappers", ExceptionMapper.class),
            new Kind("features", Feature.class),
            new Kind("dynamic features", DynamicFeature.class));

    public RestMount {
        resources = List.copyOf(resources);
        routes = List.copyOf(routes);
        providerGroups = List.copyOf(providerGroups);
    }

    /**
     * Describes a mounted stack. Reads the classes' annotations and interfaces only: it creates no instance.
     *
     * @param classes what the {@code BeanProvider} handed Cassini, {@code @Path} and {@code @Provider} classes
     * @param routes     what the stack resolved, {@code CassiniStack.routes()}
     * @param statistics what it counts, {@code CassiniStack.statistics()}, or {@code null}
     */
    public static RestMount of(String name, String listener, String prefix, boolean stripPrefix,
                               Set<Class<?>> classes, List<RouteDescription> routes, CassiniStatistics statistics) {
        List<String> resources = new ArrayList<>();
        List<Class<?>> providers = new ArrayList<>();
        for (Class<?> c : classes) {
            if (c.isAnnotationPresent(Path.class)) {
                resources.add(c.getName());
            }
            if (c.isAnnotationPresent(Provider.class)) {
                providers.add(c);
            }
        }
        List<ProviderGroup> groups = new ArrayList<>();
        for (Kind kind : KINDS) {
            List<String> members = providers.stream()
                    .filter(kind.contract()::isAssignableFrom)
                    .map(Class::getName)
                    .sorted()
                    .toList();
            if (!members.isEmpty()) {
                groups.add(new ProviderGroup(kind.name(), members));
            }
        }
        return new RestMount(name, listener, prefix, stripPrefix, resources.stream().sorted().toList(), routes,
                groups, statistics);
    }

    /** The binary names of the {@code @Provider} classes, each once, sorted. */
    public List<String> providers() {
        return providerGroups.stream().flatMap(g -> g.classes().stream()).distinct().sorted().toList();
    }

    /**
     * Where a route is on the listener: under the prefix when Chappe strips it, as Cassini routes it otherwise, since
     * Cassini then sees, and matches, the full path.
     */
    public String pathOnListener(RouteDescription route) {
        return stripPrefix ? prefix + route.path() : route.path();
    }

    /** The prefix as the report prints it: {@code /} for the root. */
    public String displayPrefix() {
        return prefix.isEmpty() ? "/" : prefix;
    }
}
