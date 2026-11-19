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
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.CredentialValidationResult.Status;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.IdentityStore.ValidationType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The default {@code IdentityStoreHandler}, one test per rule of Jakarta Security 4.0 section 3.2.4.
 * The negative outcomes come first: a caller is let in only by an explicit {@code VALID}.
 */
class DefaultIdentityStoreHandlerTest {

    private static final Credential CREDENTIAL = new UsernamePasswordCredential("reza", "secret1");

    /** A store with a scripted answer that records the calls it receives. */
    private static final class ScriptedStore implements IdentityStore {
        private final String name;
        private final int priority;
        private final Set<ValidationType> types;
        private final CredentialValidationResult answer;
        private final Set<String> groups;
        private final List<String> journal;

        ScriptedStore(String name, int priority, Set<ValidationType> types, CredentialValidationResult answer,
                      Set<String> groups, List<String> journal) {
            this.name = name;
            this.priority = priority;
            this.types = types;
            this.answer = answer;
            this.groups = groups;
            this.journal = journal;
        }

        @Override
        public CredentialValidationResult validate(Credential credential) {
            journal.add("validate:" + name);
            return answer;
        }

        @Override
        public Set<String> getCallerGroups(CredentialValidationResult validationResult) {
            journal.add("groups:" + name);
            return groups;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public Set<ValidationType> validationTypes() {
            return types;
        }
    }

    private static final Set<ValidationType> BOTH = EnumSet.of(ValidationType.VALIDATE, ValidationType.PROVIDE_GROUPS);
    private static final Set<ValidationType> VALIDATE_ONLY = EnumSet.of(ValidationType.VALIDATE);
    private static final Set<ValidationType> GROUPS_ONLY = EnumSet.of(ValidationType.PROVIDE_GROUPS);

    private static CredentialValidationResult valid(String storeId, String caller, Set<String> groups) {
        return new CredentialValidationResult(storeId, new CallerPrincipal(caller), "dn=" + caller, "uid-" + caller, groups);
    }

    private static CredentialValidationResult validate(List<IdentityStore> stores) {
        return new DefaultIdentityStoreHandler(stores).validate(CREDENTIAL);
    }

    @Test
    void no_store_validates_nobody() {
        assertEquals(Status.NOT_VALIDATED, validate(List.of()).getStatus());
    }

    @Test
    void stores_that_do_not_validate_leave_the_credential_not_validated() {
        var journal = new ArrayList<String>();
        var store = new ScriptedStore("a", 100, VALIDATE_ONLY, CredentialValidationResult.NOT_VALIDATED_RESULT,
                Set.of(), journal);

        assertEquals(Status.NOT_VALIDATED, validate(List.of(store)).getStatus());
    }

    @Test
    void an_invalid_answer_is_returned_when_no_store_says_valid() {
        var journal = new ArrayList<String>();
        var invalid = new ScriptedStore("a", 100, VALIDATE_ONLY, CredentialValidationResult.INVALID_RESULT,
                Set.of(), journal);
        var abstaining = new ScriptedStore("b", 200, VALIDATE_ONLY, CredentialValidationResult.NOT_VALIDATED_RESULT,
                Set.of(), journal);

        assertEquals(Status.INVALID, validate(List.of(invalid, abstaining)).getStatus());
        assertEquals(List.of("validate:a", "validate:b"), journal);
    }

    @Test
    void a_store_that_only_provides_groups_never_validates() {
        var journal = new ArrayList<String>();
        var store = new ScriptedStore("a", 100, GROUPS_ONLY, valid("a", "reza", Set.of()), Set.of("g"), journal);

        assertEquals(Status.NOT_VALIDATED, validate(List.of(store)).getStatus());
        assertEquals(List.of(), journal);
    }

    @Test
    void a_later_valid_answer_wins_over_an_earlier_invalid_one() {
        var journal = new ArrayList<String>();
        var invalid = new ScriptedStore("a", 100, VALIDATE_ONLY, CredentialValidationResult.INVALID_RESULT,
                Set.of(), journal);
        var valid = new ScriptedStore("b", 200, VALIDATE_ONLY, valid("b", "reza", Set.of()), Set.of(), journal);

        var result = validate(List.of(invalid, valid));

        assertEquals(Status.VALID, result.getStatus());
        assertEquals("reza", result.getCallerPrincipal().getName());
    }

