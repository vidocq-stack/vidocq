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

import dev.langchain4j.cdi.mcp.server.protocol.McpProtocolVersions;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceTemplateDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import dev.langchain4j.cdi.mcp.server.transport.McpEndpoint;
import dev.langchain4j.cdi.mcp.server.transport.McpMrtrMode;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfig;
import dev.langchain4j.cdi.mcp.server.transport.McpServerConfigResolver;
import io.vidocq.runtime.spi.report.StartupReportContext;
import io.vidocq.runtime.spi.report.StartupReportSection;
import io.vidocq.runtime.spi.report.Verbosity;

import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Writes the {@code mcp} section of the startup report and raises {@code VIDOCQ-MCP-001} to {@code 005}. Boot facts
 * only, read from memory: the registries langchain4j-cdi filled when the application scope started, the configuration
 * the server resolves, the container's metadata as {@link McpInspection} read it. The request-state secret is only
 * ever written as {@link StartupReportSection#secret configured or not}.
 */
final class McpStartupSection {

    static final String CODE_DESCRIPTOR = "VIDOCQ-MCP-001";
    static final String CODE_NOT_DEPLOYED = "VIDOCQ-MCP-002";
    static final String CODE_TWIN = "VIDOCQ-MCP-003";
    static final String CODE_TWO_CONFIGS = "VIDOCQ-MCP-004";
    static final String CODE_UNNAMED_PARAMETERS = "VIDOCQ-MCP-005";

    /** The endpoint class, by name: {@link StartupReportContext#hasBeanOfType} and the routes compare names. */
    static final String ENDPOINT = McpEndpoint.class.getName();
    /** The synthetic bean langchain4j-cdi's CDI 4.1 invoker registers; it is created at the first call only. */
    static final String INVOKER_PROVIDER = "dev.langchain4j.cdi.mcp.invoker.cdi41.McpCdi41InvokerProvider";
    /** What the endpoint row says until a section declares the route of {@link McpEndpoint}. */
    static final String ENDPOINT_PATH = "/mcp (Jakarta REST path; absolute URL once the rest section declares routes)";

    private McpStartupSection() {}

    /**
     * Writes the section.
     *
     * @param inspection   what {@code onStart} read from the container
     * @param mapped       whether a {@code vidocq.mcp.*} key is set
     * @param serverModule the module of langchain4j-cdi's MCP server, as this extension links to it
     * @param context      what the report lets a contributor read
     * @param section      where the section is written
     */
    static void write(McpInspection inspection, boolean mapped, Module serverModule, StartupReportContext context,
                      StartupReportSection section) {
        ModuleDescriptor descriptor = serverModule.getDescriptor();
        if (descriptor != null) {
            List<String> problems = McpChecks.descriptorProblems(descriptor);
            if (!problems.isEmpty()) {
                section.anomaly(CODE_DESCRIPTOR,
                        "Module " + descriptor.name() + " " + String.join("; it ", problems) + ".",
                        "Use a langchain4j-cdi version whose dev.langchain4j.cdi.mcp.server module is open and "
                                + "provides " + McpChecks.SERVER_SPI + ".");
            }
        }
        if (inspection.twin()) {
            section.summary("loaded twice");
            section.anomaly(CODE_TWIN, "langchain4j-cdi's MCP server is loaded twice: its beans come from the "
                            + "application layer while this extension links to the boot-layer copy, so the mcp "
                            + "section is left empty.",
                    "Do not list dev.langchain4j.cdi.mcp.* modules in -Dvidocq.app.modules.");
            return;
        }
        Optional<McpToolRegistry> tools = context.hasBeanOfType(ENDPOINT)
                ? context.lookup(McpToolRegistry.class) : Optional.empty();
        if (tools.isEmpty()) {
            section.summary("not deployed");
            section.anomaly(CODE_NOT_DEPLOYED, "The langchain4j-cdi MCP server extension is present but no "
                            + "McpEndpoint bean exists: /mcp is not served.",
                    "Align the langchain4j-cdi version with the one this extension is built with: its bean list "
                            + "names the classes of that version.");
            return;
        }
        McpToolRegistry toolRegistry = tools.get();
        McpPromptRegistry prompts = context.lookup(McpPromptRegistry.class).orElseGet(McpPromptRegistry::new);
        McpResourceRegistry resources = context.lookup(McpResourceRegistry.class).orElseGet(McpResourceRegistry::new);
        List<String> urls = context.routeUrls(ENDPOINT);

        section.summary(count(toolRegistry.size(), "tool") + ", " + count(prompts.size(), "prompt") + ", "
                + count(resources.size(), "resource") + ", " + count(resources.templateSize(), "resource template")
                + (urls.isEmpty() ? "" : " at " + String.join(", ", urls)));

        if (mapped && inspection.namedConfigBeans() > 1) {
            section.anomaly(CODE_TWO_CONFIGS, "vidocq.mcp.* keys are set and the application also produces a "
                            + "@Named(\"mcp-server\") McpServerConfig: the keys win and the application's producer is "
                            + "not used.",
                    "Keep either the vidocq.mcp.* keys or the application's @Named(\"mcp-server\") producer.");
        }

        List<Method> methods = new ArrayList<>();
        toolRegistry.listTools().forEach(tool -> methods.add(tool.getMethod()));
        prompts.listPrompts().forEach(prompt -> methods.add(prompt.getMethod()));
        resources.listTemplates().forEach(template -> methods.add(template.getMethod()));
        List<String> unnamed = McpChecks.unnamedParameters(methods);
        if (!unnamed.isEmpty()) {
            section.anomaly(CODE_UNNAMED_PARAMETERS, unnamed.size() + " MCP method(s) have parameters without a "
                            + "name (" + String.join(", ", unnamed) + "): clients see them as arg0, arg1, ... and "
                            + "their values are never bound.",
                    "Compile the application with -parameters, or name each argument with @ToolArg(name = ...), "
                            + "@PromptArg(name = ...) or @ResourceTemplateArg(name = ...).");
        }

        if (context.verbosity() != Verbosity.DETAILED) {
            return;
        }
        McpServerConfig config = context.lookup(McpServerConfigResolver.class)
                .map(McpServerConfigResolver::get)
                .orElseGet(McpServerConfig::new);
        section.row("endpoint", urls.isEmpty() ? ENDPOINT_PATH : String.join(", ", urls));
        section.row("protocols", protocols());
        section.row("server",
                config.getServerName() + " " + config.getServerVersion() + " (" + origin(inspection) + ")");
        section.list("tools",
                toolRegistry.listTools().stream().map(McpToolDescriptor::getName).sorted().toList());
        section.list("prompts",
                prompts.listPrompts().stream().map(McpPromptDescriptor::getName).sorted().toList());
        section.list("resources",
                resources.listResources().stream().map(McpResourceDescriptor::getUri).sorted().toList());
        section.list("resource templates",
                resources.listTemplates().stream().map(McpResourceTemplateDescriptor::getUriTemplate).sorted().toList());
        section.row("invoker", context.hasBeanOfType(INVOKER_PROVIDER)
                ? "CDI 4.1 invoker registered (built at the first call)" : "reflection");
        section.row("mrtr", config.getMrtrMode() == McpMrtrMode.CONTINUATION
                ? "CONTINUATION, timeout " + config.getContinuationTimeout()
                : "REPLAY, request state TTL " + config.getRequestStateTtl());
        boolean secretConfigured = config.getRequestStateSecret() != null;
        section.secret("requestStateSecret", secretConfigured);
        if (!secretConfigured && config.getMrtrMode() == McpMrtrMode.REPLAY) {
            section.row("request state key", "random per-JVM key (single instance only)");
        }
        section.row("allowed origins", origins(config.getAllowedOrigins()));
        section.row("cache hints", "ttl " + config.getCacheTtl() + ", " + config.getCacheScope());
    }

    /** {@code 1 tool}, {@code 0 tools}, {@code 2 resource templates}. */
    static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** {@code 2026-07-28 (modern), 2025-03-26 (legacy)}. */
    static String protocols() {
        List<String> versions = new ArrayList<>();
        for (String version : McpProtocolVersions.SUPPORTED) {
            versions.add(version + (McpProtocolVersions.MODERN.contains(version) ? " (modern)" : " (legacy)"));
        }
        return String.join(", ", versions);
    }

    /**
     * Where the served configuration comes from. The extension's producer is an enabled alternative: when it exists,
     * it is the one served, even next to an application producer.
     */
    static String origin(McpInspection inspection) {
        if (inspection.configFromExtension()) {
            return "vidocq.mcp.*";
        }
        return inspection.namedConfigBeans() == 0 ? "defaults" : "application bean";
    }

    /** An empty list accepts a present {@code Origin} only when it and {@code Host} are loopback. */
    static String origins(List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return "loopback only";
        }
        return allowed.contains("*") ? "*" : String.join(", ", allowed);
    }
}
