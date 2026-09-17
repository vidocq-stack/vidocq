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
package io.vidocq.runtime.core.banner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What can be known about the build of one Vidocq brick or application archive. Every component
 * but {@code exploded} may be {@code null}; the factories never throw.
 *
 * <p>Everything is read <strong>inside the archive that holds the module or the class</strong>
 * (a jar, an exploded directory or a runtime image), never through a class-loader-wide lookup: on
 * the class path every archive shares one unnamed module, and a global lookup of a common
 * resource name returns the first jar's file for every brick. The version comes from, in order:
 * <ol>
 *   <li>{@link java.lang.module.ModuleDescriptor#rawVersion()} of a named module (Maven passes
 *       {@code --module-version});</li>
 *   <li>{@code git.build.version} of {@value #BUILD_INFO_DIR}{@code <artifactId>.properties},
 *       written by {@code git-commit-id-maven-plugin} as configured in {@code vidocq-parent};</li>
 *   <li>{@code version} of {@code META-INF/maven/<groupId>/<artifactId>/pom.properties}.</li>
 * </ol>
 *
 * @param module      the module name, or {@code null} on the class path
 * @param version     the version, or {@code null} when unknown
 * @param commit      the abbreviated commit id
 * @param dirty       whether the working tree had uncommitted changes when it was built
 * @param builtAt     when it was built (absent in releases, which stay reproducible)
 * @param committedAt when its commit was made
 * @param location    the archive, or {@code null} when unknown
 * @param exploded    whether the archive is a directory of classes (an IDE or {@code target/classes})
 * @param fileDatedAt the modification time of the jar file
 * @param artifactId  the Maven artifactId of {@code pom.properties}
 */
public record BuildInfo(String module, String version, String commit, Boolean dirty, Instant builtAt,
                        Instant committedAt, URI location, boolean exploded, Instant fileDatedAt,
                        String artifactId) {

    /** Directory of the per-artifact build identity files. */
    public static final String BUILD_INFO_DIR = "META-INF/vidocq/build-info/";

    private static final String MAVEN_DIR = "META-INF/maven/";
    private static final String POM_PROPERTIES = "/pom.properties";

    /** The build identity file of {@code artifactId}: one name per artifact, so class-path jars never collide. */
    public static String resource(String artifactId) {
        return BUILD_INFO_DIR + artifactId + ".properties";
    }

    /** What is known about the archive of {@code anchor}: its named module, or its class-path code source. */
    public static BuildInfo ofClass(Class<?> anchor) {
        if (anchor.getModule().isNamed()) {
            return of(anchor.getModule());
        }
        try {
            CodeSource source = anchor.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return unknown(null);
            }
            return read(null, null, source.getLocation().toURI());
        } catch (Exception e) {
            return unknown(null);
        }
    }

    /** What is known about a named module, read in the archive its layer resolved it from. */
    public static BuildInfo of(Module module) {
        String name = module.getName();
        if (name == null) {
            return unknown(null);
        }
        try {
            String rawVersion = module.getDescriptor() == null ? null
                    : module.getDescriptor().rawVersion().orElse(null);
            ModuleLayer layer = module.getLayer();
            Optional<ModuleReference> reference = layer == null ? Optional.empty()
                    : layer.configuration().findModule(name).map(ResolvedModule::reference);
            URI location = reference.flatMap(ModuleReference::location).orElse(null);
            if (location != null && "file".equalsIgnoreCase(location.getScheme())) {
                return read(name, rawVersion, location);
            }
            if (reference.isPresent()) {
                // a runtime image (jrt:) or another reader: list through the module reader
                try (ModuleReader reader = reference.get().open()) {
                    return read(name, rawVersion, location, new ReaderArchive(reader));
                }
            }
            return read(name, rawVersion, location, Archive.NONE);
        } catch (IOException | RuntimeException e) {
            return unknown(name);
        }
    }

    /** What is known about the archive at {@code location} ({@code file:} jar or directory). */
    static BuildInfo read(String module, String rawVersion, URI location) {
        try {
            Path path = Path.of(location);
            if (Files.isDirectory(path)) {
                return read(module, rawVersion, location, new DirectoryArchive(path));
            }
            if (Files.isRegularFile(path)) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    return read(module, rawVersion, location, new ZipArchive(zip, path));
                }
            }
            return read(module, rawVersion, location, Archive.NONE);
        } catch (IOException | RuntimeException e) {
            return new BuildInfo(module, real(rawVersion), null, null, null, null, location, false, null, null);
        }
    }

    private static BuildInfo read(String module, String rawVersion, URI location, Archive archive) throws IOException {
        List<String> buildInfos = archive.names(BUILD_INFO_DIR).stream()
                .filter(n -> n.endsWith(".properties") && n.indexOf('/', BUILD_INFO_DIR.length()) < 0)
                .toList();
        List<String> poms = archive.names(MAVEN_DIR).stream()
                .filter(n -> n.endsWith(POM_PROPERTIES) && n.split("/").length == 5)
                .toList();
        // one artifact per archive; a shaded archive is disambiguated by the other file's name
        String pomEntry = poms.size() == 1 ? poms.getFirst() : null;
        String buildInfoEntry = buildInfos.size() == 1 ? buildInfos.getFirst() : null;
        if (buildInfoEntry == null && pomEntry != null && buildInfos.contains(resource(artifactIdOf(pomEntry)))) {
            buildInfoEntry = resource(artifactIdOf(pomEntry));
        }
        if (pomEntry == null && buildInfoEntry != null) {
            String artifactId = buildInfoEntry.substring(BUILD_INFO_DIR.length(),
                    buildInfoEntry.length() - ".properties".length());
            pomEntry = poms.stream().filter(p -> artifactIdOf(p).equals(artifactId)).findFirst().orElse(null);
        }
        Properties build = load(archive, buildInfoEntry);
        Properties pom = load(archive, pomEntry);

        String version = real(rawVersion);
        if (version == null) {
            version = real(build.getProperty("git.build.version"));
        }
        if (version == null) {
            version = real(pom.getProperty("version"));
        }
        return new BuildInfo(module, version, real(build.getProperty("git.commit.id.abbrev")),
                bool(build.getProperty("git.dirty")), instant(build.getProperty("git.build.time")),
                instant(build.getProperty("git.commit.time")), location, archive.directory(),
                archive.directory() ? null : archive.dated(), real(pom.getProperty("artifactId")));
    }

    /** Nothing known but the module name. */
    static BuildInfo unknown(String module) {
        return new BuildInfo(module, null, null, null, null, null, null, false, null, null);
    }

    /** Whether the version is unknown or a {@code -SNAPSHOT}. */
    public boolean snapshot() {
        return version == null || version.endsWith("-SNAPSHOT");
    }

    /** Whether the build is known to come from a modified working tree. */
    public boolean isDirty() {
        return Boolean.TRUE.equals(dirty);
    }

    /**
     * The version, then the details between parentheses when there are any:
     * {@code 0.3.0}, {@code 0.4.0-SNAPSHOT (9beafc47+dirty, built 2026-09-17T14:02:11Z)},
     * {@code version unknown (classes in vidocq-runtime-core/target/classes)}.
     */
    public String describe() {
        return describe(true);
    }

    /**
     * {@link #describe()}, optionally without the {@code last Maven build} label of a versioned
     * directory, whose {@code ?} already says the version may not be the one running.
     */
    public String describe(boolean lastMavenBuildLabel) {
        String text = version == null ? "version unknown" : exploded ? version + "?" : version;
        String details = details(lastMavenBuildLabel);
        return details == null ? text : text + " (" + details + ")";
    }

    /**
     * The details of {@link #describe()} without the parentheses, or {@code null} when there are none.
     *
     * <p>A directory of classes shows where the classes come from; a version found there is only
     * that of the last Maven build (an IDE recompiles without it, a stale directory keeps an old
     * one), and its build identity file may predate the classes. A jar shows its commit and date
     * only when it is a snapshot or was built from a modified tree: a clean release is its version.
     */
    public String details() {
        return details(true);
    }

    private String details(boolean lastMavenBuildLabel) {
        List<String> details = new ArrayList<>();
        if (exploded && location != null) {
            if (version != null && lastMavenBuildLabel) {
                details.add("last Maven build");
            }
            details.add("classes in " + lastSegments(location, 3));
        } else if (snapshot() || isDirty()) {
            if (commit != null) {
                details.add(commit + (isDirty() ? "+dirty" : ""));
            }
            if (builtAt != null) {
                details.add("built " + builtAt);
            } else if (committedAt != null) {
                details.add("committed " + committedAt);
            } else if (fileDatedAt != null) {
                details.add("jar dated " + fileDatedAt);
            }
        }
        return details.isEmpty() ? null : String.join(", ", details);
    }

    /** The last {@code count} segments of a {@code file:} location, {@code /}-separated. */
    static String lastSegments(URI location, int count) {
        try {
            Path path = Path.of(location);
            int n = path.getNameCount();
            Path tail = n <= count ? path : path.subpath(n - count, n);
            return tail.toString().replace('\\', '/');
        } catch (RuntimeException notAFileUri) {
            return location.toString();
        }
    }

    /** {@code null} for an absent, blank or {@code unknown} value and for an unfiltered {@code ${...}} placeholder. */
    static String real(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.isEmpty() || v.startsWith("${") || v.equalsIgnoreCase("unknown") ? null : v;
    }

    static Boolean bool(String value) {
        String v = real(value);
        if (v == null) {
            return null;
        }
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** An ISO-8601 date-time with an offset, as UTC to the second; {@code null} when absent or invalid. */
    static Instant instant(String value) {
        String v = real(value);
        if (v == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v).toInstant().truncatedTo(ChronoUnit.SECONDS);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String artifactIdOf(String pomEntry) {
        String[] segments = pomEntry.split("/");
        return segments.length >= 2 ? segments[segments.length - 2] : "";
    }

    private static Properties load(Archive archive, String entry) {
        Properties properties = new Properties();
        if (entry == null) {
            return properties;
        }
        try (InputStream in = archive.open(entry)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException | RuntimeException unreadable) {
            // an unreadable file is an absent one
            properties.clear();
        }
        return properties;
    }

    /** The entries of one archive, whatever its form. */
    private interface Archive {

        Archive NONE = new Archive() {
            @Override
            public List<String> names(String prefix) {
                return List.of();
            }

            @Override
            public InputStream open(String name) {
                return null;
            }
        };

        /** The file entry names under {@code prefix}, {@code /}-separated. */
        List<String> names(String prefix) throws IOException;

        /** The entry, or {@code null} when absent. */
        InputStream open(String name) throws IOException;

        default boolean directory() {
            return false;
        }

        default Instant dated() {
            return null;
        }
    }

    private record DirectoryArchive(Path root) implements Archive {

        @Override
        public List<String> names(String prefix) throws IOException {
            Path start = root.resolve(prefix);
            if (!Files.isDirectory(start)) {
                return List.of();
            }
            try (Stream<Path> files = Files.walk(start, 4)) {
                return files.filter(Files::isRegularFile)
                        .map(p -> root.relativize(p).toString().replace('\\', '/'))
                        .sorted()
                        .toList();
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }

        @Override
        public InputStream open(String name) throws IOException {
            Path file = root.resolve(name);
            return Files.isRegularFile(file) ? Files.newInputStream(file) : null;
        }

        @Override
        public boolean directory() {
            return true;
        }
    }

    private record ZipArchive(ZipFile zip, Path path) implements Archive {

        @Override
        public List<String> names(String prefix) {
            return zip.stream()
                    .filter(e -> !e.isDirectory() && e.getName().startsWith(prefix))
                    .map(ZipEntry::getName)
                    .sorted()
                    .toList();
        }

        @Override
        public InputStream open(String name) throws IOException {
            ZipEntry entry = zip.getEntry(name);
            return entry == null ? null : zip.getInputStream(entry);
        }

        @Override
        public Instant dated() {
            try {
                return Files.getLastModifiedTime(path).toInstant().truncatedTo(ChronoUnit.SECONDS);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    private record ReaderArchive(ModuleReader reader) implements Archive {

        @Override
        public List<String> names(String prefix) throws IOException {
            try (Stream<String> names = reader.list()) {
                return names.filter(n -> n.startsWith(prefix) && !n.endsWith("/")).sorted().toList();
            }
        }

        @Override
        public InputStream open(String name) throws IOException {
            return reader.open(name).orElse(null);
        }
    }
}
