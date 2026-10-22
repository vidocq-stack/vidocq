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
import java.util.Map;
import jakarta.el.*;
import io.vidocq.peano.core.internal.PeanoExpressionFactory;
import io.vidocq.peano.spi.AccessorTable;
import org.junit.jupiter.api.Test;

/** EL-019: the accessor tables and their lookup cache belong to the factory, not to each context. */
class FactoryAccessorCatalogTest {
    private final PeanoExpressionFactory factory = new PeanoExpressionFactory();
    private final Map.Entry<String, Integer> entry = Map.of("a", 1).entrySet().iterator().next();

    private AccessorCatalog evaluateInNewContext() {
        var context = new StandardELContext(factory);
        context.getVariableMapper().setVariable("e", factory.createValueExpression(entry, Object.class));
        assertEquals("a", factory.createValueExpression(context, "${e.key}", Object.class).getValue(context));
        return ((GeneratedAccessorELResolver) context.getContext(GeneratedAccessorELResolver.class)).catalog();
    }

    @Test
    void contextsOfOneFactoryShareTheCatalogAndItsLookupCache() {
        AccessorCatalog first = evaluateInNewContext();
        List<AccessorTable> looked = first.cached(entry.getClass());
        assertNotNull(looked);
        AccessorCatalog second = evaluateInNewContext();
        assertSame(first, second);
        assertSame(looked, second.cached(entry.getClass()));
    }

    @Test
    void anotherContextClassLoaderGetsItsOwnCatalog() {
        AccessorCatalog owner = evaluateInNewContext();
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(new ClassLoader(previous) {});
        try {
            // Another application's loader may see other providers: never serve it this factory's tables.
            assertNotSame(owner, evaluateInNewContext());
        } finally {
            thread.setContextClassLoader(previous);
        }
        assertSame(owner, evaluateInNewContext());
    }

    @Test
    void factoryCoercionAdaptsThroughTheGivenCatalog() {
        var table = new FunctionTable();
        var catalog = new AccessorCatalog(List.of(table));
        var lambda = (LambdaExpression) factory.createValueExpression(new StandardELContext(factory),
                "${x -> x}", Object.class).getValue(new StandardELContext(factory));
        assertSame(table.adapter, Coercions.convert(lambda, Shout.class, () -> catalog));
        assertThrows(ELException.class, () -> Coercions.convert(lambda, Shout.class,
                () -> new AccessorCatalog(List.of())));
    }

    /** An application functional interface with no standard adapter. */
    public interface Shout {
        String shout(String text);
    }

    private static final class FunctionTable extends AccessorTable {
        final Shout adapter = text -> text;

        @Override public Class<?> targetType() { return Shout.class; }
        @Override public <T> T convertToType(ELContext context, Object value, Class<T> type) {
            return type.cast(adapter);
        }
        @Override public Object getValue(ELContext context, Object base, Object property) { return null; }
        @Override public Class<?> getType(ELContext context, Object base, Object property) { return null; }
        @Override public void setValue(ELContext context, Object base, Object property, Object value) {
            // Not a property table.
        }
        @Override public boolean isReadOnly(ELContext context, Object base, Object property) { return false; }
        @Override public Class<?> getCommonPropertyType(ELContext context, Object base) { return null; }
    }
}
