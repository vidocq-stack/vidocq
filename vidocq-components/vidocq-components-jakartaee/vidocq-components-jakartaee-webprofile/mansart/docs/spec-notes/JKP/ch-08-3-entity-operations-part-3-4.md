# Ch-08-3 Entity Operations (Part 3/4) — Requirements Note

## Attribute Converters

3. Every attribute converter class must implement the interface `jakarta.persistence.AttributeConverter`.
3. Every attribute converter class must be annotated with `Converter` or declared as a converter in the XML descriptor.
3. If `autoApply` of `Converter` is `true`, the converter is automatically applied to all attributes of the target type, including basic attribute values contained within more complex attribute types.
3. A converter class must be annotated `Converter` or declared as a converter in the object/relational mapping descriptor.
3. The persistence provider must not invoke attribute converters that depend upon CDI injection when CDI is not enabled.
3. In supporting injection into attribute converters, the persistence provider must behave as if it carries out the CDI lifecycle steps: obtain BeanManager, create AnnotatedType, create InjectionTarget, create CreationalContext, produce, inject, postConstruct (if any), preDestroy (if any), dispose, release.
3. The conversion of all basic types is supported except for: Id attributes (including embedded ids and derived identities), version attributes, relationship attributes, and attributes explicitly annotated as `Enumerated` or `Temporal` or designated as such in the XML descriptor.
3. Auto-apply converters will not be applied to excluded attribute types; applications that apply converters to such attributes through `Convert` will not be portable.
3. The persistence provider runtime must apply conversion methods to instances of attribute values in path expressions within Jakarta Persistence query language or criteria queries (comparisons, bulk updates, etc.) before sending them to the database.
3. When converted attributes are compared with literals or parameters in queries, the value of the literal or parameter must also be converted.
3. If the result of a Jakarta Persistence query language query or criteria query includes converted attributes, the persistence provider must apply the specified conversions to the corresponding values before returning them to the application.
3. The use of functions, including aggregates, on converted attributes is undefined.
3. If an exception is thrown from a conversion method, the persistence provider must wrap the exception in a `PersistenceException` and, if the persistence context is joined to a transaction, mark the transaction for rollback.

## Second-Level Cache

3.10. Whether a given entity is eligible for storage in the second-level cache is determined by: the annotations of the entity class, and the value specified for the `shared-cache-mode` element of `persistence.xml` or the configuration property `jakarta.persistence.sharedCache.mode`.
3.10. The property `jakarta.persistence.sharedCache.mode` takes precedence over the `shared-cache-mode` element.
3.10. If `NONE` is specified, caching is disabled for the persistence unit and the persistence provider must not cache any entity data.
3.10. If `ENABLE_SELECTIVE` is specified, an entity may be cached if the entity class is explicitly annotated `@Cacheable` or `@Cacheable(true)`, or if the equivalent setting is specified in XML.
3.10. If `DISABLE_SELECTIVE` is specified, an entity may be cached unless the entity class is explicitly annotated `@Cacheable(false)`, or unless the equivalent setting is specified in XML.
3.10. If neither `shared-cache-mode` nor `jakarta.persistence.sharedCache.mode` is specified, or if the value is `UNSPECIFIED`, the behavior is not defined and provider-specific defaults may apply; in particular, the semantics of `Cacheable` (and XML equivalent) is undefined.
3.10. If a second-level cache is supported and enabled, the provider must respect the configuration options defined in this section, if specified by the application.
3.10. Cache modes must be respected when caching is enabled, regardless of whether caching is enabled via the configuration options defined by this specification or via provider-specific mechanisms.

3.10.2. If second-level caching is not enabled (e.g., `shared-cache-mode` is `NONE`), cache modes must be ignored.
3.10.2. If a given entity is not eligible for storage in the second-level cache (e.g., `ENABLE_SELECTIVE` and entity is not annotated `@Cacheable`), cache modes are ignored for operations applying to that entity.
3.10.2. Applications which depend on cache retrieve mode or cache store mode but do not specify `shared-cache-mode` are not portable.

## Query APIs

3.11. For `TypedQuery` instances, the query result type is determined by: the type of the query specified when the `CriteriaQuery` object is created (criteria queries); or the `resultClass` argument to `createQuery`/`NamedQuery` (JPQL), where the select list must contain only a single item assignable to the specified type.
3.11. For `Query` instances, elements of a query result whose select list consists of more than one select expression are of type `Object[]`; if the select list consists of only one select expression, elements are of type `Object`.
3.11. An `IllegalArgumentException` is thrown if: a parameter instance does not correspond to a parameter of the query; a parameter name does not correspond to a named parameter of the query; a positional value does not correspond to a positional parameter of the query; or the type of the parameter is not valid for the query.
3.11. The effect of applying `setMaxResults` or `setFirstResult` to a query involving fetch joins over collections is undefined.
3.11. The use of `setMaxResults` and `setFirstResult` is not supported for stored procedure queries.
3.11. Query and `TypedQuery` methods other than `executeUpdate` are not required to be invoked within a transaction context, unless a lock mode other than `LockModeType.NONE` has been specified.
3.11. Runtime exceptions thrown by `getParameters`, `getParameter`, `getParameterValue`, `getOutputParameterValue`, `getLockMode` do not cause the current transaction to be marked for rollback.
3.11. Runtime exceptions thrown by methods of `Tuple`, `TupleElement`, and `Parameter` interfaces do not cause the current transaction to be marked for rollback.
3.11. On database platforms on which a query timeout causes transaction rollback, the persistence provider must throw `PersistenceException` instead of `QueryTimeoutException`.

3.11.2. When `FlushModeType.AUTO` is set on a query object, or if the flush mode for the persistence context is `AUTO` and no query-level flush mode is specified, the persistence provider must ensure that all updates to entity state which could potentially affect the query result are visible to the processing of the query.
3.11.2. If the persistence context has not been joined to the current transaction, the persistence provider must not flush to the database regardless of the flush mode setting.
3.11.2. If there is no transaction active, the persistence provider must not flush to the database.

3.11.3. If a lock mode other than `NONE` is specified for a query, the query must be executed within a transaction (and the persistence context must be joined to the transaction) or `TransactionRequiredException` will be thrown.
3.11.3. Locking is supported for Jakarta Persistence query language queries and criteria queries only. If `setLockMode` or `getLockMode` is invoked on a query that is not a JPQL select query or a criteria query, `IllegalStateException` may be thrown or query execution will fail.

3.11.4. The hint `jakarta.persistence.query.timeout` specifies time in milliseconds.

3.11.11.2. A SQL result set mapping must not be used to map results to the non-persistent state of an entity.
3.11.11.2. The use of named parameters is not defined for native SQL queries. Only positional parameter binding for SQL queries may be used by portable applications.

3.11.12.1. When a stored procedure returns one or more result sets and no `resultClasses` or `resultSetMappings` element has been specified, any result set will be returned as a list of type `Object[]`.
3.11.12.1. The order of specification of result set mappings must be the same as the order in which the result sets will be returned by the stored procedure invocation.
3.11.12.1. The combining of different strategies for the mapping of stored procedure result sets is undefined.
