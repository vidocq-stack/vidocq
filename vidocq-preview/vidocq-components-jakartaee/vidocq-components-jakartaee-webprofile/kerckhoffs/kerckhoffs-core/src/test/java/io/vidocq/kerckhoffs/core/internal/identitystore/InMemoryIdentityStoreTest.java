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
package io.vidocq.kerckhoffs.core.internal.identitystore;

import jakarta.security.enterprise.CallerPrincipal;
import jakarta.security.enterprise.credential.CallerOnlyCredential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.CredentialValidationResult.Status;
import jakarta.security.enterprise.identitystore.IdentityStore.ValidationType;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition.Credentials;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The built-in in-memory identity store (Jakarta Security 4.0, section 3.4.3). Refusals come first: a
 * caller is let in only by an exact caller name and an exact password.
 */
class InMemoryIdentityStoreTest {

    /** The annotation of the TCK fixture {@code app-mem}, read the way the CDI extension reads it. */
    @InMemoryIdentityStoreDefinition({
            @Credentials(callerName = "reza", password = "secret1", groups = {"foo", "bar"}),
            @Credentials(callerName = "alex", password = "secret2", groups = {"foo", "kaz"}),
            @Credentials(callerName = "arjan", password = "secret3"),
            @Credentials(callerName = "werner", password = "secret4", groups = {"bar", "kaz"})})
    static class Application {
    }

    @InMemoryIdentityStoreDefinition(value = @Credentials(callerName = "reza", password = "secret1"),
            priority = 10, useFor = ValidationType.PROVIDE_GROUPS)
    static class Configured {
    }

    @InMemoryIdentityStoreDefinition(value = @Credentials(callerName = "reza", password = "secret1"),
            priorityExpression = "${config.priority}")
    static class WithPriorityExpression {
    }

    @InMemoryIdentityStoreDefinition(value = @Credentials(callerName = "reza", password = "secret1"),
            useForExpression = "${config.useFor}")
    static class WithUseForExpression {
    }

    @InMemoryIdentityStoreDefinition({@Credentials(callerName = "reza", password = "a"),
            @Credentials(callerName = "reza", password = "b")})
    static class Duplicated {
    }

    @InMemoryIdentityStoreDefinition(@Credentials(callerName = "", password = "a"))
    static class Nameless {
    }

    private static InMemoryIdentityStore store(Class<?> annotated) {
        return InMemoryIdentityStore.of(annotated.getAnnotation(InMemoryIdentityStoreDefinition.class));
    }

    private static CredentialValidationResult validate(String caller, String password) {
        return store(Application.class).validate(new UsernamePasswordCredential(caller, password));
    }

    // ---- refusals

    @Test
    void an_unknown_caller_is_invalid() {
        assertEquals(Status.INVALID, validate("nobody", "secret1").getStatus());
    }

    @Test
    void a_wrong_password_is_invalid() {
        assertEquals(Status.INVALID, validate("reza", "wrong").getStatus());
    }

    @Test
    void the_password_of_another_caller_is_invalid() {
        assertEquals(Status.INVALID, validate("reza", "secret2").getStatus());
    }

    @Test
    void the_password_must_match_exactly_not_by_prefix() {
        assertEquals(Status.INVALID, validate("reza", "secret").getStatus());
        assertEquals(Status.INVALID, validate("reza", "secret11").getStatus());
        assertEquals(Status.INVALID, validate("reza", "").getStatus());
    }

    @Test
    void the_caller_name_is_case_sensitive() {
        assertEquals(Status.INVALID, validate("Reza", "secret1").getStatus());
    }

    @Test
    void a_credential_of_another_kind_is_not_validated() {
        var result = store(Application.class).validate(new CallerOnlyCredential("reza"));

        assertEquals(Status.NOT_VALIDATED, result.getStatus());
    }

    @Test
    void a_cleared_credential_is_not_accepted() {
        var credential = new UsernamePasswordCredential("reza", "secret1");
        credential.clear();

        assertEquals(Status.INVALID, store(Application.class).validate(credential).getStatus());
    }

    // ---- acceptance

    @Test
    void the_right_caller_and_password_are_valid() {
        var result = validate("reza", "secret1");

        assertEquals(Status.VALID, result.getStatus());
        assertEquals(new CallerPrincipal("reza").getName(), result.getCallerPrincipal().getName());
        assertEquals(Set.of("foo", "bar"), result.getCallerGroups());
    }

    @Test
    void every_declared_caller_is_valid_with_its_own_groups() {
        assertEquals(Set.of("foo", "kaz"), validate("alex", "secret2").getCallerGroups());
        assertEquals(Set.of(), validate("arjan", "secret3").getCallerGroups());
        assertEquals(Set.of("bar", "kaz"), validate("werner", "secret4").getCallerGroups());
    }

    @Test
    void the_groups_of_a_validated_caller_are_provided_on_request() {
        var store = store(Application.class);

        var groups = store.getCallerGroups(new CredentialValidationResult("reza"));

        assertEquals(Set.of("foo", "bar"), groups);
    }

    @Test
    void the_groups_of_an_unknown_caller_are_empty() {
        assertEquals(Set.of(), store(Application.class).getCallerGroups(new CredentialValidationResult("nobody")));
    }

    // ---- the annotation

    @Test
    void the_defaults_of_the_annotation_are_priority_90_and_both_validation_types() {
        var store = store(Application.class);

        assertEquals(90, store.priority());
        assertEquals(Set.of(ValidationType.VALIDATE, ValidationType.PROVIDE_GROUPS), store.validationTypes());
    }

    @Test
    void the_priority_and_the_validation_types_of_the_annotation_are_applied() {
        var store = store(Configured.class);

        assertEquals(10, store.priority());
        assertEquals(Set.of(ValidationType.PROVIDE_GROUPS), store.validationTypes());
    }

    @Test
    void an_expression_attribute_is_refused_rather_than_ignored() {
        assertThrows(IllegalArgumentException.class, () -> store(WithPriorityExpression.class));
        assertThrows(IllegalArgumentException.class, () -> store(WithUseForExpression.class));
    }

    @Test
    void a_caller_declared_twice_or_without_a_name_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> store(Duplicated.class));
        assertThrows(IllegalArgumentException.class, () -> store(Nameless.class));
    }

    // ---- through the handler

    @Test
    void the_handler_returns_the_caller_with_the_groups_of_the_store() {
        var handler = new DefaultIdentityStoreHandler(List.of(store(Application.class)));

        var result = handler.validate(new UsernamePasswordCredential("reza", "secret1"));

        assertEquals(Status.VALID, result.getStatus());
        assertEquals(Set.of("foo", "bar"), result.getCallerGroups());
        assertEquals(Status.INVALID, handler.validate(new UsernamePasswordCredential("reza", "nope")).getStatus());
    }
}
