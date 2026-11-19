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

import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;

import java.util.Map;

public class MediaTypeImpl extends AbstractExtensible<MediaType> implements MediaType {

    private Schema schema;
    private Map<String, Example> examples;
    private Object example;
    private Map<String, Encoding> encoding;

    @Override public Schema getSchema() { return schema; }
    @Override public void setSchema(Schema schema) { this.schema = schema; }

    @Override public Map<String, Example> getExamples() { return ModelCollections.immutableMapView(examples); }
    @Override public void setExamples(Map<String, Example> examples) { this.examples = ModelCollections.mutableMap(examples); }

    @Override
    public MediaType addExample(String key, Example example) {
        if (example == null) return this;
        examples = ModelCollections.copyOnWriteMap(examples);
        examples.put(key, example);
        return this;
    }

    @Override
    public void removeExample(String key) {
        if (examples != null) {
            examples = ModelCollections.copyOnWriteMap(examples);
            examples.remove(key);
        }
    }

    @Override public Object getExample() { return example; }
    @Override public void setExample(Object example) { this.example = example; }

    @Override public Map<String, Encoding> getEncoding() { return ModelCollections.immutableMapView(encoding); }
    @Override public void setEncoding(Map<String, Encoding> encoding) { this.encoding = ModelCollections.mutableMap(encoding); }

    @Override
    public MediaType addEncoding(String key, Encoding encodingItem) {
        if (encodingItem == null) return this;
        encoding = ModelCollections.copyOnWriteMap(encoding);
        encoding.put(key, encodingItem);
        return this;
    }

    @Override
    public void removeEncoding(String key) {
        if (encoding != null) {
            encoding = ModelCollections.copyOnWriteMap(encoding);
            encoding.remove(key);
        }
    }
}

