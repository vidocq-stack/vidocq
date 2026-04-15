package fr.vidocq.vidocq.maven;

import fr.vidocq.vauban.maven.generate.GenerationResult;
import fr.vidocq.vauban.maven.generate.VaubanGenerator;
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
import java.util.List;

/**
 * Genere l'index des beans CDI et pre-genere les proxies/intercepteurs
 * pour les dependances non pre-traitees par le VaubanProcessor APT.
 * <p>
 * Pour les classes du projet, le VaubanProcessor APT fait tout a la compilation.
 * Ce Mojo traite les JARs de dependances qui n'ont pas ete pre-traites
 * (pas de {@code META-INF/vauban-bce-processed}).
 * </p>
 * <p>
 * Au runtime, les BCEs {@code @Enhancement} s'executent automatiquement
 * pour les beans sans scope provenant de JARs non pre-traites
 * (via {@code BceProcessor.processEnhancementOnly()}).
 * </p>
 */
@Mojo(name = "generate",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES,
      requiresDependencyResolution = ResolutionScope.COMPILE)
public class VidocqGenerateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Vidocq - Generating bean index and proxies for dependencies");

        Path classesDir = outputDirectory.toPath();
        if (!Files.isDirectory(classesDir)) {
            getLog().info("No classes directory found, skipping");
            return;
        }

        try {
            List<Path> vidocqDeps = collectVidocqDependencies();
            if (vidocqDeps.isEmpty()) {
                getLog().info("No Vidocq dependencies to process");
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
                    classLoader
            );

            // Sauvegarder le beans.list existant (genere par l'APT)
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

            // Merger les beans de l'APT avec ceux des dependances
            if (!existingBeans.isEmpty()) {
                var merged = new java.util.TreeSet<>(existingBeans);
                merged.addAll(result.discoveredBeanClasses());
                Files.createDirectories(beansListPath.getParent());
                var lines = new ArrayList<String>();
                lines.add("# Vauban discovered beans — APT + vidocq-maven-plugin");
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

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate bean index", e);
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

    private List<Path> collectVidocqDependencies() {
        List<Path> deps = new ArrayList<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null) {
                String groupId = artifact.getGroupId();
                if (groupId.startsWith("fr.vidocq.")) {
                    deps.add(artifact.getFile().toPath());
                }
            }
        }
        return deps;
    }
}
