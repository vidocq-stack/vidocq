package io.vidocq.mansart.data.core;

import io.vidocq.mansart.data.dialect.Attribute;
import io.vidocq.mansart.data.dialect.EntityModel;
import io.vidocq.mansart.data.dialect.attribute.JoinPath;
import io.vidocq.mansart.data.dialect.attribute.JoinedAttribute;
import io.vidocq.mansart.data.dialect.attribute.ReferenceAttribute;

/**
 * M8-3 — resolves a JDQL path expression (e.g. {@code "book.author.name"}) against an
 * {@link EntityModel}, returning either:
 * <ul>
 *   <li>the leaf {@link Attribute} directly when the path has a single segment</li>
 *   <li>a {@link JoinedAttribute} wrapping the leaf with a {@link JoinPath} when ≥ 2 segments</li>
 * </ul>
 *
 * <p>Each non-final segment must point at a {@link ReferenceAttribute} (a
 * {@code @ManyToOne}/{@code @OneToOne}). Target metamodels are resolved through
 * {@link EntityModels#lookup(Class)} (cached). Throws {@link MansartDataException} on unknown
 * segments or non-relation intermediates.
 *
 * <p>The resolved object — {@link JoinedAttribute} when joined — carries enough information for
 * the dialect to allocate aliases, render JOIN clauses, and qualify the final column.
 */
// M8-3 — promoted to public so generated repository impls (in user packages) can call
// `PathResolver.resolve(metamodel.$MODEL, "author.name")` for path expressions.
public final class PathResolver {

    private PathResolver() {}

    public static Attribute<?, ?> resolve(EntityModel<?> rootModel, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            return rootModel.attribute(path).orElseThrow(() ->
                    new MansartDataException("Unknown attribute '" + path + "' on "
                            + rootModel.entityClass().getSimpleName()));
        }
        String[] segments = path.split("\\.");
        java.util.List<JoinPath.Step> steps = new java.util.ArrayList<>(segments.length - 1);
        EntityModel<?> currentModel = rootModel;
        for (int i = 0; i < segments.length - 1; i++) {
            final String seg = segments[i];
            final EntityModel<?> here = currentModel;
            Attribute<?, ?> a = here.attribute(seg).orElseThrow(() ->
                    new MansartDataException("Unknown segment '" + seg + "' in path '" + path
                            + "' on " + here.entityClass().getSimpleName()));
            if (!(a instanceof ReferenceAttribute<?, ?> ref)) {
                throw new MansartDataException("Path segment '" + seg + "' in '" + path
                        + "' is not a @ManyToOne/@OneToOne relation");
            }
            EntityModel<?> targetModel = EntityModels.lookup(ref.javaType());
            steps.add(new JoinPath.Step(
                    ref.name(),
                    ref.columnName(),
                    ref.referencedColumn(),
                    targetModel.tableName(),
                    targetModel.schema() == null ? "" : targetModel.schema(),
                    ref.javaType()));
            currentModel = targetModel;
        }
        final String leafName = segments[segments.length - 1];
        final EntityModel<?> leafModel = currentModel;
        Attribute<?, ?> leaf = leafModel.attribute(leafName).orElseThrow(() ->
                new MansartDataException("Unknown leaf '" + leafName + "' in path '" + path
                        + "' on " + leafModel.entityClass().getSimpleName()));
        return new JoinedAttribute<>(leaf, new JoinPath(steps), rootModel.entityClass());
    }
}
