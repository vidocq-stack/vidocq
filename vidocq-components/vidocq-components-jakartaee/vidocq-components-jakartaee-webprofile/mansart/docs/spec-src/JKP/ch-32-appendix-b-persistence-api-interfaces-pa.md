# Appendix B: Persistence API Interfaces (part 4/5)

import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.MapAttribute;
import jakarta.persistence.metamodel.PluralAttribute;

import java.util.List;

/**
 * Declares operations common to {@link EntityGraph} and {@link Subgraph}.
 *
 * @see EntityGraph
 * @see Subgraph
 *
 * @since 3.2
 */
public interface Graph<T> {

 /**
 * Get an existing attribute node for the attribute with the given
 * name, or add a new attribute node if there is no existing node.
 *
 * @param attributeName name of the attribute
 * @return the attribute node
 * @throws IllegalArgumentException if the attribute is not an
 * attribute of this entity.
 * @throws IllegalStateException if the EntityGraph has been
 * statically defined
 *
 * @since 3.2
 */
 <Y> AttributeNode<Y> addAttributeNode(String attributeName);

 /**
 * Get an existing attribute node for the given attribute, or add
 * a new attribute node if there is no existing node.
 *
 * @param attribute attribute
 * @return the attribute node
 * @throws IllegalStateException if the EntityGraph has been
 * statically defined
 *
 * @since 3.2
 */
 <Y> AttributeNode<Y> addAttributeNode(Attribute<? super T, Y> attribute);

 /**
 * Determine if there is an existing attribute node for the attribute
 * with the given name.
 *
 * @param attributeName name of the attribute
 * @return true if there is an existing attribute node
 * @throws IllegalArgumentException if the attribute is not an
 * attribute of this entity.
 *
 * @since 3.2
 */
 boolean hasAttributeNode(String attributeName);

 /**
 * Determine if there is an existing attribute node for the given
 * attribute.
 *
 * @param attribute attribute
 * @return true if there is an existing attribute node
 *
 * @since 3.2
 */
 boolean hasAttributeNode(Attribute<? super T, ?> attribute);

 /**
 * Get an existing attribute node for the attribute with the given
 * name.
 *
 * @param attributeName name of the attribute
 * @return the attribute node
 * @throws IllegalArgumentException if the attribute is not an
 * attribute of this entity.
 * @throws java.util.NoSuchElementException if there is no existing
 * node for the attribute
 *
 * @since 3.2
 */
 <Y> AttributeNode<Y> getAttributeNode(String attributeName);

 /**
 * Get an existing attribute node for the given attribute.
 *
 * @param attribute attribute
 * @return the attribute node
 * @throws java.util.NoSuchElementException if there is no existing
 * node for the attribute
 *
 * @since 3.2
 */
 <Y> AttributeNode<Y> getAttributeNode(Attribute<? super T, Y> attribute);

 /**
 * Remove an attribute node from the entity graph.
 * When this graph is interpreted as a load graph, this operation
 * suppresses inclusion of an attribute mapped for eager fetching.
 * The effect of this call may be overridden by subsequent
 * invocations of {@link #addAttributeNode} or {@link #addSubgraph}.
 * If there is no existing node for the given attribute name, this
 * operation has no effect.
 *
 * @param attributeName name of the attribute
 *
 * @since 3.2
 */
 void removeAttributeNode(String attributeName);

 /**
 * Remove an attribute node from the entity graph.
 * When this graph is interpreted as a load graph, this operation
 * suppresses inclusion of an attribute mapped for eager fetching.
 * The effect of this call may be overridden by subsequent
 * invocations of {@link #addAttributeNode} or {@link #addSubgraph}.
 * If there is no existing node for the given attribute, this
 * operation has no effect.
 *
 * @param attribute attribute
 *
 * @since 3.2
 */
 void removeAttributeNode(Attribute<? super T, ?> attribute);

 /**
 * Remove all attribute nodes of the given attribute types.
 * When this graph is interpreted as a load graph, this operation
 * suppresses inclusion of attributes mapped for eager fetching.
 * The effect of this call may be overridden by subsequent
 * invocations of {@link #addAttributeNode} or {@link #addSubgraph}.
 *
 * @since 3.2
 */
 void removeAttributeNodes(Attribute.PersistentAttributeType nodeTypes);

 /**
 * Add one or more attribute nodes to the entity graph.
 * If there is already an existing node for one of the given
 * attribute names, that particular argument is ignored and
 * has no effect.
 *
 * @param attributeName name of the attribute 
 * @throws IllegalArgumentException if the attribute is not an 
 * attribute of this managed type.
 * @throws IllegalStateException if the EntityGraph has been 
 * statically defined
 */
 void addAttributeNodes(String... attributeName);

 /**
 * Add one or more attribute nodes to the entity graph.
 * If there is already an existing node for one of the given
 * attributes, that particular argument is ignored and has no
 * effect.
 *
 * @param attribute attribute
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 */
 void addAttributeNodes(Attribute<? super T, ?>... attribute);

 /**
 * Add a node to the graph that corresponds to a managed
 * type. This allows for construction of multi-node entity graphs
 * that include related managed types.
 *
 * @param attribute attribute
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type
 * @throws IllegalStateException if the EntityGraph has been 
 * statically defined
 */
 <X> Subgraph<X> addSubgraph(Attribute<? super T, X> attribute);

