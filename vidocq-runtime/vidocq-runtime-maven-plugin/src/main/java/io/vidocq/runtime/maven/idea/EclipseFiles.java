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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launch configurations {@code vidocq:eclipse} writes into {@code .eclipse/} (Vidocq/vidocq#339). Eclipse lists
 * every {@code .launch} file of a project of its workspace under Run Configurations.
 *
 * <ul>
 *   <li>{@code <name> (dev)}, {@code (run)} and {@code (test)} are Maven builds (m2e) of {@code vidocq:dev},
 *   {@code vidocq:run} and {@code vidocq:test} in the Eclipse project of the application, on a Java 25 runtime:
 *   Eclipse 2026-09 ships one. m2e names a project after its artifactId, and so do these files.</li>
 *   <li>{@code <name> (debug)} attaches Eclipse's debugger to the agent {@code vidocq:dev} starts.</li>
 * </ul>
 *
 * <p>Each file starts with {@link #MARKER}: {@code vidocq:eclipse} rewrites or removes a file that does, and leaves
 * any other alone. Eclipse rewrites a configuration edited in its dialog without the marker, which then becomes the
 * user's.</p>
 */
final class EclipseFiles {

    /** The first line of every file {@code vidocq:eclipse} writes, and of those it may rewrite. */
    static final String MARKER = "<!-- Written by vidocq:eclipse (mvn vidocq:eclipse). It rewrites a file that starts"
            + " with this line; delete the line to keep your own. -->\n";

    private static final String JAVA_25 = "org.eclipse.jdt.launching.JRE_CONTAINER/"
            + "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25";

    private EclipseFiles() {
    }

    /** The launch configurations of {@code applications}, by file name, in a stable order. */
    static Map<String, String> launches(List<IdeaApplication> applications) {
        Map<String, String> files = new LinkedHashMap<>();
        for (IdeaApplication application : applications) {
            String name = application.configurationName();
            String project = project(application);
            files.put(name + " (dev).launch",
                    maven(project, "process-classes " + RunConfigurationRenderer.DEV_GOAL));
            files.put(name + " (run).launch", maven(project, RunConfigurationRenderer.RUN_GOAL));
            files.put(name + " (test).launch", maven(project, "vidocq:test"));
            files.put(name + " (debug).launch", attach(project, application));
        }
        return files;
    }

    /** Whether {@code content} is a file {@code vidocq:eclipse} wrote, so that it may rewrite or remove it. */
    static boolean ours(String content) {
        return content.startsWith(MARKER);
    }

    private static String maven(String project, String goals) {
        return MARKER + "<launchConfiguration type=\"org.eclipse.m2e.Maven2LaunchConfigurationType\">\n"
                + "    <stringAttribute key=\"M2_GOALS\" value=\"" + xml(goals) + "\"/>\n"
                + "    <stringAttribute key=\"M2_RUNTIME\" value=\"EMBEDDED\"/>\n"
                + "    <booleanAttribute key=\"M2_WORKSPACE_RESOLUTION\" value=\"false\"/>\n"
                + "    <stringAttribute key=\"org.eclipse.jdt.launching.JRE_CONTAINER\" value=\"" + JAVA_25 + "\"/>\n"
                + "    <stringAttribute key=\"org.eclipse.jdt.launching.WORKING_DIRECTORY\" value=\"${workspace_loc:/"
                + xml(project) + "}\"/>\n"
                + "</launchConfiguration>\n";
    }

    private static String attach(String project, IdeaApplication application) {
        return MARKER + "<launchConfiguration type=\"org.eclipse.jdt.launching.remoteJavaApplication\">\n"
                + "    <booleanAttribute key=\"org.eclipse.jdt.launching.ALLOW_TERMINATE\" value=\"false\"/>\n"
                + "    <mapAttribute key=\"org.eclipse.jdt.launching.CONNECT_MAP\">\n"
                + "        <mapEntry key=\"hostname\" value=\"" + xml(application.debugHost()) + "\"/>\n"
                + "        <mapEntry key=\"port\" value=\"" + application.debugPort() + "\"/>\n"
                + "    </mapAttribute>\n"
                + "    <stringAttribute key=\"org.eclipse.jdt.launching.PROJECT_ATTR\" value=\"" + xml(project) + "\"/>\n"
                + "    <stringAttribute key=\"org.eclipse.jdt.launching.VM_CONNECTOR_ID\""
                + " value=\"org.eclipse.jdt.launching.socketAttachConnector\"/>\n"
                + "</launchConfiguration>\n";
    }

    /** The Eclipse project m2e imports the application as: its artifactId, m2e's default name template. */
    private static String project(IdeaApplication application) {
        String coordinates = application.coordinates();
        return coordinates.substring(coordinates.indexOf(':') + 1);
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
