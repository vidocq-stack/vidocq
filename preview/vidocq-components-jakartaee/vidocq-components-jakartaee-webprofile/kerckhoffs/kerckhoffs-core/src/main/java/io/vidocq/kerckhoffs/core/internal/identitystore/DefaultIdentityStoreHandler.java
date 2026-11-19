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

import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.CredentialValidationResult.Status;
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.IdentityStore.ValidationType;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The default {@link IdentityStoreHandler} (Jakarta Security 4.0, section 3.2.4): several identity
 * stores act as one.
 *
 * <p>The handler fails closed. A caller gets in only through a {@code VALID} answer of a store that
 * declared the {@code VALIDATE} type; every other outcome is {@code INVALID} (a store refused the
 * credential) or {@code NOT_VALIDATED} (no store had an opinion). The stores are read again at each
 * call, so the handler follows an {@code Instance<IdentityStore>} that changes.
 */
public final class DefaultIdentityStoreHandler implements IdentityStoreHandler {

    private static final Comparator<IdentityStore> BY_PRIORITY = Comparator.comparingInt(IdentityStore::priority);

    private final Iterable<? extends IdentityStore> stores;

    /**
     * @param stores the identity stores available to the application, read at each validation
     */
    public DefaultIdentityStoreHandler(Iterable<? extends IdentityStore> stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
    }

    @Override
    public CredentialValidationResult validate(Credential credential) {
        Objects.requireNonNull(credential, "credential");

        List<IdentityStore> available = new ArrayList<>();
        stores.forEach(available::add);
        available.sort(BY_PRIORITY);

        Validation validation = firstValid(available, credential);
        CredentialValidationResult valid = validation.result();
        if (valid == null) {
            return validation.refused()
                    ? CredentialValidationResult.INVALID_RESULT
                    : CredentialValidationResult.NOT_VALIDATED_RESULT;
        }

        Set<String> groups = new HashSet<>();
        if (validation.store().validationTypes().contains(ValidationType.PROVIDE_GROUPS)) {
            groups.addAll(valid.getCallerGroups());
        }
        for (IdentityStore store : available) {
            if (store.validationTypes().equals(Set.of(ValidationType.PROVIDE_GROUPS))) {
                groups.addAll(store.getCallerGroups(valid));
            }
        }
        return new CredentialValidationResult(valid.getIdentityStoreId(), valid.getCallerPrincipal(),
                valid.getCallerDn(), valid.getCallerUniqueId(), groups);
    }

    /**
     * The outcome of the validating phase.
     *
     * @param store   the store that answered {@code VALID}, or {@code null}
     * @param result  its answer, or {@code null} when no store said {@code VALID}
     * @param refused whether a store answered {@code INVALID} before the end of the phase
     */
    private record Validation(IdentityStore store, CredentialValidationResult result, boolean refused) {
    }

    /** Calls the stores that validate, in order, until one answers {@code VALID}. */
    private static Validation firstValid(List<IdentityStore> available, Credential credential) {
        boolean refused = false;
        for (IdentityStore store : available) {
            if (!store.validationTypes().contains(ValidationType.VALIDATE)) {
                continue;
            }
            CredentialValidationResult result = store.validate(credential);
            if (result.getStatus() == Status.VALID) {
                return new Validation(store, result, refused);
            }
            refused |= result.getStatus() == Status.INVALID;
        }
        return new Validation(null, null, refused);
    }
}
