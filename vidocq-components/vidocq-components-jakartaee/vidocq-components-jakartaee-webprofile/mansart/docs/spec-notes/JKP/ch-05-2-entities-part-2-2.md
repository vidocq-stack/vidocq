# ch-05-2-entities-part-2-2 — Normative Requirements

## 2.12. Relationship Mapping Defaults

2.12 [R-01] The inverse side of a bidirectional relationship must refer to its owning side by use of the `mappedBy` element of the `OneToOne`, `OneToMany`, or `ManyToMany` annotation.
2.12 [R-02] The many side of one-to-many / many-to-one bidirectional relationships must be the owning side; the `mappedBy` element cannot be specified on the `ManyToOne` annotation.
2.12 [R-03] The `JoinColumn` annotation or corresponding XML element must be used to specify unidirectional one-to-many relationships by means of foreign key mappings.
2.12 [R-04] The `JoinTable` annotation or corresponding XML element must be used to specify unidirectional and bidirectional one-to-one, bidirectional many-to-one/one-to-many, and unidirectional many-to-one relationships by means of join table mappings.
2.12 [R-05] Such mapping annotations must be specified on the owning side of the relationship.
2.12 [R-06] Any overriding of mapping defaults must be consistent with the relationship modeling annotation that is specified.
2.12 [R-07] If there are no associated entities for a multi-valued relationship of an entity fetched from the database, the persistence provider must return an empty collection as the value of the relationship.

## 2.12.1. Bidirectional OneToOne Relationships

2.12.1 [R-08] Entity A must be specified as the owner of the relationship.

## 2.12.2. Bidirectional ManyToOne / OneToMany Relationships

2.12.2 [R-09] Entity A must be the owner of the relationship.

## 2.12.3. Unidirectional Single-Valued Relationships

2.12.3 [R-10] A unidirectional relationship has only an owning side, which must be Entity A.

## 2.12.4. Bidirectional ManyToMany Relationships

2.12.4 [R-11] Entity A must be the owner of the relationship.

## 2.12.5. Unidirectional Multi-Valued Relationships

2.12.5 [R-12] A unidirectional relationship has only an owning side, which must be Entity A.

## 2.13.2. Mapped Superclasses

2.13.2 [R-13] A mapped superclass, unlike an entity, is not queryable and must not be passed as an argument to `EntityManager` or `Query` operations.
2.13.2 [R-14] Persistent relationships defined by a mapped superclass must be unidirectional.

## 2.13.3. Non-Entity Classes in the Entity Inheritance Hierarchy

2.13.3 [R-15] Non-entity classes cannot be passed as arguments to methods of the `EntityManager` or `Query` interfaces and cannot bear mapping information.

## 2.14. Inheritance Mapping Strategies

2.14 [R-16] An implementation is required to support the single table per class hierarchy inheritance mapping strategy and the joined subclass strategy.

## 2.15. Naming of Database Objects

2.15 [R-17] By default, the names of database objects must be treated as undelimited identifiers and passed to the database as such.
2.15 [R-18] It is possible to specify that all database identifiers in use for a persistence unit be treated as delimited identifiers by specifying the `<delimited-identifiers/>` element within the `persistence-unit-defaults` element of the object/relational xml mapping file.
2.15 [R-19] If the `<delimited-identifiers/>` element is specified, it cannot be overridden.
2.15 [R-20] Using annotations, a name is specified as a delimited identifier by enclosing the name within double quotes, whereby the inner quotes are escaped (e.g., `@Table(name="\"customer\""`).
