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

import io.vidocq.runtime.codegen.commons.ModuleInfoRequirements;
import io.vidocq.runtime.codegen.commons.ModuleRequirementsDescriptor;
import io.vidocq.runtime.codegen.commons.Requirement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Verify that the application's {@code module-info.java} declares the JPMS directives its Vidocq
 * extensions need at runtime. Each extension ships a {@code META-INF/vidocq/module-requirements.properties}
 * descriptor (see {@link ModuleRequirementsDescriptor}); this goal unions the descriptors of every
 * resolved dependency and checks them against the compiled {@code module-info.class}.
 *
 * <p>These are the dependency-driven directives — e.g. {@code opens db.migration} (Flyway/Liquibase
 * scan classpath migrations) — that compile fine on the classpath/TCK but break only on the module
 * path (a real {@code docker compose up} / jlink boot). A missing directive fails the build with the
 * exact, copy-pasteable fix. Only {@code opens} is enforced: it is never inherited, whereas a
 * {@code requires} may be satisfied transitively and cannot be checked from declared directives
 * alone.</p>
 *
 * <p>Default phase: {@code process-classes} — after the module-info is compiled, so the check reads
 * the authoritative {@code module-info.class} rather than parsing source. Skip with
 * {@code -Dvidocq.moduleinfo.skip=true}; downgrade failures to warnings with
 * {@code -Dvidocq.moduleinfo.failOnMissing=false}. The opt-in companion goal
 * {@code vidocq:complete-module-info} rewrites the source to add the missing directives.</p>
 */
@Mojo(name = "check-module-info",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true)
public class VidocqCheckModuleInfoMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    /** Skip the check entirely. */
    @Parameter(property = "vidocq.moduleinfo.skip", defaultValue = "false")
    private boolean skip;

    /** If {@code true} (the default), a missing directive fails the build; {@code false} only warns. */
    @Parameter(property = "vidocq.moduleinfo.failOnMissing", defaultValue = "true")
    private boolean failOnMissing;

    @Override
    public void execute() throws MojoFailureException {
        if (skip) {
            getLog().info("Vidocq check-module-info: skipped (-Dvidocq.moduleinfo.skip=true)");
            return;
        }
        if ("pom".equals(project.getPackaging())) {
            return;
        }

        Set<Requirement> required = ModuleRequirementsCollector.applicableTo(
                ModuleRequirementsCollector.collect(project.getArtifacts(), getLog()), outputDirectory);
        if (required.isEmpty()) {
            getLog().debug("Vidocq check-module-info: no applicable extension requirements for this module.");
            return;
        }

        File moduleInfo = new File(outputDirectory, "module-info.class");
        if (!moduleInfo.isFile()) {
            List<String> issues = new ArrayList<>();
            issues.add("This module depends on Vidocq extensions that require JPMS directives, but it has "
                    + "no module-info.java (it is not modular). On the module path the extensions break. "
                    + "Add a module-info.java declaring:");
            for (Requirement r : required) {
                issues.add("    " + render(r));
            }
            report(issues);
            return;
        }

        ModuleDescriptor descriptor;
        try (InputStream in = Files.newInputStream(moduleInfo.toPath())) {
            descriptor = ModuleDescriptor.read(in);
        } catch (IOException e) {
            throw new MojoFailureException("Cannot read " + moduleInfo, e);
        }

        List<Requirement> missing = ModuleInfoRequirements.missing(descriptor, required);
        if (missing.isEmpty()) {
            getLog().info("Vidocq check-module-info: module-info of '" + descriptor.name() + "' satisfies all "
                    + required.size() + " extension requirement(s).");
            return;
        }

        List<String> issues = new ArrayList<>();
        issues.add("module-info of '" + descriptor.name() + "' is missing " + missing.size()
                + " directive(s) required by its Vidocq extensions — add to src/main/java/module-info.java:");
        for (Requirement r : missing) {
            issues.add("    " + render(r));
        }
        report(issues);
    }

    private static String render(Requirement r) {
        return r.reason().isBlank() ? r.directive() : r.directive() + "   (" + r.reason() + ")";
    }

    private void report(List<String> issues) throws MojoFailureException {
        for (String issue : issues) {
            if (failOnMissing) {
                getLog().error(issue);
            } else {
                getLog().warn(issue);
            }
        }
        if (failOnMissing) {
            throw new MojoFailureException("Vidocq check-module-info found a non-modular or incomplete "
                    + "module-info. Add the directives above to src/main/java/module-info.java, or "
                    + "downgrade to warnings via -Dvidocq.moduleinfo.failOnMissing=false.");
        }
    }
}
