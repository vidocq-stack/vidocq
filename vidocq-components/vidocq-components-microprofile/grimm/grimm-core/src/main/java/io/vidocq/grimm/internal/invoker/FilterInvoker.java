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
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.tags.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Invokes the configured {@link OASFilter} to post-process the OpenAPI model.
 *
 * <p>Spec §4.3 — filter sequence (leaf nodes upward, {@code filterOpenAPI} last):
 * {@code filterPathItem}, {@code filterOperation}, {@code filterParameter},
 * {@code filterRequestBody}, {@code filterAPIResponse}, {@code filterSchema},
 * {@code filterHeader}, {@code filterTag}, {@code filterServer}, {@code filterLink},
 * {@code filterCallback}, {@code filterSecurityScheme} (components scope), and
 * finally {@code filterOpenAPI}.</p>
 *
 * <p>Spec §4.3.1: any {@code filterXxx} returning {@code null} removes the element
 * from its parent collection (or clears the verb slot on a {@link PathItem}).</p>
 */
public final class FilterInvoker {

    /**
     * Applies the configured filter to the OpenAPI model.
     *
     * @param model  the OpenAPI model to filter (mutated in place)
     * @param config the configuration
     * @return the filtered model
     */
    public OpenAPI applyFilter(OpenAPI model, FilterConfig config) {
        if (!config.hasFilter() || model == null) {
            return model;
        }
        OASFilter filter = instantiateFilter(config.filterClassName());

        filterPaths(model, filter);
        filterTopLevelTags(model, filter);
        filterServers(model.getServers(), filter, model::setServers);
        filterComponents(model, filter);

        filter.filterOpenAPI(model);
        return model;
    }

    // -------------- Paths / PathItems / Operations --------------

    private void filterPaths(OpenAPI model, OASFilter filter) {
        var paths = model.getPaths();
        Map<String, PathItem> pathItems = paths == null ? null : paths.getPathItems();
        if (pathItems == null || pathItems.isEmpty()) return;

        Map<String, PathItem> kept = new LinkedHashMap<>();
        for (Map.Entry<String, PathItem> e : pathItems.entrySet()) {
            PathItem candidate = e.getValue();
            filterOperations(candidate, filter);
            filterParameters(candidate.getParameters(), filter, candidate::setParameters);
            filterServers(candidate.getServers(), filter, candidate::setServers);

            PathItem filteredItem = filter.filterPathItem(candidate);
            if (filteredItem == null) {
                continue;
            }
            kept.put(e.getKey(), filteredItem);
        }
        paths.setPathItems(kept);
    }

    private void filterOperations(PathItem item, OASFilter filter) {
        for (PathItem.HttpMethod m : PathItem.HttpMethod.values()) {
            Operation op = getOperation(item, m);
            if (op == null) continue;
            filterOperationDetails(op, filter);
            Operation filtered = filter.filterOperation(op);
            if (filtered == null) {
                setOperation(item, m, null);
                continue;
            }
            setOperation(item, m, filtered);
        }
    }

    private void filterOperationDetails(Operation op, OASFilter filter) {
        filterParameters(op.getParameters(), filter, op::setParameters);

        RequestBody rb = op.getRequestBody();
        if (rb != null) {
            RequestBody fr = filter.filterRequestBody(rb);
            op.setRequestBody(fr);
            if (fr != null) filterContentSchemas(fr.getContent(), filter);
        }

        APIResponses responses = op.getResponses();
        if (responses != null && responses.getAPIResponses() != null) {
            Map<String, APIResponse> keptResponses = new LinkedHashMap<>();
            for (Map.Entry<String, APIResponse> entry : responses.getAPIResponses().entrySet()) {
                APIResponse candidate = entry.getValue();
                filterContentSchemas(candidate.getContent(), filter);
                filterMap(candidate.getHeaders(), filter::filterHeader, candidate::setHeaders);
                filterMap(candidate.getLinks(), filter::filterLink, candidate::setLinks);

                APIResponse filtered = filter.filterAPIResponse(candidate);
                if (filtered == null) {
                    continue;
                }
                keptResponses.put(entry.getKey(), filtered);
            }
            responses.setAPIResponses(keptResponses);
        }

        if (op.getCallbacks() != null) {
            filterMap(op.getCallbacks(), filter::filterCallback, op::setCallbacks);
        }
        filterServers(op.getServers(), filter, op::setServers);
    }

