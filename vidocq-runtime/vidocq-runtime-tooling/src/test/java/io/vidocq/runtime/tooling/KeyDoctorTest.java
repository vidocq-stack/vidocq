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

import io.vidocq.runtime.config.keys.KeyCatalog;
import io.vidocq.runtime.config.keys.KeyDescriptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two checks of the doctor on the descriptions of the configuration keys, and the facts they read. */
class KeyDoctorTest {

    private static final String KEYS = "Key descriptions";
    private static final String VALUES = "Config values";
    private static final String WRITTEN = "META-INF/vidocq/config-user-keys.properties";
    private static final String GENERATED = "META-INF/vidocq/config-keys.properties";

    @TempDir
    Path dir;

    /** A file of format 1 named {@code origin}, with these lines: line 2 is the first of them. */
    private static KeyDescriptions file(String origin, String... lines) {
        return KeyDescriptions.read(origin, "format=1\n" + String.join("\n", lines));
    }

    /** The facts of a project whose jars ship these written files, none of them the project's own. */
    private static KeyDoctor.Facts facts(Map<String, String> config, KeyDescriptions... written) {
        return facts(config, Set.of(), written);
    }

    private static KeyDoctor.Facts facts(Map<String, String> config, Set<String> own, KeyDescriptions... written) {
        return facts(true, config, own, written);
    }

    /** The facts of a project whose jars could not be resolved: these files are its own. */
    private static KeyDoctor.Facts unresolved(Map<String, String> config, KeyDescriptions... own) {
        Set<String> origins = new HashSet<>();
        for (KeyDescriptions file : own) {
            origins.add(file.origin());
        }
        return facts(false, config, origins, own);
    }

    private static KeyDoctor.Facts facts(boolean resolved, Map<String, String> config, Set<String> own,
                                         KeyDescriptions... written) {
        List<KeyCatalog.Jar> jars = new ArrayList<>();
        for (KeyDescriptions file : written) {
            jars.add(new KeyCatalog.Jar(file, KeyDescriptions.none()));
        }
        return new KeyDoctor.Facts(config, KeyCatalog.of(jars), written.length, own, resolved);
    }

    /** The diagnostics of one check; none of the doctor's is ever a failure. */
    private static List<Diagnostic> check(KeyDoctor.Facts facts, String name) {
        List<Diagnostic> all = KeyDoctor.check(facts);
        assertTrue(all.stream().noneMatch(d -> d.status() == Diagnostic.Status.FAIL), all.toString());
        assertTrue(all.stream().noneMatch(Diagnostic::fixable), all.toString());
        return all.stream().filter(d -> d.name().equals(name)).toList();
    }

    private static List<String> details(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(Diagnostic::detail).toList();
    }

    private static Diagnostic only(List<Diagnostic> diagnostics) {
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        return diagnostics.getFirst();
    }

    // ---- check: the descriptions -----------------------------------------------------------------------------

    @Test
    void aProjectWhoseJarsDescribeNothingGetsTwoPassingChecks() {
        assertEquals(List.of(
                        Diagnostic.ok(KEYS, "no jar of the project describes its configuration keys"),
                        Diagnostic.ok(VALUES, "no described key in vidocq.properties")),
                KeyDoctor.check(facts(Map.of("vidocq.http.port", "eighty"))));
    }

    @Test
    void theFilesAndTheKeysTheyDescribeAreCounted() {
        KeyDoctor.Facts facts = facts(Map.of("other.key", "1"),
                file("a.jar!/keys", "shop.a|type=int", "shop.b|type=int"), file("b.jar!/keys", "shop.c|doc=C."));

        assertEquals(List.of(Diagnostic.ok(KEYS, "2 files, 3 keys described"),
                Diagnostic.ok(VALUES, "no described key in vidocq.properties")), KeyDoctor.check(facts));
    }

    @Test
    void oneFileAndOneKeyAreCountedInTheSingular() {
        assertEquals(Diagnostic.ok(KEYS, "1 file, 1 key described"),
                only(check(facts(Map.of(), file("a.jar!/keys", "shop.a|type=int")), KEYS)));
    }

    @Test
    void aFactOfALaterFormatInAJarIsCountedAndNotAWarning() {
        KeyDoctor.Facts facts = facts(Map.of(), file("a.jar!/keys", "shop.a|type=int", "shop.a|unit=seconds"));

        assertEquals(Diagnostic.ok(KEYS, "1 file, 1 key described, 1 fact of a later format skipped"),
                only(check(facts, KEYS)));
    }

