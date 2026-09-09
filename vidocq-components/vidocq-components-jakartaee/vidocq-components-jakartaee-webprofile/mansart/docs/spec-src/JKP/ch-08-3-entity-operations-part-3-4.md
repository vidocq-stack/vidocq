# 3. Entity Operations (part 3/4)

A converter can be used to convert attributes defined by entity classes,
mapped superclasses, or embeddable classes.[54] A converted attribute is considered a basic attribute,
since, with the aid of the converter, its values can be represented as
instances of a basic type.

Every attribute converter class must implement the interface
jakarta.persistence.AttributeConverter and must be annotated with the
Converter annotation or declared as a converter in the XML descriptor.
If the value of the autoApply element of the Converter annotation is
true, the converter is automatically applied to all attributes of the
target type, including to basic attribute values that are contained within
other, more complex attribute types. See Section 10.6.

/**
 * Interface implemented by custom attribute <em>converters</em>. A
 * converter is a class whose methods convert between:
 * <ul>
 * <li>the <em>target type</em> of the converter, an arbitrary Java
 * type which may be used as the type of a persistent field or
 * property, and
 * <li>a {@linkplain Basic basic type} used as an intermediate step
 * in mapping to the database representation.
 * </ul>
 *
 * <p>A converted field or property is considered {@link Basic}, since,
 * with the aid of the converter, its values can be represented as
 * instances of a basic type.
 *
 * <p>A converter class must be annotated {@link Converter} or declared
 * as a converter in the object/relational mapping descriptor. The value
 * of {@link Converter#autoApply autoApply} determines if the converter
 * is automatically applied to persistent fields and properties of the
 * target type. The {@link Convert} annotation may be used to apply a
 * converter which is declared {@code autoApply=false}, to explicitly
 * {@linkplain Convert#disableConversion disable conversion}, or to
 * resolve ambiguities when multiple converters would otherwise apply.
 *
 * <p>Note that the target type {@code X} and the converted basic type
 * {@code Y} may be the same Java type.
 *
 * @param <X> the target type, that is, the type of the entity attribute
 * @param <Y> a basic type representing the type of the database column
 *
 * @see Converter
 * @see Convert#converter
 */
public interface AttributeConverter<X,Y> {

 /**
 * Converts the value stored in the entity attribute into the 
 * data representation to be stored in the database.
 *
 * @param attribute the entity attribute value to be converted
 * @return the converted data to be stored in the database column
 */
 Y convertToDatabaseColumn(X attribute);

 /**
 * Converts the data stored in the database column into the value
 * to be stored in the entity attribute.
 *
 * <p>Note that it is the responsibility of the converter writer
 * to specify the correct {@code dbData} type for the corresponding
 * column for use by the JDBC driver: i.e., persistence providers
 * are not expected to do such type conversion.
 *
 * @param dbData the data from the database column to be converted
 * @return the converted value to be stored in the entity attribute
 */
 X convertToEntityAttribute(Y dbData);
}

Attribute converter classes in Jakarta EE
environments support dependency injection through the Contexts and
Dependency Injection API (CDI) [7] when CDI is
enabled[55]. An attribute converter class that makes
use of CDI injection may also define lifecycle callback methods
annotated with the PostConstruct and PreDestroy annotations. These
methods will be invoked after injection has taken place and before the
attribute converter instance is destroyed respectively.

The persistence provider is responsible for
using the CDI SPI to create instances of the attribute converter class;
to perform injection upon such instances; to invoke their
PostConstruct and PreDestroy methods, if any; and to dispose of the
attribute converter instances.

The persistence provider is only required to
support CDI injection into attribute converters in Jakarta EE container
environments[56]. If CDI is not enabled, the
persistence provider must not invoke attribute converters that depend
upon CDI injection.

An attribute converter is a noncontextual
object. In supporting injection into attribute converters, the
persistence provider must behave as if it carries out the following
steps involving the use of the CDI SPI. (See
[7]).

Obtain a BeanManager instance. (See Section 9.1.)

Create an AnnotatedType instance for the attribute converter class.

Create an InjectionTarget instance for the annotated type.

Create a CreationalContext.

Instantiate the listener by calling the InjectionTarget produce method.

Inject the listener instance by calling the InjectionTarget inject method on the instance.

Invoke the PostConstruct callback, if any,
by calling the InjectionTarget postConstruct method on the instance.

When the listener instance is to be
destroyed, the persistence provider must behave as if it carries out the
following steps.

Call the InjectionTarget preDestroy method on the instance.

Call the InjectionTarget dispose method on the instance.

Call the CreationalContext release method.

Persistence providers may optimize the steps
above, e.g. by avoiding calls to the actual CDI SPI and relying on
container-specific interfaces instead, as long as the outcome is the
same.

Attribute converters that do not make use of
CDI injection are stateless. The lifecycle of such attribute converters
is unspecified.

