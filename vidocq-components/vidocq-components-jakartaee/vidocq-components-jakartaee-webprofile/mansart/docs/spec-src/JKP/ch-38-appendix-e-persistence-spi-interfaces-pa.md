# Appendix E: Persistence SPI Interfaces (part 1/2)

The following APIs are defined in the package jakarta.persistence.spi.

E.1. ClassTransformer

import java.security.ProtectionDomain;

/**
 * A persistence provider supplies an instance of this 
 * interface to the {@link PersistenceUnitInfo#addTransformer}
 * method. The supplied transformer instance will get 
 * called to transform entity class files when they are 
 * loaded or redefined. The transformation occurs before 
 * the class is defined by the JVM.
 *
 * @since 1.0
 */
public interface ClassTransformer {

 /**
 * Invoked when a class is being loaded or redefined.
 * The implementation of this method may transform the 
 * supplied class file and return a new replacement class 
 * file.
 *
 * @param loader the defining loader of the class to be 
 * transformed, may be null if the bootstrap loader
 * @param className the name of the class in the internal form 
 * of fully qualified class and interface names
 * @param classBeingRedefined if this is a redefine, the 
 * class being redefined, otherwise null
 * @param protectionDomain the protection domain of the 
 * class being defined or redefined
 * @param classfileBuffer the input byte buffer in class 
 * file format - must not be modified
 * @return a well-formed class file buffer (the result of 
 * the transform), or null if no transform is performed
 * @throws TransformerException if the input does
 * not represent a well-formed class file
 */
 byte[] transform(ClassLoader loader,
 String className,
 Class<?> classBeingRedefined,
 ProtectionDomain protectionDomain, 
 byte[] classfileBuffer) 
 throws TransformerException;
}

E.2. LoadState

/**
 * Load states returned by the {@link ProviderUtil} SPI methods.
 * @since 2.0
 *
 */
public enum LoadState {
 /** The state of the element is known to have been loaded. */
 LOADED,
 /** The state of the element is known not to have been loaded. */
 NOT_LOADED,
 /** The load state of the element cannot be determined. */
 UNKNOWN
}

E.3. PersistenceProvider

package jakarta.persistence.spi;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.util.Map;

/**
 * Interface implemented by the persistence provider.
 *
 * <p> It is invoked by the container in Jakarta EE environments and
 * by the {@link Persistence} class in Java SE environments to create
 * an {@link EntityManagerFactory} and/or to cause schema generation
 * to occur.
 *
 * @since 1.0
 */
public interface PersistenceProvider {

 /**
 * Called by {@link Persistence} class when an
 * {@link EntityManagerFactory} is to be created.
 *
 * @param emName the name of the persistence unit
 * @param map a Map of properties for use by the 
 * persistence provider. These properties may be used to
 * override the values of the corresponding elements in 
 * the {@code persistence.xml} file or specify values for
 * properties not specified in the {@code persistence.xml}
 * (and may be null if no properties are specified).
 * @return EntityManagerFactory for the persistence unit, 
 * or null if the provider is not the right provider
 *
 * @see Persistence#createEntityManagerFactory(String, Map)
 */
 EntityManagerFactory createEntityManagerFactory(String emName, Map<?, ?> map);

 /**
 * Called by {@link Persistence} class when an
 * {@link EntityManagerFactory} is to be created.
 *
 * @param configuration the configuration of the persistence unit
 * @return EntityManagerFactory for the persistence unit,
 * or null if the provider is not the right provider
 * @throws IllegalStateException if required configuration is missing
 *
 * @see Persistence#createEntityManagerFactory(PersistenceConfiguration)
 *
 * @since 3.2
 */
 EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration);

 /**
 * Called by the container when an {@link EntityManagerFactory}
 * is to be created. 
 *
 * @param info metadata for use by the persistence provider
 * @param map a Map of integration-level properties for use 
 * by the persistence provider (may be null if no properties
 * are specified). These properties may include properties to
 * control schema generation. If a Bean Validation provider is
 * present in the classpath, the container must pass the
 * {@code ValidatorFactory} instance in the map with the key
 * {@code "jakarta.persistence.validation.factory"}. If the
 * containing archive is a bean archive, the container must
 * pass the {@code BeanManager} instance in the map with the
 * key {@code "jakarta.persistence.bean.manager"}.
 * @return {@link EntityManagerFactory} for the persistence unit
 * specified by the metadata
 */
 EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo info, Map<?, ?> map);

 /**
 * Create database schemas and/or tables and/or create DDL
 * scripts as determined by the supplied properties.
 * <p>
 * Called by the container when schema generation is to
 * occur as a separate phase from creation of the entity
 * manager factory.
 * <p>
 * @param info metadata for use by the persistence provider
 * @param map properties for schema generation; these may
 * also include provider-specific properties
 * @throws PersistenceException if insufficient or inconsistent
 * configuration information is provided of if schema
 * generation otherwise fails
 *
 * @since 2.1
 */
 void generateSchema(PersistenceUnitInfo info, Map<?, ?> map);

 /**
 * Create database schemas and/or tables and/or create DDL
 * scripts as determined by the supplied properties.
 * <p>
 * Called by the {@link Persistence} class when schema generation
 * is to occur as a separate phase from creation of the entity
 * manager factory.
 * <p>
 * @param persistenceUnitName the name of the persistence unit
 * @param map properties for schema generation; these may
 * also contain provider-specific properties. The
 * value of these properties override any values that
 * may have been configured elsewhere.
 * @return true if schema was generated, otherwise false
 * @throws PersistenceException if insufficient or inconsistent
 * configuration information is provided or if schema
 * generation otherwise fails
 *
 * @since 2.1
 */
 boolean generateSchema(String persistenceUnitName, Map<?, ?> map);

 /**
 * Return the utility interface implemented by the persistence
 * provider.
 * @return an instance of {@link ProviderUtil}
 *
 * @since 2.0
 */
 ProviderUtil getProviderUtil();
}

