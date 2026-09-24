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
package io.vidocq.runtime.devservices.host;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServicesFlagTest {

    private static final Function<String, Optional<String>> NO_FILES = key -> Optional.empty();

    private static Function<String, Optional<String>> files(String value) {
        Map<String, String> map = Map.of(DevServicesFlag.KEY, value);
        return key -> Optional.ofNullable(map.get(key));
    }

    @Test
    void theDefaultAppliesWhenNoSourceHasTheKey() {
        assertTrue(DevServicesFlag.enabled(Optional.empty(), NO_FILES, true));
        assertFalse(DevServicesFlag.enabled(Optional.empty(), NO_FILES, false));
    }

    @Test
    void theFilesBeatTheDefault() {
        assertFalse(DevServicesFlag.enabled(Optional.empty(), files("false"), true));
        assertTrue(DevServicesFlag.enabled(Optional.empty(), files("true"), false));
    }

    @Test
    void anExplicitValueBeatsTheFilesBothWays() {
        assertFalse(DevServicesFlag.enabled(Optional.of("false"), files("true"), true));
        assertTrue(DevServicesFlag.enabled(Optional.of("true"), files("false"), false));
    }

    @Test
    void valuesAreTrimmedAndReadInAnyCase() {
        assertTrue(DevServicesFlag.enabled(Optional.of(" TRUE "), NO_FILES, false));
        assertFalse(DevServicesFlag.enabled(Optional.empty(), files("False\t"), true));
    }

    @Test
    void aBlankExplicitValueFallsThroughToTheFiles() {
        assertTrue(DevServicesFlag.enabled(Optional.of(" "), files("true"), false));
    }

    @Test
    void aValueThatIsNeitherTrueNorFalseIsRejectedWithTheKeyAndValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DevServicesFlag.enabled(Optional.empty(), files("${DEV}"), true));
        assertTrue(e.getMessage().contains(DevServicesFlag.KEY), e.getMessage());
        assertTrue(e.getMessage().contains("${DEV}"), e.getMessage());
    }
}
