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

import io.vidocq.runtime.tooling.ProjectScaffolder;
import io.vidocq.runtime.tooling.ProjectSpec;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Scaffolds a new Vidocq project, as {@code vidocq create} does: both run {@link ProjectScaffolder}, so the pom,
 * {@code module-info.java}, {@code vidocq.properties} and classes are the same (Vidocq/vidocq#314). It needs no
 * project; outside one, the plugin is named in full:
 *
 * <pre>mvn io.vidocq.runtime:vidocq-runtime-maven-plugin:&lt;version&gt;:create -Dname=my-api -DgroupId=com.acme
 *     -Dextensions=cassini-rest,knock-health</pre>
 */
@Mojo(name = "create",
      requiresProject = false,
      threadSafe = true)
public class VidocqCreateMojo extends AbstractMojo {

    /** Where the project directory is created: the current directory. */
    @Parameter(defaultValue = "${basedir}", readonly = true, required = true)
    private File basedir;

    /** The project directory and artifactId. */
    @Parameter(property = "name")
    private String name;

    /** The Maven groupId; {@value ProjectSpec#DEFAULT_GROUP_ID} by default. */
    @Parameter(property = "groupId")
    private String groupId;

    /** The root Java package, also the module name; {@code <groupId>.<name>} by default. */
    @Parameter(property = "package")
    private String pkg;

    /** The extensions to enable, comma-separated catalog ids. */
    @Parameter(property = "extensions")
    private String extensions;

    /** The vidocq-runtime-parent version; this plugin's own by default. */
    @Parameter(property = "parentVersion")
    private String parentVersion;

    /** Also write an {@code @VidocqMain} trampoline, for an IDE Run or a {@code VidocqApp}. */
    @Parameter(property = "main", defaultValue = "false")
    private boolean main;

    /** Declare an {@code open module}: every package open to reflection, so no {@code opens} line is ever needed. */
    @Parameter(property = "openModule", defaultValue = "false")
    private boolean openModule;

    @Parameter(defaultValue = "${plugin.version}", readonly = true, required = true)
    private String runtimeVersion;

    @Override
    public void execute() throws MojoFailureException {
        if (name == null || name.isBlank()) {
            throw new MojoFailureException("Name the project: mvn io.vidocq.runtime:vidocq-runtime-maven-plugin:"
                    + runtimeVersion + ":create -Dname=<name>");
        }
        List<String> ids = extensions == null ? List.of() : Arrays.stream(extensions.split(","))
                .map(String::strip).filter(id -> !id.isEmpty()).toList();
        ProjectSpec spec;
        try {
            spec = ProjectSpec.of(name.strip(), groupId, pkg, ids, parentVersion, main, openModule,
                    "-Dpackage=<pkg>");
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
        getLog().info("Scaffolding project '" + spec.name() + "'…");
        try {
            ProjectScaffolder.scaffold(spec, basedir.toPath(), runtimeVersion, new LogReporter(getLog()));
        } catch (IOException | IllegalStateException e) {
            throw new MojoFailureException("Failed to create project: " + e.getMessage(), e);
        }
        getLog().info("Project '" + spec.name() + "' created: cd " + spec.name() + " && ./mvnw vidocq:dev");
        if (!spec.main()) {
            getLog().info("  No main class: the runtime boots the app. Add -Dmain for an @VidocqMain trampoline.");
        }
    }
}
