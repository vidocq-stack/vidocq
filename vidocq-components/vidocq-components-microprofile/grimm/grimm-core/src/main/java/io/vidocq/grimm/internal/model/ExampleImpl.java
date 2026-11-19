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

public class ExampleImpl extends AbstractExtensibleRef<Example> implements Example {

    private String summary;
    private String description;
    private Object value;
    private String externalValue;

    @Override protected String resolveComponentPrefix() { return "#/components/examples/"; }

    @Override public String getSummary() { return summary; }
    @Override public void setSummary(String summary) { this.summary = summary; }

    @Override public String getDescription() { return description; }
    @Override public void setDescription(String description) { this.description = description; }

    @Override public Object getValue() { return value; }
    @Override public void setValue(Object value) { this.value = value; }

    @Override public String getExternalValue() { return externalValue; }
    @Override public void setExternalValue(String externalValue) { this.externalValue = externalValue; }
}

