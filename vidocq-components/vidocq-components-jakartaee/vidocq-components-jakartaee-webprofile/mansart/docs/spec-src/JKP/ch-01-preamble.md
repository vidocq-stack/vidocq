# PREAMBLE

@@HEADING@@ Jakarta Persistence

Jakarta Persistence Team, https://projects.eclipse.org/projects/ee4j.jpa

https://dev.eclipse.org/mailman/listinfo/jpa-dev

 3.2,
April 10, 2024

Table of Contents

Eclipse Foundation Specification License - v1.1

Disclaimers

1. Introduction

1.1. Authorship

1.2. Document Conventions

2. Entities

2.1. The Entity Class

2.2. Persistent Fields and Properties

2.2.1. Persistent Attribute Type

2.2.2. Property Access

2.3. Access Type

2.3.1. Default Access Type

2.3.2. Explicit Access Type

2.3.3. Access Type of an Embeddable Class

2.3.4. Defaulted Access Types of Embeddable Classes and Mapped Superclasses

2.4. Primary Keys and Entity Identity

2.4.1. Composite primary keys

2.4.2. Primary Keys Corresponding to Derived Identities

2.4.2.1. Specification of Derived Identities

2.4.2.2. Mapping of Derived Identities

2.4.2.3. Examples of Derived Identities

2.5. Entity Versions

2.6. Basic Types

2.7. Embeddable Classes

2.8. Collections of Embeddable Classes and Basic Types

2.9. Map Collections

2.9.1. Map Keys

2.9.2. Map Values

2.10. Mapping Defaults for Non-Relationship Fields or Properties

2.11. Entity Relationships

2.12. Relationship Mapping Defaults

2.12.1. Bidirectional OneToOne Relationships

2.12.2. Bidirectional ManyToOne / OneToMany Relationships

2.12.3. Unidirectional Single-Valued Relationships

2.12.3.1. Unidirectional OneToOne Relationships

2.12.3.2. Unidirectional ManyToOne Relationships

2.12.4. Bidirectional ManyToMany Relationships

2.12.5. Unidirectional Multi-Valued Relationships

2.12.5.1. Unidirectional OneToMany Relationships

2.12.5.2. Unidirectional ManyToMany Relationships

2.13. Inheritance

2.13.1. Abstract Entity Classes

2.13.2. Mapped Superclasses

2.13.3. Non-Entity Classes in the Entity Inheritance Hierarchy

2.14. Inheritance Mapping Strategies

2.14.1. Single Table per Class Hierarchy Strategy

2.14.2. Joined Subclass Strategy

2.14.3. Table per Concrete Class Strategy

2.15. Naming of Database Objects

3. Entity Operations

3.1. Overview

3.2. EntityManager Interface

3.3. Entity Instance’s Life Cycle

3.3.1. Entity Instance Creation

3.3.2. Persisting an Entity Instance

3.3.3. Removal

3.3.4. Synchronization to the Database

3.3.5. Refreshing an Entity Instance

3.3.6. Evicting an Entity Instance from the Persistence Context

3.3.7. Detached Entities

3.3.7.1. Merging Detached Entity State

3.3.7.2. Detached Entities and Lazy Loading

3.3.8. Managed Instances

3.3.9. Load State

3.4. Persistence Context Lifetime and Synchronization Type

3.4.1. Synchronization with the Current Transaction

3.4.2. Transaction Commit

3.4.3. Transaction Rollback

3.5. Locking and Concurrency

3.5.1. Optimistic Locking

3.5.2. Entity Versions and Optimistic Locking

3.5.3. Pessimistic Locking

3.5.4. Lock Modes

3.5.4.1. OPTIMISTIC, OPTIMISTIC_FORCE_INCREMENT

3.5.4.2. PESSIMISTIC_READ, PESSIMISTIC_WRITE, PESSIMISTIC_FORCE_INCREMENT

3.5.4.3. Lock Mode Properties and Uses

3.5.5. OptimisticLockException

3.6. Entity Listeners and Callback Methods

3.6.1. Entity Listeners

3.6.2. Lifecycle Callback Methods

3.6.3. Semantics of the Life Cycle Callback Methods for Entities

3.6.4. Multiple Lifecycle Callback Methods for an Entity Lifecycle Event

3.6.5. Exceptions

3.6.6. Specification of Callback Listener Classes and Lifecycle Methods in the XML Descriptor

3.6.6.1. Specification of Callback Listeners

3.6.6.2. Specification of the Binding of Entity Listener Classes to Entities

3.7. Bean Validation

3.7.1. Automatic Validation Upon Lifecycle Events

