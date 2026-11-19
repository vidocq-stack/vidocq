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
package io.vidocq.runtime.extensions.jakartaee.core.cassini;

import io.vidocq.cassini.spi.gen.ApplicationDescriptor;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationPathsTest {

    static class Api extends Application {}

    static class Other extends Application {}

    record Descriptor(Class<?> applicationClass, String applicationPath) implements ApplicationDescriptor {}

    @Test
    void theGeneratedDescriptorGivesThePathWithoutAnyBean() {
        assertEquals("/api", ApplicationPaths.resolve(List.of(new Descriptor(Api.class, "api")), null));
    }

    @Test
    void thePathIsNormalized() {
        assertEquals("/api/v1", ApplicationPaths.resolve(List.of(new Descriptor(Api.class, "/api/v1/")), null));
    }

    @Test
    void aRootPathMountsOnTheRoot() {
        assertNull(ApplicationPaths.resolve(List.of(new Descriptor(Api.class, "/")), null));
        assertNull(ApplicationPaths.resolve(List.of(new Descriptor(Api.class, "")), null));
    }

    @Test
    void noDescriptorAndNoBeanManagerMeansNoPath() {
        assertNull(ApplicationPaths.resolve(List.of(), null));
    }

    @Test
    void severalApplicationsResolveByClassNameWhateverTheirOrder() {
        Descriptor api = new Descriptor(Api.class, "api");
        Descriptor other = new Descriptor(Other.class, "other");

        assertEquals("/api", ApplicationPaths.resolve(List.of(other, api), null));
        assertEquals("/api", ApplicationPaths.resolve(List.of(api, other), null));
    }
}
