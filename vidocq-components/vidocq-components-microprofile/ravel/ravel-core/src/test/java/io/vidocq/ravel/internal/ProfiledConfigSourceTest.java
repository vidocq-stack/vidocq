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
package io.vidocq.ravel.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ProfiledConfigSource — §7.5 profile-aware view of a source")
class ProfiledConfigSourceTest {

    private static final String URL_KEY = "app.url";
    private static final String DEV_URL = "http://dev";

    private final ProfiledConfigSource source = new ProfiledConfigSource(MapConfigSource.of("app", 200, Map.of(
            "%dev." + URL_KEY, DEV_URL,
            "%prod." + URL_KEY, "http://prod",
            URL_KEY, "http://default")), "dev");

    @Test
    void properties_expose_only_the_active_profile_keys_without_their_prefix() {
        assertEquals(Map.of(URL_KEY, DEV_URL), source.getProperties());
        assertEquals(Set.of(URL_KEY), source.getPropertyNames());
    }

    @Test
    void value_lookup_reads_the_profiled_key() {
        assertEquals(DEV_URL, source.getValue(URL_KEY));
        assertNull(source.getValue("app.other"));
    }

    @Test
    void name_and_ordinal_derive_from_the_delegate() {
        assertEquals("ProfiledConfigSource[dev](app)", source.getName());
        assertEquals(201, source.getOrdinal());
    }
}