 /**
 * Add a node to the graph that corresponds to a managed
 * type with inheritance. This allows for multiple subclass
 * subgraphs to be defined for this node of the entity
 * graph. Subclass subgraphs will automatically include the
 * specified attributes of superclass subgraphs.
 *
 * @param attribute attribute
 * @param type entity subclass
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute's target
 * type is not a managed type
 * @throws IllegalStateException if the EntityGraph has been
 * statically defined
 *
 * @since 3.2
 */
 <Y> Subgraph<Y> addTreatedSubgraph(Attribute<? super T, ? super Y> attribute, Class<Y> type);

 /**
 * Add a node to the graph that corresponds to a managed type
 * with inheritance. This allows for multiple subclass
 * subgraphs to be defined for this node of the entity graph.
 * Subclass subgraphs will automatically include the specified
 * attributes of superclass subgraphs
 *
 * @param attribute attribute
 * @param type entity subclass
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been 
 * statically defined
 * @deprecated use {@link #addTreatedSubgraph(Attribute, Class)}
 */
 @Deprecated(since = "3.2", forRemoval = true)
 <X> Subgraph<? extends X> addSubgraph(Attribute<? super T, X> attribute, Class<? extends X> type);

 /**
 * Add a node to the graph that corresponds to a managed type.
 * This allows for construction of multi-node entity graphs
 * that include related managed types.
 *
 * @param attributeName name of the attribute 
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute is not an 
 * attribute of this managed type.
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been 
 * statically defined
 */
 <X> Subgraph<X> addSubgraph(String attributeName);

 /**
 * Add a node to the graph that corresponds to a managed
 * type with inheritance. This allows for multiple subclass
 * subgraphs to be defined for this node of the entity
 * graph. Subclass subgraphs will automatically include the
 * specified attributes of superclass subgraphs
 *
 * @param attributeName name of the attribute 
 * @param type entity subclass
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute is not 
 * an attribute of this managed type.
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been 
 * statically defined
 */
 <X> Subgraph<X> addSubgraph(String attributeName, Class<X> type);

 /**
 * Add a node to the graph that corresponds to a collection element
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attribute attribute
 * @return subgraph for the element attribute
 * @throws IllegalArgumentException if the attribute's target type
 * is not an entity
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 *
 * @since 3.2
 */
 <E> Subgraph<E> addElementSubgraph(PluralAttribute<? super T, ?, E> attribute);

 /**
 * Add a node to the graph that corresponds to a collection element
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attribute attribute
 * @return subgraph for the element attribute
 * @throws IllegalArgumentException if the attribute's target type
 * is not an entity
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 *
 * @since 3.2
 */
 <E> Subgraph<E> addTreatedElementSubgraph(PluralAttribute<? super T, ?, ? super E> attribute, Class<E> type);

 /**
 * Add a node to the graph that corresponds to a collection element
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attributeName name of the attribute
 * @return subgraph for the element attribute
 * @throws IllegalArgumentException if the attribute is not an
 * attribute of this entity.
 * @throws IllegalArgumentException if the attribute's target
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 */
 <X> Subgraph<X> addElementSubgraph(String attributeName);

 /**
 * Add a node to the graph that corresponds to a collection element
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attributeName name of the attribute
 * @param type entity subclass
 * @return subgraph for the element attribute
 * @throws IllegalArgumentException if the attribute is not an
 * attribute of this entity.
 * @throws IllegalArgumentException if the attribute's target
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 */
 <X> Subgraph<X> addElementSubgraph(String attributeName, Class<X> type);

 /**
 * Add a node to the graph that corresponds to a map key
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attribute attribute
 * @return subgraph for the key attribute
 * @throws IllegalArgumentException if the attribute's target
 * type is not a managed type entity
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 */
 <K> Subgraph<K> addMapKeySubgraph(MapAttribute<? super T, K, ?> attribute);

 /**
 * Add a node to the graph that corresponds to a map key
 * that is a managed type with inheritance. This allows for
 * construction of multi-node entity graphs that include related
 * managed types. Subclass subgraphs will automatically include
 * the specified attributes of superclass subgraphs
 *
 * @param attribute attribute
 * @param type entity subclass
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute's target
 * type is not a managed type entity
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 */
 <K> Subgraph<K> addTreatedMapKeySubgraph(MapAttribute<? super T, ? super K, ?> attribute, Class<K> type);

 /**
 * Add a node to the graph that corresponds to a map key
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attribute attribute
 * @return subgraph for the key attribute
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type entity
 * @throws IllegalStateException if this EntityGraph has been 
 * statically defined
 * @deprecated use {@link #addMapKeySubgraph(MapAttribute)}
 */
 @Deprecated(since = "3.2", forRemoval = true)
 <X> Subgraph<X> addKeySubgraph(Attribute<? super T, X> attribute);

