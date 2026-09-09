# Ch-19-10: Metadata Annotations — Normative Requirements

## Section 10

- 10 — These annotations and types are in the package `jakarta.persistence`.
- 10 — The XML schema defined in chapter 12 provides an alternative to the use of metadata annotations.

## Section 10.1 (Entity)

- 10.1 — The `name` annotation element specifies the entity name.
- 10.1 — If the `name` element is not specified, the entity name defaults to the unqualified name of the entity class.
- 10.1 — This name is used to refer to the entity in queries.

## Section 10.2 (Callback Annotations)

- 10.2 — The `EntityListeners` annotation specifies the callback listener classes to be used for an entity or mapped superclass.
- 10.2 — The `EntityListeners` annotation may be applied to an entity class or mapped superclass.
- 10.2 — The `ExcludeSuperclassListeners` annotation specifies that the invocation of superclass listeners is to be excluded for the entity class (or mapped superclass) and its subclasses.
- 10.2 — The `ExcludeDefaultListeners` annotation specifies that the invocation of default listeners is to be excluded for the entity class (or mapped superclass) and its subclasses.
- 10.2 — The callback lifecycle annotations (`PrePersist`, `PostPersist`, `PreRemove`, `PostRemove`, `PreUpdate`, `PostUpdate`, `PostLoad`) may be applied to methods of an entity class, of a mapped superclass, or of an entity listener class.

## Section 10.3 (Entity Graph Annotations)

- 10.3.1 — The `NamedEntityGraph` annotation must be applied to the root entity of the graph.
- 10.3.1 — The annotation specifies the limits of the graph of associated attributes and entities fetched when an operation which retrieves an instance or instances of the root entity is executed.
- 10.3.1 — The `name` element assigns a name to the entity graph, and is used to identify the entity graph in calls to `EntityManager.getEntityGraph()`.
- 10.3.1 — If no `name` is explicitly specified, the name defaults to the entity name of the annotated root entity.
- 10.3.1 — Entity graph names must be unique within the persistence unit.
- 10.3.1 — The `attributeNodes` element lists attributes of the annotated entity class that are to be included in the entity graph.
- 10.3.1 — The `includeAllAttributes` element specifies that all attributes of the annotated entity class are to be included in the entity graph.
- 10.3.1 — An `attributeNode` element may still be used in conjunction with `includeAllAttributes` to specify a subgraph for the attribute.
- 10.3.1 — The `subgraphs` element specifies a list of subgraphs, further specifying attributes that are managed types.
- 10.3.1 — These subgraphs are referenced by name from `NamedAttributeNode` definitions.
- 10.3.1 — The `subclassSubgraphs` element specifies a list of subgraphs that add additional attributes for subclasses of the root entity.
- 10.3.1 — The `NamedEntityGraphs` annotation can be used to specify multiple named entity graphs for the entity to which it is applied.

## Section 10.3.2 (NamedAttributeNode)

- 10.3.2 — The `NamedAttributeNode` annotation is used to specify an attribute node within an entity graph or subgraph.
- 10.3.2 — The `value` element specifies the name of the corresponding attribute.
- 10.3.2 — The `subgraph` element is used to refer to a `NamedSubgraph` specification that further characterizes an attribute node corresponding to a managed type.
- 10.3.2 — The value of the `subgraph` element must correspond to the name used for the subgraph in the `NamedSubgraph` element.
- 10.3.2 — If the referenced attribute is an entity which has entity subclasses, there may be more than one `NamedSubgraph` element with this name, and the `subgraph` element is considered to refer to all of these.
- 10.3.2 — The `keySubgraph` element is used to refer to a `NamedSubgraph` specification that further characterizes an attribute node corresponding to the key of a Map-valued attribute.
- 10.3.2 — The value of the `keySubgraph` element must correspond to the name used for the subgraph in the `NamedSubgraph` element.

## Section 10.3.3 (NamedSubgraph)

- 10.3.3 — The `NamedSubgraph` annotation is used to further define an attribute node.
- 10.3.3 — It is referenced by its name from the `subgraph` or `keySubgraph` element of a `NamedAttributeNode`.
- 10.3.3 — The `name` element is the name used to reference the subgraph from a `NamedAttributeNode` definition.
- 10.3.3 — The `type` element must be specified when the subgraph corresponds to a subclass of the entity type corresponding to the referencing attribute node.
- 10.3.3 — The `attributeNodes` element lists attributes of the class that must be included.
- 10.3.3 — If the subgraph corresponds to a subclass of the class referenced by the corresponding attribute node, only subclass-specific attributes are listed.

## Section 10.4 (Annotations for Queries)

