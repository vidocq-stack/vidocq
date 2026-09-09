# Appendix B: Persistence API Interfaces (part 2/5)

 /**
 * Create an instance of {@link TypedQuery} for executing a
 * Jakarta Persistence query language named query.
 * The select list of the query must contain only a single
 * item, which must be assignable to the type specified by
 * the {@code resultClass} argument.
 * @param name the name of a query defined in metadata
 * @param resultClass the type of the query result
 * @return the new query instance
 * @throws IllegalArgumentException if a query has not been
 * defined with the given name or if the query string is
 * found to be invalid or if the query result is found to
 * not be assignable to the specified type
 * @since 2.0
 */
 <T> TypedQuery<T> createNamedQuery(String name, Class<T> resultClass);

 /**
 * Create an instance of {@link TypedQuery} for executing a
 * named query written in the Jakarta Persistence query
 * language or in native SQL.
 * @param reference a reference to the query defined in metadata
 * @return the new query instance
 * @throws IllegalArgumentException if a query has not been
 * defined, or if the query string is found to be
 * invalid, or if the query result is found to not be
 * assignable to the specified type
 * @see EntityManagerFactory#getNamedQueries(Class)
 * @see NamedQuery
 * @see NamedNativeQuery
 */
 <T> TypedQuery<T> createQuery(TypedQueryReference<T> reference);

 /**
 * Create an instance of {@link Query} for executing a native
 * SQL statement, e.g., for update or delete.
 *
 * <p>If the query is not an update or delete query, query
 * execution will result in each row of the SQL result being
 * returned as a result of type {@code Object[]} (or a result
 * of type {@code Object} if there is only one column in the
 * select list.) Column values are returned in the order of
 * their occurrence in the select list and default JDBC type
 * mappings are applied.
 * @param sqlString a native SQL query string
 * @return the new query instance
 */
 Query createNativeQuery(String sqlString);

 /**
 * Create an instance of {@link Query} for executing a native
 * SQL query.
 *
 * <p><em>In the next release of this API, the return type of this
 * method will change to {@code TypedQuery<T>}.</em>
 * @param sqlString a native SQL query string
 * @param resultClass the type of the query result
 * @return the new query instance
 */
 <T> Query createNativeQuery(String sqlString, Class<T> resultClass);

 /**
 * Create an instance of {@link Query} for executing
 * a native SQL query.
 * @param sqlString a native SQL query string
 * @param resultSetMapping the name of the result set mapping
 * @return the new query instance
 */
 Query createNativeQuery(String sqlString, String resultSetMapping);

 /**
 * Create an instance of {@link StoredProcedureQuery} for executing
 * a stored procedure in the database.
 * <p>Parameters must be registered before the stored procedure can
 * be executed.
 * <p>If the stored procedure returns one or more result sets, any
 * result set is returned as a list of type {@code Object[]}.
 * @param name name assigned to the stored procedure query in
 * metadata
 * @return the new stored procedure query instance
 * @throws IllegalArgumentException if no query has been defined
 * with the given name
 * @since 2.1
 */
 StoredProcedureQuery createNamedStoredProcedureQuery(String name);

 /**
 * Create an instance of {@link StoredProcedureQuery} for executing a
 * stored procedure in the database.
 * <p>Parameters must be registered before the stored procedure can
 * be executed.
 * <p>If the stored procedure returns one or more result sets, any
 * result set is returned as a list of type {@code Object[]}.
 * @param procedureName name of the stored procedure in the database
 * @return the new stored procedure query instance
 * @throws IllegalArgumentException if a stored procedure of the
 * given name does not exist (or if query execution will
 * fail)
 * @since 2.1
 */
 StoredProcedureQuery createStoredProcedureQuery(String procedureName);

 /**
 * Create an instance of {@link StoredProcedureQuery} for executing
 * a stored procedure in the database.
 * <p>Parameters must be registered before the stored procedure can
 * be executed.
 * <p>The {@code resultClass} arguments must be specified in the
 * order in which the result sets is returned by the stored procedure
 * invocation.
 * @param procedureName name of the stored procedure in the database
 * @param resultClasses classes to which the result sets
 * produced by the stored procedure are to be mapped
 * @return the new stored procedure query instance
 * @throws IllegalArgumentException if a stored procedure of the
 * given name does not exist (or if query execution will
 * fail)
 * @since 2.1
 */
 StoredProcedureQuery createStoredProcedureQuery(
 String procedureName, Class<?>... resultClasses);

 /**
 * Create an instance of {@link StoredProcedureQuery} for executing
 * a stored procedure in the database.
 * <p>Parameters must be registered before the stored procedure can
 * be executed.
 * <p>The {@code resultSetMapping} arguments must be specified in
 * the order in which the result sets is returned by the stored
 * procedure invocation.
 * @param procedureName name of the stored procedure in the
 * database
 * @param resultSetMappings the names of the result set mappings
 * to be used in mapping result sets
 * returned by the stored procedure
 * @return the new stored procedure query instance
 * @throws IllegalArgumentException if a stored procedure or
 * result set mapping of the given name does not exist
 * (or the query execution will fail)
 */
 StoredProcedureQuery createStoredProcedureQuery(
 String procedureName, String... resultSetMappings);

 /**
 * Indicate to the entity manager that a JTA transaction is
 * active and join the persistence context to it. 
 * <p>This method should be called on a JTA application 
 * managed entity manager that was created outside the scope
 * of the active transaction or on an entity manager of type
 * {@link SynchronizationType#UNSYNCHRONIZED} to associate
 * it with the current JTA transaction.
 * @throws TransactionRequiredException if there is no active
 * transaction
 */
 void joinTransaction();

 /**
 * Determine whether the entity manager is joined to the
 * current transaction. Returns false if the entity manager
 * is not joined to the current transaction or if no
 * transaction is active.
 * @return boolean
 * @since 2.1
 */
 boolean isJoinedToTransaction();

 /**
 * Return an object of the specified type to allow access to
 * a provider-specific API. If the provider implementation
 * of {@code EntityManager} does not support the given type,
 * the {@link PersistenceException} is thrown.
 * @param cls the class of the object to be returned.
 * This is usually either the underlying class
 * implementing {@code EntityManager} or an
 * interface it implements.
 * @return an instance of the specified class
 * @throws PersistenceException if the provider does not 
 * support the given type
 * @since 2.0
 */
 <T> T unwrap(Class<T> cls);

 /**
 * Return the underlying provider object for the
 * {@link EntityManager}, if available. The result of this
 * method is implementation-specific.
 * <p>The {@code unwrap} method is to be preferred for new
 * applications.
 * @return the underlying provider object
 */
 Object getDelegate();

 /**
 * Close an application-managed entity manager.
 * <p>After invocation of {@code close()}, every method of
 * the {@code EntityManager} instance and of any instance
 * of {@link Query}, {@link TypedQuery}, or
 * {@link StoredProcedureQuery} obtained from it throws
 * the {@link IllegalStateException}, except for
 * {@link #getProperties()}, {@link #getTransaction()},
 * and {@link #isOpen()} (which returns false).
 * <p>If this method is called when the entity manager is
 * joined to an active transaction, the persistence context
 * remains managed until the transaction completes.
 * @throws IllegalStateException if the entity manager is
 * container-managed
 */
 void close();

 /**
 * Determine whether the entity manager is open. 
 * @return true until the entity manager has been closed
 */
 boolean isOpen();

 /**
 * Return the resource-level {@link EntityTransaction} object.
 * The {@code EntityTransaction} instance may be used serially
 * to begin and commit multiple transactions.
 * @return EntityTransaction instance
 * @throws IllegalStateException if invoked on a JTA entity
 * manager
 */
 EntityTransaction getTransaction();

 /**
 * The {@linkplain EntityManagerFactory entity manager factory}
 * which created this entity manager.
 * @return the {@link EntityManagerFactory}
 * @throws IllegalStateException if the entity manager has 
 * been closed
 * @since 2.0
 */
 EntityManagerFactory getEntityManagerFactory();

 /**
 * Obtain an instance of {@link CriteriaBuilder} which may be
 * used to construct {@link CriteriaQuery} objects.
 * @return an instance of {@link CriteriaBuilder}
 * @throws IllegalStateException if the entity manager has
 * been closed
 * @see EntityManagerFactory#getCriteriaBuilder()
 * @since 2.0
 */
 CriteriaBuilder getCriteriaBuilder();

 /**
 * Obtain an instance of the {@link Metamodel} interface which
 * provides access to metamodel objects describing the managed
 * types belonging to the persistence unit.
 * @return an instance of {@link Metamodel}
 * @throws IllegalStateException if the entity manager has
 * been closed
 * @since 2.0
 */
 Metamodel getMetamodel();

 /**
 * Create a new mutable {@link EntityGraph}, allowing dynamic
 * definition of an entity graph.
 * @param rootType class of entity graph
 * @return entity graph
 * @since 2.1
 */
 <T> EntityGraph<T> createEntityGraph(Class<T> rootType);

 /**
 * Obtain a mutable copy of a named {@link EntityGraph}, or
 * return null if there is no entity graph with the given
 * name.
 * @param graphName name of an entity graph
 * @return entity graph
 * @since 2.1
 */
 EntityGraph<?> createEntityGraph(String graphName);

 /**
 * Obtain a named {@link EntityGraph}. The returned instance
 * of {@code EntityGraph} should be considered immutable.
 * @param graphName name of an existing entity graph
 * @return named entity graph
 * @throws IllegalArgumentException if there is no entity
 * of graph with the given name
 * @since 2.1
 */
 EntityGraph<?> getEntityGraph(String graphName);

 /**
 * Return all named {@link EntityGraph}s that are defined for
 * the given entity class type.
 * @param entityClass entity class
 * @return list of all entity graphs defined for the entity
 * @throws IllegalArgumentException if the class is not an entity
 * @since 2.1
 */
 <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> entityClass);

 /**
 * Execute the given action using the database connection underlying this
 * {@code EntityManager}. Usually, the connection is a JDBC connection, but a
 * provider might support some other native connection type, and is not required
 * to support {@code java.sql.Connection}. If this {@code EntityManager} is
 * associated with a transaction, the action is executed in the context of the
 * transaction. The given action should close any resources it creates, but should
 * not close the connection itself, nor commit or roll back the transaction. If
 * the given action throws an exception, the persistence provider must mark the
 * transaction for rollback.
 * @param action the action
 * @param <C> the connection type, usually {@code java.sql.Connection}
 * @throws PersistenceException wrapping the checked {@link Exception} thrown by
 * {@link ConnectionConsumer#accept}, if any
 * @since 3.2
 */
 <C> void runWithConnection(ConnectionConsumer<C> action);

 /**
 * Call the given function and return its result using the database connection
 * underlying this {@code EntityManager}. Usually, the connection is a JDBC
 * connection, but a provider might support some other native connection type,
 * and is not required to support {@code java.sql.Connection}. If this
 * {@code EntityManager} is associated with a transaction, the function is
 * executed in the context of the transaction. The given function should close
 * any resources it creates, but should not close the connection itself, nor
 * commit or roll back the transaction. If the given action throws an exception,
 * the persistence provider must mark the transaction for rollback.
 * @param function the function
 * @param <C> the connection type, usually {@code java.sql.Connection}
 * @param <T> the type of result returned by the function
 * @return the value returned by {@link ConnectionFunction#apply}.
 * @throws PersistenceException wrapping the checked {@link Exception} thrown by
 * {@link ConnectionFunction#apply}, if any
 * @since 3.2
 */
 <C,T> T callWithConnection(ConnectionFunction<C, T> function);

}

