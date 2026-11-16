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
package io.vidocq.kerckhoffs.cdi;

import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.enterprise.inject.build.compatible.spi.Synthesis;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanBuilder;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.AnnotationMember;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Qualifier;
import jakarta.inject.Singleton;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.FormAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes the identity stores of an application reach the {@link IdentityStoreHandler} (Jakarta
 * Security 4.0, sections 3.2.4 and 3.4.3): one {@link IdentityStore} bean per
 * {@link InMemoryIdentityStoreDefinition}, and the default handler over every store bean.
 *
 * <p>It works at build, from the classes of the deployment only; what it found travels in the
 * parameters of the synthetic beans.
 */
public class KerckhoffsExtension implements BuildCompatibleExtension {

    /** The parameter that tells two synthetic beans of equal signature apart. */
    private static final String ORIGIN = "origin";

    /** A definition and the class that declares it. */
    record Declared(String origin, AnnotationInfo definition) {
    }

    private final List<Declared> inMemoryDefinitions = new ArrayList<>();
    private final List<Declared> basicDefinitions = new ArrayList<>();
    private final List<Declared> formDefinitions = new ArrayList<>();
    private boolean applicationHandler;

    /** A handler the application provides replaces the default one (section 3.4.3): there is only ever one. */
    @Enhancement(types = IdentityStoreHandler.class, withSubtypes = true)
    public void collectApplicationHandler(ClassInfo candidate) {
        if (!candidate.isInterface() && !candidate.isAbstract()) applicationHandler = true;
    }

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = InMemoryIdentityStoreDefinition.class)
    public void collectInMemoryDefinition(ClassInfo declaring) {
        inMemoryDefinitions.add(new Declared(declaring.name(), declaring.annotation(InMemoryIdentityStoreDefinition.class)));
    }

    /** The definition is repeatable (section 3.8.1): one annotation, or several held by the container annotation. */
    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = {
            BasicAuthenticationMechanismDefinition.class, BasicAuthenticationMechanismDefinition.List.class})
    public void collectBasicDefinition(ClassInfo declaring) {
        for (AnnotationInfo annotation : declaring.annotations()) {
            String name = annotation.name().replace('$', '.');
            if (name.equals(BasicAuthenticationMechanismDefinition.class.getCanonicalName())) {
                basicDefinitions.add(new Declared(declaring.name(), annotation));
            } else if (name.equals(BasicAuthenticationMechanismDefinition.List.class.getCanonicalName())) {
                for (AnnotationMember repeated : annotation.member("value").asArray()) {
                    basicDefinitions.add(new Declared(declaring.name(), repeated.asNestedAnnotation()));
                }
            }
        }
    }

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = FormAuthenticationMechanismDefinition.class)
    public void collectFormDefinition(ClassInfo declaring) {
        formDefinitions.add(new Declared(declaring.name(), declaring.annotation(FormAuthenticationMechanismDefinition.class)));
    }

    @Synthesis
    public void synthesizeBeans(SyntheticComponents components) {
        for (Declared declared : inMemoryDefinitions) {
            components.addBean(IdentityStore.class)
                    .type(IdentityStore.class)
                    .scope(Singleton.class)
                    // The origin tells two definitions apart: the container merges beans of equal parameters.
                    .withParam(ORIGIN, declared.origin())
                    .withParam("definition", declared.definition())
                    .createWith(InMemoryIdentityStoreCreator.class);
        }
        for (Declared declared : basicDefinitions) {
            var builder = components.addBean(HttpAuthenticationMechanism.class)
                    .type(HttpAuthenticationMechanism.class)
                    .scope(Singleton.class)
                    .withParam(ORIGIN, declared.origin())
                    .withParam("realmName", literal(declared, text(declared.definition(), "realmName", "")));
            qualify(builder, declared, BasicAuthenticationMechanismDefinition.BasicAuthenticationMechanism.class);
            builder.createWith(BasicMechanismCreator.class);
        }
        for (Declared declared : formDefinitions) {
            AnnotationInfo login = declared.definition().hasMember("loginToContinue")
                    ? declared.definition().member("loginToContinue").asNestedAnnotation() : null;
            if (!text(login, "useForwardToLoginExpression", "").isEmpty()) {
                throw new DeploymentException("useForwardToLoginExpression of " + declared.origin()
                        + " is an expression: only useForwardToLogin is supported yet");
            }
            components.addBean(HttpAuthenticationMechanism.class)
                    .type(HttpAuthenticationMechanism.class)
                    .scope(Singleton.class)
                    .withParam(ORIGIN, declared.origin())
                    .withParam("loginPage", literal(declared, text(login, "loginPage", "/login")))
                    .withParam("errorPage", literal(declared, text(login, "errorPage", "/login-error")))
                    .withParam("forwardToLogin",
                            login == null || !login.hasMember("useForwardToLogin") || login.member("useForwardToLogin").asBoolean())
                    .createWith(FormMechanismCreator.class);
        }
        if (!applicationHandler) {
            components.addBean(IdentityStoreHandler.class)
                    .type(IdentityStoreHandler.class)
                    .scope(Singleton.class)
                    .createWith(IdentityStoreHandlerCreator.class);
        }
    }

    /**
     * An attribute of a mechanism definition, as a literal: written as text or as an Expression Language string
     * literal ({@code ${'name'}}); a richer expression waits for Jakarta Expression Language (PLAN.md, K3).
     */
    private static String literal(Declared declared, String value) {
        if (!value.startsWith("${")) return value;
        if (value.endsWith("}") && value.length() >= 5) {
            String inner = value.substring(2, value.length() - 1).trim();
            char quote = inner.charAt(0);
            if ((quote == '\'' || quote == '"') && inner.length() >= 2 && inner.charAt(inner.length() - 1) == quote
                    && inner.indexOf(quote, 1) == inner.length() - 1) {
                return inner.substring(1, inner.length() - 1);
            }
        }
        throw new DeploymentException("An attribute of the mechanism declared by " + declared.origin()
                + " is an expression (" + value + "): only a literal is supported yet");
    }

    /**
     * Gives the mechanism the qualifiers its definition names (section 3.8.1). A definition that names none keeps
     * {@code fallback}, and stays the {@code @Default} mechanism of the application as well.
     */
    private static void qualify(SyntheticBeanBuilder<?> builder, Declared declared,
                                Class<? extends Annotation> fallback) {
        List<AnnotationMember> named = declared.definition().hasMember("qualifiers")
                ? declared.definition().member("qualifiers").asArray() : List.of();
        if (named.isEmpty()) {
            builder.qualifier(fallback);
            builder.qualifier(Default.class);
            return;
        }
        for (AnnotationMember qualifier : named) {
            String name = qualifier.asType().asClass().declaration().name();
            Class<?> type;
            try {
                // Loaded, never initialized: nothing of the deployment runs here. Only a qualifier is kept.
                // nosemgrep: the name is the one the application wrote in its own definition, checked below
                type = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new DeploymentException("The qualifier " + name + " of the mechanism declared by "
                        + declared.origin() + " is not a class the deployment can load", e);
            }
            if (!type.isAnnotation() || !type.isAnnotationPresent(Qualifier.class)) {
                throw new DeploymentException("The qualifier " + name + " of the mechanism declared by "
                        + declared.origin() + " is not a qualifier annotation");
            }
            builder.qualifier(type.asSubclass(Annotation.class));
        }
    }

    /** A text attribute of {@code annotation}, or {@code fallback} when the annotation or the attribute is absent. */
    private static String text(AnnotationInfo annotation, String name, String fallback) {
        return annotation != null && annotation.hasMember(name) ? annotation.member(name).asString() : fallback;
    }
}
