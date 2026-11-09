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
package io.vidocq.mansart.data.tests;

import io.vidocq.mansart.data.core.Generated;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The name of the implementation the processor generates for a repository, as {@code MansartData} and the CDI
 * extension derive it from the interface (mansart#85): the binary name plus {@code Impl}, with {@code _} for the
 * {@code $} of a nested interface, so that a top-level generated class never carries a {@code $} in its name.
 */
class GeneratedImplementationNameTest {

    @Test
    void aTopLevelRepositoryGetsTheImplSuffix() {
        assertThat(Generated.implementationName("demo.Pets")).isEqualTo("demo.PetsImpl");
    }

    @Test
    void aNestedRepositoryTradesTheDollarForAnUnderscore() {
        assertThat(Generated.implementationName("demo.Kennel$Pets")).isEqualTo("demo.Kennel_PetsImpl");
        assertThat(Generated.implementationName("demo.Outer$Inner$Pets")).isEqualTo("demo.Outer_Inner_PetsImpl");
    }

    @Test
    void theDefaultPackageIsKept() {
        assertThat(Generated.implementationName("Kennel$Pets")).isEqualTo("Kennel_PetsImpl");
    }
}
