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
package io.vidocq.mansart.data.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the {@code generate-external-repositories} goal on a compiled fixture library: a {@code @Repository} in a
 * jar (one of them nested, one already generated), and checks what it generates, that the generated sources compile,
 * and the index it writes.
 */
class GenerateExternalRepositoriesMojoTest {

    private static final String PART = """
            package fixture.lib;
            @jakarta.persistence.Entity
            public class Part {
                @jakarta.persistence.Id @jakarta.persistence.GeneratedValue private Long id;
                private String name;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """;
    private static final String PART_REPOSITORY = """
            package fixture.lib;
            @jakarta.data.repository.Repository
            public interface PartRepository extends jakarta.data.repository.BasicRepository<Part, Long> {
                java.util.List<Part> findByName(String name);
            }
            """;
    private static final String CATALOG = """
            package fixture.lib;
            public final class Catalog {
                private Catalog() { }
                @jakarta.data.repository.Repository
                public interface Parts extends jakarta.data.repository.BasicRepository<Part, Long> { }
                public interface NotARepository { }
            }
            """;
    private static final String DONE_REPOSITORY = """
            package fixture.lib;
            @jakarta.data.repository.Repository
            public interface DoneRepository extends jakarta.data.repository.BasicRepository<Part, Long> { }
            """;
    private static final String TARGET = "app.gen";
    private static final String GENERATED = "generated";

    @TempDir
    Path dir;

    /** Compiles the fixture library into a jar with an index naming DoneRepository as already generated. */
    private Path fixtureJar() throws IOException {
        Path src = dir.resolve("src/fixture/lib");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Part.java"), PART);
        Files.writeString(src.resolve("PartRepository.java"), PART_REPOSITORY);
        Files.writeString(src.resolve("Catalog.java"), CATALOG);
        Files.writeString(src.resolve("DoneRepository.java"), DONE_REPOSITORY);
        Path classes = Files.createDirectories(dir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString(),
                "-classpath", System.getProperty("java.class.path")));
        try (Stream<Path> files = Files.list(src)) {
            files.map(Path::toString).forEach(args::add);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertThat(javac.run(null, null, null, args.toArray(String[]::new))).isZero();

        Path jar = dir.resolve("fixture.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(classes.relativize(p).toString().replace(File.separatorChar, '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
            jos.putNextEntry(new JarEntry("META-INF/mansart-repositories.list"));
            jos.write("# already generated\n\nfixture.lib.DoneRepository=fixture.lib.DoneRepositoryImpl\n"
                    .getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("broken/Broken.class"));
            jos.write(new byte[]{1, 2, 3});
            jos.closeEntry();
        }
        return jar;
    }

    private static List<String> testClassPath() {
        return Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    private GenerateExternalRepositoriesMojo mojo(List<String> classpath, String targetPackage, boolean skip)
            throws ReflectiveOperationException {
        MavenProject project = new MavenProject() {
            @Override
            public List<String> getCompileClasspathElements() {
                return classpath;
            }
        };
        project.setGroupId("org.example");
        project.setArtifactId("1st-app");
        var mojo = new GenerateExternalRepositoriesMojo();
        set(mojo, "project", project);
        set(mojo, "generatedSourcesDirectory", dir.resolve(GENERATED).toFile());
        set(mojo, "outputDirectory", dir.resolve("output").toFile());
        set(mojo, "targetPackage", targetPackage);
        set(mojo, "skip", skip);
        return mojo;
    }

    private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
        Field f = GenerateExternalRepositoriesMojo.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private List<String> generatedFiles() throws IOException {
        Path generated = dir.resolve(GENERATED);
        if (!Files.exists(generated)) return List.of();
        try (Stream<Path> walk = Files.walk(generated)) {
            return walk.filter(Files::isRegularFile).map(p -> generated.relativize(p).toString()).sorted().toList();
        }
    }

    @Test
    void generatesTheMissingImplementationsAndTheirIndex() throws Exception {
        List<String> classpath = new ArrayList<>(testClassPath());
        classpath.addFirst(fixtureJar().toString());
        var mojo = mojo(classpath, TARGET, false);

        mojo.execute();

        List<String> generated = generatedFiles();
        assertThat(generated).anyMatch(f -> f.startsWith("app/gen/") && f.contains("PartRepository"))
                .anyMatch(f -> f.contains("Parts"))
                .noneMatch(f -> f.contains("DoneRepository"))
                .noneMatch(f -> f.contains("NotARepository"));
        List<String> index = Files.readAllLines(
                dir.resolve("output/META-INF/mansart-repositories-external.list")).stream()
                .filter(l -> !l.startsWith("#")).toList();
        assertThat(index).hasSize(2).allMatch(l -> l.contains("=" + TARGET + "."))
                .anyMatch(l -> l.startsWith("fixture.lib.PartRepository="))
                .anyMatch(l -> l.startsWith("fixture.lib.Catalog.Parts="));

        List<String> args = new ArrayList<>(List.of("-proc:none", "-d", dir.resolve("check").toString(),
                "-classpath", String.join(File.pathSeparator, classpath)));
        for (String f : generated) args.add(dir.resolve(GENERATED).resolve(f).toString());
        assertThat(ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)))
                .as("the generated sources compile").isZero();
    }

    @Test
    void derivesTheTargetPackageFromTheProjectCoordinates() throws Exception {
        List<String> classpath = new ArrayList<>(testClassPath());
        classpath.addFirst(fixtureJar().toString());

        mojo(classpath, " ", false).execute();

        assertThat(generatedFiles()).isNotEmpty()
                .allMatch(f -> f.startsWith("org/example/_1st_app/mansart/generated/".replace('/', File.separatorChar)));
    }

    @Test
    void generatesNothingWithoutRepositoriesOrWhenSkipped() throws Exception {
        mojo(List.of(dir.resolve("missing").toString()), TARGET, false).execute();
        assertThat(generatedFiles()).isEmpty();

        List<String> classpath = new ArrayList<>(testClassPath());
        classpath.addFirst(fixtureJar().toString());
        mojo(classpath, TARGET, true).execute();
        assertThat(generatedFiles()).isEmpty();
        assertThat(dir.resolve("output")).doesNotExist();
    }

    @Test
    void scansDirectoriesAndTheirIndexes() throws Exception {
        fixtureJar();
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve("META-INF"));
        Files.writeString(classes.resolve("META-INF/mansart-repositories-external.list"),
                "fixture.lib.PartRepository=app.gen.PartRepositoryImpl\n");
        Files.write(classes.resolve("module-info.class"), new byte[]{0});
        Files.write(classes.resolve("Garbage.class"), new byte[]{0, 1});

        assertThat(RepositoryClassScanner.findRepositoryInterfaces(List.of(classes.toString())))
                .containsExactlyInAnyOrder("fixture.lib.PartRepository", "fixture.lib.Catalog.Parts",
                        "fixture.lib.DoneRepository");
        assertThat(RepositoryClassScanner.alreadyGenerated(List.of(classes.toString(), "missing.jar")))
                .containsExactly("fixture.lib.PartRepository");
    }

    @Test
    void failsWhenTheCodegenFails() throws Exception {
        List<String> classpath = new ArrayList<>(testClassPath());
        classpath.addFirst(fixtureJar().toString());
        var mojo = mojo(classpath, "not a package", false);

        assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
    }
}
