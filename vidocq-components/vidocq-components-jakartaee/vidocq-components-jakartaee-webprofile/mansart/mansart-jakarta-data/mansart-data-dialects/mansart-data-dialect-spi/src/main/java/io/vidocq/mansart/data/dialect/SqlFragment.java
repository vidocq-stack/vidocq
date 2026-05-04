package io.vidocq.mansart.data.dialect;

import java.util.List;

/**
 * Rendered SQL fragment with the ordered list of bind sites it expects.
 *
 * <p>{@link #binds()} is a flat list of {@link BindSite} entries describing the order in which
 * arguments must be set on the {@link java.sql.PreparedStatement}. Composite predicates (e.g.
 * {@link Where.Between}, {@link Where.In}) expand to multiple bind sites at render time.
 */
public record SqlFragment(String sql, List<BindSite> binds) {

    public SqlFragment { binds = List.copyOf(binds); }

    /** Describes one parameter slot in the rendered SQL. */
    public record BindSite(Attribute<?, ?> target, BindKind kind) {
        public enum BindKind { VALUE, RANGE_LOW, RANGE_HIGH, IN_ELEMENT, KEYSET, LIMIT, OFFSET }
    }
}