The conversion of all basic types is
supported except for the following: Id attributes (including the
attributes of embedded ids and derived identities), version attributes,
relationship attributes, and attributes explicitly annotated as
Enumerated or Temporal or designated as such in the XML descriptor.
Auto-apply converters will not be applied to such attributes, and
applications that apply converters to such attributes through use of the
Convert annotation will not be portable.

Type conversion may be specified at the level
of individual attributes by means of the Convert annotation. The
Convert annotation may also be used to override or disable an
auto-apply conversion. See Section 11.1.10.

The Convert annotation may be applied
directly to an attribute of an entity, mapped superclass, or embeddable
class to specify conversion of the attribute or to override the use of a
converter that has been specified as autoApply=true. When persistent
properties are used, the Convert annotation is applied to the getter
method.

The Convert annotation may be applied to an
entity that extends a mapped superclass to specify or override the
conversion mapping for an inherited basic or embedded attribute.

The persistence provider runtime is
responsible for invoking the specified conversion methods for the target
attribute type when loading the entity attribute from the database and
before storing the entity attribute state to the database. The
persistence provider must apply any conversion methods to instances of
attribute values in path expressions used within Jakarta Persistence query
language queries or criteria queries (such as in comparisons, bulk
updates, etc.) before sending them to the database for the query
execution. When such converted attributes are used in comparison
operations with literals or parameters, the value of the literal or
parameter to which they are compared must also be converted. If the
result of a Jakarta Persistence query language query or criteria query
includes one or more entity attributes for which conversion mappings
have been specified, the persistence provider must apply the specified
conversions to the corresponding values in the query result before
returning them to the application. The use of functions, including
aggregates, on converted attributes is undefined. If an exception is
thrown from a conversion method, the persistence provider must wrap the
exception in a PersistenceException and, if the persistence context is
joined to a transaction, mark the transaction for rollback.

3.10. Second-Level Cache

A persistence provider may support the use of a second-level cache,
that is, it might have a way to store data read in one persistence context
for use in subsequent persistence contexts. A second-level cache might
enhance performance, but tends to undermine the semantics of transaction
processing, possibly exposing the application to stale data or similar
anomalies.

Access to the second-level cache, if enabled, is mediated via the
persistence context, and is largely transparent to the application.
As an exception, the Cache interface described below in Section 3.10.3
allows the application to directly evict data from the second-level cache.

The persistence provider is not required to support use of a second-level
cache.

3.10.1. The Shared Cache Mode and Cacheable Annotation

Whether a given entity is eligible for storage in the second level cache
is determined by:

the annotations of the entity class, and

the value specified for the shared-cache-mode element of the
persistence.xml file or by the configuration property
jakarta.persistence.sharedCache.mode.

The value of the property jakarta.persistence.sharedCache.mode takes
precedence over the value of the shared-cache-mode element.

The shared-cache-mode element takes one of five possible values,
which are enumerated by jakarta.persistence.SharedCacheMode:

ALL specifies that every entity and all its state may be cached.

NONE specifies that caching is disabled for the persistence unit,
and that the persistence provider must not cache any entity data.

ENABLE_SELECTIVE specifies that an entity may be cached if the
entity class is explicitly annotated @Cacheable or @Cacheable(true),
or if the equivalent setting is specified in XML.

DISABLE_SELECTIVE specifies that an entity may be cached unless
the entity class is explicitly annotated @Cacheable(false), or
unless the equivalent setting is specified in XML.

UNSPECIFIED selects the provider-specific default behavior.

If neither the shared-cache-mode element nor the property
jakarta.persistence.sharedCache.mode is specified, or if the specified
value is UNSPECIFIED, the behavior is not defined, and provider-specific
defaults may apply. In particular, the semantics of the Cacheable
annotation (and XML equivalent) is undefined.

If the persistence provider does not support use of a second-level cache,
or if a second-level cache is not installed or not enabled, this setting
may be ignored and no caching will occur.

A persistence provider may support additional vendor-specific mechanisms
for configuring the cache and marking entities eligible (or not) for
storage in the second-level cache. However, if a second-level cache is
supported, and enabled, the provider must respect the configuration options
defined in this section, if specified by the application.

3.10.2. Cache Modes

The cache retrieve mode and cache store mode control how a given
persistence context by interacts with the second-level cache.

The cache retrieve mode may be set by calling setCacheRetrieveMode()
on EntityManager or Query.

The cache store mode may be set by calling setCacheStoreMode() on
EntityManager or Query.

A cache store mode or cache retrieve mode, or both, may be passed to
the find() method of EntityManager as a FindOption.

A cache store mode may be passed to the refresh() method of
EntityManager as a RefreshOption.

A cache mode specified for a given Query instance applies only to
executions of that query, but takes precedence over the current cache
mode of the EntityManager to which the Query belongs. A cache mode
passed to find() or refresh() applies only to the method invocation,
and takes precedence over the current cache mode of the EntityManager.

