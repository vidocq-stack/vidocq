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
package io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.dev;

import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpEndpointLive;
import io.vidocq.runtime.extensions.essentials.langchain4jcdi.mcp.live.McpInspection;
import io.vidocq.runtime.spi.ExtensionContext;
import io.vidocq.runtime.spi.devconsole.Chart;
import io.vidocq.runtime.spi.devconsole.LivePanel;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import io.vidocq.runtime.spi.devconsole.PanelSample;
import io.vidocq.runtime.spi.devconsole.Series;

import java.time.Duration;
import java.util.List;

/**
 * The {@code mcp} section of the startup report, live: the values langchain4j-cdi's MCP server holds in memory,
 * read without creating a single bean (Vidocq/vidocq#143). The section itself is still written by
 * {@code McpExtension}, in the runtime extension; this module only makes it live, and only {@code vidocq:dev} adds
 * it.
 *
 * <p>It is also the MCP inspector: an action per tool, prompt, resource and resource template, which calls the
 * application's own {@code /mcp} (see {@link McpInspector}).
 */
public final class McpLivePanel implements LivePanel {

    /** How long the inspector waits for {@code /mcp}: under the console's 60 s limit of an action. */
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(55);

    /**
     * What the page plots. The three invoker values get no chart: they count distinct methods, so they grow once
     * per method and then stop, and a curve of them would read as traffic.
     */
    private static final List<Chart> CHARTS = List.of(
            new Chart("connections", "Connections",
                    List.of(Series.area("sessions"), Series.line("streams"), Series.line("listens"))),
            new Chart("server-requests", "Server-to-client requests", List.of(Series.line("pending"))));

    private volatile McpLiveBeans live = McpLiveBeans.NONE;
    private volatile McpInspector inspector = McpInspector.OFF;

    /** Created by the service loader. */
    public McpLivePanel() {}

    @Override
    public String id() {
        return "mcp";
    }

    /**
     * Resolves the beans of the MCP server, once, without creating any of them, and builds the inspector from the
     * registries that exist and the URL of {@code /mcp} the runtime extension published. Closes the previous
     * inspector's client first, in case a dev reload calls {@code start} again without an intervening {@code stop}.
     */
    @Override
    public void start(ExtensionContext context) {
        McpInspection inspection = McpInspection.of(context.beanManager());
        live = McpLiveBeans.of(inspection, context.beanManager());
        inspector.close();
        inspector = McpInspector.start(context.beanManager(), inspection, McpEndpointLive.urls(), CALL_TIMEOUT);
    }

    /** Drops the beans, the catalogue and the inspector's history: a dev reload builds them again. */
    @Override
    public void stop() {
        live = McpLiveBeans.NONE;
        inspector.close();
        inspector = McpInspector.OFF;
    }

    @Override
    public List<Chart> charts() {
        return CHARTS;
    }

    /** The inspector's actions: one per tool, prompt, resource and resource template; none before {@link #start}. */
    @Override
    public List<PanelAction> actions() {
        return inspector.actions();
    }

    /**
     * The live values of the MCP server, from the beans {@link #start} resolved: counters kept in memory, read
     * without a lock, without I/O and without creating a bean. Nothing before {@link #start}, nothing after
     * {@link #stop}, and nothing on a twin server. Also samples the inspector: what it offers and where it calls,
     * or why it offers nothing.
     */
    @Override
    public void sample(PanelSample sample) {
        live.sample(sample);
        inspector.sample(sample);
    }
}