 /**
 * Add a node to the graph that corresponds to a map key
 * that is a managed type with inheritance. This allows for
 * construction of multi-node entity graphs that include related
 * managed types. Subclass subgraphs will automatically include
 * the specified attributes of superclass subgraphs
 *
 * @param attribute attribute
 * @param type entity subclass
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type entity
 * @throws IllegalStateException if this EntityGraph has been 
 * statically defined
 * @deprecated use {@link #addTreatedMapKeySubgraph(MapAttribute, Class)}
 */
 @Deprecated(since = "3.2", forRemoval = true)
 <X> Subgraph<? extends X> addKeySubgraph(Attribute<? super T, X> attribute, Class<? extends X> type);

 /**
 * Add a node to the graph that corresponds to a map key
 * that is a managed type. This allows for construction of
 * multi-node entity graphs that include related managed types.
 *
 * @param attributeName name of the attribute
 * @return subgraph for the key attribute
 * @throws IllegalArgumentException if the attribute is not an 
 * attribute of this entity.
 * @throws IllegalArgumentException if the attribute's target 
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been
 * statically defined
 */
 <X> Subgraph<X> addKeySubgraph(String attributeName);

 /**
 * Add a node to the graph that corresponds to a map key
 * that is a managed type with inheritance. This allows for
 * construction of multi-node entity graphs that include related
 * managed types. Subclass subgraphs will include the specified
 * attributes of superclass subgraphs
 *
 * @param attributeName name of the attribute
 * @param type entity subclass
 * @return subgraph for the attribute
 * @throws IllegalArgumentException if the attribute is not an 
 * attribute of this entity.
 * @throws IllegalArgumentException if the attribute's target
 * type is not a managed type
 * @throws IllegalStateException if this EntityGraph has been 
 * statically defined
 */
 <X> Subgraph<X> addKeySubgraph(String attributeName, Class<X> type);

 /**
 * Return the attribute nodes corresponding to the attributes of
 * this managed type that are included in the graph.
 * @return list of attribute nodes included in the graph or an
 * empty list if none have been defined
 */
 List<AttributeNode<?>> getAttributeNodes();

}

B.13. EntityGraph

/**
 * This type represents the root of an entity graph that will be
 * used as a template to define the attribute nodes and boundaries
 * of a graph of entities and entity relationships. The root must
 * be an entity type.
 * <p>
 * The methods to add subgraphs implicitly create the corresponding
 * attribute nodes as well; such attribute nodes should not be
 * redundantly specified.
 *
 * @param <T> The type of the root entity.
 *
 * @see AttributeNode
 * @see Subgraph
 * @see NamedEntityGraph
 *
 * @see EntityManager#createEntityGraph(Class)
 * @see EntityManager#createEntityGraph(String)
 * @see EntityManager#getEntityGraph(String)
 * @see EntityManagerFactory#addNamedEntityGraph(String, EntityGraph)
 * @see EntityManager#find(EntityGraph, Object, FindOption...)
 *
 * @since 2.1
 */
public interface EntityGraph<T> extends Graph<T> {

 /**
 * Return the name of a named {@code EntityGraph} (an entity
 * graph defined by means of the {@link NamedEntityGraph}
 * annotation, XML descriptor element, or added by means of the
 * {@link EntityManagerFactory#addNamedEntityGraph} method).
 * Returns null if the {@code EntityGraph} is not a named
 * {@code EntityGraph}.
 */
 String getName();

 /**
 * Add additional attributes to this entity graph that
 * correspond to attributes of subclasses of the entity type of
 * this {@code EntityGraph}. Subclass subgraphs automatically
 * include the specified attributes of superclass subgraphs.
 *
 * @param type entity subclass
 * @return subgraph for the subclass
 * @throws IllegalArgumentException if the type is not an entity type
 * @throws IllegalStateException if the EntityGraph has been
 * statically defined
 */
 <S extends T> Subgraph<S> addTreatedSubgraph(Class<S> type);

 /**
 * Add additional attributes to this entity graph that
 * correspond to attributes of subclasses of the entity type of
 * this {@code EntityGraph}. Subclass subgraphs automatically
 * include the specified attributes of superclass subgraphs.
 *
 * @param type entity subclass
 * @return subgraph for the subclass
 * @throws IllegalArgumentException if the type is not an entity type
 * @throws IllegalStateException if the EntityGraph has been 
 * statically defined
 * @deprecated use {@link #addTreatedSubgraph(Class)}
 */
 @Deprecated(since = "3.2", forRemoval = true)
 <T> Subgraph<? extends T> addSubclassSubgraph(Class<? extends T> type);

}

B.14. Subgraph

/**
 * This type represents a subgraph for an attribute node that
 * corresponds to a managed type. Using this class, an entity
 * subgraph can be embedded within an {@link EntityGraph}.
 *
 * @param <T> The type of the attribute.
 *
 * @see EntityGraph
 * @see AttributeNode
 * @see NamedSubgraph
 *
 * @since 2.1
 */
public interface Subgraph<T> extends Graph<T> {

 /**
 * Return the type for which this subgraph was defined.
 * @return managed type referenced by the subgraph
 */
 Class<T> getClassType();

}

B.15. AttributeNode

import java.util.Map;

/**
 * Represents an attribute node of an entity graph.
 *
 * @param <T> The type of the attribute.
 *
 * @see EntityGraph
 * @see Subgraph
 * @see NamedAttributeNode
 *
 * @since 2.1
 */
