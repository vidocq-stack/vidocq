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
import jakarta.security.enterprise.identitystore.IdentityStore;
import jakarta.security.enterprise.identitystore.InMemoryIdentityStoreDefinition;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The built-in in-memory identity store (Jakarta Security 4.0, section 3.4.3), configured by an
 * {@link InMemoryIdentityStoreDefinition}.
 *
 * <p>It is meant for tests, demos and debugging: the passwords are in the annotation. It fails
 * closed: an unknown caller, a wrong password and a cleared credential are all {@code INVALID}, the
 * password is compared in constant time, and a caller declared twice, a caller without a name or an
 * {@code *Expression} attribute (not supported yet) refuse the store instead of being ignored.
 */
public final class InMemoryIdentityStore implements IdentityStore {

    /** The identity of this store in the results it returns. */
    static final String STORE_ID = "InMemoryIdentityStore";

    /** What is known of one caller: the password as UTF-8 bytes, and the groups. */
    private static final class Entry {
        /** Stands for a caller the store does not know: it accepts nothing and has no group. */
        static final Entry UNKNOWN = new Entry(new byte[0], Set.of(), false);

        private final byte[] password;
        private final Set<String> groups;
        private final boolean known;

        Entry(byte[] password, Set<String> groups) {
            this(password, groups, true);
        }

        private Entry(byte[] password, Set<String> groups, boolean known) {
            this.password = password;
            this.groups = groups;
            this.known = known;
        }

        /**
         * Compares the provided password with the declared one in constant time. An unknown caller is compared
         * with the provided bytes themselves, so the work done does not tell a known caller from an unknown one.
         */
        boolean accepts(byte[] provided) {
            boolean equal = MessageDigest.isEqual(known ? password : provided, provided);
            return equal && known;
        }

        Set<String> groups() {
            return groups;
        }
    }

    private final Map<String, Entry> callers;
    private final int priority;
    private final Set<ValidationType> validationTypes;

    private InMemoryIdentityStore(Map<String, Entry> callers, int priority, Set<ValidationType> validationTypes) {
        this.callers = callers;
        this.priority = priority;
        this.validationTypes = validationTypes;
    }

    /**
     * Reads the store from its annotation.
     *
     * @throws IllegalArgumentException for a caller declared twice or without a name, and for a non-blank
     *                                  {@code priorityExpression} or {@code useForExpression}
     */
    public static InMemoryIdentityStore of(InMemoryIdentityStoreDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        refuseExpression("priorityExpression", definition.priorityExpression());
        refuseExpression("useForExpression", definition.useForExpression());

        Map<String, Entry> callers = new HashMap<>();
        for (InMemoryIdentityStoreDefinition.Credentials declared : definition.value()) {
            String name = declared.callerName();
            if (name.isBlank()) {
                throw new IllegalArgumentException("An in-memory caller needs a name");
            }
            Entry entry = new Entry(declared.password().getBytes(StandardCharsets.UTF_8), Set.copyOf(Set.of(declared.groups())));
            if (callers.putIfAbsent(name, entry) != null) {
                throw new IllegalArgumentException("The in-memory caller " + name + " is declared twice");
            }
        }
        Set<ValidationType> types = EnumSet.noneOf(ValidationType.class);
        types.addAll(Arrays.asList(definition.useFor()));
        return new InMemoryIdentityStore(callers, definition.priority(), Collections.unmodifiableSet(types));
    }

    private static void refuseExpression(String attribute, String value) {
        if (!value.isBlank()) {
            throw new IllegalArgumentException("The " + attribute + " attribute of an in-memory identity store is not "
                    + "supported yet: " + value);
        }
    }

    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (!(credential instanceof UsernamePasswordCredential usernamePassword)) {
            return CredentialValidationResult.NOT_VALIDATED_RESULT;
        }
        // A cleared credential holds no password any more: it fails the comparison below like a wrong one.
        byte[] provided = utf8(usernamePassword.getPassword().getValue());
        try {
            Entry entry = callers.getOrDefault(usernamePassword.getCaller(), Entry.UNKNOWN);
            return entry.accepts(provided)
                    ? new CredentialValidationResult(STORE_ID, new CallerPrincipal(usernamePassword.getCaller()), null,
                    null, entry.groups())
                    : CredentialValidationResult.INVALID_RESULT;
        } finally {
            Arrays.fill(provided, (byte) 0);
        }
    }

    @Override
    public Set<String> getCallerGroups(CredentialValidationResult validationResult) {
        CallerPrincipal principal = validationResult.getCallerPrincipal();
        Entry entry = principal == null ? Entry.UNKNOWN : callers.getOrDefault(principal.getName(), Entry.UNKNOWN);
        return entry.groups();
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public Set<ValidationType> validationTypes() {
        return validationTypes;
    }

    private static byte[] utf8(char[] password) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return bytes;
    }
}
