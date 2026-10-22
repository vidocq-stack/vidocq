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

    @Test
    void interfaceTableResolvesAnonymousAndPrivateImplementations() {
        var resolver = new GeneratedAccessorELResolver(List.of(new Labelled(Status.class,"status")));
        Status anonymous = new Status() {};
        assertEquals("status", who(resolver, anonymous));
        assertEquals("status", who(resolver, new PrivateStatus()));
        assertEquals("status", who(resolver, anonymous));
    }

    @Test
    void superclassTableResolvesSubclass() {
        var resolver = new GeneratedAccessorELResolver(List.of(new Labelled(Base.class,"base")));
        assertEquals("base", who(resolver, new Sub()));
        assertEquals("base", who(resolver, new Leaf()));
    }

    @Test
    void exactClassWinsOverSuperclassAndSuperclassOverInterfaces() {
        var all = new GeneratedAccessorELResolver(List.of(new Labelled(Status.class,"status"),
                new Labelled(Base.class,"base"), new Labelled(Sub.class,"sub")));
        assertEquals("sub", who(all, new Sub()));
        var inherited = new GeneratedAccessorELResolver(List.of(new Labelled(Status.class,"status"),
                new Labelled(Base.class,"base")));
        assertEquals("base", who(inherited, new Sub()));
    }

    @Test
    void searchesInterfacesBreadthFirstInDeclarationOrder() {
        assertEquals("first", who(new GeneratedAccessorELResolver(List.of(
                new Labelled(Second.class,"second"), new Labelled(First.class,"first"))), new Both()));
        // Leaf implements Own (which extends OwnParent) and extends Middle (which implements Inherited).
        assertEquals("own", who(new GeneratedAccessorELResolver(List.of(
                new Labelled(Inherited.class,"inherited"), new Labelled(Own.class,"own"))), new Leaf()));
        assertEquals("ownParent", who(new GeneratedAccessorELResolver(List.of(
                new Labelled(Inherited.class,"inherited"), new Labelled(OwnParent.class,"ownParent"))), new Leaf()));
        assertEquals("inherited", who(new GeneratedAccessorELResolver(List.of(
                new Labelled(Inherited.class,"inherited"))), new Leaf()));
    }

    @Test
    void cachesNegativeLookupsAndLeavesUnmatchedHierarchiesUnresolved() {
        var resolver = new GeneratedAccessorELResolver(List.of(new Labelled(Status.class,"status")));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertNull(resolver.cached(Middle.class));
        assertNull(resolver.getValue(context,new Middle(),"who"));
        assertFalse(context.isPropertyResolved());
        List<AccessorTable> cached = resolver.cached(Middle.class);
        assertEquals(List.of(), cached);
        assertNull(resolver.getValue(context,new Middle(),"who"));
        assertFalse(context.isPropertyResolved());
        assertSame(cached, resolver.cached(Middle.class));
    }

    @Test
    void visitsAnInterfaceReachedThroughTwoParentsOnce() {
        var diamond = new Counting(Diamond.class);
        var resolver = new GeneratedAccessorELResolver(List.of(diamond));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertNull(resolver.getValue(context,new Cross(),"who"));
        assertFalse(context.isPropertyResolved());
        assertEquals(1, diamond.calls);
        assertEquals(List.of(diamond), resolver.cached(Cross.class));
        assertEquals("diamond", who(new GeneratedAccessorELResolver(List.of(new Labelled(Diamond.class,"diamond"))), new Cross()));
        // Right sits on the first level, Diamond on the next one.
        assertEquals("right", who(new GeneratedAccessorELResolver(List.of(
                new Labelled(Diamond.class,"diamond"), new Labelled(Right.class,"right"))), new Cross()));
    }

    @Test
    void leavesNullBasesUnresolved() {
        var resolver = new GeneratedAccessorELResolver(List.of(new Labelled(Status.class,"status")));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertNull(resolver.getValue(context,null,"who"));
        assertNull(resolver.getType(context,null,"who"));
        assertFalse(resolver.isReadOnly(context,null,"who"));
        resolver.setValue(context,null,"who","x");
        assertNull(resolver.invoke(context,null,"who",null,new Object[0]));
        assertNull(resolver.getCommonPropertyType(context,null));
        assertNull(GeneratedAccessorELResolver.reference(context,null,"who",null,new Object[0]));
        assertNull(GeneratedAccessorELResolver.invokeRegistered(context,null,"who",null,new Object[0]));
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void staticBasesKeepExactTypeLookup() {
        var resolver = new GeneratedAccessorELResolver(List.of(new Labelled(Base.class,"base")));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertNull(resolver.getValue(context,new ELClass(Sub.class),"who"));
        assertFalse(context.isPropertyResolved());
        assertEquals("base", resolver.getValue(context,new ELClass(Base.class),"who"));
        assertTrue(context.isPropertyResolved());
    }

    @Test
    void functionalConversionKeepsExactTargetLookup() {
        var table = new Labelled(Status.class,"status");
        var resolver = new GeneratedAccessorELResolver(List.of(table));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        resolver.convertToType(context,"value",ChildStatus.class);
        assertEquals(0, table.conversions);
        resolver.convertToType(context,"value",Status.class);
        assertEquals(1, table.conversions);
    }

    @Test
    void shipsABuiltInMapEntryTable() {
        var resolver = new GeneratedAccessorELResolver(List.of());
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertEquals("a", resolver.getValue(context,java.util.Map.entry("a",1),"key"));
        assertTrue(context.isPropertyResolved());
    }

    @Test
    void applicationMapEntryTableOverridesTheBuiltIn() {
        var resolver = new GeneratedAccessorELResolver(List.of(new Labelled(java.util.Map.Entry.class,"application")));
        assertEquals("application", who(resolver, java.util.Map.entry("a",1)));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertNull(resolver.getValue(context,java.util.Map.entry("a",1),"key"));
        assertFalse(context.isPropertyResolved());
        assertThrows(ELException.class, () -> new GeneratedAccessorELResolver(List.of(
                new Labelled(java.util.Map.Entry.class,"one"), new Labelled(java.util.Map.Entry.class,"two"))));
    }

    @Test
    void anUnresolvingTableFallsThroughToTheNextMatchingTable() {
        var exact = new ExactOnly(Base.class);
        var status = new StatusTable();
        var resolver = new GeneratedAccessorELResolver(List.of(exact, status));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        var sub = new Sub();
        assertEquals("status", resolver.getValue(context,sub,"prop"));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertEquals(String.class, resolver.getType(context,sub,"prop"));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertFalse(resolver.isReadOnly(context,sub,"prop"));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        resolver.setValue(context,sub,"prop","written");
        assertTrue(context.isPropertyResolved());
        assertEquals("written", status.written);
        context.setPropertyResolved(false);
        assertEquals("acted", resolver.invoke(context,sub,"act",new Class<?>[0],new Object[0]));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertEquals("act", GeneratedAccessorELResolver.reference(context,sub,"act",new Class<?>[0],new Object[0])
                .getMethodInfo().getName());
        context.setPropertyResolved(false);
        assertEquals("acted", GeneratedAccessorELResolver.invokeRegistered(context,sub,"act",new Class<?>[0],new Object[0]));
        assertTrue(context.isPropertyResolved());
        context.setPropertyResolved(false);
        assertEquals(String.class, resolver.getCommonPropertyType(context,sub));
        // The exact-class table still serves its own class first.
        assertEquals("base", resolver.getValue(context,new Base(),"prop"));
        assertTrue(exact.consulted > 0);
        // No matching table resolves: the operation stays unresolved for the rest of the chain.
        context.setPropertyResolved(false);
        assertNull(resolver.getValue(context,sub,"missing"));
        assertFalse(context.isPropertyResolved());
    }

    @Test
    void interfaceTableResolvesBehindAnExactOnlySuperclassTableInDenyMode() {
        String previous = System.getProperty("peano.reflection");
        System.setProperty("peano.reflection","deny");
        try {
            var factory = ExpressionFactory.newInstance();
            var context = new StandardELContext(factory);
            var chain = new CompositeELResolver();
            chain.add(new GeneratedAccessorELResolver(List.of(new ExactOnly(Base.class), new StatusTable())));
            chain.add(new ReflectionGuard());
            context.addELResolver(chain);
            context.getVariableMapper().setVariable("x", factory.createValueExpression(new Sub(), Object.class));
            assertEquals("status", factory.createValueExpression(context,"${x.prop}",Object.class).getValue(context));
            assertEquals("acted", factory.createValueExpression(context,"${x.act()}",Object.class).getValue(context));
            var denied = assertThrows(ELException.class,
                    () -> factory.createValueExpression(context,"${x.missing}",Object.class).getValue(context));
            assertEquals("Reflection fallback denied for " + Sub.class.getName()
                    + "; register a generated accessor provider or a custom resolver", denied.getMessage());
        } finally {
            if (previous == null) System.clearProperty("peano.reflection");
            else System.setProperty("peano.reflection",previous);
        }
    }

    /** Behaves like a generated table: it handles its exact class only. */
    private static final class ExactOnly extends AccessorTable {
        private final Class<?> type;
        private int consulted;
        ExactOnly(Class<?> type) { this.type = type; }
        private boolean accepts(ELContext context, Object base, Object property) {
            consulted++;
            if (base == null || base.getClass() != type || !"prop".equals(property)) return false;
            context.setPropertyResolved(base,property);
            return true;
        }
        @Override public Class<?> targetType() { return type; }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            return accepts(context,base,property) ? "base" : null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) {
            return accepts(context,base,property) ? String.class : null;
        }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            accepts(context,base,property);
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) {
            return accepts(context,base,property) && false;
        }
        @Override public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] args) {
            consulted++;
            if (base == null || base.getClass() != type || !"act".equals(method)) return null;
            context.setPropertyResolved(base,method);
            return "base-acted";
        }
        @Override public MethodReference getMethodReference(ELContext context, Object base, String method,
                Class<?>[] types, Object[] arguments) {
            consulted++;
            return base != null && base.getClass() == type && "act".equals(method)
                    ? new MethodReference(base,new MethodInfo("act",String.class,new Class<?>[0]),
                            new java.lang.annotation.Annotation[0],new Object[0]) : null;
        }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base != null && base.getClass() == type ? String.class : null;
        }
    }

    /** Handwritten interface table: serves every Status implementation. */
    private static final class StatusTable extends AccessorTable {
        private Object written;
        private boolean accepts(ELContext context, Object base, Object property) {
            if (!(base instanceof Status) || !"prop".equals(property)) return false;
            context.setPropertyResolved(base,property);
            return true;
        }
        @Override public Class<?> targetType() { return Status.class; }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            return accepts(context,base,property) ? "status" : null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) {
            return accepts(context,base,property) ? String.class : null;
        }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            if (accepts(context,base,property)) written = value;
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) {
            accepts(context,base,property);
            return false;
        }
        @Override public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] args) {
            if (!(base instanceof Status) || !"act".equals(method)) return null;
            context.setPropertyResolved(base,method);
            return "acted";
        }
        @Override public MethodReference getMethodReference(ELContext context, Object base, String method,
                Class<?>[] types, Object[] arguments) {
            return base instanceof Status && "act".equals(method)
                    ? new MethodReference(base,new MethodInfo("act",String.class,new Class<?>[0]),
                            new java.lang.annotation.Annotation[0],new Object[0]) : null;
        }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) {
            return base instanceof Status ? String.class : null;
        }
    }

    private static Object who(GeneratedAccessorELResolver resolver, Object base) {
        var context = new StandardELContext(ExpressionFactory.newInstance());
        Object value = resolver.getValue(context,base,"who");
        assertTrue(context.isPropertyResolved(), "No table resolved " + base.getClass().getName());
        return value;
    }

    public interface Status {}
    interface ChildStatus extends Status {}
    private static final class PrivateStatus implements Status {}
    static class Base {}
    static class Sub extends Base implements Status {}
    interface First {}
    interface Second {}
    static final class Both implements First, Second {}
    interface OwnParent {}
    interface Own extends OwnParent {}
    interface Inherited {}
    static class Middle extends Base implements Inherited {}
    static final class Leaf extends Middle implements Own {}
    interface Diamond {}
    interface Left extends Diamond {}
    interface Right extends Diamond {}
    static final class Cross implements Left, Right {}

    /** Counts the getValue calls it receives and never resolves. */
    private static final class Counting extends AccessorTable {
        private final Class<?> type;
        private int calls;
        Counting(Class<?> type) { this.type = type; }
        @Override public Class<?> targetType() { return type; }
        @Override public Object getValue(ELContext context, Object base, Object property) { calls++; return null; }
        @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {}
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return false; }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return null; }
    }

    /** Answers the pseudo-property "who" with its label, for any base it is selected for. */
    private static final class Labelled extends AccessorTable {
        private final Class<?> type;
        private final String label;
        private int conversions;
        Labelled(Class<?> type, String label) { this.type = type; this.label = label; }
        @Override public Class<?> targetType() { return type; }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            if (!"who".equals(property)) return null;
            context.setPropertyResolved(base,property);
            return label;
        }
        @Override public <T> T convertToType(ELContext context, Object value, Class<T> target) {
            conversions++;
            return null;
        }
        @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {}
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return true; }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return String.class; }
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

