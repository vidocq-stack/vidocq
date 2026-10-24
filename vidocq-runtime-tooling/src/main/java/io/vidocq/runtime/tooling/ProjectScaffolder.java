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
package io.vidocq.runtime.tooling;


import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Generates a minimal Vidocq Maven project on disk from a {@link ProjectSpec}: {@code vidocq create} and
 * {@code vidocq:create} both run it, so they write the same project (Vidocq/vidocq#314).
 *
 * <pre>
 * &lt;name&gt;/
 *   pom.xml
 *   mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
 *   src/main/java/
 *     module-info.java
 *     &lt;package&gt;/
 *       &lt;ClassName&gt;.java        (--main only)
 *       HelloResource.java      (cassini-rest only)
 *   src/main/resources/
 *     vidocq.properties
 * </pre>
 *
 * <p>No application main class by default: the runtime's own main boots the application
 * from {@code vidocq.mainModule}, under {@code vidocq:dev}, {@code vidocq:run} and the
 * {@code vidocq:package} launchers alike. {@code --main} adds an {@code @VidocqMain}
 * trampoline and the {@code vidocq.mainClass} that names it.
 */
public final class ProjectScaffolder {

    /** Where the Vidocq SNAPSHOTs are published, on every build of {@code main}. */
    static final String SNAPSHOT_REPOSITORY_URL = "https://central.sonatype.com/repository/maven-snapshots/";

    /** The extension whose scaffold writes a {@code HelloResource}. */
    private static final String REST_EXTENSION = "cassini-rest";

    /** The version of a new application, which is its own and not the runtime's (BUG-20261024-06). */
    static final String APPLICATION_VERSION = "1.0.0-SNAPSHOT";

    /** Where the tooling jar holds the Maven wrapper of the Vidocq repository (BUG-20261024-08). */
    private static final String WRAPPER_RESOURCES = "/META-INF/vidocq/maven-wrapper/";

    /** The wrapper files, relative to the project root. */
    private static final List<String> WRAPPER_FILES =
            List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties");

    private ProjectScaffolder() {}

    /**
     * Writes the project {@code create} describes into {@code baseDir/<name>}. Its parent carries
     * {@code create.parentVersion()}, else {@code runtimeVersion}, the tool's own runtime version; from 0.4.0 the
     * parent's BOM versions the dependencies and codegen paths, which an older parent leaves to them.
     */
    public static void scaffold(ProjectSpec create, Path baseDir, String runtimeVersion, Reporter reporter)
            throws IOException {
        Path root     = baseDir.resolve(create.name());
        Path javaRoot = root.resolve("src/main/java");
        Path srcPkg   = javaRoot.resolve(packageToPath(create.pkg()));
        Path res      = root.resolve("src/main/resources");

        if (Files.exists(root)) {
            throw new IllegalStateException("Directory '" + create.name() + "' already exists.");
        }
        Files.createDirectories(srcPkg);
        Files.createDirectories(res);

        write(root.resolve("pom.xml"),                               buildPom(create, runtimeVersion, reporter));
        writeMavenWrapper(root);
        write(root.resolve(".gitignore"),                            GITIGNORE);
        write(root.resolve("README.md"),                             buildReadme(create));
        write(javaRoot.resolve("module-info.java"),                  buildModuleInfo(create, reporter));
        if (create.main()) {
            write(srcPkg.resolve(appClassName(create.name()) + ".java"), buildApp(create));
        }
        if (create.extensions().contains(REST_EXTENSION)) {
            write(srcPkg.resolve("HelloResource.java"),              buildHelloResource(create));
            String parentVersion = create.parentVersion() != null ? create.parentVersion() : runtimeVersion;
            if (parentPinsItsVersion(parentVersion)) {
                Path testPkg = root.resolve("src/test/java").resolve(packageToPath(create.pkg()));
                Files.createDirectories(testPkg);
                write(testPkg.resolve("HelloResourceTest.java"),     buildHelloResourceTest(create));
            } else {
                reporter.note("Parent version " + parentVersion + " predates 0.4.0: no first test, as @VidocqTest"
                        + " (vidocq-runtime-junit) comes with 0.4.0.");
            }
        }
        write(res.resolve("vidocq.properties"),                      buildProperties());
    }

    /**
     * Whether the scaffold writes a class into the application package. The module can only
     * open a package that holds one: an empty package compiles with a warning, then the JVM
     * refuses the module ("Package … not found in module").
     */
    private static boolean writesApplicationClass(ProjectSpec c) {
        return c.main() || c.extensions().contains(REST_EXTENSION);
    }

    // -------------------------------------------------------------------------
    // Template builders
    // -------------------------------------------------------------------------

    /**
     * Builds the pom with an explicit runtime parent version — the
     * {@code vidocq-runtime-parent} the generated project inherits from. An explicit
     * {@code --parent-version} always wins. A SNAPSHOT parent (explicit, or the
     * runtime of a CLI installed from a SNAPSHOT) is not on Maven Central: the pom
     * then declares the Central snapshot repository for dependencies and plugins.
     */
    static String buildPom(ProjectSpec c, String runtimeVersion, Reporter reporter) {
        String parentVersion = c.parentVersion() != null ? c.parentVersion() : runtimeVersion;
        boolean snapshot = parentVersion.endsWith("-SNAPSHOT");
        if (snapshot) {
            reporter.note("Parent version " + parentVersion + " is a SNAPSHOT: the project resolves"
                    + " Vidocq artifacts from " + SNAPSHOT_REPOSITORY_URL);
        }
        boolean ownVersion = parentPinsItsVersion(parentVersion);
        if (!ownVersion) {
            reporter.note("Parent version " + parentVersion + " predates 0.4.0: the project inherits its version,"
                    + " as the parent's dependencyManagement resolves at the project's version.");
        }
        // From 0.4.0 the parent imports vidocq-runtime-bom, which versions every Vidocq artifact (#339).
        String vidocqVersion = ownVersion ? null : parentVersion;
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>io.vidocq.runtime</groupId>
                        <artifactId>vidocq-runtime-parent</artifactId>
                        <version>%s</version>
                        <relativePath/>
                    </parent>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                %s    <name>%s</name>

                    <properties>
                        <vidocq.mainModule>%s</vidocq.mainModule>
                %s    </properties>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-core</artifactId>
                %s        </dependency>
                %s
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                                <executions>
                                    <execution>
                                        <goals>
                                            <goal>package</goal>
                                        </goals>
                                    </execution>
                                </executions>
                            </plugin>
                %s        </plugins>
                    </build>
                </project>
                """.formatted(parentVersion, c.groupId(), c.name(),
                ownVersion ? versionLine("    ", APPLICATION_VERSION) : "", c.name(),
                moduleName(c), c.main() ? mainClassProperty(c) : "",
                snapshot ? snapshotRepositories() : "", versionLine("            ", vidocqVersion),
                extensionDeps(c.extensions(), vidocqVersion) + (ownVersion ? testDeps(c) : ""),
                aptCodegenPlugin(c.extensions(), vidocqVersion));
    }

    /**
     * Whether the application can have a version of its own under {@code parentVersion}. Before 0.4.0 the parent's
     * dependencyManagement named the runtime version {@code ${project.version}}, which Maven interpolates in the
     * child, so an application of its own version asked for every runtime artifact at that version
     * (BUG-20261006-02). A version that does not start with {@code <major>.<minor>} is taken as a recent one.
     */
    static boolean parentPinsItsVersion(String parentVersion) {
        String[] parts = parentVersion.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > 0 || minor >= 4;
        } catch (NumberFormatException _) {
            return true;
        }
    }

    /** The {@code vidocq.mainClass} of a {@code --main} scaffold: its trampoline. */
    private static String mainClassProperty(ProjectSpec c) {
        return "        <vidocq.mainClass>" + c.pkg() + "." + appClassName(c.name()) + "</vidocq.mainClass>\n";
    }

    /**
     * Central snapshot repository, for dependencies and for the SNAPSHOT
     * {@code vidocq-runtime-maven-plugin}. Releases stay on Maven Central.
     */
    private static String snapshotRepositories() {
        return """

                    <repositories>
                        <repository>
                            <id>central-snapshots</id>
                            <url>%1$s</url>
                            <releases><enabled>false</enabled></releases>
                            <snapshots><enabled>true</enabled></snapshots>
                        </repository>
                    </repositories>

                    <pluginRepositories>
                        <pluginRepository>
                            <id>central-snapshots</id>
                            <url>%1$s</url>
                            <releases><enabled>false</enabled></releases>
                            <snapshots><enabled>true</enabled></snapshots>
                        </pluginRepository>
                    </pluginRepositories>
                """.formatted(SNAPSHOT_REPOSITORY_URL);
    }

    /**
     * Compiler plugin override wiring the APT codegen bundle of every selected
     * extension that ships one — {@code vidocq:checkpom} fails the build otherwise.
     * Appends to the parent's Vauban indexer entry.
     */
    private static String aptCodegenPlugin(Set<String> ids, String version) {
        var paths = new StringBuilder();
        for (String id : ids) {
            KnownExtensions.codegenBundle(id).ifPresent(coordinate -> paths
                    .append("                    <path>\n")
                    .append("                        <groupId>").append(coordinate.groupId()).append("</groupId>\n")
                    .append("                        <artifactId>").append(coordinate.artifactId()).append("</artifactId>\n")
                    .append(versionLine("                        ", version))
                    .append("                    </path>\n"));
        }
        if (paths.isEmpty()) return "";
        return """
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                            <annotationProcessorPaths combine.children="append">
        %s                </annotationProcessorPaths>
                        </configuration>
                    </plugin>
        """.formatted(paths.toString());
    }

    // Under a parent before 0.4.0 dependency versions are pinned explicitly: it names the runtime
    // version ${project.version} in its dependencyManagement, which re-evaluates to the
    // generated app's version and would not resolve (BUG-20261006-02). A null version writes none.
    private static String extensionDeps(Set<String> ids, String version) {
        if (ids.isEmpty()) return "";
        var sb = new StringBuilder();
        for (String id : ids) {
            var coordinate = KnownExtensions.resolve(id);
            sb.append("        <dependency>\n")
              .append("            <groupId>").append(coordinate.groupId()).append("</groupId>\n")
              .append("            <artifactId>").append(coordinate.artifactId()).append("</artifactId>\n")
              .append(versionLine("            ", version))
              .append("        </dependency>\n");
        }
        return sb.toString();
    }

    /**
     * The test dependencies, under a parent from 0.4.0, whose BOMs version them: JUnit, and {@code @VidocqTest} when
     * the scaffold writes a resource to test. Nothing goes into {@code module-info.java}: Surefire patches the tests
     * into the application module, which reads the class path.
     */
    private static String testDeps(ProjectSpec c) {
        String vidocqTest = c.extensions().contains(REST_EXTENSION) ? """
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-junit</artifactId>
                            <scope>test</scope>
                        </dependency>
                """ : "";
        return vidocqTest + """
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <scope>test</scope>
                        </dependency>
                """;
    }

    /**
     * The core requirement of the trampoline, then each selected extension's directives in
     * catalog order — the same {@link ModuleInfoEditor} edit {@code vidocq extension add}
     * makes, so a scaffolded extension and an added one end up declared alike. The application
     * package is opened only when the scaffold writes a class into it.
     */
    private static String buildModuleInfo(ProjectSpec c, Reporter reporter) {
        String source = c.main()
                ? """
                module %s {
                    requires io.vidocq.runtime.core;
                }
                """.formatted(moduleName(c))
                : """
                module %s {
                }
                """.formatted(moduleName(c));
        String openable = writesApplicationClass(c) ? c.pkg() : null;
        List<String> ordered = new ArrayList<>();
        KnownExtensions.catalog().forEach(e -> {
            if (c.extensions().contains(e.id())) {
                ordered.add(e.id());
            }
        });
        c.extensions().stream().filter(id -> !ordered.contains(id)).sorted().forEach(ordered::add);
        for (String id : ordered) {
            source = ModuleInfoEditor.add(source, id, KnownExtensions.moduleDirectives(id, openable)).source();
            if (openable == null && KnownExtensions.moduleDirectives(id, c.pkg()).contains("opens " + c.pkg())) {
                reporter.warning(id + " reflects on the application package, which has no class yet: add opens "
                        + c.pkg() + "; to module-info.java once it has one.");
            }
        }
        return source;
    }

    private static String moduleName(ProjectSpec c) {
        return c.pkg().replace('-', '.');
    }

    private static String buildApp(ProjectSpec c) {
        String className = appClassName(c.name());
        return """
                package %s;

                import io.vidocq.runtime.core.Vidocq;
                import io.vidocq.runtime.spi.VidocqMain;

                /**
                 * Entry point. {@code main} is a trampoline: {@code Vidocq.run} resolves the application
                 * into the Vauban layer and boots it there, from the IDE, {@code vidocq start} or a jlink
                 * image alike. Put no code before it: it would run outside that layer.
                 *
                 * <p>The runtime's own main boots the application without this class. Keep it for a
                 * right-click Run in the IDE, or for logic after boot (pass a {@code VidocqApp} to
                 * {@code Vidocq.run(Class, String...)}).
                 */
                @VidocqMain
                public final class %s {

                    public static void main(String[] args) {
                        Vidocq.run(args);
                    }
                }
                """.formatted(c.pkg(), className);
    }

    private static String buildHelloResource(ProjectSpec c) {
        return """
                package %s;

                import jakarta.enterprise.context.ApplicationScoped;
                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.Produces;
                import jakarta.ws.rs.core.MediaType;

                /** A first REST resource: {@code GET /hello}. */
                @ApplicationScoped
                @Path("/hello")
                @Produces(MediaType.TEXT_PLAIN)
                public class HelloResource {

                    @GET
                    public String hello() {
                        return "Hello from Vidocq!";
                    }
                }
                """.formatted(c.pkg());
    }

    /** The first test: {@code @VidocqTest} starts the application and the test calls the resource. */
    private static String buildHelloResourceTest(ProjectSpec c) {
        return """
                package %s;

                import io.vidocq.runtime.junit.VidocqApplication;
                import io.vidocq.runtime.junit.VidocqResponse;
                import io.vidocq.runtime.junit.VidocqTest;
                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                /** {@code @VidocqTest} starts the application for this class and stops it after the last test. */
                @VidocqTest
                class HelloResourceTest {

                    @Test
                    void saysHello(VidocqApplication application) {
                        VidocqResponse response = application.get("/hello");

                        assertEquals(200, response.status(), application.log());
                        assertEquals("Hello from Vidocq!", response.body());
                    }
                }
                """.formatted(c.pkg());
    }

    /** What a build or an IDE writes in the project, kept out of version control. */
    private static final String GITIGNORE = """
            # Build output
            target/

            # IntelliJ IDEA
            .idea/
            *.iml

            # Eclipse
            .classpath
            .project
            .settings/

            # VS Code
            .vscode/

            # macOS
            .DS_Store
            """;

    /** The commands that work on the created project, with the CLI and without it. */
    private static String buildReadme(ProjectSpec c) {
        boolean rest = c.extensions().contains(REST_EXTENSION);
        return """
                # %s

                A Vidocq application, created by `vidocq create`.

                ## With the Vidocq CLI

                | Command | What it does |
                |---|---|
                | `vidocq dev` | Runs the application and restarts it on every change, with the dev console |
                | `vidocq test` | Runs the tests on every change |
                | `vidocq build` | Builds the distribution under `target/` |
                | `vidocq build jlink` | Builds a self-contained runtime image |
                | `vidocq extension add <id>` | Adds an extension (`vidocq extension list --available`) |
                | `vidocq doctor` | Checks the machine and the project |

                ## With Maven alone

                | Command | What it does |
                |---|---|
                | `./mvnw vidocq:dev` | Runs the application and restarts it on every change |
                | `./mvnw test` | Runs the tests |
                | `./mvnw package` | Builds the distribution: `target/%s-<version>/bin/%s.sh` starts it |
                %s
                Configuration: `src/main/resources/vidocq.properties`. Documentation: https://doc.vidocq.dev/vidocq/
                """.formatted(c.name(), c.name(), c.name(), rest ? """

                Then:

                ```
                curl http://localhost:8080/hello
                ```
                """ : "");
    }

    private static String buildProperties() {
        return """
                # Vidocq application configuration

                # HTTP endpoint. vidocq.http.port is an alias for the listener named 'default';
                # vidocq.chappe.listener.<name>.port addresses any listener and wins over the alias.
                vidocq.http.port=8080
                """;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static String packageToPath(String pkg) {
        return pkg.replace('.', '/');
    }

    /**
     * Converts kebab/snake names to PascalCase ending in {@code App} once: "my-cool-app" → "MyCoolApp",
     * "shop" → "ShopApp" (BUG-20261024-09).
     */
    static String appClassName(String name) {
        String[] parts = name.split("[-_]");
        var sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) {
                sb.append(Character.toUpperCase(p.charAt(0)));
                if (p.length() > 1) sb.append(p.substring(1));
            }
        }
        if (!sb.toString().endsWith("App")) {
            sb.append("App");
        }
        return sb.toString();
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    /** A {@code <version>} line at {@code indent}, none for a {@code null} version. */
    private static String versionLine(String indent, String version) {
        return version == null ? "" : indent + "<version>" + version + "</version>\n";
    }

    /**
     * Writes the Maven wrapper the tooling jar carries, {@code mvnw} executable by its owner where the file system
     * has POSIX permissions: what {@code ./mvnw} needs, and what git records as an executable file.
     */
    private static void writeMavenWrapper(Path root) throws IOException {
        for (String file : WRAPPER_FILES) {
            Path target = root.resolve(file);
            Files.createDirectories(target.getParent());
            try (InputStream in = ProjectScaffolder.class.getResourceAsStream(WRAPPER_RESOURCES + file)) {
                if (in == null) {
                    throw new IOException(WRAPPER_RESOURCES + file + " is missing from the Vidocq tooling");
                }
                Files.copy(in, target);
            }
        }
        Path mvnw = root.resolve("mvnw");
        if (mvnw.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(mvnw));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(mvnw, permissions);
        }
    }
}