E.4. PersistenceProviderResolver

import java.util.List;

/**
 * Provides a list of {@linkplain PersistenceProvider persistence
 * providers} available in the runtime environment.
 * 
 * <p> Implementations must be thread-safe.
 *
 * <p> Note that the {@link #getPersistenceProviders} method can
 * potentially be called many times: it is recommended that the
 * implementation of this method make use of caching.
 *
 * @see PersistenceProvider
 * @since 2.0
 */
public interface PersistenceProviderResolver {

 /**
 * Returns a list of the {@linkplain PersistenceProvider
 * persistence provider} implementations available in the
 * runtime environment.
 *
 * @return list of the persistence providers available 
 * in the environment
 */
 List<PersistenceProvider> getPersistenceProviders();

 /**
 * Clear cache of providers.
 */
 void clearCachedProviders();
} 

E.5. PersistenceProviderResolverHolder

package jakarta.persistence.spi;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Holds the global {@link PersistenceProviderResolver} instance.
 * If no {@code PersistenceProviderResolver} is set by the environment,
 * the default {@code PersistenceProviderResolver} is used.
 *
 * <p>Enable {@code "jakarta.persistence.spi"} logger to show diagnostic
 * information.
 * 
 * <p>Implementations must be thread-safe.
 * 
 * @since 2.0
 */
public class PersistenceProviderResolverHolder {

 private static PersistenceProviderResolver singleton = new DefaultPersistenceProviderResolver();

 /**
 * Returns the current persistence provider resolver.
 * 
 * @return the current persistence provider resolver
 */
 public static PersistenceProviderResolver getPersistenceProviderResolver() {
 return singleton;
 }

 /**
 * Defines the persistence provider resolver used.
 * 
 * @param resolver persistence provider resolver to be used.
 */
 public static void setPersistenceProviderResolver(PersistenceProviderResolver resolver) {
 if (resolver == null) {
 singleton = new DefaultPersistenceProviderResolver();
 } else {
 singleton = resolver;
 }
 }

 /**
 * Default provider resolver class to use when none is explicitly set.
 * 
 * <p>Uses service loading mechanism as described in the Jakarta Persistence
 * specification. A ServiceLoader.load() call is made with the current context
 * classloader to find the service provider files on the classpath.
 */
 private static class DefaultPersistenceProviderResolver implements PersistenceProviderResolver {

 /**
 * Cached list of available providers cached by CacheKey to ensure
 * there is not potential for provider visibility issues. 
 */
 private volatile HashMap<CacheKey, PersistenceProviderReference> providers = new HashMap<CacheKey, PersistenceProviderReference>();
 
 /**
 * Queue for reference objects referring to class loaders or persistence providers.
 */
 private static final ReferenceQueue referenceQueue = new ReferenceQueue();

 public List<PersistenceProvider> getPersistenceProviders() {
 // Before we do the real loading work, see whether we need to
 // do some cleanup: If references to class loaders or
 // persistence providers have been nulled out, remove all related
 // information from the cache.
 processQueue();
 
 ClassLoader loader = getContextClassLoader();
 CacheKey cacheKey = new CacheKey(loader);
 PersistenceProviderReference providersReferent = this.providers.get(cacheKey);
 List<PersistenceProvider> loadedProviders = null;
 
 if (providersReferent != null) {
 loadedProviders = providersReferent.get();
 }

 if (loadedProviders == null) {
 loadedProviders = new ArrayList<>();
 Iterator<PersistenceProvider> ipp = ServiceLoader.load(PersistenceProvider.class, loader).iterator();
 try {
 while (ipp.hasNext()) {
 try {
 PersistenceProvider pp = ipp.next();
 loadedProviders.add(pp);
 } catch (ServiceConfigurationError sce) {
 log(Level.FINEST, sce.toString());
 }
 }
 } catch (ServiceConfigurationError sce) {
 log(Level.FINEST, sce.toString());
 }

 // If none are found we'll log the provider names for diagnostic
 // purposes.
 if (loadedProviders.isEmpty()) {
 log(Level.WARNING, "No valid providers found.");
 }
 
 providersReferent = new PersistenceProviderReference(loadedProviders, referenceQueue, cacheKey);

 this.providers.put(cacheKey, providersReferent);
 }

 return loadedProviders;
 }
 