B.2. EntityTransaction

package jakarta.persistence;

/**
 * Interface used to control transactions on resource-local entity
 * managers. The {@link EntityManager#getTransaction} method returns
 * the {@code EntityTransaction} interface.
 *
 * @since 1.0
 */
public interface EntityTransaction {

 /**
 * Start a resource transaction. 
 * @throws IllegalStateException if {@link #isActive()} is true
 */
 void begin();

 /**
 * Commit the current resource transaction, writing any unflushed
 * changes to the database.
 * @throws IllegalStateException if {@link #isActive()} is false
 * @throws RollbackException if the commit fails
 */
 void commit();

 /**
 * Roll back the current resource transaction. 
 * @throws IllegalStateException if {@link #isActive()} is false
 * @throws PersistenceException if an unexpected error 
 * condition is encountered
 */
 void rollback();

 /**
 * Mark the current resource transaction so that the only possible
 * outcome of the transaction is for the transaction
 * to be rolled back. 
 * @throws IllegalStateException if {@link #isActive()} is false
 */
 void setRollbackOnly();

 /**
 * Determine whether the current resource transaction has been 
 * marked for rollback.
 * @return boolean indicating whether the transaction has been
 * marked for rollback
 * @throws IllegalStateException if {@link #isActive()} is false
 */
 boolean getRollbackOnly();

