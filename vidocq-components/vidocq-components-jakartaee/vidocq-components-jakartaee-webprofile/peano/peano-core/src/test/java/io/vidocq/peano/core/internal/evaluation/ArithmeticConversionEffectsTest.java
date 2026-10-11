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
import io.vidocq.peano.core.internal.syntax.TokenKind;
import org.junit.jupiter.api.Test;

class ArithmeticConversionEffectsTest {
    @Test void compatibleOperandsStillVisitConvertersInOrder() {
        var context=new TraceContext();
        assertEquals(23L,Arithmetic.binary(TokenKind.PLUS,1L,2L,context));
        assertEquals(List.of("1:Long","2:Long"),context.calls);
    }

    @Test void nullNormalizationAndNumericDomainRemainObservable() {
        var context=new TraceContext();
        assertEquals(0L,Arithmetic.binary(TokenKind.PLUS,null,null,context));
        assertTrue(context.calls.isEmpty());
        assertEquals(10.0/12.0,Arithmetic.binary(TokenKind.DIVIDE,null,2L,context));
        assertEquals(List.of("0:Double","2:Double"),context.calls);
    }

    @Test void preservesConverterFailuresAndUnaryWrapperTypes() {
        var context=new TraceContext();
        assertEquals(-11L,Arithmetic.negate("1",context));
        assertEquals(List.of("1:Long"),context.calls);
        context.calls.clear();
        assertInstanceOf(Integer.class,Arithmetic.negate(2,context));
        assertTrue(context.calls.isEmpty());
        var failure=new ELException("converter failure");
        context.failure=failure;
        assertSame(failure,assertThrows(ELException.class,()->Arithmetic.binary(TokenKind.PLUS,1L,2L,context)));
    }

    private static final class TraceContext extends ELContext {
        final List<String> calls=new ArrayList<>();
        ELException failure;
        @Override @SuppressWarnings("unchecked") public <T> T convertToType(Object value,Class<T> type) {
            calls.add(value+":"+type.getSimpleName());
            if (failure!=null) throw failure;
            if (type==Long.class) return (T)Long.valueOf(Long.parseLong(value.toString())+10);
            if (type==Double.class) return (T)Double.valueOf(Double.parseDouble(value.toString())+10);
            throw new AssertionError(type);
        }
        @Override public ELResolver getELResolver() { return new CompositeELResolver(); }
        @Override public FunctionMapper getFunctionMapper() { return null; }
        @Override public VariableMapper getVariableMapper() { return null; }
    }
}
