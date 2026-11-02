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
package io.vidocq.runtime.extensions.essentials.devconsole;

import io.vidocq.vauban.core.container.CodegenCoverage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** How the console writes a codegen verdict, every one Vauban knows today and any it adds later. */
class CodegenLabelTest {

    private static String label(CodegenCoverage.Verdict verdict) {
        return CdiInventory.codegen(new CodegenCoverage.Coverage(verdict, List.of()));
    }

    @Test
    void theKnownVerdictsKeepTheirLabels() {
        assertEquals("APT", label(CodegenCoverage.Verdict.APT));
        assertEquals("Class-File", label(CodegenCoverage.Verdict.CLASS_FILE));
        assertEquals("APT + Class-File", label(CodegenCoverage.Verdict.APT_AND_CLASS_FILE));
        assertEquals("partial", label(CodegenCoverage.Verdict.PARTIAL));
        assertEquals("reflection", label(CodegenCoverage.Verdict.REFLECTION));
        assertEquals("unknown", label(CodegenCoverage.Verdict.UNKNOWN));
        assertEquals("n/a", label(CodegenCoverage.Verdict.NOT_APPLICABLE));
    }

    /** A verdict a newer Vauban adds still gets a label, its name, instead of breaking the console. */
    @Test
    void everyVerdictHasALabel() {
        for (CodegenCoverage.Verdict verdict : CodegenCoverage.Verdict.values()) {
            assertFalse(label(verdict).isBlank(), verdict::name);
        }
    }
}
