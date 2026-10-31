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
 * Verify that the application's {@code module-info.java} declares the Java module directives its Vidocq
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
 * <p>It also reads the compiled classes for the packages a runtime module reaches by reflection — the
 * JPA entities, and the types a REST resource or provider takes or returns, with the types their fields
 * and getters hold, and the CDI interceptors (see {@link ReflectedPackages}) — and asks for an
 * {@code opens} of each one the module does not open: JSON-B binds those types, and Vauban calls the
 * interceptors, by reflection, and the first request failed (BUG-20261030-24, BUG-20261031-04).</p>
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

        File moduleInfo = new File(outputDirectory, "module-info.class");
        if (!moduleInfo.isFile()) {
            boolean source = hasModuleInfoSource(project.getCompileSourceRoots());
            if (source) {
                getLog().warn(UNCOMPILED);
            }
            report(uncompiledIssues(required, source));
            return;
        }
        ModuleDescriptor descriptor = read(moduleInfo);
        List<Requirement> missing = required.isEmpty() ? List.of() : ModuleInfoRequirements.missing(descriptor, required);
        ReflectedPackages.Reached reached = reflectedPackages();
        List<String> unopened = ReflectedPackages.missing(descriptor, reached);
        if (missing.isEmpty() && unopened.isEmpty()) {
            getLog().info("Vidocq check-module-info: module-info of '" + descriptor.name() + "' satisfies all "
                    + required.size() + " extension requirement(s) and opens the " + reached.reasons().size()
                    + " package(s) the runtime reaches by reflection.");
            return;
        }
        List<String> issues = new ArrayList<>();
        if (!missing.isEmpty()) {
            issues.add("module-info of '" + descriptor.name() + "' is missing " + missing.size()
                    + " directive(s) required by its Vidocq extensions — `vidocq doctor --fix` adds them, or add to"
                    + " src/main/java/module-info.java:");
            missing.forEach(r -> issues.add("    " + render(r)));
        }
        if (!unopened.isEmpty()) {
            // BUG-20261030-24, BUG-20261031-04: JSON-B binds these types, and Vauban calls the interceptors, by
            // reflection; the first request failed with an IllegalAccessException.
            issues.add("module-info of '" + descriptor.name() + "' does not open " + unopened.size()
                    + " package(s) the runtime reaches by reflection (JSON-B, JPA, CDI interceptors): the first"
                    + " request using them would fail — `mvn vidocq:complete-module-info` adds them, or add to"
                    + " src/main/java/module-info.java:");
            unopened.forEach(pkg -> issues.add("    opens " + pkg + ";   (" + reached.reasons().get(pkg) + ")"));
        }
        report(issues);
    }

    /**
     * Logged instead of a verdict when the sources declare a {@code module-info.java} that {@code target/classes}
     * lacks: the compilation stopped before it, under {@code vidocq dev} which goes on after a compile error
     * (BUG-20261031-02).
     */
    static final String UNCOMPILED = "Vidocq check-module-info: skipped — module-info.java is not compiled yet"
            + " (see the compilation errors above); the module's directives are checked once it compiles.";

    /** Whether one of the compile source roots holds a {@code module-info.java}. */
    static boolean hasModuleInfoSource(List<String> compileSourceRoots) {
        return compileSourceRoots.stream().anyMatch(root -> new File(root, "module-info.java").isFile());
    }

    /**
     * The issues of a module whose {@code module-info.class} is missing: none when its source exists (the
     * compilation did not finish, and the compiler says why) or when its extensions ask for no directive; else
     * the directives its {@code module-info.java} must declare.
     */
    static List<String> uncompiledIssues(Set<Requirement> required, boolean moduleInfoSource) {
        if (moduleInfoSource || required.isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        issues.add("This module depends on Vidocq extensions that need Java module directives, but it has "
                + "no module-info.java (it is not a Java module), and Vidocq runs an application as one. "
                + "Add a module-info.java declaring:");
        required.forEach(r -> issues.add("    " + render(r)));
        return issues;
    }

    private static ModuleDescriptor read(File moduleInfo) throws MojoFailureException {
        try (InputStream in = Files.newInputStream(moduleInfo.toPath())) {
            return ModuleDescriptor.read(in);
        } catch (IOException e) {
            throw new MojoFailureException("Cannot read " + moduleInfo, e);
        }
    }

    private ReflectedPackages.Reached reflectedPackages() throws MojoFailureException {
        try {
            return ReflectedPackages.of(outputDirectory.toPath());
        } catch (IOException e) {
            throw new MojoFailureException("Cannot read the classes of " + outputDirectory, e);
        }
    }

    private static String render(Requirement r) {
        return r.reason().isBlank() ? r.directive() : r.directive() + "   (" + r.reason() + ")";
    }

    private void report(List<String> issues) throws MojoFailureException {
        if (issues.isEmpty()) {
            return;
        }
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
