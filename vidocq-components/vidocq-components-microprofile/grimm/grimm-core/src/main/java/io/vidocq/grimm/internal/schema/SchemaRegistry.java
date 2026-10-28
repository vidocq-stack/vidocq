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
import java.util.function.Consumer;
import java.util.function.Supplier;

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

        Map<String, Schema> properties = mergedProperties(generated.getProperties(), explicit.getProperties());
        if (properties != null) {
            merged.setProperties(properties);
        }
        List<String> required = mergedRequired(generated.getRequired(), explicit.getRequired());
        if (required != null) {
            merged.setRequired(required);
        }
        return merged;
    }

    /** The union of both property maps, the explicit entries winning; {@code null} when both are unset. */
    private static Map<String, Schema> mergedProperties(Map<String, Schema> generatedProperties,
            Map<String, Schema> explicitProperties) {
        if (generatedProperties == null && explicitProperties == null) {
            return null;
        }
        Map<String, Schema> mergedProperties = new LinkedHashMap<>();
        if (generatedProperties != null) {
            mergedProperties.putAll(generatedProperties);
        }
        if (explicitProperties != null) {
            mergedProperties.putAll(explicitProperties);
        }
        return mergedProperties;
    }

    /** The generated required names followed by the new explicit ones; {@code null} when both are unset. */
    private static List<String> mergedRequired(List<String> generatedRequired, List<String> explicitRequired) {
        if (generatedRequired == null && explicitRequired == null) {
            return null;
        }
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
        return required;
    }

    private void copySchema(Schema source, Schema target) {
        copyAnnotationAndApplicatorFields(source, target);
        copyValidationFields(source, target);
        // additionalProperties is a union (boolean OR schema); copy the active form only,
        // otherwise the synthesised Schema returned by getAdditionalPropertiesSchema() for the
        // boolean form would clobber the boolean (cf. SchemaImpl).
        if (source.getAdditionalPropertiesBoolean() != null) {
            target.setAdditionalPropertiesBoolean(source.getAdditionalPropertiesBoolean());
        } else if (source.getAdditionalPropertiesSchema() != null) {
            target.setAdditionalPropertiesSchema(source.getAdditionalPropertiesSchema());
        }
        copyIfSet(source::getComment, target::setComment);
        if (source.getExtensions() != null) {
            for (Map.Entry<String, Object> extension : source.getExtensions().entrySet()) {
                target.addExtension(extension.getKey(), extension.getValue());
            }
        }
    }

    private static void copyAnnotationAndApplicatorFields(Schema source, Schema target) {
        copyIfSet(source::getRef, target::setRef);
        copyIfSet(source::getTitle, target::setTitle);
        copyIfSet(source::getDescription, target::setDescription);
        copyIfSet(source::getType, target::setType);
        copyIfSet(source::getFormat, target::setFormat);
        copyIfSet(source::getDefaultValue, target::setDefaultValue);
        copyIfSet(source::getExample, target::setExample);
        copyIfSet(source::getExamples, target::setExamples);
        copyIfSet(source::getReadOnly, target::setReadOnly);
        copyIfSet(source::getWriteOnly, target::setWriteOnly);
        copyIfSet(source::getDeprecated, target::setDeprecated);
        copyIfSet(source::getDiscriminator, target::setDiscriminator);
        copyIfSet(source::getXml, target::setXml);
        copyIfSet(source::getDependentRequired, target::setDependentRequired);
        copyIfSet(source::getDependentSchemas, target::setDependentSchemas);
        copyIfSet(source::getPropertyNames, target::setPropertyNames);
        copyIfSet(source::getContains, target::setContains);
        copyIfSet(source::getIfSchema, target::setIfSchema);
        copyIfSet(source::getThenSchema, target::setThenSchema);
        copyIfSet(source::getElseSchema, target::setElseSchema);
        copyIfSet(source::getConstValue, target::setConstValue);
    }

    private static void copyValidationFields(Schema source, Schema target) {
        copyIfSet(source::getExternalDocs, target::setExternalDocs);
        copyIfSet(source::getItems, target::setItems);
        copyIfSet(source::getAllOf, target::setAllOf);
        copyIfSet(source::getAnyOf, target::setAnyOf);
        copyIfSet(source::getOneOf, target::setOneOf);
        copyIfSet(source::getNot, target::setNot);
        copyIfSet(source::getMaximum, target::setMaximum);
        copyIfSet(source::getExclusiveMaximum, target::setExclusiveMaximum);
        copyIfSet(source::getMinimum, target::setMinimum);
        copyIfSet(source::getExclusiveMinimum, target::setExclusiveMinimum);
        copyIfSet(source::getMaxLength, target::setMaxLength);
        copyIfSet(source::getMinLength, target::setMinLength);
        copyIfSet(source::getPattern, target::setPattern);
        copyIfSet(source::getMaxItems, target::setMaxItems);
        copyIfSet(source::getMinItems, target::setMinItems);
        copyIfSet(source::getUniqueItems, target::setUniqueItems);
        copyIfSet(source::getMaxProperties, target::setMaxProperties);
        copyIfSet(source::getMinProperties, target::setMinProperties);
        copyIfSet(source::getMultipleOf, target::setMultipleOf);
        copyIfSet(source::getEnumeration, target::setEnumeration);
    }

    private static <T> void copyIfSet(Supplier<T> getter, Consumer<T> setter) {
        T value = getter.get();
        if (value != null) {
            setter.accept(value);
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

