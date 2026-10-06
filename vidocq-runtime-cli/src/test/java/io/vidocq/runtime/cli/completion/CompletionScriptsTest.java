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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionScriptsTest {

    private static final List<String> CMDS = List.of("version", "info", "build");

    @Test
    void bashRegistersCompletionFunction() {
        String script = CompletionScripts.script(Shell.BASH, CMDS);
        assertTrue(script.contains("complete -F _vidocq vidocq"));
        assertTrue(script.contains("compgen -W"));
        assertTrue(script.contains("version info build"));
    }

    @Test
    void zshHasCompdefHeaderAndCommands() {
        String script = CompletionScripts.script(Shell.ZSH, CMDS);
        assertTrue(script.startsWith("#compdef vidocq"));
        assertTrue(script.contains("compdef _vidocq vidocq"));
        assertTrue(script.contains("(( $+functions[compdef] )) || { autoload -Uz compinit && compinit; }"),
                "sourcing the script before compinit must not fail on 'compdef'");
        assertTrue(script.contains("version info build"));
    }

    @Test
    void bothScriptsShareTheSpecFunction() {
        for (Shell shell : Shell.values()) {
            String script = CompletionScripts.script(shell, CMDS);
            assertTrue(script.contains("_vidocq_spec() {"), shell + " lacks _vidocq_spec");
            assertTrue(script.contains("extension) echo \"list add remove\" ;;"), shell + " lacks sub-commands");
            assertTrue(script.contains("\"extension list\") echo \"--installed --available --all --refresh\" ;;"));
            assertTrue(script.contains("\"create -x\") echo \"chappe-webserver "), shell + " lacks extension ids");
            assertTrue(script.contains("@pom-extensions)"), shell + " lacks the pom.xml lookup");
        }
    }

    @Test
    void shellFromToken() {
        assertEquals(Shell.BASH, Shell.fromToken("bash"));
        assertEquals(Shell.ZSH, Shell.fromToken("zsh"));
    }

    @Test
    void unknownShellThrows() {
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> Shell.fromToken("fish"));
        assertTrue(ex.getMessage().contains("fish"));
    }

    @Test
    void catalogStaysInStepWithKnownCommands() {
        // Guard against accidentally dropping a command from completion.
        assertTrue(CommandCatalog.COMMANDS.containsAll(
                List.of("build", "clean", "config", "completion", "extension")));
    }
}
