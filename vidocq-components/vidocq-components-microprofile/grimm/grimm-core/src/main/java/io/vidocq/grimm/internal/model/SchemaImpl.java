package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.media.Discriminator;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.XML;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mutable implementation of {@link Schema}.
 * Supports the full OpenAPI 3.1 / JSON Schema 2020-12 dialect as required by MP OpenAPI 4.1.
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
    @Override public List<Object> getEnumeration() { return enumeration; }
    @Override public void setEnumeration(List<Object> enumeration) { this.enumeration = enumeration; }
    @Override public Schema addEnumeration(Object value) {
        if (enumeration == null) enumeration = new ArrayList<>();
        enumeration.add(value); return this; }
    @Override public void removeEnumeration(Object value) { if (enumeration != null) enumeration.remove(value); }

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
    @Override public List<String> getRequired() { return required; }
    @Override public void setRequired(List<String> required) { this.required = required; }
    @Override public Schema addRequired(String req) {
        if (required == null) required = new ArrayList<>();
        required.add(req); return this; }
    @Override public void removeRequired(String req) { if (required != null) required.remove(req); }

    // ── type ──
    @Override public List<SchemaType> getType() { return type; }
    @Override public void setType(List<SchemaType> types) { this.type = types; }
    @Override public Schema addType(SchemaType t) {
        if (type == null) type = new ArrayList<>();
        type.add(t); return this; }
    @Override public void removeType(SchemaType t) { if (type != null) type.remove(t); }

    // ── not ──
    @Override public Schema getNot() { return not; }
    @Override public void setNot(Schema not) { this.not = not; }

    // ── properties ──
    @Override public Map<String, Schema> getProperties() { return properties; }
    @Override public void setProperties(Map<String, Schema> properties) { this.properties = properties; }
    @Override public Schema addProperty(String key, Schema propertySchema) {
        if (propertySchema == null) return this;
        if (properties == null) properties = new LinkedHashMap<>();
        properties.put(key, propertySchema); return this; }
    @Override public void removeProperty(String key) { if (properties != null) properties.remove(key); }

    // ── additionalProperties ──
    @Override public Schema getAdditionalPropertiesSchema() { return additionalPropertiesSchema; }
    @Override public Boolean getAdditionalPropertiesBoolean() { return additionalPropertiesBoolean; }
    @Override public void setAdditionalPropertiesSchema(Schema additionalProperties) {
        this.additionalPropertiesSchema = additionalProperties;
        this.additionalPropertiesBoolean = null; }
    @Override public void setAdditionalPropertiesBoolean(Boolean additionalProperties) {
        this.additionalPropertiesBoolean = additionalProperties;
        this.additionalPropertiesSchema = null; }

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
    @Override public List<Schema> getAllOf() { return allOf; }
    @Override public void setAllOf(List<Schema> allOf) { this.allOf = allOf; }
    @Override public Schema addAllOf(Schema s) {
        if (allOf == null) allOf = new ArrayList<>(); allOf.add(s); return this; }
    @Override public void removeAllOf(Schema s) { if (allOf != null) allOf.remove(s); }

    @Override public List<Schema> getAnyOf() { return anyOf; }
    @Override public void setAnyOf(List<Schema> anyOf) { this.anyOf = anyOf; }
    @Override public Schema addAnyOf(Schema s) {
        if (anyOf == null) anyOf = new ArrayList<>(); anyOf.add(s); return this; }
    @Override public void removeAnyOf(Schema s) { if (anyOf != null) anyOf.remove(s); }

    @Override public List<Schema> getOneOf() { return oneOf; }
    @Override public void setOneOf(List<Schema> oneOf) { this.oneOf = oneOf; }
    @Override public Schema addOneOf(Schema s) {
        if (oneOf == null) oneOf = new ArrayList<>(); oneOf.add(s); return this; }
    @Override public void removeOneOf(Schema s) { if (oneOf != null) oneOf.remove(s); }

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
    @Override public Map<String, Schema> getDependentSchemas() { return dependentSchemas; }
    @Override public void setDependentSchemas(Map<String, Schema> dependentSchemas) { this.dependentSchemas = dependentSchemas; }
    @Override public Schema addDependentSchema(String propertyName, Schema schema) {
        if (dependentSchemas == null) dependentSchemas = new LinkedHashMap<>();
        dependentSchemas.put(propertyName, schema); return this; }
    @Override public void removeDependentSchema(String propertyName) {
        if (dependentSchemas != null) dependentSchemas.remove(propertyName); }

    // ── prefixItems ──
    @Override public List<Schema> getPrefixItems() { return prefixItems; }
    @Override public void setPrefixItems(List<Schema> prefixItems) { this.prefixItems = prefixItems; }
    @Override public Schema addPrefixItem(Schema prefixItem) {
        if (prefixItems == null) prefixItems = new ArrayList<>();
        prefixItems.add(prefixItem); return this; }
    @Override public void removePrefixItem(Schema prefixItem) {
        if (prefixItems != null) prefixItems.remove(prefixItem); }

    // ── contains ──
    @Override public Schema getContains() { return contains; }
    @Override public void setContains(Schema contains) { this.contains = contains; }

    // ── patternProperties ──
    @Override public Map<String, Schema> getPatternProperties() { return patternProperties; }
    @Override public void setPatternProperties(Map<String, Schema> patternProperties) { this.patternProperties = patternProperties; }
    @Override public Schema addPatternProperty(String regex, Schema schema) {
        if (patternProperties == null) patternProperties = new LinkedHashMap<>();
        patternProperties.put(regex, schema); return this; }
    @Override public void removePatternProperty(String regex) {
        if (patternProperties != null) patternProperties.remove(regex); }

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
    @Override public Map<String, List<String>> getDependentRequired() { return dependentRequired; }
    @Override public void setDependentRequired(Map<String, List<String>> dependentRequired) { this.dependentRequired = dependentRequired; }
    @Override public Schema addDependentRequired(String propertyName, List<String> additionalRequiredPropertyNames) {
        if (dependentRequired == null) dependentRequired = new LinkedHashMap<>();
        dependentRequired.put(propertyName, additionalRequiredPropertyNames); return this; }
    @Override public void removeDependentRequired(String propertyName) {
        if (dependentRequired != null) dependentRequired.remove(propertyName); }

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
    @Override public List<Object> getExamples() { return examples; }
    @Override public void setExamples(List<Object> examples) { this.examples = examples; }
    @Override public Schema addExample(Object example) {
        if (examples == null) examples = new ArrayList<>();
        examples.add(example); return this; }
    @Override public void removeExample(Object example) { if (examples != null) examples.remove(example); }

    // ── generic property access (Schema.get / Schema.set / getAll / setAll) ──
    @Override
    public Object get(String propertyName) {
        if (extraProperties != null && extraProperties.containsKey(propertyName)) {
            return extraProperties.get(propertyName);
        }
        return null;
    }

    @Override
    public Schema set(String propertyName, Object value) {
        if (extraProperties == null) extraProperties = new LinkedHashMap<>();
        extraProperties.put(propertyName, value);
        return this;
    }

    @Override
    public Map<String, ?> getAll() {
        return extraProperties == null ? Map.of() : extraProperties;
    }

    @Override
    public void setAll(Map<String, ?> allProperties) {
        if (allProperties == null) {
            extraProperties = null;
        } else {
            extraProperties = new LinkedHashMap<>(allProperties);
        }
    }
}

