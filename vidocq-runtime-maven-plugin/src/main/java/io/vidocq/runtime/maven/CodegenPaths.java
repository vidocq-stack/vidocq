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
package io.vidocq.runtime.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Puts on maven-compiler-plugin's {@code annotationProcessorPaths}, in the effective model, the Vauban indexer
 * ({@code vidocq-runtime-core-codegen}) and the codegen bundle of each catalog extension the project depends on
 * outside the test scope (Vidocq/vidocq#339, evolution 2). A path the project already declares is kept as it is.
 *
 * <p>The paths go into the plugin's configuration and into each of its executions, as Maven has already copied the
 * plugin's configuration into them when it built the model. Only a project that declares this plugin is touched:
 * in a multi-module build, the other modules are libraries.</p>
 *
 * <p>Compiled for Java 17, with {@link CodegenPathsParticipant}, and reading the extension catalog as a resource
 * rather than through {@code vidocq-runtime-tooling} (Java 25): an IDE may run its Maven import on an older runtime,
 * as VS Code's Java extension does on Java 21, and a participant it cannot load fails the whole import.</p>
 */
final class CodegenPaths {

    static final String RUNTIME_GROUP = "io.vidocq.runtime";
    static final String CORE = "vidocq-runtime-core";
    static final String CORE_CODEGEN = "vidocq-runtime-core-codegen";
    private static final String PLUGIN = "vidocq-runtime-maven-plugin";
    private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
    private static final String PATHS = "annotationProcessorPaths";
    /** Generated from the extensions' descriptors; read by {@code KnownExtensions} too. */
    static final String CATALOG = "META-INF/vidocq/extension-catalog.properties";
    /** {@code groupId:artifactId} of a catalog extension → {groupId, artifactId} of its codegen bundle. */
    private static final Map<String, String[]> BUNDLES = bundles();

    private CodegenPaths() {}

    /** Wires the paths {@code model} lacks and returns their artifactIds, in the order added. */
    static List<String> wire(Model model) {
        if (model.getBuild() == null || !declaresThePlugin(model)) {
            return List.of();
        }
        Plugin compiler = model.getBuild().getPluginsAsMap().get(COMPILER);
        Map<String, String[]> wanted = wanted(model);
        if (compiler == null || wanted.isEmpty()) {
            return List.of();
        }
        List<String> added = new ArrayList<>();
        addTo(compiler.getConfiguration(), compiler::setConfiguration, wanted, added);
        for (PluginExecution execution : compiler.getExecutions()) {
            addTo(execution.getConfiguration(), execution::setConfiguration, wanted, added);
        }
        return added;
    }

    private static boolean declaresThePlugin(Model model) {
        return model.getBuild().getPlugins().stream()
                .anyMatch(p -> RUNTIME_GROUP.equals(p.getGroupId()) && PLUGIN.equals(p.getArtifactId()));
    }

    /** artifactId → {groupId, artifactId, version}: the indexer first, then one bundle per extension. */
    private static Map<String, String[]> wanted(Model model) {
        Map<String, String[]> wanted = new LinkedHashMap<>();
        String runtimeVersion = null;
        for (Dependency d : model.getDependencies()) {
            if ("test".equals(d.getScope())) {
                continue;
            }
            if (RUNTIME_GROUP.equals(d.getGroupId()) && CORE.equals(d.getArtifactId())) {
                runtimeVersion = d.getVersion();
            }
            String[] bundle = BUNDLES.get(d.getGroupId() + ":" + d.getArtifactId());
            if (bundle != null) {
                wanted.put(bundle[1], new String[] {bundle[0], bundle[1], d.getVersion()});
            }
            if (runtimeVersion == null && !wanted.isEmpty()) {
                runtimeVersion = d.getVersion();
            }
        }
        if (runtimeVersion == null) {
            return Map.of();
        }
        Map<String, String[]> ordered = new LinkedHashMap<>();
        ordered.put(CORE_CODEGEN, new String[] {RUNTIME_GROUP, CORE_CODEGEN, runtimeVersion});
        ordered.putAll(wanted);
        return ordered;
    }

    private static void addTo(Object configuration, Consumer<Object> setter, Map<String, String[]> wanted,
                              List<String> added) {
        Xpp3Dom config = (Xpp3Dom) configuration;
        if (config == null) {
            config = new Xpp3Dom("configuration");
            setter.accept(config);
        }
        Xpp3Dom paths = config.getChild(PATHS);
        if (paths == null) {
            paths = new Xpp3Dom(PATHS);
            config.addChild(paths);
        }
        List<String> declared = new ArrayList<>();
        for (Xpp3Dom path : paths.getChildren("path")) {
            Xpp3Dom artifactId = path.getChild("artifactId");
            if (artifactId != null && artifactId.getValue() != null) {
                declared.add(artifactId.getValue().strip());
            }
        }
        for (String[] coordinate : wanted.values()) {
            if (declared.contains(coordinate[1])) {
                continue;
            }
            Xpp3Dom path = new Xpp3Dom("path");
            path.addChild(element("groupId", coordinate[0]));
            path.addChild(element("artifactId", coordinate[1]));
            path.addChild(element("version", coordinate[2]));
            paths.addChild(path);
            if (!added.contains(coordinate[1])) {
                added.add(coordinate[1]);
            }
        }
    }

    private static Map<String, String[]> bundles() {
        Properties catalog = new Properties();
        try (InputStream in = CodegenPaths.class.getClassLoader().getResourceAsStream(CATALOG)) {
            if (in == null) {
                throw new IllegalStateException(CATALOG + " is missing from the plugin's class path");
            }
            catalog.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String[]> bundles = new HashMap<>();
        for (String key : catalog.stringPropertyNames()) {
            if (!key.endsWith(".artifactId")) {
                continue;
            }
            String id = key.substring(0, key.length() - ".artifactId".length());
            String groupId = catalog.getProperty(id + ".groupId", "").strip();
            String codegen = catalog.getProperty(id + ".codegen", "").strip();
            if (!groupId.isEmpty() && !codegen.isEmpty()) {
                bundles.put(groupId + ":" + catalog.getProperty(key).strip(), new String[] {groupId, codegen});
            }
        }
        return bundles;
    }

    private static Xpp3Dom element(String name, String value) {
        Xpp3Dom element = new Xpp3Dom(name);
        element.setValue(value);
        return element;
    }
}
