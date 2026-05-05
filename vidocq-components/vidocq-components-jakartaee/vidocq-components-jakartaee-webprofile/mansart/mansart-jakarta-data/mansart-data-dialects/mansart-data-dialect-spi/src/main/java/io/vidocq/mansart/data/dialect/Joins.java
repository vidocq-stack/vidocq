package io.vidocq.mansart.data.dialect;

import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * M8-3 — helpers for dialects: collect distinct {@link JoinPath}s reachable from a {@link Where}
 * (and {@link OrderBy}) AST, allocate stable {@code t1, t2, …} aliases, and expose alias lookup
 * for predicate/order rendering. Root table always gets {@code t0}.
 *
 * <p>Returns an empty plan when no {@link JoinedAttribute} appears anywhere — the dialect can
 * then keep emitting non-aliased SQL ({@code "col"} rather than {@code t0."col"}) for backwards
 * compatibility with existing single-table queries.
 */
public final class Joins {

    private Joins() {}

    public static final String ROOT_ALIAS = "t0";

    /**
     * Walks {@code where} and {@code orderBy}, collects every distinct join path (and all of
     * its prefixes — required so an inner relation also gets a JOIN clause). Allocates an alias
     * per path in registration order: the shortest paths come first, so deeper chains build on
     * top of their already-aliased prefixes.
     */
    public static Plan collect(Where where, OrderBy orderBy) {
        // Use an ordered set to register paths from shallowest to deepest. We rely on JoinPath
        // structural equality — same chain = same key.
        java.util.LinkedHashSet<JoinPath> ordered = new java.util.LinkedHashSet<>();
        Consumer<Attribute<?, ?>> visit = a -> {
            if (a instanceof JoinedAttribute<?, ?> joined) registerWithPrefixes(joined.path(), ordered);
        };
        walkWhere(where, visit);
        if (orderBy != null) {
            for (var o : orderBy.orders()) visit.accept(o.attr());
        }

        LinkedHashMap<JoinPath, String> aliasByPath = new LinkedHashMap<>();
        int i = 1;
        for (JoinPath p : ordered) {
            aliasByPath.put(p, "t" + (i++));
        }
        return new Plan(aliasByPath);
    }

    private static void registerWithPrefixes(JoinPath p, java.util.LinkedHashSet<JoinPath> sink) {
        for (int n = 1; n <= p.steps().size(); n++) sink.add(p.prefix(n));
    }

    private static void walkWhere(Where w, Consumer<Attribute<?, ?>> sink) {
        if (w == null) return;
        switch (w) {
            case Where.Eq x        -> sink.accept(x.attr());
            case Where.NotEq x     -> sink.accept(x.attr());
            case Where.Lt x        -> sink.accept(x.attr());
            case Where.Lte x       -> sink.accept(x.attr());
            case Where.Gt x        -> sink.accept(x.attr());
            case Where.Gte x       -> sink.accept(x.attr());
            case Where.Like x      -> sink.accept(x.attr());
            case Where.In x        -> sink.accept(x.attr());
            case Where.Between x   -> sink.accept(x.attr());
            case Where.IsNull x    -> sink.accept(x.attr());
            case Where.IsNotNull x -> sink.accept(x.attr());
            case Where.And x       -> { for (Where c : x.children()) walkWhere(c, sink); }
            case Where.Or x        -> { for (Where c : x.children()) walkWhere(c, sink); }
            case Where.Not x       -> walkWhere(x.child(), sink);
            case Where.IgnoreCase x -> walkWhere(x.inner(), sink);
            case Where.Func x      -> walkWhere(x.inner(), sink);
            case Where.AlwaysTrue ignored -> {}
            case Where.AlwaysFalse ignored -> {}
        }
    }

    /**
     * Per-query plan: the alias allocated to each join path (root excluded — always {@code t0}).
     * {@link #aliasFor(Attribute)} returns the alias for a {@link JoinedAttribute}, or
     * {@code null} for plain attributes (the dialect should fall back to the root alias if
     * non-empty, or no prefix at all if {@link #isEmpty()}).
     */
    public record Entry(JoinPath path, String alias) {}

    public record Plan(LinkedHashMap<JoinPath, String> aliasByPath) {

        public boolean isEmpty() { return aliasByPath.isEmpty(); }

        public List<Entry> entriesInOrder() {
            List<Entry> out = new ArrayList<>(aliasByPath.size());
            for (var e : aliasByPath.entrySet()) out.add(new Entry(e.getKey(), e.getValue()));
            return out;
        }

        public String aliasFor(Attribute<?, ?> a) {
            if (a instanceof JoinedAttribute<?, ?> joined) {
                String alias = aliasByPath.get(joined.path());
                if (alias == null) {
                    throw new IllegalStateException("No alias allocated for join path " + joined.path());
                }
                return alias;
            }
            return null;
        }

        /** Returns the alias for the table that owns {@code a}: alias for joined leaf, root for plain. */
        public String tableAliasFor(Attribute<?, ?> a) {
            String j = aliasFor(a);
            return j != null ? j : ROOT_ALIAS;
        }
    }
}
