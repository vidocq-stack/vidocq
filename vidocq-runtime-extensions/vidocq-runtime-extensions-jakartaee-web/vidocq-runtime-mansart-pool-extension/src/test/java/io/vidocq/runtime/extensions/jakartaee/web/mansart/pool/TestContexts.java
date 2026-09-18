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
package io.vidocq.runtime.extensions.jakartaee.web.mansart.pool;

import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.config.VidocqConfig;
import io.vidocq.runtime.spi.report.LaunchMode;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.Verbosity;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.inject.Named;

import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** What Vidocq hands the extension: a configuration, the context of {@code onStart}, the context of the report. */
final class TestContexts {

    private TestContexts() {
    }

    /** A {@link VidocqConfiguration} backed by a map, which lists its keys. */
    record MapConfig(Map<String, String> data) implements VidocqConfiguration {

        @Override
        public Optional<String> property(String key) {
            return Optional.ofNullable(data.get(key));
        }

        @Override
        public Iterable<String> propertyNames() {
            return data.keySet();
        }
    }

    /** A configuration of key and value pairs. */
    static MapConfig config(String... keysAndValues) {
        Map<String, String> data = new HashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            data.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new MapConfig(data);
    }

    /**
     * The context of the report: what a contributor reads while it writes its section. It knows no bean and no route.
     *
     * @param launchMode the launch mode of the boot
     * @param verbosity  how much the report shows
     */
    record ReportContext(LaunchMode launchMode, Verbosity verbosity) implements StartupReportContext {

        /** The context the dev console gives: every row. */
        static ReportContext detailed(LaunchMode launchMode) {
            return new ReportContext(launchMode, Verbosity.DETAILED);
        }

        @Override
        public boolean hasBeanOfType(String typeName) {
            return false;
        }

        @Override
        public <T> Optional<T> lookup(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public List<String> routeUrls(String handlerClassName) {
            return List.of();
        }
    }

    /**
     * The context of {@code onStart}: a {@link BeanManager} that knows a {@code @Named DataSource} bean for each of
     * {@code namedBeans} and nothing else, and records the names it was asked.
     */
    static final class StartContext implements ExtensionContext {

        private final Set<String> namedBeans;
        private final RuntimeException failure;
        private final List<String> asked = new ArrayList<>();

        private StartContext(Set<String> namedBeans, RuntimeException failure) {
            this.namedBeans = namedBeans;
            this.failure = failure;
        }

        /** A container with a {@code @Named("<name>") DataSource} bean for each of {@code names}. */
        static StartContext withNamedBeans(String... names) {
            return new StartContext(Set.of(names), null);
        }

        /** A container whose bean manager cannot be obtained. */
        static StartContext failing(RuntimeException failure) {
            return new StartContext(Set.of(), failure);
        }

        /** The names the bean manager was asked for, in order. */
        List<String> asked() {
            return List.copyOf(asked);
        }

        @Override
        public VaubanContainer container() {
            throw new UnsupportedOperationException("no container in this test");
        }

        @Override
        public VidocqConfiguration configuration() {
            return key -> Optional.empty();
        }

        @Override
        public VidocqConfig config() {
            throw new UnsupportedOperationException("no typed configuration in this test");
        }

        @Override
        public LaunchMode launchMode() {
            return LaunchMode.DEV;
        }

        @Override
        public BeanManager beanManager() {
            if (failure != null) {
                throw failure;
            }
            return (BeanManager) Proxy.newProxyInstance(BeanManager.class.getClassLoader(),
                    new Class<?>[] {BeanManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getBeans") && args != null && args.length == 2
                                && args[0] == DataSource.class && args[1] instanceof Annotation[] qualifiers) {
                            for (Annotation qualifier : qualifiers) {
                                if (qualifier instanceof Named named) {
                                    asked.add(named.value());
                                    // the extension only asks whether the set is empty: any element stands for the bean
                                    return namedBeans.contains(named.value()) ? Set.of(named) : Set.of();
                                }
                            }
                        }
                        throw new UnsupportedOperationException(method.toString());
                    });
        }
    }
}
