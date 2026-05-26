package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.Extensible;
import org.eclipse.microprofile.openapi.models.Reference;

/**
 * Base class for model POJOs that are both {@link Extensible} and {@link Reference}.
 *
 * @param <T> the self-type
 */
public abstract class AbstractExtensibleRef<T extends Extensible<T> & Reference<T>>
        extends AbstractExtensible<T>
        implements Reference<T> {

    private String ref;

    @Override
    public String getRef() {
        return ref;
    }

    @Override
    public void setRef(String ref) {
        // Expand short names to full $ref per spec
        if (ref != null && !ref.contains("/")) {
            ref = resolveComponentPrefix() + ref;
        }
        this.ref = ref;
    }

    /**
     * Returns the component prefix for short-name expansion.
     * Sub-classes override to return the correct prefix
     * (e.g. {@code "#/components/schemas/"}).
     */
    protected String resolveComponentPrefix() {
        return "#/components/";
    }
}

