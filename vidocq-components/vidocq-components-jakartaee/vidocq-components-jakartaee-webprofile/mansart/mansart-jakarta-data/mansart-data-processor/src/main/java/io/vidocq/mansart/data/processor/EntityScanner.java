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

import javax.annotation.processing.Messager;
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
import javax.lang.model.util.Types;
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

    private final Elements elements;
    private final Types    types;
    private final Messager messager; // nullable — null in the Maven-plugin path

    EntityScanner(ProcessingEnvironment env) {
        this(env.getElementUtils(), env.getTypeUtils(), env.getMessager());
    }

    EntityScanner(Elements elements, Types types, Messager messager) {
        this.elements = elements;
        this.types = types;
        this.messager = messager;
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
        else if (isBoolean(javaTypeFqn))   kind = AttributeKind.BOOLEAN;
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

        // M8-3 — referencedColumnName from @JoinColumn — only meaningful when REFERENCE.
        String referencedColumn = "id";
        if (kind == AttributeKind.REFERENCE && joinColumn != null
                && joinColumn.containsKey("referencedColumnName")) {
            String rc = joinColumn.get("referencedColumnName").getValue().toString();
            if (!rc.isEmpty()) referencedColumn = rc;
        }

        boolean ordinalEnum = false;
        if (kind == AttributeKind.ENUM) {
            Map<String, ? extends AnnotationValue> enumerated =
                    annotationValues(field, "jakarta.persistence.Enumerated");
            ordinalEnum = enumerated != null && enumerated.containsKey("value")
                    && "ORDINAL".equals(enumerated.get("value").getValue().toString());
        }

        return new AttributeDescriptor(field, name, columnName, javaTypeFqn, kind,
                nullable, unique, length, generated, referencedColumn, ordinalEnum);
    }

    /* ----- helpers ---- */

    boolean isJpaPresent() {
        return elements.getTypeElement("jakarta.persistence.Entity") != null;
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

    private boolean isBoolean(String fqn) {
        return "boolean".equals(fqn) || "java.lang.Boolean".equals(fqn);
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
        Element el = types.asElement(t);
        return el != null && el.getKind() == ElementKind.ENUM;
    }

    private void error(Element element, String message) {
        if (messager != null) {
            messager.printMessage(Diagnostic.Kind.ERROR, "[mansart-data] " + message, element);
        }
    }

    /* ----- DTOs ---- */

    enum AttributeKind { ID, VERSION, TEXT, NUMERIC, BOOLEAN, TEMPORAL, REFERENCE, ENUM }

    record AttributeDescriptor(
            VariableElement element,
            String name,
            String columnName,
            String javaTypeFqn,
            AttributeKind kind,
            boolean nullable,
            boolean unique,
            int length,
            boolean generated,
            // M8-3 — for REFERENCE attributes only: PK column on the target entity (defaults "id").
            String referencedColumn,
            // MANSART-003 — for ENUM attributes only: @Enumerated(ORDINAL) stores the index; no
            // @Enumerated, or STRING, stores the name (Mansart's default, where JPA would store the index).
            boolean ordinalEnum
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
