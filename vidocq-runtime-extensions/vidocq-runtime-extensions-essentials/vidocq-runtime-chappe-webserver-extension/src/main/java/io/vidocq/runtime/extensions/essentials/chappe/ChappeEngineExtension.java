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
package io.vidocq.runtime.extensions.essentials.chappe;

import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Vidocq extension which installs the shared {@link ChappeMountPoint}.
 * <p>
 * Low priority (100): runs before contributing extensions (REST, Servlet, etc.)
 * so that they can call {@link ChappeMountPoint#instance()} in their
 * phase {@code onStart}.
 * </p>
 * <p>The effective startup of the Chappe servers is ensured by
 * {@link ChappeServerBootstrap} at the end of the chain (priority 10,000).</p>
 * <p><b>Startup report:</b> writes the {@code http} section, which declares every listener of the configuration
 * with the URL it really listens on. Its priority puts it before the sections that declare routes, so that they
 * print absolute URLs and read them through {@code routeUrls}: every {@code contribute} runs after every
 * {@code onStart}, when the servers are already listening. A listener an extension declared for itself, such as
 * the dev console's, is left to that extension's section.</p>
 */
public final class ChappeEngineExtension implements VidocqExtension, StartupReportContributor {

    private static final System.Logger LOG = System.getLogger(ChappeEngineExtension.class.getName());

    @Override
    public String name() {
        return "chappe-engine";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        ChappeMountPoint mp = new ChappeMountPoint();
        ChappeMountPoint.install(mp);
        LOG.log(System.Logger.Level.INFO, "Chappe engine: mount point ready");
    }

    @Override
    public String id() {
        return "http";
    }

    @Override
    public String title() {
        return "HTTP (Chappe)";
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        ChappeMountPoint mp;
        try {
            mp = ChappeMountPoint.instance();
        } catch (IllegalStateException notInstalled) {
            section.summary("no listener started");
            return;
        }
        List<String> started = new ArrayList<>();
        for (ChappeMountPoint.Declaration d : mp.declarations()) {
            if (d.owner() != null) {
                continue;
            }
            String name = d.listener().name();
            Optional<String> url = mp.boundUrl(name);
            if (url.isPresent()) {
                section.listener(name, url.get());
                started.add(name + " " + url.get());
            }
        }
        section.summary(started.isEmpty() ? "no listener started" : String.join(", ", started));
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        // Nothing: the instance is statically exposed via ChappeMountPoint.instance().
        // A CDI integration by @Produces can be added without changing the API.
    }
}