- 10.4.1 — The `NamedQuery` annotation declares a named query written in the Jakarta Persistence query language.
- 10.4.1 — The `name` element assigns a name to the query, which is used to identify the query in calls to `EntityManager.createNamedQuery()`.
- 10.4.1 — The `query` element must specify a query string itself, written in the Jakarta Persistence query language.
- 10.4.1 — The `resultClass` element specifies the Java class of each query result.
- 10.4.1 — The query result class may be overridden by explicitly passing a `Class` object to `EntityManager.createNamedQuery(String, Class)`.
- 10.4.1 — If the `resultClass` element of a `NamedQuery` annotation is not specified, the persistence implementation is entitled to default the result class to `Object` or `Object[]`.
- 10.4.1 — The `lockMode` element specifies a lock mode for the entity instances in results returned by the query.
- 10.4.1 — If a lock mode other than `NONE` is specified, the query may only be executed within a persistence context with an associated active transaction.
- 10.4.1 — The `hints` element may be used to specify query properties and hints.
- 10.4.1 — Properties defined by this specification must be observed by the provider.
- 10.4.1 — Hints defined by this specification should be observed by the provider when possible.
- 10.4.1 — Vendor-specific hints that are not recognized by a provider must be ignored.
- 10.4.1 — The `NamedQuery` and `NamedQuery` annotations can be applied to an entity or mapped superclass.

## Section 10.4.2 (NamedNativeQuery)

- 10.4.2 — The `NamedNativeQuery` annotation defines a named native SQL query.
- 10.4.2 — The `name` element assigns a name to the query, which is used to identify the query in calls to `EntityManager.createNamedQuery()`.
- 10.4.2 — The `query` element must specify the query string itself, written in the native SQL dialect of the database.
- 10.4.2 — The `resultClass` element specifies the class of each query result.
- 10.4.2 — If a result set mapping is specified, the specified result class must agree with the type inferred from the result set mapping.
- 10.4.2 — If a `resultClass` is not explicitly specified, then it is inferred from the result set mapping, if any, or defaults to `Object` or `Object[]`.
- 10.4.2 — The query result class may be overridden by explicitly passing a `Class` object to `EntityManager.createNamedQuery(String, Class)`.
- 10.4.2 — The `resultSetMapping` element specifies the name of a `SqlResultSetMapping` specification defined elsewhere in metadata.
- 10.4.2 — The named `SqlResultSetMapping` is used to interpret the result set of the native SQL query.
- 10.4.2 — Alternatively, the elements `entities`, `classes`, and `columns` may be used to specify a result set mapping.
- 10.4.2 — These elements may not be used in conjunction with `resultSetMapping`.
- 10.4.2 — The `hints` element may be used to specify query properties and hints.
- 10.4.2 — Hints defined by this specification should be observed by the provider when possible.
- 10.4.2 — Vendor-specific hints which are not recognized by the provider must be ignored.
- 10.4.2 — The `NamedNativeQuery` and `NamedNativeQueries` annotations can be applied to an entity or mapped superclass.

## Section 10.4.3 (NamedStoredProcedureQuery)

- 10.4.3 — The `NamedStoredProcedureQuery` annotation is used to specify a stored procedure, its parameters, and its result type.
- 10.4.3 — The `name` element is the name that is passed as an argument to the `createNamedStoredProcedureQuery` method to create an executable `StoredProcedureQuery` object.
- 10.4.3 — The `procedureName` element is the name of the stored procedure in the database.
- 10.4.3 — All parameters must be specified in the order in which they occur in the parameter list of the stored procedure.
- 10.4.3 — The `resultClasses` element refers to the class (or classes) that are used to map the results.
- 10.4.3 — The `resultSetMappings` element names one or more result set mappings, as defined by the `SqlResultSetMapping` annotation.
- 10.4.3 — If there are multiple result sets, it is assumed that they will be mapped using the same mechanism (e.g., either all via result class mappings or all via result set mappings).
- 10.4.3 — The order of the specification of these mappings must be the same as the order in which the result sets will be returned by the stored procedure invocation.
- 10.4.3 — If the stored procedure returns one or more result sets and no `resultClasses` or `resultSetMappings` element is specified, any result set will be returned as a list of type `Object[]`.
- 10.4.3 — The combining of different strategies for the mapping of stored procedure result sets is undefined.
- 10.4.3 — The `hints` element may be used to specify query properties and hints.
- 10.4.3 — Properties defined by this specification must be observed by the provider.
- 10.4.3 — Vendor-specific hints that are not recognized by a provider must be ignored.
- 10.4.3 — The `NamedStoredProcedureQuery` and `NamedStoredProcedureQueries` annotations can be applied to an entity or mapped superclass.
- 10.4.3 — All parameters of a named stored procedure query must be specified using the `StoredProcedureParameter` annotation.
- 10.4.3 — The `name` element refers to the name of the parameter as defined by the stored procedure in the database.
- 10.4.3 — If a parameter name is not specified, it is assumed that the stored procedure uses positional parameters.
- 10.4.3 — The `mode` element specifies whether the parameter is an `IN`, `INOUT`, `OUT`, or `REF_CURSOR` parameter.
- 10.4.3 — The `type` element refers to the JDBC type for the parameter.

