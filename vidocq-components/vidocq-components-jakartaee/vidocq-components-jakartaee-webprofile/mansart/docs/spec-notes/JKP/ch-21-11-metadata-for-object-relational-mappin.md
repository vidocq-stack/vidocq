# 11. Metadata for Object/Relational Mapping - Normative Requirements

Extracted from: ch-21-11-metadata-for-object-relational-mappin.md
Section: 11 (part 2/4)

- `11.1.19` The annotated field must be declared final, and must be of type:
- `11.1.19` The field must not be set to null, and must hold a distinct value for each value of the enum type.
- `11.1.20` If the value of the ConstraintMode element is` NO_CONSTRAINT`, the provider must not generate a foreign key constraint.
- `11.1.21` (Optional) The primary key generation strategy that the persistence provider must use to generate the annotated entity primary key.
- `11.1.21` However, if the persistence provider stores a value generated according to the UUID strategy in a column of type VARCHAR or equivalent, the value must be stored in its canonical representation, unless the application explicitly indicates that some other representation is preferred.
- `11.1.21` The GeneratedValue annotation may be applied to a primary key property or field of an entity or mapped superclass in conjunction with the Id annotation.[115] The persistence provider is only required to support the use of the GeneratedValue annotation for simple primary keys.
- `11.1.21` The TABLE generator type value indicates that the persistence provider must assign primary keys for the entity using an underlying database table to ensure uniqueness.
- `11.1.23` (Required) The composite primary key class.
- `11.1.23` The Id annotation must also be applied to the corresponding fields or properties of the entity.
- `11.1.23` The names of the fields or properties in the primary key class and the primary key fields or properties of the entity must correspond and their types must match according to the rules specified in Section 2.4 and Section 2.4.2.
- `11.1.24` (Required) The names of the columns to be included in the index.
- `11.1.24` The persistence provider must observe the specified ordering of the columns.
- `11.1.25` Support for the combination of inheritance strategies is not required by this specification.
- `11.1.26` If more than one JoinColumn annotation is applied to a field or property, both the name and the referencedColumnName elements must be specified in each such JoinColumn annotation.
- `11.1.27` (Required) The join columns that map the relationship.
- `11.1.27` When the JoinColumns annotation is used, both the name and the referencedColumnName elements must be specified in each of the grouped JoinColumn annotations.
- `11.1.30` (Optional) The operations that must be cascaded to the target of the association.
- `11.1.30` (Optional) Whether the association should be lazily loaded or must be eagerly fetched.
- `11.1.30` If the collection is defined using generics to specify the element type, the associated target entity class does not need to be specified; otherwise it must be specified.
- `11.1.30` If the relationship is bidirectional and the entity containing the embeddable class is the owner of the relationship, the non-owning side must use the mappedBy element of the ManyToMany annotation to specify the relationship field or property of the embeddable class.
- `11.1.30` If the relationship is bidirectional, the non-owning side must use the mappedBy element of the ManyToMany annotation to specify the relationship field or property of the owning side.
- `11.1.30` Must be specified otherwise.
- `11.1.30` Required unless the relationship is unidirectional.
- `11.1.30` The EAGER strategy is a requirement on the persistence provider runtime that the associated entities must be eagerly fetched.
- `11.1.30` The EAGER strategy is a requirement on the persistence provider runtime that the associated entity must be eagerly fetched.
- `11.1.30` The dot ("." ) notation syntax must be used in the mappedBy element to indicate the relationship attribute within the embedded attribute.
- `11.1.31` (Optional) The operations that must be cascaded to the target of the association.
- `11.1.31` (Optional) Whether the association should be lazily loaded or must be eagerly fetched.
- `11.1.31` If set to false then a non-null relationship must always exist.
- `11.1.31` If the relationship is bidirectional, the non-owning OneToMany entity side must use the mappedBy element of the OneToMany annotation to specify the relationship field or property of the embeddable field or property on the owning side of the relationship.
- `11.1.31` The EAGER strategy is a requirement on the persistence provider runtime that the associated entity must be eagerly fetched.
- `11.1.31` The dot (“.”) notation syntax must be used in the mappedBy element to indicate the relationship attribute within the embedded attribute.
- `11.1.33` (Required) The type of the map key.
- `11.1.33` If the map is specified using Java generics, the MapKeyClass annotation and associated type need not be specified; otherwise they must be specified.
- `11.1.35` If the map is specified using Java generics, the MapKeyClass annotation and associated type need not be specified; otherwise they must be specified.

---
Requirements: 35
