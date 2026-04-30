package io.vidocq.mpserver.maven;

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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;

/**
 * Empaquette une image runtime jlink existante en un installer natif via
 * {@code jpackage} : {@code .dmg}/{@code .pkg} (macOS), {@code .deb}/{@code .rpm}
 * (Linux), {@code .exe}/{@code .msi} (Windows), ou {@code app-image} (dossier
 * app cross-platform sans installer).
 *
 * <p>Réutilise l'image générée par le goal {@code vidocq:jlink} (par défaut
 * {@code target/dist/}) pour éviter de re-résoudre les modules.</p>
 *
 * <h3>Type par défaut</h3>
 * <p>{@code app-image} : produit un dossier app exécutable sans dépendre
 * d'outils natifs (pas de {@code dpkg}/{@code rpmbuild}/{@code pkgbuild}/
 * Wix). Pour un installer signé/distribuable, fixer explicitement
 * {@code &lt;type&gt;dmg|deb|rpm|msi|exe&lt;/type&gt;}.</p>
 */
@Mojo(name = "jpackage",
      defaultPhase = LifecyclePhase.PACKAGE,
      requiresDependencyResolution = ResolutionScope.RUNTIME)
public class VidocqJpackageMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "vidocq.mainModule", required = true)
    private String mainModule;

    @Parameter(property = "vidocq.mainClass", required = true)
    private String mainClass;

    /** Nom de l'application (binaire / installer). Défaut : {@code project.artifactId}. */
    @Parameter(defaultValue = "${project.artifactId}", property = "vidocq.appName")
    private String appName;

    /**
     * Version de l'application. Défaut : {@code project.version} mais sans suffix
     * {@code -SNAPSHOT} (jpackage exige une version numérique).
     */
    @Parameter(defaultValue = "${project.version}", property = "vidocq.appVersion")
    private String appVersion;

    /**
     * Type de package. Valeurs : {@code app-image} (défaut), {@code dmg}, {@code pkg},
     * {@code deb}, {@code rpm}, {@code msi}, {@code exe}.
     */
    @Parameter(defaultValue = "app-image", property = "vidocq.jpackageType")
    private String type;

    /**
     * Image runtime jlink en entrée. Défaut : {@code target/dist} (sortie du goal
     * {@code vidocq:jlink}).
     */
    @Parameter(defaultValue = "${project.build.directory}/dist", property = "vidocq.runtimeImage")
    private File runtimeImage;

    /** Répertoire de sortie. Défaut : {@code target/installer}. */
    @Parameter(defaultValue = "${project.build.directory}/installer", property = "vidocq.installerDir")
    private File installerDir;

    /** Icône (optionnelle). Format : {@code .icns} (macOS), {@code .ico} (Windows), {@code .png} (Linux). */
    @Parameter(property = "vidocq.icon")
    private File icon;

    /** Vendor (méta installer). */
    @Parameter(defaultValue = "${project.groupId}", property = "vidocq.vendor")
    private String vendor;

    /** Description courte (méta installer). Défaut : {@code project.description}. */
    @Parameter(defaultValue = "${project.description}", property = "vidocq.appDescription")
    private String description;

    @Override
    public void execute() throws MojoExecutionException {
        if (!runtimeImage.isDirectory()) {
            throw new MojoExecutionException(
                    "runtimeImage introuvable : " + runtimeImage
                            + ". Lance d'abord le goal vidocq:jlink (ou pointe runtimeImage sur une image jlink existante).");
        }

        String version = sanitizeVersion(appVersion);

        try {
            deleteRecursive(installerDir.toPath());
            Files.createDirectories(installerDir.toPath());

            List<String> args = new ArrayList<>();
            args.add("--type"); args.add(type);
            args.add("--name"); args.add(appName);
            args.add("--app-version"); args.add(version);
            args.add("--runtime-image"); args.add(runtimeImage.getAbsolutePath());
            args.add("--module"); args.add(mainModule + "/" + mainClass);
            args.add("--dest"); args.add(installerDir.getAbsolutePath());
            if (vendor != null && !vendor.isBlank())          { args.add("--vendor"); args.add(vendor); }
            if (description != null && !description.isBlank()) { args.add("--description"); args.add(description); }
            if (icon != null && icon.isFile())                { args.add("--icon"); args.add(icon.getAbsolutePath()); }

            getLog().info("Vidocq jpackage — type=" + type + " name=" + appName
                    + " version=" + version + " main=" + mainModule + "/" + mainClass);

            ToolProvider jpackage = ToolProvider.findFirst("jpackage")
                    .orElseThrow(() -> new MojoExecutionException(
                            "jpackage tool non disponible dans ce JDK"));
            int rc = jpackage.run(System.out, System.err, args.toArray(new String[0]));
            if (rc != 0) {
                throw new MojoExecutionException("jpackage a échoué (rc=" + rc + ")");
            }
            getLog().info("Installer/app produit dans : " + installerDir.getAbsolutePath());
        } catch (IOException e) {
            throw new MojoExecutionException("jpackage IO error", e);
        }
    }

    /** jpackage exige une version sans qualifiers ({@code 1.2.3}). On strip {@code -SNAPSHOT} et autres. */
    static String sanitizeVersion(String v) {
        if (v == null || v.isBlank()) return "0.0.0";
        String s = v.trim();
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        // Garde uniquement chiffres et points.
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c) || c == '.') out.append(c);
        }
        String cleaned = out.toString();
        return cleaned.isBlank() ? "0.0.0" : cleaned;
    }

    static String osShortName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac"))    return "macos";
        if (os.contains("win"))    return "windows";
        if (os.contains("linux"))  return "linux";
        return "unknown";
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
