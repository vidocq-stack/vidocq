package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

import java.util.LinkedHashMap;
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