Alternatively, a cache mode may be specified using the property name
jakarta.persistence.cache.retrieveMode or
jakarta.persistence.cache.storeMode by:

calling the setProperty() method of EntityManager,

calling the setHint() method of Query, or

passing a map containing one of these properties to find() or refresh().

If second-level caching is not enabled (for example, if the
shared-cache-mode element is set to NONE), cache modes must be
ignored. Similarly, if a given entity is not eligible for storage in
the second-level cache (for example, if the shared-cache-mode element
is set to ENABLE_SELECTIVE, and the entity is not annotated @Cacheable),
cache modes are ignored for operations applying to that entity.

Cache modes must be respected when caching is enabled, regardless of
whether caching is enabled via the configuration options defined by this
specification or via provider-specific mechanisms.

Applications which depend on the cache retrieve mode or cache store mode
but which do not specify the shared-cache-mode element are not portable.

CacheRetrieveMode enumerates the cache retrieve modes recognized by this
specification. The semantics of each mode is defined by its Javadoc.

/**
 * Specifies how the {@link EntityManager} interacts with the
 * second-level cache when data is read from the database via
 * the {@link EntityManager#find} methods and execution of
 * queries.
 * <ul>
 * <li>{@link #USE} indicates that data may be read from the
 * second-level cache.
 * <li>{@link #BYPASS} indicates that data may not be read
 * from the second-level cache.
 * </ul>
 *
 * <p>Enumerates legal values of the property
 * {@code jakarta.persistence.cache.retrieveMode}.
 *
 * @see EntityManager#setCacheRetrieveMode(CacheRetrieveMode)
 * @see Query#setCacheRetrieveMode(CacheRetrieveMode)
 *
 * @since 2.0
 */
public enum CacheRetrieveMode implements FindOption {

 /**
 * Read entity data from the cache: this is the default
 * behavior.
 */
 USE,

 /**
 * Bypass the cache: get data directly from the database.
 */
 BYPASS 
}

CacheStoreMode enumerates the cache store modes recognized by this
specification. The semantics of each mode is defined by its Javadoc.

/**
 * Specifies how the {@link EntityManager} interacts with the
 * second-level cache when data is read from the database and
 * when data is written to the database.
 * <ul>
 * <li>{@link #USE} indicates that data may be written to the
 * second-level cache.
 * <li>{@link #BYPASS} indicates that data may not be written
 * to the second-level cache.
 * <li>{@link #REFRESH} indicates that data must be written
 * to the second-level cache, even when the data is already
 * cached.
 * </ul>
 *
 * <p>Enumerates legal values of the property
 * {@code jakarta.persistence.cache.storeMode}.
 *
 * @see EntityManager#setCacheStoreMode(CacheStoreMode)
 * @see Query#setCacheStoreMode(CacheStoreMode)
 *
 * @since 2.0
 */
public enum CacheStoreMode implements FindOption, RefreshOption {

 /**
 * Insert entity data into cache when read from database and
 * insert/update entity data when written to the database:
 * this is the default behavior. Does not force refresh of
 * already cached items when reading from database.
 */
 USE,

 /**
 * Don't insert into cache. 
 */
 BYPASS,

 /**
 * Insert/update entity data held in the cache when read from
 * the database and when written to the database. Force refresh
 * of cache for items read from database.
 */
 REFRESH
}

3.10.3. Cache Interface

The Cache interface found in Section B.5 allows the application to
request eviction of entity data from the second-level cache directly
and immediately, outside the scope of any persistence context.

3.11. Query APIs

The Query and TypedQuery APIs are used
for the execution of both static queries and dynamic queries. These APIs
also support parameter binding and pagination control. The
StoredProcedureQuery API is used for the execution of queries that
invoke stored procedures defined in the database.

These interfaces may be found in Appendix B.

3.11.1. Query Execution

Jakarta Persistence query language, Criteria API, and native SQL
select queries are executed using the methods getResultList,
getSingleResult, and getSingleResultOrNull.
Update and delete operations (update and delete “queries”) are
executed using the executeUpdate method.

For TypedQuery instances, the query result
type is determined in the case of criteria queries by the type of the
query specified when the CriteriaQuery object is created, as described
in Section 6.3.1. In the case of Jakarta Persistence query language queries, the type of the
result is determined by the resultClass argument to the createQuery
or createNamedQuery method, and the select list of the query must
contain only a single item which must be assignable to the specified
type.

For Query instances, the elements of a
query result whose select list consists of more than one select
expression are of type Object[]. If the select list consists of only
one select expression, the elements of the query result are of type
Object. When native SQL queries are used, the SQL result set mapping
(see Section 3.11.11), determines
how many items (entities, scalar values, etc.) are returned. If multiple
items are returned, the elements of the query result are of type
Object[]. If only a single item is returned as a result of the SQL
result set mapping or if a result class is specified, the elements of
the query result are of type Object.

