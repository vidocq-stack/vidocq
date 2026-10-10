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
import jakarta.el.*;
import org.junit.jupiter.api.Test;

class FunctionExpressionTest {
    final ExpressionFactory factory = ExpressionFactory.newInstance();

    @Test
    void capturesQualifiedAndUnqualifiedFunctionsBeforeMapperChanges() throws Exception {
        var creation = new StandardELContext(factory);
        var mapper = creation.getFunctionMapper();
        mapper.mapFunction("f", "choose", Math.class.getMethod("max", int.class, int.class));
        mapper.mapFunction("", "choose", Math.class.getMethod("max", int.class, int.class));
        var qualified = factory.createValueExpression(creation, "${f:choose('2',3)}", Object.class);
        var unqualified = factory.createValueExpression(creation, "${choose(2,3)}", Object.class);
        mapper.mapFunction("f", "choose", Math.class.getMethod("min", int.class, int.class));
        mapper.mapFunction("", "choose", Math.class.getMethod("min", int.class, int.class));
        var evaluation = new StandardELContext(factory);
        assertEquals(3, qualified.<Object>getValue(evaluation));
        assertEquals(3, unqualified.<Object>getValue(evaluation));
        assertEquals(2, factory.createValueExpression(creation, "${f:choose(2,3)}", Object.class).<Object>getValue(evaluation));
    }

    @Test
    void coercesVarargsAndUnwrapsInvocationTargetExceptions() throws Exception {
        var context = new StandardELContext(factory);
        context.getFunctionMapper().mapFunction("f", "format", String.class.getMethod("format", String.class, Object[].class));
        assertEquals("Peano: 6", factory.createValueExpression(context, "${f:format('%s: %s','Peano',6)}", Object.class).getValue(context));
        context.getFunctionMapper().mapFunction("f", "parse", Integer.class.getMethod("parseInt", String.class));
        var expression = factory.createValueExpression(context, "${f:parse('bad')}", Object.class);
        assertInstanceOf(NumberFormatException.class, assertThrows(ELException.class, () -> expression.getValue(context)).getCause());
    }

    @Test
    void rejectsMissingQualifiedFunctionsAndNonStaticMappingsAtCreation() throws Exception {
        var context = new StandardELContext(factory);
        assertThrows(ELException.class, () -> factory.createValueExpression(context, "${f:missing()}", Object.class));
        context.getFunctionMapper().mapFunction("f", "bad", String.class.getMethod("length"));
        assertThrows(ELException.class, () -> factory.createValueExpression(context, "${f:bad()}", Object.class));
    }
}

