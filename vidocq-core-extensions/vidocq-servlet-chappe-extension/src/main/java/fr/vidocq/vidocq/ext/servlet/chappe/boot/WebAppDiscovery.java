package fr.vidocq.vidocq.ext.servlet.chappe.boot;

import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.ServletDispatcher;
import fr.vidocq.vidocq.ext.servlet.chappe.dispatcher.UrlPatternMatcher;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.servlet.Servlet;
import jakarta.servlet.annotation.WebServlet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scanne le {@link BeanManager} Vauban pour découvrir les beans CDI annotés
 * {@code @WebServlet} et les transformer en {@link ServletDispatcher.Mapping}.
 *
 * <p>Chaque url-pattern déclaré dans {@code @WebServlet.value}/{@code .urlPatterns}
 * produit un mapping. La même instance de servlet est partagée entre tous ses patterns
 * (les servlets sont traités en singleton au sens Servlet 6.1 §2.2).</p>
 */
public final class WebAppDiscovery {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    public static List<ServletDispatcher.Mapping> discover(BeanManager beanManager) {
        List<ServletDispatcher.Mapping> mappings = new ArrayList<>();
        Set<Bean<?>> beans = beanManager.getBeans(Servlet.class, ANY);
        for (Bean<?> bean : beans) {
            Class<?> cls = bean.getBeanClass();
            WebServlet ann = cls.getAnnotation(WebServlet.class);
            if (ann == null) continue;
            Servlet instance = resolveInstance(beanManager, bean);
            String servletName = ann.name().isEmpty() ? cls.getSimpleName() : ann.name();
            String[] patterns = ann.urlPatterns().length > 0 ? ann.urlPatterns() : ann.value();
            for (String pattern : patterns) {
                mappings.add(new ServletDispatcher.Mapping(
                        UrlPatternMatcher.of(pattern), instance, servletName));
            }
        }
        return mappings;
    }

    @SuppressWarnings("unchecked")
    private static Servlet resolveInstance(BeanManager bm, Bean<?> bean) {
        Bean<Object> b = (Bean<Object>) bean;
        Object ref = bm.getReference(b, Servlet.class, bm.createCreationalContext(b));
        return (Servlet) ref;
    }

    public static BeanManager lookupBeanManager() {
        return CDI.current().getBeanManager();
    }

    private WebAppDiscovery() {}
}
