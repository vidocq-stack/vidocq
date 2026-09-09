# 11. Metadata for Object/Relational Mapping - Normative Requirements

Extracted from: ch-23-11-metadata-for-object-relational-mappin.md
Section: 11 (part 4/4)

- `11.1.53` (Required) The table generator mappings
- `11.1.54` (Required) The type used in mapping java.util.Date or java.util.Calendar.
- `11.1.54` The Temporal annotation must be specified for persistent fields or properties of type java.util.Date and java.util.Calendar unless a converter is being applied.
- `11.1.56` (Required) An array of the column names that make up the constraint.
- `11.1.57` If schema generation is in effect, the persistence provider must observe the mapping information specified by these annotations and their corresponding XML elements.
- `11.1.57` The names of database objects must be treated in conformance with the requirements of Section 2.15.
- `11.2.3` If a strategy is indicated, the provider must use it if it is supported by the target database.
- `11.2.3` If an EmbeddedId attribute corresponds to a relationship attribute, the MapsId annotation must be used, and the column mapping is determined by the join column for the relationship.
- `11.2.5` The ordering of the columnNames specified in the UniqueConstraint annotation must be observed by the provider when creating the constraint.
- `11.2.5` The ordering of the names in the columnList element specified in the Index annotation must be observed by the provider when creating the index.

---
Requirements: 10
