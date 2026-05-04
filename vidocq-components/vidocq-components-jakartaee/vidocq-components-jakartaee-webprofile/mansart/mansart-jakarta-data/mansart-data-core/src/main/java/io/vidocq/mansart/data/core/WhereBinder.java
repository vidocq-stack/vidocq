package io.vidocq.mansart.data.core;

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
            case Where.Eq w        -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.NotEq w     -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.Lt w        -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.Lte w       -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.Gt w        -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.Gte w       -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.Like w      -> psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            case Where.Between w   -> {
                psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
                psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
            }
            case Where.In w -> {
                for (int i = 0; i < w.arity(); i++) {
                    psIdx = bindOne(dialect, ps, psIdx, args, argCursor, w.attr().javaType());
                }
            }
            case Where.IsNull ignored1 -> { /* no bind */ }
            case Where.IsNotNull ignored2 -> { /* no bind */ }
            case Where.And w -> {
                for (Where child : w.children()) psIdx = bind(dialect, ps, child, args, psIdx, argCursor);
            }
            case Where.Or w -> {
                for (Where child : w.children()) psIdx = bind(dialect, ps, child, args, psIdx, argCursor);
            }
            case Where.Not w -> psIdx = bind(dialect, ps, w.child(), args, psIdx, argCursor);
            case Where.AlwaysTrue ignored3  -> { /* no bind */ }
            case Where.AlwaysFalse ignored4 -> { /* no bind */ }
        }
        return psIdx;
    }

    private static int bindOne(Dialect dialect, PreparedStatement ps, int psIdx,
                               Object[] args, int[] cursor, Class<?> type) throws SQLException {
        Object value = args[cursor[0]++];
        dialect.bind(ps, psIdx, value, type);
        return psIdx + 1;
    }
}