public interface AttributeNode<T> {

 /**
 * Return the name of the attribute corresponding to the
 * attribute node.
 * @return name of the attribute
 */
 String getAttributeName();

 /**
 * Return a map of subgraphs associated with this attribute
 * node.
 * @return a {@link Map} of subgraphs associated with this
 * attribute node or an empty {@code Map} if none have been
 * defined
 */
 Map<Class, Subgraph> getSubgraphs();

 /**
 * Return a map of subgraphs associated with this attribute
 * node's map key.
 * @return a {@link Map} of subgraphs associated with this
 * attribute node's map key or an empty {@code Map} if none
 * have been defined
 */
 Map<Class, Subgraph> getKeySubgraphs();
}

B.16. SchemaManager

import java.util.Map;

/**
 * Allows programmatic {@linkplain #create schema creation},
 * {@linkplain #validate schema validation},
 * {@linkplain #truncate data cleanup}, and
 * {@linkplain #drop schema cleanup} for entities belonging
 * to a certain persistence unit.
 * 
 * <p>Properties are inherited from the {@link EntityManagerFactory},
 * that is, they may be specified via {@code persistence.xml} or
 * {@link Persistence#createEntityManagerFactory(String, Map)}.
 *
 * @see EntityManagerFactory#getSchemaManager()
 *
 * @since 3.2
 */
public interface SchemaManager {
 /**
 * Create database objects mapped by entities belonging to the
 * persistence unit.
 *
 * <p>If a DDL operation fails, the behavior is undefined.
 * A provider may throw an exception, or it may ignore the problem
 * and continue.
 *
 * @param createSchemas if {@code true}, attempt to create schemas,
 * otherwise, assume the schemas already exist
 */
 void create(boolean createSchemas);

 /**
 * Drop database objects mapped by entities belonging to the
 * persistence unit, undoing the effects of the
 * {@linkplain #create(boolean) previous creation}.
 *
 * <p>If a DDL operation fails, the behavior is undefined.
 * A provider may throw an exception, or it may ignore the problem
 * and continue.
 *
 * @param dropSchemas if {@code true}, drop schemas,
 * otherwise, leave them be
 */
 void drop(boolean dropSchemas);

 /**
 * Validate that the database objects mapped by entities belonging
 * to the persistence unit have the expected definitions.
 *
 * <p>The persistence provider is not required to perform
 * any specific validation, so the semantics of this operation are
 * entirely provider-specific.
 *
 * @throws SchemaValidationException if a database object is missing or
 * does not have the expected definition
 */
 void validate() throws SchemaValidationException;

 /**
 * Truncate the database tables mapped by entities belonging to
 * the persistence unit, and then re-import initial data from any
 * configured SQL scripts for data loading.
 *
 * <p>If a SQL operation fails, the behavior is undefined.
 * A provider may throw an exception, or it may ignore the problem
 * and continue.
 */
 void truncate();
}

B.17. Persistence

package jakarta.persistence;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolver;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.persistence.spi.LoadState;

/**
 * Bootstrap class used to obtain an {@link EntityManagerFactory}
 * in Java SE environments. It may also be used to cause schema
 * generation to occur.
 * 
 * <p>The {@code Persistence} class is available in a Jakarta EE
 * container environment as well; however, support for the Java SE
 * bootstrapping APIs is not required in container environments.
 * 
 * <p>The {@code Persistence} class is used to obtain a {@link
 * PersistenceUtil PersistenceUtil} instance in both Jakarta EE
 * and Java SE environments.
 *
 * @since 1.0
 */
public class Persistence {

 /**
 * Default constructor.
 * @deprecated This class is not intended to be extended nor instantiated,
 * it is going to be marked {@code final} when this constructor becomes hidden.
 */
 @Deprecated(since = "3.2", forRemoval = true)
 public Persistence() {
 //kept for backward compatibility with pre-3.2 versions
 }

 /**
 * Create and return an {@link EntityManagerFactory} for the named
 * persistence unit.
 * 
 * @param persistenceUnitName the name of the persistence unit
 * @return the factory that creates {@link EntityManager}s configured
 * according to the specified persistence unit
 */
 public static EntityManagerFactory createEntityManagerFactory(String persistenceUnitName) {
 return createEntityManagerFactory(persistenceUnitName, null);
 }

 /**
 * Create and return an {@link EntityManagerFactory} for the named
 * persistence unit, using the given properties.
 * 
 * @param persistenceUnitName the name of the persistence unit
 * @param properties additional properties to use when creating the
 * factory. These properties may include properties
 * to control schema generation. The values of these
 * properties override any values that may have been
 * configured elsewhere.
 * @return the factory that creates {@link EntityManager}s configured
 * according to the specified persistence unit
 */
 public static EntityManagerFactory createEntityManagerFactory(String persistenceUnitName, Map<?,?> properties) {

 EntityManagerFactory emf = null;
 PersistenceProviderResolver resolver = PersistenceProviderResolverHolder.getPersistenceProviderResolver();

 List<PersistenceProvider> providers = resolver.getPersistenceProviders();

 for (PersistenceProvider provider : providers) {
 emf = provider.createEntityManagerFactory(persistenceUnitName, properties);
 if (emf != null) {
 break;
 }
 }
 if (emf == null) {
 throw new PersistenceException("No Persistence provider for EntityManager named " + persistenceUnitName);
 }
 return emf;
 }

