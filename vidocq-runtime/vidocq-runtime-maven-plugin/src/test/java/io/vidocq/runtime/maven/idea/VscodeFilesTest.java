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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code .vscode/} files {@code vidocq:vscode} writes (Vidocq/vidocq#339). */
class VscodeFilesTest {

    private static final IdeaApplication TODO = new IdeaApplication("com.example:todo", null, "todo", "todo",
            "pom.xml", "vidocq:generate", null, 0);
    private static final IdeaApplication SHOP = new IdeaApplication("com.example:shop", null, "shop", "shop",
            "apps/shop/pom.xml", "vidocq:generate", "127.0.0.1", 5006);

    @Test
    void theSettingsRunTheLanguageServerOnTheJdkAndLeaveTheBuildToVidocqDev() {
        assertEquals(VscodeFiles.MARKER + """
                {
                  "java.jdt.ls.java.home": "/opt/jdk-25",
                  "java.autobuild.enabled": false,
                  "java.configuration.updateBuildConfiguration": "automatic"
                }
                """, VscodeFiles.settings("/opt/jdk-25"));
    }

    @Test
    void aWindowsJdkPathIsAValidJsonString() {
        assertTrue(VscodeFiles.settings("C:\\Program Files\\Java\\jdk-25")
                .contains("\"java.jdt.ls.java.home\": \"C:\\\\Program Files\\\\Java\\\\jdk-25\""));
    }

    @Test
    void controlCharactersAreEscapedForJson() {
        assertEquals("\"a\\nb\\rc\\td\\u0001\"", VscodeFiles.json("a\nb\rc\td\u0001"));
    }

    @Test
    void oneApplicationGetsPlainTaskNamesThroughTheWrapper() {
        assertEquals(VscodeFiles.MARKER + """
                {
                  "version": "2.0.0",
                  "tasks": [
                    {
                      "label": "vidocq dev",
                      "type": "shell",
                      "command": "./mvnw",
                      "windows": { "command": ".\\\\mvnw.cmd" },
                      "args": ["-f", "pom.xml", "process-classes", "vidocq:dev"],
                      "isBackground": true,
                      "problemMatcher": []
                    },
                    {
                      "label": "vidocq run",
                      "type": "shell",
                      "command": "./mvnw",
                      "windows": { "command": ".\\\\mvnw.cmd" },
                      "args": ["-f", "pom.xml", "vidocq:run"],
                      "isBackground": true,
                      "problemMatcher": []
                    },
                    {
                      "label": "vidocq test",
                      "type": "shell",
                      "command": "./mvnw",
                      "windows": { "command": ".\\\\mvnw.cmd" },
                      "args": ["-f", "pom.xml", "vidocq:test"],
                      "isBackground": true,
                      "problemMatcher": []
                    }
                  ]
                }
                """, VscodeFiles.tasks(List.of(TODO), true));
    }

    @Test
    void severalApplicationsAreNamedAndAProjectWithoutWrapperRunsMaven() {
        String tasks = VscodeFiles.tasks(List.of(TODO, SHOP), false);

        assertTrue(tasks.contains("\"label\": \"vidocq dev: shop\""), tasks);
        assertTrue(tasks.contains("\"args\": [\"-f\", \"apps/shop/pom.xml\", \"process-classes\", \"vidocq:dev\"]"), tasks);
        assertTrue(tasks.contains("\"command\": \"mvn\""), tasks);
        assertFalse(tasks.contains("mvnw"), tasks);
    }

    @Test
    void theLaunchConfigurationsAttachToTheDebugAgentOfVidocqDev() {
        assertEquals(VscodeFiles.MARKER + """
                {
                  "version": "0.2.0",
                  "configurations": [
                    {
                      "type": "java",
                      "name": "Vidocq: attach to todo (dev)",
                      "request": "attach",
                      "hostName": "127.0.0.1",
                      "port": 5005
                    },
                    {
                      "type": "java",
                      "name": "Vidocq: attach to shop (dev)",
                      "request": "attach",
                      "hostName": "127.0.0.1",
                      "port": 5006
                    }
                  ]
                }
                """, VscodeFiles.launch(List.of(TODO, SHOP)));
    }

    @Test
    void onlyAFileThatStartsWithTheMarkerIsOurs() {
        assertTrue(VscodeFiles.ours(VscodeFiles.settings("/opt/jdk-25")));
        assertTrue(VscodeFiles.ours(VscodeFiles.MARKER + "{}\n"));
        assertFalse(VscodeFiles.ours("{\n  \"editor.tabSize\": 2\n}\n"));
        assertFalse(VscodeFiles.ours("// my settings\n" + VscodeFiles.MARKER + "{}\n"));
    }
}
