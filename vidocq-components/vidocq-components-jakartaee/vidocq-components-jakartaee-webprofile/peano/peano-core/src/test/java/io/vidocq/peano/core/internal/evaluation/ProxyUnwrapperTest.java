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
import java.util.List;
import jakarta.el.*;
import io.vidocq.peano.spi.AccessorTable;
import io.vidocq.peano.spi.ProxyUnwrapper;
import io.vidocq.peano.spi.ProxyUnwrappers;
import org.junit.jupiter.api.Test;

/**
 * A CDI container hands EL a client proxy of a normal-scoped bean, a subclass of the bean class: the bean's
 * generated table serves it once an unwrapper says which class it stands for, and only then.
 */
class ProxyUnwrapperTest {
    public static class Cart { public String getOwner() { return "ada"; } }
    /** Stands for a container's client proxy: delegates every call. */
    public static class Cart_ClientProxy extends Cart {
        private final Cart delegate = new Cart() { @Override public String getOwner() { return "delegated"; } };
        @Override public String getOwner() { return delegate.getOwner(); }
    }
    public static class Plain extends Cart {}

    /** What the processor generates: the exact-class check goes through serves(). */
    static final class CartTable extends AccessorTable {
        @Override public Class<?> targetType() { return Cart.class; }
        @Override public Object getValue(ELContext context, Object base, Object property) {
            if (base == null || !serves(base.getClass()) || !"owner".equals(property)) return null;
            context.setPropertyResolved(base, property);
            return ((Cart) base).getOwner();
        }
        @Override public Class<?> getType(ELContext c, Object b, Object p) { return null; }
        @Override public void setValue(ELContext c, Object b, Object p, Object v) {
            // Read-only table: the test only reads.
        }
        @Override public boolean isReadOnly(ELContext c, Object b, Object p) { return true; }
        @Override public Class<?> getCommonPropertyType(ELContext c, Object b) { return null; }
    }

    static final class SuffixUnwrapper implements ProxyUnwrapper {
        @Override public Class<?> proxiedClass(Class<?> runtime) {
            return runtime.getSimpleName().endsWith("_ClientProxy") ? runtime.getSuperclass() : null;
        }
    }

    @Test
    void aRegisteredUnwrapperLetsTheBeanTableServeItsProxyAndNothingElse() {
        var resolver = new GeneratedAccessorELResolver(List.of(new CartTable()));
        var context = new StandardELContext(ExpressionFactory.newInstance());
        assertEquals("ada", resolver.getValue(context, new Cart(), "owner"));

        context.setPropertyResolved(false);
        assertNull(resolver.getValue(context, new Plain(), "owner"));
        assertFalse(context.isPropertyResolved(), "a plain subclass keeps the exact-class rule");

        ProxyUnwrappers.register(new SuffixUnwrapper());
        context.setPropertyResolved(false);
        assertEquals("delegated", resolver.getValue(context, new Cart_ClientProxy(), "owner"));
        assertTrue(context.isPropertyResolved());

        context.setPropertyResolved(false);
        assertNull(resolver.getValue(context, new Plain(), "owner"));
        assertFalse(context.isPropertyResolved(), "still not a proxy");
        assertNull(ProxyUnwrappers.proxiedClass(Plain.class));
        assertEquals(Cart.class, ProxyUnwrappers.proxiedClass(Cart_ClientProxy.class));
    }
}