 /**
 * Remove garbage collected cache keys & providers.
 */
 private void processQueue() {
 CacheKeyReference ref;
 while ((ref = (CacheKeyReference) referenceQueue.poll()) != null) {
 providers.remove(ref.getCacheKey());
 } 
 }

 /**
 * Wraps {@code Thread.currentThread().getContextClassLoader()} into a
 * doPrivileged block if security manager is present
 */
 private static ClassLoader getContextClassLoader() {
 if (System.getSecurityManager() == null) {
 return Thread.currentThread().getContextClassLoader();
 } else {
 return AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
 public ClassLoader run() {
 return Thread.currentThread().getContextClassLoader();
 }
 });
 }
 }

 private static final String LOGGER_SUBSYSTEM = "jakarta.persistence.spi";

 private Logger logger;

 private void log(Level level, String message) {
 if (this.logger == null) {
 this.logger = Logger.getLogger(LOGGER_SUBSYSTEM);
 }
 this.logger.log(level, LOGGER_SUBSYSTEM + "::" + message);
 }

 /**
 * Clear all cached providers
 */
 public void clearCachedProviders() {
 this.providers.clear();
 }

 /**
 * The common interface to get a CacheKey implemented by
 * LoaderReference and PersistenceProviderReference.
 */
 private interface CacheKeyReference {
 CacheKey getCacheKey();
 } 
 
 /**
 * Key used for cached persistence providers. The key checks
 * the class loader to determine if the persistence providers
 * is a match to the requested one. The loader may be null.
 */
 private class CacheKey implements Cloneable {
 
 /* Weak Reference to ClassLoader */
 private LoaderReference loaderRef;
 
 /* Cached Hashcode */
 private int hashCodeCache;

 CacheKey(ClassLoader loader) {
 if (loader == null) {
 this.loaderRef = null;
 } else {
 loaderRef = new LoaderReference(loader, referenceQueue, this);
 }
 calculateHashCode();
 }

 ClassLoader getLoader() {
 return (loaderRef != null) ? loaderRef.get() : null;
 }

 public boolean equals(Object other) {
 if (this == other) {
 return true;
 }
 try {
 final CacheKey otherEntry = (CacheKey) other;
 // quick check to see if they are not equal
 if (hashCodeCache != otherEntry.hashCodeCache) {
 return false;
 }
 // are refs (both non-null) or (both null)?
 if (loaderRef == null) {
 return otherEntry.loaderRef == null;
 }
 ClassLoader loader = loaderRef.get();
 return (otherEntry.loaderRef != null)
 // with a null reference we can no longer find
 // out which class loader was referenced; so
 // treat it as unequal
 && (loader != null) && (loader == otherEntry.loaderRef.get());
 } catch (NullPointerException e) {
 } catch (ClassCastException e) {
 }

 return false;
 }

 public int hashCode() {
 return hashCodeCache;
 }

 private void calculateHashCode() {
 ClassLoader loader = getLoader();
 if (loader != null) {
 hashCodeCache = loader.hashCode();
 }
 }

 public Object clone() {
 try {
 CacheKey clone = (CacheKey) super.clone();
 if (loaderRef != null) {
 clone.loaderRef = new LoaderReference(loaderRef.get(), referenceQueue, clone);
 }
 return clone;
 } catch (CloneNotSupportedException e) {
 // this should never happen
 throw new InternalError();
 }
 }

 public String toString() {
 return "CacheKey[" + getLoader() + ")]";
 }
 }
 
 /**
 * References to class loaders are weak references, so that they can be
 * garbage collected when nobody else is using them. The DefaultPersistenceProviderResolver 
 * class has no reason to keep class loaders alive.
 */
 private class LoaderReference extends WeakReference<ClassLoader> 
 implements CacheKeyReference {
 private CacheKey cacheKey;

 @SuppressWarnings("unchecked")
 LoaderReference(ClassLoader referent, ReferenceQueue q, CacheKey key) {
 super(referent, q);
 cacheKey = key;
 }

 public CacheKey getCacheKey() {
 return cacheKey;
 }
 }

 /**
 * References to persistence provider are soft references so that they can be garbage
 * collected when they have no hard references.
 */
 private class PersistenceProviderReference extends SoftReference<List<PersistenceProvider>>
 implements CacheKeyReference {
 private CacheKey cacheKey;

 @SuppressWarnings("unchecked")
 PersistenceProviderReference(List<PersistenceProvider> referent, ReferenceQueue q, CacheKey key) {
 super(referent, q);
 cacheKey = key;
 }

 public CacheKey getCacheKey() {
 return cacheKey;
 }
 }
 }
}

