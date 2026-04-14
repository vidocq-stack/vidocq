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
 * pour une application Vidocq.
 * <p>
 * Delegue a {@link VaubanGenerator} qui :
 * <ol>
 *   <li>Scanne les classes du projet et ses dependances</li>
 *   <li>Execute la decouverte de beans CDI (scopes, intercepteurs, producers)</li>
 *   <li>Pre-genere les client proxies ({@code _ClientProxy}) pour les beans normal-scoped</li>
 *   <li>Pre-genere les sous-classes interceptees ({@code $$Intercepted})</li>
 *   <li>Ecrit {@code META-INF/vauban-beans.list}</li>
 * </ol>
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
        getLog().info("Vidocq - Generating bean index and proxies");

        Path classesDir = outputDirectory.toPath();
        if (!Files.isDirectory(classesDir)) {
            getLog().info("No classes directory found, skipping");
            return;
        }

        try {
            // Only scan Vidocq/Vauban JARs, not Jersey/HK2 internals.
            // Third-party beans are not CDI-managed by Vidocq.
            List<Path> vidocqExtensionJars = collectVidocqExtensionJars();

            // Build a ClassLoader with project classes + all dependencies
            // so VaubanGenerator can resolve types for proxy generation
            URLClassLoader classLoader = buildClassLoader(vidocqExtensionJars, classesDir);

            var config = new VaubanGenerator.Config(
                    vidocqExtensionJars,
                    classesDir,
                    classesDir,
                    classLoader
            );

            GenerationResult result;
            try {
                result = VaubanGenerator.generate(config);
            } finally {
                classLoader.close();
            }

            getLog().info("Bean index: " + result.discoveredBeanClasses().size() + " class(es)");
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

    private URLClassLoader buildClassLoader(List<Path> jars, Path classesDir) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();
        urls.add(classesDir.toUri().toURL());
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null) {
                urls.add(artifact.getFile().toURI().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader());
    }

    private List<Path> collectVidocqExtensionJars() {
        List<Path> jars = new ArrayList<>();
        for (var artifact : project.getArtifacts()) {
            if (artifact.getFile() != null && artifact.getFile().getName().endsWith(".jar")) {
                // Only include Vidocq/Vauban JARs that may contain CDI beans
                String groupId = artifact.getGroupId();
                if (groupId.startsWith("fr.vidocq.")) {
                    jars.add(artifact.getFile().toPath());
                }
            }
        }
        return jars;
    }
}