Stored procedure queries can be executed using the getResultList,
getSingleResult, getSingleResultOrNull, and execute methods.
Stored procedures that perform only updates or deletes can be executed
using the executeUpdate method. Stored procedure query execution is
described in detail in Section 3.11.12.3.

An IllegalArgumentException is thrown if a
parameter instance is specified that does not correspond to a parameter
of the query, if a parameter name is specified that does not correspond
to a named parameter of the query, if a positional value is specified
that does not correspond to a positional parameter of the query, or if
the type of the parameter is not valid for the query. This exception may
be thrown when the parameter is bound, or the execution of the query may
fail. See Section 3.11.5, Section 3.11.6, and Section 3.11.7 for supported
parameter usage.

The effect of applying setMaxResults or
setFirstResult to a query involving fetch joins over collections is
undefined. The use of setMaxResults and setFirstResult is not
supported for stored procedure queries.

Query and TypedQuery methods other than
the executeUpdate method are not required to be invoked within a
transaction context, unless a lock mode other than LockModeType.NONE
has been specified for the query. In particular, the getResultList,
getSingleResult, and getSingleResultOrNull methods are not required
to be invoked within a transaction context unless such a lock mode has
been specified for the query[57]. If an entity manager with
transaction-scoped persistence context is in use, the resulting entities
will be detached; if an entity manager with an extended persistence
context is used, they will be managed. See Chapter 7 for further
discussion of entity manager use outside a transaction and persistence context types.

Whether a StoredProcedureQuery should be
invoked in a transaction context should be determined by the
transactional semantics and/or requirements of the stored procedure
implementation and the database in use. In particular, problems may
occur if the stored procedure initiates a transaction and a transaction
is already in effect. The state of any entities returned by the stored
procedure query invocation is determined as decribed above.

Runtime exceptions other than the
NoResultException, NonUniqueResultException,
QueryTimeoutException, and LockTimeoutException thrown by the
methods of the Query, TypedQuery, and StoredProcedureQuery
interfaces other than those methods specified below cause the current
transaction to be marked for rollback if the persistence context is
joined to the transaction. On database platforms on which a query
timeout causes transaction rollback, the persistence provider must throw
the PersistenceException instead of the QueryTimeoutException.

Runtime exceptions thrown by the following
methods of the Query, TypedQuery, and StoredProcedureQuery
interfaces do not cause the current transaction to be marked for
rollback: getParameters, getParameter, getParameterValue,
getOutputParameterValue, getLockMode.

Runtime exceptions thrown by the methods of
the Tuple, TupleElement, and Parameter interfaces do not cause
the current transaction to be marked for rollback.

For example:

public List findWithName(String name) {
 return em.createQuery("SELECT c FROM Customer c WHERE c.name LIKE :custName")
 .setParameter("custName", name)
 .setMaxResults(10)
 .getResultList();
}

3.11.2. Queries and Flush Mode

The flush mode setting affects the result of
a query as follows.

When queries are executed within a
transaction, if FlushModeType.AUTO is set on the Query,
TypedQuery, or StoredProcedureQuery object, or if the flush mode
setting for the persistence context is AUTO (the default) and a flush
mode setting has not been specified for the query object, the
persistence provider is responsible for ensuring that all updates to the
state of all entities in the persistence context which could potentially
affect the result of the query are visible to the processing of the
query. The persistence provider implementation may achieve this by
flushing those entities to the database or by some other means. If
FlushModeType.COMMIT is set, the effect of updates made to entities in
the persistence context upon queries is unspecified.

If the persistence context has not been
joined to the current transaction, the persistence provider must not
flush to the database regardless of the flush mode setting.

/**
 * Enumerates flush modes recognized by the {@link EntityManager}.
 *
 * <p>When queries are executed within a transaction, if {@link #AUTO}
 * is set on the {@link Query Query} or {@link TypedQuery} object, or
 * if the flush mode setting for the persistence context is {@code AUTO}
 * (the default) and a flush mode setting has not been specified for the
 * {@code Query} or {@code TypedQuery} object, the persistence provider
 * is responsible for ensuring that all updates to the state of all
 * entities in the persistence context which could potentially affect
 * the result of the query are visible to the processing of the query.
 * The persistence provider implementation may achieve this by flushing
 * updates to those entities to the database or by some other means.
 *
 * <p>On the other hand, if {@link #COMMIT} is set, the effect of updates
 * made to entities in the persistence context on queries is unspecified.
 *
 * <p>If there is no transaction active or the persistence context is
 * not joined to the current transaction, the persistence provider must
 * not flush to the database.
 *
 * @see EntityManager#setFlushMode(FlushModeType)
 * @see Query#setFlushMode(FlushModeType)
 *
 * @since 1.0
 */
public enum FlushModeType {

