package io.vidocq.mansart.data.processor;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Walks an {@code @jakarta.persistence.Entity}-annotated {@link TypeElement} and produces a
 * {@link EntityDescriptor} by reading the standard JPA mapping annotations
 * ({@code @Id}, {@code @Column}, {@code @Table}, {@code @Version}, {@code @ManyToOne},
 * {@code @JoinColumn}, …).
 *
 * <p>Mansart used to ship its own mirror annotations ({@code @io.vidocq.mansart.data.Entity},
 * etc.) for « zero-dep » purity; that was retired in M7-29 — {@code jakarta.persistence-api}
 * is a spec API jar with no implementation, so the philosophy holds with the standard names.
 */
final class EntityScanner {

    private final ProcessingEnvironment env;

    EntityScanner(ProcessingEnvironment env) {
        this.env = env;
    }

    EntityDescriptor scan(TypeElement type) {
        if (!hasAnnotation(type, "jakarta.persistence.Entity")) {
            return null;
        }

        AnnotationDialect dialect = AnnotationDialect.JPA;
        String tableName = readEntityTable(type, dialect);
        String schema    = readEntitySchema(type, dialect);

        List<AttributeDescriptor> attributes = new ArrayList<>();
        AttributeDescriptor       id         = null;
        AttributeDescriptor       version    = null;

        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.FIELD) continue;
            if (member.getModifiers().contains(Modifier.STATIC)) continue;
            if (member.getModifiers().contains(Modifier.TRANSIENT)) continue;
            if (hasAnnotation(member, dialect.transientAnno)) continue;

            VariableElement field = (VariableElement) member;
            AttributeDescriptor a = describe(field, dialect);
            if (a == null) continue;

