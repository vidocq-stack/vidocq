package fr.vidocq.vidocq.ext.servlet.chappe.security;

import java.util.Optional;

/**
 * SPI de délégation pour l'authentification des utilisateurs.
 *
 * <p>L'implémentation par défaut {@link AnonymousSecurityProvider} refuse toute
 * authentification — une application qui souhaite activer BASIC/FORM remplace ce
 * provider via le ServletContext ou CDI.</p>
 */
public interface SecurityProvider {

    Optional<AuthenticatedUser> authenticate(String username, String password);
}