    @Test
    void aFactTheReaderDoesNotKnowInAFileOfTheProjectIsAWarning() {
        KeyDoctor.Facts facts = facts(Map.of(), Set.of("src/keys"),
                file("src/keys", "shop.a|type=int", "shop.a|typo=x"), file("a.jar!/keys", "shop.b|unit=seconds"));

        Diagnostic warning = only(check(facts, KEYS));
        assertEquals(Diagnostic.Status.WARN, warning.status());
        assertEquals("src/keys:3 — 'typo' is not an attribute of format 1: the fact is skipped", warning.detail());
    }

    @Test
    void aWrongLineIsAWarningThatNamesTheFileAndTheLine() {
        KeyDescriptions file = file("a.jar!/keys", "shop.a|type=int", "shop.b|type=int", "shop.c|type=itn");

        Diagnostic warning = only(check(facts(Map.of(), file), KEYS));

        assertEquals(Diagnostic.Status.WARN, warning.status());
        assertEquals("a.jar!/keys:4 — " + file.problems().getFirst().message(), warning.detail());
        assertTrue(warning.hint().contains("config-user-keys.properties"), warning.hint());
    }

    @Test
    void aProblemThatIsNotOneLinesNamesTheFileOnly() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.jar"), "this is not a zip file");

        Diagnostic warning = only(check(KeyDoctor.read(dir, List.of(broken)), KEYS));