E.6. PersistenceUnitInfo

package jakarta.persistence.spi;

import javax.sql.DataSource;
import java.util.List;
import java.util.Properties;
import java.net.URL;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.EntityManagerFactory;

/**
 * Interface implemented by the container and used by the persistence
 * provider when creating an {@link EntityManagerFactory}.
 *
 * @since 1.0
 */
public interface PersistenceUnitInfo {
 
 /**
 * Returns the name of the persistence unit. Corresponds to the
 * {@code name} attribute in the {@code persistence.xml} file.
 * @return the name of the persistence unit
 */
 String getPersistenceUnitName();

 /**
 * Returns the fully qualified name of the persistence provider
 * implementation class. Corresponds to the {@code provider} element
 * in the {@code persistence.xml} file.
 * @return the fully qualified name of the persistence provider 
 * implementation class
 */
 String getPersistenceProviderClassName();

 /**
 * Returns the fully-qualified class name of an annotation annotated
 * {@code Scope} or {@code NormalScope}. Corresponds to the {@code scope}
 * element in {@code persistence.xml}.
 * @return the fully-qualified class name of the scope annotation,
 * or null if no scope was explicitly specified
 */
 public String getScopeAnnotationName();

 /**
 * Returns the fully-qualified class names of annotations annotated
 * {@code Qualifier}. Corresponds to the {@code qualifier} element in
 * {@code persistence.xml}.
 * @return the fully-qualified class names of the qualifier annotations,
 * or an empty list if no qualifier annotations were explicitly
 * specified
 */
 public List<String> getQualifierAnnotationNames();

 /**
 * Returns the transaction type of the entity managers created by
 * the {@link EntityManagerFactory}. The transaction type corresponds
 * to the {@code transaction-type} attribute in the {@code persistence.xml}
 * file.
 * @return transaction type of the entity managers created
 * by the EntityManagerFactory
 *
 * <p>Note: This method will change its return type to {@link jakarta.persistence.PersistenceUnitTransactionType}
 * in the next major version.
 */
 PersistenceUnitTransactionType getTransactionType();

 /**
 * Returns the JTA-enabled data source to be used by the
 * persistence provider. The data source corresponds to the
 * {@code jta-data-source} element in the {@code persistence.xml}
 * file or is provided at deployment or by the container.
 * @return the JTA-enabled data source to be used by the 
 * persistence provider
 */
 DataSource getJtaDataSource();

 /**
 * Returns the non-JTA-enabled data source to be used by the
 * persistence provider for accessing data outside a JTA
 * transaction. The data source corresponds to the named
 * {@code non-jta-data-source} element in the {@code persistence.xml}
 * file or provided at deployment or by the container.
 * @return the non-JTA-enabled data source to be used by the 
 * persistence provider for accessing data outside a JTA 
 * transaction
 */
 DataSource getNonJtaDataSource();

 /**
 * Returns the list of the names of the mapping files that the
 * persistence provider must load to determine the mappings for
 * the entity classes. The mapping files must be in the standard
 * XML mapping format, be uniquely named and be resource-loadable
 * from the application classpath. Each mapping file name
 * corresponds to a {@code mapping-file} element in the
 * {@code persistence.xml} file.
 * @return the list of mapping file names that the persistence
 * provider must load to determine the mappings for the entity
 * classes 
 */
 List<String> getMappingFileNames();

 /**
 * Returns a list of URLs for the jar files or exploded jar
 * file directories that the persistence provider must examine
 * for managed classes of the persistence unit. Each URL
 * corresponds to a {@code jar-file} element in the
 * {@code persistence.xml} file. A URL will either be a
 * file: URL referring to a jar file or referring to a directory
 * that contains an exploded jar file, or some other URL from
 * which an InputStream in jar format can be obtained.
 * @return a list of URL objects referring to jar files or
 * directories 
 */
 List<URL> getJarFileUrls();

 /**
 * Returns the URL for the jar file or directory that is the
 * root of the persistence unit. (If the persistence unit is
 * rooted in the WEB-INF/classes directory, this is the URL
 * of that directory.)
 * The URL will either be a file: URL referring to a jar file 
 * or referring to a directory that contains an exploded jar
 * file, or some other URL from which an InputStream in jar
 * format can be obtained.
 * @return a URL referring to a jar file or directory
 */
 URL getPersistenceUnitRootUrl();

 /**
 * Returns the list of the names of the classes that the
 * persistence provider must add to its set of managed
 * classes. Each name corresponds to a named {@code class} element in the
 * {@code persistence.xml} file.
 * @return the list of the names of the classes that the 
 * persistence provider must add to its set of managed 
 * classes 
 */
 List<String> getManagedClassNames();

