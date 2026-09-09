# ch-20-11-metadata-for-object-relational-mapping — Normative Requirements

## Section 11 — Object/Relational Mapping Metadata

11. The implementation of this specification must assume the application's dependency upon object/relational mapping metadata and must observe the semantics and requirements expressed by that mapping.

## Section 11.1.1 — Access Annotation

11.1.1. The `value` element of the `Access` annotation is required and specifies the access type to be applied to the class or attribute.

## Section 11.1.2 — AssociationOverride Annotation

11.1.2. When used to override mappings at multiple levels of embedding, a dot (".") notation syntax must be used in the `name` element to indicate an attribute within an embedded attribute.
11.1.2. To override the mappings of an embeddable class used as a map value, "value." must be used to prefix the name of the attribute within the embeddable class being overridden.
11.1.2. If the relationship mapping uses a join table, the `joinTable` element must be specified to override the mapping of the join table and/or its join columns.
11.1.2. The `joinColumns` element must be specified if a foreign key mapping is used in the overriding of the mapping of the relationship.
11.1.2. The `joinColumns` element must not be specified if a join table is used in the overriding of the mapping of the relationship.
11.1.2. The `joinTable` element must be specified if a join table is used in the overriding of the mapping of the relationship.
11.1.2. The `joinTable` element must not be specified if a foreign key mapping is used in the overriding of the mapping of the relationship.
11.1.2. If both the `foreignKey` element of `AssociationOverride` and the `foreignKey` element of any of the `joinColumns` elements are specified, the behavior is undefined.

## Section 11.1.4 — AttributeOverride Annotation

11.1.4. When the `AttributeOverride` annotation is applied to a map, "key." or "value." must be used to prefix the name of the attribute being overridden to specify it as part of the map key or map value.
11.1.4. To override mappings at multiple levels of embedding, a dot (".") notation form must be used in the `name` element to indicate an attribute within an embedded attribute.

## Section 11.1.6 — Basic Annotation

11.1.6. The persistence provider must support mappings to the column types listed in tables B-2 and B-4 of the JDBC 4.3 specification.
11.1.6. The provider must support mapping `java.time.Instant` to the JDBC `TIMESTAMP` or `TIMESTAMP_WITH_TIMEZONE` type.
11.1.6. The provider must support mapping `java.time.Year` to the JDBC `INTEGER` and `SMALLINT` types.
11.1.6. The provider must support mapping `java.math.BigInteger` and `java.math.BigDecimal` to the JDBC `NUMERIC` and `DECIMAL` types.
11.1.6. The provider must support mapping `java.util.UUID` to the JDBC `CHAR` and `VARCHAR` types.
11.1.6. The provider must support mapping `char[]` to the JDBC `CHAR`, `NCHAR`, `VARCHAR`, `NVARCHAR`, `LONGVARCHAR`, and `LONGNVARCHAR` types.
11.1.6. The `EAGER` strategy is a requirement on the persistence provider runtime that data must be eagerly fetched.
11.1.6. If the persistence provider stores a `java.util.UUID` value in a `VARCHAR` column, the value must be stored in its canonical representation unless the application explicitly indicates that some other representation is preferred.

## Section 11.1.7 — Cacheable Annotation

11.1.7. `Cacheable(false)` means that the entity and its state must not be cached by the provider.

## Section 11.1.8 — CollectionTable Annotation

11.1.8. If the `CollectionTable` annotation is missing, the default values of the `CollectionTable` annotation elements apply.
11.1.8. This annotation may not be applied to a persistent field or property not annotated `@ElementCollection`.
11.1.8. If no `foreignKey` annotation element is specified in either location, the persistence provider's default foreign key strategy will apply.

## Section 11.1.9 — Column Annotation

11.1.9. If no `Column` annotation is specified, the default values in Table 12 apply.
11.1.9. Portable applications which make use of schema generation must explicitly specify the precision and scale of columns of type `numeric` or `decimal`.

