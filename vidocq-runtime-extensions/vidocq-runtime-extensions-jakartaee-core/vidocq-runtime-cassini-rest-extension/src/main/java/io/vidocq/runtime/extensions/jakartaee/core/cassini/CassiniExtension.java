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
import io.vidocq.runtime.extensions.essentials.chappe.ChappeListener;
import io.vidocq.runtime.extensions.essentials.chappe.ChappeMountPoint;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.vauban.core.context.RequestContext;

import java.util.Set;

/**
 * Vidocq Runtime extension which connects Cassini (Jakarta REST 4.0 standalone) to the
 * Chappe HTTP engine via {@link ChappeHttpAdapter} — legacy auto-mount route.
 *
 * <p>Priority 500: runs after {@code ChappeEngineExtension} and before
 * {@code ChappeServerBootstrap}, in order to contribute a JAX-RS handler to the
 * {@link ChappeMountPoint}.
 *
 * <p>The bootstrap goes through the public SPI {@link CassiniStack#builder()}:
 * cassini-core provides the {@code BuilderFactory} via ServiceLoader, and a
 * {@link VaubanBeanProvider} built on the {@code VaubanContainer} of
 * runtime exposes discovered {@code @Path}/{@code @Provider} resources
 * by CDI.
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>{@code vidocq.rest.context-path} — mount prefix (default: {@code /})</li>
 *   <li>{@code vidocq.rest.listener} — target Chappe listener (default: {@link ChappeListener#DEFAULT})</li>
 * </ul>
 *
 * <p><b>Deactivation:</b> as soon as a declarative mount {@code vidocq.http.mount.<n>.type=restful}
 * is present in the config, this extension gives way to
 * {@code ChappeMountConfigExtension} to avoid a double mount.</p>
 */
public final class CassiniExtension implements VidocqExtension {

    private static final System.Logger LOG = System.getLogger(CassiniExtension.class.getName());

    private String contextPath = "/";
    private String listener = ChappeListener.DEFAULT;

    @Override
    public String name() {
        return "rest-cassini";
    }

    @Override
    public int priority() {
        return 500;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.contextPath = config.property("vidocq.rest.context-path", "/");
        this.listener = config.property("vidocq.rest.listener", ChappeListener.DEFAULT);
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
    }

    @Override
    public void onStart(ExtensionContext context) {
        if (hasDeclarativeCassiniMount(context)) {
            LOG.log(System.Logger.Level.INFO,
                    "A declarative vidocq.mount.<n>.type=cassini is configured — "
                            + "skipping legacy auto-mount.");
            return;
        }

        BeanProvider beanProvider = new VaubanBeanProvider(context.container());
        Set<Class<?>> resourceClasses = beanProvider.getResourceClasses();
        if (resourceClasses.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                    "No @Path beans discovered — Cassini REST extension inactive");
            return;
        }

        CassiniStack stack = CassiniStack.builder()
                .beanProvider(beanProvider)
                .build();

        // @RequestScoped activation via Vauban around each dispatch.
        RequestContext requestContext = new RequestContext();
        ChappeHttpAdapter.Scoped scoped = requestContext::runInScope;
        ChappeHttpAdapter bridge = new ChappeHttpAdapter(stack.adapter(), scoped);

        String mountPrefix = "/".equals(contextPath) ? "" : contextPath;
        ChappeMountPoint.instance().mount(listener, mountPrefix, bridge);

        LOG.log(System.Logger.Level.INFO,
                "Cassini REST extension mounted on listener={0} prefix={1} ({2} resource class(es))",
                listener, mountPrefix.isEmpty() ? "/" : mountPrefix, resourceClasses.size());
    }

    @Override
    public void onStop() {
    }

    private static boolean hasDeclarativeCassiniMount(ExtensionContext context) {
        var config = context.config();
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith("vidocq.http.mount.") || !key.endsWith(".type")) continue;
            if ("restful".equals(config.getValue(key, String.class, ""))) return true;
        }
        return false;
    }
}
