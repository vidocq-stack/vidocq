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
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.servlet.ServletException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deploys the application with Foy and keeps the live deployments of this boot: the startup
 * report reads them, {@link FoyExtension#onStop()} closes them (an in-JVM hot reload then starts
 * from a fresh deployment on the new class loader).
 */
final class FoyDeployments {

    /** A deployment mounted under {@code name} ({@code vidocq.servlet}, or a declarative mount's name). */
    record Live(String name, String listener, FoyChappeBoot.Mounted mounted) {}

    private static final List<Live> LIVE = new ArrayList<>();

    private FoyDeployments() {}

    /**
     * @param contextPath {@code null}: the descriptors' {@code <default-context-path>}, else {@code /}
     * @return empty when the application has no servlet, filter, listener nor initializer
     */
    static Optional<FoyChappeBoot.Mounted> deploy(BeanManager beanManager, ClassLoader loader,
                                                  String contextPath, int sessionTimeoutSeconds) {
        FoyChappeBoot.Builder builder = FoyChappeBoot.builder()
                .beanManager(beanManager)
                .classLoader(loader)
                .sessionTimeoutSeconds(sessionTimeoutSeconds);
        if (contextPath != null) builder.contextPath(contextPath);
        try {
            return builder.build();
        } catch (ServletException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    static synchronized void record(String name, String listener, FoyChappeBoot.Mounted mounted) {
        LIVE.add(new Live(name, listener, mounted));
    }

    static synchronized List<Live> all() {
        return List.copyOf(LIVE);
    }

    /** Closes every deployment of this boot, the last mounted first. */
    static synchronized void closeAll() {
        RuntimeException failure = null;
        for (Live live : LIVE.reversed()) {
            try {
                live.mounted().close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        LIVE.clear();
        if (failure != null) throw failure;
    }
}
