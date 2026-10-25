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

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Vidocq/vidocq#339 (evolution 2): the plugin, declared with {@code <extensions>true</extensions>}, puts on the
 * compiler's processor path the codegen bundle of each Vidocq extension the project depends on, and the Vauban
 * indexer, so that an extension takes one place in the pom: its dependency.
 */
class CodegenPathsTest {

    private static final String VERSION = "0.4.0";
    private static final String CASSINI_GROUP = "io.vidocq.runtime.extensions.jakartaee.core";

    @Test
    void anExtensionGetsItsCodegenBundleAndTheIndexerInTheConfigurationAndEveryExecution() {
        Model model = application(dependency(CASSINI_GROUP, "vidocq-runtime-cassini-rest-extension", null));

        List<String> wired = CodegenPaths.wire(model);

        assertEquals(List.of("vidocq-runtime-core-codegen", "vidocq-runtime-cassini-rest-extension-codegen"), wired);
        Plugin compiler = compiler(model);
        assertEquals(List.of("io.vidocq.runtime:vidocq-runtime-core-codegen:" + VERSION,
                CASSINI_GROUP + ":vidocq-runtime-cassini-rest-extension-codegen:" + VERSION),
                paths((Xpp3Dom) compiler.getConfiguration()));
        for (PluginExecution execution : compiler.getExecutions()) {
            assertEquals(paths((Xpp3Dom) compiler.getConfiguration()), paths((Xpp3Dom) execution.getConfiguration()),
                    execution.getId());
        }
    }

    /** A pom written before 0.4.0, or by hand, keeps its paths: nothing is declared twice. */
    @Test
    void aPathTheProjectDeclaresIsNotAddedAgain() {
        Model model = application(dependency(CASSINI_GROUP, "vidocq-runtime-cassini-rest-extension", null));
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom declared = new Xpp3Dom("annotationProcessorPaths");
        declared.addChild(path("io.vidocq.runtime", "vidocq-runtime-core-codegen", null));
        declared.addChild(path(CASSINI_GROUP, "vidocq-runtime-cassini-rest-extension-codegen", null));
        configuration.addChild(declared);
        compiler(model).setConfiguration(configuration);
        // Maven copies the plugin's configuration into each execution when it builds the model.
        compiler(model).getExecutions().forEach(e -> e.setConfiguration(new Xpp3Dom(configuration)));

        assertEquals(List.of(), CodegenPaths.wire(model));
        assertEquals(List.of("io.vidocq.runtime:vidocq-runtime-core-codegen:null",
                CASSINI_GROUP + ":vidocq-runtime-cassini-rest-extension-codegen:null"), paths(configuration));
    }

    /** A test-scope Vidocq jar (vidocq-runtime-junit, a dev service) brings no processor to the main sources. */
    @Test
    void testDependenciesAndExtensionsWithoutCodegenAreLeftOut() {
        Dependency junit = dependency("io.vidocq.runtime", "vidocq-runtime-junit", "test");
        Model model = application(junit, dependency("org.acme", "acme-extension", null));

        assertEquals(List.of("vidocq-runtime-core-codegen"), CodegenPaths.wire(model));
    }

    /** In a multi-module build, a module that does not declare the plugin is not an application. */
    @Test
    void aModuleWithoutThePluginIsLeftAlone() {
        Model model = application(dependency(CASSINI_GROUP, "vidocq-runtime-cassini-rest-extension", null));
        model.getBuild().getPlugins().removeIf(p -> p.getArtifactId().equals("vidocq-runtime-maven-plugin"));

        assertEquals(List.of(), CodegenPaths.wire(model));
        assertNull(compiler(model).getConfiguration());
    }

    @Test
    void aProjectWithoutVidocqDependencyIsLeftAlone() {
        Model model = application();
        model.getDependencies().clear();

        assertEquals(List.of(), CodegenPaths.wire(model));
    }

    /** The versions come from the dependencies, which the BOM or the parent already resolved in the model. */
    @Test
    void theCodegenBundleTakesTheVersionOfItsExtension() {
        Dependency cassini = dependency(CASSINI_GROUP, "vidocq-runtime-cassini-rest-extension", null);
        cassini.setVersion("0.4.1");
        Model model = application(cassini);

        CodegenPaths.wire(model);

        assertEquals(List.of("io.vidocq.runtime:vidocq-runtime-core-codegen:" + VERSION,
                CASSINI_GROUP + ":vidocq-runtime-cassini-rest-extension-codegen:0.4.1"),
                paths((Xpp3Dom) compiler(model).getConfiguration()));
    }

    // ---- fixtures: the effective model of an application, as Maven builds it before afterProjectsRead ----

    private static Model application(Dependency... extensions) {
        Model model = new Model();
        model.setPackaging("jar");
        model.addDependency(dependency("io.vidocq.runtime", "vidocq-runtime-core", null));
        for (Dependency d : extensions) {
            model.addDependency(d);
        }
        Build build = new Build();
        Plugin vidocq = new Plugin();
        vidocq.setGroupId("io.vidocq.runtime");
        vidocq.setArtifactId("vidocq-runtime-maven-plugin");
        build.addPlugin(vidocq);
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        for (String id : new String[] {"default-compile", "default-testCompile"}) {
            PluginExecution execution = new PluginExecution();
            execution.setId(id);
            compiler.addExecution(execution);
        }
        build.addPlugin(compiler);
        model.setBuild(build);
        return model;
    }

    private static Dependency dependency(String groupId, String artifactId, String scope) {
        Dependency d = new Dependency();
        d.setGroupId(groupId);
        d.setArtifactId(artifactId);
        d.setVersion(VERSION);
        d.setScope(scope);
        return d;
    }

    private static Plugin compiler(Model model) {
        return model.getBuild().getPluginsAsMap().get("org.apache.maven.plugins:maven-compiler-plugin");
    }

    private static Xpp3Dom path(String groupId, String artifactId, String version) {
        Xpp3Dom path = new Xpp3Dom("path");
        for (String[] child : new String[][] {{"groupId", groupId}, {"artifactId", artifactId},
                {"version", version}}) {
            if (child[1] != null) {
                Xpp3Dom c = new Xpp3Dom(child[0]);
                c.setValue(child[1]);
                path.addChild(c);
            }
        }
        return path;
    }

    private static List<String> paths(Xpp3Dom configuration) {
        List<String> result = new ArrayList<>();
        for (Xpp3Dom p : configuration.getChild("annotationProcessorPaths").getChildren("path")) {
            Xpp3Dom version = p.getChild("version");
            result.add(p.getChild("groupId").getValue() + ":" + p.getChild("artifactId").getValue() + ":"
                    + (version == null ? null : version.getValue()));
        }
        return result;
    }
}
