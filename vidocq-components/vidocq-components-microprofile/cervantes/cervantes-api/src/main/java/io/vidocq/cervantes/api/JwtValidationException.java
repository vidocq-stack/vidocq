package io.vidocq.cervantes.api;

/**
 * Échec de validation d'un JWT : format invalide, algorithme non supporté, signature incorrecte,
 * clé introuvable, ou claim invalide ({@code iss}/{@code aud}/{@code exp}/{@code nbf}).
 *
 * <p>Volontairement non détaillée côté appelant HTTP (le filtre d'authentification la traduit en
 * {@code 401}). Le message reste précis pour les logs serveur, jamais renvoyé au client.</p>
 */
public class JwtValidationException extends Exception {

    public JwtValidationException(String message) {
        super(message);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
