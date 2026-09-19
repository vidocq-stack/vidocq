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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp;

import dev.langchain4j.cdi.mcp.server.transport.McpEndpoint;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfig;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.VidocqConfiguration;
import io.vidocq.runtime.spi.VidocqExtension;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportContributor;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;

import java.util.Set;

/**
 * Hosts a langchain4j-cdi MCP server on Vidocq.
 *
 * <ul>
 *   <li>Its {@code META-INF/vauban-beans.list}, generated from the langchain4j-cdi jar at build time, names the
 *       server's beans: the application needs no {@code scanDependencies}.</li>
 *   <li>{@link #configure} maps the {@code vidocq.mcp.*} keys; {@link #beforeStart} publishes the result as the
 *       {@code @Named("mcp-server")} bean langchain4j-cdi reads, only when a key is set.</li>
 *   <li>{@link #contribute} writes the {@code mcp} section of the startup report and raises
 *       {@code VIDOCQ-MCP-001} to {@code 005}.</li>
 * </ul>
 *
 * <p>Priority {@code 600}: after Cassini ({@code 500}), whose section, when it declares routes, comes first, so
 * that the {@code mcp} section can print the absolute URL of {@code /mcp}.
 */
public final class McpExtension implements VidocqExtension, StartupReportContributor {

    static final String NAME = "langchain4j-cdi-mcp";
    static final int PRIORITY = 600;
    static final String SECTION_ID = "mcp";
    static final String SECTION_TITLE = "MCP server (langchain4j-cdi)";

    private static final System.Logger LOG = System.getLogger(McpExtension.class.getName());

    /** What the {@code vidocq.mcp.*} keys describe; {@code null} when none is set. */
    private volatile McpServerConfig mapped;
    /** What {@link #onStart} read from the container; {@link McpInspection#NOTHING} before and after. */
    private volatile McpInspection inspection = McpInspection.NOTHING;

    /** Created by the {@link java.util.ServiceLoader}. */
    public McpExtension() {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Set<String> configKeys() {
        return McpConfigMapping.KEYS;
    }

    @Override
    public void configure(VidocqConfiguration config) {
        mapped = McpConfigMapping.map(config).orElse(null);
        if (mapped != null) {
            LOG.log(System.Logger.Level.DEBUG, "MCP server configuration mapped from vidocq.mcp.*");
        }
    }

    @Override
    public void beforeStart(VaubanContainerBuilder builder) {
        McpServerConfig config = mapped;
        if (config != null) {
            McpServerConfigProducer.config = config;
            builder.addSyntheticArchiveClass(McpServerConfigProducer.class);
        }
    }

    @Override
    public void onStart(ExtensionContext context) {
        inspection = McpInspection.of(context.beanManager());
    }

    @Override
    public void onStop() {
        inspection = McpInspection.NOTHING;
        McpServerConfigProducer.config = null;
    }

    @Override
    public String id() {
        return SECTION_ID;
    }

    @Override
    public String title() {
        return SECTION_TITLE;
    }

    @Override
    public void contribute(StartupReportContext context, StartupReportSection section) {
        McpStartupSection.write(inspection, mapped != null, McpEndpoint.class.getModule(), context, section);
    }
}
