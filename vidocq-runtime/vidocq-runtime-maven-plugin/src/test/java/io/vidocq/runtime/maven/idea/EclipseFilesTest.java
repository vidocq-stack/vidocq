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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code .eclipse/} launch configurations {@code vidocq:eclipse} writes (Vidocq/vidocq#339). */
class EclipseFilesTest {

    private static final IdeaApplication TODO = new IdeaApplication("com.example:todo", null, "todo", "todo",
            "pom.xml", "vidocq:generate", null, 0);
    private static final IdeaApplication SHOP = new IdeaApplication("com.example:shop-app", null, "shop", "shop",
            "apps/shop/pom.xml", "vidocq:generate", "127.0.0.1", 5006);

    @Test
    void eachApplicationGetsDevRunTestAndDebugConfigurations() {
        Map<String, String> files = EclipseFiles.launches(List.of(TODO, SHOP));

        assertEquals(List.of("todo (dev).launch", "todo (run).launch", "todo (test).launch", "todo (debug).launch",
                "shop (dev).launch", "shop (run).launch", "shop (test).launch", "shop (debug).launch"),
                List.copyOf(files.keySet()));
    }

    @Test
    void theDevConfigurationIsAMavenBuildOfTheApplicationOnJava25() {
        assertEquals(EclipseFiles.MARKER + """
                <launchConfiguration type="org.eclipse.m2e.Maven2LaunchConfigurationType">
                    <stringAttribute key="M2_GOALS" value="process-classes vidocq:dev"/>
                    <stringAttribute key="M2_RUNTIME" value="EMBEDDED"/>
                    <booleanAttribute key="M2_WORKSPACE_RESOLUTION" value="false"/>
                    <stringAttribute key="org.eclipse.jdt.launching.JRE_CONTAINER" \
                value="org.eclipse.jdt.launching.JRE_CONTAINER/org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType/JavaSE-25"/>
                    <stringAttribute key="org.eclipse.jdt.launching.WORKING_DIRECTORY" value="${workspace_loc:/todo}"/>
                </launchConfiguration>
                """, EclipseFiles.launches(List.of(TODO)).get("todo (dev).launch"));
    }

    @Test
    void runAndTestRunTheirGoalInTheEclipseProjectOfTheApplication() {
        Map<String, String> files = EclipseFiles.launches(List.of(SHOP));

        assertTrue(files.get("shop (run).launch").contains("key=\"M2_GOALS\" value=\"vidocq:run\""));
        assertTrue(files.get("shop (test).launch").contains("key=\"M2_GOALS\" value=\"vidocq:test\""));
        assertTrue(files.get("shop (test).launch").contains("value=\"${workspace_loc:/shop-app}\""),
                "m2e names the Eclipse project after the artifactId");
    }

    @Test
    void theDebugConfigurationAttachesToTheAgentOfVidocqDev() {
        assertEquals(EclipseFiles.MARKER + """
                <launchConfiguration type="org.eclipse.jdt.launching.remoteJavaApplication">
                    <booleanAttribute key="org.eclipse.jdt.launching.ALLOW_TERMINATE" value="false"/>
                    <mapAttribute key="org.eclipse.jdt.launching.CONNECT_MAP">
                        <mapEntry key="hostname" value="127.0.0.1"/>
                        <mapEntry key="port" value="5006"/>
                    </mapAttribute>
                    <stringAttribute key="org.eclipse.jdt.launching.PROJECT_ATTR" value="shop-app"/>
                    <stringAttribute key="org.eclipse.jdt.launching.VM_CONNECTOR_ID" \
                value="org.eclipse.jdt.launching.socketAttachConnector"/>
                </launchConfiguration>
                """, EclipseFiles.launches(List.of(SHOP)).get("shop (debug).launch"));
    }

    @Test
    void valuesAreEscapedForXml() {
        IdeaApplication odd = new IdeaApplication("com.example:a&b", null, "a&b", "a&b", "pom.xml",
                "vidocq:generate", "\"host\"", 0);
        Map<String, String> files = EclipseFiles.launches(List.of(odd));

        assertTrue(files.get("a&b (dev).launch").contains("value=\"${workspace_loc:/a&amp;b}\""));
        assertTrue(files.get("a&b (debug).launch").contains("value=\"&quot;host&quot;\""));
    }

    @Test
    void onlyAFileThatStartsWithTheMarkerIsOurs() {
        assertTrue(EclipseFiles.ours(EclipseFiles.launches(List.of(TODO)).get("todo (dev).launch")));
        assertFalse(EclipseFiles.ours("<?xml version=\"1.0\"?>\n<launchConfiguration/>\n"));
    }
}
