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

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.models.Components;
import org.eclipse.microprofile.openapi.models.Constructible;
import org.eclipse.microprofile.openapi.models.ExternalDocumentation;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.Paths;
import org.eclipse.microprofile.openapi.models.callbacks.Callback;
import org.eclipse.microprofile.openapi.models.examples.Example;
import org.eclipse.microprofile.openapi.models.headers.Header;
import org.eclipse.microprofile.openapi.models.info.Contact;
import org.eclipse.microprofile.openapi.models.info.Info;
import org.eclipse.microprofile.openapi.models.info.License;
import org.eclipse.microprofile.openapi.models.links.Link;
import org.eclipse.microprofile.openapi.models.media.Content;
import org.eclipse.microprofile.openapi.models.media.Discriminator;
import org.eclipse.microprofile.openapi.models.media.Encoding;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.eclipse.microprofile.openapi.models.media.XML;
import org.eclipse.microprofile.openapi.models.parameters.Parameter;
import org.eclipse.microprofile.openapi.models.parameters.RequestBody;
import org.eclipse.microprofile.openapi.models.responses.APIResponse;
import org.eclipse.microprofile.openapi.models.responses.APIResponses;
import org.eclipse.microprofile.openapi.models.security.OAuthFlow;
import org.eclipse.microprofile.openapi.models.security.OAuthFlows;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.models.security.SecurityScheme;
import org.eclipse.microprofile.openapi.models.servers.Server;
import org.eclipse.microprofile.openapi.models.servers.ServerVariable;
import org.eclipse.microprofile.openapi.models.tags.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Contract of every model object built by {@link OASFactory}, read from the MP OpenAPI 4.2
 * models Javadoc: a property set is the property read; {@code addX} adds an element that
 * {@code removeX} removes again; the collections the getters return cannot be used to change the
 * model; a {@code null} value given to a keyed {@code addX(key, value)} is ignored or rejected, and
 * removing from an empty model changes nothing. Setting {@code null} clears a property (an empty collection counts as cleared).
 *
 * <p>The accessors are found by reflection on the model interfaces so that every one of them is
 * checked, including those added by a later version of the API.</p>
 */
class ModelContractTest {

    /** A value that is a valid reference too: {@code setRef} keeps a value holding a {@code /} as is. */
    private static final String TEXT = "#/text";

    static Stream<Class<? extends Constructible>> modelTypes() {
        return Stream.of(OpenAPI.class, Components.class, ExternalDocumentation.class, Operation.class,
                PathItem.class, Paths.class, Callback.class, Example.class, Header.class, Contact.class,
                Info.class, License.class, Link.class, Content.class, Discriminator.class, Encoding.class,
                MediaType.class, Schema.class, XML.class, Parameter.class, RequestBody.class,
                APIResponse.class, APIResponses.class, OAuthFlow.class, OAuthFlows.class,
                SecurityRequirement.class, SecurityScheme.class, Server.class, ServerVariable.class, Tag.class);
    }

    @ParameterizedTest
    @MethodSource("modelTypes")
    void everyPropertySetIsTheValueRead(Class<? extends Constructible> type) throws Exception {
        int checked = 0;
        for (Method setter : type.getMethods()) {
            Method getter = getterOf(type, setter).orElse(null);
            Object value = getter == null ? null : valueFor(setter.getGenericParameterTypes()[0]);
            if (value == null) {
                continue;
            }
            Constructible model = OASFactory.createObject(type);
            String label = type.getSimpleName() + "." + setter.getName();
            setter.invoke(model, value);
            assertEquals(value, getter.invoke(model), label);
            setter.invoke(model, (Object) null);
            assertTrue(isNullOrEmpty(getter.invoke(model)), label + "(null)");
            checked++;
        }
        assertTrue(checked > 0, type.getSimpleName() + " has no property");
    }

    @ParameterizedTest
    @MethodSource("modelTypes")
    void addedElementsAreRemovedAgain(Class<? extends Constructible> type) throws Exception {
        for (Method add : type.getMethods()) {
            Object[] args = add.getName().startsWith("add") ? argumentsFor(add) : null;
            Method remove = args == null ? null : removerOf(type, add).orElse(null);
            if (remove == null) {
                continue;
            }
            Constructible model = OASFactory.createObject(type);
            String label = type.getSimpleName() + "." + add.getName();
            int before = elementCount(model);
            add.invoke(model, args);
            assertTrue(elementCount(model) > before, label + " added nothing");
            remove.invoke(model, removeArgument(add, remove, args));
            assertEquals(before, elementCount(model), label + " then remove");
        }
    }

    @ParameterizedTest
    @MethodSource("modelTypes")
    void returnedCollectionsDoNotChangeTheModel(Class<? extends Constructible> type) throws Exception {
        Constructible model = OASFactory.createObject(type);
        for (Method add : type.getMethods()) {
            Object[] args = add.getName().startsWith("add") ? argumentsFor(add) : null;
            if (args != null) {
                add.invoke(model, args);
            }
        }
        int count = elementCount(model);
        for (Object collection : collections(model)) {
            clearQuietly(collection);
        }
        assertEquals(count, elementCount(model), type.getSimpleName());
    }

    @ParameterizedTest
    @MethodSource("modelTypes")
    void aNullValueGivenToAKeyedAddIsIgnoredOrRejected(Class<? extends Constructible> type) throws Exception {
        for (Method add : type.getMethods()) {
            Object[] args = isModelAdd(add) ? argumentsFor(add) : null;
            if (args == null) {
                continue;
            }
            args[args.length - 1] = null;
            Constructible model = OASFactory.createObject(type);
            if (invokeOrRejected(model, add, args)) {
                assertEquals(0, elementCount(model), type.getSimpleName() + "." + add.getName() + "(key, null)");
            }
        }
        assertTrue(type.isInstance(OASFactory.createObject(type)));
    }

