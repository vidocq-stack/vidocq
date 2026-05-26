package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Invokes the configured {@link OASModelReader} to build an OpenAPI model.
 *
 * Spec §4.1: OASModelReader is instantiated by class name and called to build the model.
 */
public final class ModelReaderInvoker {

    /**
     * Invokes the model reader if configured in the given {@code FilterConfig}.
     *
     * @param config the configuration
     * @return the model built by the reader, or null if no reader is configured
     * @throws IllegalArgumentException if the reader cannot be instantiated or fails
     */
    public OpenAPI invokeModelReader(FilterConfig config) {
        if (!config.hasModelReader()) {
            return null;
        }

        try {
            Class<?> readerClass = Class.forName(config.modelReaderClassName());
            OASModelReader reader = (OASModelReader) readerClass.getDeclaredConstructor().newInstance();

            return reader.buildModel();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Model reader class not found: " + config.modelReaderClassName(), e);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Class does not implement OASModelReader: " + config.modelReaderClassName(), e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to instantiate or invoke model reader: " + config.modelReaderClassName(), e);
        }
    }
}



