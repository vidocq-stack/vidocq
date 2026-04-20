package fr.vidocq.vidocq.ext.rest.cassini.internal;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Modèle d'une méthode de ressource JAX-RS découverte côté Cassini.
 *
 * <p>M1 : path littéral, pas encore d'URI templates (voir M2a).</p>
 *
 * @param beanClass   classe CDI portant la ressource
 * @param javaMethod  méthode Java (déjà rendue accessible)
 * @param httpMethod  verbe HTTP (GET, POST, …)
 * @param path        chemin canonique (préfixe classe + suffixe méthode)
 * @param produces    media types déclarés via {@code @Produces} (peut être vide)
 */
public record ResourceMethod(
        Class<?> beanClass,
        Method javaMethod,
        String httpMethod,
        String path,
        Set<String> produces) {
}