 /**
 * Flushing to occur at transaction commit. The provider may flush
 * at other times, but is not required to.
 */
 COMMIT,

 /**
 * (Default) Flushing to occur at query execution.
 */
 AUTO
}

If there is no transaction active, the
persistence provider must not flush to the database.

3.11.3. Queries and Lock Mode

The setLockMode method of the
Query or TypedQuery interface or the lockMode element of the
NamedQuery annotation may be used to lock the results of a query. A
lock is obtained for each entity specified in the query result
(including entities passed to constructors in the query SELECT
clause).[58]

If the lock mode type is PESSIMISTIC_READ,
PESSIMISTIC_WRITE, or PESSIMISTIC_FORCE_INCREMENT, and the query
returns scalar data (e.g., the values of entity field or properties,
including scalar data passed to constructors in the query SELECT
clause), the underlying database rows will be
locked[59], but the version columns (if any) for any
entities corresponding to such scalar data will not be updated unless
the entities themselves are also otherwise retrieved and updated.

If the lock mode type is OPTIMISTIC or
OPTIMISTIC_FORCE_INCREMENT, and the query returns scalar data, any
entities returned by the query will be locked, but no locking will occur
for scalar data that does not correspond to the state of any entity
instance in the query result.

If a lock mode other than NONE is specified
for a query, the query must be executed within a transaction (and the
persistence context must be joined to the transaction) or the
TransactionRequiredException will be thrown.

Locking is supported for Jakarta Persistence
query language queries and criteria queries only. If the setLockMode
or getLockMode method is invoked on a query that is not a Jakarta
Persistence query language select query or a criteria query, the
IllegalStateException may be thrown or the query execution will fail.

3.11.4. Query Hints

The following hint is defined by this specification for use in query configuration.

jakarta.persistence.query.timeout // time in milliseconds

This hint may:

be passed to the setHint() method of the Query, TypedQuery,
and StoredProcedureQuery interfaces found in Appendix B,

used with the NamedQuery, NamedNativeQuery, and
NamedStoredProcedureQuery annotations specified in Section 10.4,

passed as a property to the createEntityManagerFactory() method
of the Persistence class, as defined in Section 9.7, or

used in the properties element of the persistence.xml file, as
defined in Section 8.2.1.11.

The timeout specified by calling the createEntityManagerFactory()
method, via the persistence.xml file, or in annotations, serves as
a default value which can be selectively overridden by calling the
setHint() method.

Portable applications should not rely on this
hint. Depending on the persistence provider and database in use, the
hint may or may not be observed.

Vendors are permitted to support the use of
additional, vendor-specific hints. Vendor-specific hints must not use
the jakarta.persistence namespace. Vendor-specific hints must be ignored
if they are not understood.

3.11.5. Parameter Objects

Parameter objects can be used for criteria
queries and for Jakarta Persistence query language queries.

Implementations may support the use of
Parameter objects for native queries, however support for Parameter
objects with native queries is not required by this specification. The
use of Parameter objects for native queries will not be portable. The
mixing of parameter objects with named or positional parameters is invalid.

Portable applications should not attempt to
reuse a Parameter object obtained from a Query or TypedQuery
instance in the context of a different Query or TypedQuery instance.

3.11.6. Named Parameters

Named parameters can be used for Jakarta
Persistence query language queries, for criteria queries (although use
of Parameter objects is to be preferred), and for stored procedure
queries that support named parameters.

Named parameters follow the rules for
identifiers defined in Section 4.4.1.
Named parameters are case-sensitive. The mixing of named and positional
parameters is invalid.

A named parameter of a Jakarta Persistence query
language query is an identifier that is prefixed by the " : " symbol.
The parameter names passed to the setParameter methods of the Query
and TypedQuery interfaces do not include this " : " prefix.

3.11.7. Positional Parameters

Only positional parameter binding and
positional access to result items may be portably used for native
queries, except for stored procedure queries for which named parameters
have been defined. When binding the values of positional parameters, the
numbering starts as “ 1 ”. It is assumed that for native queries the
parameters themselves use the SQL syntax (i.e., “ ? ”, rather than “
?1 ”).

The use of positional parameters is not supported for criteria queries.

3.11.8. Arguments to query parameters

Arguments are assigned to query parameters by calling Query.setParameter().
The first parameter of setParameter() identifies the named or positional
parameter of the query.

An argument may be assigned to a single-valued parameter of a JPQL or
native SQL query by passing the argument to the second parameter of
setParameter().

query.setParameter("name", name)

A list of arguments may be assigned to a collection-valued parameter
of a JPQL query by packaging the arguments in a non-null instance of
java.util.List and passing the list as an argument to the second
parameter of setParameter(). The list should contain at least one
element. If the list is empty the behavior is undefined. Portable
applications should not pass an empty list to a collection-valued
parameter.

query.setParameter("names", List.of(name1, name2, name3))

3.11.9. Named Queries

