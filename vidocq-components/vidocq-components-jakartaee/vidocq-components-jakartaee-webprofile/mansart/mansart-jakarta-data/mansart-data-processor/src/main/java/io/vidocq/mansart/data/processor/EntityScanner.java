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

    private static final String ID_ANNO = "jakarta.persistence.Id";
    private static final String VERSION_ANNO = "jakarta.persistence.Version";
    private static final String COLUMN_ANNO = "jakarta.persistence.Column";
    private static final String TABLE_ANNO = "jakarta.persistence.Table";
    private static final String TRANSIENT_ANNO = "jakarta.persistence.Transient";
    private static final String GENERATED_VALUE_ANNO = "jakarta.persistence.GeneratedValue";
    private static final String MANY_TO_ONE_ANNO = "jakarta.persistence.ManyToOne";
    private static final String ONE_TO_ONE_ANNO = "jakarta.persistence.OneToOne";
    private static final String JOIN_COLUMN_ANNO = "jakarta.persistence.JoinColumn";

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

        String tableName = readEntityTable(type);
        String schema    = readEntitySchema(type);

        List<AttributeDescriptor> attributes = new ArrayList<>();
        AttributeDescriptor       id         = null;
        AttributeDescriptor       version    = null;

        for (Element member : type.getEnclosedElements()) {
            if (!isPersistentField(member)) continue;
            VariableElement field = (VariableElement) member;
            AttributeDescriptor a = describe(field);
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

    /** An instance field that is neither {@code transient} nor {@code @Transient}. */
    private boolean isPersistentField(Element member) {
        return member.getKind() == ElementKind.FIELD
                && !member.getModifiers().contains(Modifier.STATIC)
                && !member.getModifiers().contains(Modifier.TRANSIENT)
                && !hasAnnotation(member, TRANSIENT_ANNO);
    }

    private AttributeDescriptor describe(VariableElement field) {
        String name = field.getSimpleName().toString();
        TypeMirror typeMirror = field.asType();
        String javaTypeFqn = typeMirror.toString();
        AttributeKind kind = kindOf(field, typeMirror, javaTypeFqn);

        Map<String, ? extends AnnotationValue> column = annotationValues(field, COLUMN_ANNO);
        Map<String, ? extends AnnotationValue> joinColumn = annotationValues(field, JOIN_COLUMN_ANNO);

        String columnName = columnNameOf(name, kind, column, joinColumn);
        boolean nullable = readBoolean(column, "nullable", true) && readBoolean(joinColumn, "nullable", true);
        boolean unique   = readBoolean(column, "unique", false)   || readBoolean(joinColumn, "unique", false);
        int     length   = (int) readNumber(column, "length", 255L);
        boolean generated = hasAnnotation(field, GENERATED_VALUE_ANNO);

        // M8-3 — referencedColumnName from @JoinColumn — only meaningful when REFERENCE.
        String referencedColumn = kind == AttributeKind.REFERENCE
                ? nonEmptyText(joinColumn, "referencedColumnName", "id")
                : "id";
        boolean ordinalEnum = kind == AttributeKind.ENUM && isOrdinal(field);

        return new AttributeDescriptor(field, name, columnName, javaTypeFqn, kind,
                nullable, unique, length, generated, referencedColumn, ordinalEnum);
    }

    private AttributeKind kindOf(VariableElement field, TypeMirror typeMirror, String javaTypeFqn) {
        if (hasAnnotation(field, ID_ANNO))      return AttributeKind.ID;
        if (hasAnnotation(field, VERSION_ANNO)) return AttributeKind.VERSION;
        if (hasAnnotation(field, MANY_TO_ONE_ANNO) || hasAnnotation(field, ONE_TO_ONE_ANNO)) {
            return AttributeKind.REFERENCE;
        }
        if (isEnumType(typeMirror))        return AttributeKind.ENUM;
        if (isTextType(javaTypeFqn))       return AttributeKind.TEXT;
        if (isBoolean(javaTypeFqn))        return AttributeKind.BOOLEAN;
        if (isTemporal(javaTypeFqn))       return AttributeKind.TEMPORAL;
        // numbers, and the fallback for any other type — refined later
        return AttributeKind.NUMERIC;
    }

    /** {@code @Column(name)}, else {@code @JoinColumn(name)}, else the name derived from the field. */
    private static String columnNameOf(String name, AttributeKind kind, Map<String, ? extends AnnotationValue> column,
                                       Map<String, ? extends AnnotationValue> joinColumn) {
        String fromColumn = nonEmptyText(column, "name", null);
        if (fromColumn != null) return fromColumn;
        String fromJoinColumn = nonEmptyText(joinColumn, "name", null);
        if (fromJoinColumn != null) return fromJoinColumn;
        return kind == AttributeKind.REFERENCE
                ? io.vidocq.mansart.data.dialect.SqlNames.foreignKeyColumn(name)
                : io.vidocq.mansart.data.dialect.SqlNames.columnName(name);
    }

    /** The text of {@code key} when the annotation sets it to a non-empty value, else {@code fallback}. */
    private static String nonEmptyText(Map<String, ? extends AnnotationValue> values, String key, String fallback) {
        AnnotationValue value = values.get(key);
        if (value == null) return fallback;
        String text = value.getValue().toString();
        return text.isEmpty() ? fallback : text;
    }

    /** MANSART-003 — {@code @Enumerated(ORDINAL)}. */
    private boolean isOrdinal(VariableElement field) {
        Map<String, ? extends AnnotationValue> enumerated = annotationValues(field, "jakarta.persistence.Enumerated");
        return enumerated.containsKey("value") && "ORDINAL".equals(enumerated.get("value").getValue().toString());
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
        return Map.of();
    }

    private boolean readBoolean(Map<String, ? extends AnnotationValue> values, String key, boolean def) {
        if (!values.containsKey(key)) return def;
        return (Boolean) values.get(key).getValue();
    }

    private long readNumber(Map<String, ? extends AnnotationValue> values, String key, long def) {
        if (!values.containsKey(key)) return def;
        return ((Number) values.get(key).getValue()).longValue();
    }

    private String readEntityTable(TypeElement type) {
        String n = nonEmptyText(annotationValues(type, TABLE_ANNO), "name", null);
        return n != null ? n : io.vidocq.mansart.data.dialect.SqlNames.tableName(type.getSimpleName().toString());
    }

    private String readEntitySchema(TypeElement type) {
        Map<String, ? extends AnnotationValue> table = annotationValues(type, TABLE_ANNO);
        if (table.containsKey("schema")) {
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
}
