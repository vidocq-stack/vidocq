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
import jakarta.enterprise.inject.build.compatible.spi.SyntheticComponents;
import jakarta.enterprise.lang.model.AnnotationInfo;
import jakarta.enterprise.lang.model.declarations.ClassInfo;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.inject.Singleton;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.FormAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;

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

    /** A definition and the class that declares it. */
    record Declared(String origin, AnnotationInfo definition) {
    }

    private final List<Declared> inMemoryDefinitions = new ArrayList<>();
    private final List<Declared> basicDefinitions = new ArrayList<>();
    private final List<Declared> formDefinitions = new ArrayList<>();

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = InMemoryIdentityStoreDefinition.class)
    public void collectInMemoryDefinition(ClassInfo declaring) {
        inMemoryDefinitions.add(new Declared(declaring.name(), declaring.annotation(InMemoryIdentityStoreDefinition.class)));
    }

    @Enhancement(types = Object.class, withSubtypes = true, withAnnotations = BasicAuthenticationMechanismDefinition.class)
    public void collectBasicDefinition(ClassInfo declaring) {
        basicDefinitions.add(new Declared(declaring.name(), declaring.annotation(BasicAuthenticationMechanismDefinition.class)));
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
                    .withParam("origin", declared.origin())
                    .withParam("definition", declared.definition())
                    .createWith(InMemoryIdentityStoreCreator.class);
        }
        for (Declared declared : basicDefinitions) {
            components.addBean(HttpAuthenticationMechanism.class)
                    .type(HttpAuthenticationMechanism.class)
                    .scope(Singleton.class)
                    .withParam("origin", declared.origin())
                    .withParam("realmName", literal(declared, text(declared.definition(), "realmName", "")))
                    .createWith(BasicMechanismCreator.class);
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
                    .withParam("origin", declared.origin())
                    .withParam("loginPage", literal(declared, text(login, "loginPage", "/login")))
                    .withParam("errorPage", literal(declared, text(login, "errorPage", "/login-error")))
                    .withParam("forwardToLogin",
                            login == null || !login.hasMember("useForwardToLogin") || login.member("useForwardToLogin").asBoolean())
                    .createWith(FormMechanismCreator.class);
        }
        components.addBean(IdentityStoreHandler.class)
                .type(IdentityStoreHandler.class)
                .scope(Singleton.class)
                .createWith(IdentityStoreHandlerCreator.class);
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

    /** A text attribute of {@code annotation}, or {@code fallback} when the annotation or the attribute is absent. */
    private static String text(AnnotationInfo annotation, String name, String fallback) {
        return annotation != null && annotation.hasMember(name) ? annotation.member(name).asString() : fallback;
    }
}