 /**
 * Indicate whether a resource transaction is in progress.
 * @return boolean indicating whether transaction is in progress
 * @throws PersistenceException if an unexpected error 
 * condition is encountered
 */
 boolean isActive();

 /**
 * Set the transaction timeout, in seconds. This is a hint.
 * @param timeout the timeout, in seconds, or null to indicate
 * that the database server should set the timeout
 * @since 3.2
 */
 void setTimeout(Integer timeout);

 /**
 * The transaction timeout.
 * @since 3.2
 */
 Integer getTimeout();
}

B.3. EntityManagerFactory

package jakarta.persistence;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.criteria.CriteriaBuilder;

/**
 * Interface used to interact with the persistence unit, and to
 * create new instances of {@link EntityManager}.
 *
 * <p>A persistence unit defines the set of all classes that are
 * related or grouped by the application, and which must be
 * colocated in their mapping to a single database. If two entity
 * types participate in an association, then they must belong to
 * the same persistence unit.
 *
 * <p>A persistence unit may be defined by a {@code persistence.xml}
 * file, or it may be defined at runtime via the
 * {@link PersistenceConfiguration} API.
 *
 * <p>Every persistence unit has a <em>transaction type</em>,
 * either {@link PersistenceUnitTransactionType#JTA JTA}, or
 * {@link PersistenceUnitTransactionType#RESOURCE_LOCAL RESOURCE_LOCAL}.
 * Resource-local transactions are managed programmatically via the
 * {@link EntityTransaction} interface.
 *
 * <p>An {@link EntityManagerFactory} with a lifecycle managed by
 * the application may be created using the static operations of
 * the {@link Persistence} class:
 * <ul>
 * <li>if the persistence unit is defined in {@code persistence.xml},
 * an entity manager factory may be created by calling
 * {@link Persistence#createEntityManagerFactory(String)} or
 * {@link Persistence#createEntityManagerFactory(String,Map)},
 * or
 * <li>if the persistence unit was defined using
 * {@link PersistenceConfiguration}, an entity manager factory
 * may be created by calling
 * {@link Persistence#createEntityManagerFactory(PersistenceConfiguration)}.
 * </ul>
 *
 * <p>Usually, there is exactly one {@code EntityManagerFactory} for
 * each persistence unit:
 * {@snippet :
 * // create a factory at initialization time
 * static final EntityManagerFactory entityManagerFactory =
 * Persistence.createEntityManagerFactory("orderMgt");
 * }
 *
 * <p>Alternatively, in the Jakarta EE environment, a
 * container-managed {@code EntityManagerFactory} may be obtained
 * by dependency injection, using {@link PersistenceUnit}.
 * {@snippet :
 * // inject the container-managed factory
 * @PersistenceUnit(unitName="orderMgt")
 * EntityManagerFactory entityManagerFactory;
 * }
 *
 * <p>An application-managed {@code EntityManager} may be created
 * via a call to {@link #createEntityManager()}. However, this
 * approach places complete responsibility for cleanup and exception
 * management on the client, and is thus considered error-prone. It
 * is much safer to use the methods {@link #runInTransaction} and
 * {@link #callInTransaction} to obtain {@code EntityManager}s.
 * Alternatively, in the Jakarta EE environment, a container-managed
 * {@link EntityManager} may be obtained by dependency injection,
 * using {@link PersistenceContext}, and the application need not
 * interact with the {@code EntityManagerFactory} directly.
 *
 * <p>The {@code EntityManagerFactory} provides access to certain
 * other useful APIs:
 * <ul>
 * <li>an instance of {@link Metamodel} exposing a model of the
 * managed types associated with the persistence unit may be
 * obtained by calling {@link #getMetamodel()},
 * <li>an instance of {@link SchemaManager}, allowing programmatic
 * control over schema generation and validation, may be
 * obtained by calling {@link #getSchemaManager()},
 * <li>an instance of {@link Cache}, allowing direct programmatic
 * control over the second-level cache, may be obtained by
 * calling {@link #getCache()},
 * <li>the {@link CriteriaBuilder}, used to define criteria queries,
 * may be obtained by calling {@link #getCriteriaBuilder()},
 * and
 * <li>the {@link PersistenceUnitUtil} may be obtained by calling
 * {@link #getPersistenceUnitUtil()}.
 * </ul>
 *
 * <p>When the application has finished using the entity manager
 * factory, or when the application terminates, the application
 * should {@linkplain #close} the entity manager factory. If
 * necessary, a {@link java.lang.ref.Cleaner} may be used:
 * {@snippet :
 * // factory should be destroyed before program terminates
 * Cleaner.create().register(entityManagerFactory, entityManagerFactory::close);
 * }
 * Once an {@code EntityManagerFactory} has been closed, all its
 * entity managers are considered to be in the closed state.
 *
 * @see EntityManager
 *
 * @since 1.0
 */