 /**
 * Create and return an {@link EntityManagerFactory} for the named
 * persistence unit, using the given properties.
 *
 * @param configuration configuration of the persistence unit
 * @return the factory that creates {@link EntityManager}s configured
 * according to the specified persistence unit
 *
 * @since 3.2
 */
 public static EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration) {

 EntityManagerFactory emf = null;
 PersistenceProviderResolver resolver = PersistenceProviderResolverHolder.getPersistenceProviderResolver();

 List<PersistenceProvider> providers = resolver.getPersistenceProviders();

 for (PersistenceProvider provider : providers) {
 emf = provider.createEntityManagerFactory(configuration);
 if (emf != null) {
 break;
 }
 }
 if (emf == null) {
 throw new PersistenceException("No Persistence provider for EntityManager named " + configuration.name());
 }
 return emf;
 }

 /**
 * Create database schemas and/or tables and/or create DDL scripts
 * as determined by the supplied properties.
 * <p>
 * Called when schema generation is to occur as a separate phase
 * from creation of the entity manager factory.
 * <p>
 * @param persistenceUnitName the name of the persistence unit
 * @param map properties for schema generation; these may also
 * contain provider-specific properties. The values
 * of these properties override any values that may
 * have been configured elsewhere.
 * @throws PersistenceException if insufficient or inconsistent
 * configuration information is provided or if schema
 * generation otherwise fails.
 *
 * @since 2.1
 */
 public static void generateSchema(String persistenceUnitName, Map<?,?> map) {
 PersistenceProviderResolver resolver = PersistenceProviderResolverHolder.getPersistenceProviderResolver();
 List<PersistenceProvider> providers = resolver.getPersistenceProviders();
 
 for (PersistenceProvider provider : providers) {
 if (provider.generateSchema(persistenceUnitName, map)) {
 return;
 }
 }
 
 throw new PersistenceException("No Persistence provider to generate schema named " + persistenceUnitName);
 }

 /**
 * Return the {@link PersistenceUtil} instance
 * @return {@link PersistenceUtil} instance
 * @since 2.0
 */
 public static PersistenceUtil getPersistenceUtil() {
 return new PersistenceUtilImpl();
 }

 /**
 * Implementation of the {@link PersistenceUtil} interface
 * @since 2.0
 */
 private static class PersistenceUtilImpl implements PersistenceUtil {
 public boolean isLoaded(Object entity, String attributeName) {
 PersistenceProviderResolver resolver = PersistenceProviderResolverHolder.getPersistenceProviderResolver();

 List<PersistenceProvider> providers = resolver.getPersistenceProviders();

 for (PersistenceProvider provider : providers) {
 LoadState loadstate = provider.getProviderUtil().isLoadedWithoutReference(entity, attributeName);
 if(loadstate == LoadState.LOADED) {
 return true;
 } else if (loadstate == LoadState.NOT_LOADED) {
 return false;
 } // else continue
 }

 //None of the providers could determine the load state try isLoadedWithReference
 for (PersistenceProvider provider : providers) {
 LoadState loadstate = provider.getProviderUtil().isLoadedWithReference(entity, attributeName);
 if(loadstate == LoadState.LOADED) {
 return true;
 } else if (loadstate == LoadState.NOT_LOADED) {
 return false;
 } // else continue
 }

 //None of the providers could determine the load state.
 return true;
 }

 public boolean isLoaded(Object entity) {
 PersistenceProviderResolver resolver = PersistenceProviderResolverHolder.getPersistenceProviderResolver();

 List<PersistenceProvider> providers = resolver.getPersistenceProviders();

 for (PersistenceProvider provider : providers) {
 LoadState loadstate = provider.getProviderUtil().isLoaded(entity);
 if(loadstate == LoadState.LOADED) {
 return true;
 } else if (loadstate == LoadState.NOT_LOADED) {
 return false;
 } // else continue
 }
 //None of the providers could determine the load state
 return true;
 }
 }

 /**
 * This final String is deprecated and should be removed and is only here for TCK backward compatibility
 * @since 1.0
 * @deprecated
 *
 * TODO: Either change TCK reference to PERSISTENCE_PROVIDER field to expect 
 * "jakarta.persistence.spi.PersistenceProvider" or remove PERSISTENCE_PROVIDER field and also update TCK signature 
 * tests. 
 */
 @Deprecated(since = "3.2", forRemoval = true)
 public static final String PERSISTENCE_PROVIDER = "jakarta.persistence.spi.PersistenceProvider";
 
 /**
 * This instance variable is deprecated and should be removed and is only here for TCK backward compatibility
 * @since 1.0
 * @deprecated
 */
 @Deprecated(since = "3.2", forRemoval = true)
 protected static final Set<PersistenceProvider> providers = new HashSet<PersistenceProvider>();
}

