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

class OfficialCompatibilityTest {
    @Test
    void writesDynamicRootNamesWithoutInferringTheirTypeFromCurrentValues() {
        var el = new ELProcessor();
        el.defineBean("a", null);
        assertEquals(2.5, el.<Object>eval("a=1; a=2.5; a"));
        assertEquals(42L, el.<Object>eval("b=null; b=42; b"));
    }

    @Test
    void equalityIgnoresWhitespaceOperatorAliasesAndEvaluationDelimiterChoice() {
        var factory = ExpressionFactory.newInstance();
        var context = new StandardELContext(factory);
        for (String[] pair : new String[][]{
                {"${A+B+C}", "${ A + B\t+\tC }"},
                {"${A < B}", "${A lt B}"},
                {"${A}", "#{A}"},
                {"${[1,2].stream().map(x->x*2)}", "#{ [1, 2].stream().map( x -> x * 2 ) }"},
                {"${{'a':true and false}}", "#{ {\"a\": true && false} }"}}) {
            var a = factory.createValueExpression(context,pair[0],Object.class);
            var b = factory.createValueExpression(context,pair[1],Object.class);
            assertEquals(a,b);
            assertEquals(a.hashCode(),b.hashCode());
        }
        assertNotEquals(factory.createValueExpression(context,"${A+B}",Object.class),
                factory.createValueExpression(context,"${B+A}",Object.class));
        assertNotEquals(factory.createValueExpression(context,"a b",Object.class),
                factory.createValueExpression(context,"ab",Object.class));
    }

    @Test
    void bareMethodReferencesUseTheApiFallbackWhenTheChainDoesNotHandleInvocation() {
        var factory = ExpressionFactory.newInstance();
        var empty = new CompositeELResolver();
        var context = new StandardELContext(factory) {
            @Override public ELResolver getELResolver() { return empty; }
        };
        var vector = new java.util.Vector<Object>();
        context.getVariableMapper().setVariable("vect",factory.createValueExpression(vector,Object.class));
        var method = factory.createMethodExpression(context,"#{vect.add}",boolean.class,new Class<?>[]{Object.class});
        assertEquals(true,method.invoke(context,new Object[]{"Peano"}));
        assertEquals(java.util.List.of("Peano"),vector);
        var call=factory.createValueExpression(context,"${vect.add('next')}",Object.class);
        assertThrows(MethodNotFoundException.class,()->call.getValue(context));
        assertEquals(1,vector.size());
    }
}

