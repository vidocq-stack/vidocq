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

import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which modules of a reactor an IDE still compiles into {@code target/} ({@link IdeOutputReach}). */
class IdeOutputReachTest {

    @Test
    void anApplicationWithThePluginAsAnExtensionIsReached() {
        assertEquals(List.of(), IdeOutputReach.unreached(List.of(application("todo", true))));
    }

    /** Maven loads the lifecycle participant from a build extension only. */
    @Test
    void thePluginWithoutExtensionsDoesNotReach() {
        assertEquals(List.of("todo"), IdeOutputReach.unreached(List.of(application("todo", false))));
    }

    @Test
    void aLibraryModuleWithoutThePluginIsNamed() {
        List<MavenProject> reactor = List.of(project("todo-parent", "pom"), project("todo-model", "jar"),
                application("todo", true));

        assertEquals(List.of("todo-model"), IdeOutputReach.unreached(reactor));
    }

    /** An IDE compiles nothing of an aggregator. */
    @Test
    void anAggregatorIsNeverNamed() {
        assertEquals(List.of(), IdeOutputReach.unreached(List.of(project("todo-parent", "pom"))));
    }

    @Test
    void aProfileOnM2eVersionInAParentReaches() {
        MavenProject grandParent = project("company-parent", "pom");
        grandParent.getModel().addProfile(profileOn("m2e.version"));
        MavenProject parent = project("todo-parent", "pom");
        parent.setParent(grandParent);
        MavenProject library = project("todo-model", "jar");
        library.setParent(parent);

        assertEquals(List.of(), IdeOutputReach.unreached(List.of(parent, library)));
    }

    @Test
    void aProfileOfTheModuleItselfReaches() {
        MavenProject library = project("todo-model", "jar");
        library.getModel().addProfile(profileOn("m2e.version"));

        assertEquals(List.of(), IdeOutputReach.unreached(List.of(library)));
    }

    @Test
    void aProfileOnAnotherPropertyDoesNot() {
        MavenProject parent = project("todo-parent", "pom");
        parent.getModel().addProfile(profileOn("idea.version"));
        MavenProject library = project("todo-model", "jar");
        library.setParent(parent);

        assertEquals(List.of("todo-model"), IdeOutputReach.unreached(List.of(parent, library)));
    }

    /** A profile with no activation, or one activated by something other than a property, moves nothing. */
    @Test
    void aProfileThatNoPropertyActivatesDoesNot() {
        MavenProject library = project("todo-model", "jar");
        Profile none = new Profile();
        none.setId("release");
        Profile byDefault = new Profile();
        byDefault.setId("fast");
        byDefault.setActivation(new Activation());
        byDefault.getActivation().setActiveByDefault(true);
        library.getModel().addProfile(none);
        library.getModel().addProfile(byDefault);

        assertEquals(List.of("todo-model"), IdeOutputReach.unreached(List.of(library)));
    }

    @Test
    void theModulesComeInTheOrderOfTheReactor() {
        List<MavenProject> reactor = List.of(project("zeta", "jar"), application("todo", true),
                project("alpha", "jar"));

        assertEquals(List.of("zeta", "alpha"), IdeOutputReach.unreached(reactor));
    }

    private static MavenProject application(String artifactId, boolean extension) {
        MavenProject project = project(artifactId, "jar");
        Plugin plugin = IdeaApplicationsTest.vidocqPlugin();
        plugin.setExtensions(extension);
        project.getBuild().addPlugin(plugin);
        return project;
    }

    private static MavenProject project(String artifactId, String packaging) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(artifactId);
        model.setPackaging(packaging);
        model.setBuild(new Build());
        return new MavenProject(model);
    }

    private static Profile profileOn(String property) {
        Profile profile = new Profile();
        profile.setId("ide-output");
        Activation activation = new Activation();
        ActivationProperty name = new ActivationProperty();
        name.setName(property);
        activation.setProperty(name);
        profile.setActivation(activation);
        return profile;
    }
}