public interface EntityManagerFactory extends AutoCloseable {

 /**
 * Create a new application-managed {@link EntityManager}. This
 * method returns a new {@code EntityManager} instance each time
 * it is invoked. 
 * <p>The {@link EntityManager#isOpen} method will return true
 * on the returned instance.
 * @return entity manager instance
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 */
 EntityManager createEntityManager();
 
 /**
 * Create a new application-managed {@link EntityManager} with
 * the given {@link Map} specifying property settings. This
 * method returns a new {@code EntityManager} instance each time
 * it is invoked.
 * <p>The {@link EntityManager#isOpen} method will return true
 * on the returned instance.
 * @param map properties for entity manager
 * @return entity manager instance
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 */
 EntityManager createEntityManager(Map<?, ?> map);

 /**
 * Create a new JTA application-managed {@link EntityManager} with
 * the specified synchronization type. This method returns a new
 * {@code EntityManager} instance each time it is invoked.
 * <p>The {@link EntityManager#isOpen} method will return true on
 * the returned instance.
 * @param synchronizationType how and when the entity manager should
 * be synchronized with the current JTA
 * transaction
 * @return entity manager instance
 * @throws IllegalStateException if the entity manager factory has
 * been configured for resource-local entity managers or is closed
 *
 * @since 2.1
 */
 EntityManager createEntityManager(SynchronizationType synchronizationType);

 /**
 * Create a new JTA application-managed {@link EntityManager} with
 * the specified synchronization type and map of properties. This
 * method returns a new {@code EntityManager} instance each time it
 * is invoked.
 * <p>The {@link EntityManager#isOpen} method will return true on the
 * returned instance.
 * @param synchronizationType how and when the entity manager should
 * be synchronized with the current JTA
 * transaction
 * @param map properties for entity manager
 * @return entity manager instance
 * @throws IllegalStateException if the entity manager factory has
 * been configured for resource-local entity managers or is closed
 *
 * @since 2.1
 */
 EntityManager createEntityManager(SynchronizationType synchronizationType, Map<?, ?> map);

 /**
 * Return an instance of {@link CriteriaBuilder} which may be used
 * to construct {@link jakarta.persistence.criteria.CriteriaQuery}
 * objects.
 * @return an instance of {@link CriteriaBuilder}
 * @throws IllegalStateException if the entity manager factory has
 * been closed
 *
 * @see EntityManager#getCriteriaBuilder()
 *
 * @since 2.0
 */
 CriteriaBuilder getCriteriaBuilder();
 
