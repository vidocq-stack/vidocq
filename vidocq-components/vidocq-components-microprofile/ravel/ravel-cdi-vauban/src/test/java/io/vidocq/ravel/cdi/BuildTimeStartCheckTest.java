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
package io.vidocq.ravel.cdi;

import io.vidocq.vauban.api.ExtensionPhase;
import io.vidocq.vauban.core.container.VaubanContainer;
import io.vidocq.vauban.core.extensions.SyntheticMetadataSerializer;
import io.vidocq.vauban.core.extensions.VaubanSyntheticObserverBuilder;
import jakarta.enterprise.inject.build.compatible.spi.BeanInfo;
import jakarta.enterprise.inject.build.compatible.spi.InjectionPointInfo;
import jakarta.enterprise.inject.build.compatible.spi.Messages;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.lang.model.declarations.DeclarationInfo;
import jakarta.enterprise.lang.model.declarations.FieldInfo;
import jakarta.enterprise.lang.model.types.ClassType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ravel#50, end to end on Vauban: the extension runs at build time only, Vauban writes the synthetic
 * observer and its parameters to the metadata of the archive, and a container that starts from that
 * metadata — where nothing registers the extension — still checks the configuration.
 *
 * <p>The build is the one the Vauban processor does: the extension, in the build phase, fills
 * Vauban's own observer builder, and Vauban's serializer writes it next to the marker that tells
 * the container the extensions of this archive already ran.</p>
 */
@DisplayName("Start check — a container started from what the build recorded")
class BuildTimeStartCheckTest {

    private static final String PREFIX = "ravel50.frozen.";
    private static final String OWNER = "com.example.Shop";

    @TempDir
    Path archive;

    private final List<String> systemProperties = new ArrayList<>();

    @AfterEach
    void clearSystemProperties() {
        systemProperties.forEach(System::clearProperty);
    }

    @Test
    void missing_required_property_fails_the_start_of_a_container_that_does_not_run_the_extension()
            throws IOException {
        buildArchive(
                injectionPoint("java.lang.String", PREFIX + "name", null, "name"),
                injectionPoint("java.lang.Integer", PREFIX + "port", null, "port"));
        set(PREFIX + "port", "not-a-number");

        DeploymentException failure = assertThrows(DeploymentException.class, this::startFromTheArchive);

        String messages = messagesOf(failure);
        assertTrue(messages.contains("Missing required config property '" + PREFIX + "name' for type"
                + " java.lang.String [at " + OWNER + "#name]"), messages);
        assertTrue(messages.contains("Invalid @ConfigProperty for key '" + PREFIX + "port'"), messages);
        assertTrue(messages.contains("[at " + OWNER + "#port]"), messages);
    }

    @Test
    void present_and_defaulted_properties_let_that_container_start() throws IOException {
        buildArchive(
                injectionPoint("java.lang.String", PREFIX + "name", null, "name"),
                injectionPoint("java.lang.Integer", PREFIX + "port", "8181", "port"));
        set(PREFIX + "name", "ravel");

        assertDoesNotThrow(this::startFromTheArchive);
    }

    @Test
    void the_recorded_text_comes_back_unchanged_whatever_its_characters() throws IOException {
        // A default value is free text; "no default" and "an empty default" are two different things.
        String odd = "a,b:c=d #e\\f\n\tg é€ ${h}";
        buildArchive(
                injectionPoint("java.lang.Integer", PREFIX + "odd", odd, "odd"),
                injectionPoint("java.lang.String", PREFIX + "empty", "", "empty"),
                injectionPoint("java.lang.String", PREFIX + "none", null, "none"));

        DeploymentException failure = assertThrows(DeploymentException.class, this::startFromTheArchive);

        String messages = messagesOf(failure);
        assertTrue(messages.contains("3 problems"), messages);
        assertTrue(messages.contains("Invalid @ConfigProperty for key '" + PREFIX + "odd': Cannot convert '" + odd
                + "'"), messages);
        assertTrue(messages.contains("Empty defaultValue is invalid for required config property '" + PREFIX
                + "empty' [at " + OWNER + "#empty]"), messages);
        assertTrue(messages.contains("Missing required config property '" + PREFIX + "none'"), messages);
    }

    // -- Build: the extension in the build phase, Vauban's builder and serializer -------------------