B.18. PersistenceConfiguration

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a configuration of a persistence unit, allowing programmatic
 * creation of an {@link EntityManagerFactory}. The configuration options
 * available via this API reflect the similarly-named elements of the
 * {@code persistence.xml} file.
 *
 * <p>This API may not be used to configure a container-managed persistence
 * unit. That is, the configured persistence unit should be considered a
 * Java SE persistence unit, even when this API is used within the Jakarta
 * EE environment.
 *
 * <p>If injection of the {@link EntityManagerFactory} is required, a CDI
 * {@code Producer} may be used to make the {@link EntityManagerFactory}
 * available as a CDI managed bean.
 *
 * {@snippet :
 * @Produces @ApplicationScoped @Documents
 * EntityManagerFactory configure() {
 * return new PersistenceConfiguration()
 * .name("DocumentData")
 * .nonJtaDataSource("java:global/jdbc/DocumentDatabase")
 * .managedClass(Document.class)
 * .createEntityManagerFactory();
 * }
 * }
 *
 * <p>Similarly, if injection of an {@link EntityManager} is required,
 * a CDI {@code Producer} method/{@code Disposer} method pair may be
 * used to make the {@link EntityManager} available as a CDI managed
 * bean.
 *
 * {@snippet :
 * @Produces @TransactionScoped @Documents
 * EntityManager create(@Documents EntityManagerFactory factory) {
 * return factory.createEntityManager();
 * }
 *
 * void close(@Disposes @Documents EntityManager entityManager) {
 * entityManager.close();
 * }
 * }
 *
 * <p>It is intended that persistence providers define subclasses of
 * this class with vendor-specific configuration options. A provider
 * must support configuration via any instance of this class or of any
 * subclass of this class.
 *
 * @see Persistence#createEntityManagerFactory(PersistenceConfiguration)
 *
 * @since 3.2
 */
public class PersistenceConfiguration {

 /**
 * Fully qualified name of the JDBC driver class.
 */
 public static final String JDBC_DRIVER = "jakarta.persistence.jdbc.driver";
 /**
 * JDBC URL.
 */
 public static final String JDBC_URL = "jakarta.persistence.jdbc.url";
 /**
 * Username for JDBC authentication.
 */
 public static final String JDBC_USER = "jakarta.persistence.jdbc.user";
 /**
 * Password for JDBC authentication.
 */
 public static final String JDBC_PASSWORD = "jakarta.persistence.jdbc.password";
 /**
 * An instance of {@code javax.sql.DataSource}.
 */
 public static final String JDBC_DATASOURCE = "jakarta.persistence.dataSource";

 /**
 * Default pessimistic lock timeout hint.
 */
 public static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";
 /**
 * Default query timeout hint.
 */
 public static final String QUERY_TIMEOUT = "jakarta.persistence.query.timeout";

 /**
 * The action to be performed against the database.
 *
 * <p>Standard actions are: {@code none}, {@code create},
 * {@code drop}, {@code drop-and-create}, {@code validate}.
 */
 public static final String SCHEMAGEN_DATABASE_ACTION = "jakarta.persistence.schema-generation.database.action";
 /**
 * The action to be generated as a SQL script.
 *
 * <p>The script is generated in the location specified by
 * {@value #SCHEMAGEN_CREATE_TARGET} or {@value #SCHEMAGEN_DROP_TARGET}.
 *
 * <p>Standard actions are: {@code none}, {@code create},
 * {@code drop}, {@code drop-and-create}.
 */
 public static final String SCHEMAGEN_SCRIPTS_ACTION = "jakarta.persistence.schema-generation.scripts.action";
 /**
 * The source of artifacts to be created.
 *
 * <p>Standard sources are: {@code metadata}, {@code script},
 * {@code metadata-then-script}, {@code script-then-metadata}.
 *
 * <p>The location of the script source is specified by
 * {@value #SCHEMAGEN_CREATE_SCRIPT_SOURCE}.
 */
 public static final String SCHEMAGEN_CREATE_SOURCE = "jakarta.persistence.schema-generation.create-source";
 /**
 * The source of artifacts to be dropped.
 *
 * <p>Standard sources are: {@code metadata}, {@code script},
 * {@code metadata-then-script}, {@code script-then-metadata}.
 *
 * <p>The location of the script source is specified by
 * {@value #SCHEMAGEN_DROP_SCRIPT_SOURCE}.
 */
 public static final String SCHEMAGEN_DROP_SOURCE = "jakarta.persistence.schema-generation.drop-source";
 /**
 * An application-provided SQL script to be executed when the
 * schema is created.
 */
 public static final String SCHEMAGEN_CREATE_SCRIPT_SOURCE = "jakarta.persistence.schema-generation.create-script-source";
 /**
 * An application-provided SQL script to be executed when the
 * schema is dropped.
 */
 public static final String SCHEMAGEN_DROP_SCRIPT_SOURCE = "jakarta.persistence.schema-generation.drop-script-source";
 /**
 * The provider-generated SQL script which creates the schema
 * when {@value SCHEMAGEN_SCRIPTS_ACTION} is set.
 */
 public static final String SCHEMAGEN_CREATE_TARGET = "jakarta.persistence.schema-generation.create-target";
 /**
 * The provider-generated SQL script which drops the schema
 * when {@value SCHEMAGEN_SCRIPTS_ACTION} is set.
 */
 public static final String SCHEMAGEN_DROP_TARGET = "jakarta.persistence.schema-generation.drop-target";

