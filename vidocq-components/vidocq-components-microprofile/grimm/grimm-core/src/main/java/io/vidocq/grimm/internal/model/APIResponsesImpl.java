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

import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

import java.util.Map;

public class APIResponsesImpl extends AbstractExtensible<APIResponses> implements APIResponses {

    private Map<String, APIResponse> responses;
    private APIResponse defaultValue;

    @Override
    public APIResponses addAPIResponse(String name, APIResponse apiResponse) {
        if (apiResponse == null) return this;
        responses = ModelCollections.copyOnWriteMap(responses);
        responses.put(name, apiResponse);
        if (DEFAULT.equals(name)) {
            defaultValue = apiResponse;
        }
        return this;
    }

    @Override
    public void removeAPIResponse(String name) {
        if (responses != null) {
            responses = ModelCollections.copyOnWriteMap(responses);
            responses.remove(name);
        }
        if (DEFAULT.equals(name)) {
            defaultValue = null;
        }
    }

    @Override
    public Map<String, APIResponse> getAPIResponses() { return ModelCollections.immutableMapView(responses); }

    @Override
    public void setAPIResponses(Map<String, APIResponse> items) {
        this.responses = ModelCollections.mutableMap(items);
        this.defaultValue = responses == null ? null : responses.get(DEFAULT);
    }

    @Override
    public APIResponse getDefaultValue() {
        return defaultValue;
    }

    @Override
    public void setDefaultValue(APIResponse defaultValue) {
        if (defaultValue == null) {
            removeAPIResponse(DEFAULT);
            return;
        }
        addAPIResponse(DEFAULT, defaultValue);
    }
}

