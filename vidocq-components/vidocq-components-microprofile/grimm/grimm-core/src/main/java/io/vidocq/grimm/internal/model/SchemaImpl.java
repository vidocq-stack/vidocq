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
package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.media.Discriminator;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.XML;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Mutable implementation of {@link Schema}.
 * Supports the full OpenAPI 3.1 / JSON Schema 2020-12 dialect as required by MP OpenAPI 4.2.
 * §3.1 model POJO — all fields nullable (absent optional fields stored as null).
 */
public class SchemaImpl extends AbstractExtensibleRef<Schema> implements Schema {

    @Override protected String resolveComponentPrefix() { return "#/components/schemas/"; }

    private Discriminator discriminator;
    private String title;
    private Object defaultValue;
    private List<Object> enumeration;
    private BigDecimal multipleOf;
    private BigDecimal maximum;
    private BigDecimal exclusiveMaximum;
    private BigDecimal minimum;
    private BigDecimal exclusiveMinimum;
    private Integer maxLength;
    private Integer minLength;
    private String pattern;
    private Integer maxItems;
    private Integer minItems;
    private Boolean uniqueItems;
    private Integer maxProperties;
    private Integer minProperties;
    private List<String> required;
    private List<SchemaType> type;
    private Schema not;
    private Map<String, Schema> properties;
    private Schema additionalPropertiesSchema;
    private Boolean additionalPropertiesBoolean;
    private String description;
    private String format;
    private Boolean readOnly;
    private Boolean writeOnly;
    private Object example;
    private ExternalDocumentation externalDocs;
    private Boolean deprecated;
    private XML xml;
    private Schema items;
    private List<Schema> allOf;
    private List<Schema> anyOf;
    private List<Schema> oneOf;
    private String schemaDialect;
    private String comment;
    private Schema ifSchema;
    private Schema thenSchema;
    private Schema elseSchema;
    private Map<String, Schema> dependentSchemas;
    private List<Schema> prefixItems;
    private Schema contains;
    private Map<String, Schema> patternProperties;
    private Schema propertyNames;
    private Schema unevaluatedItems;
    private Schema unevaluatedProperties;
    private Object constValue;
    private Integer maxContains;
    private Integer minContains;
    private Map<String, List<String>> dependentRequired;
    private String contentEncoding;
    private String contentMediaType;
    private Schema contentSchema;
    private Boolean booleanSchema;
    private List<Object> examples;

    // Extra properties store for unknown keywords (Schema.get/set/getAll/setAll)
    private Map<String, Object> extraProperties;

    // ── discriminator ──
    @Override public Discriminator getDiscriminator() { return discriminator; }
    @Override public void setDiscriminator(Discriminator discriminator) { this.discriminator = discriminator; }
    @Override public Schema discriminator(Discriminator discriminator) { setDiscriminator(discriminator); return this; }

    // ── title ──
    @Override public String getTitle() { return title; }
    @Override public void setTitle(String title) { this.title = title; }

    // ── defaultValue ──
    @Override public Object getDefaultValue() { return defaultValue; }
    @Override public void setDefaultValue(Object defaultValue) { this.defaultValue = defaultValue; }

    // ── enumeration ──
    @Override public List<Object> getEnumeration() { return ModelCollections.immutableListView(enumeration); }
    @Override public void setEnumeration(List<Object> enumeration) { this.enumeration = ModelCollections.mutableList(enumeration); }
    @Override public Schema addEnumeration(Object value) {
        enumeration = ModelCollections.copyOnWriteList(enumeration);
        enumeration.add(value); return this; }
    @Override public void removeEnumeration(Object value) {
        if (enumeration != null) {
            enumeration = ModelCollections.copyOnWriteList(enumeration);
            enumeration.remove(value);
        }
    }

    // ── multipleOf ──
    @Override public BigDecimal getMultipleOf() { return multipleOf; }
    @Override public void setMultipleOf(BigDecimal multipleOf) { this.multipleOf = multipleOf; }

