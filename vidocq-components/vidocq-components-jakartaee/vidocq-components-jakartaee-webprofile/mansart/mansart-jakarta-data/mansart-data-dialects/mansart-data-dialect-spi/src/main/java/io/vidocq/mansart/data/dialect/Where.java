package io.vidocq.mansart.data.dialect;

import java.util.List;

/**
 * Backend-neutral predicate AST. Lowered to SQL by {@link Dialect#select}.
 */
public sealed interface Where {

    Where ALWAYS_TRUE = new AlwaysTrue();

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
    record AlwaysTrue() implements Where {}

    static Where eq(Attribute<?, ?> a)        { return new Eq(a); }
    static Where between(Attribute<?, ?> a)   { return new Between(a); }
    static Where in(Attribute<?, ?> a, int n) { return new In(a, n); }
    static Where and(Where... ws)             { return new And(List.of(ws)); }
    static Where or(Where... ws)              { return new Or(List.of(ws)); }
}
