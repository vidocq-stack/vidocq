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
     * Builds the pom with an explicit runtime parent version — the released
     * {@code vidocq-runtime-parent} the generated project inherits from. An explicit
     * {@code --parent-version} always wins; a SNAPSHOT parent (dev build of the CLI)
     * triggers a warning because it will not resolve from Maven Central.
     */
    static String buildPom(Command.Create c, String runtimeVersion) {
        String parentVersion = c.parentVersion() != null ? c.parentVersion() : runtimeVersion;
        if (c.parentVersion() == null && parentVersion.endsWith("-SNAPSHOT")) {
            CliOutput.warning("Scaffolded parent version " + parentVersion
                    + " is a SNAPSHOT and will not resolve from Maven Central."
                    + " Use --parent-version <released-version> to override.");
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
                    <version>1.0.0-SNAPSHOT</version>
                    <name>%s</name>

                    <dependencies>
                        <dependency>
                            <groupId>io.vidocq.runtime</groupId>
                            <artifactId>vidocq-runtime-core</artifactId>
                        </dependency>
                %s
                    </dependencies>
                </project>
                """.formatted(parentVersion, c.groupId(), c.name(), c.name(),
                extensionDeps(c.extensions()));
    }

    private static String extensionDeps(Set<String> ids) {
        if (ids.isEmpty()) return "";
        var sb = new StringBuilder();
        for (String id : ids) {
            sb.append("        <dependency>\n")
              .append("            <groupId>io.vidocq.runtime</groupId>\n")
              .append("            <artifactId>vidocq-runtime-").append(id).append("-extension</artifactId>\n")
              .append("        </dependency>\n");
        }
        return sb.toString();
    }

    private static String buildModuleInfo(Command.Create c) {
        String moduleName = c.pkg().replace('-', '.');
        return """
                module %s {
                    requires io.vidocq.runtime.core;
                }
                """.formatted(moduleName);
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
