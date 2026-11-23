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

import io.vidocq.kerckhoffs.core.internal.mechanism.BasicAuthenticationMechanism;
import io.vidocq.kerckhoffs.core.internal.mechanism.FormAuthenticationMechanism;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Any;
import jakarta.security.enterprise.authentication.mechanism.http.BasicAuthenticationMechanismDefinition;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The authentication mechanisms an application declares reach CDI (Jakarta Security 4.0, section 3.8). */
class MechanismCdiTest {

    private static VaubanContainer start(Class<?>... beans) {
        var builder = VaubanContainer.builder().addBeanClass(KerckhoffsExtension.class)
                .addBeanClass(IdentityStoreStartCheck.class);
        for (Class<?> bean : beans) builder.addBeanClass(bean);
        return builder.build();
    }

    @Test
    void anExpressionRealmIsRefusedNotIgnored() {
        assertThrows(RuntimeException.class, () -> start(ExpressionRealmApplication.class));
    }

    @Test
    void anApplicationWithoutMechanismDeclaresNone() {
        try (var container = start(MemoryApplication.class)) {
            assertTrue(container.getBeanManager().getBeans(HttpAuthenticationMechanism.class).isEmpty());
        }
    }

    @Test
    void aBasicDefinitionPutsTheBasicMechanismInService() {
        try (var container = start(MemoryApplication.class, BasicApplication.class)) {
            assertInstanceOf(BasicAuthenticationMechanism.class, container.select(HttpAuthenticationMechanism.class));
        }
    }

    /**
     * A definition whose {@code qualifiers} is the declared default, written out or left to the annotation, keeps the
     * mechanism the {@code @Default} one (KERCK-20261118-01): a language model that shows the default members, as
     * Vauban does since BUG-20261117-55, gives the definition that member.
     */
    @Test
    void aBasicDefinitionThatWritesTheDefaultQualifiersStaysTheDefaultMechanism() {
        try (var container = start(MemoryApplication.class, ExplicitDefaultQualifierApplication.class)) {
            assertInstanceOf(BasicAuthenticationMechanism.class, container.select(HttpAuthenticationMechanism.class));
        }
    }

    @Test
    void eachRepeatedBasicDefinitionIsAMechanismOfItsOwnQualifiers() {
        try (var container = start(MemoryApplication.class, QualifiedMechanismsApplication.class)) {
            var beans = container.getBeanManager();

            assertEquals(1, beans.getBeans(HttpAuthenticationMechanism.class, SecondRealm.Literal.INSTANCE).size());
            assertEquals(1, beans.getBeans(HttpAuthenticationMechanism.class,
                    BasicAuthenticationMechanismDefinition.BasicAuthenticationMechanism.Literal.INSTANCE).size());
            assertEquals(2, beans.getBeans(HttpAuthenticationMechanism.class, Any.Literal.INSTANCE).size());
        }
    }

    @Test
    void anExpressionForTheForwardFlagIsRefusedNotIgnored() {
        assertThrows(RuntimeException.class, () -> start(ExpressionFormApplication.class));
    }

    @Test
    void aFormDefinitionPutsTheFormMechanismInService() {
        try (var container = start(MemoryApplication.class, FormApplication.class)) {
            var mechanism = assertInstanceOf(FormAuthenticationMechanism.class,
                    container.select(HttpAuthenticationMechanism.class));

            assertEquals("FORM mechanism, login page /login-servlet", mechanism.toString());
        }
    }

    /** A custom FORM definition is never ignored (BUG-20261123-61): its login page is the application's. */
    @Test
    void aCustomFormDefinitionPutsTheCustomFormMechanismInService() {
        try (var container = start(MemoryApplication.class, CustomFormApplication.class)) {
            var mechanism = assertInstanceOf(FormAuthenticationMechanism.class,
                    container.select(HttpAuthenticationMechanism.class));

            assertEquals("custom FORM mechanism, login page /login.jsf", mechanism.toString());
        }
    }
}
