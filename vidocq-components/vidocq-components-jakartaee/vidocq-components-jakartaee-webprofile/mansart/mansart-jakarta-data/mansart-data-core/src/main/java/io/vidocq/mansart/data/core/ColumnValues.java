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
package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.attribute.EnumAttribute;
import io.vidocq.mansart.data.dialect.attribute.EnumStorage;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;

/**
 * How an attribute's value is held in its column, for the few attributes whose column type is not their Java
 * type: an {@link EnumStorage#ORDINAL} enum is an {@code Integer} index (MANSART-003), a reference is its FK id.
 * Every bind and every read of an attribute's column goes through here, so the dialects keep binding by Java type.
 */
final class ColumnValues {

    private ColumnValues() {}

    /** The Java type the column is bound and read as. */
    static Class<?> columnType(Attribute<?, ?> a) {
        if (a instanceof ReferenceAttribute<?, ?>) return Long.class;
        if (isOrdinal(a)) return Integer.class;
        return a.javaType();
    }

    /** The Java type the column is declared from in DDL: as {@link #columnType}, except references (unchanged). */
    static Class<?> ddlType(Attribute<?, ?> a) {
        return isOrdinal(a) ? Integer.class : a.javaType();
    }

    /** {@code value} as the column holds it. */
    static Object toColumn(Attribute<?, ?> a, Object value) {
        if (value instanceof Enum<?> e && isOrdinal(a)) return e.ordinal();
        return value;
    }

    /** A value read from the column, as the attribute holds it. */
    static Object fromColumn(Attribute<?, ?> a, Object value) {
        if (value instanceof Integer index && isOrdinal(a)) {
            Object[] constants = a.javaType().getEnumConstants();
            if (index < 0 || index >= constants.length) {
                throw new MansartDataException("Column " + a.columnName() + " holds " + index + ", which is no index of "
                        + a.javaType().getName() + " (" + constants.length + " constants)");
            }
            return constants[index];
        }
        return value;
    }

    private static boolean isOrdinal(Attribute<?, ?> a) {
        return a instanceof EnumAttribute<?, ?> e && e.storage() == EnumStorage.ORDINAL;
    }
}
