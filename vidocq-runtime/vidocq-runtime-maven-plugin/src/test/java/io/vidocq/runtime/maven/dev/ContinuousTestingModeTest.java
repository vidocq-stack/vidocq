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
package io.vidocq.runtime.maven.dev;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #798: {@code vidocq.dev.continuousTesting} is {@code true}, {@code false} or {@code paused}. */
class ContinuousTestingModeTest {

    private static final String KEY = "vidocq.dev.continuousTesting";
    private static final Function<String, Optional<String>> NO_FILES = k -> Optional.empty();

    private static Function<String, Optional<String>> files(String value) {
        Map<String, String> map = Map.of(KEY, value);
        return k -> Optional.ofNullable(map.get(k));
    }

    @Test
    void eachValueInAnyCaseAndTrimmed() {
        assertEquals(ContinuousTesting.Mode.ON, ContinuousTesting.Mode.parse(KEY, "true"));
        assertEquals(ContinuousTesting.Mode.ON, ContinuousTesting.Mode.parse(KEY, " TRUE "));
        assertEquals(ContinuousTesting.Mode.OFF, ContinuousTesting.Mode.parse(KEY, "False"));
        assertEquals(ContinuousTesting.Mode.PAUSED, ContinuousTesting.Mode.parse(KEY, "PAUSED"));
    }

    @Test
    void anotherValueIsRejectedWithTheKeyTheValueAndTheChoices() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ContinuousTesting.Mode.parse(KEY, "sometimes"));
        assertEquals(KEY + " must be true, false or paused, not 'sometimes'", e.getMessage());
    }

    @Test
    void theExplicitValueThenTheFilesThenTheDefault() {
        assertEquals(ContinuousTesting.Mode.ON, ContinuousTesting.Mode.resolve(KEY, Optional.of("true"),
                files("false"), ContinuousTesting.Mode.PAUSED));
        assertEquals(ContinuousTesting.Mode.OFF, ContinuousTesting.Mode.resolve(KEY, Optional.empty(),
                files("false"), ContinuousTesting.Mode.PAUSED));
        assertEquals(ContinuousTesting.Mode.PAUSED, ContinuousTesting.Mode.resolve(KEY, Optional.of(" "),
                files(""), ContinuousTesting.Mode.PAUSED), "a blank value is no value");
        assertEquals(ContinuousTesting.Mode.OFF, ContinuousTesting.Mode.resolve(KEY, Optional.empty(), NO_FILES,
                ContinuousTesting.Mode.OFF));
    }

    @Test
    void onlyOffRunsNoTests() {
        assertTrue(ContinuousTesting.Mode.ON.watches());
        assertTrue(ContinuousTesting.Mode.PAUSED.watches());
        assertFalse(ContinuousTesting.Mode.OFF.watches());
    }
}
