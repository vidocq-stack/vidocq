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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellSetupTest {

    private static final Path HOME = Path.of("/home/me");

    @Test
    void detectsTheShellFromTheShellVariable() {
        assertEquals(Shell.ZSH, ShellSetup.detect("/bin/zsh"));
        assertEquals(Shell.BASH, ShellSetup.detect("/opt/homebrew/bin/bash"));
    }

    @Test
    void unknownOrMissingShellIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ShellSetup.detect("/usr/bin/fish"));
        assertThrows(IllegalArgumentException.class, () -> ShellSetup.detect(null));
    }

    @Test
    void eachShellHasItsRcFileAndScriptFile() {
        assertEquals(HOME.resolve(".zshrc"), ShellSetup.rcFile(Shell.ZSH, HOME));
        assertEquals(HOME.resolve(".bashrc"), ShellSetup.rcFile(Shell.BASH, HOME));
        assertEquals(HOME.resolve(".vidocq/completion/vidocq.zsh"), ShellSetup.scriptFile(Shell.ZSH, HOME));
    }

    @Test
    void appendsTheBlockAfterExistingContent() {
        String rc = ShellSetup.withBlock("export FOO=1\n", HOME.resolve(".vidocq/completion/vidocq.zsh"));

        assertTrue(rc.startsWith("export FOO=1\n"));
        assertTrue(rc.contains(ShellSetup.BEGIN));
        assertTrue(rc.contains(". \"/home/me/.vidocq/completion/vidocq.zsh\""));
        assertTrue(rc.endsWith(ShellSetup.END + "\n"));
    }

    @Test
    void addsTheMissingNewlineBeforeTheBlock() {
        String rc = ShellSetup.withBlock("export FOO=1", HOME.resolve("s"));

        assertTrue(rc.startsWith("export FOO=1\n"));
    }

    @Test
    void installingTwiceKeepsASingleBlock() {
        Path script = HOME.resolve(".vidocq/completion/vidocq.zsh");
        String once = ShellSetup.withBlock("export FOO=1\n", script);

        assertEquals(once, ShellSetup.withBlock(once, script));
    }

    @Test
    void reinstallingReplacesTheBlockInPlace() {
        String rc = ShellSetup.withBlock("a\n", HOME.resolve("old")) + "b\n";

        String updated = ShellSetup.withBlock(rc, HOME.resolve("new"));

        assertFalse(updated.contains("old"));
        assertTrue(updated.startsWith("a\n" + ShellSetup.BEGIN));
        assertTrue(updated.endsWith(ShellSetup.END + "\nb\n"));
    }

    @Test
    void uninstallingRemovesOnlyTheBlock() {
        String rc = ShellSetup.withBlock("a\n", HOME.resolve("s")) + "b\n";

        assertEquals("a\nb\n", ShellSetup.withoutBlock(rc));
        assertEquals("a\n", ShellSetup.withoutBlock("a\n"));
    }
}
