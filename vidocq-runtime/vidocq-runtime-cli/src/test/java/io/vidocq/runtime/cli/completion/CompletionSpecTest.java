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
package io.vidocq.runtime.cli.completion;

import io.vidocq.runtime.cli.CliException;
import io.vidocq.runtime.cli.CliParser;
import io.vidocq.runtime.tooling.KnownExtensions;
import io.vidocq.runtime.tooling.RegistryEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionSpecTest {

    private static List<String> after(String... typed) {
        return CompletionSpec.candidates(List.of(typed));
    }

    @Test
    void firstWordIsACommand() {
        assertEquals(CommandCatalog.COMMANDS, after());
    }

    @Test
    void extensionOffersItsSubCommandsUnderEitherName() {
        assertEquals(List.of("list", "add", "remove"), after("extension"));
        assertEquals(List.of("list", "add", "remove"), after("ext"));
    }

    @Test
    void extensionListOffersItsFlags() {
        assertEquals(List.of("--installed", "--available", "--all", "--refresh"), after("extension", "list"));
        assertEquals(after("extension", "list"), after("ext", "ls", "--all"));
    }

    @Test
    void extensionAddOffersTheCatalogAndRemoveWhatThePomDeclares() {
        assertEquals(List.of(CompletionSpec.ADDABLE_EXTENSIONS), after("extension", "add"));
        assertEquals(List.of(CompletionSpec.ADDABLE_EXTENSIONS), after("extension", "add", "cassini-rest"));
        assertEquals(List.of(CompletionSpec.POM_EXTENSIONS), after("ext", "rm"));
    }

    @Test
    void createOffersExtensionIdsAfterItsExtensionOption() {
        List<String> ids = KnownExtensions.catalog().stream().map(RegistryEntry::id).toList();
        assertEquals(ids, after("create", "--name", "todo", "-x"));
        assertTrue(after("create").contains("--group-id"));
        assertTrue(after("create").contains("--main"));
        assertFalse(CompletionSpec.takesValue("--main"), "--main is a flag");
    }

    @Test
    void buildOffersItsTypesOnlyAsFirstArgument() {
        assertTrue(after("build").containsAll(List.of("package", "jlink", "jpackage", "docker", "--offline")));
        assertFalse(after("build", "jlink").contains("docker"));
        assertTrue(after("build", "jlink").contains("--skip-tests"));
    }

    @Test
    void configCompletesTheProjectKeys() {
        assertEquals(List.of("get", "set", "list"), after("config"));
        assertEquals(List.of(CompletionSpec.CONFIG_KEYS), after("config", "get"));
        assertTrue(after("config", "get", "vidocq.http.port").isEmpty());
    }

    @Test
    void completionOffersShellsAndSetup() {
        assertEquals(List.of("bash", "zsh", "install", "uninstall"), after("completion"));
        assertEquals(List.of("bash", "zsh"), after("completion", "install"));
        assertTrue(after("completion", "install", "zsh").isEmpty());
    }

    @Test
    void startConfigCompletesFilesAndHelpCompletesCommands() {
        assertEquals(List.of(CompletionSpec.FILES), after("run", "--config"));
        assertEquals(CommandCatalog.COMMANDS, after("help"));
        assertTrue(after("help", "build").isEmpty());
    }

    @Test
    void ideaOffersItsOptionsAndTheTwoKinds() {
        assertTrue(after().containsAll(List.of("test", "idea")));
        assertEquals(List.of("--check", "--strict", "--kind"), after("idea"));
        assertEquals(List.of("maven", "application"), after("idea", "--kind"));
        assertTrue(after().contains("vscode"));
        assertTrue(after().contains("eclipse"));
        assertEquals(List.of("--check"), after("vscode"));
        assertEquals(List.of("--check"), after("eclipse"));
        assertEquals(List.of("--once", "--maven-profile"), after("test"));
    }

    @Test
    void updateOffersItsChannelOptions() {
        assertEquals(List.of("--check", "--release", "--snapshot"), after("update"));
    }

    /** Vidocq/vidocq#782: {@code vidocq create -<TAB>} offers {@code -x} next to {@code --extension}. */
    @Test
    void aSingleDashOffersTheShortAliasesNextToTheLongOptions() {
        List<String> offered = CompletionSpec.candidates(List.of("create"), "-");

        assertTrue(offered.containsAll(List.of("-n", "-g", "-x")), offered.toString());
        assertTrue(offered.containsAll(after("create")), offered.toString());
        assertEquals(List.of("-x"), CompletionSpec.candidates(List.of("create", "--name", "todo"), "-x").stream()
                .filter(w -> w.startsWith("-x")).toList(), "a typed short alias completes to itself");
    }

    @Test
    void aDoubleDashOffersTheLongOptionsOnly() {
        assertEquals(after("create"), CompletionSpec.candidates(List.of("create"), "--"));
        assertTrue(after("create").stream().allMatch(w -> w.startsWith("--")), "nothing typed: long options only");
    }

    @Test
    void startAndDevOfferTheirShortAliases() {
        assertTrue(CompletionSpec.candidates(List.of("run"), "-").containsAll(List.of("-p", "-c", "--port")));
        assertTrue(CompletionSpec.candidates(List.of("dev"), "-").containsAll(List.of("-p", "-P", "--profile")));
        assertFalse(CompletionSpec.candidates(List.of("dev"), "-").contains("-c"), "-c is start's, not dev's");
        assertEquals(List.of(CompletionSpec.FILES), after("run", "-p", "8080", "-c"));
        assertTrue(after("dev", "-P").isEmpty(), "-P takes a free-form value");
    }

    @Test
    void unknownCommandOffersNothing() {
        assertTrue(after("frobnicate").isEmpty());
    }

    @Test
    void everyOfferedOptionIsAcceptedByTheParser() {
        for (var context : CompletionSpec.contexts()) {
            for (String word : CompletionSpec.candidates(context, "-")) {
                if (!word.startsWith("-") || word.equals("--")) {
                    continue;
                }
                List<String> args = new ArrayList<>(context);
                args.add(word);
                if (CompletionSpec.takesValue(word)) {
                    args.add(word.contains("port") || word.equals("-p") ? "8080" : "x");
                }
                try {
                    CliParser.parse(args.toArray(String[]::new));
                } catch (CliException e) {
                    assertFalse(e.getMessage().startsWith("Unknown option"),
                            "completion offers " + args + " but the parser rejects it: " + e.getMessage());
                }
            }
        }
    }
}