 /**
 * Return an instance of the {@link Metamodel} interface for access
 * to the metamodel of the persistence unit.
 * @return an instance of {@link Metamodel}
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 *
 * @since 2.0
 */
 Metamodel getMetamodel();

 /**
 * Indicates whether the factory is open. Returns true until the
 * factory has been closed.
 * @return boolean indicating whether the factory is open
 */
 boolean isOpen();
 
 /**
 * Close the factory, releasing any resources that it holds.
 * After a factory instance has been closed, all methods invoked
 * on it will throw the {@link IllegalStateException}, except
 * for {@link #isOpen}, which will return false. Once an
 * {@code EntityManagerFactory} has been closed, all its
 * entity managers are considered to be in the closed state.
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 */
 void close();

 /**
 * The name of the persistence unit.
 *
 * @since 3.2
 */
 String getName();

 /**
 * Get the properties and associated values that are in effect
 * for the entity manager factory. Changing the contents of the
 * map does not change the configuration in effect.
 * @return properties
 * @throws IllegalStateException if the entity manager factory 
 * has been closed
 *
 * @since 2.0
 */
 Map<String, Object> getProperties();

 /**
 * Access the cache that is associated with the entity manager 
 * factory (the "second level cache").
 * @return an instance of {@link Cache}, or null if there is no
 * second-level cache in use
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 *
 * @since 2.0
 */
 Cache getCache();

 /**
 * Return interface providing access to utility methods for the
 * persistence unit.
 * @return an instance of {@link PersistenceUnitUtil}
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 *
 * @since 2.0
 */
 PersistenceUnitUtil getPersistenceUnitUtil();

 /**
 * The type of transaction management used by this persistence
 * unit, either resource-local transaction management, or JTA.
 *
 * @since 3.2
 */
 PersistenceUnitTransactionType getTransactionType();

 /**
 * Return interface providing access to schema management
 * operations for the persistence unit.
 * @return an instance of {@link SchemaManager}
 * @throws IllegalStateException if the entity manager factory
 * has been closed
 *
 * @since 3.2
 */
 SchemaManager getSchemaManager();

 /**
 * Define the query, typed query, or stored procedure query as
 * a named query such that future query objects can be created
 * from it using the {@link EntityManager#createNamedQuery} or
 * {@link EntityManager#createNamedStoredProcedureQuery} methods.
 * <p>Any configuration of the query object (except for actual
 * parameter binding) in effect when the named query is added
 * is retained as part of the named query definition. This
 * includes configuration information such as max results, hints,
 * flush mode, lock mode, result set mapping information, and
 * information about stored procedure parameters.
 * <p>When the query is executed, information that can be set by
 * means of the query APIs can be overridden. Information that is
 * overridden does not affect the named query as registered with
 * the entity manager factory, and thus does not affect subsequent
 * query objects created from it by calling {@code createNamedQuery}
 * or {@code createNamedStoredProcedureQuery}.
 * <p>If a named query of the same name has been previously defined,
 * either statically via metadata or via this method, that query
 * definition is replaced.
 *
 * @param name name for the query
 * @param query a {@link Query}, {@link TypedQuery},
 * or {@link StoredProcedureQuery}
 *
 * @since 2.1
 */
 void addNamedQuery(String name, Query query);

 /**
 * Return an object of the specified type to allow access to
 * a provider-specific API. If the provider implementation of
 * {@code EntityManagerFactory} does not support the given
 * type, the {@link PersistenceException} is thrown.
 * @param cls the class of the object to be returned.
 * This is usually either the underlying class
 * implementing {@code EntityManagerFactory} or an
 * interface it implements.
 * @return an instance of the specified class
 * @throws PersistenceException if the provider does not support
 * the given type
 * @since 2.1
 */
 <T> T unwrap(Class<T> cls);

 /**
 * Add a named copy of the given {@link EntityGraph} to this
 * {@code EntityManagerFactory}. If an entity graph with the
 * given name already exists, it is replaced.
 * @param graphName name for the entity graph
 * @param entityGraph entity graph
 * @since 2.1
 */
 <T> void addNamedEntityGraph(String graphName, EntityGraph<T> entityGraph);

 /**
 * A map keyed by {@linkplain NamedQuery#name query name}, containing
 * {@linkplain TypedQueryReference references} to every named query whose
 * result type is assignable to the given Java type.
 * @param resultType any Java type, including {@code Object.class}
 * meaning all queries
 * @return a map keyed by query name
 * @param <R> the specified upper bound on the query result types
 *
 * @since 3.2
 */
 <R> Map<String, TypedQueryReference<R>> getNamedQueries(Class<R> resultType);

 /**
 * A map keyed by {@linkplain NamedEntityGraph#name graph name}, containing
 * every named {@linkplain EntityGraph entity graph} whose entity type is
 * assignable to the given Java type.
 * @param entityType any Java type, including {@code Object.class}
 * meaning all entity graphs
 * @return a map keyed by graph name
 * @param <E> the specified upper bound on the entity graph types
 *
 * @since 3.2
 */
 <E> Map<String, EntityGraph<? extends E>> getNamedEntityGraphs(Class<E> entityType);