    // ── maximum / exclusiveMaximum ──
    @Override public BigDecimal getMaximum() { return maximum; }
    @Override public void setMaximum(BigDecimal maximum) { this.maximum = maximum; }
    @Override public BigDecimal getExclusiveMaximum() { return exclusiveMaximum; }
    @Override public void setExclusiveMaximum(BigDecimal exclusiveMaximum) { this.exclusiveMaximum = exclusiveMaximum; }

    // ── minimum / exclusiveMinimum ──
    @Override public BigDecimal getMinimum() { return minimum; }
    @Override public void setMinimum(BigDecimal minimum) { this.minimum = minimum; }
    @Override public BigDecimal getExclusiveMinimum() { return exclusiveMinimum; }
    @Override public void setExclusiveMinimum(BigDecimal exclusiveMinimum) { this.exclusiveMinimum = exclusiveMinimum; }

    // ── string constraints ──
    @Override public Integer getMaxLength() { return maxLength; }
    @Override public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    @Override public Integer getMinLength() { return minLength; }
    @Override public void setMinLength(Integer minLength) { this.minLength = minLength; }
    @Override public String getPattern() { return pattern; }
    @Override public void setPattern(String pattern) { this.pattern = pattern; }

    // ── array constraints ──
    @Override public Integer getMaxItems() { return maxItems; }
    @Override public void setMaxItems(Integer maxItems) { this.maxItems = maxItems; }
    @Override public Integer getMinItems() { return minItems; }
    @Override public void setMinItems(Integer minItems) { this.minItems = minItems; }
    @Override public Boolean getUniqueItems() { return uniqueItems; }
    @Override public void setUniqueItems(Boolean uniqueItems) { this.uniqueItems = uniqueItems; }

    // ── object constraints ──
    @Override public Integer getMaxProperties() { return maxProperties; }
    @Override public void setMaxProperties(Integer maxProperties) { this.maxProperties = maxProperties; }
    @Override public Integer getMinProperties() { return minProperties; }
    @Override public void setMinProperties(Integer minProperties) { this.minProperties = minProperties; }

    // ── required ──
    @Override public List<String> getRequired() { return ModelCollections.immutableListView(required); }
    @Override public void setRequired(List<String> required) { this.required = ModelCollections.mutableList(required); }
    @Override public Schema addRequired(String req) {
        required = ModelCollections.copyOnWriteList(required);
        required.add(req); return this; }
    @Override public void removeRequired(String req) {
        if (required != null) {
            required = ModelCollections.copyOnWriteList(required);
            required.remove(req);
        }
    }

    // ── type ──
    @Override public List<SchemaType> getType() { return ModelCollections.immutableListView(type); }
    @Override public void setType(List<SchemaType> types) { this.type = ModelCollections.mutableList(types); }
    @Override public Schema addType(SchemaType t) {
        type = ModelCollections.copyOnWriteList(type);
        type.add(t); return this; }
    @Override public void removeType(SchemaType t) {
        if (type != null) {
            type = ModelCollections.copyOnWriteList(type);
            type.remove(t);
        }
    }

    // ── not ──
    @Override public Schema getNot() { return not; }
    @Override public void setNot(Schema not) { this.not = not; }

    // ── properties ──
    @Override public Map<String, Schema> getProperties() { return ModelCollections.immutableMapView(properties); }
    @Override public void setProperties(Map<String, Schema> properties) { this.properties = ModelCollections.mutableMap(properties); }
    @Override public Schema addProperty(String key, Schema propertySchema) {
        if (propertySchema == null) return this;
        properties = ModelCollections.copyOnWriteMap(properties);
        properties.put(key, propertySchema); return this; }
    @Override public void removeProperty(String key) {
        if (properties != null) {
            properties = ModelCollections.copyOnWriteMap(properties);
            properties.remove(key);
        }
    }

