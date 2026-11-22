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
package io.vidocq.runtime.config.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link KeyFiles}: the two files of a jar file, or of a directory of classes. */
class KeyFilesTest {

    private static final String WRITTEN = "format=1\nshop.mail.port|type=int\n";
    private static final String GENERATED = "format=1\nshop.mail.host|doc=The host.\n";

    @TempDir
    Path dir;

    /** A jar file named {@code name} with these entries, name then text, in UTF-8. */
    private Path jar(String name, String... entries) throws IOException {
        Path jar = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** A directory of classes named {@code name} with these files, path then text, in UTF-8. */
    private Path classes(String name, String... files) throws IOException {
        Path classes = Files.createDirectories(dir.resolve(name));
        for (int i = 0; i < files.length; i += 2) {
            Path file = classes.resolve(files[i]);
            Files.createDirectories(file.getParent());
            Files.writeString(file, files[i + 1]);
        }
        return classes;
    }

    private static Map<String, String> typesAndDocs(KeyDescriptions file) {
        Map<String, String> said = new LinkedHashMap<>();
        file.keys().forEach(key -> said.put(key.key().text(), key.type() + "/" + key.doc().orElse("")));
        return said;
    }

    @Test
    void a_jar_with_both_files_gives_both() throws IOException {
        Path jar = jar("shop.jar", KeyCatalog.WRITTEN, WRITTEN, "x/Y.class", "", KeyCatalog.GENERATED, GENERATED);

        KeyCatalog.Jar read = KeyFiles.of(jar);

        assertEquals(Map.of("shop.mail.port", "int/"), typesAndDocs(read.written()));
        assertEquals(Map.of("shop.mail.host", "string/The host."), typesAndDocs(read.generated()));
        assertEquals(List.of(), read.written().problems());
        assertTrue(KeyFiles.present(jar));
    }

    @Test
    void the_origin_of_a_fact_of_a_jar_is_the_name_of_the_jar_and_the_path_in_it() throws IOException {
        Path jar = jar("shop.jar", KeyCatalog.WRITTEN, WRITTEN, KeyCatalog.GENERATED, GENERATED);

        KeyCatalog.Jar read = KeyFiles.of(jar);

        assertEquals("shop.jar!/META-INF/vidocq/config-user-keys.properties", read.written().origin());
        assertEquals(new Fact("int", "shop.jar!/META-INF/vidocq/config-user-keys.properties", 2),
                read.written().keys().getFirst().fact(Attribute.TYPE).orElseThrow());
        assertEquals("shop.jar!/META-INF/vidocq/config-keys.properties", read.generated().origin());
    }

    @Test
    void a_jar_with_one_file_gives_it_and_nothing_for_the_other() throws IOException {
        Path written = jar("written.jar", KeyCatalog.WRITTEN, WRITTEN);
        Path generated = jar("generated.jar", KeyCatalog.GENERATED, GENERATED);

        assertEquals(1, KeyFiles.of(written).written().keys().size());
        assertSame(KeyDescriptions.none(), KeyFiles.of(written).generated());
        assertSame(KeyDescriptions.none(), KeyFiles.of(generated).written());
        assertEquals(1, KeyFiles.of(generated).generated().keys().size());
        assertTrue(KeyFiles.present(written));
        assertTrue(KeyFiles.present(generated));
    }

    @Test
    void a_jar_with_neither_gives_nothing() throws IOException {
        Path jar = jar("plain.jar", "x/Y.class", "", "META-INF/vidocq/extension.properties", "id=x\n");

        assertSame(KeyDescriptions.none(), KeyFiles.of(jar).written());
        assertSame(KeyDescriptions.none(), KeyFiles.of(jar).generated());
        assertFalse(KeyFiles.present(jar));
    }

    @Test
    void a_directory_of_classes_is_read_the_same_way_and_the_origin_is_the_path_of_the_file() throws IOException {
        Path classes = classes("classes", KeyCatalog.WRITTEN, WRITTEN, KeyCatalog.GENERATED, GENERATED);

        KeyCatalog.Jar read = KeyFiles.of(classes);

        assertEquals(Map.of("shop.mail.port", "int/"), typesAndDocs(read.written()));
        assertEquals(Map.of("shop.mail.host", "string/The host."), typesAndDocs(read.generated()));
        assertEquals(classes.resolve(KeyCatalog.WRITTEN).toString(), read.written().origin());
        assertEquals(classes.resolve(KeyCatalog.GENERATED).toString(), read.generated().origin());
        assertTrue(KeyFiles.present(classes));
    }

    @Test
    void a_directory_with_one_file_or_none() throws IOException {
        Path one = classes("one", KeyCatalog.GENERATED, GENERATED);
        Path none = classes("none", "x/Y.class", "");

        assertSame(KeyDescriptions.none(), KeyFiles.of(one).written());
        assertEquals(1, KeyFiles.of(one).generated().keys().size());
        assertTrue(KeyFiles.present(one));
        assertSame(KeyDescriptions.none(), KeyFiles.of(none).written());
        assertSame(KeyDescriptions.none(), KeyFiles.of(none).generated());
        assertFalse(KeyFiles.present(none));
    }

    @Test
    void a_path_that_does_not_exist_gives_nothing_and_no_problem() {
        Path missing = dir.resolve("missing.jar");

        assertSame(KeyDescriptions.none(), KeyFiles.of(missing).written());
        assertSame(KeyDescriptions.none(), KeyFiles.of(missing).generated());
        assertFalse(KeyFiles.present(missing));
    }

    @Test
    void a_jar_file_that_is_not_a_zip_gives_one_problem_that_names_it() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.jar"), "this is not a zip file");

        KeyCatalog.Jar read = KeyFiles.of(broken);

        assertEquals(List.of(), read.written().keys());
        assertSame(KeyDescriptions.none(), read.generated());
        assertEquals(1, read.written().problems().size(), read.written().problems().toString());
        Problem problem = read.written().problems().getFirst();
        assertEquals(broken.toString(), problem.origin());
        assertEquals(0, problem.line());
        assertEquals(Problem.Kind.WRONG, problem.kind());
        assertTrue(problem.message().startsWith("cannot be read as a jar"), problem.message());
        assertFalse(KeyFiles.present(broken));
    }

