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
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the single rule that says which application main class a module declares.
 *
 * <p>{@code vidocq:package} and {@code vidocq:idea} must agree on it: the launch scripts of a
 * distribution and the IntelliJ run configuration of the same module start the same class. The
 * normalisation (the runtime's own main class and a blank value mean "no application main class", a
 * legacy {@code module/Class} value keeps its class) used to be private to {@code vidocq:package}.
 *
 * <p>The source order mirrors what Maven injects into a command-line invocation of a Vidocq goal: an
 * explicit plugin-level {@code <mainClass>} wins over the {@code vidocq.mainClass} property that the
 * parameter expression reads. An execution-level {@code <mainClass>} is deliberately not a source:
 * a goal run on its own (the IntelliJ before-launch step) does not see it.
 */
class ApplicationMainClassTest {

    @Test
    void normalize_treatsBlankAndTheRuntimeMainAsNoApplicationMainClass() {
        assertNull(ApplicationMainClass.normalize(null));
        assertNull(ApplicationMainClass.normalize(""));
        assertNull(ApplicationMainClass.normalize("   "));
        assertNull(ApplicationMainClass.normalize("io.vidocq.runtime.core.Vidocq"));
    }

    @Test
    void normalize_keepsTheClassOfALegacyModuleReference() {
        assertEquals("a.b.App", ApplicationMainClass.normalize("a.b.App"));
        assertEquals("a.b.App", ApplicationMainClass.normalize("a.b/a.b.App"));
    }

    @Test
    void declared_readsThePropertyWhenThePluginHasNoMainClass() {
        MavenProject project = project(plugin(null));
        project.getProperties().setProperty("vidocq.mainClass", "com.example.PropertyApp");

        assertEquals("com.example.PropertyApp", ApplicationMainClass.declared(project));
    }

    @Test
    void declared_prefersThePluginLevelConfigurationOverTheProperty() {
        MavenProject project = project(plugin("com.example.ConfiguredApp"));
        project.getProperties().setProperty("vidocq.mainClass", "com.example.PropertyApp");

        assertEquals("com.example.ConfiguredApp", ApplicationMainClass.declared(project));
    }

    @Test
    void declared_fallsBackToThePropertyWhenThePluginLevelValueIsBlank() {
        MavenProject project = project(plugin("  "));
        project.getProperties().setProperty("vidocq.mainClass", "com.example.PropertyApp");

        assertEquals("com.example.PropertyApp", ApplicationMainClass.declared(project));
    }

    @Test
    void declared_ignoresAMainClassSetOnlyOnAnExecution() {
        Plugin plugin = plugin(null);
        PluginExecution jlink = new PluginExecution();
        jlink.setId("jlink");
        jlink.addGoal("jlink");
        jlink.setConfiguration(configuration("mainClass", "com.example.JlinkOnlyApp"));
        plugin.addExecution(jlink);

        assertNull(ApplicationMainClass.declared(project(plugin)));
    }

    @Test
    void declared_isNullWithoutThePluginOrAnyValue() {
        assertNull(ApplicationMainClass.declared(new MavenProject(new Model())));
        assertNull(ApplicationMainClass.declared(project(plugin(null))));
    }

    @Test
    void declaredMainModule_followsTheSameSourceOrder() {
        Plugin plugin = plugin(null);
        plugin.setConfiguration(configuration("mainModule", "com.example.configured"));
        MavenProject project = project(plugin);
        project.getProperties().setProperty("vidocq.mainModule", "com.example.property");

        assertEquals("com.example.configured", ApplicationMainClass.declaredMainModule(project));

        MavenProject propertyOnly = project(plugin(null));
        propertyOnly.getProperties().setProperty("vidocq.mainModule", "com.example.property");
        assertEquals("com.example.property", ApplicationMainClass.declaredMainModule(propertyOnly));
    }

    static MavenProject project(Plugin plugin) {
        Model model = new Model();
        model.setGroupId("com.example");
        model.setArtifactId("app");
        model.setVersion("1.0");
        Build build = new Build();
        build.addPlugin(plugin);
        model.setBuild(build);
        return new MavenProject(model);
    }

    static Plugin plugin(String mainClass) {
        Plugin plugin = new Plugin();
        plugin.setGroupId("io.vidocq.runtime");
        plugin.setArtifactId("vidocq-runtime-maven-plugin");
        if (mainClass != null) {
            plugin.setConfiguration(configuration("mainClass", mainClass));
        }
        return plugin;
    }

    static Xpp3Dom configuration(String name, String value) {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        Xpp3Dom child = new Xpp3Dom(name);
        child.setValue(value);
        configuration.addChild(child);
        return configuration;
    }
}