            attributes.add(a);
            if (a.kind == AttributeKind.ID) {
                if (id != null) {
                    error(field, "Multiple @Id fields are not supported in v1.");
                    return null;
                }
                id = a;
            }
            if (a.kind == AttributeKind.VERSION) {
                version = a;
            }
        }

        if (id == null) {
            error(type, "Entity " + type.getQualifiedName() + " has no @Id field.");
            return null;
        }

        return new EntityDescriptor(type, tableName, schema, id, version, attributes);
    }

    private AttributeDescriptor describe(VariableElement field, AnnotationDialect dialect) {
        String name = field.getSimpleName().toString();
        TypeMirror typeMirror = field.asType();
        String javaTypeFqn = typeMirror.toString();

        boolean isId      = hasAnnotation(field, dialect.idAnno);
        boolean isVersion = hasAnnotation(field, dialect.versionAnno);
        boolean isManyToOne = hasAnnotation(field, dialect.manyToOneAnno);
        boolean isOneToOne  = hasAnnotation(field, dialect.oneToOneAnno);
        boolean isEnum      = isEnumType(typeMirror);

        AttributeKind kind;
        if (isId)                          kind = AttributeKind.ID;
        else if (isVersion)                kind = AttributeKind.VERSION;
        else if (isManyToOne || isOneToOne) kind = AttributeKind.REFERENCE;
        else if (isEnum)                   kind = AttributeKind.ENUM;
        else if (isTextType(javaTypeFqn))  kind = AttributeKind.TEXT;
        else if (isTemporal(javaTypeFqn))  kind = AttributeKind.TEMPORAL;
        else if (isNumeric(javaTypeFqn))   kind = AttributeKind.NUMERIC;
        else                               kind = AttributeKind.NUMERIC; // fallback — refined later

        Map<String, ? extends AnnotationValue> column =
                annotationValues(field, dialect.columnAnno);
        Map<String, ? extends AnnotationValue> joinColumn =
                annotationValues(field, dialect.joinColumnAnno);

        String columnName;
        if (column != null && column.containsKey("name") && !column.get("name").getValue().toString().isEmpty()) {
            columnName = column.get("name").getValue().toString();
        } else if (joinColumn != null && joinColumn.containsKey("name") && !joinColumn.get("name").getValue().toString().isEmpty()) {
            columnName = joinColumn.get("name").getValue().toString();
        } else if (kind == AttributeKind.REFERENCE) {
            columnName = io.vidocq.mansart.data.dialect.SqlNames.foreignKeyColumn(name);
        } else {
            columnName = io.vidocq.mansart.data.dialect.SqlNames.columnName(name);
        }

        boolean nullable = readBoolean(column, "nullable", true) && readBoolean(joinColumn, "nullable", true);
        boolean unique   = readBoolean(column, "unique", false)   || readBoolean(joinColumn, "unique", false);
        int     length   = (int) readNumber(column, "length", 255L);
        boolean generated = hasAnnotation(field, dialect.generatedValueAnno);

        return new AttributeDescriptor(field, name, columnName, javaTypeFqn, kind,
                nullable, unique, length, generated);
    }

    /* ----- helpers ---- */

    static boolean isJpaPresent(ProcessingEnvironment env) {
        return env.getElementUtils().getTypeElement("jakarta.persistence.Entity") != null;
    }

    private boolean hasAnnotation(Element e, String fqn) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqn)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, ? extends AnnotationValue> annotationValues(Element e, String fqn) {
        for (AnnotationMirror a : e.getAnnotationMirrors()) {
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals(fqn)) {
                Elements elements = env.getElementUtils();
                @SuppressWarnings("unchecked")
                Map<String, ? extends AnnotationValue> values =
                        (Map<String, ? extends AnnotationValue>)
                                (Map<?, ?>) elements.getElementValuesWithDefaults(a).entrySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                en -> en.getKey().getSimpleName().toString(),
                                                Map.Entry::getValue));
                return values;
            }
        }
        return null;
    }

    private boolean readBoolean(Map<String, ? extends AnnotationValue> values, String key, boolean def) {
        if (values == null || !values.containsKey(key)) return def;
        return (Boolean) values.get(key).getValue();
    }

    private long readNumber(Map<String, ? extends AnnotationValue> values, String key, long def) {
        if (values == null || !values.containsKey(key)) return def;
        return ((Number) values.get(key).getValue()).longValue();
    }

    private String readEntityTable(TypeElement type, AnnotationDialect d) {
        Map<String, ? extends AnnotationValue> table = annotationValues(type, d.tableAnno);
        if (table != null && table.containsKey("name")) {
            String n = table.get("name").getValue().toString();
            if (!n.isEmpty()) return n;
        }
        return io.vidocq.mansart.data.dialect.SqlNames.tableName(type.getSimpleName().toString());
    }

    private String readEntitySchema(TypeElement type, AnnotationDialect d) {
        Map<String, ? extends AnnotationValue> table = annotationValues(type, d.tableAnno);
        if (table != null && table.containsKey("schema")) {
            return table.get("schema").getValue().toString();
        }
        return "";
    }

    private boolean isTextType(String fqn) {
        return "java.lang.String".equals(fqn);
    }

    private boolean isTemporal(String fqn) {
        return fqn.startsWith("java.time.") || "java.util.Date".equals(fqn) || "java.sql.Date".equals(fqn);
    }

    private boolean isNumeric(String fqn) {
        return switch (fqn) {
            case "byte", "short", "int", "long", "float", "double",
                 "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
                 "java.lang.Float", "java.lang.Double",
                 "java.math.BigInteger", "java.math.BigDecimal" -> true;
            default -> false;
        };
    }

    private boolean isEnumType(TypeMirror t) {
        if (t.getKind() != TypeKind.DECLARED) return false;
        Element el = env.getTypeUtils().asElement(t);
        return el != null && el.getKind() == ElementKind.ENUM;
    }

    private void error(Element element, String message) {
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, "[mansart-data] " + message, element);
    }

    /* ----- DTOs ---- */

    enum AttributeKind { ID, VERSION, TEXT, NUMERIC, TEMPORAL, REFERENCE, ENUM }

    record AttributeDescriptor(
            VariableElement element,
            String name,
            String columnName,
            String javaTypeFqn,
            AttributeKind kind,
            boolean nullable,
            boolean unique,
            int length,
            boolean generated
    ) {}

    record EntityDescriptor(
            TypeElement type,
            String tableName,
            String schema,
            AttributeDescriptor id,
            AttributeDescriptor version,
            List<AttributeDescriptor> attributes
    ) {}

    private enum AnnotationDialect {
        JPA(
                "jakarta.persistence.Id",
                "jakarta.persistence.Version",
                "jakarta.persistence.Column",
                "jakarta.persistence.Table",
                "jakarta.persistence.Transient",
                "jakarta.persistence.GeneratedValue",
                "jakarta.persistence.ManyToOne",
                "jakarta.persistence.OneToOne",
                "jakarta.persistence.JoinColumn");

        final String idAnno, versionAnno, columnAnno, tableAnno, transientAnno,
                generatedValueAnno, manyToOneAnno, oneToOneAnno, joinColumnAnno;

        AnnotationDialect(String id, String version, String column, String table, String tr,
                          String gv, String m2o, String o2o, String join) {
            this.idAnno = id; this.versionAnno = version; this.columnAnno = column;
            this.tableAnno = table; this.transientAnno = tr; this.generatedValueAnno = gv;
            this.manyToOneAnno = m2o; this.oneToOneAnno = o2o; this.joinColumnAnno = join;
        }
    }
}
