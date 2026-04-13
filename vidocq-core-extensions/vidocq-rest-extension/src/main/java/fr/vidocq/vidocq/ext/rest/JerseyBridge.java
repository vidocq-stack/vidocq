package fr.vidocq.vidocq.ext.rest;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;

import java.util.Set;

/**
 * Pont entre Jersey (JAX-RS) et le container CDI Vauban.
 * <p>
 * Scanne le {@link BeanManager} pour découvrir les beans annotés
 * {@link Path @Path} et {@link Provider @Provider}, puis les enregistre
 * dans la {@link ResourceConfig} Jersey.
 * </p>
 *
 * <h3>Architecture</h3>
 * <ol>
 *   <li>{@code registerResources(Resource.from(class))} — enregistre le routing
 *       (métadonnées {@code @Path}, {@code @GET}, etc.) <b>sans</b> créer de binding HK2</li>
 *   <li>{@code register(AbstractBinder)} — enregistre des factories HK2 qui délèguent
 *       la création d'instances au {@link BeanManager} CDI</li>
 * </ol>
 * <p>Ainsi il n'y a qu'un seul binding HK2 par classe (celui de la factory CDI),
 * pas de conflit avec un binding implicite de Jersey.</p>
 */
final class JerseyBridge {

    private static final System.Logger LOG = System.getLogger(JerseyBridge.class.getName());

    private final BeanManager beanManager;

    JerseyBridge(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    @SuppressWarnings("unchecked")
    ResourceConfig configure() {
        ResourceConfig config = new ResourceConfig();

        AbstractBinder cdiBinder = new AbstractBinder() {
            @Override
            protected void configure() {
                Set<Bean<?>> allBeans = beanManager.getBeans(Object.class, Any.Literal.INSTANCE);
                for (Bean<?> bean : allBeans) {
                    Class<?> beanClass = bean.getBeanClass();

                    if (beanClass.isAnnotationPresent(Path.class)
                            || beanClass.isAnnotationPresent(Provider.class)) {
                        bindFactory(new CdiFactory<>(beanManager, (Class) beanClass))
                                .to((Class) beanClass);
                    }
                }
            }
        };

        config.register(cdiBinder);

        Set<Bean<?>> allBeans = beanManager.getBeans(Object.class, Any.Literal.INSTANCE);
        LOG.log(System.Logger.Level.INFO, "CDI beans discovered: " + allBeans.size());
        for (Bean<?> bean : allBeans) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass.isAnnotationPresent(Path.class)) {
                config.register(beanClass);
                LOG.log(System.Logger.Level.INFO, "  Registered JAX-RS resource: " + beanClass.getName());
            } else if (beanClass.isAnnotationPresent(Provider.class)) {
                config.register(beanClass);
                LOG.log(System.Logger.Level.INFO, "  Registered JAX-RS provider: " + beanClass.getName());
            }
        }

        return config;
    }

    private static final class CdiFactory<T> implements Factory<T> {

        private final BeanManager beanManager;
        private final Class<T> type;

        CdiFactory(BeanManager beanManager, Class<T> type) {
            this.beanManager = beanManager;
            this.type = type;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T provide() {
            Set<Bean<?>> beans = beanManager.getBeans(type, Any.Literal.INSTANCE);
            if (beans.isEmpty()) {
                return null;
            }
            Bean<T> bean = (Bean<T>) beanManager.resolve(beans);
            CreationalContext<T> ctx = beanManager.createCreationalContext(bean);
            return (T) beanManager.getReference(bean, type, ctx);
        }

        @Override
        public void dispose(T instance) {
            // CDI gère le cycle de vie
        }
    }
}
