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
import java.util.List;
import jakarta.el.*;
import io.vidocq.peano.core.internal.evaluation.AccessorCatalog;
import io.vidocq.peano.spi.AccessorTable;
import org.junit.jupiter.api.Test;

/** EL-019: ExpressionFactory.coerceToType adapts lambdas through the factory's own catalog. */
class FactoryCoercionCatalogTest {
    @Test
    void coerceToTypeAdaptsLambdasThroughTheFactoryCatalog() {
        var table = new ShoutTable();
        var factory = new PeanoExpressionFactory(new AccessorCatalog(List.of(table)));
        var context = new StandardELContext(factory);
        var lambda = (LambdaExpression) factory.createValueExpression(context, "${x -> x}", Object.class).getValue(context);
        assertSame(table.adapter, factory.coerceToType(lambda, Shout.class));
    }

    /** An application functional interface with no standard adapter and no registered provider. */
    public interface Shout {
        String shout(String text);
    }

    private static final class ShoutTable extends AccessorTable {
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
