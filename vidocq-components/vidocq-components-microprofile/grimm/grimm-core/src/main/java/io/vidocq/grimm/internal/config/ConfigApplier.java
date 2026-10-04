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
package io.vidocq.grimm.internal.config;

import io.vidocq.grimm.internal.schema.SchemaRegistry;
import io.vidocq.grimm.internal.serialization.JsonDeserializer;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.servers.Server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies {@link GrimmConfig} side effects to the assembled OpenAPI model
 * (spec §4.1 keys {@code mp.openapi.servers} and {@code mp.openapi.schema.<FQCN>}).
 *
 * <p>Called after the {@code ModelMerger} but before the {@code FilterInvoker} so
 * filters can still inspect / mutate the config-applied state.</p>
 */
public final class ConfigApplier {

    private ConfigApplier() {}

    /**
     * Replaces {@code openAPI.servers} with the URLs from {@code mp.openapi.servers}
     * when the property is set (non-empty). When unset, the model's existing servers
     * are left untouched.
     */
    public static void applyServers(OpenAPI openAPI, GrimmConfig config) {
        if (openAPI == null || config == null) {
            return;
        }

        List<Server> globalServers = createServers(config.servers());
        if (!globalServers.isEmpty()) {
            openAPI.setServers(globalServers);
        }

        var paths = openAPI.getPaths();
        var pathItems = paths == null ? null : paths.getPathItems();
        if (pathItems == null || pathItems.isEmpty()) {
            return;
        }

        for (Map.Entry<String, PathItem> entry : pathItems.entrySet()) {
            PathItem item = entry.getValue();
            if (item == null) {
                continue;
            }
            List<Server> pathServers = createServers(config.pathServers().get(entry.getKey()));
            if (!pathServers.isEmpty()) {
                item.setServers(pathServers);
            }

            for (PathItem.HttpMethod httpMethod : PathItem.HttpMethod.values()) {
                Operation operation = getOperation(item, httpMethod);
                if (operation == null || operation.getOperationId() == null) {
                    continue;
                }
                List<Server> operationServers = createServers(config.operationServers().get(operation.getOperationId()));
                if (!operationServers.isEmpty()) {
                    operation.setServers(operationServers);
                }
            }
        }
    }

    private static List<Server> createServers(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<Server> servers = new ArrayList<>();
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            Server s = OASFactory.createObject(Server.class);
            s.setUrl(url);
            servers.add(s);
        }
        return servers;
    }

    private static Operation getOperation(PathItem item, PathItem.HttpMethod method) {
        return switch (method) {
            case GET -> item.getGET();
            case POST -> item.getPOST();
            case PUT -> item.getPUT();
            case DELETE -> item.getDELETE();
            case PATCH -> item.getPATCH();
            case HEAD -> item.getHEAD();
            case OPTIONS -> item.getOPTIONS();
            case TRACE -> item.getTRACE();
        };
    }

    /**
     * Registers schema overrides from {@code mp.openapi.schema.<FQCN>} into the given
     * {@link SchemaRegistry} under the simple class name. Subsequent calls to
     * {@code SchemaGenerator.generate(clazz)} for one of these classes will return a
     * {@code $ref} to the override.
     *
     * <p>The value of each property is parsed as a JSON object via {@link JsonDeserializer}
     * and converted into a {@link Schema} by the typed mapper of the static-file reader.</p>
     *
     * @return a map FQCN → registered schema name (for diagnostic purposes)
     */
    public static Map<String, String> applySchemaOverrides(SchemaRegistry registry, GrimmConfig config) {
        if (registry == null || config == null || config.schemaOverrides().isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : config.schemaOverrides().entrySet()) {
            String fqcn = e.getKey();
            String json = e.getValue();
            Class<?> clazz = tryLoad(fqcn);
            if (clazz == null) {
                continue; // unknown class — silently skip (spec is silent on this case)
            }
            Object parsed = JsonDeserializer.parseRaw(json);
            if (!(parsed instanceof Map<?, ?> map)) {
                continue; // not a JSON object — skip
            }
            // "name" only chooses the registry name: it is not a schema keyword.
            Object nameField = map.get("name");
            Map<Object, Object> keywords = new LinkedHashMap<>(map);
            keywords.remove("name");
            Schema schema = JsonDeserializer.toSchema(expandRefAliases(keywords));
            String preferredName = nameField instanceof String s && !s.isBlank()
                    ? s : clazz.getSimpleName();
            String name = registry.reserve(clazz, preferredName);
            registry.publish(name, schema);
            result.put(fqcn, name);
        }
        return result;
    }

    private static final List<String> SCHEMA_KEYS = List.of("items", "additionalProperties", "not", "if", "then",
            "else", "contains", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "contentSchema");
    private static final List<String> SCHEMA_LIST_KEYS = List.of("allOf", "anyOf", "oneOf", "prefixItems");
    private static final List<String> SCHEMA_MAP_KEYS = List.of("properties", "patternProperties", "dependentSchemas");

    /**
     * Historical alias of {@code mp.openapi.schema.*} values: a {@code ref} key, at any schema level
     * that has no {@code $ref}, is a reference whose short name expands to
     * {@code #/components/schemas/<name>} as {@link Schema#setRef} does. An explicit {@code $ref}
     * is left verbatim, like in a static file.
     */
    private static Map<Object, Object> expandRefAliases(Map<?, ?> schema) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : schema.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object value = e.getValue();
            if (SCHEMA_KEYS.contains(key) && value instanceof Map<?, ?> m) {
                value = expandRefAliases(m);
            } else if (SCHEMA_LIST_KEYS.contains(key) && value instanceof List<?> list) {
                List<Object> items = new ArrayList<>();
                for (Object item : list) {
                    items.add(item instanceof Map<?, ?> m ? expandRefAliases(m) : item);
                }
                value = items;
            } else if (SCHEMA_MAP_KEYS.contains(key) && value instanceof Map<?, ?> m) {
                Map<Object, Object> named = new LinkedHashMap<>();
                for (Map.Entry<?, ?> p : m.entrySet()) {
                    named.put(p.getKey(), p.getValue() instanceof Map<?, ?> pm ? expandRefAliases(pm) : p.getValue());
                }
                value = named;
            }
            out.put(e.getKey(), value);
        }
        if (out.get("ref") instanceof String alias) {
            out.remove("ref"); // an explicit $ref wins; the alias must not reach Schema#set either
            if (!out.containsKey("$ref")) {
                out.put("$ref", alias.contains("/") ? alias : "#/components/schemas/" + alias);
            }
        }
        return out;
    }

    private static Class<?> tryLoad(String fqcn) {
        try {
            return Class.forName(fqcn, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(fqcn);
            } catch (ClassNotFoundException ignored) {
                return null;
            }
        }
    }
}
