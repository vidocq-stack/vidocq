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

import io.vidocq.runtime.tooling.KnownExtensions;
import io.vidocq.runtime.tooling.RegistryEntry;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.util.List;

/**
 * Lists the Vidocq extensions the project runs with, transitive ones marked, as {@code vidocq extension list}
 * does; {@code -Dvidocq.extensions.available} adds the catalog (Vidocq/vidocq#314):
 *
 * <pre>mvn vidocq:list-extensions [-Dvidocq.extensions.available]</pre>
 *
 * <p>Has no default phase.</p>
 */
@Mojo(name = "list-extensions",
      requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class VidocqListExtensionsMojo extends AbstractMojo {

    private static final String ADD_HINT = "mvn vidocq:add-extension -Dextensions=<id>";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Also list the extensions of the catalog. */
    @Parameter(property = "vidocq.extensions.available", defaultValue = "false")
    private boolean available;

    @Override
    public void execute() {
        getLog().info("Installed extensions:");
        List<ProjectExtensionArtifacts.Installed> installed = ProjectExtensionArtifacts.of(project);
        if (installed.isEmpty()) {
            getLog().info("  (none — add one with " + ADD_HINT + ")");
        }
        for (ProjectExtensionArtifacts.Installed extension : installed) {
            getLog().info(String.format("  ✔ %-26s %s%s", extension.id(), extension.artifact(),
                    extension.direct() ? "" : "  (transitive)"));
        }
        if (!available) {
            return;
        }
        getLog().info("Available extensions:");
        for (RegistryEntry entry : KnownExtensions.catalog()) {
            getLog().info(String.format("  • %-22s %s", entry.id(), entry.description()));
        }
        getLog().info("  source: built-in catalog — add with " + ADD_HINT);
    }
}
