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
package io.vidocq.runtime.maven.depgen;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorPathsTest {

    private static String path(String g, String a, String v, String extra) {
        return "<path><groupId>" + g + "</groupId><artifactId>" + a + "</artifactId>"
                + (v == null ? "" : "<version>" + v + "</version>") + extra + "</path>";
    }

    private static MavenProject project(String config, String executionConfig) throws Exception {
        Model model = new Model();
        Plugin compiler = new Plugin();
        compiler.setGroupId("org.apache.maven.plugins");
        compiler.setArtifactId("maven-compiler-plugin");
        if (config != null) {
            compiler.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                    "<configuration><annotationProcessorPaths>" + config + "</annotationProcessorPaths></configuration>")));
        }
        if (executionConfig != null) {
            PluginExecution execution = new PluginExecution();
            execution.setId("other");
            execution.setConfiguration(Xpp3DomBuilder.build(new StringReader(
                    "<configuration><annotationProcessorPaths>" + executionConfig
                            + "</annotationProcessorPaths></configuration>")));
            compiler.addExecution(execution);
        }
        Build build = new Build();
        build.addPlugin(compiler);
        model.setBuild(build);
        return new MavenProject(model);
    }

    @Test
    void readsTheConfigurationThenTheExecutionsInOrder() throws Exception {
        MavenProject project = project(
                path("g", "a", "1", "") + path("g", "b", "2", "<type>pom</type><classifier>c</classifier>"),
                path("h", "c", null, ""));

        assertEquals(List.of(
                new ProcessorPath("g", "a", "1", "jar", null),
                new ProcessorPath("g", "b", "2", "pom", "c"),
                new ProcessorPath("h", "c", null, "jar", null)), ProcessorPaths.of(project));
    }

    @Test
    void dropsDuplicatesOfTheSameGroupArtifactTypeAndClassifier() throws Exception {
        MavenProject project = project(path("g", "a", "1", ""), path("g", "a", "2", ""));

        assertEquals(List.of(new ProcessorPath("g", "a", "1", "jar", null)), ProcessorPaths.of(project));
    }

    @Test
    void noCompilerPluginMeansNoPath() {
        assertTrue(ProcessorPaths.of(new MavenProject(new Model())).isEmpty());
    }
}