3.7.1.1. Enabling Automatic Validation

3.7.1.2. Requirements for Automatic Validation upon Lifecycle Events

3.7.2. Providing the ValidatorFactory

3.8. Entity Graphs

3.8.1. Use of Entity Graphs in find and query operations

3.8.1.1. Fetch Graph Semantics

3.8.1.2. Load Graph Semantics

3.9. Type Conversion of Basic Attributes

3.10. Second-Level Cache

3.10.1. The Shared Cache Mode and Cacheable Annotation

3.10.2. Cache Modes

3.10.3. Cache Interface

3.11. Query APIs

3.11.1. Query Execution

3.11.2. Queries and Flush Mode

3.11.3. Queries and Lock Mode

3.11.4. Query Hints

3.11.5. Parameter Objects

3.11.6. Named Parameters

3.11.7. Positional Parameters

3.11.8. Arguments to query parameters

3.11.9. Named Queries

3.11.10. Polymorphic Queries

3.11.11. SQL Queries

3.11.11.1. Returning Managed Entities from Native Queries

3.11.11.2. Returning Unmanaged Instances

3.11.11.3. Combinations of Result Types

3.11.11.4. Restrictions

3.11.12. Stored Procedures

3.11.12.1. Named Stored Procedure Queries

3.11.12.2. Dynamically-specified Stored Procedure Queries

3.11.12.3. Stored Procedure Query Execution

3.12. Summary of Exceptions

4. Query Language

4.1. Overview

4.2. Statement Types

4.2.1. Select Statements

4.2.1.1. Set Operators in Select Statements

4.2.2. Update and Delete Statements

4.3. Abstract Schema Types and Query Domains

4.3.1. Naming

4.3.2. Example

4.4. The FROM Clause and Navigational Declarations

4.4.1. Identifiers

4.4.2. Identification Variables

4.4.3. Range Variable Declarations

4.4.4. Path Expressions

4.4.4.1. Path Expression Syntax

4.4.5. Joins

4.4.5.1. Inner Joins

4.4.5.2. Outer Joins

4.4.5.3. Fetch Joins

4.4.6. Collection Member Declarations

4.4.7. FROM Clause and SQL

4.4.8. Polymorphism

4.4.9. Downcasting

4.5. WHERE Clause

4.6. Conditional Expressions

4.6.1. Conditional Expression Composition

4.6.2. Operators and Operator Precedence

4.6.3. Comparison Expressions

4.6.4. Between Expressions

4.6.5. In Expressions

4.6.6. Like Expressions

4.6.7. Null Comparison Expressions

4.6.8. Empty Collection Comparison Expressions

4.6.9. Collection Member Expressions

4.6.10. Exists Expressions

4.6.11. All or Any Expressions

4.6.12. Subqueries

4.6.13. Null Values

4.6.14. Equality and Comparison Semantics

4.6.14.1. Queries Using Input Parameters

4.7. Scalar Expressions

4.7.1. Literals

4.7.2. Identification Variables

4.7.3. Path Expressions

4.7.4. Input Parameters

4.7.4.1. Positional Parameters

4.7.4.2. Named Parameters

4.7.5. Arithmetic Expressions

4.7.6. String concatenation operator

4.7.7. Built-in String, Arithmetic, and Datetime Functional Expressions

4.7.7.1. String Functions

4.7.7.2. Arithmetic Functions

4.7.7.3. Datetime Functions

4.7.8. Typecasts

4.7.9. Invocation of Predefined and User-defined Database Functions

4.7.10. Case Expressions

4.7.11. Identifier and Version Functions

4.7.12. Entity Type Expressions and Literal Entity Types

4.7.13. Numeric Expressions and Type Promotion

4.8. GROUP BY, HAVING

4.9. SELECT Clause

4.9.1. Result Type of the SELECT Clause

4.9.2. Constructor Expressions in the SELECT Clause

4.9.3. Null Values in the Query Result

4.9.4. Embeddables in the Query Result

4.9.5. Aggregate Functions in the SELECT Clause

4.10. ORDER BY Clause

4.11. Bulk Update and Delete Operations

4.12. BNF

5. Metamodel API

5.1. Static Metamodel Classes

5.1.1. Canonical Metamodel

5.1.1.1. Example Canonical Metamodel

5.1.2. Bootstrapping the Static Metamodel

5.2. Runtime Access to Metamodel

6. Criteria API

6.1. Overview

6.2. Criteria Query API Usage

6.3. Constructing Criteria Queries

6.3.1. CriteriaQuery Creation

6.3.2. Query Roots

