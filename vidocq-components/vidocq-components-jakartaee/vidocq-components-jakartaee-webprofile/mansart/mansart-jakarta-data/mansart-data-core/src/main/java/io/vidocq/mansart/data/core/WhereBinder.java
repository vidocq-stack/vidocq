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
import io.vidocq.mansart.data.dialect.Dialect;
import io.vidocq.mansart.data.dialect.Where;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Walks a {@link Where} AST in render order and binds parameters from a flat {@code args[]} array.
 * The traversal MUST mirror the order the dialect's {@code SqlFragment} produces ({@code ?} marks).
 */
final class WhereBinder {

    private WhereBinder() {}

    static int bind(Dialect dialect, PreparedStatement ps, Where where,
                    Object[] args, int psIdx, int[] argCursor) throws SQLException {
        switch (where) {
            case Where.Eq(var attr)        -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.NotEq(var attr)     -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.Lt(var attr)        -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.Lte(var attr)       -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.Gt(var attr)        -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.Gte(var attr)       -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.Like(var attr)      -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            case Where.Between(var attr)   -> {
                psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
                psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
            }
            case Where.In(var attr, var arity) -> {
                for (int i = 0; i < arity; i++) {
                    psIdx = bindOne(dialect, ps, psIdx, args, argCursor, attr);
                }
            }
            case Where.IsNull _ -> { /* no bind */ }
            case Where.IsNotNull _ -> { /* no bind */ }
            case Where.And(var children) -> {
                for (Where child : children) psIdx = bind(dialect, ps, child, args, psIdx, argCursor);
            }
            case Where.Or(var children) -> {
                for (Where child : children) psIdx = bind(dialect, ps, child, args, psIdx, argCursor);
            }
            case Where.Not(var child) -> psIdx = bind(dialect, ps, child, args, psIdx, argCursor);
            // M7-27 — IgnoreCase wraps a single text comparator that consumes exactly one arg.
            // The dialect renders LOWER(col) <op> LOWER(?), so we still bind one parameter.
            case Where.IgnoreCase(var inner) -> psIdx = bind(dialect, ps, inner, args, psIdx, argCursor);
            // M8-1 — Func(fn, inner) wraps a comparator with a unary scalar function on the
            // column. UPPER/LOWER/ABS preserve the bound parameter type. LENGTH returns the
            // character count, so the bound parameter must be Integer instead of the column's
            // declared Java type.
            case Where.Func w -> psIdx = bindFunc(dialect, ps, w, args, psIdx, argCursor);
            case Where.AlwaysTrue _  -> { /* no bind */ }
            case Where.AlwaysFalse _ -> { /* no bind */ }
        }
        return psIdx;
    }

    private static int bindOne(Dialect dialect, PreparedStatement ps, int psIdx,
                               Object[] args, int[] cursor, Attribute<?, ?> attr) throws SQLException {
        Object value = args[cursor[0]++];
        dialect.bind(ps, psIdx, ColumnValues.toColumn(attr, value), ColumnValues.columnType(attr));
        return psIdx + 1;
    }

    /** Binds the next argument as {@code type}, for a function whose result type is not the column's ({@code LENGTH}). */
    private static int bindOne(Dialect dialect, PreparedStatement ps, int psIdx,
                               Object[] args, int[] cursor, Class<?> type) throws SQLException {
        Object value = args[cursor[0]++];
        dialect.bind(ps, psIdx, value, type);
        return psIdx + 1;
    }

    /**
     * M8-1 — bind for {@link Where.Func}. {@code LENGTH} forces Integer typing on bound
     * parameters because the SQL function returns a character count regardless of the column
     * type. Other unary functions ({@code UPPER}/{@code LOWER}/{@code ABS}) preserve the
     * column's Java type.
     */
    private static int bindFunc(Dialect dialect, PreparedStatement ps, Where.Func func,
                                Object[] args, int psIdx, int[] argCursor) throws SQLException {
        if (!"LENGTH".equals(func.fn())) {
            return bind(dialect, ps, func.inner(), args, psIdx, argCursor);
        }
        return bindFuncWithType(dialect, ps, func.inner(), args, psIdx, argCursor, Integer.class);
    }

    private static int bindFuncWithType(Dialect dialect, PreparedStatement ps, Where inner,
                                        Object[] args, int psIdx, int[] argCursor,
                                        Class<?> overrideType) throws SQLException {
        return switch (inner) {
            case Where.Eq _      -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.NotEq _   -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.Lt _      -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.Lte _     -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.Gt _      -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.Gte _     -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.Like _    -> bindOne(dialect, ps, psIdx, args, argCursor, overrideType);
            case Where.Between _ -> bindOne(dialect, ps,
                    bindOne(dialect, ps, psIdx, args, argCursor, overrideType),
                    args, argCursor, overrideType);
            case Where.In w            -> {
                int p = psIdx;
                for (int i = 0; i < w.arity(); i++) {
                    p = bindOne(dialect, ps, p, args, argCursor, overrideType);
                }
                yield p;
            }
            case Where.IsNull _    -> psIdx;
            case Where.IsNotNull _ -> psIdx;
            case Where.Not(var child)             -> bindFuncWithType(dialect, ps, child, args, psIdx, argCursor, overrideType);
            default -> throw new IllegalArgumentException(
                    "Func wraps Eq/NotEq/Lt/Lte/Gt/Gte/Like/Between/In/IsNull/IsNotNull/Not only, got: " + inner);
        };
    }
}
