package io.vidocq.mpserver.ext.servlet.chappe.security;

import java.util.Optional;

/** {@link SecurityProvider} par défaut : refuse toute authentification. */
public final class AnonymousSecurityProvider implements SecurityProvider {

    @Override
    public Optional<AuthenticatedUser> authenticate(String username, String password) {
        return Optional.empty();
    }
}