6.3.3. Joins

6.3.4. Fetch Joins

6.3.5. Path Navigation

6.3.6. Restricting the Query Result

6.3.7. Downcasting

6.3.8. Expressions

6.3.8.1. Result Types of Expressions

6.3.9. Literals

6.3.10. Parameter Expressions

6.3.11. Specifying the Select List

6.3.11.1. Assigning Aliases to Selection Items

6.3.12. Subqueries

6.3.13. GroupBy and Having

6.3.14. Ordering the Query Results

6.3.15. Bulk Update and Delete Operations

6.4. Constructing Strongly-typed Queries using the jakarta.persistence.metamodel Interfaces

6.5. Use of the Criteria API with Strings to Reference Attributes

6.6. Query Modification

6.7. Query Execution

7. Entity Managers and Persistence Contexts

7.1. Persistence Contexts

7.2. Obtaining an EntityManager

7.2.1. Obtaining an Entity Manager in the Jakarta EE Environment

7.2.2. Obtaining an Application-managed Entity Manager

7.3. Obtaining an Entity Manager Factory

7.3.1. Obtaining an Entity Manager Factory in a Jakarta EE Container

7.3.2. Obtaining an Entity Manager Factory in a Java SE Environment

7.3.3. Obtaining an Entity Manager Factory for a programmatically-defined persistence unit

7.4. EntityManagerFactory Interface

7.5. Controlling Transactions

7.5.1. JTA EntityManagers

7.5.2. Resource-local EntityManagers

7.5.3. The EntityTransaction Interface

7.6. The runInTransaction and callInTransaction methods

7.7. Container-managed Persistence Contexts

7.7.1. Persistence Context Synchronization Type

7.7.2. Container-managed Transaction-scoped Persistence Context

7.7.3. Container-managed Extended Persistence Context

7.7.3.1. Inheritance of Extended Persistence Context

7.7.4. Persistence Context Propagation

7.7.4.1. Requirements for Persistence Context Propagation

7.8. Application-managed Persistence Contexts

7.9. Requirements on the Container

7.9.1. Application-managed Persistence Contexts

7.9.2. Container Managed Persistence Contexts

7.10. Runtime Contracts between the Container and Persistence Provider

7.10.1. Container Responsibilities

7.10.2. Provider Responsibilities

7.11. PersistenceUnitUtil Interface

7.12. SchemaManager Interface

8. Entity Packaging

8.1. Persistence Unit

8.2. Persistence Unit Packaging

8.2.1. persistence.xml file

8.2.1.1. name

8.2.1.2. transaction-type

8.2.1.3. description

8.2.1.4. provider

8.2.1.5. qualifier

8.2.1.6. scope

8.2.1.7. jta-data-source, non-jta-data-source

8.2.1.8. mapping-file, jar-file, class, exclude-unlisted-classes

8.2.1.9. shared-cache-mode

8.2.1.10. validation-mode

8.2.1.11. properties

8.2.2. Persistence Unit Scope

8.3. persistence.xml Schema

9. Container and Provider Contracts for Deployment and Bootstrapping

9.1. Jakarta EE Deployment

9.2. Bootstrapping in Java SE Environments

9.2.1. Schema Generation

9.3. Determining the Available Persistence Providers

9.4. Schema Generation

9.4.1. Data Loading

9.5. Responsibilities of the Persistence Provider

9.5.1. jakarta.persistence.spi.PersistenceProvider

9.5.2. jakarta.persistence.spi.ProviderUtil

9.6. jakarta.persistence.spi.PersistenceUnitInfo Interface

9.6.1. jakarta.persistence.spi.ClassTransformer Interface

9.7. jakarta.persistence.Persistence Class

9.8. jakarta.persistence.PersistenceConfiguration Class

9.9. PersistenceUtil Interface

9.9.1. Contracts for Determining the Load State of an Entity or Entity Attribute

10. Metadata Annotations

10.1. Entity

10.2. Callback Annotations

10.3. EntityGraph Annotations

10.3.1. NamedEntityGraph and NamedEntityGraphs Annotations

10.3.2. NamedAttributeNode Annotation

10.3.3. NamedSubgraph Annotation

10.4. Annotations for Queries

10.4.1. NamedQuery Annotation

10.4.2. NamedNativeQuery Annotation

10.4.3. NamedStoredProcedureQuery Annotation

10.4.4. Annotations for SQL Result Set Mappings

10.5. References to EntityManager and EntityManagerFactory

10.5.1. PersistenceContext Annotation

10.5.2. PersistenceUnit Annotation

