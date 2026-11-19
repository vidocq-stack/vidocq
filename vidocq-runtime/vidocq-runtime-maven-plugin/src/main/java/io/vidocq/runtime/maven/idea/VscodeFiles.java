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

import java.util.List;

/**
 * The {@code .vscode/} files {@code vidocq:vscode} writes for VS Code's Java extension (Vidocq/vidocq#339):
 * {@code settings.json}, {@code tasks.json} and {@code launch.json}.
 *
 * <ul>
 *   <li>The settings run the extension's language server on the build's JDK: it runs the annotation processors in its
 *   own JVM, and Vauban's are Java 25 class files, which the Java 21 the extension ships cannot load. They turn the
 *   extension's automatic build off: under {@code vidocq dev}, both would write {@code target/classes} on every save;
 *   the editor keeps its diagnostics and completion, and {@code vidocq dev} compiles.</li>
 *   <li>The tasks run {@code vidocq:dev}, {@code vidocq:run} and {@code vidocq:test} of each application, through
 *   the Maven wrapper when the project has one.</li>
 *   <li>The launch configurations attach VS Code's debugger to the agent {@code vidocq:dev} starts.</li>
 * </ul>
 *
 * <p>Each file starts with {@link #MARKER}: {@code vidocq:vscode} rewrites a file that does, and leaves any other
 * alone. VS Code reads these files as JSON with comments.</p>
 */
final class VscodeFiles {

    /** The first line of every file {@code vidocq:vscode} writes, and of those it may rewrite. */
    static final String MARKER = "// Written by vidocq:vscode (mvn vidocq:vscode). It rewrites a file that starts with"
            + " this line; delete the line to keep your own.\n";

    private VscodeFiles() {
    }

    /** {@code settings.json}: the language server on {@code jdkHome}, no automatic build. */
    static String settings(String jdkHome) {
        return MARKER + "{\n"
                + "  \"java.jdt.ls.java.home\": " + json(jdkHome) + ",\n"
                + "  \"java.autobuild.enabled\": false,\n"
                + "  \"java.configuration.updateBuildConfiguration\": \"automatic\"\n"
                + "}\n";
    }

    /** {@code tasks.json}: {@code vidocq dev}, {@code run} and {@code test} of each application. */
    static String tasks(List<IdeaApplication> applications, boolean wrapper) {
        StringBuilder json = new StringBuilder(MARKER).append("{\n  \"version\": \"2.0.0\",\n  \"tasks\": [\n");
        String separator = "";
        for (IdeaApplication application : applications) {
            String suffix = applications.size() == 1 ? "" : ": " + application.configurationName();
            for (String[] task : List.of(
                    new String[] {"dev", "process-classes", RunConfigurationRenderer.DEV_GOAL},
                    new String[] {"run", RunConfigurationRenderer.RUN_GOAL},
                    new String[] {"test", "vidocq:test"})) {
                json.append(separator).append("    {\n")
                        .append("      \"label\": ").append(json("vidocq " + task[0] + suffix)).append(",\n")
                        .append("      \"type\": \"shell\",\n")
                        .append("      \"command\": ").append(json(wrapper ? "./mvnw" : "mvn")).append(",\n")
                        .append("      \"windows\": { \"command\": ").append(json(wrapper ? ".\\mvnw.cmd" : "mvn"))
                        .append(" },\n")
                        .append("      \"args\": [").append(json("-f")).append(", ").append(json(application.pomPath()));
                for (int i = 1; i < task.length; i++) {
                    json.append(", ").append(json(task[i]));
                }
                json.append("],\n")
                        .append("      \"isBackground\": true,\n")
                        .append("      \"problemMatcher\": []\n")
                        .append("    }");
                separator = ",\n";
            }
        }
        return json.append("\n  ]\n}\n").toString();
    }

    /** {@code launch.json}: attach to the debug agent of each application's {@code vidocq:dev}. */
    static String launch(List<IdeaApplication> applications) {
        StringBuilder json = new StringBuilder(MARKER).append("{\n  \"version\": \"0.2.0\",\n  \"configurations\": [\n");
        String separator = "";
        for (IdeaApplication application : applications) {
            json.append(separator).append("    {\n")
                    .append("      \"type\": \"java\",\n")
                    .append("      \"name\": ").append(json("Vidocq: attach to " + application.configurationName()
                            + " (dev)")).append(",\n")
                    .append("      \"request\": \"attach\",\n")
                    .append("      \"hostName\": ").append(json(application.debugHost())).append(",\n")
                    .append("      \"port\": ").append(application.debugPort()).append('\n')
                    .append("    }");
            separator = ",\n";
        }
        return json.append("\n  ]\n}\n").toString();
    }

    /** Whether {@code content} is a file {@code vidocq:vscode} wrote, so that it may rewrite it. */
    static boolean ours(String content) {
        return content.startsWith(MARKER);
    }

    /** {@code value} as a JSON string. */
    static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