 /**
 * Returns whether classes in the root of the persistence unit
 * that have not been explicitly listed are to be included in the
 * set of managed classes. This value corresponds to the
 * {@code exclude-unlisted-classes} element in the
 * {@code persistence.xml} file.
 * @return whether classes in the root of the persistence
 * unit that have not been explicitly listed are to be
 * included in the set of managed classes
 */
 boolean excludeUnlistedClasses();

 /**
 * Returns the specification of how the provider must use
 * a second-level cache for the persistence unit.
 * The result of this method corresponds to the {@code shared-cache-mode}
 * element in the {@code persistence.xml} file.
 * @return the second-level cache mode that must be used by the
 * provider for the persistence unit
 *
 * @since 2.0
 */
 SharedCacheMode getSharedCacheMode();

 /**
 * Returns the validation mode to be used by the persistence
 * provider for the persistence unit. The validation mode
 * corresponds to the {@code validation-mode} element in the
 * {@code persistence.xml} file.
 * @return the validation mode to be used by the 
 * persistence provider for the persistence unit
 * 
 * @since 2.0
 */
 ValidationMode getValidationMode();

 /**
 * Returns a properties object. Each property corresponds to a
 * {@code property} element in the {@code persistence.xml} file
 * or to a property set by the container.
 * @return Properties object 
 */
 Properties getProperties();
 
 /**
 * Returns the schema version of the {@code persistence.xml} file.
 * @return {@code persistence.xml} schema version
 *
 * @since 2.0
 */
 String getPersistenceXMLSchemaVersion();

 /**
 * Returns ClassLoader that the provider may use to load any
 * classes, resources, or open URLs.
 * @return ClassLoader that the provider may use to load any 
 * classes, resources, or open URLs 
 */
 ClassLoader getClassLoader();

 /**
 * Add a transformer supplied by the provider that is called for
 * every new class definition or class redefinition that gets
 * loaded by the loader returned by the
 * {@link PersistenceUnitInfo#getClassLoader} method. The
 * transformer has no effect on the result returned by the
 * {@link PersistenceUnitInfo#getNewTempClassLoader} method.
 * Classes are only transformed once within the same classloading
 * scope, regardless of how many persistence units they may be 
 * a part of.
 * @param transformer provider-supplied transformer that the
 * container invokes at class-(re)definition time
 */
 void addTransformer(ClassTransformer transformer);

 /**
 * Return a new instance of a {@link ClassLoader} that the provider
 * may use to temporarily load any classes, resources, or open
 * URLs. The scope and classpath of this loader is exactly the
 * same as that of the loader returned by {@link
 * PersistenceUnitInfo#getClassLoader}. None of the classes loaded
 * by this class loader are visible to application components. The
 * provider may only use this {@code ClassLoader} within the scope
 * of the {@link PersistenceProvider#createContainerEntityManagerFactory}
 * call.
 * @return temporary {@code ClassLoader} with same visibility as
 * current loader
 */
 ClassLoader getNewTempClassLoader();
}

E.7. ProviderUtil

import jakarta.persistence.FetchType;

/**
 * Utility interface implemented by the persistence provider. This
 * interface is invoked by the {@link jakarta.persistence.PersistenceUtil}
 * implementation to determine the load status of an entity or entity
 * attribute.
 *
 * @since 2.0
 */
public interface ProviderUtil { 

 /**
 * If the provider determines that the entity has been provided by
 * itself and that the state of the specified attribute has been loaded,
 * this method returns {@link LoadState#LOADED}.
 * <p> If the provider determines that the entity has been provided
 * by itself and that either entity attributes with {@link FetchType#EAGER}
 * have not been loaded or that the state of the specified attribute has
 * not been loaded, this method returns {@link LoadState#NOT_LOADED}.
 * <p> If a provider cannot determine the load state, this method
 * returns {@link LoadState#UNKNOWN}.
 * <p> The provider's implementation of this method must not obtain a
 * reference to an attribute value, as this could trigger the loading
 * of entity state if the entity has been provided by a different
 * provider.
 * @param entity entity instance
 * @param attributeName name of attribute whose load status is
 * to be determined
 * @return load status of the attribute
 */
 LoadState isLoadedWithoutReference(Object entity, String attributeName);

 /**
 * If the provider determines that the entity has been provided by
 * itself and that the state of the specified attribute has been loaded,
 * this method returns {@link LoadState#LOADED}.
 * <p> If a provider determines that the entity has been provided by
 * itself and that either the entity attributes with {@link FetchType#EAGER}
 * have not been loaded or that the state of the specified attribute has
 * not been loaded, this method returns {@link LoadState#NOT_LOADED}.
 * <p> If the provider cannot determine the load state, this method
 * returns {@link LoadState#UNKNOWN}.
 * <p> The provider's implementation of this method is permitted to
 * obtain a reference to the attribute value. (This access is safe
 * because providers which might trigger the loading of the attribute
 * state will have already been determined by
 * {@link #isLoadedWithoutReference}.)
 *
 * @param entity entity instance
 * @param attributeName name of attribute whose load status is
 * to be determined
 * @return load status of the attribute
 */
 LoadState isLoadedWithReference(Object entity, String attributeName);

