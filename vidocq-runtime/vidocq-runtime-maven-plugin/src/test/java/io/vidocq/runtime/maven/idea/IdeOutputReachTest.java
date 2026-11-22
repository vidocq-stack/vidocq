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

import io.vidocq.runtime.maven.idea.IdeOutputReach.Unreached;
import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Build;
import org.apache.maven.model.BuildBase;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.Profile;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which modules of a build an IDE still compiles into {@code target/}, and what would move each
 * ({@link IdeOutputReach}).
 */
class IdeOutputReachTest {

    private static final Unreached NONE = new Unreached(List.of(), List.of());

    @TempDir
    Path dir;

    @Test
    void anApplicationOfOneModuleWithThePluginAsAnExtensionIsReached() {
        assertEquals(NONE, IdeOutputReach.unreached(List.of(application("todo", true))));
    }

    /** Maven loads the lifecycle participant from a build extension only: the attribute is what is missing. */
    @Test
    void thePluginWithoutExtensionsDoesNotReachAndTheAttributeWould() {
        assertEquals(new Unreached(List.of(), List.of("todo")),
                IdeOutputReach.unreached(List.of(application("todo", false))));
    }

    /**
     * The application of two modules that was run in the language server of VS Code's Java extension: the
     * participant moves none of its modules, the application that declares the plugin no more than the library.
     */
    @Test
    void theModulesOfABuildOfSeveralModulesAreNamedTheApplicationToo() {
        MavenProject parent = project("todo-parent", "pom");
        MavenProject library = module(parent, project("todo-lib", "jar"));
        MavenProject application = module(parent, application("todo-app", true));
        application.getModel().addDependency(dependency("todo-lib"));

        assertEquals(new Unreached(List.of("todo-lib", "todo-app"), List.of()),
                IdeOutputReach.unreached(List.of(parent, library, application)));
    }

    @Test
    void aLibraryWithoutThePluginIsNamed() {
        List<MavenProject> reactor = List.of(project("todo-model", "jar"), application("todo", true));

        assertEquals(new Unreached(List.of("todo-model"), List.of()), IdeOutputReach.unreached(reactor));
    }

    /** No pom in common, and one reads the other: the participant leaves the one that is read. */
    @Test
    void aStandaloneModuleAnotherOneOfTheBuildDependsOnIsNamed() {
        MavenProject application = application("todo", true);
        MavenProject acceptance = application("todo-acceptance", true);
        acceptance.getModel().addDependency(dependency("todo"));

        assertEquals(new Unreached(List.of("todo"), List.of()),
                IdeOutputReach.unreached(List.of(application, acceptance)));
    }

    /** What Maven loads for one module runs on every project of the session. */
    @Test
    void aStandaloneModuleWithoutTheAttributeIsMovedByTheParticipantAnotherModuleLoads() {
        assertEquals(NONE,
                IdeOutputReach.unreached(List.of(application("todo", true), application("billing", false))));
    }

    /** An IDE compiles nothing of an aggregator. */
    @Test
    void anAggregatorIsNeverNamed() {
        assertEquals(NONE, IdeOutputReach.unreached(List.of(project("todo-parent", "pom"))));
    }

    /** Its pom chose Maven's folders. */
    @Test
    void aModuleThatTakesTheWayOutIsNotNamed() {
        MavenProject application = application("todo", true);
        application.getProperties().setProperty("vidocq.ide.output.skip", "true");
        MavenProject library = project("todo-model", "jar");
        library.getProperties().setProperty("vidocq.ide.output.skip", "true");

        assertEquals(NONE, IdeOutputReach.unreached(List.of(application, library)));
    }

    @Test
    void aProfileOnM2eVersionInAParentReaches() {
        MavenProject grandParent = project("company-parent", "pom");
        grandParent.getModel().addProfile(ideOutput("m2e.version"));
        MavenProject parent = project("todo-parent", "pom");
        parent.setParent(grandParent);
        MavenProject library = module(parent, project("todo-model", "jar"));
        MavenProject application = module(parent, application("todo", true));

        assertEquals(NONE, IdeOutputReach.unreached(List.of(parent, library, application)));
    }

    @Test
    void aProfileOfTheModuleItselfReaches() {
        MavenProject library = project("todo-model", "jar");
        library.getModel().addProfile(ideOutput("m2e.version"));

        assertEquals(NONE, IdeOutputReach.unreached(List.of(library)));
    }

