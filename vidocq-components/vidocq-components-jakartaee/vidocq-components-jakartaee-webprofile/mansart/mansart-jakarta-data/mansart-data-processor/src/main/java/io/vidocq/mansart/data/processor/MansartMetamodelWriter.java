package io.vidocq.mansart.data.processor;

import io.vidocq.mansart.data.processor.EntityScanner.AttributeDescriptor;
import io.vidocq.mansart.data.processor.EntityScanner.EntityDescriptor;

import javax.annotation.processing.Filer;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.PrintWriter;

/** Writes the Mansart-rich metamodel class {@code _Book} for an entity {@code Book}. */
final class MansartMetamodelWriter {

    private final Filer filer;

    MansartMetamodelWriter(Filer filer) { this.filer = filer; }

    void write(EntityDescriptor e) throws IOException {
        String pkg = packageOf(e.type().getQualifiedName().toString());
        String simple = e.type().getSimpleName().toString();
        String className = "_" + simple;
        String fqn = pkg.isEmpty() ? className : pkg + "." + className;

        JavaFileObject file = filer.createSourceFile(fqn, e.type());
        try (PrintWriter w = new PrintWriter(file.openWriter())) {
            if (!pkg.isEmpty()) {
                w.println("package " + pkg + ";");
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
            w.println("            LOOKUP = MethodHandles.privateLookupIn(" + simple + ".class, MethodHandles.lookup());");
            w.println("        } catch (IllegalAccessException ex) {");
            w.println("            throw new ExceptionInInitializerError(ex);");
            w.println("        }");
            w.println("    }");
            w.println();
            for (AttributeDescriptor a : e.attributes()) {
                writeAttribute(w, simple, a, a == e.id(), a == e.version());
            }
            writeModel(w, simple, e);
            w.println("}");
        }
    }

    private void writeAttribute(PrintWriter w, String entitySimple, AttributeDescriptor a,
                                boolean isId, boolean isVersion) {
        String boxed = box(a.javaTypeFqn());
        String getterMh = "getterMh(\"" + a.name() + "\", " + boxed + ".class)";
        String setterMh = "setterMh(\"" + a.name() + "\", " + boxed + ".class)";

        switch (a.kind()) {
            case ID -> w.println("    public static final IdAttribute<" + entitySimple + ", " + boxed + "> "
                    + a.name() + " = new IdAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entitySimple + ".class, " + a.generated() + ", "
                    + getterMh + ", " + setterMh + ");");
            case VERSION -> w.println("    public static final VersionAttribute<" + entitySimple + ", " + boxed + "> "
                    + a.name() + " = new VersionAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entitySimple + ".class, " + getterMh + ", " + setterMh + ");");
            case TEXT -> w.println("    public static final TextAttribute<" + entitySimple + "> "
                    + a.name() + " = new TextAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + entitySimple + ".class, " + a.nullable() + ", " + a.unique() + ", "
                    + a.length() + ", " + getterMh + ", " + setterMh + ");");
            case NUMERIC -> w.println("    public static final NumericAttribute<" + entitySimple + ", " + boxed + "> "
                    + a.name() + " = new NumericAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entitySimple + ".class, " + a.nullable() + ", " + a.unique()
                    + ", 0, 0, " + getterMh + ", " + setterMh + ");");
            case TEMPORAL -> w.println("    public static final TemporalAttribute<" + entitySimple + ", " + boxed + "> "
                    + a.name() + " = new TemporalAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entitySimple + ".class, " + a.nullable() + ", " + a.unique()
                    + ", " + getterMh + ", " + setterMh + ");");
            case REFERENCE -> w.println("    public static final ReferenceAttribute<" + entitySimple + ", " + boxed + "> "
                    + a.name() + " = new ReferenceAttribute<>(\"" + a.name() + "\", \"" + a.columnName() + "\", "
                    + boxed + ".class, " + entitySimple + ".class, " + a.nullable() + ", " + a.unique()
                    + ", false, \"" + a.referencedColumn() + "\", " + getterMh + ", " + setterMh + ");");
            case ENUM -> w.println("    @SuppressWarnings({\"rawtypes\", \"unchecked\"})\n"
                    + "    public static final EnumAttribute " + a.name() + " = new EnumAttribute(\""
                    + a.name() + "\", \"" + a.columnName() + "\", " + boxed + ".class, "
                    + entitySimple + ".class, " + a.nullable() + ", " + a.unique()
                    + ", io.vidocq.mansart.data.dialect.attribute.EnumStorage.ORDINAL, "
                    + getterMh + ", " + setterMh + ");");
        }
        w.println();
    }

    private void writeModel(PrintWriter w, String entitySimple, EntityDescriptor e) {
        w.println("    public static final EntityModel<" + entitySimple + "> $MODEL = new EntityModel<>(");
        w.println("        " + entitySimple + ".class,");
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
        w.println("        try { return LOOKUP.findGetter(" + entitySimple + ".class, fieldName, type); }");
        w.println("        catch (NoSuchFieldException | IllegalAccessException ex) { throw new ExceptionInInitializerError(ex); }");
        w.println("    }");
        w.println();
        w.println("    private static MethodHandle setterMh(String fieldName, Class<?> type) {");
        w.println("        try { return LOOKUP.findSetter(" + entitySimple + ".class, fieldName, type); }");
        w.println("        catch (NoSuchFieldException | IllegalAccessException ex) { throw new ExceptionInInitializerError(ex); }");
        w.println("    }");
        w.println();
        w.println("    private static MethodHandle constructorMh() {");
        w.println("        try { return LOOKUP.findConstructor(" + entitySimple + ".class, java.lang.invoke.MethodType.methodType(void.class)); }");
        w.println("        catch (NoSuchMethodException | IllegalAccessException ex) {");
        w.println("            throw new ExceptionInInitializerError(\"" + entitySimple + " must have a no-arg constructor\");");
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
