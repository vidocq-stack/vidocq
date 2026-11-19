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
package io.vidocq.runtime.extensions.microprofile.knock;

import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.Verbosity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link StartupReportContext} answering from maps: the beans that exist, the instances lookups return, the routes.
 */
final class FakeReportContext implements StartupReportContext {

    private final Verbosity verbosity;
    private final Set<String> beanTypes = new HashSet<>();
    private final Map<Class<?>, Object> instances = new HashMap<>();
    private final Map<String, List<String>> routes = new HashMap<>();

    FakeReportContext(Verbosity verbosity) {
        this.verbosity = verbosity;
    }

    /** Declares a bean of {@code type} whose {@link #lookup} returns {@code instance}. */
    <T> FakeReportContext bean(Class<T> type, T instance) {
        beanTypes.add(type.getName());
        instances.put(type, instance);
        return this;
    }

    /** Declares a bean type that {@link #hasBeanOfType} knows but {@link #lookup} never returns. */
    FakeReportContext beanType(String typeName) {
        beanTypes.add(typeName);
        return this;
    }

    FakeReportContext route(String handlerClassName, String url) {
        routes.computeIfAbsent(handlerClassName, key -> new java.util.ArrayList<>()).add(url);
        return this;
    }

    @Override
    public Verbosity verbosity() {
        return verbosity;
    }

    @Override
    public LaunchMode launchMode() {
        return LaunchMode.DEV;
    }

    @Override
    public boolean hasBeanOfType(String typeName) {
        return beanTypes.contains(typeName);
    }

    @Override
    public <T> Optional<T> lookup(Class<T> type) {
        return Optional.ofNullable(type.cast(instances.get(type)));
    }

    @Override
    public List<String> routeUrls(String handlerClassName) {
        return List.copyOf(routes.getOrDefault(handlerClassName, List.of()));
    }
}