    /**
     * An old idiom of company parent poms: a profile on {@code m2e.version} that only holds the lifecycle mapping of
     * m2e. It moves nothing.
     */
    @Test
    void aProfileOnM2eVersionThatSetsNoBuildDirectoryDoesNot() {
        MavenProject parent = project("company-parent", "pom");
        Profile lifecycleMapping = profileOn("m2e.version");
        lifecycleMapping.setId("m2e");
        lifecycleMapping.setBuild(new BuildBase());
        lifecycleMapping.getBuild().setPluginManagement(new PluginManagement());
        Plugin mapping = new Plugin();
        mapping.setGroupId("org.eclipse.m2e");
        mapping.setArtifactId("lifecycle-mapping");
        lifecycleMapping.getBuild().getPluginManagement().addPlugin(mapping);
        Profile properties = profileOn("m2e.version");
        properties.setId("m2e-properties");
        properties.addProperty("m2e.apt.activation", "jdt_apt");
        parent.getModel().addProfile(lifecycleMapping);
        parent.getModel().addProfile(properties);
        MavenProject library = module(parent, project("todo-model", "jar"));

        assertEquals(new Unreached(List.of("todo-model"), List.of()),
                IdeOutputReach.unreached(List.of(parent, library)));
    }

    @Test
    void aProfileOnAnotherPropertyDoesNot() {
        MavenProject parent = project("todo-parent", "pom");
        parent.getModel().addProfile(ideOutput("idea.version"));
        MavenProject library = module(parent, project("todo-model", "jar"));

        assertEquals(new Unreached(List.of("todo-model"), List.of()),
                IdeOutputReach.unreached(List.of(parent, library)));
    }

    /** A profile with no activation, or one activated by something other than a property, moves nothing here. */
    @Test
    void aProfileThatNoPropertyActivatesDoesNot() {
        MavenProject library = project("todo-model", "jar");
        Profile none = new Profile();
        none.setId("release");
        none.setBuild(directory());
        Profile byDefault = new Profile();
        byDefault.setId("fast");
        byDefault.setActivation(new Activation());
        byDefault.getActivation().setActiveByDefault(true);
        byDefault.setBuild(directory());
        library.getModel().addProfile(none);
        library.getModel().addProfile(byDefault);

        assertEquals(new Unreached(List.of("todo-model"), List.of()), IdeOutputReach.unreached(List.of(library)));
    }

    @Test
    void theModulesComeInTheOrderOfTheReactor() {
        List<MavenProject> reactor = List.of(project("zeta", "jar"), application("todo", true),
                project("alpha", "jar"));

        assertEquals(new Unreached(List.of("zeta", "alpha"), List.of()), IdeOutputReach.unreached(reactor));
    }

    @Test
    void theModulesThatLackTheAttributeToo() {
        List<MavenProject> reactor = List.of(application("zeta", false), project("todo-model", "jar"),
                application("alpha", false));

        assertEquals(new Unreached(List.of("todo-model"), List.of("zeta", "alpha")),
                IdeOutputReach.unreached(reactor));
    }

    /** A project with the plugin in its build, as a build extension or not, and no parent pom on disk. */
    private MavenProject application(String artifactId, boolean extension) {
        MavenProject project = project(artifactId, "jar");
        Plugin plugin = IdeaApplicationsTest.vidocqPlugin();
        plugin.setExtensions(extension);
        project.getBuild().addPlugin(plugin);
        return project;
    }

    /** A project read from {@code dir/<artifactId>/pom.xml}, with no parent pom on disk. */
    private MavenProject project(String artifactId, String packaging) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId(artifactId);
        model.setPackaging(packaging);
        model.setBuild(new Build());
        MavenProject project = new MavenProject(model);
        project.setFile(dir.resolve(artifactId).resolve("pom.xml").toFile());
        return project;
    }

    /** {@code module}, which inherits the pom of {@code parent} from the disk. */
    private static MavenProject module(MavenProject parent, MavenProject module) {
        module.setParent(parent);
        module.setParentFile(parent.getFile());
        return module;
    }

    private static Dependency dependency(String artifactId) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("com.example");
        dependency.setArtifactId(artifactId);
        dependency.setVersion("1.0");
        return dependency;
    }

    /** The profile of the documentation, activated by {@code property}. */
    private static Profile ideOutput(String property) {
        Profile profile = profileOn(property);
        profile.setBuild(directory());
        return profile;
    }

    private static BuildBase directory() {
        BuildBase build = new BuildBase();
        build.setDirectory("${project.basedir}/target-ide");
        return build;
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
