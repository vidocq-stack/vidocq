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
package io.vidocq.runtime.extensions.jakartaee.web.foy;

import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.config.VidocqConfig;
import jakarta.enterprise.inject.spi.BeanManager;

import java.util.Optional;
import java.util.Set;

/**
 * Serves the application's Jakarta Servlet components through Foy on the shared Chappe web
 * server. Runs after the REST extension (500) and before the declarative mounts (7000) and the
 * server start (10000). Foy activates the CDI request context itself and, through
 * {@code foy-cdi-vauban}, the session context: the dispatch is not wrapped here.
 */
public final class FoyExtension implements VidocqExtension {

    static final String AUTO_MOUNT = "vidocq.servlet";
    private static final System.Logger LOG = System.getLogger(FoyExtension.class.getName());

    private final MountTarget target;
    private ServletSettings settings;

    public FoyExtension() {
        this(MountTarget.chappe());
    }

    FoyExtension(MountTarget target) {
        this.target = target;
    }

    @Override
    public String name() {
        return "servlet-foy";
    }

    @Override
    public int priority() {
        return 600;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        this.settings = ServletSettings.from(config);
    }

    @Override
    public Set<String> configKeys() {
        return Set.of(ServletSettings.CONTEXT_PATH, ServletSettings.LISTENER, ServletSettings.SESSION_TIMEOUT);
    }

    @Override
    public void onStart(ExtensionContext context) {
        start(context.beanManager(), context.config(), Thread.currentThread().getContextClassLoader());
    }

    void start(BeanManager beanManager, VidocqConfig config, ClassLoader loader) {
        if (hasDeclarativeServletMount(config)) {
            LOG.log(System.Logger.Level.INFO,
                    "A declarative vidocq.http.mount.<n>.type=servlet is configured, skipping the auto-mount");
            return;
        }
        Optional<FoyChappeBoot.Mounted> deployed = FoyDeployments.deploy(beanManager, loader,
                settings.contextPath(), settings.sessionTimeoutSeconds());
        if (deployed.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "No web component discovered, Foy Servlet extension inactive");
            return;
        }
        FoyChappeBoot.Mounted mounted = deployed.get();
        // Recorded before mounting: a refused mount (duplicate prefix) still leaves it to onStop.
        FoyDeployments.record(AUTO_MOUNT, settings.listener(), mounted);
        target.mount(settings.listener(), mounted.mountPrefix(), mounted.handler());
        LOG.log(System.Logger.Level.INFO, "Foy Servlet extension mounted on listener={0} prefix={1}",
                settings.listener(), mounted.mountPrefix().isEmpty() ? "/" : mounted.mountPrefix());
    }

    @Override
    public void onStop() {
        FoyDeployments.closeAll();
    }

    private static boolean hasDeclarativeServletMount(VidocqConfig config) {
        for (String key : config.getPropertyNames()) {
            if (!key.startsWith("vidocq.http.mount.") || !key.endsWith(".type")) continue;
            if ("servlet".equals(config.getValue(key, String.class, ""))) return true;
        }
        return false;
    }
}