 /**
 * If the provider determines that the entity has been provided by
 * itself and that the state of all attributes for which
 * {@link FetchType#EAGER} has been specified have been loaded, this
 * method returns {@link LoadState#LOADED}.
 * <p> If the provider determines that the entity has been provided
 * by itself and that not all attributes with {@link FetchType#EAGER}
 * have been loaded, this method returns {@link LoadState#NOT_LOADED}.
 * <p> If the provider cannot determine if the entity has been
 * provided by itself, this method returns {@link LoadState#UNKNOWN}.
 * <p> The provider's implementation of this method must not obtain
 * a reference to any attribute value, as this could trigger the
 * loading of entity state if the entity has been provided by a
 * different provider.
 * @param entity whose loaded status is to be determined
 * @return load status of the entity
 */
 LoadState isLoaded(Object entity);
}

1. An entity instance is a local object inaccessible to remote processes. If instances of an entity are to be passed by value as detached objects (e.g., via a remote interface), the entity class must be serializable.

2. The term "persistence provider runtime" refers to the runtime environment of the persistence implementation. In a JakartaEE environment, this might be the Jakarta EE container itself, or a third-party persistence provider implementation integrated with the container.

3. These annotations must not be applied to the setter methods.

4. Portable applications should not expect the order of a list to be maintained across persistence contexts unless the OrderColumn or OrderBy annotation is used and modifications to the list observe the specified ordering.

5. A persistence provider is permitted—​but not required—​to accept the combinations @Basic @ElementCollection and @Embedded @ElementCollection.

6. Specifically, if getX is the name of the getter method and setX is the name of the setter method, where X is any string, the name of the persistent property is obtained by calling java.beans.Introspector.decapitalize(X).

7. Lazy fetching is a hint to the persistence provider and can be specified by means of the Basic, OneToOne, OneToMany, ManyToOne, ManyToMany, and ElementCollection annotations and their XML equivalents. See Chapter 11.

8. The use of XML as an alternative and the interaction between Java language annotations and XML elements in defining default and explicit access types is described in Chapter 12.

9. An Access annotation of a field or property getter is considered a "mapping annotation" for the purposes of this section. Therefore, an attribute-level Access annotation may not be used to selectively override the access type of an attribute of an entity class with a defaulted access type.

10. Composite primary keys often arise when mapping a legacy database with primary keys comprising multiple columns.

11. In general, however, approximate numeric types (e.g., floating point types) should never be used in primary keys.

12. This includes not changing the value of a mutable type that is primary key or an attribute of a composite primary key.

13. The implementation may, but is not required to, throw an exception. Portable applications must not rely on any such specific behavior.

14. If the application does not set a primary key attribute mapped to the same column or columns as the relationship, the value of that attribute might not be available until after the entity has been flushed to the database.

15. The primary key of the parent might be represented as an embedded id or as an id class.

16. Note that the use of PrimaryKeyJoinColumn instead of MapsId would result in the same mapping in this example. Use of MapsId is preferred for the mapping of derived identities.

17. Bulk update statements, however, are permitted to set the version of an entity. See Section 4.11.

18. Note that an instance of Calendar must be fully initialized for the SQL type it maps.

19. The use of java.util.Calendar or of java.util.Date is strongly discouraged. Newly-written programs should use the date/time types defined in the package java.time.

20. The use of date/time types defined in the package java.sql is strongly discouraged. Newly-written programs should use the date/time types defined in java.time.

21. The use of Byte arrays or of Character arrays is discouraged. Newly-written programs should use byte or char arrays instead.

22. Direct or indirect circular containment dependencies among embeddable classes are not permitted.

23. An entity cannot have a unidirectional relationship to the embeddable class of another entity (or itself).

24. Note that when an embeddable instance is used as a map key, these attributes represent its identity. Changes to embeddable instances used as map keys have undefined behaviour and should be avoided.

25. For associations of type java.util.Map, target type refers to the type that is the Map value.

26. If the parent is detached or new or was previously removed before the orphan was associated with it, the remove operation is not applied to the entity being orphaned.

27. When the relationship is modeled as a java.util.Map, “Entity B references a collection of Entity A” means that Entity B references a map collection in which the type of the Map value is Entity A. The map key may be a basic type, embeddable class, or an entity.

28. The superclass must not be an embeddable class or id class.

29. If a transaction-scoped persistence context is used, it is not required to be retained across transactions.

30. This includes instances of a non-entity class that extends an entity class.

