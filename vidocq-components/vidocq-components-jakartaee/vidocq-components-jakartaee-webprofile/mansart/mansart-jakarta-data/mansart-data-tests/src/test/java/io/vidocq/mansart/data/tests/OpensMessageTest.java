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

import io.vidocq.mansart.data.core.MansartDataException;
import io.vidocq.mansart.data.core.RuntimeEntityModelBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The run-time error raised when Mansart cannot reach an entity package of a named module prints the exact
 * {@code opens} line to write, and says the compile-time path needs none. {@code java.util.ArrayList} stands for
 * a class of a package that is not open to Mansart.
 */
class OpensMessageTest {

    @Test
    void entityLookupFailureNamesTheOpensLineAndTheCompileTimePath() {
        assertThatThrownBy(() -> RuntimeEntityModelBuilder.build(java.util.ArrayList.class, null))
                .isInstanceOf(MansartDataException.class)
                .hasMessageContaining("Cannot privateLookupIn java.util.ArrayList")
                .hasMessageContaining("opens java.util to ")
                .hasMessageContaining("annotation processor")
                .hasMessageContaining("vidocq:generate")
                .hasMessageContaining("needs no opens");
    }
}
