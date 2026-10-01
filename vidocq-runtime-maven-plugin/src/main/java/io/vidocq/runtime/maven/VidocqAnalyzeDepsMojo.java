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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.util.List;

/**
 * Prints which dependencies {@code vidocq:generate} scans for CDI beans, what selects or excludes each, and what it
 * does with them, then the {@code <scanDependencies>} block that makes the bean-archive detection explicit. Reads the
 * same {@code scanDependencies}, {@code scanExcludes} and {@code autoScan} as {@code generate}: configure them at
 * plugin level so both goals see them. Changes nothing.
 */
@Mojo(name = "analyze-deps", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, threadSafe = true)
public class VidocqAnalyzeDepsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter
    private List<String> scanDependencies;

    @Parameter
    private List<String> scanExcludes;

    @Parameter(property = "vidocq.generate.autoScan", defaultValue = "true")
    private boolean autoScan;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            var decisions = ScanSelection.decide(ScanSelection.dependenciesOf(project.getArtifacts()),
                    scanDependencies == null ? List.of() : scanDependencies,
                    scanExcludes == null ? List.of() : scanExcludes, autoScan);
            AnalyzeReport.render(decisions).lines().forEach(getLog()::info);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read the dependency jars", e);
        }
    }
}