Named queries are static queries expressed in
metadata or queries registered by means of the EntityManagerFactory
addNamedQuery method. Named queries can be defined in the Jakarta
Persistence query language or in SQL. Query names are scoped to the
persistence unit.

The following is an example of the definition
of a named query defined in metadata:

@NamedQuery(
 name="findAllCustomersWithName",
 query="SELECT c FROM Customer c WHERE c.name LIKE :custName"
)

The following is an example of the use of a named query:

@PersistenceContext
public EntityManager em;
 // ...

 customers = em.createNamedQuery("findAllCustomersWithName")
 .setParameter("custName", "Smith")
 .getResultList();

3.11.10. Polymorphic Queries

By default, all queries are polymorphic. That
is, the FROM clause of a query designates not only instances of the
specific entity class(es) to which it explicitly refers, but subclasses
as well. The instances returned by a query include instances of the
subclasses that satisfy the query conditions.

For example, the following query returns the
average salary of all employees, including subtypes of Employee, such
as Manager and Exempt.

select avg(e.salary) from Employee e where e.salary > 80000

Entity type expressions, described in Section 4.7.12, as well as the
use of downcasting, described in Section 4.4.9, can be used to restrict query polymorphism.

3.11.11. SQL Queries

Queries may be expressed in native SQL. The
result of a native SQL query may consist of entities, unmanaged
instances created via constructors, scalar values, or some combination
of these.

The SQL query facility is intended to provide
support for those cases where it is necessary to use the native SQL of
the target database in use (and/or where the Jakarta Persistence query
language cannot be used). Native SQL queries are not expected to be
portable across databases.

3.11.11.1. Returning Managed Entities from Native Queries

The persistence provider is responsible for
performing the mapping between the values returned by the SQL query and
entity attributes in accordance with the object/relational mapping
metadata for the entity or entities. In particular, the names of the
columns in the SQL result are used to map to the entity attributes as
defined by this metadata. This mapping includes the mapping of the
attributes of any embeddable classes that are part of the
non-collection-valued entity state and attributes corresponding to
foreign keys contained as part of the entity
state[60].

When an entity is to be returned from a
native query, the SQL statement should select all of the columns that
are mapped to the entity object. This should include foreign key columns
to related entities. The results obtained when insufficient data is
available are undefined.

In the simplest case—i.e., when the results
of the query are limited to entities of a single entity class and the
mapping information can be derived from the columns of the SQL result
and the object/relational mapping metadata—it is sufficient to specify
only the expected class of the entity result.

The following example illustrates the case
where a native SQL query is created dynamically using the
createNativeQuery method and the entity class that specifies the type
of the result is passed in as an argument.

Query q = em.createNativeQuery(
 "SELECT o.id, o.quantity, o.item " +
 "FROM Order o, Item i " +
 "WHERE (o.item = i.id) AND (i.name = 'widget')",
 com.acme.Order.class);

When executed, this query will return a
collection of all Order entities for items named “widget”.

The SqlResultSetMapping metadata
annotation—which is designed to handle more complex cases—can be used as
an alternative here. See Section 10.4.4 for the definition of the
SqlResultSetMapping metadata annotation and related annotations.

For the query shown above, the
SqlResultSetMapping metadata for the query result type might be
specified as follows:

@SqlResultSetMapping(
 name="WidgetOrderResults",
 entities=@EntityResult(entityClass=com.acme.Order.class))

The same results as produced by the query
above can then obtained by the following:

Query q = em.createNativeQuery(
 "SELECT o.id, o.quantity, o.item " +
 "FROM Order o, Item i " +
 "WHERE (o.item = i.id) AND (i.name = 'widget')",
 "WidgetOrderResults");

When multiple entities are returned by a SQL
query or when the column names of the SQL result do not correspond to
those of the object/relational mapping metadata, a SqlResultSetMapping
metadata definition must be provided to specify the entity mapping.

The following query and SqlResultSetMapping
metadata illustrates the return of multiple entity types. It assumes
default metadata and column name defaults.

Query q = em.createNativeQuery(
 "SELECT o.id, o.quantity, o.item, i.id, i.name, i.description " +
 "FROM Order o, Item i " +
 "WHERE (o.quantity > 25) AND (o.item = i.id)",
 "OrderItemResults");

@SqlResultSetMapping(name="OrderItemResults", entities={
 @EntityResult(entityClass=com.acme.Order.class),
 @EntityResult(entityClass=com.acme.Item.class)
})

When the column names of the SQL result do
not correspond to those of the object/relational mapping metadata
or introduce a conflict in mapping column defaults as in the example code above,
more explicit SQL result mapping metadata must be provided to enable the
persistence provider runtime to map the JDBC results into the expected
objects. This might arise, for example, when column aliases must be used
in the SQL SELECT clause when the SQL result would otherwise contain
multiple columns of the same name or when columns in the SQL result are
the results of operators or functions. The FieldResult annotation
element within the EntityResult annotation is used to specify the
mapping of such columns to entity attributes.

