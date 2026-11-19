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

import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The application main class a module declares, shared by the goals that launch it.
 *
 * <p>{@code vidocq:package} receives the value Maven injects into its {@code mainClass} parameter and
 * only needs {@link #normalize(String)}. {@code vidocq:idea} is an aggregator: it reads every module of
 * the reactor from its model, through {@link #declared(MavenProject)}, which follows the order Maven
 * applies to a command-line invocation of a Vidocq goal:
 * <ol>
 *   <li>the plugin-level {@code <mainClass>} of {@value #PLUGIN_KEY} in the module's build plugins;</li>
 *   <li>the module's {@value #PROPERTY} property, which the parameter expression reads.</li>
 * </ol>
 * An execution-level {@code <mainClass>} is not a source: {@code vidocq:package} bound to the
 * lifecycle honours it, but a goal run on its own (such as the IntelliJ before-launch step) does not
 * see it.
 */
public final class ApplicationMainClass {

    /** {@code groupId:artifactId} of this plugin, as Maven keys build plugins. */
    public static final String PLUGIN_KEY = "io.vidocq.runtime:vidocq-runtime-maven-plugin";

    /** The property the {@code mainClass} parameters of the Vidocq goals read. */
    public static final String PROPERTY = "vidocq.mainClass";

    /** The property the {@code mainModule} parameters of the Vidocq goals read. */
    public static final String MODULE_PROPERTY = "vidocq.mainModule";

    /** The runtime's own main class, the default of {@code vidocq:package}: not an application main class. */
    public static final String RUNTIME_MAIN_CLASS = "io.vidocq.runtime.core.Vidocq";

    private ApplicationMainClass() {
    }

    /**
     * The application main class designated by a configured value: {@code null} when the value is blank
     * or the runtime's own main class, the class part of a legacy {@code module/class} reference,
     * otherwise the value itself.
     */
    public static String normalize(String mainClass) {
        if (mainClass == null || mainClass.isBlank() || RUNTIME_MAIN_CLASS.equals(mainClass)) {
            return null;
        }
        int slash = mainClass.indexOf('/');
        return slash >= 0 ? mainClass.substring(slash + 1) : mainClass;
    }

    /**
     * The main class the module declares, not normalised: the plugin-level {@code <mainClass>} when it
     * is not blank, else the {@value #PROPERTY} property when it is not blank, else {@code null}.
     */
    public static String declared(MavenProject project) {
        return declaredValue(project, "mainClass", PROPERTY);
    }

    /** The main module the module declares, with the same source order as {@link #declared(MavenProject)}. */
    public static String declaredMainModule(MavenProject project) {
        return declaredValue(project, "mainModule", MODULE_PROPERTY);
    }

    private static String declaredValue(MavenProject project, String parameter, String property) {
        Plugin plugin = project.getPlugin(PLUGIN_KEY);
        if (plugin != null && plugin.getConfiguration() instanceof Xpp3Dom configuration) {
            Xpp3Dom child = configuration.getChild(parameter);
            if (child != null && child.getValue() != null && !child.getValue().isBlank()) {
                return child.getValue();
            }
        }
        String value = project.getProperties().getProperty(property);
        return value == null || value.isBlank() ? null : value;
    }
}
