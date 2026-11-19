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

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the {@code .vscode/} files of a Vidocq project for VS Code's Java extension (Vidocq/vidocq#339): the
 * language server on the build's JDK with no automatic build, {@code vidocq dev}, {@code run} and {@code test} tasks
 * per application, and a configuration that attaches the debugger to {@code vidocq:dev}. See {@link VscodeFiles}.
 *
 * <p>Command line only, at the directory VS Code opens. A file that does not start with {@link VscodeFiles#MARKER}
 * is left alone, with what to add to it. {@code -Dvidocq.vscode.check=true} writes nothing and fails when a file is
 * missing or out of date.</p>
 */
@Mojo(name = "vscode",
      aggregator = true,
      threadSafe = true,
      requiresDependencyResolution = ResolutionScope.NONE)
public class VidocqVscodeMojo extends IdeFilesMojo {

    /** The JDK the language server runs on; by default the one running this build, which builds with Java 25. */
    @Parameter(property = "vidocq.vscode.jdk")
    private String jdk;

    @Override
    String goal() {
        return "vscode";
    }

    @Override
    String ide() {
        return "VS Code";
    }

    @Override
    String folder() {
        return ".vscode";
    }

    @Override
    String marker() {
        return VscodeFiles.MARKER;
    }

    @Override
    Map<String, String> files(List<IdeaApplication> applications, Path directory) {
        String jdkHome = jdk != null && !jdk.isBlank() ? jdk.strip() : System.getProperty("java.home");
        if ((jdk == null || jdk.isBlank()) && Runtime.version().feature() < 25) {
            getLog().warn(prefix() + "this build runs on Java " + Runtime.version().feature() + ": the language"
                    + " server needs Java 25 to run Vauban's processors. Run on Java 25, or give"
                    + " -Dvidocq.vscode.jdk=<JDK 25>.");
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put("settings.json", VscodeFiles.settings(jdkHome));
        files.put("tasks.json", VscodeFiles.tasks(applications, Files.isRegularFile(directory.resolve("mvnw"))));
        files.put("launch.json", VscodeFiles.launch(applications));
        return files;
    }

    void setJdk(String jdk) {
        this.jdk = jdk;
    }
}
