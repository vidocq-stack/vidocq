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

import jakarta.el.ELResolver;
import jakarta.el.ELContext;
import jakarta.el.MethodReference;

/**
 * Application-owned resolver using direct property and method calls for one runtime type.
 * Tables follow the ELResolver resolution-flag contract: claim handled operations
 * with setPropertyResolved(base, property), including resolved null values, and leave
 * unknown operations unresolved. Writable types describe setter inputs; read-only
 * properties return null from getType. Operand conversion uses the supplied ELContext.
 *
 * Tables must be safe for concurrent use and keep evaluation state in the ELContext.
 * Selection tries the exact runtime class, then its superclasses nearest first (Object
 * excluded), then its interfaces breadth-first; each operation goes to those tables in that
 * order until one resolves it, so a table that leaves an operation unresolved passes it on. A
 * table keyed on a public interface therefore serves anonymous and private implementations.
 * Static access through an ELClass base uses the exact class only.
 * This contract can be implemented by generated code or by a handwritten table.
 * Functional-interface tables handle convertToType by exact target interface.
 * Factory-driven lambda conversion supplies a null context; adapters then use the
 * lambda's captured context and ExpressionFactory result coercion. Ordinary resolver
 * conversion supplies the caller context and retains custom conversion precedence.
 */
public abstract class AccessorTable extends ELResolver {
    /** Creates an accessor table. */
    protected AccessorTable() {}

    /**
     * Returns the class or interface this table is registered for.
     *
     * @return a non-null target type
     */
    public abstract Class<?> targetType();

    /**
     * Returns generated metadata without invoking the referenced method.
     *
     * @param context evaluation context
     * @param base method target
     * @param method method name
     * @param parameterTypes declared parameter types or null for inline arguments
     * @param arguments raw evaluated arguments
     * @return reference or null if this table supplies no metadata for the method
     */
    public MethodReference getMethodReference(ELContext context, Object base, String method,
            Class<?>[] parameterTypes, Object[] arguments) {
        return null;
    }
}

