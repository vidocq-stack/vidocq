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

import dev.langchain4j.cdi.mcp.server.protocol.McpToolAnnotationsModel;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpPromptRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceRegistry;
import dev.langchain4j.cdi.mcp.server.registry.McpResourceTemplateDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolDescriptor;
import dev.langchain4j.cdi.mcp.server.registry.McpToolRegistry;
import io.vidocq.runtime.spi.devconsole.PanelAction;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What the MCP inspector offers (spec §3.1): one item per tool, prompt, resource and resource template of
 * langchain4j-cdi's registries, read once per boot at the panel's {@code start}, each with the id of its action, what
 * the page shows of it, the JSON Schema of its argument and, for a tool, the question asked before a call.
 *
 * <p>The registries are read the way the rest of the panel reads beans: only an instance that already exists,
 * through {@link Context#get(jakarta.enterprise.context.spi.Contextual)}, never one created for the occasion.
 *
 * @param items  tools, then prompts, resources and resource templates, each kind in name or URI order
 * @param absent why the catalogue is empty, or {@code null} when it could be read
 */
record McpCatalogue(List<Item> items, String absent) {

    static final McpCatalogue EMPTY = new McpCatalogue(List.of(), null);
    /** Registry beans exist, but the server has not created any of them yet. */
    static final String NO_REGISTRY = "no MCP registry created yet";
    /** What a tool that may change something asks before a call. */
    static final String CONFIRMATION = "Call %s? It runs the application's code, and may change data.";
    /** The schema of an argument whose own schema is missing or too large: the page offers a raw JSON editor. */
    static final String OPEN_SCHEMA = "{\"type\":\"object\"}";

    /** What an item is, the group its action goes in, and the name of its json argument. */
    enum Kind {
        TOOL("Tools", "arguments", "Arguments"),
        PROMPT("Prompts", "arguments", "Arguments"),
        RESOURCE("Resources", null, null),
        TEMPLATE("Resources", "variables", "Variables");

        private final String group;
        private final String argument;
        private final String argumentLabel;

        Kind(String group, String argument, String argumentLabel) {
            this.group = group;
            this.argument = argument;
            this.argumentLabel = argumentLabel;
        }

        /** The group its actions are shown in. */
        String group() {
            return group;
        }

        /** The name of its json argument; {@code null} for a fixed resource, which takes none. */
        String argument() {
            return argument;
        }

        /** What the page writes next to that argument. */
        String argumentLabel() {
            return argumentLabel;
        }
    }

    /**
     * One thing the inspector can call.
     *
     * @param kind               what it is
     * @param id                 the id of its action, by {@link ActionIds}
     * @param target             the tool or prompt name, the resource URI or the URI template
     * @param label              the tool's title, else its name; the prompt's name; the URI or URI template
     * @param description        its description, at most {@link PanelAction#MAX_DESCRIPTION} characters, or
     *                           {@code null}
     * @param schema             the JSON Schema of its argument, {@code null} for a fixed resource
     * @param confirmation       the question asked before a call, or {@code null}
     * @param headerDesignations for a tool, the arguments it mirrors into {@code Mcp-Param-*} headers, by name
     */
    record Item(Kind kind, String id, String target, String label, String description, String schema,
                String confirmation, Map<String, String> headerDesignations) {}

    McpCatalogue {
        items = List.copyOf(items);
    }

    /**
     * Reads the catalogue from the registries that exist in the container, creating none. A registry with no
     * instance yet counts as empty; none at all is {@link #NO_REGISTRY}, and no registry bean at all
     * {@link McpLiveBeans#NOT_DEPLOYED}. Never throws: a registry that fails makes a catalogue that says so.
     *
     * @param beans the bean manager of the started container
     * @return the catalogue
     */
    static McpCatalogue read(BeanManager beans) {
        try {
            Bean<?> toolBean = bean(beans, McpToolRegistry.class);
            Bean<?> promptBean = bean(beans, McpPromptRegistry.class);
            Bean<?> resourceBean = bean(beans, McpResourceRegistry.class);
            if (toolBean == null && promptBean == null && resourceBean == null) {
                return new McpCatalogue(List.of(), McpLiveBeans.NOT_DEPLOYED);
            }
            McpToolRegistry tools = instance(beans, toolBean, McpToolRegistry.class);
            McpPromptRegistry prompts = instance(beans, promptBean, McpPromptRegistry.class);
            McpResourceRegistry resources = instance(beans, resourceBean, McpResourceRegistry.class);
            if (tools == null && prompts == null && resources == null) {
                return new McpCatalogue(List.of(), NO_REGISTRY);
            }
            return of(tools == null ? List.of() : tools.listTools(),
                    prompts == null ? List.of() : prompts.listPrompts(),
                    resources == null ? List.of() : resources.listResources(),
                    resources == null ? List.of() : resources.listTemplates());
        } catch (RuntimeException | LinkageError unreadable) {
            return new McpCatalogue(List.of(), "catalogue unreadable: " + unreadable.getClass().getSimpleName());
        }
    }

    /**
     * The catalogue of these descriptors, each kind in name or URI order, so that ids are stable across reloads.
     */
    static McpCatalogue of(Collection<McpToolDescriptor> tools, Collection<McpPromptDescriptor> prompts,
                           Collection<McpResourceDescriptor> resources,
                           Collection<McpResourceTemplateDescriptor> templates) {
        ActionIds ids = new ActionIds();
        List<Item> items = new ArrayList<>();
        for (McpToolDescriptor tool : sorted(tools, McpToolDescriptor::getName)) {
            String title = tool.getTitle();
            items.add(new Item(Kind.TOOL, ids.named("tool.", tool.getName()), tool.getName(),
                    title == null || title.isBlank() ? tool.getName() : title, description(tool.getDescription()),
                    schema(tool), confirmation(tool), Map.copyOf(tool.getHeaderDesignations())));
        }
        for (McpPromptDescriptor prompt : sorted(prompts, McpPromptDescriptor::getName)) {
            items.add(new Item(Kind.PROMPT, ids.named("prompt.", prompt.getName()), prompt.getName(),
                    prompt.getName(), description(prompt.getDescription()), promptSchema(prompt.getArguments()),
                    null, Map.of()));
        }
        for (McpResourceDescriptor resource : sorted(resources, McpResourceDescriptor::getUri)) {
            items.add(new Item(Kind.RESOURCE, ids.hashed("res.", resource.getUri()), resource.getUri(),
                    resource.getUri(), description(resource.getDescription()), null, null, Map.of()));
        }
        for (McpResourceTemplateDescriptor template
                : sorted(templates, McpResourceTemplateDescriptor::getUriTemplate)) {
            items.add(new Item(Kind.TEMPLATE, ids.hashed("tpl.", template.getUriTemplate()),
                    template.getUriTemplate(), template.getUriTemplate(), description(template.getDescription()),
                    templateSchema(template.getVariableNames()), null, Map.of()));
        }
        return new McpCatalogue(items, null);
    }

    /** {@code 5 tools, 1 prompt, 1 resource, 1 resource template}. */
    String summary() {
        return count(Kind.TOOL, "tool") + ", " + count(Kind.PROMPT, "prompt") + ", "
                + count(Kind.RESOURCE, "resource") + ", " + count(Kind.TEMPLATE, "resource template");
    }

    /**
     * The question a tool asks before a call: none when it is {@code readOnlyHint: true} and not
     * {@code destructiveHint: true}. langchain4j-cdi keeps an annotation member only when it differs from its
     * default, so a {@code @Tool} never carries {@code destructiveHint: true}; a descriptor built by hand may.
     */
    static String confirmation(McpToolDescriptor tool) {
        McpToolAnnotationsModel annotations = tool.getAnnotations();
        boolean readOnly = annotations != null && Boolean.TRUE.equals(annotations.readOnlyHint());
        boolean destructive = annotations != null && Boolean.TRUE.equals(annotations.destructiveHint());
        return readOnly && !destructive ? null : CONFIRMATION.formatted(tool.getName());
    }

    private String count(Kind kind, String noun) {
        long n = items.stream().filter(item -> item.kind() == kind).count();
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The modern era's input schema, else the legacy one; an open object when missing or too large. */
    private static String schema(McpToolDescriptor tool) {
        JsonObject schema = tool.getModernInputSchema() != null ? tool.getModernInputSchema() : tool.getInputSchema();
        String text = schema == null ? OPEN_SCHEMA : schema.toString();
        return text.length() > PanelAction.Argument.MAX_SCHEMA ? OPEN_SCHEMA : text;
    }

    /** A flat schema: a {@code string} per argument, {@code required} as declared. */
    private static String promptSchema(List<McpPromptDescriptor.PromptArgument> arguments) {
        JsonObjectBuilder properties = Json.createObjectBuilder();
        JsonArrayBuilder required = Json.createArrayBuilder();
        for (McpPromptDescriptor.PromptArgument argument : arguments == null
                ? List.<McpPromptDescriptor.PromptArgument>of() : arguments) {
            JsonObjectBuilder property = Json.createObjectBuilder().add("type", "string");
            if (argument.description() != null && !argument.description().isBlank()) {
                property.add("description", argument.description());
            }
            properties.add(argument.name(), property);
            if (argument.required()) {
                required.add(argument.name());
            }
        }
        return Json.createObjectBuilder().add("type", "object").add("properties", properties)
                .add("required", required).build().toString();
    }

    /** A flat schema: a required {@code string} per variable. */
    private static String templateSchema(List<String> variables) {
        JsonObjectBuilder properties = Json.createObjectBuilder();
        JsonArrayBuilder required = Json.createArrayBuilder();
        for (String variable : variables) {
            properties.add(variable, Json.createObjectBuilder().add("type", "string"));
            required.add(variable);
        }
        return Json.createObjectBuilder().add("type", "object").add("properties", properties)
                .add("required", required).build().toString();
    }

    /** A description, stripped, cut to {@link PanelAction#MAX_DESCRIPTION} characters; {@code null} when blank. */
    private static String description(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= PanelAction.MAX_DESCRIPTION ? stripped
                : stripped.substring(0, PanelAction.MAX_DESCRIPTION - 3) + "...";
    }

    private static <T> List<T> sorted(Collection<T> items, Function<T, String> key) {
        return items.stream().sorted(Comparator.comparing(key, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The one bean of {@code type}, when it is that very class: the twin guard of {@code McpInspection}. */
    private static Bean<?> bean(BeanManager beans, Class<?> type) {
        Bean<?> bean = beans.resolve(beans.getBeans(type));
        return bean != null && bean.getBeanClass() == type ? bean : null;
    }

    /** The instance {@code bean} already has, or {@code null}: the one-argument {@code Context.get} creates none. */
    private static <T> T instance(BeanManager beans, Bean<?> bean, Class<T> type) {
        if (bean == null) {
            return null;
        }
        Object existing = beans.getContext(bean.getScope()).get(bean);
        return type.isInstance(existing) ? type.cast(existing) : null;
    }
}