    @ParameterizedTest
    @MethodSource("modelTypes")
    void removingFromAnEmptyModelChangesNothing(Class<? extends Constructible> type) throws Exception {
        for (Method add : type.getMethods()) {
            Object[] args = add.getName().startsWith("add") ? argumentsFor(add) : null;
            Method remove = args == null ? null : removerOf(type, add).orElse(null);
            if (remove != null) {
                Constructible model = OASFactory.createObject(type);
                remove.invoke(model, removeArgument(add, remove, args));
                assertEquals(0, elementCount(model), type.getSimpleName() + "." + remove.getName());
            }
        }
        assertTrue(type.isInstance(OASFactory.createObject(type)));
    }

    @Test
    void aNullScopeGivesAnEmptyListOfScopes() {
        SecurityRequirement requirement = OASFactory.createSecurityRequirement()
                .addScheme("one", (String) null)
                .addScheme("two", (List<String>) null);

        assertEquals(Map.of("one", List.of(), "two", List.of()), requirement.getSchemes());
    }

    // ── reflection helpers ──

    /**
     * The keyed {@code addX(key, value)} methods, whose Javadoc says a {@code null} value is
     * rejected or ignored. {@code SecurityRequirement.addScheme} documents another behaviour
     * (an empty list of scopes) and {@code OAuthFlow.addScope} documents none.
     */
    private static boolean isModelAdd(Method add) {
        return add.getName().startsWith("add") && add.getParameterCount() == 2
                && !"addScheme".equals(add.getName()) && !"addScope".equals(add.getName());
    }

    private static boolean isNullOrEmpty(Object value) {
        return value == null || value instanceof Map<?, ?> map && map.isEmpty();
    }

    private static Optional<Method> getterOf(Class<?> type, Method setter) {
        if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1
                || setter.getReturnType() != void.class) {
            return Optional.empty();
        }
        String property = setter.getName().substring(3);
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals("get" + property) && candidate.getParameterCount() == 0
                    && candidate.getReturnType() == setter.getParameterTypes()[0]) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Optional<Method> removerOf(Class<?> type, Method add) {
        String name = "remove" + add.getName().substring(3);
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == 1) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Object removeArgument(Method add, Method remove, Object[] addArgs) {
        boolean byKey = add.getParameterCount() == 2 && remove.getParameterTypes()[0] == String.class;
        return byKey ? addArgs[0] : addArgs[addArgs.length - 1];
    }

    private static Object[] argumentsFor(Method method) {
        Type[] types = method.getGenericParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = valueFor(types[i]);
            if (args[i] == null) {
                return null;
            }
        }
        return args;
    }

    /** A non-null sample of {@code type}, or {@code null} when the type is not supported here. */
    private static Object valueFor(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return collectionOf(parameterized);
        }
        if (!(type instanceof Class<?> raw)) {
            return null;
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (Constructible.class.isAssignableFrom(raw) && raw.isInterface()) {
            return OASFactory.createObject(raw.asSubclass(Constructible.class));
        }
        return scalarFor(raw);
    }

    private static Object scalarFor(Class<?> raw) {
        if (raw == String.class || raw == Object.class) {
            return TEXT;
        }
        if (raw == Boolean.class) {
            return Boolean.TRUE;
        }
        if (raw == Integer.class) {
            return 3;
        }
        return raw == BigDecimal.class ? BigDecimal.TEN : null;
    }

    private static Object collectionOf(ParameterizedType type) {
        Type[] arguments = type.getActualTypeArguments();
        Object element = valueFor(arguments[arguments.length - 1]);
        if (element == null) {
            return null;
        }
        if (type.getRawType() == List.class) {
            return List.of(element);
        }
        return type.getRawType() == Map.class ? Map.of("key", element) : null;
    }

    private static List<Object> collections(Object model) throws ReflectiveOperationException {
        List<Object> found = new ArrayList<>();
        for (Method getter : model.getClass().getMethods()) {
            // Schema#getAll is a view of every other property, not a collection of its own.
            boolean collectionGetter = getter.getName().startsWith("get") && getter.getParameterCount() == 0
                    && !"getAll".equals(getter.getName())
                    && !Modifier.isStatic(getter.getModifiers())
                    && (Map.class.isAssignableFrom(getter.getReturnType())
                    || Collection.class.isAssignableFrom(getter.getReturnType()));
            Object value = collectionGetter ? getter.invoke(model) : null;
            if (value != null) {
                found.add(value);
            }
        }
        return found;
    }

    private static int elementCount(Object model) throws ReflectiveOperationException {
        int count = 0;
        for (Object collection : collections(model)) {
            count += collection instanceof Map<?, ?> map ? map.size() : ((Collection<?>) collection).size();
        }
        return count;
    }

    private static void clearQuietly(Object collection) {
        try {
            if (collection instanceof Map<?, ?> map) {
                map.clear();
            } else {
                ((Collection<?>) collection).clear();
            }
        } catch (UnsupportedOperationException _) {
            // an unmodifiable view is one way of honouring the contract
        }
    }

    private static boolean invokeOrRejected(Object model, Method add, Object[] args) throws IllegalAccessException {
        try {
            add.invoke(model, args);
            return true;
        } catch (InvocationTargetException rejected) {
            if (!(rejected.getCause() instanceof NullPointerException
                    || rejected.getCause() instanceof IllegalArgumentException)) {
                fail(add.getName() + " threw " + rejected.getCause());
            }
            return false;
        }
    }
}