## Section 10.4.4 (SQL Result Set Mappings)

- 10.4.4 — The `SqlResultSetMapping` annotation is used to specify the mapping of the result set of a native SQL query or stored procedure.
- 10.4.4 — The `name` element is the name given to the result set mapping, and is used to identify it when calling methods of the `EntityManager` which create instances of `Query` and `StoredProcedureQuery`.
- 10.4.4 — The `entities`, `classes`, and `columns` elements are used to specify the mapping of result set columns to entities, to constructors, and to scalar values, respectively.
- 10.4.4 — The `entityClass` element specifies the class of the result.
- 10.4.4 — The `lockMode` element specifies the `LockModeType` obtained when the native SQL query is executed.
- 10.4.4 — The `fields` element is used to map the columns specified in the SELECT list of the query to the properties or fields of the entity class.
- 10.4.4 — The `discriminatorColumn` element is used to specify the column name (or alias) of the column in the SELECT list that is used to determine the type of the entity instance.
- 10.4.4 — The `name` element of `FieldResult` is the name of the persistent field or property of the class.
- 10.4.4 — The `column` element of `FieldResult` specifies the name of the corresponding column in the SELECT list.
- 10.4.4 — The `targetClass` element of `ConstructorResult` specifies the class whose constructor is to be invoked.
- 10.4.4 — The `columns` element of `ConstructorResult` specifies the mapping of columns in the SELECT list to the arguments of the intended constructor.
- 10.4.4 — The `name` element of `ColumnResult` specifies the name of the column in the SELECT list.
- 10.4.4 — The `type` element of `ColumnResult` specifies the Java type to which the column type is to be mapped.
- 10.4.4 — If the `type` element is not specified, the default JDBC type mapping for the column will be used.

## Section 10.5 (References to EntityManager and EntityManagerFactory)

- 10.5.1 — The `PersistenceContext` annotation is used to express a dependency on a container-managed entity manager and its associated persistence context.
- 10.5.1 — The `name` element refers to the name by which the entity manager is to be accessed in the environment referencing context.
- 10.5.1 — If the `unitName` element is specified, the persistence unit for the entity manager that is accessible in JNDI must have the same name.
- 10.5.1 — The `type` element specifies whether a transaction-scoped or extended persistence context is to be used.
- 10.5.1 — If the `type` element is not specified, a transaction-scoped persistence context is used.
- 10.5.1 — The `synchronization` element specifies whether the persistence context is always automatically synchronized with the current transaction or whether the persistence context must be explicitly joined to the current transaction by means of the `EntityManager joinTransaction` method.
- 10.5.1 — Properties defined by this specification must be observed by the provider.
- 10.5.1 — Properties that are not recognized by a vendor must be ignored.
- 10.5.1 — The `PersistenceContexts` annotation declares one or more `PersistenceContext` annotations.

## Section 10.5.2 (PersistenceUnit)

- 10.5.2 — The `PersistenceUnit` annotation is used to express a dependency on an entity manager factory and its associated persistence unit.
- 10.5.2 — The `name` element refers to the name by which the entity manager factory is to be accessed in the environment referencing context.
- 10.5.2 — If the `unitName` element is specified, the persistence unit for the entity manager factory that is accessible in JNDI must have the same name.
- 10.5.2 — The `PersistenceUnits` annotation declares one or more `PersistenceUnit` annotations.

## Section 10.6 (Annotations for Attribute Converter Classes)

- 10.6 — Every converter class must implement `AttributeConverter` and must be annotated with the `Converter` annotation or declared as a converter in the XML descriptor.
- 10.6 — The target type for a converter is determined by the actual type argument of the first type parameter of `AttributeConverter`.
- 10.6 — If the `autoApply` element is specified as `true`, the persistence provider must automatically apply the converter to every mapped attribute of the specified target type belonging to any entity in the persistence unit, except for attributes for which conversion is overridden by means of the `Convert` annotation.
- 10.6 — In determining whether a converter applies to an attribute, the provider must treat primitive types and wrapper types as equivalent.
- 10.6 — A converter never applies to id attributes, version attributes, relationship attributes, or to attributes explicitly annotated as `Enumerated` or `Temporal` (or designated as such via XML).
- 10.6 — A converter never applies to any attribute annotated `@Convert(disableConversion=true)` or to an attribute for which the `Convert` annotation explicitly specifies a different converter.
- 10.6 — If `autoApply` is `false`, the converter applies only to attributes of the target type for which conversion is explicitly enabled via the `Convert` annotation.
- 10.6 — If there is more than one converter defined for the same target type, the `Convert` annotation must be used to explicitly specify which converter applies.