    @Test
    void stores_are_called_by_ascending_priority_and_the_first_valid_stops_the_chain() {
        var journal = new ArrayList<String>();
        var late = new ScriptedStore("late", 300, VALIDATE_ONLY, valid("late", "late", Set.of()), Set.of(), journal);
        var first = new ScriptedStore("first", 10, VALIDATE_ONLY, valid("first", "reza", Set.of()), Set.of(), journal);
        var middle = new ScriptedStore("middle", 100, VALIDATE_ONLY, CredentialValidationResult.INVALID_RESULT,
                Set.of(), journal);

        var result = validate(List.of(late, first, middle));

        assertEquals("first", result.getIdentityStoreId());
        assertEquals(List.of("validate:first"), journal);
    }

    @Test
    void the_identity_of_the_valid_result_is_kept() {
        var store = new ScriptedStore("mem", 100, VALIDATE_ONLY, valid("mem", "reza", Set.of()), Set.of(), new ArrayList<>());

        var result = validate(List.of(store));

        assertEquals("mem", result.getIdentityStoreId());
        assertEquals("reza", result.getCallerPrincipal().getName());
        assertEquals("dn=reza", result.getCallerDn());
        assertEquals("uid-reza", result.getCallerUniqueId());
    }

    @Test
    void the_groups_of_a_store_that_does_not_provide_groups_are_dropped() {
        var store = new ScriptedStore("a", 100, VALIDATE_ONLY, valid("a", "reza", Set.of("admin")), Set.of(),
                new ArrayList<>());

        assertEquals(Set.of(), validate(List.of(store)).getCallerGroups());
    }

    @Test
    void the_groups_of_a_store_that_provides_groups_are_kept_without_asking_it_again() {
        var journal = new ArrayList<String>();
        var store = new ScriptedStore("a", 100, BOTH, valid("a", "reza", Set.of("admin")), Set.of("never"), journal);

        var result = validate(List.of(store));

        assertEquals(Set.of("admin"), result.getCallerGroups());
        assertEquals(List.of("validate:a"), journal);
    }

    @Test
    void stores_that_only_provide_groups_add_theirs_in_priority_order() {
        var journal = new ArrayList<String>();
        var validating = new ScriptedStore("v", 100, BOTH, valid("v", "reza", Set.of("user")), Set.of(), journal);
        var second = new ScriptedStore("g2", 300, GROUPS_ONLY, null, Set.of("ops"), journal);
        var first = new ScriptedStore("g1", 200, GROUPS_ONLY, null, Set.of("dev", "user"), journal);

        var result = validate(List.of(validating, second, first));

        assertEquals(Set.of("user", "dev", "ops"), result.getCallerGroups());
        assertEquals(List.of("validate:v", "groups:g1", "groups:g2"), journal);
    }

    @Test
    void a_store_that_also_provides_groups_is_not_asked_for_groups_when_it_did_not_validate() {
        var journal = new ArrayList<String>();
        var validating = new ScriptedStore("v", 100, VALIDATE_ONLY, valid("v", "reza", Set.of()), Set.of(), journal);
        var both = new ScriptedStore("b", 200, BOTH, valid("b", "other", Set.of("x")), Set.of("y"), journal);

        var result = validate(List.of(validating, both));

        assertEquals(Set.of(), result.getCallerGroups());
        assertEquals(List.of("validate:v"), journal);
    }

    @Test
    void groups_are_not_asked_when_nobody_validated() {
        var journal = new ArrayList<String>();
        var invalid = new ScriptedStore("a", 100, VALIDATE_ONLY, CredentialValidationResult.INVALID_RESULT,
                Set.of(), journal);
        var groups = new ScriptedStore("g", 200, GROUPS_ONLY, null, Set.of("dev"), journal);

        validate(List.of(invalid, groups));

        assertEquals(List.of("validate:a"), journal);
    }

    @Test
    void the_stores_are_read_at_each_call() {
        var stores = new ArrayList<IdentityStore>();
        var handler = new DefaultIdentityStoreHandler(stores);
        assertEquals(Status.NOT_VALIDATED, handler.validate(CREDENTIAL).getStatus());

        stores.add(new ScriptedStore("a", 100, VALIDATE_ONLY, valid("a", "reza", Set.of()), Set.of(), new ArrayList<>()));

        assertEquals(Status.VALID, handler.validate(CREDENTIAL).getStatus());
    }

    @Test
    void a_null_credential_is_refused() {
        var handler = new DefaultIdentityStoreHandler(List.of());

        assertThrows(NullPointerException.class, () -> handler.validate(null));
    }
}