    private void buildArchive(InjectionPointInfo... injectionPoints) throws IOException {
        ConfigCdiExtension extension = new ConfigCdiExtension();
        List<VaubanSyntheticObserverBuilder<?>> observers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        ExtensionPhase.atBuildTime(() -> {
            extension.registerConfigPropertyInjectionPoints(bean(List.of(injectionPoints)), messages(errors));
            extension.synthesizeConfigPropertyBeans(vaubanObservers(observers), null);
            return null;
        });
        assertEquals(List.of(), errors);
        assertEquals(1, observers.size(), "the extension registers one start check");

        Path metaInf = Files.createDirectories(archive.resolve("META-INF"));
        try (var out = Files.newOutputStream(archive.resolve(SyntheticMetadataSerializer.METADATA_PATH))) {
            SyntheticMetadataSerializer.write(List.of(), observers, out);
        }
        Files.writeString(archive.resolve(SyntheticMetadataSerializer.BCE_PROCESSED_MARKER), "# test\n");
        Files.writeString(metaInf.resolve("vauban-beans.list"), "");
    }

    /** Starts a container on the archive, then stops it; no bean class registers the extension. */
    private void startFromTheArchive() throws IOException {
        try (var loader = new URLClassLoader(new URL[]{archive.toUri().toURL()}, getClass().getClassLoader())) {
            VaubanContainer.builder().classLoader(loader).scanClasspath().build().close();
        }
    }

    private void set(String key, String value) {
        systemProperties.add(key);
        System.setProperty(key, value);
    }

    private static String messagesOf(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    // -- Fakes: the language model of the build, and the components Vauban records -----------------

    /** {@code addObserver} answers Vauban's own builder, kept in {@code observers}; beans are ignored. */
    private static SyntheticComponents vaubanObservers(List<VaubanSyntheticObserverBuilder<?>> observers) {
        SyntheticBeanBuilder<?> beans = proxy(SyntheticBeanBuilder.class, (p, _, _) -> p);
        return proxy(SyntheticComponents.class, (_, m, a) -> switch (m.getName()) {
            case "addBean" -> beans;
            case "addObserver" -> {
                var observer = new VaubanSyntheticObserverBuilder<>((Class<?>) a[0]);
                observers.add(observer);
                yield observer;
            }
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static InjectionPointInfo injectionPoint(String type, String key, String defaultValue, String field) {
        ClassInfo typeDeclaration = proxy(ClassInfo.class, (_, m, _) -> switch (m.getName()) {
            case "name", "toString" -> type;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        ClassType classType = proxy(ClassType.class, (_, m, _) -> switch (m.getName()) {
            case "declaration" -> typeDeclaration;
            case "toString" -> type;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        ClassInfo owner = proxy(ClassInfo.class, (_, m, _) -> switch (m.getName()) {
            case "name" -> OWNER;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        DeclarationInfo declaration = proxy(FieldInfo.class, (p, m, _) -> switch (m.getName()) {
            case "name" -> field;
            case "kind" -> DeclarationInfo.Kind.FIELD;
            case "asField" -> p;
            case "declaringClass" -> owner;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        Map<String, AnnotationMember> members = new HashMap<>();
        members.put("name", stringMember(key));
        if (defaultValue != null) {
            members.put("defaultValue", stringMember(defaultValue));
        }
        AnnotationInfo qualifier = proxy(AnnotationInfo.class, (_, m, _) -> switch (m.getName()) {
            case "name" -> ConfigProperty.class.getName();
            case "members" -> members;
            default -> throw new UnsupportedOperationException(m.getName());
        });
        return proxy(InjectionPointInfo.class, (_, m, _) -> switch (m.getName()) {
            case "type" -> classType;
            case "qualifiers" -> List.of(qualifier);
            case "declaration" -> declaration;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static AnnotationMember stringMember(String value) {
        return proxy(AnnotationMember.class, (_, m, _) -> switch (m.getName()) {
            case "isString" -> true;
            case "asString" -> value;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    private static BeanInfo bean(List<InjectionPointInfo> injectionPoints) {
        return proxy(BeanInfo.class, (_, m, _) -> switch (m.getName()) {
            case "injectionPoints" -> injectionPoints;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    /** Only the errors matter: every {@code error} overload has the message as its first argument. */
    private static Messages messages(List<String> errors) {
        return proxy(Messages.class, (_, m, a) -> {
            if (m.getName().equals("error")) {
                errors.add(String.valueOf(a[0]));
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(BuildTimeStartCheckTest.class.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