    // ── additionalProperties ──
    @Override public Schema getAdditionalPropertiesSchema() {
        if (additionalPropertiesSchema != null) {
            return additionalPropertiesSchema;
        }
        if (additionalPropertiesBoolean != null) {
            Schema synthetic = new SchemaImpl();
            synthetic.setBooleanSchema(additionalPropertiesBoolean);
            return synthetic;
        }
        return null;
    }
    @Override public Boolean getAdditionalPropertiesBoolean() { return additionalPropertiesBoolean; }
    @Override public void setAdditionalPropertiesSchema(Schema additionalProperties) {
        this.additionalPropertiesSchema = additionalProperties;
        this.additionalPropertiesBoolean = null;
    }
    @Override public void setAdditionalPropertiesBoolean(Boolean additionalProperties) {
        this.additionalPropertiesBoolean = additionalProperties;
        // OpenAPI models `additionalProperties` as either a boolean OR a schema.
        // Keep schema null when the boolean form is chosen.
        this.additionalPropertiesSchema = null;
    }

    // ── description / format ──
    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }
    @Override public String getFormat() { return format; }
    @Override public void setFormat(String format) { this.format = format; }

    // ── readOnly / writeOnly ──
    @Override public Boolean getReadOnly() { return readOnly; }
    @Override public void setReadOnly(Boolean readOnly) { this.readOnly = readOnly; }
    @Override public Boolean getWriteOnly() { return writeOnly; }
    @Override public void setWriteOnly(Boolean writeOnly) { this.writeOnly = writeOnly; }

    // ── example (single) ──
    @Override public Object getExample() { return example; }
    @Override public void setExample(Object example) { this.example = example; }

    // ── externalDocs ──
    @Override public ExternalDocumentation getExternalDocs() { return externalDocs; }
    @Override public void setExternalDocs(ExternalDocumentation externalDocs) { this.externalDocs = externalDocs; }

    // ── deprecated ──
    @Override public Boolean getDeprecated() { return deprecated; }
    @Override public void setDeprecated(Boolean deprecated) { this.deprecated = deprecated; }

    // ── xml ──
    @Override public XML getXml() { return xml; }
    @Override public void setXml(XML xml) { this.xml = xml; }

    // ── items ──
    @Override public Schema getItems() { return items; }
    @Override public void setItems(Schema items) { this.items = items; }

    // ── allOf / anyOf / oneOf ──
    @Override public List<Schema> getAllOf() { return ModelCollections.immutableListView(allOf); }
    @Override public void setAllOf(List<Schema> allOf) { this.allOf = ModelCollections.mutableList(allOf); }
    @Override public Schema addAllOf(Schema s) {
        allOf = ModelCollections.copyOnWriteList(allOf); allOf.add(s); return this; }
    @Override public void removeAllOf(Schema s) { if (allOf != null) { allOf = ModelCollections.copyOnWriteList(allOf); allOf.remove(s); } }

    @Override public List<Schema> getAnyOf() { return ModelCollections.immutableListView(anyOf); }
    @Override public void setAnyOf(List<Schema> anyOf) { this.anyOf = ModelCollections.mutableList(anyOf); }
    @Override public Schema addAnyOf(Schema s) {
        anyOf = ModelCollections.copyOnWriteList(anyOf); anyOf.add(s); return this; }
    @Override public void removeAnyOf(Schema s) { if (anyOf != null) { anyOf = ModelCollections.copyOnWriteList(anyOf); anyOf.remove(s); } }

    @Override public List<Schema> getOneOf() { return ModelCollections.immutableListView(oneOf); }
    @Override public void setOneOf(List<Schema> oneOf) { this.oneOf = ModelCollections.mutableList(oneOf); }
    @Override public Schema addOneOf(Schema s) {
        oneOf = ModelCollections.copyOnWriteList(oneOf); oneOf.add(s); return this; }
    @Override public void removeOneOf(Schema s) { if (oneOf != null) { oneOf = ModelCollections.copyOnWriteList(oneOf); oneOf.remove(s); } }

    // ── schemaDialect / $comment ──
    @Override public String getSchemaDialect() { return schemaDialect; }
    @Override public void setSchemaDialect(String schemaDialect) { this.schemaDialect = schemaDialect; }
    @Override public String getComment() { return comment; }
    @Override public void setComment(String comment) { this.comment = comment; }

    // ── if / then / else ──
    @Override public Schema getIfSchema() { return ifSchema; }
    @Override public void setIfSchema(Schema ifSchema) { this.ifSchema = ifSchema; }
    @Override public Schema getThenSchema() { return thenSchema; }
    @Override public void setThenSchema(Schema thenSchema) { this.thenSchema = thenSchema; }
    @Override public Schema getElseSchema() { return elseSchema; }
    @Override public void setElseSchema(Schema elseSchema) { this.elseSchema = elseSchema; }

    // ── dependentSchemas ──
    @Override public Map<String, Schema> getDependentSchemas() { return ModelCollections.immutableMapView(dependentSchemas); }
    @Override public void setDependentSchemas(Map<String, Schema> dependentSchemas) { this.dependentSchemas = ModelCollections.mutableMap(dependentSchemas); }
    @Override public Schema addDependentSchema(String propertyName, Schema schema) {
        if (schema == null) {
            return this;
        }
        dependentSchemas = ModelCollections.copyOnWriteMap(dependentSchemas);
        dependentSchemas.put(propertyName, schema); return this; }
    @Override public void removeDependentSchema(String propertyName) {
        if (dependentSchemas != null) { dependentSchemas = ModelCollections.copyOnWriteMap(dependentSchemas); dependentSchemas.remove(propertyName); } }

    // ── prefixItems ──
    @Override public List<Schema> getPrefixItems() { return ModelCollections.immutableListView(prefixItems); }
    @Override public void setPrefixItems(List<Schema> prefixItems) { this.prefixItems = ModelCollections.mutableList(prefixItems); }
    @Override public Schema addPrefixItem(Schema prefixItem) {
        prefixItems = ModelCollections.copyOnWriteList(prefixItems);
        prefixItems.add(prefixItem); return this; }
    @Override public void removePrefixItem(Schema prefixItem) {
        if (prefixItems != null) { prefixItems = ModelCollections.copyOnWriteList(prefixItems); prefixItems.remove(prefixItem); } }

    // ── contains ──
    @Override public Schema getContains() { return contains; }
    @Override public void setContains(Schema contains) { this.contains = contains; }

    // ── patternProperties ──
    @Override public Map<String, Schema> getPatternProperties() { return ModelCollections.immutableMapView(patternProperties); }
    @Override public void setPatternProperties(Map<String, Schema> patternProperties) { this.patternProperties = ModelCollections.mutableMap(patternProperties); }
    @Override public Schema addPatternProperty(String regex, Schema schema) {
        if (schema == null) {
            return this;
        }
        patternProperties = ModelCollections.copyOnWriteMap(patternProperties);
        patternProperties.put(regex, schema); return this; }
    @Override public void removePatternProperty(String regex) {
        if (patternProperties != null) { patternProperties = ModelCollections.copyOnWriteMap(patternProperties); patternProperties.remove(regex); } }

    // ── propertyNames ──
    @Override public Schema getPropertyNames() { return propertyNames; }
    @Override public void setPropertyNames(Schema propertyNameSchema) { this.propertyNames = propertyNameSchema; }

    // ── unevaluatedItems / unevaluatedProperties ──
    @Override public Schema getUnevaluatedItems() { return unevaluatedItems; }
    @Override public void setUnevaluatedItems(Schema unevaluatedItems) { this.unevaluatedItems = unevaluatedItems; }
    @Override public Schema getUnevaluatedProperties() { return unevaluatedProperties; }
    @Override public void setUnevaluatedProperties(Schema unevaluatedProperties) { this.unevaluatedProperties = unevaluatedProperties; }

    // ── const ──
    @Override public Object getConstValue() { return constValue; }
    @Override public void setConstValue(Object constValue) { this.constValue = constValue; }

    // ── maxContains / minContains ──
    @Override public Integer getMaxContains() { return maxContains; }
    @Override public void setMaxContains(Integer maxContains) { this.maxContains = maxContains; }
    @Override public Integer getMinContains() { return minContains; }
    @Override public void setMinContains(Integer minContains) { this.minContains = minContains; }

    // ── dependentRequired ──
    @Override
    public Map<String, List<String>> getDependentRequired() {
        return ModelCollections.immutableMapView(dependentRequired);
    }
    @Override public void setDependentRequired(Map<String, List<String>> dependentRequired) {
        this.dependentRequired = ModelCollections.mutableMap(dependentRequired);
    }
    @Override public Schema addDependentRequired(String propertyName, List<String> additionalRequiredPropertyNames) {
        if (additionalRequiredPropertyNames == null) {
            return this;
        }
        dependentRequired = ModelCollections.copyOnWriteMap(dependentRequired);
        dependentRequired.put(propertyName, additionalRequiredPropertyNames); return this; }
    @Override public void removeDependentRequired(String propertyName) {
        if (dependentRequired != null) { dependentRequired = ModelCollections.copyOnWriteMap(dependentRequired); dependentRequired.remove(propertyName); } }

    // ── content* ──
    @Override public String getContentEncoding() { return contentEncoding; }
    @Override public void setContentEncoding(String contentEncoding) { this.contentEncoding = contentEncoding; }
    @Override public String getContentMediaType() { return contentMediaType; }
    @Override public void setContentMediaType(String contentMediaType) { this.contentMediaType = contentMediaType; }
    @Override public Schema getContentSchema() { return contentSchema; }
    @Override public void setContentSchema(Schema contentSchema) { this.contentSchema = contentSchema; }

    // ── booleanSchema ──
    @Override public Boolean getBooleanSchema() { return booleanSchema; }
    @Override public void setBooleanSchema(Boolean booleanSchema) { this.booleanSchema = booleanSchema; }

    // ── examples (list) ──
    @Override public List<Object> getExamples() { return ModelCollections.immutableListView(examples); }
    @Override public void setExamples(List<Object> examples) { this.examples = ModelCollections.mutableList(examples); }
    @Override public Schema addExample(Object example) {
        examples = ModelCollections.copyOnWriteList(examples);
        examples.add(example); return this; }
    @Override public void removeExample(Object example) { if (examples != null) { examples = ModelCollections.copyOnWriteList(examples); examples.remove(example); } }

    // ── generic property access (Schema.get / Schema.set / getAll / setAll) ──
    // A property is named as in the JSON document. A standard name holds its value either in its
    // typed field or, when set() gave it a value of another type (alternative dialect), in
    // extraProperties — never in both. A list or map whose elements the typed field cannot hold
    // is a value of another type. Any other name is an extension.
    @Override
    public Object get(String propertyName) {
        if (extraProperties != null && extraProperties.containsKey(propertyName)) {
            return extraProperties.get(propertyName);
        }
        Property property = propertyName == null ? null : STANDARD_PROPERTIES.get(propertyName);
        return property == null ? null : property.getter().apply(this);
    }

    @Override
    public Schema set(String propertyName, Object value) {
        if (propertyName == null) {
            return this;
        }
        Property property = STANDARD_PROPERTIES.get(propertyName);
        if (property != null) {
            boolean typed = value == null || property.accepts().test(value);
            property.setter().accept(this, typed ? value : null);
            if (typed) {
                removeExtension(propertyName);
                return this;
            }
            // A value of another type is kept as written, below.
        }
        if (value == null) {
            removeExtension(propertyName);
            return this;
        }
        extraProperties = ModelCollections.copyOnWriteMap(extraProperties);
        extraProperties.put(propertyName, value);
        return this;
    }

    /** Every property set to a non-null value, standard ones first, as {@link #get(String)} reads them. */
    @Override
    public Map<String, ?> getAll() {
        Map<String, Object> all = new LinkedHashMap<>();
        STANDARD_PROPERTIES.forEach((name, property) -> {
            Object value = property.getter().apply(this);
            if (value != null) {
                all.put(name, value);
            }
        });
        if (extraProperties != null) {
            all.putAll(extraProperties);
        }
        return Collections.unmodifiableMap(all);
    }

    /**
     * Clears every property, extensions, {@code $ref} and the boolean-schema form included, then
     * sets each entry with {@link #set}.
     */
    @Override
    public void setAll(Map<String, ?> allProperties) {
        STANDARD_PROPERTIES.values().forEach(property -> property.setter().accept(this, null));
        extraProperties = null;
        booleanSchema = null;
        if (allProperties != null) {
            allProperties.forEach(this::set);
        }
    }

    // ── extensions == unknown properties (MP OpenAPI 4.2 #698, base dialect) ──
    // Every override below uses extraProperties; the inherited AbstractExtensible.extensions field stays unused.
    @Override
    public Map<String, Object> getExtensions() {
        return extraProperties == null ? Map.of() : ModelCollections.immutableMapView(extraProperties);
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) {
        extraProperties = extensions == null ? null : new LinkedHashMap<>(extensions);
    }

    @Override
    public Schema addExtension(String name, Object value) {
        if (name == null || value == null) {
            return this;
        }
        extraProperties = ModelCollections.copyOnWriteMap(extraProperties);
        extraProperties.put(name, value);
        return this;
    }

    @Override
    public void removeExtension(String name) {
        if (extraProperties != null && extraProperties.containsKey(name)) {
            extraProperties = ModelCollections.copyOnWriteMap(extraProperties);
            extraProperties.remove(name);
        }
    }

    @Override
    public boolean hasExtension(String name) {
        return extraProperties != null && extraProperties.containsKey(name);
    }

    @Override
    public Object getExtension(String name) {
        return extraProperties == null ? null : extraProperties.get(name);
    }

    // ── the standard properties, by JSON name ──

    /**
     * A standard property: the values its typed setter takes, and its typed accessors.
     * Only these names are properties — {@code extensions}, {@code all} or {@code defaultValue},
     * the names of Java accessors, are unknown properties, hence extensions.
     */
    private record Property(Predicate<Object> accepts, Function<SchemaImpl, Object> getter,
                            BiConsumer<SchemaImpl, Object> setter) {

        static <T> Property of(Class<T> type, Function<SchemaImpl, ? extends T> getter,
                               BiConsumer<SchemaImpl, T> setter) {
            return new Property(type::isInstance, getter::apply,
                    (schema, value) -> setter.accept(schema, type.cast(value)));
        }
    }

    /** A list whose every element is an {@code element}: what a typed list property holds. */
    private static Predicate<Object> listOf(Class<?> element) {
        return value -> value instanceof List<?> list && list.stream().allMatch(element::isInstance);
    }

    /** A map from {@code String} keys to values that {@code valueAccepts}: what a typed map property holds. */
    private static Predicate<Object> mapOf(Predicate<Object> valueAccepts) {
        return value -> value instanceof Map<?, ?> map && map.entrySet().stream()
                .allMatch(entry -> entry.getKey() instanceof String && valueAccepts.test(entry.getValue()));
    }

    /** The boolean schema {@code true} or {@code false}, with no other property. */
    private static boolean isBareBooleanSchema(Schema schema) {
        return schema.getBooleanSchema() != null && schema.getAll().isEmpty();
    }

    private static final Map<String, Property> STANDARD_PROPERTIES = standardProperties();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<String, Property> standardProperties() {
        Map<String, Property> p = new LinkedHashMap<>();
        // $ref is read and written as it stands: no short-name expansion, as for a static file.
        p.put("$ref", Property.of(String.class, SchemaImpl::getRef, (s, v) -> setVerbatimRef(s, v)));
        p.put("$schema", Property.of(String.class, SchemaImpl::getSchemaDialect, SchemaImpl::setSchemaDialect));
        p.put("$comment", Property.of(String.class, SchemaImpl::getComment, SchemaImpl::setComment));
        p.put("discriminator", Property.of(Discriminator.class, SchemaImpl::getDiscriminator,
                SchemaImpl::setDiscriminator));
        p.put("title", Property.of(String.class, SchemaImpl::getTitle, SchemaImpl::setTitle));
        p.put("default", Property.of(Object.class, SchemaImpl::getDefaultValue, SchemaImpl::setDefaultValue));
        p.put("enum", Property.of(List.class, SchemaImpl::getEnumeration, (s, v) -> s.setEnumeration(v)));
        p.put("multipleOf", Property.of(BigDecimal.class, SchemaImpl::getMultipleOf, SchemaImpl::setMultipleOf));
        p.put("maximum", Property.of(BigDecimal.class, SchemaImpl::getMaximum, SchemaImpl::setMaximum));
        p.put("exclusiveMaximum", Property.of(BigDecimal.class, SchemaImpl::getExclusiveMaximum,
                SchemaImpl::setExclusiveMaximum));
        p.put("minimum", Property.of(BigDecimal.class, SchemaImpl::getMinimum, SchemaImpl::setMinimum));
        p.put("exclusiveMinimum", Property.of(BigDecimal.class, SchemaImpl::getExclusiveMinimum,
                SchemaImpl::setExclusiveMinimum));
        p.put("maxLength", Property.of(Integer.class, SchemaImpl::getMaxLength, SchemaImpl::setMaxLength));
        p.put("minLength", Property.of(Integer.class, SchemaImpl::getMinLength, SchemaImpl::setMinLength));
        p.put("pattern", Property.of(String.class, SchemaImpl::getPattern, SchemaImpl::setPattern));
        p.put("maxItems", Property.of(Integer.class, SchemaImpl::getMaxItems, SchemaImpl::setMaxItems));
        p.put("minItems", Property.of(Integer.class, SchemaImpl::getMinItems, SchemaImpl::setMinItems));
        p.put("uniqueItems", Property.of(Boolean.class, SchemaImpl::getUniqueItems, SchemaImpl::setUniqueItems));
        p.put("maxProperties", Property.of(Integer.class, SchemaImpl::getMaxProperties,
                SchemaImpl::setMaxProperties));
        p.put("minProperties", Property.of(Integer.class, SchemaImpl::getMinProperties,
                SchemaImpl::setMinProperties));
        p.put("required", new Property(listOf(String.class), SchemaImpl::getRequired,
                (s, v) -> s.setRequired((List) v)));
        p.put("type", new Property(listOf(SchemaType.class), SchemaImpl::getType, (s, v) -> s.setType((List) v)));
        p.put("not", Property.of(Schema.class, SchemaImpl::getNot, SchemaImpl::setNot));
        p.put("properties", new Property(mapOf(Schema.class::isInstance), SchemaImpl::getProperties,
                (s, v) -> s.setProperties((Map) v)));
        // The boolean form is read as a boolean schema, as getAdditionalPropertiesSchema() does, and
        // a bare boolean schema written back takes the boolean form again: setAll(getAll()) keeps it.
        p.put("additionalProperties", new Property(
                value -> value instanceof Schema || value instanceof Boolean,
                SchemaImpl::getAdditionalPropertiesSchema,
                (s, v) -> {
                    if (v instanceof Boolean b) {
                        s.setAdditionalPropertiesBoolean(b);
                    } else if (v instanceof Schema schema && isBareBooleanSchema(schema)) {
                        s.setAdditionalPropertiesBoolean(schema.getBooleanSchema());
                    } else {
                        s.setAdditionalPropertiesSchema((Schema) v);
                    }
                }));
        p.put("description", Property.of(String.class, SchemaImpl::getDescription, SchemaImpl::setDescription));
        p.put("format", Property.of(String.class, SchemaImpl::getFormat, SchemaImpl::setFormat));
        p.put("readOnly", Property.of(Boolean.class, SchemaImpl::getReadOnly, SchemaImpl::setReadOnly));
        p.put("writeOnly", Property.of(Boolean.class, SchemaImpl::getWriteOnly, SchemaImpl::setWriteOnly));
        p.put("example", Property.of(Object.class, SchemaImpl::getExample, SchemaImpl::setExample));
        p.put("externalDocs", Property.of(ExternalDocumentation.class, SchemaImpl::getExternalDocs,
                SchemaImpl::setExternalDocs));
        p.put("deprecated", Property.of(Boolean.class, SchemaImpl::getDeprecated, SchemaImpl::setDeprecated));
        p.put("xml", Property.of(XML.class, SchemaImpl::getXml, SchemaImpl::setXml));
        p.put("items", Property.of(Schema.class, SchemaImpl::getItems, SchemaImpl::setItems));
        p.put("allOf", new Property(listOf(Schema.class), SchemaImpl::getAllOf, (s, v) -> s.setAllOf((List) v)));
        p.put("anyOf", new Property(listOf(Schema.class), SchemaImpl::getAnyOf, (s, v) -> s.setAnyOf((List) v)));
        p.put("oneOf", new Property(listOf(Schema.class), SchemaImpl::getOneOf, (s, v) -> s.setOneOf((List) v)));
        p.put("if", Property.of(Schema.class, SchemaImpl::getIfSchema, SchemaImpl::setIfSchema));
        p.put("then", Property.of(Schema.class, SchemaImpl::getThenSchema, SchemaImpl::setThenSchema));
        p.put("else", Property.of(Schema.class, SchemaImpl::getElseSchema, SchemaImpl::setElseSchema));
        p.put("dependentSchemas", new Property(mapOf(Schema.class::isInstance), SchemaImpl::getDependentSchemas,
                (s, v) -> s.setDependentSchemas((Map) v)));
        p.put("prefixItems", new Property(listOf(Schema.class), SchemaImpl::getPrefixItems,
                (s, v) -> s.setPrefixItems((List) v)));
        p.put("contains", Property.of(Schema.class, SchemaImpl::getContains, SchemaImpl::setContains));
        p.put("patternProperties", new Property(mapOf(Schema.class::isInstance), SchemaImpl::getPatternProperties,
                (s, v) -> s.setPatternProperties((Map) v)));
        p.put("propertyNames", Property.of(Schema.class, SchemaImpl::getPropertyNames,
                SchemaImpl::setPropertyNames));
        p.put("unevaluatedItems", Property.of(Schema.class, SchemaImpl::getUnevaluatedItems,
                SchemaImpl::setUnevaluatedItems));
        p.put("unevaluatedProperties", Property.of(Schema.class, SchemaImpl::getUnevaluatedProperties,
                SchemaImpl::setUnevaluatedProperties));
        p.put("const", Property.of(Object.class, SchemaImpl::getConstValue, SchemaImpl::setConstValue));
        p.put("maxContains", Property.of(Integer.class, SchemaImpl::getMaxContains, SchemaImpl::setMaxContains));
        p.put("minContains", Property.of(Integer.class, SchemaImpl::getMinContains, SchemaImpl::setMinContains));
        p.put("dependentRequired", new Property(mapOf(listOf(String.class)), SchemaImpl::getDependentRequired,
                (s, v) -> s.setDependentRequired((Map) v)));
        p.put("contentEncoding", Property.of(String.class, SchemaImpl::getContentEncoding,
                SchemaImpl::setContentEncoding));
        p.put("contentMediaType", Property.of(String.class, SchemaImpl::getContentMediaType,
                SchemaImpl::setContentMediaType));
        p.put("contentSchema", Property.of(Schema.class, SchemaImpl::getContentSchema,
                SchemaImpl::setContentSchema));
        p.put("examples", Property.of(List.class, SchemaImpl::getExamples, (s, v) -> s.setExamples(v)));
        return Collections.unmodifiableMap(p);
    }
}

