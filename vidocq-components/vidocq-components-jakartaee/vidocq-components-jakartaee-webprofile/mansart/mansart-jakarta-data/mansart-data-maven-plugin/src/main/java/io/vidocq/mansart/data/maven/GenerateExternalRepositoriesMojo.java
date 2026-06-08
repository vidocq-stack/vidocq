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
package io.vidocq.mansart.data.maven;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Generates, ahead of time, a {@code @Singleton} implementation for every {@code @Repository}
 * interface found in the project's compile-classpath dependency jars that does not already ship a
 * generated implementation. The implementations (and any missing entity metamodels) are emitted
 * into an application-owned package and indexed in {@code META-INF/mansart-repositories-external.list},
 * which the Mansart CDI {@code BuildCompatibleExtension} reads alongside the APT-produced index —
 * so beans for library-provided repositories are wired statically instead of via the runtime
 * reflective fallback.
 */
@Mojo(name = "generate-external-repositories",
      defaultPhase = LifecyclePhase.GENERATE_SOURCES,
      requiresDependencyResolution = ResolutionScope.COMPILE,
      threadSafe = true)
public class GenerateExternalRepositoriesMojo extends AbstractMojo {

    private static final String EXTERNAL_LIST = "META-INF/mansart-repositories-external.list";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/mansart")
    private File generatedSourcesDirectory;

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(property = "mansart.targetPackage")
    private String targetPackage;

    @Parameter(property = "mansart.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Mansart: external repository generation skipped (mansart.skip=true).");
            return;
        }
        var log = getLog();
        List<String> classpath;
        try {
            classpath = project.getCompileClasspathElements();
        } catch (DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Cannot resolve the compile classpath", e);
        }

        try {
            List<String> repositories = RepositoryClassScanner.findRepositoryInterfaces(classpath);
            Set<String> alreadyGenerated = RepositoryClassScanner.alreadyGenerated(classpath);
            List<String> toGenerate = new ArrayList<>();
            for (String repo : repositories) {
                if (!alreadyGenerated.contains(repo)) toGenerate.add(repo);
            }

            if (toGenerate.isEmpty()) {
                log.info("Mansart: no external @Repository requiring ahead-of-time generation.");
                return;
            }

            String pkg = (targetPackage != null && !targetPackage.isBlank())
                    ? targetPackage : deriveTargetPackage();
            Files.createDirectories(generatedSourcesDirectory.toPath());

            log.info("Mansart: generating implementations for " + toGenerate.size()
                    + " external @Repository interface(s) into package " + pkg);
            List<String> entries = runCodegen(classpath, toGenerate, pkg);

            // The generated sources are compiled by the standard compile phase that follows.
            project.addCompileSourceRoot(generatedSourcesDirectory.getAbsolutePath());
            writeExternalIndex(entries);

            log.info("Mansart: generated " + entries.size() + " external repository implementation(s).");
        } catch (IOException e) {
            throw new MojoExecutionException("Mansart external repository generation failed", e);
        }
    }

    /**
     * Runs a source-less {@code javac} task over the compile classpath with a throwaway processor
     * that drives {@link io.vidocq.mansart.data.processor.ExternalRepositoryCodegen}. {@code -proc:only}
     * makes the task emit sources without compiling them — the standard compile phase compiles them.
     */
    private List<String> runCodegen(List<String> classpath, List<String> repoFqns, String pkg)
            throws MojoExecutionException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new MojoExecutionException("No system Java compiler available — run the build on a JDK.");
        }
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
        try {
            List<File> cpFiles = new ArrayList<>();
            for (String e : classpath) cpFiles.add(new File(e));
            fm.setLocation(StandardLocation.CLASS_PATH, cpFiles);
            fm.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generatedSourcesDirectory));

            ExternalCodegenProcessor processor = new ExternalCodegenProcessor(repoFqns, pkg);
            var task = compiler.getTask(null, fm, null, List.of("-proc:only"), null,
                    List.of(new TriggerSource()));
            task.setProcessors(List.of(processor));
            Boolean ok = task.call();
            if (ok == null || !ok) {
                throw new MojoExecutionException("Mansart codegen javac task reported errors (see log).");
            }
            return processor.entries();
        } catch (IOException e) {
            throw new MojoExecutionException("Mansart codegen I/O error", e);
        } finally {
            try { fm.close(); } catch (IOException ignored) { /* best effort */ }
        }
    }

    private void writeExternalIndex(List<String> entries) throws IOException {
        if (entries.isEmpty()) return;
        Path list = outputDirectory.toPath().resolve(EXTERNAL_LIST);
        Files.createDirectories(list.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by mansart-data-maven-plugin — one line per external @Repository\n");
        sb.append("# format: <interface-fqn>=<impl-fqn>\n");
        for (String e : entries) sb.append(e).append('\n');
        Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Derives a stable, valid application-owned package from the project coordinates. */
    private String deriveTargetPackage() {
        String base = sanitizePackage(project.getGroupId() + "." + project.getArtifactId());
        return base + ".mansart.generated";
    }

    private static String sanitizePackage(String raw) {
        String[] segments = raw.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String seg : segments) {
            if (seg.isEmpty()) continue;
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < seg.length(); i++) {
                char c = seg.charAt(i);
                s.append(Character.isJavaIdentifierPart(c) ? c : '_');
            }
            if (s.length() == 0) continue;
            if (!Character.isJavaIdentifierStart(s.charAt(0))) s.insert(0, '_');
            if (sb.length() > 0) sb.append('.');
            sb.append(s);
        }
        return sb.length() == 0 ? "mansart" : sb.toString();
    }

    /** A minimal in-memory compilation unit so the processing round fires; never compiled (-proc:only). */
    private static final class TriggerSource extends SimpleJavaFileObject {
        TriggerSource() {
            super(URI.create("string:///mansart_codegen_trigger/Trigger.java"), Kind.SOURCE);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return "package mansart_codegen_trigger; class Trigger {}";
        }
    }
}
