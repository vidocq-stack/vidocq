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
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec §4.1 configuration keys: {@code mp.openapi.servers.path.*} and
 * {@code mp.openapi.servers.operation.*} (§4.1.5, alternative servers), the scan and filter keys
 * and {@code mp.openapi.schema.*}, read by {@link GrimmConfig} and applied by
 * {@link ConfigApplier}.
 */
class ConfigServersAndOverridesTest {

    private static final String PATH = "/items";
    private static final String PATH_KEY = "mp.openapi.servers.path.";
    private static final String OPERATION_KEY = "mp.openapi.servers.operation.";
    private static final String SCHEMA_KEY = "mp.openapi.schema.";
    private static final String PATH_URL = "https://path.example.com";
    private static final String OP_URL = "https://op.example.com";
    private static final String LIST_ID = "listItems";
    private static final String STRING_SCHEMA = "{\"type\":\"string\"}";

    /** Target of a schema override. */
    static final class Sample {
        @Override public String toString() {
            return "sample";
        }
    }

    private static OpenAPI modelWithOperations() {
        PathItem item = OASFactory.createPathItem()
                .GET(OASFactory.createOperation().operationId(LIST_ID))
                .POST(OASFactory.createOperation());
        return OASFactory.createOpenAPI().paths(OASFactory.createPaths()
                .addPathItem(PATH, item)
                .addPathItem("/other", OASFactory.createPathItem()));
    }

    @Test
    void pathAndOperationServersAreApplied() {
        GrimmConfig config = GrimmConfig.fromMap(Map.of(
                PATH_KEY + PATH, PATH_URL + ", ",
                OPERATION_KEY + LIST_ID, OP_URL));
        OpenAPI model = modelWithOperations();

        ConfigApplier.applyServers(model, config);

        PathItem item = model.getPaths().getPathItem(PATH);
        assertEquals(PATH_URL, item.getServers().get(0).getUrl());
        assertEquals(1, item.getServers().size());
        assertEquals(OP_URL, item.getGET().getServers().get(0).getUrl());
        assertNull(item.getPOST().getServers());
        assertNull(model.getPaths().getPathItem("/other").getServers());
        assertNull(model.getServers());
    }

    @Test
    void blankServerUrlsAreSkipped() {
        GrimmConfig config = new GrimmConfig(ScanConfig.defaultConfig(), FilterConfig.defaultConfig(),
                List.of(" ", OP_URL), null, null, null, false);
        OpenAPI model = OASFactory.createOpenAPI();

        ConfigApplier.applyServers(model, config);

        assertEquals(1, model.getServers().size());
    }

    @Test
    void nullArgumentsAndModelsWithoutPathsAreLeftAlone() {
        GrimmConfig config = GrimmConfig.fromMap(Map.of(PATH_KEY + PATH, PATH_URL));
        OpenAPI empty = OASFactory.createOpenAPI();

        ConfigApplier.applyServers(null, config);
        ConfigApplier.applyServers(empty, null);
        ConfigApplier.applyServers(empty, config);

        assertNull(empty.getPaths());
        assertEquals(Map.of(), ConfigApplier.applySchemaOverrides(null, config));
        assertEquals(Map.of(), ConfigApplier.applySchemaOverrides(new SchemaRegistry(), null));
    }

    @Test
    void blankServerKeysAndValuesAreIgnored() {
        Map<String, String> source = new HashMap<>();
        source.put(PATH_KEY, PATH_URL);
        source.put(PATH_KEY + PATH, " ");
        source.put(OPERATION_KEY, OP_URL);
        source.put(OPERATION_KEY + LIST_ID, "");
        source.put(SCHEMA_KEY, STRING_SCHEMA);
        source.put(SCHEMA_KEY + Sample.class.getName(), " ");

        GrimmConfig config = GrimmConfig.fromMap(source);

        assertEquals(Map.of(), config.pathServers());
        assertEquals(Map.of(), config.operationServers());
        assertEquals(Map.of(), config.schemaOverrides());
    }

    @Test
    void scanAndFilterKeysAreRead() {
        GrimmConfig config = GrimmConfig.fromMap(Map.of(
                "mp.openapi.scan.disable", " TRUE ",
                "mp.openapi.scan.beanvalidation", "false",
                "mp.openapi.scan.packages", "com.a, ,com.b",
                "mp.openapi.filter", "  ",
                "mp.openapi.model.reader", " com.example.Reader "));

        assertTrue(config.scan().disableScan());
        assertFalse(config.scan().scanBeanValidation());
        assertEquals(2, config.scan().includePackages().size());
        assertFalse(config.filter().hasFilter());
        assertEquals("com.example.Reader", config.filter().modelReaderClassName());
    }

    @Test
    void theCanonicalConstructorCopiesAndCleansItsMaps() {
        Map<String, List<String>> pathServers = new LinkedHashMap<>();
        pathServers.put(" ", List.of(PATH_URL));
        pathServers.put(PATH, null);

        GrimmConfig config = new GrimmConfig(ScanConfig.defaultConfig(), FilterConfig.defaultConfig(),
                null, pathServers, Map.of(), null, true);

        assertEquals(Map.of(PATH, List.of()), config.pathServers());
        assertEquals(List.of(), config.servers());
        assertTrue(config.extensionsScanDisable());
        ScanConfig scan = ScanConfig.defaultConfig();
        assertThrows(NullPointerException.class,
                () -> new GrimmConfig(scan, null, null, null, null, null, false));
    }

    @Test
    void anOverrideThatIsNotAJsonObjectIsSkipped() {
        SchemaRegistry registry = new SchemaRegistry();
        GrimmConfig config = GrimmConfig.fromMap(Map.of(SCHEMA_KEY + Sample.class.getName(), "[1, 2]"));

        assertEquals(Map.of(), ConfigApplier.applySchemaOverrides(registry, config));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void theNameFieldChoosesTheRegistryName() {
        SchemaRegistry registry = new SchemaRegistry();
        GrimmConfig config = GrimmConfig.fromMap(Map.of(SCHEMA_KEY + Sample.class.getName(),
                "{\"name\":\"Renamed\",\"type\":\"string\"}"));

        Map<String, String> assigned = ConfigApplier.applySchemaOverrides(registry, config);

        assertEquals("Renamed", assigned.get(Sample.class.getName()));
        assertNull(registry.snapshot().get("Renamed").get("name"));
    }

    @Test
    void aClassInvisibleToTheContextLoaderIsLoadedFromGrimm() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        GrimmConfig config = GrimmConfig.fromMap(Map.of(SCHEMA_KEY + Sample.class.getName(), STRING_SCHEMA));
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], null)) {
            thread.setContextClassLoader(isolated);
            assertEquals("Sample", ConfigApplier.applySchemaOverrides(registry, config).get(Sample.class.getName()));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
