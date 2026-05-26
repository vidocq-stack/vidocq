package io.vidocq.grimm.internal.schema;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.media.Schema;

import java.util.LinkedHashMap;
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
            components.addSchema(e.getKey(), e.getValue());
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