The following example combining multiple
entity types includes aliases in the SQL statement. This requires that
the column names be explicitly mapped to the entity fields corresponding
to those columns. The FieldResult annotation is used for this purpose.

Query q = em.createNativeQuery(
 "SELECT o.id AS order_id, " +
 "o.quantity AS order_quantity, " +
 "o.item AS order_item, " +
 "i.id, i.name, i.description " +
 "FROM Order o, Item i " +
 "WHERE (order_quantity > 25) AND (order_item = i.id)",
 "OrderItemResults");

@SqlResultSetMapping(name="OrderItemResults", entities={
 @EntityResult(entityClass=com.acme.Order.class, fields={
 @FieldResult(name="id", column="order_id"),
 @FieldResult(name="quantity", column="order_quantity"),
 @FieldResult(name="item", column="order_item")}),
 @EntityResult(entityClass=com.acme.Item.class)
})

When the returned entity type contains an
embeddable class, the FieldResult element must use a dot (“ . ”)
notation to indicate which column maps to which field or property of the
contained embeddable.

Example:

Query q = em.createNativeQuery(
 "SELECT c.id AS customer_id, " +
 "c.street AS customer_street, " +
 "c.city AS customer_city, " +
 "c.state AS customer_state, " +
 "c.status AS customer_status " +
 "FROM Customer c " +
 "WHERE c.status = 'GOLD' ",
 "CustomerResults");

@SqlResultSetMapping(name=”CustomerResults”, entities={
 @EntityResult(entityClass=com.acme.Customer.class, fields={
 @FieldResult(name="id", column="customer_id"),
 @FieldResult(name="address.street", column="customer_street"),
 @FieldResult(name="address.city", column="customer_city"),
 @FieldResult(name="address.state", column="customer_state"),
 @FieldResult(name="status", column="customer_status")
 })
})

When the returned entity type is the owner of
a single-valued relationship and the foreign key is a composite foreign
key (composed of multiple columns), a FieldResult element should be
used for each of the foreign key columns. The FieldResult element must
use the dot (“ . ”) notation form to indicate the column that maps to
each property or field of the target entity primary key.

If the target entity has a primary key of
type IdClass, this specification takes the form of the name of the
field or property for the relationship, followed by a dot (“ . ”),
followed by the name of the field or property of the primary key in the
target entity. The latter will be annotated with Id, as specified in
Section 11.1.23.

Example:

Query q = em.createNativeQuery(
 "SELECT o.id AS order_id, " +
 "o.quantity AS order_quantity, " +
 "o.item_id AS order_item_id, " +
 "o.item_name AS order_item_name, " +
 "i.id, i.name, i.description " +
 "FROM Order o, Item i " +
 "WHERE (order_quantity > 25) AND (order_item_id = i.id) " +
 "AND (order_item_name = i.name)",
 "OrderItemResults");

@SqlResultSetMapping(name="OrderItemResults", entities={
 @EntityResult(entityClass=com.acme.Order.class, fields={
 @FieldResult(name="id", column="order_id"),
 @FieldResult(name="quantity", column="order_quantity"),
 @FieldResult(name="item.id", column="order_item_id")}),
 @FieldResult(name="item.name", column="order_item_name")}),
 @EntityResult(entityClass=com.acme.Item.class)
})

If the target entity has a primary key of
type EmbeddedId, this specification is composed of the name of the
field or property for the relationship, followed by a dot (“ . ”),
followed by the name or the field or property of the primary key (i.e.,
the name of the field or property annotated as EmbeddedId), followed
by the name of the corresponding field or property of the embedded
primary key class.

Example:

Query q = em.createNativeQuery(
 "SELECT o.id AS order_id, " +
 "o.quantity AS order_quantity, " +
 "o.item_id AS order_item_id, " +
 "o.item_name AS order_item_name, " +
 "i.id, i.name, i.description " +
 "FROM Order o, Item i " +
 "WHERE (order_quantity > 25) AND (order_item_id = i.id) AND (order_item_name = i.name)",
 "OrderItemResults");

@SqlResultSetMapping(name="OrderItemResults", entities={
 @EntityResult(entityClass=com.acme.Order.class, fields={
 @FieldResult(name="id", column="order_id"),
 @FieldResult(name="quantity", column="order_quantity"),
 @FieldResult(name="item.itemPk.id", column="order_item_id")}),
 @FieldResult(name="item.itemPk.name", column="order_item_name")}),
 @EntityResult(entityClass=com.acme.Item.class)
})

The FieldResult elements for the composite
foreign key are combined to form the primary key EmbeddedId class for
the target entity. This may then be used to subsequently retrieve the
entity if the relationship is to be eagerly loaded.

The dot-notation form is not required to be
supported for any usage other than for embeddables, composite foreign
keys, or composite primary keys.