        assertEquals(Diagnostic.Status.WARN, warning.status());
        assertTrue(warning.detail().startsWith(broken + " — cannot be read as a jar"), warning.detail());
    }

    /** A jar that cannot be opened is not a description to correct: the remedy is the jar. */
    @Test
    void aJarThatCannotBeOpenedHasAHintOfItsOwn() throws IOException {
        Path broken = Files.writeString(dir.resolve("broken.jar"), "this is not a zip file");
        KeyDescriptions wrong = file("a.jar!/keys", "shop.c|type=itn");

        Diagnostic unopened = only(check(KeyDoctor.read(dir, List.of(broken)), KEYS));
        Diagnostic line = only(check(facts(Map.of(), wrong), KEYS));

        assertTrue(unopened.hint().contains("damaged"), unopened.hint());
        assertTrue(unopened.hint().contains("local Maven repository"), unopened.hint());
        assertFalse(unopened.hint().contains("config-user-keys.properties"), unopened.hint());
        assertTrue(line.hint().contains("config-user-keys.properties"), line.hint());
        assertFalse(line.hint().contains("damaged"), line.hint());
    }

    // ---- check: a project whose jars could not be resolved ---------------------------------------------------

    private static final String JARS_UNREAD = "the jars of the project could not be read: the keys they describe"
            + " are not known";
    private static final String VALUES_UNCHECKED = "the values were not checked against the jars of the project,"
            + " which could not be read";

    @Test
    void aProjectWhoseJarsCouldNotBeReadSaysSoInBothChecksAndNotThatNothingIsDescribed() {
        List<Diagnostic> all = KeyDoctor.check(unresolved(Map.of("vidocq.sample.port", "eighty")));

        assertEquals(List.of(JARS_UNREAD, VALUES_UNCHECKED), details(all));
        assertTrue(all.stream().allMatch(d -> d.status() == Diagnostic.Status.WARN), all.toString());
        assertTrue(all.stream().allMatch(d -> d.hint().contains("Extensions")), all.toString());
        assertEquals(List.of(KEYS, VALUES), all.stream().map(Diagnostic::name).toList());
    }

    @Test
    void theFilesOfTheProjectAreStillReadAndCheckedWhenItsJarsCouldNotBe() {
        KeyDescriptions own = file("src/keys", "shop.port|type=int", "shop.mode|values=fast,safe");

        List<Diagnostic> fits = KeyDoctor.check(unresolved(Map.of("shop.port", "80", "other", "x"), own));
        List<Diagnostic> refused = KeyDoctor.check(unresolved(Map.of("shop.port", "eighty"), own));

        assertEquals(List.of("1 file, 2 keys described", JARS_UNREAD, "1 described key, every value fits",
                VALUES_UNCHECKED), details(fits));
        assertEquals(List.of(Diagnostic.Status.OK, Diagnostic.Status.WARN, Diagnostic.Status.OK,
                Diagnostic.Status.WARN), fits.stream().map(Diagnostic::status).toList());
        assertEquals(List.of("1 file, 2 keys described", JARS_UNREAD, "shop.port=eighty — is not an int",
                VALUES_UNCHECKED), details(refused));
    }

    @Test
    void aWrongFileOfTheProjectIsStillReportedWhenItsJarsCouldNotBeRead() {
        KeyDescriptions own = file("src/keys", "shop.port|type=itn");

        List<String> details = details(check(unresolved(Map.of(), own), KEYS));

        assertEquals(2, details.size(), details.toString());
        assertTrue(details.get(0).startsWith("src/keys:2 — "), details.toString());
        assertEquals(JARS_UNREAD, details.get(1));
    }

    @Test
    void theExtensionsAToolFoundSayWhetherTheJarsWereRead() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "src/main/resources/" + WRITTEN, "format=1\nshop.mine|type=int\n");
        Path jar = jar("lib.jar", WRITTEN, "format=1\nlib.port|type=int\n");

        KeyDoctor.Facts found = KeyDoctor.read(project, DoctorFacts.Extensions.found(1, List.of(jar)));
        KeyDoctor.Facts failed = KeyDoctor.read(project, DoctorFacts.Extensions.failed("Maven exited with code 1"));

        assertTrue(found.resolved());
        assertEquals(List.of("lib.port", "shop.mine"), keys(found));
        assertFalse(failed.resolved());
        assertEquals(List.of("shop.mine"), keys(failed));
        assertEquals(1, failed.files());
        assertTrue(KeyDoctor.read(project, List.of(jar)).resolved());
    }

    @Test
    void tenProblemsAreShownAndTheRestIsCounted() {
        String[] lines = new String[12];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = "shop.k" + i + "|type=itn";
        }

        List<Diagnostic> warnings = check(facts(Map.of(), file("a.jar!/keys", lines)), KEYS);

        assertEquals(11, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().allMatch(d -> d.status() == Diagnostic.Status.WARN), warnings.toString());
        assertTrue(warnings.get(0).detail().startsWith("a.jar!/keys:2 — "), warnings.get(0).detail());
        assertTrue(warnings.get(9).detail().startsWith("a.jar!/keys:11 — "), warnings.get(9).detail());
        assertEquals("… and 2 more", warnings.get(10).detail());
        assertNull(warnings.get(10).hint());
    }

    @Test
    void theProblemsComeInTheOrderOfTheFilesAndOfTheirLines() {
        KeyDoctor.Facts facts = facts(Map.of(),
                file("b.jar!/keys", "shop.a|type=int", "shop.x|type=itn"),
                file("a.jar!/keys", "shop.y|type=itn", "shop.a|type=long"));

        List<String> details = details(check(facts, KEYS));

        assertEquals(3, details.size(), details.toString());
        assertTrue(details.get(0).startsWith("a.jar!/keys:2 — "), details.toString());
        assertTrue(details.get(1).startsWith("a.jar!/keys:3 — shop.a|type is 'long' here"), details.toString());
        assertTrue(details.get(2).startsWith("b.jar!/keys:3 — "), details.toString());
    }

    // ---- check: the values -----------------------------------------------------------------------------------

    @Test
    void theDescribedKeysWhoseValuesFitAreCounted() {
        KeyDescriptions file = file("a.jar!/keys", "shop.a|type=int", "shop.b|values=x,y", "shop.c|type=int");

        assertEquals(Diagnostic.ok(VALUES, "2 described keys, every value fits"),
                only(check(facts(Map.of("shop.a", "1", "shop.b", "X", "other", "?"), file), VALUES)));
        assertEquals(Diagnostic.ok(VALUES, "1 described key, every value fits"),
                only(check(facts(Map.of("shop.a", "1"), file), VALUES)));
    }

    @Test
    void aValueThatIsNotOfTheTypeIsAWarning() {
        Diagnostic warning = only(check(facts(Map.of("a.b", "eighty"), file("a.jar!/keys", "a.b|type=int")), VALUES));

        assertEquals(Diagnostic.Status.WARN, warning.status());
        assertEquals("a.b=eighty — is not an int", warning.detail());
        assertEquals("vidocq.properties gives the value; the key is described in a.jar!/keys:2", warning.hint());
    }

    @Test
    void aValueThatIsNotAllowedIsAWarning() {
        Diagnostic warning = only(check(facts(Map.of("a.b", "z"), file("a.jar!/keys", "a.b|values=x,y")), VALUES));

        assertEquals("a.b=z — is not one of x, y", warning.detail());
        assertTrue(warning.hint().endsWith("a.jar!/keys:2"), warning.hint());
    }

    @Test
    void aDeprecatedKeyIsAWarningThatGivesItsDocumentationAndNotItsValue() {
        KeyDescriptions file = file("a.jar!/keys", "a.b|doc=Use a.c.", "a.b|deprecated=true", "a.d|deprecated=true");

        List<Diagnostic> warnings = check(facts(Map.of("a.b", "secret", "a.d", "1"), file), VALUES);

        assertEquals(List.of("a.b — is deprecated. Use a.c.", "a.d — is deprecated"), details(warnings));
        assertTrue(warnings.getFirst().hint().endsWith("a.jar!/keys:3"), warnings.getFirst().hint());
    }

    @Test
    void aLongValueIsNotPrintedWhole() {
        String value = "x".repeat(40) + "y".repeat(20);

        Diagnostic warning = only(check(facts(Map.of("a.b", value), file("a.jar!/keys", "a.b|type=int")), VALUES));

        assertEquals("a.b=" + "x".repeat(40) + "… — is not an int", warning.detail());
    }

    @Test
    void aLongItemOfAListIsNotPrintedWholeEither() {
        String item = "x".repeat(60);

        Diagnostic warning = only(check(facts(Map.of("a.b", "1," + item),
                file("a.jar!/keys", "a.b|type=list:int")), VALUES));

        assertFalse(warning.detail().contains("x".repeat(41)), warning.detail());
        assertEquals("a.b=1," + "x".repeat(38) + "… — '" + "x".repeat(40) + "…' is not an int", warning.detail());
    }

    @Test
    void tenValuesAreShownInTheOrderOfTheKeysAndTheRestIsCounted() {
        Map<String, String> config = new LinkedHashMap<>();
        for (int i = 11; i >= 0; i--) {
            config.put("shop.k" + (char) ('a' + i), "x");
        }

        List<Diagnostic> warnings = check(facts(config, file("a.jar!/keys", "shop.<k>|type=int")), VALUES);

        assertEquals(11, warnings.size(), warnings.toString());
        assertEquals("shop.ka=x — is not an int", warnings.get(0).detail());
        assertEquals("shop.kj=x — is not an int", warnings.get(9).detail());
        assertEquals("… and 2 more", warnings.get(10).detail());
        assertEquals(Diagnostic.Status.WARN, warnings.get(10).status());
    }

    @Test
    void aValueWithTwoProblemsGivesTwoWarnings() {
        KeyDescriptions file = file("a.jar!/keys", "a.b|type=int", "a.b|values=1,2");

        assertEquals(List.of("a.b=three — is not an int", "a.b=three — is not one of 1, 2"),
                details(check(facts(Map.of("a.b", "three"), file), VALUES)));
    }

    // ---- read ------------------------------------------------------------------------------------------------

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

    private Path write(Path project, String path, String text) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    private static List<String> keys(KeyDoctor.Facts facts) {
        return facts.catalog().keys().stream().map(key -> key.key().text()).toList();
    }

    @Test
    void theWrittenFileOfTheProjectComesFromItsSourcesWhenItIsThere() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        Path source = write(project, "src/main/resources/" + WRITTEN, "format=1\nshop.fresh|type=int\n");
        write(project, "target/classes/" + WRITTEN, "format=1\nshop.stale|type=int\n");

        KeyDoctor.Facts facts = KeyDoctor.read(project, List.of());

        assertEquals(List.of("shop.fresh"), keys(facts));
        assertEquals(1, facts.files());
        assertEquals(Set.of(source.toString()), facts.own());
    }

    @Test
    void theWrittenFileOfTheProjectComesFromItsClassesOtherwise() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        Path built = write(project, "target/classes/" + WRITTEN, "format=1\nshop.built|type=int\n");

        KeyDoctor.Facts facts = KeyDoctor.read(project, List.of());

        assertEquals(List.of("shop.built"), keys(facts));
        assertEquals(Set.of(built.toString()), facts.own());
    }

    @Test
    void theGeneratedFileOfTheProjectComesFromItsClassesOnly() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "src/main/resources/" + WRITTEN, "format=1\nshop.written|type=int\n");
        write(project, "src/main/resources/" + GENERATED, "format=1\nshop.misplaced|type=int\n");
        write(project, "target/classes/" + GENERATED, "format=1\nshop.generated|type=int\n");

        KeyDoctor.Facts facts = KeyDoctor.read(project, List.of());

        assertEquals(List.of("shop.generated", "shop.written"), keys(facts));
        assertEquals(2, facts.files());
        assertEquals(2, facts.own().size());
    }

    @Test
    void aClassPathIsReadOnceWithoutTheClassesOfTheProjectAndWithoutWhatDoesNotExist() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "src/main/resources/" + WRITTEN, "format=1\nshop.mine|type=int\n");
        write(project, "target/classes/" + WRITTEN, "format=1\nshop.stale|type=int\n");
        Path jar = jar("lib.jar", WRITTEN, "format=1\nlib.port|type=int\n", GENERATED, "format=1\nlib.host|doc=H.\n");
        Path plain = jar("plain.jar", "x/Y.class", "");

        KeyDoctor.Facts facts = KeyDoctor.read(project, List.of(project.resolve("target/classes"), jar, plain,
                dir.resolve("app/../lib.jar"), dir.resolve("missing.jar"), jar));

        assertEquals(List.of("lib.host", "lib.port", "shop.mine"), keys(facts));
        assertEquals(List.of(), facts.catalog().problems());
        assertEquals(3, facts.files(), "the project's file and the two of the jar, each once");
        assertEquals(1, facts.own().size());
    }

    @Test
    void whatTheProjectWritesWinsOverWhatAJarSays() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "src/main/resources/" + WRITTEN, "format=1\nlib.port|doc=Mine.\n");
        Path jar = jar("lib.jar", WRITTEN, "format=1\nlib.port|doc=Theirs.\nlib.port|type=int\n");

        KeyDoctor.Facts facts = KeyDoctor.read(project, List.of(jar));

        assertEquals("Mine.", facts.catalog().describe("lib.port").orElseThrow().doc().orElseThrow());
        assertEquals("int", facts.catalog().describe("lib.port").orElseThrow().type());
    }

    @Test
    void aProjectWithoutAConfigFileHasAnEmptyConfig() throws IOException {
        KeyDoctor.Facts facts = KeyDoctor.read(Files.createDirectories(dir.resolve("app")), List.of());

        assertEquals(Map.of(), facts.config());
        assertEquals(0, facts.files());
        assertEquals(List.of(), keys(facts));
        assertEquals(Set.of(), facts.own());
    }

    @Test
    void theConfigIsReadTheWayTheRuntimeReadsIt() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "src/main/resources/vidocq.properties", String.join("\n",
                "# a comment", "shop.port = 8080", "shop.escaped=x\\,y", "shop.dollar=\\\\${x}",
                "shop.long=a,\\", "    b", "shop.empty=", ""));

        Map<String, String> config = KeyDoctor.read(project, List.of()).config();

        assertEquals(Map.of("shop.port", "8080", "shop.escaped", "x,y", "shop.dollar", "\\${x}",
                "shop.long", "a,b", "shop.empty", ""), config);
    }

    @Test
    void aConfigFileThatCannotBeReadIsAnEmptyConfig() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "vidocq.properties", "shop.port=\\u00zz\n");

        assertEquals(Map.of(), KeyDoctor.read(project, List.of()).config());
    }

    @Test
    void aProjectIsReadThenChecked() throws IOException {
        Path project = Files.createDirectories(dir.resolve("app"));
        write(project, "src/main/resources/vidocq.properties",
                "vidocq.sample.port=eighty\nvidocq.sample.mode=fast\nvidocq.sample.free=${X}\n");
        Path jar = jar("sample.jar", WRITTEN, String.join("\n", "format=1", "vidocq.sample.port|type=int",
                "vidocq.sample.mode|values=fast,safe", "vidocq.sample.free|type=int", ""));

        List<Diagnostic> diagnostics = KeyDoctor.check(KeyDoctor.read(project, List.of(jar)));

        assertEquals(List.of(Diagnostic.ok(KEYS, "1 file, 3 keys described").detail(),
                "vidocq.sample.port=eighty — is not an int"), details(diagnostics));
        assertFalse(diagnostics.get(1).hint().isBlank());
        assertTrue(diagnostics.get(1).hint().endsWith("sample.jar!/" + WRITTEN + ":2"), diagnostics.get(1).hint());
    }
}
