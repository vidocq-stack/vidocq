package fr.vidocq.vidocq.ext.rest.cassini.internal;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scanne le {@link BeanManager} CDI pour produire la liste des
 * {@link ResourceMethod} adressables.
 *
 * <p>M1 : support des annotations JAX-RS standard de verbe HTTP ({@link GET},
 * {@link POST}, {@link PUT}, {@link DELETE}, {@link HEAD}, {@link OPTIONS},
 * {@link PATCH}) et des méta-annotations {@link HttpMethod} pour les verbes
 * custom. Pas d'URI templates (voir M2a), pas de sub-resource locators.</p>
 */
public final class ResourceScanner {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private static final Class<?>[] BUILTIN_VERBS = {
            GET.class, POST.class, PUT.class, DELETE.class,
            HEAD.class, OPTIONS.class, PATCH.class
    };

    private ResourceScanner() {}

    public static List<ResourceMethod> discover(BeanManager beanManager) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, ANY)) {
            classes.add(bean.getBeanClass());
        }
        return discover(classes.toArray(Class<?>[]::new));
    }

    public static List<ResourceMethod> discover(Class<?>... classes) {
        List<ResourceMethod> out = new ArrayList<>();
        for (Class<?> cls : classes) {
            Path root = cls.getAnnotation(Path.class);
            if (root == null) continue;
            String basePath = normalize(root.value());
            Set<String> classProduces = produces(cls.getAnnotation(Produces.class));
            Set<String> classConsumes = consumes(cls.getAnnotation(Consumes.class));

            for (Method m : cls.getDeclaredMethods()) {
                String verb = resolveHttpMethod(m);
                Path sub = m.getAnnotation(Path.class);
                // Sub-resource locator §3.4.1 : @Path sur méthode SANS verbe HTTP
                // → la méthode retourne une instance dont on scanne les routes
                //   en préfixant par le path courant + @Path(method).
                if (verb == null) {
                    if (sub == null) continue;
                    Class<?> returnCls = m.getReturnType();
                    if (returnCls == void.class || returnCls == null) continue;
                    String locatorPath = combine(basePath, normalize(sub.value()));
                    // Héritage @Produces/@Consumes : locator méthode > classe.
                    Set<String> locatorProduces = produces(m.getAnnotation(Produces.class));
                    Set<String> locatorConsumes = consumes(m.getAnnotation(Consumes.class));
                    Set<String> inhProd = locatorProduces.isEmpty() ? classProduces : locatorProduces;
                    Set<String> inhCons = locatorConsumes.isEmpty() ? classConsumes : locatorConsumes;
                    m.setAccessible(true);
                    scanLocatorType(returnCls, locatorPath, inhProd, inhCons, m, out);
                    continue;
                }
                String full = (sub == null) ? basePath : combine(basePath, normalize(sub.value()));
                Set<String> methodProduces = produces(m.getAnnotation(Produces.class));
                Set<String> methodConsumes = consumes(m.getAnnotation(Consumes.class));
                Set<String> effProd = methodProduces.isEmpty() ? classProduces : methodProduces;
                Set<String> effCons = methodConsumes.isEmpty() ? classConsumes : methodConsumes;
                m.setAccessible(true);
                out.add(new ResourceMethod(cls, m, verb, UriTemplate.compile(full), effProd, effCons));
            }
        }
        return out;
    }

    private static String resolveHttpMethod(Method m) {
        for (Class<?> verb : BUILTIN_VERBS) {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> annType = (Class<? extends Annotation>) verb;
            if (m.isAnnotationPresent(annType)) {
                return verb.getAnnotation(HttpMethod.class).value();
            }
        }
        for (Annotation a : m.getAnnotations()) {
            HttpMethod meta = a.annotationType().getAnnotation(HttpMethod.class);
            if (meta != null) return meta.value();
        }
        return null;
    }

    private static void scanLocatorType(Class<?> cls, String basePath,
                                        Set<String> inheritedProduces, Set<String> inheritedConsumes,
                                        Method locator, List<ResourceMethod> out) {
        if (cls == Object.class || cls == null) return;
        Set<String> clsProduces = produces(cls.getAnnotation(Produces.class));
        if (clsProduces.isEmpty()) clsProduces = inheritedProduces;
        Set<String> clsConsumes = consumes(cls.getAnnotation(Consumes.class));
        if (clsConsumes.isEmpty()) clsConsumes = inheritedConsumes;
        Class<?> rootBean = locator.getDeclaringClass();
        for (Method m : cls.getDeclaredMethods()) {
            String verb = resolveHttpMethod(m);
            if (verb == null) continue;
            Path sub = m.getAnnotation(Path.class);
            String full = (sub == null) ? basePath : combine(basePath, normalize(sub.value()));
            Set<String> mp = produces(m.getAnnotation(Produces.class));
            Set<String> mc = consumes(m.getAnnotation(Consumes.class));
            Set<String> effP = mp.isEmpty() ? clsProduces : mp;
            Set<String> effC = mc.isEmpty() ? clsConsumes : mc;
            m.setAccessible(true);
            out.add(new ResourceMethod(cls, m, verb, UriTemplate.compile(full), effP, effC,
                    rootBean, locator));
        }
    }

    private static Set<String> produces(Produces ann) {
        if (ann == null || ann.value().length == 0) return Set.of();
        return new LinkedHashSet<>(List.of(ann.value()));
    }

    private static Set<String> consumes(Consumes ann) {
        if (ann == null || ann.value().length == 0) return Set.of();
        return new LinkedHashSet<>(List.of(ann.value()));
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String combine(String base, String sub) {
        if ("/".equals(sub)) return base;
        if ("/".equals(base)) return sub;
        return base + sub;
    }
}