31. If <delimited-identifiers> is specified and individual annotations or XML elements or attributes use escaped double quotes, the double-quotes appear in the name of the database identifier.

32. This includes, for example. modifications to persistent attributes of type char[] and byte[].

33. This might be an issue if unique constraints (such as those described for the default mappings in Section 2.12.3.1 and Section 2.12.5.1) were not applied in the definition of the object/relational mapping.

34. Note that when a new transaction is begun, the managed objects in an extended persistence context are not reloaded from the database.

35. These are instances that were persistent in the database at the start of the transaction.

36. It is unspecified as to whether instances that were not persistent in the database behave as new instances or detached instances after rollback. This may be implementation-dependent.

37. Applications may require that database isolation levels higher than read-committed be in effect. The configuration of the setting database isolation levels, however, is outside the scope of this specification.

38. Such alternative mechanisms might be standardized by a future release of this specification.

39. This includes owned relationships maintained in join tables.

40. Typically, by incrementing the version number, or by replacing the previous timestamp with a timestamp representing the current time.

41. Ideally, version verification and update happen in a single atomic operation against the datastore, for example, in a single SQL update statement.

42. Implementations are permitted to use database mechanisms other than locking to achieve the semantic effects described here, for example, multiversion concurrency control mechanisms.

43. This is achieved by using a lock with LockModeType.PESSIMISTIC_WRITE or LockModeType.PESSIMISTIC_FORCE_INCREMENT as described in Section 3.5.4.

44. For example, a persistence provider may use an underlying database platform’s SELECT FOR UPDATE statements to implement pessimistic locking if that construct provides appropriate semantics, or the provider may use an isolation level of repeatable read.

45. The lock mode type NONE may be specified as a method argument and also provides a default value for annotations.

46. Databases concurrency control mechanisms that provide comparable semantics, e.g., multiversion concurrency control, can be used by the provider.

47. The persistence provider is not required to flush the entity to the database immediately.

48. CDI is enabled by default in Jakarta EE. See the Jakarta EE specification [6].

49. The persistence provider may support CDI injection into entity listeners in other environments in which the BeanManager is available.

50. For example, if a transaction commit occurs as a result of the normal termination of a session bean business method with transaction attribute RequiresNew, the PostPersist and PostRemove callbacks are executed in the naming context, the transaction context, and the security context of that component.

51. Note that this caution applies also to the actions of objects that might be injected into an entity listener

52. Excluded listeners may be reintroduced on an entity class by listing them explicitly in the EntityListeners annotation or XML entity-listeners element.

53. If a method overrides an inherited callback method but specifies a different lifecycle event or is not a lifecycle callback method, the overridden method will not be invoked.

54. We plan to provide a facility for more complex attribute conversions in a future release of this specification.

55. CDI is enabled by default in Jakarta EE. See the Jakarta EE specification [6].

56. The persistence provider may support CDI injection into attribute converters in other environments in which the BeanManager is available.

57. A lock mode is specified for a query by means of the setLockMode method or by specifying the lock mode in the NamedQuery annotation.

58. Note that the setLockMode method may be called more than once (with different values) on a Query or TypedQuery object.

59. Note that locking will not occur for data passed to aggregate functions. Further, queries involving aggregates with pessimistic locking may not be supported on all database platforms.

60. Support for joins is currently limited to single-valued relationships that are mapped directly—i.e., not via join tables.

61. Note that REF_CURSOR parameters are used by some databases to return result sets from stored procedures.

62. As in SQL, the INTERSECT and INTERSECT ALL operations have higher precedence than UNION, UNION ALL, EXCEPT, and EXCEPT ALL.

63. This chapter uses the convention that reserved identifiers appear in upper case in the examples and BNF for the language.

64. BIT_LENGTH, CHAR_LENGTH, CHARACTER_LENGTH, POSITION, and UNKNOWN are not currently used: they are reserved for future use.

65. A range variable never designates an embeddable class abstract schema type.

66. Note that use of VALUE is optional, as an identification variable referring to an association of type java.util.Map is of the abstract schema type of the map value. (See Section 4.4.2.)

67. Support for right outer joins and full outer joins is under consideration for inclusion in a future version of this specification.

68. The implementation is not expected to perform such query operations involving such fields in memory rather than in the database.

69. Note that queries that contain subqueries on both sides of a comparison operation will not be portable across all databases.

70. Note that use of a collection-valued input parameter might prevent precompilation of the query.

71. Refer to [2] for a more precise characterization of these rules.

72. The use of the reserved word OF is optional in this expression.

73. Subqueries are restricted to the WHERE and HAVING clauses in this release. Support for subqueries in the FROM clause will be considered in a later release of this specification.

74. Note that expressions involving aggregate operators must not be used in the WHERE clause.

75. Note that not all databases support the use of a trim character other than the space character; use of this argument may result in queries that are not portable.

76. Note that not all databases support the use of the third argument to LOCATE; use of this argument may result in queries that are not portable.

