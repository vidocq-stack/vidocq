package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Modèle d'une méthode de ressource JAX-RS découverte côté Cassini.
 *
 * @param beanClass   classe CDI portant la ressource
 * @param javaMethod  méthode Java (déjà rendue accessible)
 * @param httpMethod  verbe HTTP (GET, POST, …)
 * @param template    template URI combiné (classe + méthode)
 * @param produces    media types déclarés via {@code @Produces} (peut être vide)
 */
public record ResourceMethod(
        Class<?> beanClass,
        Method javaMethod,
        String httpMethod,
        UriTemplate template,
        Set<String> produces) {

    /** Chemin canonique du template (pratique pour les logs). */
    public String path() {
        return template.template();
    }
}
