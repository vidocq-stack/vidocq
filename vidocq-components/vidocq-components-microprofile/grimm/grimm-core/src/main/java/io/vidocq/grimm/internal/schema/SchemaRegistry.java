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
package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry that interns generated schemas under {@code components/schemas} (spec §3.10).
 *
 * <p>Named schemas for non-primitive types are stored by simple class name (or a
 * configurable explicit name from {@code @Schema(name=…)}); the registry guarantees
 * uniqueness by suffixing collisions. Once registered, the generator returns a
 * reference schema {@code $ref: #/components/schemas/Name}.</p>
 *
 * <p>Thread-safety: uses {@link ReentrantLock} (no {@code synchronized} per project rules).</p>
 */
public final class SchemaRegistry {

    private final ReentrantLock lock = new ReentrantLock();
    /** name → schema definition */
    private final Map<String, Schema> schemas = new LinkedHashMap<>();
    /** class → registered name (so we can short-circuit a $ref) */
    private final Map<Class<?>, String> classToName = new LinkedHashMap<>();

    /** Returns the registered name for {@code clazz}, or {@code null} if absent. */
    public String nameOf(Class<?> clazz) {
        lock.lock();
        try {
            return classToName.get(clazz);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reserves a name for {@code clazz} eagerly (before its body is built), enabling
     * cycle resolution: any recursive reference returned during body generation can
     * already produce a {@code $ref} to this name.
     */
    public String reserve(Class<?> clazz, String preferred) {
        Objects.requireNonNull(clazz, "clazz");
        Objects.requireNonNull(preferred, "preferred");
        lock.lock();
        try {
            String existing = classToName.get(clazz);
            if (existing != null) {
                return existing;
            }
            String unique = uniqueName(preferred);
            classToName.put(clazz, unique);
            schemas.put(unique, null); // placeholder
            return unique;
        } finally {
            lock.unlock();
        }
    }

    /** Stores the final body for a previously reserved name. */
    public void publish(String name, Schema body) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(body, "body");
        lock.lock();
        try {
            schemas.put(name, body);
        } finally {
            lock.unlock();
        }
    }

    /** Builds a {@code $ref} schema pointing to {@code #/components/schemas/name}. */
    public Schema buildRef(String name) {
        Schema ref = OASFactory.createObject(Schema.class);
        ref.setRef("#/components/schemas/" + name);
        return ref;
    }

    /** Snapshot of registered schemas (insertion order). */
    public Map<String, Schema> snapshot() {
        lock.lock();
        try {
            Map<String, Schema> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Schema> e : schemas.entrySet()) {
                if (e.getValue() != null) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
            return copy;
        } finally {
            lock.unlock();
        }
    }

    /** Merges all registered schemas into the {@code components.schemas} map of {@code openAPI}. */
    public void applyTo(OpenAPI openAPI) {
        Map<String, Schema> snap = snapshot();
        if (snap.isEmpty()) {
            return;
        }
        Components components = openAPI.getComponents();
        if (components == null) {
            components = OASFactory.createObject(Components.class);
            openAPI.setComponents(components);
        }
        for (Map.Entry<String, Schema> e : snap.entrySet()) {
            Schema existing = components.getSchemas() != null ? components.getSchemas().get(e.getKey()) : null;
            if (existing == null) {
                components.addSchema(e.getKey(), e.getValue());
                continue;
            }
            components.addSchema(e.getKey(), mergeSchema(e.getValue(), existing));
        }
    }

    private Schema mergeSchema(Schema generated, Schema explicit) {
        if (generated == null) {
            return explicit;
        }
        if (explicit == null) {
            return generated;
        }
        Schema merged = OASFactory.createObject(Schema.class);
        copySchema(generated, merged);
        copySchema(explicit, merged);

        Map<String, Schema> generatedProperties = generated.getProperties();
        Map<String, Schema> explicitProperties = explicit.getProperties();
        if (generatedProperties != null || explicitProperties != null) {
            Map<String, Schema> mergedProperties = new LinkedHashMap<>();
            if (generatedProperties != null) {
                mergedProperties.putAll(generatedProperties);
            }
            if (explicitProperties != null) {
                mergedProperties.putAll(explicitProperties);
            }
            merged.setProperties(mergedProperties);
        }

        List<String> generatedRequired = generated.getRequired();
        List<String> explicitRequired = explicit.getRequired();
        if (generatedRequired != null || explicitRequired != null) {
            List<String> required = new java.util.ArrayList<>();
            if (generatedRequired != null) {
                required.addAll(generatedRequired);
            }
            if (explicitRequired != null) {
                for (String item : explicitRequired) {
                    if (!required.contains(item)) {
                        required.add(item);
                    }
                }
            }
            merged.setRequired(required);
        }
        return merged;
    }

    private void copySchema(Schema source, Schema target) {
        if (source.getRef() != null) target.setRef(source.getRef());
        if (source.getTitle() != null) target.setTitle(source.getTitle());
        if (source.getDescription() != null) target.setDescription(source.getDescription());
        if (source.getType() != null) target.setType(source.getType());
        if (source.getFormat() != null) target.setFormat(source.getFormat());
        if (source.getDefaultValue() != null) target.setDefaultValue(source.getDefaultValue());
        if (source.getExample() != null) target.setExample(source.getExample());
        if (source.getExamples() != null) target.setExamples(source.getExamples());
        if (source.getReadOnly() != null) target.setReadOnly(source.getReadOnly());
        if (source.getWriteOnly() != null) target.setWriteOnly(source.getWriteOnly());
        if (source.getDeprecated() != null) target.setDeprecated(source.getDeprecated());
        if (source.getDiscriminator() != null) target.setDiscriminator(source.getDiscriminator());
        if (source.getXml() != null) target.setXml(source.getXml());
        if (source.getDependentRequired() != null) target.setDependentRequired(source.getDependentRequired());
        if (source.getDependentSchemas() != null) target.setDependentSchemas(source.getDependentSchemas());
        if (source.getPropertyNames() != null) target.setPropertyNames(source.getPropertyNames());
        if (source.getContains() != null) target.setContains(source.getContains());
        if (source.getIfSchema() != null) target.setIfSchema(source.getIfSchema());
        if (source.getThenSchema() != null) target.setThenSchema(source.getThenSchema());
        if (source.getElseSchema() != null) target.setElseSchema(source.getElseSchema());
        if (source.getConstValue() != null) target.setConstValue(source.getConstValue());
        if (source.getExternalDocs() != null) target.setExternalDocs(source.getExternalDocs());
        if (source.getItems() != null) target.setItems(source.getItems());
        if (source.getAllOf() != null) target.setAllOf(source.getAllOf());
        if (source.getAnyOf() != null) target.setAnyOf(source.getAnyOf());
        if (source.getOneOf() != null) target.setOneOf(source.getOneOf());
        if (source.getNot() != null) target.setNot(source.getNot());
        if (source.getMaximum() != null) target.setMaximum(source.getMaximum());
        if (source.getExclusiveMaximum() != null) target.setExclusiveMaximum(source.getExclusiveMaximum());
        if (source.getMinimum() != null) target.setMinimum(source.getMinimum());
        if (source.getExclusiveMinimum() != null) target.setExclusiveMinimum(source.getExclusiveMinimum());
        if (source.getMaxLength() != null) target.setMaxLength(source.getMaxLength());
        if (source.getMinLength() != null) target.setMinLength(source.getMinLength());
        if (source.getPattern() != null) target.setPattern(source.getPattern());
        if (source.getMaxItems() != null) target.setMaxItems(source.getMaxItems());
        if (source.getMinItems() != null) target.setMinItems(source.getMinItems());
        if (source.getUniqueItems() != null) target.setUniqueItems(source.getUniqueItems());
        if (source.getMaxProperties() != null) target.setMaxProperties(source.getMaxProperties());
        if (source.getMinProperties() != null) target.setMinProperties(source.getMinProperties());
        if (source.getMultipleOf() != null) target.setMultipleOf(source.getMultipleOf());
        if (source.getEnumeration() != null) target.setEnumeration(source.getEnumeration());
        // additionalProperties is a union (boolean OR schema); copy the active form only,
        // otherwise the synthesised Schema returned by getAdditionalPropertiesSchema() for the
        // boolean form would clobber the boolean (cf. SchemaImpl).
        if (source.getAdditionalPropertiesBoolean() != null) {
            target.setAdditionalPropertiesBoolean(source.getAdditionalPropertiesBoolean());
        } else if (source.getAdditionalPropertiesSchema() != null) {
            target.setAdditionalPropertiesSchema(source.getAdditionalPropertiesSchema());
        }
        if (source.getComment() != null) target.setComment(source.getComment());
        if (source.getExtensions() != null) {
            for (Map.Entry<String, Object> extension : source.getExtensions().entrySet()) {
                target.addExtension(extension.getKey(), extension.getValue());
            }
        }
    }

    private String uniqueName(String base) {
        if (!schemas.containsKey(base) && !classToName.containsValue(base)) {
            return base;
        }
        int i = 2;
        while (schemas.containsKey(base + i) || classToName.containsValue(base + i)) {
            i++;
        }
        return base + i;
    }
}