10.6. Annotations for Attribute Converter Classes

11. Metadata for Object/Relational Mapping

11.1. Annotations for Object/Relational Mapping

11.1.1. Access Annotation

11.1.2. AssociationOverride Annotation

11.1.3. AssociationOverrides Annotation

11.1.4. AttributeOverride Annotation

11.1.5. AttributeOverrides Annotation

11.1.6. Basic Annotation

11.1.7. Cacheable Annotation

11.1.8. CollectionTable Annotation

11.1.9. Column Annotation

11.1.10. Convert Annotation

11.1.11. Converts Annotation

11.1.12. DiscriminatorColumn Annotation

11.1.13. DiscriminatorValue Annotation

11.1.14. ElementCollection Annotation

11.1.15. Embeddable Annotation

11.1.16. Embedded Annotation

11.1.17. EmbeddedId Annotation

11.1.18. Enumerated Annotation

11.1.19. EnumeratedValue Annotation

11.1.20. ForeignKey Annotation

11.1.21. GeneratedValue Annotation

11.1.22. Id Annotation

11.1.23. IdClass Annotation

11.1.24. Index Annotation

11.1.25. Inheritance Annotation

11.1.26. JoinColumn Annotation

11.1.27. JoinColumns Annotation

11.1.28. JoinTable Annotation

11.1.29. Lob Annotation

11.1.30. ManyToMany Annotation

11.1.31. ManyToOne Annotation

11.1.32. MapKey Annotation

11.1.33. MapKeyClass Annotation

11.1.34. MapKeyColumn Annotation

11.1.35. MapKeyEnumerated Annotation

11.1.36. MapKeyJoinColumn Annotation

11.1.37. MapKeyJoinColumns Annotation

11.1.38. MapKeyTemporal Annotation

11.1.39. MappedSuperclass Annotation

11.1.40. MapsId Annotation

11.1.41. OneToMany Annotation

11.1.42. OneToOne Annotation

11.1.43. OrderBy Annotation

11.1.44. OrderColumn Annotation

11.1.45. PrimaryKeyJoinColumn Annotation

11.1.46. PrimaryKeyJoinColumns Annotation

11.1.47. SecondaryTable Annotation

11.1.48. SecondaryTables Annotation

11.1.49. SequenceGenerator Annotation

11.1.50. SequenceGenerators Annotation

11.1.51. Table Annotation

11.1.52. TableGenerator Annotation

11.1.53. TableGenerators Annotation

11.1.54. Temporal Annotation

11.1.55. Transient Annotation

11.1.56. UniqueConstraint Annotation

11.1.57. Version Annotation

11.2. Object/Relational Metadata Used in Schema Generation

11.2.1. Table-level elements

11.2.1.1. Table

11.2.1.2. Inheritance

11.2.1.3. SecondaryTable

11.2.1.4. CollectionTable

11.2.1.5. JoinTable

11.2.1.6. TableGenerator

11.2.2. Column-level elements

11.2.2.1. Column

11.2.2.2. MapKeyColumn

11.2.2.3. Enumerated, MapKeyEnumerated

11.2.2.4. Temporal, MapKeyTemporal

11.2.2.5. Lob

11.2.2.6. OrderColumn

11.2.2.7. DiscriminatorColumn

11.2.2.8. Version

11.2.3. Primary Key mappings

11.2.3.1. Id

11.2.3.2. EmbeddedId

11.2.3.3. GeneratedValue

11.2.4. Foreign Key Column Mappings

11.2.4.1. JoinColumn

11.2.4.2. MapKeyJoinColumn

11.2.4.3. PrimaryKeyJoinColumn

11.2.4.4. ForeignKey

11.2.5. Other Elements

11.2.5.1. SequenceGenerator

11.2.5.2. Index

11.2.5.3. UniqueConstraint

11.3. Examples of the Application of Annotations for Object/Relational Mapping

12. XML Object/Relational Mapping Descriptor

12.1. Use of the XML Descriptor

12.2. XML Overriding Rules

12.2.1. persistence-unit-defaults Subelements

12.2.1.1. schema

12.2.1.2. catalog

12.2.1.3. delimited-identifiers

12.2.1.4. access

12.2.1.5. cascade-persist

12.2.1.6. entity-listeners

12.2.2. Other Subelements of the entity-mappings element

12.2.2.1. package

12.2.2.2. schema

12.2.2.3. catalog

12.2.2.4. access

12.2.2.5. sequence-generator

12.2.2.6. table-generator

12.2.2.7. named-query

12.2.2.8. named-native-query

12.2.2.9. named-stored-procedure-query