 /**
 * An instance of {@code jakarta.validation.ValidatorFactory},
 */
 public static final String VALIDATION_FACTORY = "jakarta.persistence.validation.factory";
 /**
 * Target groups for validation at {@link PrePersist}.
 */
 public static final String VALIDATION_GROUP_PRE_PERSIST = "jakarta.persistence.validation.group.pre-persist";
 /**
 * Target groups for validation at {@link PreUpdate}.
 */
 public static final String VALIDATION_GROUP_PRE_UPDATE = "jakarta.persistence.validation.group.pre-update";
 /**
 * Target groups for validation at {@link PreRemove}.
 */
 public static final String VALIDATION_GROUP_PRE_REMOVE = "jakarta.persistence.validation.group.pre-remove";

 /**
 * String specifying a {@link SharedCacheMode}.
 *
 * <p>Defined for use with
 * {@link Persistence#createEntityManagerFactory(String, Map)}.
 * Clients of this {@code PersistenceConfiguration} class
 * should use {@link #sharedCacheMode(SharedCacheMode)}.
 */
 public static final String CACHE_MODE = "jakarta.persistence.sharedCache.mode";

 private final String name;

 private String provider;
 private String jtaDataSource;
 private String nonJtaDataSource;

 private SharedCacheMode sharedCacheMode = SharedCacheMode.UNSPECIFIED;
 private ValidationMode validationMode = ValidationMode.AUTO;
 private PersistenceUnitTransactionType transactionType = PersistenceUnitTransactionType.RESOURCE_LOCAL;

 private final List<Class<?>> managedClasses = new ArrayList<>();
 private final List<String> mappingFileNames = new ArrayList<>();
 private final Map<String,Object> properties = new HashMap<>();

 /**
 * Create a new empty configuration. An empty configuration does not
 * typically hold enough information for successful invocation of
 * {@link #createEntityManagerFactory()}.
 *
 * @param name the name of the persistence unit, which may be used by
 * the persistence provider for logging and error reporting
 */
 public PersistenceConfiguration(String name) {
 Objects.requireNonNull(name, "Persistence unit name should not be null");
 this.name = name;
 }

 /**
 * Create a new {@link EntityManagerFactory} based on this configuration.
 * @throws PersistenceException if required configuration is missing or
 * if the factory could not be created
 */
 public EntityManagerFactory createEntityManagerFactory() {
 return Persistence.createEntityManagerFactory(this);
 }

 /**
 * The name of the persistence unit, which may be used by the persistence
 * provider for logging and error reporting.
 * @return the name of the persistence unit.
 */
 public String name() {
 return name;
 }

 /**
 * Specify the persistence provider.
 * @param providerClassName the qualified name of the persistence provider class
 * @return this configuration
 */
 public PersistenceConfiguration provider(String providerClassName) {
 this.provider = providerClassName;
 return this;
 }

 /**
 * The fully-qualified name of a concrete class implementing
 * {@link jakarta.persistence.spi.PersistenceProvider}.
 * @return the qualified name of the persistence provider class.
 */
 public String provider() {
 return provider;
 }

 /**
 * Specify the JNDI name of a JTA {@code javax.sql.DataSource}.
 * @param dataSourceJndiName the JNDI name of a JTA datasource
 * @return this configuration
 */
 public PersistenceConfiguration jtaDataSource(String dataSourceJndiName) {
 this.jtaDataSource = dataSourceJndiName;
 return this;
 }

 /**
 * The JNDI name of a JTA {@code javax.sql.DataSource}.
 * @return the configured JTA datasource, if any, or null
 */
 public String jtaDataSource() {
 return jtaDataSource;
 }

 /**
 * Specify the JNDI name of a non-JTA {@code javax.sql.DataSource}.
 * @param dataSourceJndiName the JNDI name of a non-JTA datasource
 * @return this configuration
 */
 public PersistenceConfiguration nonJtaDataSource(String dataSourceJndiName) {
 this.nonJtaDataSource = dataSourceJndiName;
 return this;
 }

 /**
 * The JNDI name of a non-JTA {@code javax.sql.DataSource}.
 * @return the configured non-JTA datasource, if any, or null
 */
 public String nonJtaDataSource() {
 return nonJtaDataSource;
 }

 /**
 * Add a managed class (an {@link Entity}, {@link Embeddable},
 * {@link MappedSuperclass}, or {@link Converter}) to the
 * configuration.
 * @param managedClass the managed class
 * @return this configuration
 */
 public PersistenceConfiguration managedClass(Class<?> managedClass) {
 managedClasses.add(managedClass);
 return this;
 }

 /**
 * The configured managed classes, that is, a list of classes
 * annotated {@link Entity}, {@link Embeddable},
 * {@link MappedSuperclass}, or {@link Converter}.
 * @return all configured managed classes
 */
 public List<Class<?>> managedClasses() {
 return managedClasses;
 }

 /**
 * Add the path of an XML mapping file loaded as a resource to
 * the configuration.
 * @param name the resource path of the mapping file
 * @return this configuration
 */
 public PersistenceConfiguration mappingFile(String name) {
 mappingFileNames.add(name);
 return this;
 }