3.11.11.2. Returning Unmanaged Instances

Instances of other classes (including
non-managed entity instances) as well as scalar results can be returned
by a native query. These can be used singly, or in combination,
including with entity results.

Scalar Results

Scalar results can be included in the query
result by specifying the ColumnResult annotation element of the
SqlResultSetMapping annotation. The intended type of the result can be
specified using the type element of the ColumnResult annotation.

Query q = em.createNativeQuery(
 "SELECT o.id AS order_id, " +
 "o.quantity AS order_quantity, " +
 "o.item AS order_item, " +
 "i.name AS item_name, " +
 "i.availabilityDate AS item_shipdate " +
 "FROM Order o, Item i " +
 "WHERE (order_quantity > 25) AND (order_item = i.id)",
 "OrderResults");

@SqlResultSetMapping(
 name="OrderResults",
 entities={
 @EntityResult(entityClass=com.acme.Order.class, fields={
 @FieldResult(name="id", column="order_id"),
 @FieldResult(name="quantity", column="order_quantity"),
 @FieldResult(name="item", column="order_item")}
 )},
 columns={
 @ColumnResult(name="item_name"),
 @ColumnResult(name="item_shipdate", type=java.util.Date.class)
 })

Constructor Results

The mapping to constructors is specified
using the ConstructorResult annotation element of the
SqlResultSetMapping annotation. The targetClass element of the
ConstructorResult annotation specifies the class whose constructor
corresponds to the specified columns. All columns corresponding to
arguments of the intended constructor must be specified using the
columns element of the ConstructorResult annotation in the same
order as that of the argument list of the constructor. Any entities
returned as constructor results will be in either the new or the
detached state, depending on whether a primary key is retrieved for the
constructed object.

Example:

Query q = em.createNativeQuery(
 "SELECT c.id, c.name, COUNT(o) as orderCount, AVG(o.price) AS avgOrder " +
 "FROM Customer c, Orders o " +
 "WHERE o.cid = c.id " +
 "GROUP BY c.id, c.name",
 "CustomerDetailsResult");

@SqlResultSetMapping(name="CustomerDetailsResult", classes={
 @ConstructorResult(targetClass=com.acme.CustomerDetails.class, columns={
 @ColumnResult(name="id"),
 @ColumnResult(name="name"),
 @ColumnResult(name="orderCount"),
 @ColumnResult(name="avgOrder", type=Double.class)})
})

3.11.11.3. Combinations of Result Types

When a SqlResultSetMapping specifies more
than one mapping type (i.e., more than one of EntityResult,
ConstructorResult, ColumnResult), then for each row in the SQL
result, the query execution will result in an Object[] instance whose
elements are as follows, in order: any entity results (in the order in
which they are defined in the entities element); any instances of
classes corresponding to constructor results (in the order defined in
the classes element); and any instances corresponding to column
results (in the order defined in the columns element). If there are
any columns whose result mappings have not been specified, they are
ignored.

3.11.11.4. Restrictions

When an entity is being returned, the SQL
statement should select all of the columns that are mapped to the entity
object. This should include foreign key columns to related entities. The
results obtained when insufficient data is available are undefined. A
SQL result set mapping must not be used to map results to the
non-persistent state of an entity.

The use of named parameters is not defined
for native SQL queries. Only positional parameter binding for SQL
queries may be used by portable applications.

3.11.12. Stored Procedures

The StoredProcedureQuery interface supports
the use of database stored procedures.

Stored procedures can be specified either by
means of the NamedStoredProcedureQuery annotation or dynamically.
Annotations for the specification of stored procedures are described in
Section 10.4.3.

3.11.12.1. Named Stored Procedure Queries

Unlike in the case of a named native query,
the NamedStoredProcedureQuery annotation names a stored procedure that
exists in the database rather than providing a stored procedure
definition. The NamedStoredProcedureQuery annotation specifies the
types of all parameters to the stored procedure, their corresponding
parameter modes (IN, OUT, INOUT, REF_CURSOR[61]), and
how result sets, if any, are to be mapped. The name that is assigned to
the stored procedure in the NamedStoredProcedureQuery annotation is
passed as an argument to the createNamedStoredProcedureQuery method to
create an executable StoredProcedureQuery object.

A stored procedure may return more than one
result set. As with native queries, the mapping of result sets can be
specified either in terms of a resultClasses or as a
resultSetMappings annotation element. If there are multiple result
sets, it is assumed that they will be mapped using the same mechanism —
e.g., all via a set of result class mappings or all via a set of result
set mappings. The order of the specification of these mappings must be
the same as the order in which the result sets will be returned by the
stored procedure invocation. If the stored procedure returns one or more
result sets and no resultClasses or resultSetMappings element has
been specified, any result set will be returned as a list of type
Object[]. The combining of different strategies for the mapping of
stored procedure result sets is undefined.
