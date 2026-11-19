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
package io.vidocq.peano.core.internal;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Month;
import java.util.Map;
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class ImportResolutionTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    @Test
    void resolvesDefaultAndExplicitClassAndStaticImportsViaTheAPIResolver() {
        var context = new StandardELContext(factory);
        assertEquals(Math.PI, factory.createValueExpression(context, "${Math.PI}", Object.class).<Object>getValue(context));
        assertInstanceOf(ELClass.class, factory.createValueExpression(context, "${Math}", Object.class).getValue(context));
        context.getImportHandler().importClass("java.time.Month");
        assertEquals(Month.JANUARY, factory.createValueExpression(context, "${Month.JANUARY}", Object.class).getValue(context));
        context.getImportHandler().importStatic("java.lang.Math.PI");
        var pi = factory.createValueExpression(context, "${PI}", Object.class);
        assertEquals(Math.PI, pi.<Object>getValue(context));
        assertNull(pi.getType(context));
        assertTrue(pi.isReadOnly(context));
        assertThrows(PropertyNotWritableException.class, () -> pi.setValue(context, 3d));
        var reference = pi.getValueReference(context);
        assertInstanceOf(ELClass.class, reference.getBase());
        assertEquals("PI", reference.getProperty());
    }

    @Test
    void resolvedNamesIncludingNullShadowImportedClassesAndStaticFields() {
        var context = new StandardELContext(factory);
        context.getImportHandler().importStatic("java.lang.Math.PI");
        context.getVariableMapper().setVariable("Math", factory.createValueExpression(Map.of("PI", 1L), Object.class));
        context.getVariableMapper().setVariable("PI", factory.createValueExpression(null, Object.class));
        assertEquals(1L, factory.createValueExpression(context, "${Math.PI}", Object.class).<Object>getValue(context));
        assertNull(factory.createValueExpression(context, "${PI}", Object.class).getValue(context));
    }
}

