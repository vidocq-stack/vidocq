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
package io.vidocq.runtime.ext.chappe;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.StaticFileHandler;
import io.vidocq.runtime.ext.chappe.spi.MountConfig;
import io.vidocq.runtime.ext.chappe.spi.MountHandlerProvider;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Provider builtin serving static content via {@link StaticFileHandler}.
 *
 * <h3>Supported properties</h3>
 * <pre>{@code
 * vidocq.http.mount.<name>.type = static
 * vidocq.http.mount.<name>.path = / # HTTP prefix (declared on the reader side)
 * vidocq.http.mount.<name>.classpath = static # base classpath (mutually exclusive with filesystem)
 * vidocq.http.mount.<name>.filesystem = /var/www/ui # base filesystem (mutually exclusive with classpath)
 * vidocq.http.mount.<name>.index = index.html # index name (default: index.html)
 * vidocq.http.mount.<name>.clean-urls = true # extensionless URLs -> .html sibling (default: false)
 * vidocq.http.mount.<name>.cache-control = max-age=3600 # header Cache-Control (optional)
 * vidocq.http.mount.<name>.cache-in-memory= true # default: false
 * }</pre>
 */
public final class StaticMountHandlerProvider implements MountHandlerProvider {

    @Override
    public String type() {
        return "static";
    }

    @Override
    public Handler create(MountConfig cfg) {
        StaticFileHandler.Builder b = StaticFileHandler.builder()
                .indexFile(cfg.property("index", String.class, "index.html"))
                .cleanUrls(cfg.property("clean-urls", Boolean.class, Boolean.FALSE));

        Optional<String> classpath = cfg.property("classpath");
        Optional<String> filesystem = cfg.property("filesystem");

        if (classpath.isPresent() && filesystem.isPresent()) {
            throw new IllegalStateException(
                    "Mount '" + cfg.name() + "' declares both classpath and filesystem — pick one.");
        }
        if (classpath.isEmpty() && filesystem.isEmpty()) {
            throw new IllegalStateException(
                    "Mount '" + cfg.name() + "' (type=static) requires either "
                            + "vidocq.http.mount." + cfg.name() + ".classpath or .filesystem");
        }
        classpath.ifPresent(b::addClasspath);
        filesystem.ifPresent(p -> b.addPath(Path.of(p)));

        cfg.property("cache-control").ifPresent(b::cacheControl);
        b.cacheInMemory(cfg.property("cache-in-memory", Boolean.class, Boolean.FALSE));

        return b.build();
    }
}
