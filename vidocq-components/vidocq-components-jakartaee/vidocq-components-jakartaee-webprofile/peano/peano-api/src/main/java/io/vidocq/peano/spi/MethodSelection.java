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
import java.util.List;
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
        Class<?>[] actual = expectedTypes != null ? expectedTypes : typesOf(values);
        var best = new ArrayList<Integer>();
        Score bestScore = null;
        for (int i=0;i<descriptors.length;i++) {
            Score score = candidateScore(context,descriptors[i],name,staticOnly,actual,values);
            if (score != null) {
                int order = bestScore==null ? 1 : score.compareTo(bestScore);
                if (order>0) { best.clear(); best.add(i); bestScore=score; }
                else if (order==0) best.add(i);
            }
        }
        return mostSpecific(descriptors,best,name);
    }

    private static int mostSpecific(MethodDescriptor[] descriptors, List<Integer> best, String name) {
        for (int candidate : best) {
            if (best.stream().allMatch(other->candidate==other
                    || moreSpecific(descriptors[candidate],descriptors[other]))) return candidate;
        }
        throw new MethodNotFoundException("No unique method "+name);
    }

    private static Class<?>[] typesOf(Object[] values) {
        if (values == null) return new Class<?>[0];
        Class<?>[] types = new Class<?>[values.length];
        for (int i=0;i<types.length;i++) types[i]=values[i]==null?null:values[i].getClass();
        return types;
    }

    private static Score candidateScore(ELContext context, MethodDescriptor descriptor, String name,
            boolean staticOnly, Class<?>[] actual, Object[] values) {
        if (!descriptor.name().equals(name) || staticOnly && !descriptor.staticMethod()) return null;
        return score(context,descriptor,actual,values);
    }

    private enum Match { EXACT, ASSIGNABLE, COERCIBLE, NONE }

    private static Score score(ELContext context, MethodDescriptor descriptor, Class<?>[] actual, Object[] values) {
        Class<?>[] formal = descriptor.parameterTypes();
        boolean varargs = descriptor.varargs();
        if (!varargs && actual.length!=formal.length || varargs && actual.length<formal.length-1) return null;
        int exact = 0;
        int assignable = 0;
        int coercible = 0;
        for (int i=0;i<actual.length;i++) {
            boolean hasValue = values!=null && i<values.length;
            Match match = match(context,actual[i],target(varargs,formal,actual,i),hasValue ? values[i] : null,hasValue);
            switch (match) {
                case EXACT -> exact++;
                case ASSIGNABLE -> assignable++;
                case COERCIBLE -> coercible++;
                case NONE -> { return null; }
            }
        }
        return new Score(exact,assignable,coercible,varargs);
    }

    private static Class<?> target(boolean varargs, Class<?>[] formal, Class<?>[] actual, int i) {
        if (!varargs || i<formal.length-1) return formal[i];
        boolean packed=i==formal.length-1 && actual.length==formal.length && actual[i]!=null
                && formal[i].isAssignableFrom(actual[i]);
        return packed?formal[i]:formal[formal.length-1].getComponentType();
    }

    private static Match match(ELContext context, Class<?> source, Class<?> target, Object value, boolean hasValue) {
        if (source==null) return Match.COERCIBLE;
        if (source==target) return Match.EXACT;
        if (boxed(target).isAssignableFrom(boxed(source))) return Match.ASSIGNABLE;
        return canCoerce(context,source,target,value,hasValue) ? Match.COERCIBLE : Match.NONE;
    }

    private static boolean canCoerce(ELContext context, Class<?> source, Class<?> target,Object value,boolean hasValue) {
        if (hasValue) {
            try { context.convertToType(value,target); return true; }
            catch (ELException _) { return false; }
        }
        Class<?> from=boxed(source);
        Class<?> to=boxed(target);
        return to==String.class || from==String.class && (Number.class.isAssignableFrom(to)
                || to==Boolean.class || to==Character.class || to.isEnum())
                || Number.class.isAssignableFrom(from) && (Number.class.isAssignableFrom(to) || to==Character.class)
                || from==Character.class && Number.class.isAssignableFrom(to);
    }

    private static boolean moreSpecific(MethodDescriptor a,MethodDescriptor b) {
        Class<?>[] left=a.parameterTypes();
        Class<?>[] right=b.parameterTypes();
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

