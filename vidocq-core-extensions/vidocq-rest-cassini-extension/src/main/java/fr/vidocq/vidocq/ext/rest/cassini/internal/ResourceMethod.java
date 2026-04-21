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
 * @param produces    media types déclarés via {@code @Produces}
 * @param consumes    media types déclarés via {@code @Consumes}
 */
public record ResourceMethod(
        Class<?> beanClass,
        Method javaMethod,
        String httpMethod,
        UriTemplate template,
        Set<String> produces,
        Set<String> consumes,
        Class<?> rootBeanClass,
        Method locator) {

    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes, null, null);
    }

    public String path() {
        return template.template();
    }

    /** Vrai si cette route est issue d'un sub-resource locator §3.4.1. */
    public boolean isLocated() {
        return locator != null && rootBeanClass != null;
    }
}
