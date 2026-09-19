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

import dev.langchain4j.cdi.mcp.server.api.McpFrameworkTypes;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.ToolArg;

import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The checks behind {@code VIDOCQ-MCP-001} and {@code VIDOCQ-MCP-005}: pure functions, no container. */
final class McpChecks {

    /** The service langchain4j-cdi's {@code McpServerSPILoader} looks up. */
    static final String SERVER_SPI = "org.mcpjava.server.spi.McpServerSPI";

    private McpChecks() {}

    /**
     * What the descriptor of {@code dev.langchain4j.cdi.mcp.server} lacks for the server to work in the boot layer.
     *
     * @param descriptor the module descriptor of the MCP server
     * @return one sentence per problem; empty when the module is open and provides {@value #SERVER_SPI}
     */
    static List<String> descriptorProblems(ModuleDescriptor descriptor) {
        List<String> problems = new ArrayList<>();
        boolean provides = descriptor.provides().stream().anyMatch(p -> p.service().equals(SERVER_SPI));
        if (!provides) {
            problems.add("does not provide " + SERVER_SPI + ": tools and prompts that build a ToolResponse, a "
                    + "PromptResponse or a TextContent fail with \"No McpServerSPI implementation found\"");
        }
        if (!descriptor.isOpen()) {
            problems.add("is not an open module: the container and the Jakarta REST runtime cannot reach its beans "
                    + "reflectively");
        }
        return problems;
    }

    /**
     * The MCP methods with a parameter that has no name: not a framework type, no explicit name in its
     * {@code @ToolArg}, {@code @PromptArg} or {@code @ResourceTemplateArg}, and compiled without {@code -parameters}.
     * langchain4j-cdi advertises such a parameter as {@code arg0}, {@code arg1}, ... and never binds it.
     *
     * @param methods the tool, prompt and resource template methods
     * @return {@code SimpleClassName#method} of each offending method, sorted, each once
     */
    static List<String> unnamedParameters(Collection<Method> methods) {
        List<String> offending = new ArrayList<>();
        for (Method method : methods) {
            for (Parameter parameter : method.getParameters()) {
                if (!McpFrameworkTypes.isFrameworkType(parameter.getType()) && !explicitlyNamed(parameter)
                        && !parameter.isNamePresent()) {
                    String name = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
                    if (!offending.contains(name)) {
                        offending.add(name);
                    }
                }
            }
        }
        offending.sort(null);
        return offending;
    }

    private static boolean explicitlyNamed(Parameter parameter) {
        ToolArg tool = parameter.getAnnotation(ToolArg.class);
        if (tool != null && !ToolArg.ELEMENT_NAME.equals(tool.name())) {
            return true;
        }
        PromptArg prompt = parameter.getAnnotation(PromptArg.class);
        if (prompt != null && !PromptArg.ELEMENT_NAME.equals(prompt.name())) {
            return true;
        }
        ResourceTemplateArg template = parameter.getAnnotation(ResourceTemplateArg.class);
        return template != null && !ResourceTemplateArg.ELEMENT_NAME.equals(template.name());
    }
}
