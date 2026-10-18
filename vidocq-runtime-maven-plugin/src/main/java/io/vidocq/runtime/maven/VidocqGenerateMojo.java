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

import io.vidocq.runtime.core.GenerateStamp;
import io.vidocq.runtime.maven.depgen.Brick;
import io.vidocq.runtime.maven.depgen.DependencyCodegenCache;
import io.vidocq.vauban.maven.generate.GenerationResult;
import io.vidocq.vauban.maven.generate.VaubanGenerator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

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
import java.util.Set;

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
 * <p>
 * Then runs the processors of every brick on the compiler's {@code annotationProcessorPaths} over the dependency
 * jars their hints select ({@link DependencyCodegenPass}); what they generate joins the jar's enriched copy, next to
 * Vauban's output (Vidocq/vidocq#246). The bricks select before Vauban runs: a jar a brick selects is scanned by
 * Vauban too, since several bricks find their components among CDI beans.
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

    /**
     * Where the output of the bricks' processors is cached per dependency jar, shared between projects. Default
     * {@code $XDG_CACHE_HOME/vidocq/depgen}, else {@code ~/.cache/vidocq/depgen}.
     */
    @Parameter(property = "vidocq.generate.cacheDir")
    private String cacheDir;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    @Component
    private RepositorySystem repoSystem;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        generate();
        // Last, once the index is final, on every path that did not fail: the stamp records that index, and the
        // boot reads a missing or stale stamp as "built without vidocq:generate" (Vidocq/vidocq#241).
        Path classesDir = outputDirectory.toPath();
        if (Files.isDirectory(classesDir)) {
            try {
                writeStamp(classesDir);
            } catch (IOException e) {
                throw new MojoExecutionException("Cannot write " + classesDir.resolve(GenerateStamp.PATH), e);
            }
        }
    }

    /** The files of the project's resolved dependencies, the module path the JSP pages compile against. */
    private List<Path> artifactFiles() {
        var files = new ArrayList<Path>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && artifact.getFile().isFile()) files.add(artifact.getFile().toPath());
        }
        return files;
    }

    private void generate() throws MojoExecutionException, MojoFailureException {
        getLog().info("Vidocq - Generating bean index and proxies for dependencies");
        // First, before Vauban parks its classes: the bricks' output of the last run goes, this run writes its own.
        try {
            DependencyCodegenPass.forgetWritten(buildDirectory.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot delete the earlier dependency codegen output under target/"
                    + JpmsPatches.DIR_NAME, e);
        }

        Path classesDir = outputDirectory.toPath();
        if (!Files.isDirectory(classesDir)) {
            getLog().info("No classes directory found, skipping");
            clearEnrichedCopies();
            return;
        }

        try {
            // Ibarra first: it completes the module descriptor, which Peano then rewrites keeping its services.
            int pages = IbarraPagePrecompilation.apply(classesDir, artifactFiles(), getLog()::warn);
            if (pages > 0) getLog().info("JSP pages compiled by Ibarra: " + pages);
            int peanoProviders=PeanoAccessorRegistration.apply(classesDir);
            if (peanoProviders>0) getLog().info("Peano accessor providers registered: "+peanoProviders);
            // The bricks select first: a jar a brick selects is scanned by Vauban too, its components being CDI beans.
            List<Brick> brickList = DependencyBricks.of(project, repoSystem, repoSession, remoteRepos);
            var dependencies = ScanSelection.dependenciesOf(project.getArtifacts());
            var brickSelection = DependencyCodegenPass.select(brickList, dependencies, orEmpty(scanExcludes),
                    DependencyCodegenPass.selectionMemo(cacheDirectory()), true);
            Map<Path, List<String>> bricksByJar = DependencyCodegenPass.bricksByJar(brickSelection);
            Map<Path, String> scannedByArtifactId = collectScannedDependencies(dependencies, bricksByJar);
            List<String> providers = List.of();
            if (scannedByArtifactId.isEmpty()) {
                getLog().info("No dependencies to process (scanDependencies empty or all pre-processed)");
            } else {
                providers = generateVauban(classesDir, scannedByArtifactId);
            }

            List<Path> closure = new ArrayList<>();
            for (var artifact : project.getArtifacts()) {
                if (artifact.getFile() != null && ScanSelection.isJarOrDirectory(artifact.getFile().toPath())) {
                    closure.add(artifact.getFile().toPath());
                }
            }
            DependencyCodegenPass.Result bricks = generateForBricks(brickList, brickSelection, closure,
                    DependencyCodegenPass.coordinatesOf(dependencies));
            if (scannedByArtifactId.isEmpty() && bricks.processed().isEmpty()) {
                clearEnrichedCopies();
                return;
            }

            // One enriched copy per processed jar: its generated code inside, its descriptor declaring the
            // providers, substituted for the original by dev, run, package and jlink.
            List<EnrichedJars.Scanned> scanned = new ArrayList<>();
            for (var artifact : project.getArtifacts()) {
                Path jar = artifact.getFile() == null ? null : artifact.getFile().toPath();
                if (jar != null && scannedByArtifactId.containsKey(jar) && Files.isRegularFile(jar)) {
                    // A jar a brick selected stays automatic in its copy even when only Vauban generated for it.
                    scanned.add(new EnrichedJars.Scanned(jar, artifact.getArtifactId(),
                            artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion(),
                            bricksByJar.containsKey(jar)));
                }
            }
            EnrichedJars.enrichAll(buildDirectory.toPath(), DependencyCodegenPass.onePerJar(scanned,
                            bricks.processed()), providers, closure, bricks.mergedResources(),
                    bricks.processed().isEmpty() ? packages -> Set.of()
                            : DependencyBricks.modulesOfPackages(closure, getLog()::warn),
                    getLog()::info, getLog()::warn);

        } catch (IOException e) {
            throw new MojoExecutionException("vidocq:generate failed: " + e.getMessage(), e);
        }
    }

    /** Vauban's part: the bean index, proxies and interceptors of the scanned jars. Returns the providers written. */
    private List<String> generateVauban(Path classesDir, Map<Path, String> scannedByArtifactId)
            throws IOException {
        List<Path> vidocqDeps = new ArrayList<>(scannedByArtifactId.keySet());
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
        return result.generatedProviders();
    }

    /** The bricks' part: their processors over the dependency jars they select, into the jars' patch directories. */
    private DependencyCodegenPass.Result generateForBricks(List<Brick> bricks,
                                                           List<DependencyCodegenPass.Selection> selections,
                                                           List<Path> closure, List<String> coordinates)
            throws IOException, MojoFailureException {
        if (bricks.isEmpty()) {
            return new DependencyCodegenPass.Result(List.of(), Set.of());
        }
        var cache = new DependencyCodegenCache(cacheDirectory());
        var pass = new DependencyCodegenPass(bricks, cache, DependencyCodegenPass.JAVAC, buildDirectory.toPath(),
                project.getBasedir().toPath(), getLog()::info, getLog()::warn, getLog()::error);
        return pass.generate(selections, closure, coordinates,
                DependencyCodegenPass.release(project.getProperties().getProperty("maven.compiler.release")),
                orEmpty(scanDependencies));
    }

    private Path cacheDirectory() {
        return DependencyCodegenCache.directory(cacheDir, System.getenv(), System.getProperty("user.home"));
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

    /** Scanned dependency jar → artifactId, in dependency order; {@code bricksByJar}: the jars the bricks select. */
    private Map<Path, String> collectScannedDependencies(List<ScanSelection.Dependency> dependencies,
                                                         Map<Path, List<String>> bricksByJar) throws IOException {
        Map<Path, String> deps = new LinkedHashMap<>();
        for (var d : ScanSelection.decide(dependencies, orEmpty(scanDependencies), orEmpty(scanExcludes), autoScan,
                bricksByJar)) {
            if (d.selected()) {
                deps.put(d.dependency().jar(), d.dependency().artifactId());
                getLog().info("Scanning " + d.dependency().coordinates() + ": " + d.sourceLabel());
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

    /**
     * Writes {@link GenerateStamp#PATH} into {@code classesDir}: the boot of an application that lacks it scans the
     * bean archives no build step indexed, since nothing else did (Vidocq/vidocq#241).
     */
    static void writeStamp(Path classesDir) throws IOException {
        Path index = classesDir.resolve(GenerateStamp.INDEX);
        Path stamp = classesDir.resolve(GenerateStamp.PATH);
        Files.createDirectories(stamp.getParent());
        Files.writeString(stamp, GenerateStamp.content(Files.isRegularFile(index) ? Files.readAllBytes(index) : null));
    }
}
