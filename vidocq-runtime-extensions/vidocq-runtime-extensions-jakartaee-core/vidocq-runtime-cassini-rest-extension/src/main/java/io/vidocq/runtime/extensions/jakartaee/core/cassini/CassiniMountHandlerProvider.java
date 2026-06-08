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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.cdi.vauban.VaubanBeanProvider;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Handler;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider;
import io.vidocq.vauban.core.context.RequestContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provider {@code MountHandlerProvider} of type {@code restful} (Jakarta
 * RESTful Web Services) — Cassini implementation mounted on Chappe with a
 * {@link BeanProvider} Vauban from the Vidocq Runtime.
 *
 * <p>The {@code type} describes the <b>standard contract</b> (JAX-RS / Jakarta
 * RESTful Web Services), not the implementation. When another provider Jakarta
 * REST will be added (ex. Jersey, RESTEasy), it will also declare
 * {@code type() = "restful"}; the selection will then be made via the property
 * optional {@code vidocq.http.mount.<name>.impl=<id>} (to enter the
 * day when several implementations will co-exist).</p>
 *
 * <h3>Supported properties</h3>
 * <pre>{@code
 * vidocq.http.mount.<name>.type = restful
 * vidocq.http.mount.<name>.path = /api          # default: / via the reader
 * # Optional per-mount resource scoping (CSV of package prefixes). When set, the
 * # discovered @Path/@Provider beans are filtered so a mount exposes only a subset:
 * vidocq.http.mount.<name>.include-packages = io.vidocq.runtime.extensions.microprofile.knock
 * vidocq.http.mount.<name>.exclude-packages = io.vidocq.runtime.extensions.microprofile.knock
 * # Lets e.g. MicroProfile Health mount at /health while the app REST stays at /api.
 * }</pre>
 */
public final class CassiniMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "restful";
    }

    @Override
    public Handler create(MountConfig cfg) {
        BeanProvider base = new VaubanBeanProvider(cfg.extensionContext().container());

        List<String> include = cfg.properties("include-packages", String.class);
        List<String> exclude = cfg.properties("exclude-packages", String.class);
        BeanProvider beanProvider = (include.isEmpty() && exclude.isEmpty())
                ? base
                : new PackageScopedBeanProvider(base, include, exclude);

        CassiniStack stack = CassiniStack.builder()
                .beanProvider(beanProvider)
                .build();
        RequestContext requestContext = new RequestContext();
        return new ChappeHttpAdapter(stack.adapter(), requestContext::runInScope);
    }

    /**
     * {@link BeanProvider} decorator that restricts the discovered resource classes to a set of
     * package prefixes, so a single CDI bean index can feed several mounts at different prefixes
     * (e.g. {@code /api} for the application, {@code /health} for MicroProfile Health). Only
     * {@link #getResourceClasses()} is filtered; bean lookup is delegated untouched.
     */
    private record PackageScopedBeanProvider(BeanProvider delegate,
                                             List<String> include,
                                             List<String> exclude) implements BeanProvider {

        @Override
        public <T> T getBean(Class<T> type) {
            return delegate.getBean(type);
        }

        @Override
        public Object contextualInstance(Class<?> type, Object bean) {
            return delegate.contextualInstance(type, bean);
        }

        @Override
        public Set<Class<?>> getResourceClasses() {
            Set<Class<?>> result = new LinkedHashSet<>();
            for (Class<?> c : delegate.getResourceClasses()) {
                String pkg = c.getPackageName();
                if (!include.isEmpty() && noneMatches(include, pkg)) {
                    continue;
                }
                if (anyMatches(exclude, pkg)) {
                    continue;
                }
                result.add(c);
            }
            return result;
        }

        private static boolean anyMatches(List<String> prefixes, String pkg) {
            for (String p : prefixes) {
                if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                    return true;
                }
            }
            return false;
        }

        private static boolean noneMatches(List<String> prefixes, String pkg) {
            return !anyMatches(prefixes, pkg);
        }
    }
}