## Section 11.1.10 — Convert Annotation

11.1.10. When persistent properties are used, the `Convert` annotation is applied to the getter method.
11.1.10. The `converter` element specifies the converter that is applied; even if an auto-applied converter would otherwise apply, the converter specified by the `converter` element must be applied instead.
11.1.10. The `disableConversion` element specifies that any auto-applied converter that would otherwise apply must not be applied.
11.1.10. If multiple converters are applicable to the annotated field or property and the `converter` element is not specified, the behavior is undefined.
11.1.10. The `Convert` annotation must not be used to specify conversion of id attributes, version attributes, relationship attributes, or attributes explicitly annotated as `Enumerated` or `Temporal`.
11.1.10. When applied to a basic attribute or a collection attribute of basic type, the `attributeName` element must not be specified.
11.1.10. When applied to an embedded attribute, a collection attribute whose element type is an embeddable type, a map collection attribute, or an entity class extending a mapped superclass, the `attributeName` element must be specified.
11.1.10. To override conversion mappings at multiple levels of embedding, a dot (".") notation form must be used in the `attributeName` element.
11.1.10. When applied to a map to specify conversion of a map key or value of basic type, "key" or "value" must be used as the value of the `attributeName` element.
11.1.10. When applied to a map whose key or value type is an embeddable type, "key." or "value." must be used to prefix the name of the attribute of the key or value type that is converted.

## Section 11.1.11 — Converts Annotation

11.1.11. Multiple converters must not be applied to the same basic attribute.

## Section 11.1.12 — DiscriminatorColumn Annotation

11.1.12. For the `SINGLE_TABLE` mapping strategy, and typically also for the `JOINED` strategy, the persistence provider will use a type discriminator column.
11.1.12. If the `DiscriminatorColumn` annotation is missing and a discriminator column is required, the name of the discriminator column defaults to "DTYPE" and the discriminator type to `STRING`.
11.1.12. The type of the discriminator column, if specified in the optional `columnDefinition` element, must be consistent with the discriminator type.

## Section 11.1.13 — DiscriminatorValue Annotation

11.1.13. The `DiscriminatorValue` annotation can only be specified on a concrete entity class.
11.1.13. The discriminator value must be consistent in type with the discriminator type of the specified or defaulted discriminator column.
11.1.13. If the `DiscriminatorValue` annotation is not specified, a provider-specific function to generate a value representing the entity type is used for the value of the discriminator column.
11.1.13. If the `DiscriminatorType` is `STRING`, the discriminator value default is the entity name.

## Section 11.1.14 — ElementCollection Annotation

11.1.14. The `ElementCollection` annotation must be specified if the collection is to be mapped by means of a collection table.
11.1.14. The `EAGER` strategy is a requirement on the persistence provider runtime that the collection elements must be eagerly fetched.

## Section 11.1.15 — Embeddable Annotation

11.1.15. The `Embeddable` annotation is used to specify a class whose instances are stored as an intrinsic part of an owning entity and share the identity of the entity.

## Section 11.1.16 — Embedded Annotation

11.1.16. Each of the persistent properties or fields of the embedded object is mapped to the database table for the entity or embeddable class.
11.1.16. The embeddable class must be annotated as `Embeddable`.
11.1.16. Implementations are not required to support embedded objects that are mapped across more than one table.

## Section 11.1.17 — EmbeddedId Annotation

11.1.17. The `EmbeddedId` annotation is applied to denote a composite primary key that is an embeddable class.
11.1.17. The embeddable class must be annotated as `Embeddable`.
11.1.17. There must be only one `EmbeddedId` annotation and no `Id` annotation when the `EmbeddedId` annotation is used.

## Section 11.1.18 — Enumerated Annotation

11.1.18. An `Enumerated` annotation specifies that a persistent property or field should be persisted as an enumerated type.
11.1.18. An enum can be mapped as either a string or an integer.
11.1.18. The default value of the `Enumerated` annotation is `ORDINAL`.
