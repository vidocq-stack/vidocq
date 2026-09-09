# 11. Metadata for Object/Relational Mapping - Normative Requirements

Extracted from: ch-22-11-metadata-for-object-relational-mappin.md
Section: 11 (part 3/4)

- `11.1.37` (Required) The map key join columns that are used to map to the entity that is the map key.
- `11.1.37` When the MapKeyJoinColumns annotation is used, both the name and the referencedColumnName elements must be specified in each of the grouped MapKeyJoinColumn annotations.
- `11.1.38` (Required) The type used in mapping java.util.Date or java.util.Calendar.
- `11.1.38` If the map is specified using Java generics, the MapKeyClass annotation and associated type need not be specified; otherwise they must be specified.
- `11.1.41` (Optional) The operations that must be cascaded to the target of the association.
- `11.1.41` (Optional) Whether the association should be lazily loaded or must be eagerly fetched.
- `11.1.41` If the collection is defined using generics to specify the element type, the associated target entity class need not be specified; otherwise it must be specified.
- `11.1.41` If the relationship is bidirectional, the mappedBy element must be used to specify the relationship field or property of the entity that is the owner of the relationship.
- `11.1.41` Must be specified otherwise.
- `11.1.41` Portable applications must otherwise not depend upon a specific order of removal, and must not reassign an entity that has been orphaned to another relationship or otherwise attempt to persist it.
- `11.1.41` Required unless the relationship is unidirectional.
- `11.1.41` The EAGER strategy is a requirement on the persistence provider runtime that the associated entities must be eagerly fetched.
- `11.1.42` (Optional) The operations that must be cascaded to the target of the association.
- `11.1.42` (Optional) Whether the association should be lazily loaded or must be eagerly fetched.
- `11.1.42` If set to false then a non-null relationship must always exist.
- `11.1.42` If the relationship is bidirectional and the entity containing the embeddable class is on the owning side of the relationship, the non-owning side must use the mappedBy element of the OneToOne annotation to specify the relationship field or property of the embeddable class.
- `11.1.42` If the relationship is bidirectional, the mappedBy element must be used to specify the relationship field or property of the entity that is the owner of the relationship.
- `11.1.42` Portable applications must otherwise not depend upon a specific order of removal, and must not reassign an entity that has been orphaned to another relationship or otherwise attempt to persist it.
- `11.1.42` The EAGER strategy is a requirement on the persistence provider runtime that the associated entity must be eagerly fetched.
- `11.1.42` The dot (“.”) notation syntax must be used in the mappedBy element to indicate the relationship attribute within the embedded attribute.
- `11.1.43` A property or field name specified as an orderby_item must correspond to a basic persistent property or field of the associated class or embedded class within it.
- `11.1.43` The properties or fields used in the ordering must correspond to columns for which comparison operators are supported.
- `11.1.43` When OrderBy is applied to an element collection of basic type, the ordering will be by value of the basic objects and the property_or_field_name is not used.[125] When specifying an ordering over an element collection of embeddable type, the dot notation must be used to specify the attribute or attributes that determine the ordering.
- `11.1.44` The order column must be of integral type.
- `11.1.44` The order column value for the first element of the list must be 0.
- `11.1.44` The persistence provider must maintain a contiguous (non-sparse) ordering of the values of the order column when updating the association or element collection.
- `11.1.46` (Required) The primary key join columns.
- `11.1.47` (Required) The name of the table.
- `11.1.48` (Required) The secondary tables that are used to map the entity class.
- `11.1.50` (Required) The sequence generator mappings

---
Requirements: 30
