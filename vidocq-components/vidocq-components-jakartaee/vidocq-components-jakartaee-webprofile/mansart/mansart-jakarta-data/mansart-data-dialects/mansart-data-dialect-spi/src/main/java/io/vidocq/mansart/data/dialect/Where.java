package io.vidocq.mansart.data.dialect;

import java.util.List;

/**
 * Backend-neutral predicate AST. Lowered to SQL by {@link Dialect#select}.
 */
public sealed interface Where {

    Where ALWAYS_TRUE  = new AlwaysTrue();
    Where ALWAYS_FALSE = new AlwaysFalse();

    record Eq(Attribute<?, ?> attr) implements Where {}
    record NotEq(Attribute<?, ?> attr) implements Where {}
    record Lt(Attribute<?, ?> attr) implements Where {}
    record Lte(Attribute<?, ?> attr) implements Where {}
    record Gt(Attribute<?, ?> attr) implements Where {}
    record Gte(Attribute<?, ?> attr) implements Where {}
    record Like(Attribute<?, ?> attr) implements Where {}
    record In(Attribute<?, ?> attr, int arity) implements Where {}
    record Between(Attribute<?, ?> attr) implements Where {}
    record IsNull(Attribute<?, ?> attr) implements Where {}
    record IsNotNull(Attribute<?, ?> attr) implements Where {}
    record And(List<Where> children) implements Where {
        public And { children = List.copyOf(children); }
    }
    record Or(List<Where> children) implements Where {
        public Or { children = List.copyOf(children); }
    }
    record Not(Where child) implements Where {}
    /**
     * M7-27 — wraps a text comparator (Eq, NotEq, Like) so the dialect emits
     * {@code LOWER(col) <op> LOWER(?)} instead of relying on the caller to
     * lowercase the bound argument. Honors database collation/locale and lets
     * functional indexes on {@code LOWER(col)} kick in.
     */
    record IgnoreCase(Where inner) implements Where {}
    /**
     * M8-1 — wraps a comparator with a unary scalar function applied to the column.
     * {@code fn} is one of {@code "UPPER"}, {@code "LOWER"}, {@code "LENGTH"}, {@code "ABS"}.
     *
     * <p>For {@code UPPER}/{@code LOWER}/{@code ABS}, the bound parameter type matches the
     * column's Java type. For {@code LENGTH}, the bound parameter is forced to {@link Integer}
     * (returns the character count of a String). Dialects map {@code LENGTH} to the SQL
     * portable {@code CHAR_LENGTH(...)}.
     */
    record Func(String fn, Where inner) implements Where {}
    record AlwaysTrue() implements Where {}
    record AlwaysFalse() implements Where {}

    static Where eq(Attribute<?, ?> a)        { return new Eq(a); }
    static Where between(Attribute<?, ?> a)   { return new Between(a); }
    static Where in(Attribute<?, ?> a, int n) { return new In(a, n); }
    static Where and(Where... ws)             { return new And(List.of(ws)); }
    static Where or(Where... ws)              { return new Or(List.of(ws)); }
}
