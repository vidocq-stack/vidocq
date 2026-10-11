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

import io.vidocq.chappe.api.Handler;
import io.vidocq.foy.chappe.FoyChappeBoot;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountConfig;
import io.vidocq.runtime.extensions.essentials.chappe.spi.MountHandlerProvider;
import jakarta.enterprise.inject.spi.BeanManager;

/**
 * {@code vidocq.http.mount.<n>.type=servlet}: mounts the application's servlets on a declared
 * prefix and listener. The prefix is the context path; {@code vidocq.http.mount.<n>.session-timeout}
 * overrides {@code vidocq.servlet.session-timeout} for this mount.
 */
public final class FoyMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "servlet";
    }

    @Override
    public Handler create(MountConfig cfg) {
        return create(cfg, cfg.extensionContext().beanManager(), Thread.currentThread().getContextClassLoader());
    }

    Handler create(MountConfig cfg, BeanManager beanManager, ClassLoader loader) {
        int global = ServletSettings.seconds(cfg.vidocqConfig().getValue(ServletSettings.SESSION_TIMEOUT).orElse(null));
        int timeout = ServletSettings.seconds("vidocq.http.mount." + cfg.name() + ".session-timeout",
                cfg.property("session-timeout").orElse(null), global);
        String contextPath = cfg.prefix().isEmpty() || "/".equals(cfg.prefix()) ? "/" : cfg.prefix();
        FoyChappeBoot.Mounted mounted = FoyDeployments.deploy(beanManager, loader, contextPath, timeout, cfg.vidocqConfig())
                .orElseThrow(() -> new IllegalStateException("vidocq.http.mount." + cfg.name()
                        + ".type=servlet, but the application has no servlet, filter, listener, web.xml "
                        + "nor ServletContainerInitializer"));
        FoyDeployments.record(cfg.name(), cfg.listener(), mounted);
        return mounted.handler();
    }
}