    @Test
    void a_file_that_is_not_a_jar_is_not_opened() throws IOException {
        Path pom = Files.writeString(dir.resolve("parent-1.0.pom"), "<project/>");
        Path library = Files.writeString(dir.resolve("libnative.so"), "not a zip either");

        for (Path file : List.of(pom, library)) {
            assertSame(KeyDescriptions.none(), KeyFiles.of(file).written(), file.toString());
            assertSame(KeyDescriptions.none(), KeyFiles.of(file).generated(), file.toString());
            assertFalse(KeyFiles.present(file), file.toString());
        }
    }

    @Test
    void a_jar_is_one_whatever_the_case_of_its_extension() throws IOException {
        assertTrue(KeyFiles.present(jar("SHOP.JAR", KeyCatalog.WRITTEN, WRITTEN)));
    }

    @Test
    void a_file_is_read_as_utf_8() throws IOException {
        String text = "format=1\nshop.mail.host|doc=L'hôte du courrier, « smtp » par défaut.\n";

        KeyCatalog.Jar fromJar = KeyFiles.of(jar("accent.jar", KeyCatalog.WRITTEN, text));
        KeyCatalog.Jar fromClasses = KeyFiles.of(classes("accent", KeyCatalog.WRITTEN, text));

        Optional<String> doc = Optional.of("L'hôte du courrier, « smtp » par défaut.");
        assertEquals(doc, fromJar.written().keys().getFirst().doc());
        assertEquals(doc, fromClasses.written().keys().getFirst().doc());
    }

    @Test
    void what_is_wrong_in_a_file_of_a_jar_is_a_problem_of_that_file() throws IOException {
        KeyCatalog.Jar read = KeyFiles.of(jar("shop.jar", KeyCatalog.WRITTEN, "format=1\nnot a fact\n"));

        Problem problem = read.written().problems().getFirst();
        assertEquals("shop.jar!/META-INF/vidocq/config-user-keys.properties", problem.origin());
        assertEquals(2, problem.line());
    }

    @Test
    void a_directory_named_like_a_file_is_no_file() throws IOException {
        Path classes = classes("odd");
        Files.createDirectories(classes.resolve(KeyCatalog.WRITTEN));

        KeyCatalog.Jar read = KeyFiles.of(classes);

        assertSame(KeyDescriptions.none(), read.written(), "a directory of that name is no file");
        assertFalse(KeyFiles.present(classes));
    }

    @Test
    void the_files_of_a_jar_make_a_catalog() throws IOException {
        Path jar = jar("shop.jar", KeyCatalog.WRITTEN, WRITTEN, KeyCatalog.GENERATED, GENERATED);
        Path classes = classes("classes", KeyCatalog.WRITTEN, "format=1\nshop.mail.port|doc=The port.\n");

        KeyCatalog catalog = KeyCatalog.of(List.of(KeyFiles.of(classes), KeyFiles.of(jar)));

        assertEquals(List.of(), catalog.problems());
        assertEquals(List.of("shop.mail.host", "shop.mail.port"),
                catalog.keys().stream().map(key -> key.key().text()).toList());
        assertEquals(List.of(), catalog.check("shop.mail.port", "25"));
        assertEquals(1, catalog.check("shop.mail.port", "smtp").size());
    }
}