77. Note that not all databases support the use of SQL case expressions. The use of case expressions may result in queries that are not portable to such databases.

78. For a general or simple CASE expression, the operands are the scalar expressions in the THEN and ELSE clauses.

79. Note that the keyword OBJECT is not required. It is preferred that it be omitted for new queries.

80. It is legal to specify DISTINCT with MAX or MIN, but it does not affect the result.

81. We expect that the option of different packages will be provided in a future release of this specification.

82. If the class was generated, it should also be annotated with either javax.annotation.processing.Generated or jakarta.annotation.Generated. The use of any other annotations on static metamodel classes is undefined.

83. The attributes of these metamodel objects play a role analogous to that which would be played by member literals.

84. Metamodel objects are used to specify typesafe nagivation through joins and through path expressions. These metamodel objects capture both the source and target types of the attribute through which navigation occurs, and are thus the mechanism by which typesafe navigation is achieved.

85. Attribute names serve this role for string-based queries. See Section 6.5.

86. Attribute names serve this role for string-based queries. See Section 6.5.

87. Note that the use of JTA is not required to be supported in application client containers.

88. It may also be used internally by the Jakarta EE container. See Section 7.10.

89. This may be the case when using multiple databases, since in a typical configuration a single entity manager only communicates with a single database. There is only one entity manager factory per persistence unit, however.

90. Specifically, when one of the methods of the EntityManager interface is invoked.

91. Note that this applies to a transaction-scoped persistence context of type SynchronizationType.UNSYNCHRONIZED that has not been joined to the transaction as well.

92. Entity manager instances obtained from different entity manager factories never share the same persistence context.

93. It is not required that these contracts be used when a third-party persistence provider is not used: the container might use these same APIs or its might use its own internal APIs.

94. The container may choose to pool EntityManagers: it instead of creating and closing in each case, it may acquire one from its pool and call clear() on it.

95. The root of the persistence unit is the WEB-INF/classes directory; the persistence.xml file is therefore contained in the WEB-INF/classes/META-INF directory.

96. Note that an given class may be used in more than one persistence unit.

97. Persistence providers are encouraged to support this syntax for use in Java SE environments.

98. Note that in this example a META-INF/orm.xml file is assumed not to exist.

99. Use of these Java SE bootstrapping APIs may be supported in Jakarta EE containers; however, support for such use is not required.

100. In dynamic environments (e.g., OSGi-based environments, containers based on dynamic kernels, etc.), the list of persistence providers may change.

101. If a custom PersistenceProviderResolver is needed in a JavaSE environment, it must be set before Persistence.createEntityManagerFactory is called. Note, however, that the setPersistenceProviderResolver method is not intended for general use, but rather is aimed at containers maintaining a dynamic environment.

102. Persistence units defined programmatically using the PersistenceConfiguration class do not support JNDI lookup or injection via the PersistenceContext or PersistenceUnit annotations.

103. The determining of the persistence providers that are available is discussed in Section 9.3.

104. A dependency on ultiple persistence contexts may be needed, for example, when multiple persistence units are used.

105. Multiple persistence units may be needed, for example, when mapping to multiple databases.

106. The use of map keys that contain embeddables that reference entities is not permitted.

107. Note that either the joinColumns element or the joinTable element of the AssociationOverride annotation is specified for overriding a given relationship (but never both).

108. The combination of inheritance strategies within a single entity inheritance hierarchy is not defined by this specification.

109. If it is not specified, the rules of Section 2.10 apply.

110. If the embeddable class is used as a primary key, the EmbeddedId rather than the Embedded annotation is used.

111. Use of the Embedded annotation is not required. See Section 2.10.

112. Note that the Id annotation is not used in the embeddable class.

113. If the element collection is a Map, this applies to the map value.

114. Mapping of stateful enum values is not supported.

115. Portable applications should not use the GeneratedValue annotation on other persistent fields or properties.

116. Note that SEQUENCE and IDENTITY are not portable across all databases.

117. A primary key with a type not listed is not portable.

118. In general, floating point types should never be used in primary keys.

119. If the element collection is a Map, this applies to the map value.

120. The ManyToMany annotation must not be used within an embeddable class used in an element collection.

121. The OneToMany annotation must not be used within an embeddable class used in an element collection.

122. If the parent is detached or new or was previously removed before the orphan was associated with it, the remove operation is not applied to the entity being orphaned.

123. If the parent is detached or new or was previously removed before the orphan was associated with it, the remove operation is not applied to the entity being orphaned.

124. If the primary key is a composite primary key, the precedence of ordering among the attributes within the primary key is not futher defined. To assign such a precedence within these attributes, each of the individual attributes must be specified as an orderby_item.

125. In all other cases when OrderBy is applied to an element collection, the property_or_field_name must be specified.

126. The OrderBy annotation should be used for ordering that is visible as persistent state and maintained by the application.
