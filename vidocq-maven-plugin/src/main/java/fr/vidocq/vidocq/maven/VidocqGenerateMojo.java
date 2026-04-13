package fr.vidocq.vidocq.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Génère l'index des beans CDI pour une application Vidocq.
 * <p>
 * Scanne le classpath du projet et ses dépendances pour produire
 * le fichier {@code META-INF/vauban-beans.list} utilisé au runtime
 * par le {@link fr.vidocq.vauban.core.container.VaubanContainerBuilder}.
 * </p>
 *
 * <p><b>Generates the CDI bean index for a Vidocq application.</b></p>
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
        getLog().info("Vidocq - Generating bean index");

        try {
            Path classesDir = outputDirectory.toPath();
            if (!Files.isDirectory(classesDir)) {
                getLog().info("No classes directory found, skipping");
                return;
            }

            // Walk the output directory and collect all .class FQCNs
            List<String> beanClasses = new ArrayList<>();
            try (var stream = Files.walk(classesDir)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                      .filter(p -> !p.getFileName().toString().equals("module-info.class"))
                      .forEach(p -> {
                          Path relative = classesDir.relativize(p);
                          String fqcn = relative.toString()
                                  .replace(File.separatorChar, '.')
                                  .replace(".class", "");
                          beanClasses.add(fqcn);
                      });
            }

            // Write META-INF/vauban-beans.list
            Path metaInf = classesDir.resolve("META-INF");
            Files.createDirectories(metaInf);
            Path beansList = metaInf.resolve("vauban-beans.list");
            Files.write(beansList, beanClasses);

            getLog().info("Bean index generated: " + beanClasses.size() + " class(es)");
            for (String cls : beanClasses) {
                getLog().info("  - " + cls);
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate bean index", e);
        }
    }
}
