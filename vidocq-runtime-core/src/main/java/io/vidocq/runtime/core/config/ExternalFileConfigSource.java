package io.vidocq.runtime.core.config;

import io.vidocq.runtime.spi.config.ConfigSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Source that reads a file {@code vidocq.properties} <b>external</b> to the jar
 * application — typically {@code dist/conf/vidocq.properties} of an image
 * jlink/jpackage. Allows an operator to override the config without
 * recompiled.
 *
 * <h3>File search (first found wins)</h3>
 * <ol>
 *   <li>{@code -Dvidocq.config.dir=&lt;path&gt;} — explicit system property.</li>
 *   <li>{@code VIDOCQ_CONFIG_DIR=&lt;path&gt;} — environment variable (Docker / k8s).</li>
 *   <li>{@code ${java.home}/conf} — jlink standalone convention (the image runtime
 *       embeds {@code conf/} sibling of {@code bin/}).</li>
 *   <li>{@code ./conf} — working dir convention (manual launch).</li>
 * </ol>
 *
 * <p>Ordinal 250 — wins against {@link PropertiesFileConfigSource} (classpath, 100)
 * but remains dominated by environment variables (300) and properties
 * system (400), in accordance with the MicroProfile Config convention.</p>
 *
 * <p>If no file is found, the source is empty (effective ordinal without
 * effect).</p>
 */
public final class ExternalFileConfigSource implements ConfigSource {

    private static final System.Logger LOG =
            System.getLogger(ExternalFileConfigSource.class.getName());

    private static final String FILE_NAME = "vidocq.properties";

    private final Path resolvedFile;
    private final Properties properties;

    public ExternalFileConfigSource() {
        this.resolvedFile = locate();
        this.properties = load(resolvedFile);
    }

    @Override
    public String getName() {
        return resolvedFile == null
                ? "ExternalFile(absent)"
                : "ExternalFile(" + resolvedFile + ")";
    }

    @Override
    public int getOrdinal() {
        return 250;
    }

    @Override
    public String getValue(String key) {
        return properties.getProperty(key);
    }

    @Override
    public Set<String> getPropertyNames() {
        Set<String> names = new HashSet<>();
        for (Object k : properties.keySet()) {
            names.add(String.valueOf(k));
        }
        return Collections.unmodifiableSet(names);
    }

    private static Path locate() {
        String sysDir = System.getProperty("vidocq.config.dir");
        if (sysDir != null && !sysDir.isBlank()) {
            return resolveOrNull(Path.of(sysDir));
        }
        String envDir = System.getenv("VIDOCQ_CONFIG_DIR");
        if (envDir != null && !envDir.isBlank()) {
            return resolveOrNull(Path.of(envDir));
        }
        // jlink convention: ${java.home}/conf — the image runtime embeds
        // conf/ sibling of bin/.
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path candidate = Path.of(javaHome).resolve("conf");
            Path file = resolveOrNull(candidate);
            if (file != null) return file;
        }
        return resolveOrNull(Path.of("conf"));
    }

    private static Path resolveOrNull(Path dir) {
        if (dir == null) return null;
        Path file = dir.resolve(FILE_NAME);
        return Files.isRegularFile(file) ? file : null;
    }

    private static Properties load(Path file) {
        Properties props = new Properties();
        if (file == null) return props;
        try (InputStream is = Files.newInputStream(file)) {
            props.load(is);
            LOG.log(System.Logger.Level.INFO,
                    "Loaded external config from " + file + " (" + props.size() + " entries)");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to load external config " + file, e);
        }
        return props;
    }
}
