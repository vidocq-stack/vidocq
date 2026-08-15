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

import io.vidocq.runtime.maven.modularize.LicenseGate;
import io.vidocq.runtime.maven.modularize.Modularizer;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Goal {@code modularize}: patches the non-modular jars of the runtime closure with a generated
 * module descriptor (see {@link Modularizer}) into {@code target/vidocq-modularized/}. The dev,
 * jlink and package goals pick those copies up automatically. Nothing is installed or deployed.
 *
 * <p>Default phase: {@code prepare-package} — after the application is compiled and before it is
 * assembled, so every downstream goal sees the patched copies. Skip with
 * {@code -Dvidocq.modularize.skip=true}.</p>
 *
 * <p>An optional {@code allowedLicenses} allow-list turns the goal into a licence gate: when it is
 * set, every artifact that ends up patched must declare one of the listed licences, otherwise the
 * build fails. Patching rewrites a third-party jar, so the gate is there to make that rewriting an
 * explicit, licence-aware decision.</p>
 */
@Mojo(name = "modularize",
      defaultPhase = LifecyclePhase.PREPARE_PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME,
      threadSafe = true)
public class VidocqModularizeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private ProjectBuilder projectBuilder;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDir;

    /** Skip the goal entirely. */
    @Parameter(property = "vidocq.modularize.skip", defaultValue = "false")
    private boolean skip;

    /**
     * {@code derived} (default) patches only the jars whose automatic module name is derived from
     * their file name; {@code all-automatic} also patches those declaring an
     * {@code Automatic-Module-Name} — which jlink requires.
     */
    @Parameter(property = "vidocq.modularize.mode", defaultValue = "derived")
    private String mode;

    /** Restrict patching to these artifactIds. */
    @Parameter
    private List<String> includes;

    /** Never patch these artifactIds. */
    @Parameter
    private List<String> excludes;

    /** artifactId to module name overrides; the JDK-derived automatic name is used by default. */
    @Parameter
    private Map<String, String> moduleNames;

    /** Generate {@code open module} descriptors. */
    @Parameter(property = "vidocq.modularize.open", defaultValue = "true")
    private boolean openModules;

    /** JDK release jdeps analyses multi-release jars against. */
    @Parameter(defaultValue = "${maven.compiler.release}")
    private String release;

    /** Optional licence allow-list; empty (the default) leaves the gate off. */
    @Parameter
    private List<String> allowedLicenses;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("vidocq:modularize skipped");
            return;
        }
        List<Path> closure = new ArrayList<>();
        Map<Path, String> artifactIdByJar = new HashMap<>();
        Map<String, Artifact> artifactByJarName = new LinkedHashMap<>();
        for (Artifact a : project.getArtifacts()) {
            File f = a.getFile();
            if (f != null && "jar".equals(a.getType()) && f.getName().endsWith(".jar")) {
                Path p = f.toPath();
                closure.add(p);
                artifactIdByJar.put(p, a.getArtifactId());
                artifactByJarName.put(f.getName(), a);
            }
        }
        Modularizer.Mode m = "all-automatic".equalsIgnoreCase(mode) ? Modularizer.Mode.ALL_AUTOMATIC
                : Modularizer.Mode.DERIVED;
        var options = new Modularizer.Options(m,
                new HashSet<>(includes == null ? List.of() : includes),
                new HashSet<>(excludes == null ? List.of() : excludes),
                moduleNames == null ? Map.of() : moduleNames,
                openModules,
                release == null || release.isBlank() ? "25" : release);
        Modularizer.Result result;
        try {
            result = Modularizer.run(closure, artifactIdByJar, buildDir.toPath(), options, getLog()::info);
        } catch (IllegalStateException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (IOException e) {
            throw new MojoExecutionException("vidocq:modularize failed", e);
        }
        if (allowedLicenses != null && !allowedLicenses.isEmpty()) {
            // The patched copy keeps the file name of the jar it was made from (ModularizedJars),
            // so the file name is enough to find the artifact each patched jar came from.
            Map<String, List<String>> licenses = new LinkedHashMap<>();
            for (Path patched : result.patched()) {
                Artifact a = artifactByJarName.get(patched.getFileName().toString());
                if (a == null) {
                    throw new MojoExecutionException("No artifact matches patched jar " + patched);
                }
                licenses.put(a.getGroupId() + ":" + a.getArtifactId(), licensesOf(a));
            }
            List<String> violations = LicenseGate.violations(licenses, new HashSet<>(allowedLicenses));
            if (!violations.isEmpty()) {
                throw new MojoFailureException("vidocq:modularize licence gate:\n  "
                        + String.join("\n  ", violations));
            }
        }
        getLog().info("vidocq:modularize — " + result.patched().size() + " jar(s) patched, "
                + result.skipped().size() + " left as is; report: "
                + ModularizedJars.root(buildDir.toPath()).resolve("report.txt"));
    }

    /** The licence names declared by the POM of {@code a}, in declaration order. */
    private List<String> licensesOf(Artifact a) throws MojoExecutionException {
        try {
            ProjectBuildingRequest req =
                    new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            req.setResolveDependencies(false);
            req.setProcessPlugins(false);
            MavenProject p = projectBuilder.build(a, req).getProject();
            List<String> names = new ArrayList<>();
            for (License l : p.getLicenses()) {
                names.add(l.getName() == null ? "" : l.getName());
            }
            return names;
        } catch (Exception e) {
            throw new MojoExecutionException("Cannot resolve licences of " + a, e);
        }
    }
}