12.2.2.10. sql-result-set-mapping

12.2.2.11. entity

12.2.2.12. mapped-superclass

12.2.2.13. embeddable

12.2.2.14. converter

12.2.3. entity Subelements and Attributes

12.2.3.1. metadata-complete

12.2.3.2. access

12.2.3.3. cacheable

12.2.3.4. name

12.2.3.5. table

12.2.3.6. secondary-table

12.2.3.7. primary-key-join-column

12.2.3.8. id-class

12.2.3.9. inheritance

12.2.3.10. discriminator-value

12.2.3.11. discriminator-column

12.2.3.12. sequence-generator

12.2.3.13. table-generator

12.2.3.14. attribute-override

12.2.3.15. association-override

12.2.3.16. convert

12.2.3.17. named-entity-graph

12.2.3.18. named-query

12.2.3.19. named-native-query

12.2.3.20. named-stored-procedure-query

12.2.3.21. sql-result-set-mapping

12.2.3.22. exclude-default-listeners

12.2.3.23. exclude-superclass-listeners

12.2.3.24. entity-listeners

12.2.3.25. pre-persist, post-persist, pre-remove, post-remove, pre-update, post-update, post-load

12.2.3.26. attributes

12.2.4. mapped-superclass Subelements and Attributes

12.2.4.1. metadata-complete

12.2.4.2. access

12.2.4.3. id-class

12.2.4.4. exclude-default-listeners

12.2.4.5. exclude-superclass-listeners

12.2.4.6. entity-listeners

12.2.4.7. pre-persist, post-persist, pre-remove, post-remove, pre-update, post-update, post-load

12.2.4.8. attributes

12.2.5. embeddable Subelements and Attributes

12.2.5.1. metadata-complete

12.2.5.2. access

12.2.5.3. attributes

12.3. XML Schema

Related Documents

Appendix A: Revision History

A.1. Jakarta Persistence 3.2

A.1.1. Deprecations

A.1.2. Deprecations for removal

A.2. Jakarta Persistence 3.1

A.3. Jakarta Persistence 3.0

A.4. Java Persistence 2.2 (Maintenance Release Draft)

Appendix B: Persistence API Interfaces

B.1. EntityManager

B.2. EntityTransaction

B.3. EntityManagerFactory

B.4. LockModeType

B.5. Cache

B.6. Query

B.7. TypedQuery

B.8. StoredProcedureQuery

B.9. Tuple

B.10. TupleElement

B.11. Parameter

B.12. Graph

B.13. EntityGraph

B.14. Subgraph

B.15. AttributeNode

B.16. SchemaManager

B.17. Persistence

B.18. PersistenceConfiguration

B.19. PersistenceUtil

B.20. PersistenceUnitUtil

Appendix C: Criteria API Interfaces

C.1. CriteriaBuilder

C.2. CriteriaDelete

C.3. CriteriaQuery

C.4. CriteriaSelect

C.5. CriteriaUpdate

C.6. AbstractQuery

C.7. CollectionJoin

C.8. CommonAbstractCriteria

C.9. CompoundSelection

C.10. Expression

C.11. Fetch

C.12. FetchParent

C.13. AbstractQuery

C.14. Join

C.15. JoinType

C.16. ListJoin

C.17. LocalDateField

C.18. LocalDateTimeField

C.19. LocalTimeField

C.20. MapJoin

C.21. Nulls

C.22. Order

C.23. ParameterExpression

C.24. Path

C.25. PluralJoin

C.26. Predicate

C.27. Root

C.28. Selection

C.29. SetJoin

C.30. Subquery

C.31. TemporalField

Appendix D: Metamodel API Interfaces

D.1. Metamodel

D.2. StaticMetamodel

D.3. Attribute

D.4. BasicType

D.5. Bindable

D.6. CollectionAttribute

D.7. EmbeddableType

D.8. EntityType

D.9. IdentifiableType

D.10. ListAttribute

D.11. ManagedType

D.12. MapAttribute

D.13. MappedSuperclassType

D.14. PluralAttribute

D.15. SetAttribute

D.16. SingularAttribute

D.17. Type

Appendix E: Persistence SPI Interfaces

E.1. ClassTransformer

E.2. LoadState

E.3. PersistenceProvider

E.4. PersistenceProviderResolver

E.5. PersistenceProviderResolverHolder

E.6. PersistenceUnitInfo

E.7. ProviderUtil

Specification: Jakarta Persistence

Version: 3.2

Status: Final Release

Release: April 10, 2024

Copyright (c) 2019, 2024 Eclipse Foundation.
