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
package demo;
import io.vidocq.peano.spi.*;
import jakarta.el.*;
import java.util.*;
public final class Provider implements AccessorProvider {
    static int creations;
    public Provider() { creations++; }
    public Collection<? extends AccessorTable> accessors() { return List.of(TABLE); }
    private static final AccessorTable TABLE=new AccessorTable() {
        public Class<?> targetType() { return Main.Functions.class; }
        public Object getValue(ELContext c,Object b,Object p) { return null; }
        public Class<?> getType(ELContext c,Object b,Object p) { return null; }
        public boolean isReadOnly(ELContext c,Object b,Object p) { return true; }
        public void setValue(ELContext c,Object b,Object p,Object v) {}
        public Class<?> getCommonPropertyType(ELContext c,Object b) { return null; }
        public MethodReference getMethodReference(ELContext context,Object base,String name,Class<?>[] types,Object[] arguments) {
            if (!(base instanceof ELClass owner) || owner.getKlass()!=Main.Functions.class || !Set.of("sum","apply").contains(name)) return null;
            return new MethodReference(base,new MethodInfo(name,long.class,name.equals("sum")
                    ? new Class<?>[]{long.class,long.class} : new Class<?>[]{LambdaExpression.class}),
                    new java.lang.annotation.Annotation[0],arguments);
        }
        public Object invoke(ELContext context,Object base,Object property,Class<?>[] types,Object[] arguments) {
            if (!(base instanceof ELClass owner) || owner.getKlass()!=Main.Functions.class) return null;
            String name=(String)property;
            context.setPropertyResolved(base,property);
            ((Main.Context)context).effects.add("table:"+name);
            return switch(name) {
                case "sum"->Main.Functions.sum(context.convertToType(arguments[0],Long.class),context.convertToType(arguments[1],Long.class));
                case "alternate"->Main.Functions.alternate(context.convertToType(arguments[0],Long.class),context.convertToType(arguments[1],Long.class));
                case "none"->Main.Functions.none(); case "boom"->Main.Functions.boom();
                case "original"->{ Main.Functions.original(); yield null; }
                case "join"->{
                    String[] values=new String[arguments.length];
                    for(int i=0;i<values.length;i++) values[i]=context.convertToType(arguments[i],String.class);
                    yield Main.Functions.join(values);
                }
                default->{ context.setPropertyResolved(false); yield null; }
            };
        }
    };
}

