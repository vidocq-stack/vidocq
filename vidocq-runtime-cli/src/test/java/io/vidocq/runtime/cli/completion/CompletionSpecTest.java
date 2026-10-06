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
import io.vidocq.runtime.cli.ext.KnownExtensions;
import io.vidocq.runtime.cli.ext.RegistryEntry;
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
        assertEquals(List.of(CompletionSpec.FILES), after("start", "--config"));
        assertEquals(CommandCatalog.COMMANDS, after("help"));
        assertTrue(after("help", "build").isEmpty());
    }

    @Test
    void unknownCommandOffersNothing() {
        assertTrue(after("frobnicate").isEmpty());
    }

    @Test
    void everyOfferedOptionIsAcceptedByTheParser() {
        for (var context : CompletionSpec.contexts()) {
            for (String word : CompletionSpec.candidates(context)) {
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
