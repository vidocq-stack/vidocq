package io.vidocq.grimm.internal.invoker;

import io.vidocq.grimm.internal.config.FilterConfig;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;

import java.util.Iterator;
import java.util.Map;

/**
 * Invokes the configured {@link OASFilter} to post-process the OpenAPI model.
 *
 * Spec §4.3: OASFilter methods are called in a specific order.
 * Returning null from a filter method removes the element.
 */
public final class FilterInvoker {

    /**
     * Applies the configured filter to the OpenAPI model.
     *
     * Spec §4.3: Filter methods are called in order.
     *
     * @param model the OpenAPI model to filter
     * @param config the configuration
     * @return the filtered model
     * @throws IllegalArgumentException if the filter cannot be instantiated or fails
     */
    public OpenAPI applyFilter(OpenAPI model, FilterConfig config) {
        if (!config.hasFilter() || model == null) {
            return model;
        }

        OASFilter filter = instantiateFilter(config.filterClassName());

        // Spec §4.3: Filter methods in order
        // (Simplified implementation - full order documented in spec)
        if (model.getPaths() != null) {
            filterPaths(model.getPaths(), filter);
        }

        // filterOpenAPI is called last and modifies in-place
        filter.filterOpenAPI(model);
        return model;
    }

    private void filterPaths(org.eclipse.microprofile.openapi.models.Paths paths, OASFilter filter) {
        if (paths.getPathItems() == null) {
            return;
        }

        Iterator<Map.Entry<String, PathItem>> iterator = paths.getPathItems().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PathItem> entry = iterator.next();
            PathItem pathItem = entry.getValue();

            if (pathItem != null) {
                PathItem filtered = filter.filterPathItem(pathItem);
                if (filtered == null) {
                    iterator.remove();
                } else {
                    filterOperations(filtered, filter);
                }
            }
        }
    }

    private void filterOperations(PathItem pathItem, OASFilter filter) {
        // Filter GET, POST, etc.
        if (pathItem.getGET() != null) {
            Operation filtered = filter.filterOperation(pathItem.getGET());
            if (filtered == null) {
                pathItem.setGET(null);
            } else {
                pathItem.setGET(filtered);
            }
        }
        if (pathItem.getPOST() != null) {
            Operation filtered = filter.filterOperation(pathItem.getPOST());
            if (filtered == null) {
                pathItem.setPOST(null);
            } else {
                pathItem.setPOST(filtered);
            }
        }
        if (pathItem.getPUT() != null) {
            Operation filtered = filter.filterOperation(pathItem.getPUT());
            if (filtered == null) {
                pathItem.setPUT(null);
            } else {
                pathItem.setPUT(filtered);
            }
        }
        if (pathItem.getDELETE() != null) {
            Operation filtered = filter.filterOperation(pathItem.getDELETE());
            if (filtered == null) {
                pathItem.setDELETE(null);
            } else {
                pathItem.setDELETE(filtered);
            }
        }
        if (pathItem.getPATCH() != null) {
            Operation filtered = filter.filterOperation(pathItem.getPATCH());
            if (filtered == null) {
                pathItem.setPATCH(null);
            } else {
                pathItem.setPATCH(filtered);
            }
        }
        if (pathItem.getHEAD() != null) {
            Operation filtered = filter.filterOperation(pathItem.getHEAD());
            if (filtered == null) {
                pathItem.setHEAD(null);
            } else {
                pathItem.setHEAD(filtered);
            }
        }
        if (pathItem.getOPTIONS() != null) {
            Operation filtered = filter.filterOperation(pathItem.getOPTIONS());
            if (filtered == null) {
                pathItem.setOPTIONS(null);
            } else {
                pathItem.setOPTIONS(filtered);
            }
        }
        if (pathItem.getTRACE() != null) {
            Operation filtered = filter.filterOperation(pathItem.getTRACE());
            if (filtered == null) {
                pathItem.setTRACE(null);
            } else {
                pathItem.setTRACE(filtered);
            }
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



