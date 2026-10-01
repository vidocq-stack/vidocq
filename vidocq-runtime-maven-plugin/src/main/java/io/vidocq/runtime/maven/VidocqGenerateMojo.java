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

import io.vidocq.vauban.maven.generate.GenerationResult;
import io.vidocq.vauban.maven.generate.VaubanGenerator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the CDI bean index and pre-generates the proxies/interceptors
 * for dependencies not pre-processed by the VaubanProcessor APT.
 * <p>
 * For the project classes, the VaubanProcessor APT does everything during compilation.
 * This Mojo processes dependency JARs that have not been pre-processed
 * (no {@code META-INF/vauban-bce-processed}).
 * </p>
 * <p>
 * At runtime, the BCEs {@code @Enhancement} execute automatically
 * for beans without scope coming from non-pre-processed JARs
 * (via {@code BceProcessor.processEnhancementOnly()}).
 * </p>
 * <p>
 * Resolves the runtime scope too: a build-compatible extension that reaches the application at runtime scope —
 * Cassini's, through its REST extension — is on the module path at run time, so the generator must run it as well,
 * or the classes it alone makes beans keep the reflection fallback (Vidocq/vidocq#188).
 * </p>
 */
@Mojo(name = "generate",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class VidocqGenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDirectory;

    /**
     * List of dependencies to scan for proxy/index generation.
     * Format: {@code groupId:artifactId}. {@code artifactId} optional or
     * {@code *} to match any artifact of a groupId. Final Wildcard
     * accepted: {@code io.vidocq.*}, {@code com.acme.*:my-lib*}.
     * <p>The jars that already carry generated code are always excluded. Extensions add their own jars through
     * their manifest, and bean archives are detected; see {@link ScanSelection}.</p>
     */
    @Parameter
    private List<String> scanDependencies;

    /**
     * Dependencies never scanned, whatever selects them: an extension, {@code scanDependencies} or the bean-archive
     * detection. Same syntax as {@code scanDependencies}.
     */
    @Parameter
    private List<String> scanExcludes;

    /**
     * Scans every dependency that is a CDI bean archive — a {@code META-INF/beans.xml} whose discovery mode is not
     * {@code none} — besides what an extension or {@code scanDependencies} names.
     */
    @Parameter(property = "vidocq.generate.autoScan", defaultValue = "true")
    private boolean autoScan;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Vidocq - Generating bean index and proxies for dependencies");

        Path classesDir = outputDirectory.toPath();
        if (!Files.isDirectory(classesDir)) {
            getLog().info("No classes directory found, skipping");
            clearEnrichedCopies();
            return;
        }

        try {
            Map<Path, String> scannedByArtifactId = collectScannedDependencies();
            List<Path> vidocqDeps = new ArrayList<>(scannedByArtifactId.keySet());
            if (vidocqDeps.isEmpty()) {
                getLog().info("No dependencies to process (scanDependencies empty or all pre-processed)");
                clearEnrichedCopies();
                return;
            }

            boolean aptProcessed = Files.exists(classesDir.resolve("META-INF/vauban-bce-processed"));
            if (aptProcessed) {
                getLog().info("APT already processed project classes (vauban-bce-processed found)");
            }

            URLClassLoader classLoader = buildClassLoader(classesDir);

            var config = new VaubanGenerator.Config(
                    vidocqDeps,
                    aptProcessed ? null : classesDir,
                    classesDir,
                    classLoader,
                    true
            );

            // Save the existing beans.list (generated by APT)
            Path beansListPath = classesDir.resolve("META-INF/vauban-beans.list");
            List<String> existingBeans = new ArrayList<>();
            if (Files.exists(beansListPath)) {
                existingBeans = Files.readAllLines(beansListPath).stream()
                        .filter(l -> !l.isBlank() && !l.startsWith("#"))
                        .toList();
            }

            GenerationResult result;
            try {
                result = VaubanGenerator.generate(config);
            } finally {
                classLoader.close();
            }

            // Merge APT beans with dependency beans
            if (!existingBeans.isEmpty()) {
                var merged = new java.util.TreeSet<>(existingBeans);
                merged.addAll(result.discoveredBeanClasses());
                Files.createDirectories(beansListPath.getParent());
                var lines = new ArrayList<String>();
                lines.add("# Vauban discovered beans — APT + vidocq-runtime-maven-plugin");
                lines.addAll(merged);
                Files.write(beansListPath, lines);
                getLog().info("Bean index (merged): " + merged.size() + " class(es)");
            } else {
                getLog().info("Bean index: " + result.discoveredBeanClasses().size() + " class(es)");
            }

            for (String cls : result.discoveredBeanClasses()) {
                getLog().info("  - " + cls);
            }

            if (!result.generatedProxies().isEmpty()) {
                getLog().info("Proxies: " + result.generatedProxies().size() + " generated");
                for (String proxy : result.generatedProxies()) {
                    getLog().info("  - " + proxy);
                }
            }

            if (!result.generatedInterceptors().isEmpty()) {
                getLog().info("Interceptors: " + result.generatedInterceptors().size() + " generated");
                for (String interceptor : result.generatedInterceptors()) {
                    getLog().info("  - " + interceptor);
                }
            }

            for (String warning : result.warnings()) {
                getLog().warn(warning);
            }

            // Classes generated for a scanned dependency live in packages owned by that
            // dependency's module: leaving them in target/classes would create a JPMS
            // split package. Park them in target/vidocq-patches/<artifactId>/; the
            // packaging goals re-attach them by enriching the dependency jar copies.
            List<String> generated = new ArrayList<>();
            generated.addAll(result.generatedProxies());
            generated.addAll(result.generatedInterceptors());
            generated.addAll(result.generatedProviders());
            var relocated = JpmsPatches.relocate(
                    classesDir, buildDirectory.toPath(), scannedByArtifactId, generated);
            for (var e : relocated.entrySet()) {
                getLog().info("JPMS: parked " + e.getValue() + " generated class(es) of '" + e.getKey()
                        + "' in target/" + JpmsPatches.DIR_NAME + "/" + e.getKey()
                        + " — packaging goals will enrich that jar");
            }

            // One enriched copy per scanned jar: its generated classes inside, its descriptor declaring the
            // providers, substituted for the original by dev, run, package and jlink.
            List<EnrichedJars.Scanned> scanned = new ArrayList<>();
            List<Path> closure = new ArrayList<>();
            for (var artifact : project.getArtifacts()) {
                if (artifact.getFile() == null || !ScanSelection.isJarOrDirectory(artifact.getFile().toPath())) {
                    continue;
                }
                Path jar = artifact.getFile().toPath();
                closure.add(jar);
                if (scannedByArtifactId.containsKey(jar) && Files.isRegularFile(jar)) {
                    scanned.add(new EnrichedJars.Scanned(jar, artifact.getArtifactId(),
                            artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion()));
                }
            }
            EnrichedJars.enrichAll(buildDirectory.toPath(), scanned, result.generatedProviders(), closure,
                    getLog()::info, getLog()::warn);

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate bean index", e);
        }
    }

    /** Nothing is scanned this run: no earlier enriched copy may still stand in for its jar. */
    private void clearEnrichedCopies() throws MojoExecutionException {
        try {
            EnrichedJars.clear(buildDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot clear target/" + EnrichedJars.DIR_NAME, e);
        }
    }

    private URLClassLoader buildClassLoader(Path classesDir) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        urls.add(classesDir.toUri().toURL());
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null) {
                urls.add(artifact.getFile().toURI().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    /** Scanned dependency jar → artifactId, in dependency order. */
    private Map<Path, String> collectScannedDependencies() throws IOException {
        Map<Path, String> deps = new LinkedHashMap<>();
        for (var d : ScanSelection.decide(ScanSelection.dependenciesOf(project.getArtifacts()),
                orEmpty(scanDependencies), orEmpty(scanExcludes), autoScan)) {
            if (d.selected()) {
                deps.put(d.dependency().jar(), d.dependency().artifactId());
                getLog().info("Scanning " + d.dependency().coordinates() + ": " + d.source().label());
            } else if (d.excludedBecause() != null) {
                if (d.facts().processed()) {
                    getLog().debug("Skipping " + d.dependency().coordinates() + ": " + d.excludedBecause());
                } else {
                    getLog().warn("Not scanning " + d.dependency().coordinates() + ": " + d.excludedBecause());
                }
            }
        }
        return deps;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