    private void filterParameters(List<Parameter> params, OASFilter filter,
                                  Consumer<List<Parameter>> setter) {
        if (params == null || params.isEmpty()) return;
        List<Parameter> kept = new ArrayList<>();
        for (Parameter p : params) {
            Parameter filtered = filter.filterParameter(p);
            if (filtered == null) continue;
            if (filtered.getSchema() != null) {
                filtered.setSchema(filter.filterSchema(filtered.getSchema()));
            }
            kept.add(filtered);
        }
        setter.accept(kept);
    }

    private void filterContentSchemas(Content content, OASFilter filter) {
        if (content == null || content.getMediaTypes() == null) return;
        for (Map.Entry<String, MediaType> e : content.getMediaTypes().entrySet()) {
            MediaType mt = e.getValue();
            if (mt != null && mt.getSchema() != null) {
                mt.setSchema(filter.filterSchema(mt.getSchema()));
            }
        }
    }

    private void filterServers(List<Server> servers, OASFilter filter,
                               Consumer<List<Server>> setter) {
        if (servers == null || servers.isEmpty()) return;
        List<Server> kept = new ArrayList<>();
        for (Server s : servers) {
            Server filtered = filter.filterServer(s);
            if (filtered != null) kept.add(filtered);
        }
        setter.accept(kept);
    }

    private void filterTopLevelTags(OpenAPI model, OASFilter filter) {
        List<Tag> tags = model.getTags();
        if (tags == null || tags.isEmpty()) return;
        List<Tag> kept = new ArrayList<>();
        for (Tag t : tags) {
            Tag filtered = filter.filterTag(t);
            if (filtered != null) kept.add(filtered);
        }
        model.setTags(kept);
    }

    // -------------- Components --------------

    private void filterComponents(OpenAPI model, OASFilter filter) {
        Components c = model.getComponents();
        if (c == null) return;
        filterMap(c.getSchemas(), filter::filterSchema, c::setSchemas);
        filterMap(c.getHeaders(), filter::filterHeader, c::setHeaders);
        filterMap(c.getParameters(), filter::filterParameter, c::setParameters);
        filterMap(c.getRequestBodies(), filter::filterRequestBody, c::setRequestBodies);
        filterMap(c.getResponses(), filter::filterAPIResponse, c::setResponses);
        filterMap(c.getLinks(), filter::filterLink, c::setLinks);
        filterMap(c.getCallbacks(), filter::filterCallback, c::setCallbacks);
        filterMap(c.getSecuritySchemes(), filter::filterSecurityScheme, c::setSecuritySchemes);
    }

    private <V> void filterMap(Map<String, V> map, Function<V, V> fn, Consumer<Map<String, V>> setter) {
        if (map == null || map.isEmpty()) return;
        Map<String, V> kept = new LinkedHashMap<>();
        for (Map.Entry<String, V> e : map.entrySet()) {
            V filtered = fn.apply(e.getValue());
            if (filtered != null) {
                kept.put(e.getKey(), filtered);
            }
        }
        setter.accept(kept);
    }

    // -------------- PathItem verb helpers --------------

    private static Operation getOperation(PathItem item, PathItem.HttpMethod m) {
        return switch (m) {
            case GET -> item.getGET();
            case POST -> item.getPOST();
            case PUT -> item.getPUT();
            case DELETE -> item.getDELETE();
            case PATCH -> item.getPATCH();
            case HEAD -> item.getHEAD();
            case OPTIONS -> item.getOPTIONS();
            case TRACE -> item.getTRACE();
        };
    }

    private static void setOperation(PathItem item, PathItem.HttpMethod m, Operation op) {
        switch (m) {
            case GET -> item.setGET(op);
            case POST -> item.setPOST(op);
            case PUT -> item.setPUT(op);
            case DELETE -> item.setDELETE(op);
            case PATCH -> item.setPATCH(op);
            case HEAD -> item.setHEAD(op);
            case OPTIONS -> item.setOPTIONS(op);
            case TRACE -> item.setTRACE(op);
        }
    }

    private OASFilter instantiateFilter(String filterClassName) {
        try {
            Class<?> filterClass = Class.forName(filterClassName);
            return (OASFilter) filterClass.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Filter class not found: " + filterClassName, e);
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Class does not implement OASFilter: " + filterClassName, e);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to instantiate or invoke filter: " + filterClassName, e);
        }
    }
}

