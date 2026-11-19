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
import java.lang.annotation.Annotation;
import jakarta.el.ExpressionFactory;
import jakarta.el.StandardELContext;
import jakarta.el.MethodNotFoundException;
import io.vidocq.peano.spi.MethodDescriptor;
import io.vidocq.peano.spi.MethodSelection;
import org.junit.jupiter.api.Test;

class MethodSelectionTest {
    @Test
    void descriptorSnapshotsAndReturnedMetadataDoNotExposeMutableArrays() {
        Class<?>[] parameters={int.class};
        var descriptor=new MethodDescriptor("m",String.class,parameters,false,false,new Annotation[0]);
        parameters[0]=String.class;
        descriptor.parameterTypes()[0]=String.class;
        descriptor.methodInfo().getParamTypes()[0]=String.class;
        assertArrayEquals(new Class<?>[]{int.class},descriptor.parameterTypes());
        var equivalent=new MethodDescriptor("m",String.class,new Class<?>[]{int.class},false,false,new Annotation[0]);
        assertEquals(descriptor,equivalent);
        assertEquals(descriptor.hashCode(),equivalent.hashCode());
    }

    @Test
    void descriptorTextShowsTheArrayContentsLikeEqualityDoes() {
        var descriptor=new MethodDescriptor("m",String.class,new Class<?>[]{int.class,String[].class},true,true,
                new Annotation[0]);
        var equivalent=new MethodDescriptor("m",String.class,new Class<?>[]{int.class,String[].class},true,true,
                new Annotation[0]);
        assertEquals(equivalent.toString(),descriptor.toString());
        assertEquals("MethodDescriptor[name=m, returnType=class java.lang.String, parameterTypes=[int, "
                +"class [Ljava.lang.String;], varargs=true, staticMethod=true, annotations=[]]",descriptor.toString());
    }

    @Test
    void signaturesResolveDeclaredDescriptorsAndKeepUnrelatedMatchesAmbiguous() {
        var context=new StandardELContext(ExpressionFactory.newInstance());
        MethodDescriptor[] overloaded={
            descriptor("m",int.class),descriptor("m",Integer.class)
        };
        assertEquals(0,MethodSelection.select(context,overloaded,"m",new Class<?>[]{int.class},null,false));
        assertEquals(1,MethodSelection.select(context,overloaded,"m",new Class<?>[]{Integer.class},null,false));
        MethodDescriptor[] ambiguous={
            descriptor("m",java.io.Serializable.class),descriptor("m",CharSequence.class)
        };
        assertThrows(MethodNotFoundException.class,
                ()->MethodSelection.select(context,ambiguous,"m",null,new Object[]{"value"},false));
    }

    @Test
    void variableArityAcceptsSpreadPackedAndAbsentTrailingArguments() {
        var context=new StandardELContext(ExpressionFactory.newInstance());
        MethodDescriptor[] varargs={
            new MethodDescriptor("join",String.class,new Class<?>[]{String.class,Object[].class},true,false,new Annotation[0])
        };
        assertEquals(0,MethodSelection.select(context,varargs,"join",null,new Object[]{"a",1,2},false));
        assertEquals(0,MethodSelection.select(context,varargs,"join",null,new Object[]{"a"},false));
        assertEquals(0,MethodSelection.select(context,varargs,"join",new Class<?>[]{String.class,Object[].class},null,false));
        assertThrows(MethodNotFoundException.class,
                ()->MethodSelection.select(context,varargs,"join",new Class<?>[0],null,false));
    }

    @Test
    void signatureOnlyQueriesFollowTheStaticCoercionTable() {
        var context=new StandardELContext(ExpressionFactory.newInstance());
        assertEquals(0,MethodSelection.select(context,new MethodDescriptor[]{descriptor("m",long.class)},"m",
                new Class<?>[]{Integer.class},null,false));
        assertEquals(0,MethodSelection.select(context,new MethodDescriptor[]{descriptor("m",String.class)},"m",
                new Class<?>[]{Thread.class},null,false));
        assertEquals(0,MethodSelection.select(context,new MethodDescriptor[]{descriptor("m",Thread.State.class)},"m",
                new Class<?>[]{String.class},null,false));
        assertEquals(0,MethodSelection.select(context,new MethodDescriptor[]{descriptor("m",int.class)},"m",
                new Class<?>[]{char.class},null,false));
        assertEquals(0,MethodSelection.select(context,new MethodDescriptor[]{descriptor("m",char.class)},"m",
                new Class<?>[]{Long.class},null,false));
        MethodDescriptor[] unrelated={descriptor("m",Thread.class)};
        Class<?>[] integer={Integer.class};
        assertThrows(MethodNotFoundException.class,()->MethodSelection.select(context,unrelated,"m",integer,null,false));
    }

    @Test
    void valueQueriesProbeTheContextAndStaticQueriesSkipInstanceMethods() {
        var context=new StandardELContext(ExpressionFactory.newInstance());
        MethodDescriptor[] numeric={descriptor("m",long.class)};
        assertEquals(0,MethodSelection.select(context,numeric,"m",null,new Object[]{"42"},false));
        Object[] text={"not a number"};
        assertThrows(MethodNotFoundException.class,()->MethodSelection.select(context,numeric,"m",null,text,false));
        Object[] nothing={null};
        assertEquals(0,MethodSelection.select(context,numeric,"m",null,nothing,false));
        assertThrows(MethodNotFoundException.class,()->MethodSelection.select(context,numeric,"m",null,nothing,true));
        assertThrows(MethodNotFoundException.class,()->MethodSelection.select(context,numeric,"other",null,nothing,false));
    }

    private static MethodDescriptor descriptor(String name,Class<?> parameter) {
        return new MethodDescriptor(name,String.class,new Class<?>[]{parameter},false,false,new Annotation[0]);
    }
}

