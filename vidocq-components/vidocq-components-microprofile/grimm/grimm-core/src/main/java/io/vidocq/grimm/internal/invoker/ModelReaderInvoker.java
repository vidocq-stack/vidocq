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
package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
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



