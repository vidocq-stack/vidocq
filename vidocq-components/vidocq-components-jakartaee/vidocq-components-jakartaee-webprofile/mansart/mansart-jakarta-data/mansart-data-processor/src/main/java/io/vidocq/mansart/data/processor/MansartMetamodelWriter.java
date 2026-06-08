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
package io.vidocq.mansart.data.processor;

import io.vidocq.mansart.data.processor.EntityScanner.AttributeDescriptor;
import io.vidocq.mansart.data.processor.EntityScanner.EntityDescriptor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;

/**
 * Writes the Mansart-rich metamodel class {@code _Book} for an entity {@code Book}.
 *
 * <p>Two emission modes:
 * <ul>
 *   <li><b>In-place</b> (APT) — {@link #write(EntityDescriptor)} emits {@code _Book} in the
 *       entity's own package, referring to the entity by simple name.</li>
 *   <li><b>Relocated</b> (Maven plugin / {@link ExternalRepositoryCodegen}) —
 *       {@link #write(EntityDescriptor, String, String, String)} emits the metamodel into an
 *       application-owned package and refers to the (external) entity by its fully-qualified name,
 *       avoiding a JPMS split package with the dependency jar that owns the entity.</li>
 * </ul>
 */
final class MansartMetamodelWriter {

    private final SourceSink sink;

    MansartMetamodelWriter(SourceSink sink) { this.sink = sink; }

    /** In-place emission: metamodel lives in the entity's package, entity referenced by simple name. */
    void write(EntityDescriptor e) throws IOException {
        String pkg = packageOf(e.type().getQualifiedName().toString());
        String simple = e.type().getSimpleName().toString();
        write(e, pkg, "_" + simple, simple);
    }

    /**
     * Relocated emission.
     *
     * @param e          the scanned entity
     * @param targetPkg  package the metamodel class is emitted into (may be {@code ""})
     * @param className  simple name of the generated metamodel class (e.g. {@code _Book})
     * @param entityRef  how the entity type is referenced in generated code — a simple name when
     *                   the metamodel sits in the entity's package, or a fully-qualified name when
     *                   relocated elsewhere
     */
    void write(EntityDescriptor e, String targetPkg, String className, String entityRef) throws IOException {
        String fqn = targetPkg.isEmpty() ? className : targetPkg + "." + className;

        try (PrintWriter w = new PrintWriter(sink.createSource(fqn))) {
            if (!targetPkg.isEmpty()) {
                w.println("package " + targetPkg + ";");
                w.println();
            }
            w.println("import io.vidocq.mansart.data.dialect.EntityModel;");
            w.println("import io.vidocq.mansart.data.dialect.attribute.*;");
            w.println("import java.lang.invoke.MethodHandle;");
            w.println("import java.lang.invoke.MethodHandles;");
            w.println("import java.util.List;");
            w.println("import java.util.Optional;");
            w.println("import javax.annotation.processing.Generated;");
            w.println();
            w.println("@Generated(\"io.vidocq.mansart.data.processor.MansartProcessor\")");
            w.println("public final class " + className + " {");
            w.println();
            w.println("    private " + className + "() {}");
            w.println();
            w.println("    private static final MethodHandles.Lookup LOOKUP;");
            w.println("    static {");
            w.println("        try {");
            w.println("            LOOKUP = MethodHandles.privateLookupIn(" + entityRef + ".class, MethodHandles.lookup());");
            w.println("        } catch (IllegalAccessException ex) {");
            w.println("            throw new ExceptionInInitializerError(ex);");
            w.println("        }");
            w.println("    }");
            w.println();
            for (AttributeDescriptor a : e.attributes()) {
                writeAttribute(w, entityRef, a, a == e.id(), a == e.version());
            }
            writeModel(w, entityRef, e);
            w.println("}");
        }
    }

