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
package io.vidocq.peano.core.internal.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import jakarta.el.*;
import io.vidocq.peano.spi.AccessorTable;
import org.junit.jupiter.api.Test;

class GeneratedAccessorResolverTest {
    @Test
    void rejectsConflictingTablesForTheSameRuntimeType() {
        assertThrows(ELException.class,
                () -> new GeneratedAccessorELResolver(List.of(new Table(),new Table())));
        assertThrows(ELException.class, () -> new GeneratedAccessorELResolver(null));
    }

    @Test
    void snapshotsRegistrationAndLeavesUnknownTargetsAndPropertiesUnresolved() {
        var registrations = new ArrayList<AccessorTable>();
        registrations.add(new Table());
        var resolver = new GeneratedAccessorELResolver(registrations);
        registrations.clear();
        var context = new StandardELContext(ExpressionFactory.newInstance());
        var target = new Target();
        assertEquals("value", resolver.getValue(context,target,"value"));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertNull(resolver.getValue(context,target,"unknown"));
        assertFalse(context.isPropertyResolved());
        assertNull(resolver.getValue(context,new Object(),"value"));
        assertFalse(context.isPropertyResolved());
        assertThrows(NullPointerException.class, ()->resolver.getValue(null,target,"value"));
    }

    private static final class Target {}

    private static final class Table extends AccessorTable {
        @Override public Class<?> targetType() { return Target.class; }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            if ("value".equals(property)) {
                context.setPropertyResolved(base,property);
                return "value";
            }
            return null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {}
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return true; }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return String.class; }
    }
}

