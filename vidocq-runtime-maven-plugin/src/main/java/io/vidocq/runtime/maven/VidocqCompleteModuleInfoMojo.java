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
import io.vidocq.runtime.codegen.commons.Requirement;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
 * Opt-in companion to {@code check-module-info}: rewrites {@code src/main/java/module-info.java} to add
 * the JPMS {@code opens} directives the application's Vidocq extensions require (from their
 * {@code META-INF/vidocq/module-requirements.properties} descriptors). Invoke explicitly:
 *
 * <pre>mvn vidocq:complete-module-info</pre>
 *
 * <p>Has <strong>no default phase</strong> — it is never run as part of a normal build, so it never
 * silently edits hand-curated source. The edit is a plain text insertion before the module's closing
 * brace (zero-dependency, no parser), is idempotent (a directive already present in source is never
 * duplicated), and marks the block it adds. Only {@code opens} is auto-added: it is never inherited,
 * whereas a {@code requires} may be satisfied transitively and must not be injected blindly.</p>
 */
@Mojo(name = "complete-module-info",
      requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true)
public class VidocqCompleteModuleInfoMojo extends AbstractMojo {

    private static final String MARKER = "// added by vidocq:complete-module-info";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    /** Skip entirely. */
    @Parameter(property = "vidocq.moduleinfo.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Vidocq complete-module-info: skipped (-Dvidocq.moduleinfo.skip=true)");
            return;
        }
        if ("pom".equals(project.getPackaging())) {
            return;
        }

        Set<Requirement> required = ModuleRequirementsCollector.collect(project.getArtifacts(), getLog());
        if (required.isEmpty()) {
            getLog().info("Vidocq complete-module-info: no extension requirements on the path, nothing to add.");
            return;
        }

        File source = locateModuleInfoSource();
        if (source == null) {
            getLog().warn("Vidocq complete-module-info: no module-info.java found under the compile source "
                    + "roots — create one first (scaffolding is not yet supported), then re-run.");
            return;
        }

        String original;
        try {
            original = Files.readString(source.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read " + source, e);
        }

        List<Requirement> missing = computeMissing(required);
        List<String> toAdd = directivesToAdd(original, missing);
        if (toAdd.isEmpty()) {
            getLog().info("Vidocq complete-module-info: module-info.java already declares every required "
                    + "directive — nothing to do.");
            return;
        }

        String updated = inject(original, toAdd);
        try {
            Files.writeString(source.toPath(), updated);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot write " + source, e);
        }
        getLog().info("Vidocq complete-module-info: added " + toAdd.size() + " directive(s) to "
                + source + ":");
        toAdd.forEach(d -> getLog().info("    " + d));
    }

    /** Missing directives, from the compiled module-info.class when present, else all required. */
    private List<Requirement> computeMissing(Set<Requirement> required) {
        File moduleInfo = new File(outputDirectory, "module-info.class");
        if (!moduleInfo.isFile()) {
            return new ArrayList<>(required);
        }
        try (InputStream in = Files.newInputStream(moduleInfo.toPath())) {
            return ModuleInfoRequirements.missing(ModuleDescriptor.read(in), required);
        } catch (IOException e) {
            getLog().debug("Vidocq complete-module-info: cannot read " + moduleInfo + " (" + e + "); "
                    + "falling back to source text.");
            return new ArrayList<>(required);
        }
    }

    private File locateModuleInfoSource() {
        for (String root : project.getCompileSourceRoots()) {
            File candidate = new File(root, "module-info.java");
            if (candidate.isFile()) {
                return candidate;
            }
        }
        return null;
    }

    // --- pure, unit-testable text logic ---

    /**
     * Renders the {@code opens} directives among {@code missing} that are not already declared in
     * {@code source}. Only {@code opens} is auto-added (a {@code requires} may be transitive).
     */
    static List<String> directivesToAdd(String source, List<Requirement> missing) {
        List<String> out = new ArrayList<>();
        for (Requirement r : missing) {
            if (r.kind() != Requirement.Kind.OPENS) {
                continue;
            }
            String directive = r.directive();
            if (!alreadyDeclared(source, directive) && !out.contains(directive)) {
                out.add(directive);
            }
        }
        return out;
    }

    /** True when a (non-commented) line of {@code source} is the given directive, modulo whitespace. */
    static boolean alreadyDeclared(String source, String directive) {
        String needle = normalize(directive);
        for (String raw : source.split("\\R")) {
            if (normalize(stripLineComment(raw)).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Inserts the directives (with a marker comment) just before the module's closing brace. */
    static String inject(String source, List<String> directives) {
        int close = source.lastIndexOf('}');
        if (close < 0 || directives.isEmpty()) {
            return source;
        }
        StringBuilder block = new StringBuilder("\n    ").append(MARKER).append('\n');
        for (String directive : directives) {
            block.append("    ").append(directive).append('\n');
        }
        return source.substring(0, close) + block + source.substring(close);
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("//");
        return idx < 0 ? line : line.substring(0, idx);
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
