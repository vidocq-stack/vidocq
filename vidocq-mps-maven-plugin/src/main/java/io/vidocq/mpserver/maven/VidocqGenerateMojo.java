package io.vidocq.mpserver.maven;

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
import java.util.List;
import java.util.jar.JarFile;

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

    /**
     * Liste des dépendances à scanner pour la génération de proxies/index.
     * Format : {@code groupId:artifactId}. {@code artifactId} optionnel ou
     * {@code *} pour matcher tout artefact d'un groupId. Wildcard final
     * accepté : {@code io.vidocq.*}, {@code com.acme.*:my-lib*}.
     * <p>Les JARs portant {@code META-INF/vauban-bce-processed} sont
     * automatiquement exclus, qu'ils matchent ou non.</p>
     */
    @Parameter
    private List<String> scanDependencies;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Vidocq - Generating bean index and proxies for dependencies");

        Path classesDir = outputDirectory.toPath();
        if (!Files.isDirectory(classesDir)) {
            getLog().info("No classes directory found, skipping");
            return;
        }

        try {
            List<Path> vidocqDeps = collectScannedDependencies();
            if (vidocqDeps.isEmpty()) {
                getLog().info("No dependencies to process (scanDependencies empty or all pre-processed)");
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

    private List<Path> collectScannedDependencies() throws IOException {
        if (scanDependencies == null || scanDependencies.isEmpty()) {
            return List.of();
        }
        List<Pattern> patterns = scanDependencies.stream().map(Pattern::parse).toList();
        List<Path> deps = new ArrayList<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() == null) continue;
            String g = artifact.getGroupId();
            String a = artifact.getArtifactId();
            if (patterns.stream().noneMatch(p -> p.matches(g, a))) continue;
            Path jar = artifact.getFile().toPath();
            if (isAlreadyProcessed(jar)) {
                getLog().debug("Skipping " + g + ":" + a + " (vauban-bce-processed)");
                continue;
            }
            deps.add(jar);
        }
        return deps;
    }

    private static boolean isAlreadyProcessed(Path jar) throws IOException {
        if (Files.isDirectory(jar)) {
            return Files.exists(jar.resolve("META-INF/vauban-bce-processed"));
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getEntry("META-INF/vauban-bce-processed") != null;
        }
    }

    private record Pattern(String groupId, String artifactId) {
        static Pattern parse(String s) {
            int colon = s.indexOf(':');
            String g = colon >= 0 ? s.substring(0, colon) : s;
            String a = colon >= 0 ? s.substring(colon + 1) : "*";
            return new Pattern(g, a.isEmpty() ? "*" : a);
        }
        boolean matches(String g, String a) {
            return matchToken(groupId, g) && matchToken(artifactId, a);
        }
        private static boolean matchToken(String pattern, String value) {
            if ("*".equals(pattern)) return true;
            if (pattern.endsWith("*")) {
                return value.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return pattern.equals(value);
        }
    }
}
