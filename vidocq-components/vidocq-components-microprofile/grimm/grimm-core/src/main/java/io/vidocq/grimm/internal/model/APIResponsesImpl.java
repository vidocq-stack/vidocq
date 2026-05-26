package io.vidocq.grimm.internal.model;

import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;

import java.util.LinkedHashMap;
import java.util.Map;

public class APIResponsesImpl extends AbstractExtensible<APIResponses> implements APIResponses {

    private Map<String, APIResponse> responses;

    @Override
    public APIResponses addAPIResponse(String name, APIResponse apiResponse) {
        if (apiResponse == null) return this;
        if (responses == null) responses = new LinkedHashMap<>();
        responses.put(name, apiResponse);
        return this;
    }

    @Override
    public void removeAPIResponse(String name) {
        if (responses != null) responses.remove(name);
    }

    @Override
    public Map<String, APIResponse> getAPIResponses() { return responses; }

    @Override
    public void setAPIResponses(Map<String, APIResponse> items) { this.responses = items; }

    @Override
    public APIResponse getDefaultValue() {
        return responses == null ? null : responses.get(DEFAULT);
    }

    @Override
    public void setDefaultValue(APIResponse defaultValue) {
        addAPIResponse(DEFAULT, defaultValue);
    }
}

