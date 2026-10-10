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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import jakarta.el.ELContext;
import jakarta.el.ELException;
import jakarta.el.ELResolver;
import io.vidocq.peano.spi.AccessorProvider;
import io.vidocq.peano.spi.AccessorTable;

/** Direct application-table dispatch, with no property/member introspection. */
public final class GeneratedAccessorELResolver extends ELResolver {
    private final Map<Class<?>, AccessorTable> tables;

    public GeneratedAccessorELResolver() {
        this(load());
    }

    public GeneratedAccessorELResolver(Collection<? extends AccessorTable> accessors) {
        if (accessors == null) throw new ELException("Accessor table collection must not be null");
        var registered = new HashMap<Class<?>, AccessorTable>();
        for (AccessorTable table : accessors) {
            if (table == null) throw new ELException("Accessor table must not be null");
            Class<?> type = table.targetType();
            if (type == null) throw new ELException("Accessor target type must not be null");
            if (registered.putIfAbsent(type, table) != null) {
                throw new ELException("Conflicting accessor tables for " + type.getName());
            }
        }
        tables = Map.copyOf(registered);
    }

    private static Collection<AccessorTable> load() {
        var accessors = new ArrayList<AccessorTable>();
        try {
            for (AccessorProvider provider : ServiceLoader.load(AccessorProvider.class)) {
                Collection<? extends AccessorTable> contributed = provider.accessors();
                if (contributed == null) throw new ELException("Accessor provider returned null tables");
                accessors.addAll(contributed);
            }
        } catch (ServiceConfigurationError | RuntimeException error) {
            if (error instanceof ELException exception) throw exception;
            throw new ELException("Cannot load application accessor tables", error);
        }
        return accessors;
    }

    private AccessorTable table(ELContext context, Object base) {
        Objects.requireNonNull(context, "context");
        return base == null ? null : tables.get(base.getClass());
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        AccessorTable table = table(context, base);
        return table == null ? null : table.getValue(context, base, property);
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        AccessorTable table = table(context, base);
        return table == null ? null : table.getType(context, base, property);
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {
        AccessorTable table = table(context, base);
        if (table != null) table.setValue(context, base, property, value);
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        AccessorTable table = table(context, base);
        return table != null && table.isReadOnly(context, base, property);
    }

    @Override
    public Object invoke(ELContext context, Object base, Object method, Class<?>[] types, Object[] arguments) {
        AccessorTable table = table(context, base);
        return table == null ? null : table.invoke(context, base, method, types, arguments);
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        AccessorTable table = table(context, base);
        return table == null ? null : table.getCommonPropertyType(context, base);
    }
}

