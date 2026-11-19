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
package io.vidocq.runtime.extensions.microprofile.humboldt;

import io.vidocq.vauban.core.BeanFactory;
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The holder bean is created by the factory the extension registers, a plain {@code new}: without one, Vauban creates
 * a class added by {@code addBeanClass} through its constructor, by reflection.
 */
class HumboldtHolderFactoryTest {

    @Test
    void theHolderIsCreatedByItsFactoryNotByReflection() throws Exception {
        VaubanContainerBuilder builder = new VaubanContainerBuilder();
        new HumboldtExtension().beforeStart(builder);

        Object factory = registeredFactory(builder, HumboldtHolder.class);
        assertNotNull(factory, "a factory is registered for HumboldtHolder");
        assertInstanceOf(HumboldtHolder.class, ((BeanFactory<?>) factory).create());
    }

    /** The factory the builder holds for {@code type}, read from its private map: the builder exposes none. */
    private static Object registeredFactory(VaubanContainerBuilder builder, Class<?> type)
            throws ReflectiveOperationException {
        var field = VaubanContainerBuilder.class.getDeclaredField("factories");
        field.setAccessible(true);
        for (var entry : ((Map<?, ?>) field.get(builder)).entrySet()) {
            if (entry.getKey().toString().equals(type.getName())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