    private void writeAttribute(PrintWriter w, String entityRef, AttributeDescriptor a,
                                boolean isId, boolean isVersion) {
        String boxed = box(a.javaTypeFqn());
        // For findGetter/findSetter we MUST use the raw declared type — passing Double.class
        // for a `double` field fails with NoSuchFieldException because MethodHandles.Lookup
        // does not auto-box. The boxed type stays in use for the typed Attribute<E, T> generics.
        String fieldType = a.javaTypeFqn();
        String getterMh = "getterMh(\"" + a.name() + "\", " + fieldType + ".class)";
        String setterMh = "setterMh(\"" + a.name() + "\", " + fieldType + ".class)";

        switch (a.kind()) {
            case ID -> w.println("    public static final IdAttribute<" + entityRef + ", " + boxed + "> "
                    + a.name() + " = new IdAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entityRef + ".class, " + a.generated() + ", "
                    + getterMh + ", " + setterMh + ");");
            case VERSION -> w.println("    public static final VersionAttribute<" + entityRef + ", " + boxed + "> "
                    + a.name() + " = new VersionAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entityRef + ".class, " + getterMh + ", " + setterMh + ");");
            case TEXT -> w.println("    public static final TextAttribute<" + entityRef + "> "
                    + a.name() + " = new TextAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + entityRef + ".class, " + a.nullable() + ", " + a.unique() + ", "
                    + a.length() + ", " + getterMh + ", " + setterMh + ");");
            case NUMERIC -> w.println("    public static final NumericAttribute<" + entityRef + ", " + boxed + "> "
                    + a.name() + " = new NumericAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entityRef + ".class, " + a.nullable() + ", " + a.unique()
                    + ", 0, 0, " + getterMh + ", " + setterMh + ");");
            case BOOLEAN -> w.println("    public static final BooleanAttribute<" + entityRef + "> "
                    + a.name() + " = new BooleanAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + entityRef + ".class, " + a.nullable() + ", " + a.unique() + ", "
                    + getterMh + ", " + setterMh + ");");
            case TEMPORAL -> w.println("    public static final TemporalAttribute<" + entityRef + ", " + boxed + "> "
                    + a.name() + " = new TemporalAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entityRef + ".class, " + a.nullable() + ", " + a.unique()
                    + ", " + getterMh + ", " + setterMh + ");");
            case REFERENCE -> w.println("    public static final ReferenceAttribute<" + entityRef + ", " + boxed + "> "
                    + a.name() + " = new ReferenceAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entityRef + ".class, " + a.nullable() + ", " + a.unique()
                    + ", false, \"" + a.referencedColumn() + "\", " + getterMh + ", " + setterMh + ");");
            case ENUM -> w.println("    @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n"
                    + "    public static final EnumAttribute " + a.name() + " = new EnumAttribute(\""
                    + a.name() + "\", \"" + a.columnName() + "\", " + boxed + ".class, "
                    + entityRef + ".class, " + a.nullable() + ", " + a.unique()
                    + ", io.vidocq.mansart.data.dialect.attribute.EnumStorage.ORDINAL, "
                    + getterMh + ", " + setterMh + ");");
        }
        w.println();
    }

    private void writeModel(PrintWriter w, String entityRef, EntityDescriptor e) {
        w.println("    public static final EntityModel<" + entityRef + "> $MODEL = new EntityModel<>(");
        w.println("        " + entityRef + ".class,");
        w.println("        \"" + e.tableName() + "\",");
        w.println("        \"" + e.schema() + "\",");
        w.println("        " + e.id().name() + ",");
        if (e.version() != null) {
            w.println("        Optional.of(" + e.version().name() + "),");
        } else {
            w.println("        Optional.empty(),");
        }
        StringBuilder list = new StringBuilder("        List.of(");
        for (int i = 0; i < e.attributes().size(); i++) {
            if (i > 0) list.append(", ");
            list.append(e.attributes().get(i).name());
        }
        list.append("),");
        w.println(list);
        w.println("        constructorMh());");
        w.println();
        w.println("    private static MethodHandle getterMh(String fieldName, Class<?> type) {");
        w.println("        try { return LOOKUP.findGetter(" + entityRef + ".class, fieldName, type); }");
        w.println("        catch (NoSuchFieldException | IllegalAccessException ex) { throw new ExceptionInInitializerError(ex); }");
        w.println("    }");
        w.println();
        w.println("    private static MethodHandle setterMh(String fieldName, Class<?> type) {");
        w.println("        try { return LOOKUP.findSetter(" + entityRef + ".class, fieldName, type); }");
        w.println("        catch (NoSuchFieldException | IllegalAccessException ex) { throw new ExceptionInInitializerError(ex); }");
        w.println("    }");
        w.println();
        w.println("    private static MethodHandle constructorMh() {");
        w.println("        try { return LOOKUP.findConstructor(" + entityRef + ".class, java.lang.invoke.MethodType.methodType(void.class)); }");
        w.println("        catch (NoSuchMethodException | IllegalAccessException ex) {");
        w.println("            throw new ExceptionInInitializerError(\"" + entityRef + " must have a no-arg constructor\");");
        w.println("        }");
        w.println("    }");
    }

    private static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    static String box(String javaType) {
        return switch (javaType) {
            case "boolean" -> "java.lang.Boolean";
            case "byte"    -> "java.lang.Byte";
            case "short"   -> "java.lang.Short";
            case "int"     -> "java.lang.Integer";
            case "long"    -> "java.lang.Long";
            case "float"   -> "java.lang.Float";
            case "double"  -> "java.lang.Double";
            case "char"    -> "java.lang.Character";
            default        -> javaType;
        };
    }
}
