package io.vidocq.mpserver.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

/**
 * Package une application Vidocq en image runtime <b>jlink</b> autonome —
 * pas de JRE pré-installée requise sur la cible.
 *
 * <p>Structure de sortie ({@code target/dist/} par défaut) :
 * <pre>
 *   dist/
 *     bin/&lt;launcher&gt;        — script de démarrage généré par jlink
 *     conf/                  — vidocq.properties, logging.properties, ...
 *     lib/, legal/, release  — image runtime jlink (modules + JDK)
 * </pre>
 *
 * <p>Tous les jars de dépendance et l'artefact applicatif doivent être des
 * <b>modules JPMS nommés</b> (présence d'un {@code module-info.class}). Les
 * automatic modules sont rejetés par {@code jlink}.</p>
 */
@Mojo(name = "jlink",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME)
public class VidocqJlinkMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Module Java contenant la classe main (ex. {@code io.vidocq.mpserver.examples.rest}). */
    @Parameter(property = "vidocq.mainModule", required = true)
    private String mainModule;

    /** Classe main pleinement qualifiée (ex. {@code io.vidocq.mpserver.examples.rest.RestExampleApp}). */
    @Parameter(property = "vidocq.mainClass", required = true)
    private String mainClass;

    /** Nom du launcher généré dans {@code dist/bin/} (défaut : {@code project.artifactId}). */
    @Parameter(defaultValue = "${project.artifactId}", property = "vidocq.launcher")
    private String launcher;

    /** Répertoire de sortie de l'image jlink (défaut : {@code target/dist}). */
    @Parameter(defaultValue = "${project.build.directory}/dist", property = "vidocq.distDir")
    private File distDir;

    /** Répertoire de staging des modules avant jlink (défaut : {@code target/jlink-mods}). */
    @Parameter(defaultValue = "${project.build.directory}/jlink-mods", readonly = true)
    private File modsDir;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDir;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File classesDir;

    /** Strip debug info (défaut : true). */
    @Parameter(defaultValue = "true", property = "vidocq.stripDebug")
    private boolean stripDebug;

    /**
     * Skip de l'exécution complète du goal jlink (défaut : false).
     * Utile en CI pour valider d'abord le mécanisme de PR cross-repo sans bloquer
     * sur des dépendances qui sont encore des automatic modules
     * (ex. microprofile-config-api avant moditect).
     */
    @Parameter(defaultValue = "false", property = "vidocq.jlink.skip")
    private boolean skip;

    /** Niveau de compression jlink ({@code zip-0} à {@code zip-9}, défaut : {@code zip-6}). */
    @Parameter(defaultValue = "zip-6", property = "vidocq.compress")
    private String compress;

    /**
     * Ressources non-modulaires à copier dans {@code dist/conf/}, relatives à
     * {@code target/classes/}. Défaut : {@code vidocq.properties} et {@code logging.properties}
     * s'ils existent.
     */
    @Parameter(property = "vidocq.includeResources")
    private List<String> includeResources;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Vidocq jlink — skip (vidocq.jlink.skip=true).");
            return;
        }
        getLog().info("Vidocq jlink — main: " + mainModule + "/" + mainClass);
        try {
            stageModules();
            String addModules = resolveAddModules();
            runJlink(addModules);
            copyResources();
            getLog().info("jlink image ready: " + distDir.toPath().toAbsolutePath());
            getLog().info("Run with: " + distDir.toPath().toAbsolutePath() + "/bin/" + launcher);
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("Failed to create jlink image", e);
        }
    }

    private void stageModules() throws IOException {
        Path stage = modsDir.toPath();
        deleteRecursive(stage);
        Files.createDirectories(stage);

        Path appJar = project.getArtifact().getFile().toPath();
        Files.copy(appJar, stage.resolve(appJar.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        for (var artifact : project.getArtifacts()) {
            File f = artifact.getFile();
            if (f != null && f.getName().endsWith(".jar")) {
                Path src = f.toPath();
                Files.copy(src, stage.resolve(src.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Vérification : tous les jars stagés doivent être des modules nommés.
        List<String> automatic = new ArrayList<>();
        for (var ref : ModuleFinder.of(stage).findAll()) {
            ModuleDescriptor md = ref.descriptor();
            if (md.isAutomatic()) {
                automatic.add(md.name() + " (" + ref.location().orElse(null) + ")");
            }
        }
        if (!automatic.isEmpty()) {
            throw new IOException("jlink ne supporte pas les automatic modules : "
                    + String.join(", ", automatic)
                    + ". Convertis ces jars en vrais modules JPMS (ajout d'un module-info.java).");
        }
    }

    /** Résout le set complet de modules à inclure : modules user + JDK transitifs (via jdeps). */
    private String resolveAddModules() throws IOException {
        Path stage = modsDir.toPath();
        Set<String> userModules = new LinkedHashSet<>();
        for (var ref : ModuleFinder.of(stage).findAll()) {
            userModules.add(ref.descriptor().name());
        }
        if (!userModules.contains(mainModule)) {
            throw new IOException("mainModule '" + mainModule + "' introuvable parmi les modules stagés : " + userModules);
        }

        // jdeps --print-module-deps sur l'app jar, avec les jars stagés en module-path,
        // retourne la liste minimale de modules (incluant JDK) requis transitivement.
        ToolProvider jdeps = ToolProvider.findFirst("jdeps")
                .orElseThrow(() -> new IOException("jdeps tool non disponible dans ce JDK"));

        Path appJar = project.getArtifact().getFile().toPath();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int rc;
        try (var ps = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             var pe = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            rc = jdeps.run(ps, pe,
                    "--module-path", stage.toString(),
                    "--multi-release", "25",
                    "--ignore-missing-deps",
                    "--print-module-deps",
                    appJar.toString());
        }
        if (rc != 0) {
            getLog().warn("jdeps stderr: " + stderr.toString(StandardCharsets.UTF_8));
            throw new IOException("jdeps a échoué (rc=" + rc + ")");
        }
        String jdepsOutput = stdout.toString(StandardCharsets.UTF_8).trim();
        Set<String> all = new LinkedHashSet<>(userModules);
        if (!jdepsOutput.isEmpty()) {
            for (String m : jdepsOutput.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) all.add(t);
            }
        }
        return String.join(",", all);
    }

    private void runJlink(String addModules) throws IOException, InterruptedException {
        Path output = distDir.toPath();
        deleteRecursive(output);

        String javaHome = System.getProperty("java.home");
        String modulePath = modsDir.toPath() + File.pathSeparator + javaHome + "/jmods";

        List<String> args = new ArrayList<>();
        args.add("--module-path"); args.add(modulePath);
        args.add("--add-modules"); args.add(addModules);
        args.add("--launcher"); args.add(launcher + "=" + mainModule + "/" + mainClass);
        args.add("--output"); args.add(output.toString());
        args.add("--no-header-files");
        args.add("--no-man-pages");
        if (stripDebug) args.add("--strip-debug");
        if (compress != null && !compress.isBlank()) {
            args.add("--compress=" + compress);
        }

        ToolProvider jlink = ToolProvider.findFirst("jlink")
                .orElseThrow(() -> new IOException("jlink tool non disponible dans ce JDK"));
        getLog().info("Modules: " + addModules);
        int rc = jlink.run(System.out, System.err, args.toArray(new String[0]));
        if (rc != 0) {
            throw new IOException("jlink a échoué (rc=" + rc + ")");
        }
    }

    private void copyResources() throws IOException {
        List<String> names = (includeResources == null || includeResources.isEmpty())
                ? List.of("vidocq.properties", "logging.properties")
                : includeResources;
        Path conf = distDir.toPath().resolve("conf");
        Files.createDirectories(conf);
        for (String name : names) {
            Path src = classesDir.toPath().resolve(name);
            if (Files.isRegularFile(src)) {
                Files.copy(src, conf.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                getLog().info("Copied resource: " + name + " -> conf/");
            }
        }
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException e) { throw new RuntimeException(e); }
            });
        }
    }
}
