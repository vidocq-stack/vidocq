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
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the Eclipse launch configurations of a Vidocq project into {@code .eclipse/} (Vidocq/vidocq#339): Maven
 * builds of {@code vidocq:dev}, {@code run} and {@code test} per application, and a Remote Java Application that
 * attaches the debugger to {@code vidocq:dev}. See {@link EclipseFiles}.
 *
 * <p>Command line only, at the reactor root. A file that does not start with {@link EclipseFiles#MARKER} is left
 * alone; one that does and that the goal no longer writes is removed. {@code -Dvidocq.eclipse.check=true} writes
 * nothing and fails when a file is missing or out of date.</p>
 *
 * <p>Eclipse keeps "Build automatically" per workspace, not per project, so the goal cannot turn it off as
 * {@code vidocq:vscode} does for VS Code: it says so.</p>
 */
@Mojo(name = "eclipse",
      aggregator = true,
      threadSafe = true,
      requiresDependencyResolution = ResolutionScope.NONE)
public class VidocqEclipseMojo extends IdeFilesMojo {

    @Override
    String goal() {
        return "eclipse";
    }

    @Override
    String ide() {
        return "Eclipse";
    }

    @Override
    String folder() {
        return ".eclipse";
    }

    @Override
    String marker() {
        return EclipseFiles.MARKER;
    }

    @Override
    Map<String, String> files(List<IdeaApplication> applications, Path directory) {
        return EclipseFiles.launches(applications);
    }

    @Override
    void after() {
        getLog().info(prefix() + "the configurations are under Run > Run Configurations once the project is imported"
                + " (File > Import > Existing Maven Projects). Turn Project > Build Automatically off while vidocq:dev"
                + " runs: both would write target/classes on every save. Eclipse keeps that setting per workspace.");
    }
}