 /**
 * Create a new application-managed {@link EntityManager} with an active
 * transaction, and execute the given function, passing the {@code EntityManager}
 * to the function.
 * <p>
 * If the transaction type of the persistence unit is JTA, and there is a JTA
 * transaction already associated with the caller, then the {@code EntityManager}
 * is associated with this current transaction. If the given function throws an
 * exception, the JTA transaction is marked for rollback, and the exception is
 * rethrown.
 * <p>
 * Otherwise, if the transaction type of the persistence unit is resource-local,
 * or if there is no JTA transaction already associated with the caller, then
 * the {@code EntityManager} is associated with a new transaction. If the given
 * function returns without throwing an exception, this transaction is committed.
 * If the function does throw an exception, the transaction is rolled back, and
 * the exception is rethrown.
 * <p>
 * Finally, the {@code EntityManager} is closed before this method returns
 * control to the client.
 *
 * @param work a function to be executed in the scope of the transaction
 *
 * @since 3.2
 */
 void runInTransaction(Consumer<EntityManager> work);
 /**
 * Create a new application-managed {@link EntityManager} with an active
 * transaction, and call the given function, passing the {@code EntityManager}
 * to the function.
 * <p>
 * If the transaction type of the persistence unit is JTA, and there is a JTA
 * transaction already associated with the caller, then the {@code EntityManager}
 * is associated with this current transaction. If the given function returns
 * without throwing an exception, the result of the function is returned. If the
 * given function throws an exception, the JTA transaction is marked for rollback,
 * and the exception is rethrown.
 * <p>
 * Otherwise, if the transaction type of the persistence unit is resource-local,
 * or if there is no JTA transaction already associated with the caller, then
 * the {@code EntityManager} is associated with a new transaction. If the given
 * function returns without throwing an exception, this transaction is committed
 * and the result of the function is returned. If the function does throw an
 * exception, the transaction is rolled back, and the exception is rethrown.
 * <p>
 * Finally, the {@code EntityManager} is closed before this method returns
 * control to the client.
 *
 * @param work a function to be called in the scope of the transaction
 * @return the value returned by the given function
 *
 * @since 3.2
 */
 <R> R callInTransaction(Function<EntityManager, R> work);
}

B.4. LockModeType

/**
 * Enumerates the kinds of optimistic or pessimistic lock which
 * may be obtained on an entity instance.
 *
 * <p> A specific lock mode may be requested by passing an explicit
 * {@code LockModeType} as an argument to:
 * <ul>
 * <li>one of the methods of {@link EntityManager} which obtains
 * locks ({@link EntityManager#lock lock()},
 * {@link EntityManager#find find()}, or
 * {@link EntityManager#refresh refresh()}), or
 * <li>to {@link Query#setLockMode(LockModeType)} or
 * {@link TypedQuery#setLockMode(LockModeType)}.
 * </ul>
 * 
 * <p> Optimistic locks are specified using
 * {@link LockModeType#OPTIMISTIC LockModeType.OPTIMISTIC} and
 * {@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}. The lock mode
 * types {@link LockModeType#READ} and {@link LockModeType#WRITE} are
 * synonyms for {@code OPTIMISTIC} and {@code OPTIMISTIC_FORCE_INCREMENT}
 * respectively. The latter are preferred for new applications.
 *
 * <p> The semantics of requesting locks of type
 * {@code LockModeType.OPTIMISTIC} and
 * {@code LockModeType.OPTIMISTIC_FORCE_INCREMENT} are the
 * following.
 *
 * <p> If transaction T1 calls for a lock of type 
 * {@code LockModeType.OPTIMISTIC} on a versioned object, 
 * the entity manager must ensure that neither of the following 
 * phenomena can occur:
 * <ul>
 * <li> P1 (Dirty read): Transaction T1 modifies a row. 
 * Another transaction T2 then reads that row and obtains the
 * modified value, before T1 has committed or rolled back.
 * Transaction T2 eventually commits successfully; it does not 
 * matter whether T1 commits or rolls back and whether it does 
 * so before or after T2 commits.
 * </li>
 * <li> P2 (Non-repeatable read): Transaction T1 reads a row. 
 * Another transaction T2 then modifies or deletes that row, 
 * before T1 has committed. Both transactions eventually commit 
 * successfully.
 * </li>
 * </ul>
 *
 * <p> Lock modes must always prevent the phenomena P1 and P2.
 *
 * <p> In addition, obtaining a lock of type
 * {@code LockModeType.OPTIMISTIC_FORCE_INCREMENT} on a versioned
 * object, will also force an update (increment) to the entity's
 * version column.
 *
 * <p> The persistence implementation is not required to support
 * the use of optimistic lock modes on non-versioned objects. When
 * it cannot support such a lock request, it must throw the {@link
 * PersistenceException}.
 *
 * <p>The lock modes {@link LockModeType#PESSIMISTIC_READ},
 * {@link LockModeType#PESSIMISTIC_WRITE}, and
 * {@link LockModeType#PESSIMISTIC_FORCE_INCREMENT} are used to
 * immediately obtain long-term database locks.
 *
 * <p> The semantics of requesting locks of type
 * {@code LockModeType.PESSIMISTIC_READ},
 * {@code LockModeType.PESSIMISTIC_WRITE}, and
 * {@code LockModeType.PESSIMISTIC_FORCE_INCREMENT} are the
 * following.
 *
 * <p> If transaction T1 calls for a lock of type
 * {@code LockModeType.PESSIMISTIC_READ} or
 * {@code LockModeType.PESSIMISTIC_WRITE} on an object, the entity
 * manager must ensure that neither of the following phenomena can
 * occur: 
 * <ul> 
 * <li> P1 (Dirty read): Transaction T1 modifies a
 * row. Another transaction T2 then reads that row and obtains the
 * modified value, before T1 has committed or rolled back.
 *
 * <li> P2 (Non-repeatable read): Transaction T1 reads a row.
 * Another transaction T2 then modifies or deletes that row, before
 * T1 has committed or rolled back.
 * </ul>
 *
 * <p> A lock with {@code LockModeType.PESSIMISTIC_WRITE} can be
 * obtained on an entity instance to force serialization among
 * transactions attempting to update the entity data. A lock with
 * {@code LockModeType.PESSIMISTIC_READ} can be used to query data
 * using repeatable-read semantics without the need to reread the
 * data at the end of the transaction to obtain a lock, and without
 * blocking other transactions reading the data. A lock with
 * {@code LockModeType.PESSIMISTIC_WRITE} can be used when querying
 * data and there is a high likelihood of deadlock or update failure
 * among concurrent updating transactions.
 * 
 * <p> The persistence implementation must support the use of locks
 * of type {@code LockModeType.PESSIMISTIC_READ} and
 * {@code LockModeType.PESSIMISTIC_WRITE} with non-versioned entities
 * as well as with versioned entities.
 *
 * <p> When the lock cannot be obtained, and the database locking
 * failure results in transaction-level rollback, the provider must
 * throw the {@link PessimisticLockException} and ensure that the
 * JTA transaction or {@code EntityTransaction} has been marked for
 * rollback.
 * 
 * <p> When the lock cannot be obtained, and the database locking
 * failure results in only statement-level rollback, the provider
 * must throw the {@link LockTimeoutException} (and must not mark
 * the transaction for rollback).
 *
 * @since 1.0
 *
 */
