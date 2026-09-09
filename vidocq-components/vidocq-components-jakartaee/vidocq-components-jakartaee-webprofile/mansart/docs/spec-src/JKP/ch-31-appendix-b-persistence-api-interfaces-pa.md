# Appendix B: Persistence API Interfaces (part 3/5)

 /**
 * Get the properties and hints and associated values that are in
 * effect for the query instance.
 * @return query properties and hints
 * @since 2.0
 */
 Map<String, Object> getHints();

 /**
 * Bind the value of a {@code Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter
 * does not correspond to a parameter of the
 * query
 * @since 2.0
 */
 <T> Query setParameter(Parameter<T> param, T value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a {@link Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 * @since 2.0
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 Query setParameter(Parameter<Calendar> param, Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a {@link Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 * @since 2.0
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 Query setParameter(Parameter<Date> param, Date value, 
 TemporalType temporalType);

 /**
 * Bind an argument value to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if
 * the argument is of incorrect type
 */
 Query setParameter(String name, Object value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if
 * the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 Query setParameter(String name, Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if
 * the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 Query setParameter(String name, Date value, 
 TemporalType temporalType);

 /**
 * Bind an argument value to a positional parameter.
 * @param position position
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the
 * query or if the argument is of incorrect type
 */
 Query setParameter(int position, Object value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a positional
 * parameter.
 * @param position position
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query or
 * if the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 Query setParameter(int position, Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a positional
 * parameter.
 * @param position position
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query or
 * if the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 Query setParameter(int position, Date value, 
 TemporalType temporalType);

 /**
 * Get the parameter objects corresponding to the declared
 * parameters of the query.
 * Returns empty set if the query has no parameters.
 * This method is not required to be supported for native
 * queries.
 * @return set of the parameter objects
 * @throws IllegalStateException if invoked on a native
 * query when the implementation does not support
 * this use
 * @since 2.0
 */
 Set<Parameter<?>> getParameters();

 /**
 * Get the parameter object corresponding to the declared
 * parameter of the given name.
 * This method is not required to be supported for native
 * queries.
 * @param name parameter name
 * @return parameter object
 * @throws IllegalArgumentException if the parameter of the
 * specified name does not exist
 * @throws IllegalStateException if invoked on a native
 * query when the implementation does not support
 * this use
 * @since 2.0
 */
 Parameter<?> getParameter(String name);

 /**
 * Get the parameter object corresponding to the declared
 * parameter of the given name and type.
 * This method is required to be supported for criteria queries
 * only.
 * @param name parameter name
 * @param type type
 * @return parameter object
 * @throws IllegalArgumentException if the parameter of the
 * specified name does not exist or is not assignable
 * to the type
 * @throws IllegalStateException if invoked on a native
 * query or Jakarta Persistence query language query when
 * the implementation does not support this use
 * @since 2.0
 */
 <T> Parameter<T> getParameter(String name, Class<T> type);

 /**
 * Get the parameter object corresponding to the declared
 * positional parameter with the given position.
 * This method is not required to be supported for native
 * queries.
 * @param position position
 * @return parameter object
 * @throws IllegalArgumentException if the parameter with the
 * specified position does not exist
 * @throws IllegalStateException if invoked on a native
 * query when the implementation does not support
 * this use
 * @since 2.0
 */
 Parameter<?> getParameter(int position);

 /**
 * Get the parameter object corresponding to the declared
 * positional parameter with the given position and type.
 * This method is not required to be supported by the provider.
 * @param position position
 * @param type type
 * @return parameter object
 * @throws IllegalArgumentException if the parameter with the
 * specified position does not exist or is not assignable
 * to the type
 * @throws IllegalStateException if invoked on a native
 * query or Jakarta Persistence query language query when
 * the implementation does not support this use
 * @since 2.0
 */
 <T> Parameter<T> getParameter(int position, Class<T> type);

 /**
 * Return a boolean indicating whether a value has been bound 
 * to the parameter.
 * @param param parameter object
 * @return boolean indicating whether parameter has been bound
 * @since 2.0
 */
 boolean isBound(Parameter<?> param);

 /**
 * Return the input value bound to the parameter.
 * (Note that OUT parameters are unbound.)
 * @param param parameter object
 * @return parameter value
 * @throws IllegalArgumentException if the parameter is not 
 * a parameter of the query
 * @throws IllegalStateException if the parameter has not
 * been bound
 * @since 2.0
 */
 <T> T getParameterValue(Parameter<T> param);

 /**
 * Return the input value bound to the named parameter.
 * (Note that OUT parameters are unbound.)
 * @param name parameter name
 * @return parameter value
 * @throws IllegalStateException if the parameter has not
 * been bound
 * @throws IllegalArgumentException if the parameter of the
 * specified name does not exist
 * @since 2.0
 */
 Object getParameterValue(String name);

 /**
 * Return the input value bound to the positional parameter.
 * (Note that OUT parameters are unbound.)
 * @param position position
 * @return parameter value
 * @throws IllegalStateException if the parameter has not
 * been bound
 * @throws IllegalArgumentException if the parameter with the
 * specified position does not exist
 * @since 2.0
 */
 Object getParameterValue(int position);

 /**
 * Set the flush mode type to be used for the query execution.
 * The flush mode type applies to the query regardless of the
 * flush mode type in use for the entity manager.
 * @param flushMode flush mode
 * @return the same query instance
 */
 Query setFlushMode(FlushModeType flushMode);

 /**
 * Get the flush mode in effect for the query execution. 
 * If a flush mode has not been set for the query object, 
 * returns the flush mode in effect for the entity manager.
 * @return flush mode
 * @since 2.0
 */
 FlushModeType getFlushMode();

 /**
 * Set the lock mode type to be used for the query execution.
 * @param lockMode lock mode
 * @return the same query instance
 * @throws IllegalStateException if the query is found not to
 * be a Jakarta Persistence query language SELECT query
 * or a {@link jakarta.persistence.criteria.CriteriaQuery}
 * query
 * @since 2.0
 */
 Query setLockMode(LockModeType lockMode);

 /**
 * Get the current lock mode for the query. Returns null if a
 * lock mode has not been set on the query object.
 * @return lock mode
 * @throws IllegalStateException if the query is found not to
 * be a Jakarta Persistence query language SELECT query
 * or a {@link jakarta.persistence.criteria.CriteriaQuery}
 * query
 * @since 2.0
 */
 LockModeType getLockMode();

 /**
 * Set the cache retrieval mode that is in effect during query
 * execution. This cache retrieval mode overrides the cache
 * retrieve mode in use by the entity manager.
 * @param cacheRetrieveMode cache retrieval mode
 * @return the same query instance
 * @since 3.2
 */
 Query setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode);

 /**
 * Set the cache storage mode that is in effect during query
 * execution. This cache storage mode overrides the cache
 * storage mode in use by the entity manager.
 * @param cacheStoreMode cache storage mode
 * @return the same query instance
 * @since 3.2
 */
 Query setCacheStoreMode(CacheStoreMode cacheStoreMode);

 /**
 * The cache retrieval mode that will be in effect during query
 * execution.
 * @since 3.2
 */
 CacheRetrieveMode getCacheRetrieveMode();

 /**
 * The cache storage mode that will be in effect during query
 * execution.
 * @since 3.2
 */
 CacheStoreMode getCacheStoreMode();

 /**
 * Set the query timeout, in milliseconds. This is a hint,
 * and is an alternative to {@linkplain #setHint setting
 * the hint} {@code jakarta.persistence.query.timeout}.
 * @param timeout the timeout, in milliseconds, or null to
 * indicate no timeout
 * @return the same query instance
 * @since 3.2
 */
 Query setTimeout(Integer timeout);

 /**
 * The query timeout.
 * @since 3.2
 */
 Integer getTimeout();

 /**
 * Return an object of the specified type to allow access to 
 * a provider-specific API. If the provider implementation of
 * {@code Query} does not support the given type, the
 * {@link PersistenceException} is thrown.
 * @param cls the type of the object to be returned.
 * This is usually either the underlying class
 * implementing {@code Query} or an interface it
 * implements.
 * @return an instance of the specified class
 * @throws PersistenceException if the provider does not support
 * the given type
 * @since 2.0
 */
 <T> T unwrap(Class<T> cls);
}

B.7. TypedQuery

package jakarta.persistence;

import java.util.List;
import java.util.Date;
import java.util.Calendar;
import java.util.stream.Stream;

/**
 * Interface used to control the execution of typed queries.
 *
 * @param <X> query result type
 *
 * @see Query
 * @see Parameter
 *
 * @since 2.0
 */
public interface TypedQuery<X> extends Query {
 
 /**
 * Execute a SELECT query and return the query results as a typed
 * {@link List List&lt;X&gt;}.
 * @return a list of the results, each of type {@link X}, or an
 * empty list if there are no results
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
 List<X> getResultList();

 /**
 * Execute a SELECT query and return the query result as a typed
 * {@link java.util.stream.Stream Stream&lt;X&gt;}.
 *
 * <p>By default, this method delegates to {@link List#stream()
 * getResultList().stream()}, however, persistence provider may
 * choose to override this method to provide additional capabilities.
 *
 * @return a stream of the results, each of type {@link X}, or an
 * empty stream if there are no results
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
 default Stream<X> getResultStream() {
 return getResultList().stream();
 }

 /**
 * Execute a SELECT query that returns a single result.
 * @return the result, of type {@link X}
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
 X getSingleResult();

 /**
 * Execute a SELECT query that returns a single untyped result.
 * @return the result, of type {@link X}, or null if there is no
 * result
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
 X getSingleResultOrNull();

 /**
 * Set the maximum number of results to retrieve.
 * @param maxResult maximum number of results to retrieve
 * @return the same query instance
 * @throws IllegalArgumentException if the argument is negative
 */
 TypedQuery<X> setMaxResults(int maxResult);

 /**
 * Set the position of the first result to retrieve.
 * @param startPosition position of the first result, 
 * numbered from 0
 * @return the same query instance
 * @throws IllegalArgumentException if the argument is negative
 */
 TypedQuery<X> setFirstResult(int startPosition);

 /**
 * Set a query property or hint. The hints elements may be used 
 * to specify query properties and hints. Properties defined by
 * this specification must be observed by the provider. 
 * Vendor-specific hints that are not recognized by a provider
 * must be silently ignored. Portable applications should not
 * rely on the standard timeout hint. Depending on the database
 * in use and the locking mechanisms used by the provider,
 * this hint may or may not be observed.
 * @param hintName name of property or hint
 * @param value value for the property or hint
 * @return the same query instance
 * @throws IllegalArgumentException if the second argument is not
 * valid for the implementation
 */
 TypedQuery<X> setHint(String hintName, Object value);

 /**
 * Bind the value of a {@code Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter
 * does not correspond to a parameter of the
 * query
 */
 <T> TypedQuery<X> setParameter(Parameter<T> param, T value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a {@link Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 TypedQuery<X> setParameter(Parameter<Calendar> param, 
 Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a {@link Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 TypedQuery<X> setParameter(Parameter<Date> param, Date value, 
 TemporalType temporalType);

 /**
 * Bind an argument value to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if
 * the argument is of incorrect type
 */
 TypedQuery<X> setParameter(String name, Object value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does
 * not correspond to a parameter of the query or if
 * the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 TypedQuery<X> setParameter(String name, Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does
 * not correspond to a parameter of the query or if
 * the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 TypedQuery<X> setParameter(String name, Date value, 
 TemporalType temporalType);

 /**
 * Bind an argument value to a positional parameter.
 * @param position position
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the
 * query or if the argument is of incorrect type
 */
 TypedQuery<X> setParameter(int position, Object value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a positional
 * parameter.
 * @param position position
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query
 * or if the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 TypedQuery<X> setParameter(int position, Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a positional
 * parameter.
 * @param position position
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query
 * or if the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 TypedQuery<X> setParameter(int position, Date value, 
 TemporalType temporalType);

 /**
 * Set the flush mode type to be used for the query execution.
 * The flush mode type applies to the query regardless of the
 * flush mode type in use for the entity manager.
 * @param flushMode flush mode
 * @return the same query instance
 */
 TypedQuery<X> setFlushMode(FlushModeType flushMode);

 /**
 * Set the lock mode type to be used for the query execution.
 * @param lockMode lock mode
 * @return the same query instance
 * @throws IllegalStateException if the query is found not to 
 * be a Jakarta Persistence query language SELECT query
 * or a {@link jakarta.persistence.criteria.CriteriaQuery}
 * query
 */
 TypedQuery<X> setLockMode(LockModeType lockMode);

 /**
 * Set the cache retrieval mode that is in effect during
 * query execution. This cache retrieval mode overrides the
 * cache retrieve mode in use by the entity manager.
 * @param cacheRetrieveMode cache retrieval mode
 * @return the same query instance
 * @since 3.2
 */
 TypedQuery<X> setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode);

 /**
 * Set the cache storage mode that is in effect during
 * query execution. This cache storage mode overrides the
 * cache storage mode in use by the entity manager.
 * @param cacheStoreMode cache storage mode
 * @return the same query instance
 * @since 3.2
 */
 TypedQuery<X> setCacheStoreMode(CacheStoreMode cacheStoreMode);

 /**
 * Set the query timeout, in milliseconds. This is a hint,
 * and is an alternative to {@linkplain #setHint setting
 * the hint} {@code jakarta.persistence.query.timeout}.
 * @param timeout the timeout, in milliseconds, or null to
 * indicate no timeout
 * @return the same query instance
 * @since 3.2
 */
 TypedQuery<X> setTimeout(Integer timeout);
}

B.8. StoredProcedureQuery

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Interface used to control stored procedure query execution.
 *
 * <p>
 * Stored procedure query execution may be controlled in accordance with 
 * the following:
 * <ul>
 * <li>The {@link #setParameter} methods are used to set the values of
 * all required {@code IN} and {@code INOUT} parameters. It is not
 * required to set the values of stored procedure parameters for which
 * default values have been defined by the stored procedure.</li>
 * <li> When {@link #getResultList} and {@link #getSingleResult} are
 * called on a {@code StoredProcedureQuery} object, the provider calls
 * {@link #execute} on an unexecuted stored procedure query before
 * processing {@code getResultList} or {@code getSingleResult}.</li>
 * <li> When {@link #executeUpdate} is called on a
 * {@code StoredProcedureQuery} object, the provider will call
 * {@link #execute} on an unexecuted stored procedure query, followed
 * by {@link #getUpdateCount}. The results of {@code executeUpdate} will
 * be those of {@code getUpdateCount}.</li>
 * <li> The {@link #execute} method supports both the simple case where
 * scalar results are passed back only via {@code INOUT} and {@code OUT}
 * parameters as well as the most general case (multiple result sets
 * and/or update counts, possibly also in combination with output
 * parameter values).</li>
 * <li> The {@code execute} method returns true if the first result is
 * a result set, and false if it is an update count or there are no
 * results other than through {@code INOUT} and {@code OUT} parameters,
 * if any.</li>
 * <li> If the {@code execute} method returns true, the pending result
 * set can be obtained by calling {@link #getResultList} or
 * {@link #getSingleResult}.</li>
 * <li> The {@link #hasMoreResults} method can then be used to test for
 * further results.</li>
 * <li> If {@code execute} or {@code hasMoreResults} returns false, the
 * {@link #getUpdateCount} method can be called to obtain the pending
 * result if it is an update count. The {@code getUpdateCount} method
 * will return either the update count (zero or greater) or -1 if there
 * is no update count (i.e., either the next result is a result set or
 * there is no next update count).</li>
 * <li> For portability, results that correspond to JDBC result sets
 * and update counts need to be processed before the values of any
 * {@code INOUT} or {@code OUT} parameters are extracted.</li>
 * <li> After results returned through {@link #getResultList} and
 * {@link #getUpdateCount} have been exhausted, results returned through
 * {@code INOUT} and {@code OUT} parameters can be retrieved.</li>
 * <li> The {@link #getOutputParameterValue} methods are used to
 * retrieve the values passed back from the procedure through
 * {@code INOUT} and {@code OUT} parameters.</li>
 * <li> When using {@code REF_CURSOR} parameters for result sets the
 * update counts should be exhausted before calling {@link #getResultList}
 * to retrieve the result set. Alternatively, the {@code REF_CURSOR}
 * result set can be retrieved through {@link #getOutputParameterValue}.
 * Result set mappings are applied to results corresponding to
 * {@code REF_CURSOR} parameters in the order the {@code REF_CURSOR}
 * parameters were registered with the query.</li>
 * <li> In the simplest case, where results are returned only via
 * {@code INOUT} and {@code OUT} parameters, {@code execute} can be
 * followed immediately by calls to {@link #getOutputParameterValue}.
 * </li>
 * </ul>
 *
 * @see Query
 * @see Parameter
 *
 * @since 2.1
 */
public interface StoredProcedureQuery extends Query {

 /**
 * Set a query property or hint. The hints elements may be used 
 * to specify query properties and hints. Properties defined by
 * this specification must be observed by the provider. 
 * Vendor-specific hints that are not recognized by a provider
 * must be silently ignored. Portable applications should not
 * rely on the standard timeout hint. Depending on the database
 * in use, this hint may or may not be observed.
 * @param hintName name of the property or hint
 * @param value value for the property or hint
 * @return the same query instance
 * @throws IllegalArgumentException if the second argument is not
 * valid for the implementation
 */
 StoredProcedureQuery setHint(String hintName, Object value);

 /**
 * Bind the value of a {@code Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 */
 <T> StoredProcedureQuery setParameter(Parameter<T> param, 
 T value);

 /**
 * Bind an instance of {@link java.util.Calendar} to a {@link Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 StoredProcedureQuery setParameter(Parameter<Calendar> param,
 Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@link java.util.Date} to a {@link Parameter} object.
 * @param param parameter object
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter does not
 * correspond to a parameter of the query
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 StoredProcedureQuery setParameter(Parameter<Date> param,
 Date value,
 TemporalType temporalType);

 /**
 * Bind an argument value to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if the
 * argument is of incorrect type
 */
 StoredProcedureQuery setParameter(String name, Object value);

 /**
 * Bind an instance of {@code java.util.Calendar} to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if the
 * value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 StoredProcedureQuery setParameter(String name, 
 Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@code java.util.Date} to a named parameter.
 * @param name parameter name
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if the parameter name does 
 * not correspond to a parameter of the query or if the
 * value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 StoredProcedureQuery setParameter(String name, 
 Date value, 
 TemporalType temporalType);

 /**
 * Bind an argument value to a positional parameter.
 * @param position position
 * @param value parameter value
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query
 * or if the argument is of incorrect type
 */
 StoredProcedureQuery setParameter(int position, Object value);

 /**
 * Bind an instance of {@code java.util.Calendar} to a positional
 * parameter.
 * @param position position
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query or
 * if the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 StoredProcedureQuery setParameter(int position, 
 Calendar value, 
 TemporalType temporalType);

 /**
 * Bind an instance of {@code java.util.Date} to a positional parameter.
 * @param position position
 * @param value parameter value
 * @param temporalType temporal type
 * @return the same query instance
 * @throws IllegalArgumentException if position does not
 * correspond to a positional parameter of the query or
 * if the value argument is of incorrect type
 * @deprecated Newly-written code should use the date/time types
 * defined in {@link java.time}.
 */
 @Deprecated(since = "3.2")
 StoredProcedureQuery setParameter(int position, 
 Date value, 
 TemporalType temporalType);

 /**
 * Set the flush mode type to be used for the query execution.
 * The flush mode type applies to the query regardless of the
 * flush mode type in use for the entity manager.
 * @param flushMode flush mode
 * @return the same query instance
 */
 StoredProcedureQuery setFlushMode(FlushModeType flushMode);

 /**
 * Set the cache retrieval mode that is in effect during
 * query execution. This cache retrieval mode overrides the
 * cache retrieve mode in use by the entity manager.
 * @param cacheRetrieveMode cache retrieval mode
 * @return the same query instance
 * @since 3.2
 */
 StoredProcedureQuery setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode);

 /**
 * Set the cache storage mode that is in effect during
 * query execution. This cache storage mode overrides the
 * cache storage mode in use by the entity manager.
 * @param cacheStoreMode cache storage mode
 * @return the same query instance
 * @since 3.2
 */
 StoredProcedureQuery setCacheStoreMode(CacheStoreMode cacheStoreMode);

 /**
 * Set the query timeout, in milliseconds. This is a hint,
 * and is an alternative to {@linkplain #setHint setting
 * the hint} {@code jakarta.persistence.query.timeout}.
 * @param timeout the timeout, in milliseconds, or null to
 * indicate no timeout
 * @return the same query instance
 * @since 3.2
 */
 StoredProcedureQuery setTimeout(Integer timeout);

 /**
 * Register a positional parameter.
 * All parameters must be registered.
 * @param position parameter position
 * @param type type of the parameter
 * @param mode parameter mode 
 * @return the same query instance
 */
 StoredProcedureQuery registerStoredProcedureParameter(
 int position,
 Class<?> type,
 ParameterMode mode);

 /**
 * Register a named parameter.
 * @param parameterName name of the parameter as registered or
 * specified in metadata
 * @param type type of the parameter
 * @param mode parameter mode 
 * @return the same query instance
 */
 StoredProcedureQuery registerStoredProcedureParameter(
 String parameterName,
 Class<?> type,
 ParameterMode mode);

 /**
 * Retrieve a value passed back from the procedure
 * through an INOUT or OUT parameter.
 * For portability, all results corresponding to result sets
 * and update counts must be retrieved before the values of 
 * output parameters.
 * @param position parameter position
 * @return the result that is passed back through the parameter
 * @throws IllegalArgumentException if the position does
 * not correspond to a parameter of the query or is
 * not an INOUT or OUT parameter
 */
 Object getOutputParameterValue(int position);

 /**
 * Retrieve a value passed back from the procedure
 * through an INOUT or OUT parameter.
 * For portability, all results corresponding to result sets
 * and update counts must be retrieved before the values of 
 * output parameters.
 * @param parameterName name of the parameter as registered or
 * specified in metadata
 * @return the result that is passed back through the parameter
 * @throws IllegalArgumentException if the parameter name does
 * not correspond to a parameter of the query or is
 * not an INOUT or OUT parameter
 */
 Object getOutputParameterValue(String parameterName);

 /**
 * Return true if the first result corresponds to a result set,
 * and false if it is an update count or if there are no results
 * other than through INOUT and OUT parameters, if any.
 * @return true if first result corresponds to result set
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 boolean execute();

 /**
 * Return the update count of -1 if there is no pending result or
 * if the first result is not an update count. The provider will
 * call {@code execute} on the query if needed.
 * @return the update count or -1 if there is no pending result
 * or if the next result is not an update count.
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
 * Retrieve the list of results from the next result set.
 * The provider will call {@code execute} on the query
 * if needed.
 * A {@code REF_CURSOR} result set, if any, is retrieved
 * in the order the {@code REF_CURSOR} parameter was 
 * registered with the query.
 * @return a list of the results or null is the next item is not 
 * a result set
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 List getResultList();

 /**
 * Retrieve a single result from the next result set.
 * The provider will call {@code execute} on the query
 * if needed.
 * A {@code REF_CURSOR} result set, if any, is retrieved
 * in the order the {@code REF_CURSOR} parameter was 
 * registered with the query.
 * @return the result or null if the next item is not a result set
 * @throws NoResultException if there is no result in the next
 * result set
 * @throws NonUniqueResultException if more than one result
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 Object getSingleResult();

 /**
 * Retrieve a single result from the next result set.
 * The provider will call {@code execute} on the query
 * if needed.
 * A {@code REF_CURSOR} result set, if any, is retrieved
 * in the order the {@code REF_CURSOR} parameter was
 * registered with the query.
 * @return the result or null if the next item is not a result set
 * or if there is no result in the next result set
 * @throws NonUniqueResultException if more than one result
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws PersistenceException if the query execution exceeds
 * the query timeout value set and the transaction
 * is rolled back
 */
 Object getSingleResultOrNull();

 /**
 * Return true if the next result corresponds to a result set,
 * and false if it is an update count or if there are no results
 * other than through INOUT and OUT parameters, if any.
 * @return true if next result corresponds to result set
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 boolean hasMoreResults();

 /**
 * Return the update count or -1 if there is no pending result
 * or if the next result is not an update count.
 * @return update count or -1 if there is no pending result or if
 * the next result is not an update count
 * @throws QueryTimeoutException if the query execution exceeds
 * the query timeout value set and only the statement is
 * rolled back
 * @throws PersistenceException if the query execution exceeds 
 * the query timeout value set and the transaction
 * is rolled back
 */
 int getUpdateCount();

}

B.9. Tuple

import java.util.List;

/**
 * Interface for extracting the elements of a query result tuple.
 *
 * @see TupleElement
 *
 * @since 2.0
 */
public interface Tuple {

 /**
 * Get the value of the specified tuple element.
 * @param tupleElement tuple element
 * @return value of tuple element
 * @throws IllegalArgumentException if tuple element
 * does not correspond to an element in the
 * query result tuple
 */
 <X> X get(TupleElement<X> tupleElement);

 /**
 * Get the value of the tuple element to which the
 * specified alias has been assigned.
 * @param alias alias assigned to tuple element
 * @param type of the tuple element
 * @return value of the tuple element
 * @throws IllegalArgumentException if alias
 * does not correspond to an element in the
 * query result tuple or element cannot be
 * assigned to the specified type
 */
 <X> X get(String alias, Class<X> type); 

 /**
 * Get the value of the tuple element to which the
 * specified alias has been assigned.
 * @param alias alias assigned to tuple element
 * @return value of the tuple element
 * @throws IllegalArgumentException if alias
 * does not correspond to an element in the
 * query result tuple
 */
 Object get(String alias); 

 /**
 * Get the value of the element at the specified
 * position in the result tuple. The first position
 * is 0.
 * @param i position in result tuple
 * @param type type of the tuple element
 * @return value of the tuple element
 * @throws IllegalArgumentException if i exceeds
 * length of result tuple or element cannot
 * be assigned to the specified type
 */
 <X> X get(int i, Class<X> type);

 /**
 * Get the value of the element at the specified
 * position in the result tuple. The first position
 * is 0.
 * @param i position in result tuple
 * @return value of the tuple element
 * @throws IllegalArgumentException if i exceeds
 * length of result tuple
 */
 Object get(int i);

 /**
 * Return the values of the result tuple elements as
 * an array.
 * @return tuple element values
 */
 Object[] toArray();

 /**
 * Return the tuple elements.
 * @return tuple elements
 */
 List<TupleElement<?>> getElements();
}

B.10. TupleElement

/**
 * The {@code TupleElement} interface defines an element that is
 * returned in a query result tuple.
 * 
 * @param <X> the type of the element
 *
 * @see Tuple
 *
 * @since 2.0
 */
public interface TupleElement<X> {
 
 /**
 * Return the Java type of the tuple element.
 * @return the Java type of the tuple element
 */
 Class<? extends X> getJavaType();

 /**
 * Return the alias assigned to the tuple element or null, 
 * if no alias has been assigned.
 * @return alias
 */
 String getAlias();
}

B.11. Parameter

/**
 * Type for query parameter objects.
 *
 * @param <T> the type of the parameter
 *
 * @see Query
 * @see TypedQuery
 *
 * @since 2.0
 */
public interface Parameter<T> {

 /**
 * Return the parameter name, or null if the parameter is
 * not a named parameter or no name has been assigned.
 * @return parameter name
 */
 String getName();

 /**
 * Return the parameter position, or null if the parameter
 * is not a positional parameter. 
 * @return position of parameter
 */
 Integer getPosition();

 /**
 * Return the Java type of the parameter. Values bound to
 * the parameter must be assignable to this type.
 * This method is required to be supported for criteria
 * queries only. Applications that use this method for
 * Jakarta Persistence query language queries and native
 * queries will not be portable.
 * @return the Java type of the parameter
 * @throws IllegalStateException if invoked on a parameter
 * obtained from a query language query or native
 * query when the implementation does not support
 * this usage
 */
 Class<T> getParameterType();
}

B.12. Graph
