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
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;

import java.util.LinkedHashMap;
import java.util.Map;

public class ParameterImpl extends AbstractExtensibleRef<Parameter> implements Parameter {

    private String name;
    private In in;
    private String description;
    private Boolean required;
    private Boolean deprecated;
    private Boolean allowEmptyValue;
    private Style style;
    private Boolean explode;
    private Boolean allowReserved;
    private Schema schema;
    private Map<String, Example> examples;
    private Object example;
    private Content content;

    @Override protected String resolveComponentPrefix() { return "#/components/parameters/"; }

    @Override public String getName() { return name; }
    @Override public void setName(String name) { this.name = name; }

    @Override public In getIn() { return in; }
    @Override public void setIn(In in) { this.in = in; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Boolean getRequired() { return required; }
    @Override public void setRequired(Boolean required) { this.required = required; }

    @Override public Boolean getDeprecated() { return deprecated; }
    @Override public void setDeprecated(Boolean deprecated) { this.deprecated = deprecated; }

    @Override public Boolean getAllowEmptyValue() { return allowEmptyValue; }
    @Override public void setAllowEmptyValue(Boolean allowEmptyValue) { this.allowEmptyValue = allowEmptyValue; }

    @Override public Style getStyle() { return style; }
    @Override public void setStyle(Style style) { this.style = style; }

    @Override public Boolean getExplode() { return explode; }
    @Override public void setExplode(Boolean explode) { this.explode = explode; }

    @Override public Boolean getAllowReserved() { return allowReserved; }
    @Override public void setAllowReserved(Boolean allowReserved) { this.allowReserved = allowReserved; }

    @Override public Schema getSchema() { return schema; }
    @Override public void setSchema(Schema schema) { this.schema = schema; }

    @Override public Map<String, Example> getExamples() { return ModelCollections.immutableMapView(examples); }
    @Override public void setExamples(Map<String, Example> examples) { this.examples = ModelCollections.mutableMap(examples); }

    @Override
    public Parameter addExample(String key, Example example) {
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

    @Override public Content getContent() { return content; }
    @Override public void setContent(Content content) { this.content = content; }
}