public enum LockModeType implements FindOption, RefreshOption {
 /**
 * Synonymous with {@link #OPTIMISTIC}.
 * <p>
 * {@code OPTIMISTIC} is preferred for new applications.
 *
 */
 READ,

 /**
 * Synonymous with {@link #OPTIMISTIC_FORCE_INCREMENT}.
 * <p>
 * {@code OPTIMISTIC_FORCE_INCREMENT} is preferred for
 * new applications.
 *
 */
 WRITE,

 /**
 * Optimistic lock.
 *
 * @since 2.0
 */
 OPTIMISTIC,

 /**
 * Optimistic lock, with version update.
 *
 * @since 2.0
 */
 OPTIMISTIC_FORCE_INCREMENT,

 /**
 *
 * Pessimistic read lock.
 *
 * @since 2.0
 */
 PESSIMISTIC_READ,

 /**
 * Pessimistic write lock.
 *
 * @since 2.0
 */
 PESSIMISTIC_WRITE,

 /**
 * Pessimistic write lock, with version update.
 *
 * @since 2.0
 */
 PESSIMISTIC_FORCE_INCREMENT,

 /**
 * No lock.
 *
 * @since 2.0
 */
 NONE
}

B.5. Cache

/**
 * Interface used to interact with the second-level cache.
 * If no second-level cache is in use, the methods of this
 * interface have no effect, except for {@link #contains},
 * which returns false.
 *
 * @since 2.0
 */
public interface Cache {

 /**
 * Whether the cache contains data for the given entity.
 * @param cls entity class 
 * @param primaryKey primary key
 * @return boolean indicating whether the entity is in the cache
 */
 boolean contains(Class<?> cls, Object primaryKey);

 /**
 * Remove the data for the given entity from the cache.
 * @param cls entity class
 * @param primaryKey primary key
 */
 void evict(Class<?> cls, Object primaryKey);

 /**
 * Remove the data for entities of the specified class
 * (and its subclasses) from the cache.
 * @param cls entity class
 */
 void evict(Class<?> cls);

 /**
 * Clear the cache.
 */
 void evictAll();

 /**
 * Return an object of the specified type to allow access to
 * the provider-specific API. If the provider's implementation
 * of the {@code Cache} interface does not support the specified
 * class, the {@link PersistenceException} is thrown.
 * @param cls the class of the object to be returned.
 * This is usually either the underlying class
 * implementing {@code Cache}, or an interface it
 * implements.
 * @return an instance of the specified type
 * @throws PersistenceException if the provider does not support
 * the given type
 * @since 2.1
 */
 <T> T unwrap(Class<T> cls);
}

B.6. Query

package jakarta.persistence;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Interface used to control query execution.
 *
 * @see TypedQuery
 * @see StoredProcedureQuery
 * @see Parameter
 *
 * @since 1.0
 */
public interface Query {

