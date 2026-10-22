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

package io.vidocq.bodoni.core.testing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-path request dispatcher behaviours, shared by {@link TestHttpServletRequest} and
 * {@link TestServletContext}. {@link #get(String)} returns {@code null} for a path with
 * no configured behaviour (as a container does for an unknown resource).
 *
 * <p>A dispatcher's {@code include} and {@code forward} behave the same: set the
 * configured status on the response it receives (if any), then throw the configured
 * failure (if any), else write the configured content to {@code response.getWriter()}.
 */
public final class TestDispatchers {

    private final Map<String, String> contents = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();
    private final Map<String, Exception> failures = new HashMap<>();
    private final Map<String, Action> actions = new HashMap<>();
    private final List<String> included = new ArrayList<>();

    /** Arbitrary behaviour of a dispatched path, run with the request and response it receives. */
    @FunctionalInterface
    public interface Action {
        void run(ServletRequest request, ServletResponse response) throws Exception;
    }

    /** The path runs {@code action} when dispatched (after status, before configured content). */
    public TestDispatchers action(String path, Action action) {
        actions.put(path, action);
        return this;
    }

    /** The path serves {@code content}. */
    public TestDispatchers content(String path, String content) {
        contents.put(path, content);
        return this;
    }

    /** The path sets this status on the response (404 for example). */
    public TestDispatchers status(String path, int status) {
        statuses.put(path, status);
        if (!contents.containsKey(path)) {
            contents.put(path, "");
        }
        return this;
    }

    /**
     * The path throws {@code failure}: {@code IOException}, {@code ServletException} and
     * runtime exceptions as they are, anything else wrapped in a {@code ServletException}.
     */
    public TestDispatchers failWith(String path, Exception failure) {
        failures.put(path, failure);
        return this;
    }

    /** Paths passed to {@code include} or {@code forward}, in call order. */
    public List<String> calls() {
        return List.copyOf(included);
    }

    /** The dispatcher for {@code path}, or {@code null} when none is configured. */
    public RequestDispatcher get(String path) {
        if (!contents.containsKey(path) && !failures.containsKey(path) && !actions.containsKey(path)) {
            return null;
        }
        return new RequestDispatcher() {
            @Override
            public void forward(ServletRequest request, ServletResponse response)
                    throws ServletException, IOException {
                dispatch(path, request, response);
            }

            @Override
            public void include(ServletRequest request, ServletResponse response)
                    throws ServletException, IOException {
                dispatch(path, request, response);
            }
        };
    }

    private void dispatch(String path, ServletRequest request, ServletResponse response) throws ServletException, IOException {
        included.add(path);
        Integer status = statuses.get(path);
        if (status != null && response instanceof HttpServletResponse http) {
            http.setStatus(status);
        }
        Action action = actions.get(path);
        if (action != null) {
            try {
                action.run(request, response);
            } catch (IOException | ServletException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }
        Exception failure = failures.get(path);
        if (failure != null) {
            switch (failure) {
                case IOException e -> throw e;
                case ServletException e -> throw e;
                case RuntimeException e -> throw e;
                default -> throw new ServletException(failure);
            }
        }
        String content = contents.get(path);
        if (content != null && !content.isEmpty()) {
            response.getWriter().write(content);
        }
    }
}
