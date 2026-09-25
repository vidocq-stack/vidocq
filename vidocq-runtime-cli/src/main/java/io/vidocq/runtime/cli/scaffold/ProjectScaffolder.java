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
package io.vidocq.runtime.cli.scaffold;

import io.vidocq.runtime.cli.CliOutput;
import io.vidocq.runtime.cli.Command;
import io.vidocq.runtime.cli.Version;
import io.vidocq.runtime.cli.ext.KnownExtensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Generates a minimal Vidocq Maven project on disk from a {@link Command.Create} descriptor.
 *
 * <pre>
 * &lt;name&gt;/
 *   pom.xml
 *   src/main/java/
 *     module-info.java
 *     &lt;package&gt;/
 *       &lt;ClassName&gt;App.java
 *   src/main/resources/
 *     vidocq.properties
 * </pre>
 */
public final class ProjectScaffolder {

    /** Where the Vidocq SNAPSHOTs are published, on every build of {@code main}. */
    static final String SNAPSHOT_REPOSITORY_URL = "https://central.sonatype.com/repository/maven-snapshots/";

    private ProjectScaffolder() {}

    public static void scaffold(Command.Create create) throws IOException {
        scaffold(create, Path.of(""));
    }

    public static void scaffold(Command.Create create, Path baseDir) throws IOException {
        Path root     = baseDir.resolve(create.name());
        Path javaRoot = root.resolve("src/main/java");
        Path srcPkg   = javaRoot.resolve(packageToPath(create.pkg()));
        Path res      = root.resolve("src/main/resources");

        if (Files.exists(root)) {
            throw new IllegalStateException("Directory '" + create.name() + "' already exists.");
        }
        Files.createDirectories(srcPkg);
        Files.createDirectories(res);

        write(root.resolve("pom.xml"),                               buildPom(create));
        write(javaRoot.resolve("module-info.java"),                  buildModuleInfo(create));
        write(srcPkg.resolve(appClassName(create.name()) + ".java"), buildApp(create));
        write(res.resolve("vidocq.properties"),                      buildProperties());
    }

    // -------------------------------------------------------------------------
    // Template builders
    // -------------------------------------------------------------------------

    private static String buildPom(Command.Create c) {
        return buildPom(c, Version.runtime());
    }

    /**
     * Builds the pom with an explicit runtime parent version — the
     * {@code vidocq-runtime-parent} the generated project inherits from. An explicit
     * {@code --parent-version} always wins. A SNAPSHOT parent (explicit, or the
     * runtime of a CLI installed from a SNAPSHOT) is not on Maven Central: the pom
     * then declares the Central snapshot repository for dependencies and plugins.
     */
    static String buildPom(Command.Create c, String runtimeVersion) {
        String parentVersion = c.parentVersion() != null ? c.parentVersion() : runtimeVersion;
        boolean snapshot = parentVersion.endsWith("-SNAPSHOT");
        if (snapshot) {
            CliOutput.info("Parent version " + parentVersion + " is a SNAPSHOT: the project resolves"
                    + " Vidocq artifacts from " + SNAPSHOT_REPOSITORY_URL);
        }
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
                    <name>%s</name>

                    <properties>
                        <vidocq.mainModule>%s</vidocq.mainModule>
                        <vidocq.mainClass>%s.%s</vidocq.mainClass>
                    </properties>
                %s
                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-core</artifactId>
                            <version>%s</version>
                        </dependency>
                %s
                    </dependencies>

                    <build>
                        <plugins>
                            <plugin>
                                <groupId>io.vidocq.runtime</groupId>
                                <artifactId>vidocq-runtime-maven-plugin</artifactId>
                                <configuration>
                                    <!-- vidocq:package 0.2.0 fails on an absent jvmArgs (BUG-20260710-01). -->
                                    <jvmArgs>-Dfile.encoding=UTF-8</jvmArgs>
                                </configuration>
                                <executions>
                                    <execution>
                                        <goals>
                                            <goal>package</goal>
                                        </goals>
                                        <configuration>
                                            <!-- vidocq:package 0.2.0 passes mainClass verbatim to the JVM
                                                 module option, which needs module/class (BUG-20260710-02). -->
                                            <mainClass>${vidocq.mainModule}/${vidocq.mainClass}</mainClass>
                                        </configuration>
                                    </execution>
                                </executions>
                            </plugin>
                %s        </plugins>
                    </build>
                </project>
                """.formatted(parentVersion, c.groupId(), c.name(), c.name(),
                moduleName(c), c.pkg(), appClassName(c.name()),
                snapshot ? snapshotRepositories() : "", parentVersion,
                extensionDeps(c.extensions(), parentVersion),
                aptCodegenPlugin(c.extensions(), parentVersion));
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
                    .append("                        <version>").append(version).append("</version>\n")
                    .append("                        <type>pom</type>\n")
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

    // Dependency versions are pinned explicitly: the released parent's
    // dependencyManagement uses ${project.version}, which re-evaluates to the
    // generated app's own version and would not resolve.
    private static String extensionDeps(Set<String> ids, String version) {
        if (ids.isEmpty()) return "";
        var sb = new StringBuilder();
        for (String id : ids) {
            var coordinate = KnownExtensions.resolve(id);
            sb.append("        <dependency>\n")
              .append("            <groupId>").append(coordinate.groupId()).append("</groupId>\n")
              .append("            <artifactId>").append(coordinate.artifactId()).append("</artifactId>\n")
              .append("            <version>").append(version).append("</version>\n")
              .append("        </dependency>\n");
        }
        return sb.toString();
    }

    private static String buildModuleInfo(Command.Create c) {
        String moduleName = moduleName(c);
        if (c.extensions().contains("cassini-rest")) {
            return """
                    module %s {
                        // APT-generated $$CassiniAdapter classes import @Generated (SOURCE retention).
                        requires static java.compiler;

                        requires jakarta.cdi;
                        requires jakarta.inject;
                        requires jakarta.ws.rs;
                        requires jakarta.json.bind;

                        requires io.vidocq.runtime.core;
                        requires io.vidocq.runtime.extensions.jakartaee.core.cassini;
                        requires io.vidocq.cassini.api;

                        // JAX-RS and JSON-B reflect on resource classes and payload types.
                        opens %s;
                    }
                    """.formatted(moduleName, c.pkg());
        }
        return """
                module %s {
                    requires io.vidocq.runtime.core;
                }
                """.formatted(moduleName);
    }

    private static String moduleName(Command.Create c) {
        return c.pkg().replace('-', '.');
    }

    private static String buildApp(Command.Create c) {
        String className = appClassName(c.name());
        return """
                package %s;

                import io.vidocq.runtime.core.VidocqBootstrap;

                public final class %s {

                    public static void main(String[] args) {
                        VidocqBootstrap.create()
                                .configure()
                                .start()
                                .awaitShutdown();
                    }
                }
                """.formatted(c.pkg(), className);
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
     * Converts kebab/snake names to PascalCase: "my-cool-app" → "MyCoolApp".
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
        sb.append("App");
        return sb.toString();
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }
}