 /**
 * Execute a SELECT query and return the query results as an untyped
 * {@link List}.
 * @return a list of the results, or an empty list if there are
 * no results
 * @throws IllegalStateException if called for a Jakarta
 * Persistence query language UPDATE or DELETE statement
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws TransactionRequiredException if a lock mode other than
 * {@code NONE} has been set and there is no transaction
 * or the persistence context has not been joined to the
 * transaction
 * @throws PessimisticLockException if pessimistic locking
 * fails and the transaction is rolled back
 * @throws LockTimeoutException if pessimistic locking
 * fails and only the statement is rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 @SuppressWarnings({"rawtypes"})
 List getResultList();

 /**
 * Execute a SELECT query and return the query results as an untyped
 * {@link java.util.stream.Stream}.
 *
 * <p>By default, this method delegates to {@code getResultList().stream()},
 * however persistence provider may choose to override this method
 * to provide additional capabilities.
 *
 * @return a stream of the results, or an empty stream if there
 * are no results
 * @throws IllegalStateException if called for a Jakarta
 * Persistence query language UPDATE or DELETE statement
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws TransactionRequiredException if a lock mode other than
 * {@code NONE} has been set and there is no transaction
 * or the persistence context has not been joined to the
 * transaction
 * @throws PessimisticLockException if pessimistic locking
 * fails and the transaction is rolled back
 * @throws LockTimeoutException if pessimistic locking
 * fails and only the statement is rolled back
 * @throws PersistenceException if the query execution exceeds
 * the query timeout value set and the transaction
 * is rolled back
 * @see Stream
 * @see #getResultList()
 * @since 2.2
 */
 @SuppressWarnings({"rawtypes"})
 default Stream getResultStream() {
 return getResultList().stream();
 }

 /**
 * Execute a SELECT query that returns a single untyped result.
 * @return the result
 * @throws NoResultException if there is no result
 * @throws NonUniqueResultException if more than one result
 * @throws IllegalStateException if called for a Jakarta
 * Persistence query language UPDATE or DELETE statement
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws TransactionRequiredException if a lock mode other than
 * {@code NONE} has been set and there is no transaction
 * or the persistence context has not been joined to the
 * transaction
 * @throws PessimisticLockException if pessimistic locking
 * fails and the transaction is rolled back
 * @throws LockTimeoutException if pessimistic locking
 * fails and only the statement is rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 Object getSingleResult();

 /**
 * Execute a SELECT query that returns a single untyped result.
 * @return the result, or null if there is no result
 * @throws NonUniqueResultException if more than one result
 * @throws IllegalStateException if called for a Jakarta
 * Persistence query language UPDATE or DELETE statement
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws TransactionRequiredException if a lock mode other than
 * {@code NONE} has been set and there is no transaction
 * or the persistence context has not been joined to the
 * transaction
 * @throws PessimisticLockException if pessimistic locking
 * fails and the transaction is rolled back
 * @throws LockTimeoutException if pessimistic locking
 * fails and only the statement is rolled back
 * @throws PersistenceException if the query execution exceeds
 * the query timeout value set and the transaction
 * is rolled back
 *
 * @since 3.2
 */
 Object getSingleResultOrNull();

 /**
 * Execute an update or delete statement.
 * @return the number of entities updated or deleted
 * @throws IllegalStateException if called for a Jakarta
 * Persistence query language SELECT statement or for
 * a criteria query
 * @throws TransactionRequiredException if there is 
 * no transaction or the persistence context has not
 * been joined to the transaction
 * @throws QueryTimeoutException if the statement execution 
 * exceeds the query timeout value set and only
 * the statement is rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 int executeUpdate();

 /**
 * Set the maximum number of results to retrieve.
 * @param maxResult maximum number of results to retrieve
 * @return the same query instance
 * @throws IllegalArgumentException if the argument is negative
 */
 Query setMaxResults(int maxResult);

 /**
 * The maximum number of results the query object was set to retrieve.
 * Returns {@link Integer#MAX_VALUE} if {@link #setMaxResults} was not
 * applied to the query object.
 * @return maximum number of results
 * @since 2.0
 */
 int getMaxResults();

 /**
 * Set the position of the first result to retrieve.
 * @param startPosition position of the first result, numbered from 0
 * @return the same query instance
 * @throws IllegalArgumentException if the argument is negative
 */
 Query setFirstResult(int startPosition);

 /**
 * The position of the first result the query object was set to
 * retrieve. Returns {@code 0} if {@code setFirstResult} was not
 * applied to the query object.
 * @return position of the first result
 * @since 2.0
 */
 int getFirstResult();

 /**
 * Set a query property or hint. The hints elements may be used 
 * to specify query properties and hints. Properties defined by
 * this specification must be observed by the provider. 
 * Vendor-specific hints that are not recognized by a provider
 * must be silently ignored. Portable applications should not
 * rely on the standard timeout hint. Depending on the database
 * in use and the locking mechanisms used by the provider,
 * this hint may or may not be observed.
 * @param hintName name of the property or hint
 * @param value value for the property or hint
 * @return the same query instance
 * @throws IllegalArgumentException if the second argument is not
 * valid for the implementation
 */
 Query setHint(String hintName, Object value);