 /**
 * The configured resource paths of XML mapping files.
 * @return all configured mapping file resource paths
 */
 public List<String> mappingFiles() {
 return mappingFileNames;
 }

 /**
 * Specify the transaction type for the persistence unit.
 * @param transactionType the transaction type
 * @return this configuration
 */
 public PersistenceConfiguration transactionType(PersistenceUnitTransactionType transactionType) {
 this.transactionType = transactionType;
 return this;
 }

 /**
 * The {@linkplain PersistenceUnitTransactionType transaction type}.
 * <ul>
 * <li>If {@link PersistenceUnitTransactionType#JTA}, a JTA data
 * source must be provided via {@link #jtaDataSource()},
 * or by the container.
 * <li>If {@link PersistenceUnitTransactionType#RESOURCE_LOCAL},
 * database connection properties may be specified via
 * {@link #properties()}, or a non-JTA datasource may be
 * provided via {@link #nonJtaDataSource()}.
 * </ul>
 * @return the transaction type
 */
 public PersistenceUnitTransactionType transactionType() {
 return transactionType;
 }

 /**
 * Specify the shared cache mode for the persistence unit.
 * @param sharedCacheMode the shared cache mode
 * @return this configuration
 */
 public PersistenceConfiguration sharedCacheMode(SharedCacheMode sharedCacheMode) {
 this.sharedCacheMode = sharedCacheMode;
 return this;
 }

 /**
 * The shared cache mode. The default behavior is unspecified
 * and {@linkplain SharedCacheMode#UNSPECIFIED provider-specific}.
 * @return the shared cache mode
 */
 public SharedCacheMode sharedCacheMode() {
 return sharedCacheMode;
 }

 /**
 * Specify the validation mode for the persistence unit.
 * @param validationMode the shared cache mode
 * @return this configuration
 */
 public PersistenceConfiguration validationMode(ValidationMode validationMode) {
 this.validationMode = validationMode;
 return this;
 }

 /**
 * The validation mode, {@link ValidationMode#AUTO} by default.
 * @return the validation mode
 */
 public ValidationMode validationMode() {
 return validationMode;
 }

 /**
 * Set a property of this persistence unit.
 * @param name the property name
 * @param value the property value
 * @return this configuration
 */
 public PersistenceConfiguration property(String name, Object value) {
 properties.put(name, value);
 return this;
 }

 /**
 * Set multiple properties of this persistence unit.
 * @param properties the properties
 * @return this configuration
 */
 public PersistenceConfiguration properties(Map<String,?> properties) {
 this.properties.putAll(properties);
 return this;
 }

 /**
 * Standard and vendor-specific property settings.
 * @return the configured properties
 */
 public Map<String, Object> properties() {
 return properties;
 }
}

B.19. PersistenceUtil

package jakarta.persistence;

/**
 * Utility interface between the application and the persistence
 * provider(s). 
 * 
 * <p>The {@code PersistenceUtil} interface instance obtained from
 * the {@link Persistence} class is used to determine the load state
 * of an entity or entity attribute regardless of which persistence
 * provider in the environment created the entity.
 *
 * @since 2.0
 */
public interface PersistenceUtil {

 /**
 * Determine the load state of a given persistent attribute.
 * @param entity entity containing the attribute
 * @param attributeName name of attribute whose load state is
 * to be determined
 * @return false if entity's state has not been loaded or if
 * the attribute state has not been loaded, else true
 */
 boolean isLoaded(Object entity, String attributeName);

 /**
 * Determine the load state of an entity.
 * This method can be used to determine the load state of an
 * entity passed as a reference. An entity is considered loaded
 * if all attributes for which {@link FetchType#EAGER} has been
 * specified have been loaded.
 * <p>The {@link #isLoaded(Object, String)} method should be
 * used to determine the load state of an attribute. Not doing
 * so might lead to unintended loading of state.
 * @param entity whose load state is to be determined
 * @return false if the entity has not been loaded, else true
 */
 boolean isLoaded(Object entity);
}

B.20. PersistenceUnitUtil

package jakarta.persistence;

import jakarta.persistence.metamodel.Attribute;

/**
 * Utility interface between the application and the persistence
 * provider managing the persistence unit.
 *
 * <p>The methods of this interface should only be invoked on
 * entity instances obtained from or managed by entity managers
 * for this persistence unit or on new entity instances.
 *
 * @since 2.0
 */
public interface PersistenceUnitUtil extends PersistenceUtil {

 /**
 * Determine the load state of a given persistent attribute
 * of an entity belonging to the persistence unit.
 * @param entity entity instance containing the attribute
 * @param attributeName name of attribute whose load state is
 * to be determined
 * @return false if entity's state has not been loaded or if 
 * the attribute state has not been loaded, else true
 */
 boolean isLoaded(Object entity, String attributeName);

 /**
 * Determine the load state of a given persistent attribute
 * of an entity belonging to the persistence unit.
 * @param entity entity instance containing the attribute
 * @param attribute attribute whose load state is to be determined
 * @return false if entity's state has not been loaded or if
 * the attribute state has not been loaded, else true
 * @since 3.2
 */
 <E> boolean isLoaded(E entity, Attribute<? super E, ?> attribute);
