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
package io.vidocq.peano.spi;

import java.util.ArrayList;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.MethodNotFoundException;

/** Overload selection using signature constants only, with no reflective member lookup. */
public final class MethodSelection {
    private MethodSelection() {}

    private record Score(int exact, int assignable, int coercible, boolean varargs) implements Comparable<Score> {
        @Override public int compareTo(Score other) {
            int order = Integer.compare(exact,other.exact);
            if (order == 0) order = Integer.compare(assignable,other.assignable);
            if (order == 0) order = Integer.compare(coercible,other.coercible);
            if (order == 0) order = Boolean.compare(other.varargs,varargs);
            return order;
        }
    }

    /**
     * Selects the unique most-specific matching descriptor.
     *
     * @param context conversion context
     * @param descriptors available signatures
     * @param name method name
     * @param expectedTypes declared parameter types, or null to infer from values
     * @param values raw argument values, possibly absent for signature-only queries
     * @param staticOnly whether instance methods are excluded
     * @return descriptor index
     */
    public static int select(ELContext context, MethodDescriptor[] descriptors, String name,
            Class<?>[] expectedTypes, Object[] values, boolean staticOnly) {
        Class<?>[] actual = expectedTypes;
        if (actual == null) {
            actual = new Class<?>[values == null ? 0 : values.length];
            for (int i=0;i<actual.length;i++) actual[i]=values[i]==null?null:values[i].getClass();
        }
        var best = new ArrayList<Integer>();
        Score bestScore = null;
        for (int i=0;i<descriptors.length;i++) {
            MethodDescriptor descriptor = descriptors[i];
            if (!descriptor.name().equals(name) || staticOnly && !descriptor.staticMethod()) continue;
            Score score = score(context,descriptor,actual,values);
            if (score == null) continue;
            int order = bestScore==null?1:score.compareTo(bestScore);
            if (order>0) { best.clear(); best.add(i); bestScore=score; }
            else if (order==0) best.add(i);
        }
        for (int candidate : best) {
            if (best.stream().allMatch(other->candidate==other
                    || moreSpecific(descriptors[candidate],descriptors[other]))) return candidate;
        }
        throw new MethodNotFoundException("No unique method "+name);
    }

    private static Score score(ELContext context, MethodDescriptor descriptor, Class<?>[] actual, Object[] values) {
        Class<?>[] formal = descriptor.parameterTypes();
        if (!descriptor.varargs() && actual.length!=formal.length
                || descriptor.varargs() && actual.length<formal.length-1) return null;
        int exact=0, assignable=0, coercible=0;
        for (int i=0;i<actual.length;i++) {
            Class<?> target;
            if (descriptor.varargs() && i>=formal.length-1) {
                boolean packed=i==formal.length-1 && actual.length==formal.length && actual[i]!=null
                        && formal[i].isAssignableFrom(actual[i]);
                target=packed?formal[i]:formal[formal.length-1].getComponentType();
            } else target=formal[i];
            Class<?> source=actual[i];
            if (source==null) coercible++;
            else if (source==target) exact++;
            else if (boxed(target).isAssignableFrom(boxed(source))) assignable++;
            else if (canCoerce(context,source,target,values!=null && i<values.length?values[i]:null,
                    values!=null && i<values.length)) coercible++;
            else return null;
        }
        return new Score(exact,assignable,coercible,descriptor.varargs());
    }

    private static boolean canCoerce(ELContext context, Class<?> source, Class<?> target,Object value,boolean hasValue) {
        if (hasValue) {
            try { context.convertToType(value,target); return true; }
            catch (ELException failure) { return false; }
        }
        Class<?> from=boxed(source), to=boxed(target);
        return to==String.class || from==String.class && (Number.class.isAssignableFrom(to)
                || to==Boolean.class || to==Character.class || to.isEnum())
                || Number.class.isAssignableFrom(from) && (Number.class.isAssignableFrom(to) || to==Character.class)
                || from==Character.class && Number.class.isAssignableFrom(to);
    }

    private static boolean moreSpecific(MethodDescriptor a,MethodDescriptor b) {
        Class<?>[] left=a.parameterTypes(), right=b.parameterTypes();
        if (left.length!=right.length) return false;
        boolean narrower=false;
        for (int i=0;i<left.length;i++) {
            if (!boxed(right[i]).isAssignableFrom(boxed(left[i]))) return false;
            narrower |= boxed(left[i])!=boxed(right[i]);
        }
        return narrower;
    }

    private static Class<?> boxed(Class<?> type) {
        if (type==boolean.class) return Boolean.class;
        if (type==char.class) return Character.class;
        if (type==byte.class) return Byte.class;
        if (type==short.class) return Short.class;
        if (type==int.class) return Integer.class;
        if (type==long.class) return Long.class;
        if (type==float.class) return Float.class;
        if (type==double.class) return Double.class;
        return type;
    }
}

