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
package io.vidocq.runtime.maven.idea;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static io.vidocq.runtime.maven.idea.IdeaApplicationsTest.compilerPlugin;
import static io.vidocq.runtime.maven.idea.IdeaApplicationsTest.dom;
import static io.vidocq.runtime.maven.idea.IdeaApplicationsTest.execution;
import static io.vidocq.runtime.maven.idea.IdeaApplicationsTest.leaf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The triggers of IntelliJ's main/test module split that {@link IdeaApplications#splitReason} reads. */
class IdeaApplicationsSplitReasonTest {

    private static final String DEFAULT_COMPILE = "default-compile";
    private static final String DEFAULT_TEST_COMPILE = "default-testCompile";
    private static final String COMPILE = "compile";
    private static final String TEST_COMPILE = "testCompile";
    private static final String CONFIGURATION = "configuration";
    private static final String SOURCE_ROOTS = "compileSourceRoots";
    private static final String LEVEL = "25";

    @Test
    void noCompilerAndNoTestLevelIsNoSplit() {
        assertNull(IdeaApplications.splitReason(project(null)));
    }

    @Test
    void aTestLevelWithoutAMainOneIsASplit() {
        MavenProject project = project(null);
        project.getProperties().setProperty("maven.compiler.testSource", "21");

        assertEquals("testSource 21 differs from source (unset)", IdeaApplications.splitReason(project));
    }

    @Test
    void aTestLevelOfTheCompilerConfigurationIsASplit() {
        Plugin compiler = compilerPlugin();
        compiler.setConfiguration(dom(CONFIGURATION, leaf("target", LEVEL), leaf("testTarget", "21")));

        assertEquals("testTarget 21 differs from target 25", IdeaApplications.splitReason(project(compiler)));
    }

    @Test
    void aCompilerWithoutExecutionsAndEqualLevelsIsNoSplit() {
        Plugin compiler = compilerPlugin();
        compiler.setConfiguration(dom(CONFIGURATION, leaf("release", LEVEL), leaf("testRelease", LEVEL)));

        assertNull(IdeaApplications.splitReason(project(compiler)));
    }

    @Test
    void testCompilerArgumentsAreASplit() {
        Plugin compiler = compilerPlugin();
        compiler.addExecution(execution(DEFAULT_TEST_COMPILE, TEST_COMPILE,
                dom(CONFIGURATION, dom("testCompilerArguments", leaf("parameters", "true")))));

        assertEquals("testCompilerArguments is set", IdeaApplications.splitReason(project(compiler)));
    }

    @Test
    void differentToolchainsAreASplit() {
        Plugin compiler = compilerPlugin();
        compiler.addExecution(execution(DEFAULT_COMPILE, COMPILE,
                dom(CONFIGURATION, dom("jdkToolchain", leaf("version", LEVEL)))));
        compiler.addExecution(execution(DEFAULT_TEST_COMPILE, TEST_COMPILE, dom(CONFIGURATION)));

        assertEquals("compile and testCompile use different jdkToolchain",
                IdeaApplications.splitReason(project(compiler)));
    }

    @Test
    void anExecutionThatAddsSourceRootsIsASplit() {
        Plugin compiler = compilerPlugin();
        compiler.addExecution(execution(DEFAULT_COMPILE, COMPILE, dom(CONFIGURATION)));
        compiler.addExecution(execution(DEFAULT_TEST_COMPILE, TEST_COMPILE, dom(CONFIGURATION)));
        compiler.addExecution(execution("generated", COMPILE,
                dom(CONFIGURATION, dom(SOURCE_ROOTS, leaf("compileSourceRoot", "src/generated/java")))));

        assertEquals("execution 'generated' adds compileSourceRoots", IdeaApplications.splitReason(project(compiler)));
    }

    @Test
    void sourceRootsOfTheDefaultExecutionsOrOfAnotherPhaseAreNoSplit() {
        Plugin compiler = compilerPlugin();
        compiler.addExecution(execution(DEFAULT_COMPILE, COMPILE, addsSourceRoot()));
        compiler.addExecution(execution(DEFAULT_TEST_COMPILE, TEST_COMPILE, addsSourceRoot()));
        PluginExecution late = execution("late", COMPILE, addsSourceRoot());
        late.setPhase("process-classes");
        compiler.addExecution(late);
        compiler.addExecution(execution("empty", COMPILE, dom(CONFIGURATION, dom(SOURCE_ROOTS))));

        assertNull(IdeaApplications.splitReason(project(compiler)));
    }

    /** A configuration that adds one source root. */
    private static Xpp3Dom addsSourceRoot() {
        return dom(CONFIGURATION, dom(SOURCE_ROOTS, leaf("compileSourceRoot", "src/more/java")));
    }

    private static MavenProject project(Plugin compiler) {
        Model model = new Model();
        model.setBuild(new Build());
        if (compiler != null) {
            model.getBuild().addPlugin(compiler);
        }
        return new MavenProject(model);
    }
}
