# 12. XML Object/Relational Mapping Descriptor (part 1/3)

The XML object/relational mapping descriptor
serves as both an alternative to and an overriding mechanism for Java
language metadata annotations.

12.1. Use of the XML Descriptor

The XML schema for the object
relational/mapping descriptor is contained in Section 12.3. The root element of this
schema is the entity-mappings element. The absence or present of the
xml-mapping-metadata-complete subelement contained in the
persistence-unit-defaults subelement of the entity-mappings element
controls whether the XML object/relational mapping descriptor is used to
selectively override annotation values or whether it serves as a
complete alternative to Java language metadata annotations.

If the xml-mapping-metadata-complete
subelement is specified, the complete set of mapping metadata for the
persistence unit is contained in the XML mapping files for the
persistence unit, and any persistence annotations on the classes are
ignored.

If xml-mapping-metadata-complete is
specified and XML elements are omitted, the default values apply. These
default values are the same as the corresponding defaults when
annotations are used, except in the cases specified in Section 12.2 below. When the
xml-mapping-metadata-complete element is specified, any
metadata-complete attributes specified within the entity,
mapped-superclass, and embeddable elements are ignored.

If the xml-mapping-metadata-complete
subelement is not specified, the XML descriptor overrides the values set
or defaulted by the use of annotations, as described below.

The mapping files used by the application
developer must conform to the XML schema defined in Section 12.3
or to the previous versions of the XML schema, orm_3_1.xsd,
orm_3_0.xsd or orm_2_2.xsd,
defined in a previous version of this specification [1]
in accordance with the version specified by the orm.xml file.

The Jakarta Persistence persistence provider
may support use of older versions of the object/relational mapping
schema as well as the object/relational mapping schema defined in
Section 12.3, whether singly or
in combination when multiple mapping files are used.

12.2. XML Overriding Rules

This section defines the rules that apply
when the XML descriptor is used to override annotations, and the rules
pertaining to the interaction of XML elements specified as subelements
of the persistence-unit-defaults, entity-mappings, entity,
mapped-superclass, and embeddable elements.

12.2.1. persistence-unit-defaults Subelements

12.2.1.1. schema

The schema subelement applies to all
entities, tables, secondary tables, join tables, collection tables,
table generators, and sequence generators in the persistence unit.

The schema subelement is overridden by any
schema subelement of the entity-mappings element; any schema
element explicitly specified in the Table or SecondaryTable
annotation on an entity or any schema attribute on any table or
secondary-table subelement defined within an entity element; any
schema element explicitly specified in a TableGenerator annotation
or table-generator subelement; any schema element explicitly
specified in a SequenceGenerator annotation or sequence-generator
subelement; any schema element explicitly specified in a JoinTable
annotation or join-table subelement; and any schema element
explicitly specified in a CollectionTable annotation or
collection-table subelement.

12.2.1.2. catalog

The catalog subelement applies to all
entities, tables, secondary tables, join tables, collection tables,
table generators, and sequence generators in the persistence unit.

The catalog subelement is overridden by any
catalog subelement of the entity-mappings element; any catalog
element explicitly specified in the Table or SecondaryTable
annotation on an entity or any catalog attribute on any table or
secondary-table subelement defined within an entity XML element; any
catalog element explicitly specified in a TableGenerator annotation
or table-generator subelement; any catalog element explicitly
specified in a SequenceGenerator annotation or sequence-generator
subelement; any catalog element explicitly specified in a JoinTable
annotation or join-table subelement; and any catalog element
explicitly specified in a CollectionTable annotation or
collection-table subelement.

12.2.1.3. delimited-identifiers

The delimited-identifiers subelement
applies to the naming of database objects, as described in Section 2.15. It
specifies that all database table-, schema-, and column-level
identifiers in use for the persistence unit be treated as delimited
identifiers.

The delimited-identifiers subelement cannot
be overridden in this release.

12.2.1.4. access

The access subelement applies to all
managed classes in the persistence unit.

The access subelement is overridden by the
use of any annotations specifying mapping information on the fields or
properties of the entity class; by any Access annotation on the entity
class, mapped superclass, or embeddable class; by any access
subelement of the entity-mappings element; by any Access annotation
on a field or property of an entity class, mapped superclass, or
embeddable class; by any access attribute defined within an entity,
mapped-superclass, or embeddable XML element, or by any access
attribute defined within an id, embedded-id, version, basic,
embedded, many-to-one, one-to-one, one-to-many,
many-to-many, or element-collection element.

12.2.1.5. cascade-persist

The cascade-persist subelement applies to
all relationships in the persistence unit.

Specifying this subelement adds the cascade
persist option to all relationships in addition to any settings
specified in annotations or XML.

The cascade-persist subelement cannot be
overridden in this release.

The ability to override the cascade-persist
of the persistence-unit-defaults element will be added in a future
release of this specification.

12.2.1.6. entity-listeners

The entity-listeners subelement defines
default entity listeners for the persistence unit. These entity
listeners are called before any other entity listeners for an entity
unless the entity listener order is overridden within a
mapped-superclass or entity element, or the
ExcludeDefaultListeners annotation is present on the entity or mapped
superclass or the exclude-default-listeners subelement is specified
within the corresponding entity or mapped-superclass XML element.

12.2.2. Other Subelements of the entity-mappings element

12.2.2.1. package

The package subelement specifies the
package of the classes listed within the subelements and attributes of
the same mapping file only. The package subelement is overridden if
the fully qualified class name is specified for a class and the two
disagree.

12.2.2.2. schema

The schema subelement applies only to the
entities, tables, secondary tables, join tables, collection tables,
table generators, and sequence generators listed within the same mapping
file.

The schema subelement is overridden by any
schema element explicitly specified in the Table, SecondaryTable
, JoinTable, or CollectionTable annotation on an entity listed
within the mapping file or any schema attribute on any table or
secondary-table subelement defined within the entity element for
such an entity, or by any schema attribute on any join-table or
collection-table subelement of an attribute defined within the
attributes subelement of the entity element for such an entity, or
by the schema attribute of any table-generator or
sequence-generator element within the mapping file.

12.2.2.3. catalog

The catalog subelement applies only to the
entities, tables, secondary tables, join tables, collection tables,
table generators, and sequence generators listed within the same mapping
file.

The catalog subelement is overridden by any
catalog element explicitly specified in the Table, SecondaryTable
, JoinTable, or CollectionTable annotation on an entity listed
within the mapping file or any catalog attribute on any table or
secondary-table subelement defined within the entity element for
such an entity, or by any catalog attribute on any join-table or
collection-table subelement of an attribute defined within the
attributes subelement of the entity element for such an entity, or
by the catalog attribute of any table-generator or
sequence-generator element within the mapping file.

12.2.2.4. access

The access subelement applies to the
managed classes listed within the same mapping file.

The access subelement is overridden by the
use of any annotations specifying mapping information on the fields or
properties of the entity class; by any Access annotation on the entity
class, mapped superclass, or embeddable class; by any Access
annotation on a field or property of an entity class, mapped superclass,
or embeddable class; by any access attribute defined within an
entity, mapped-superclass, or embeddable XML element, or by any
access attribute defined within an id, embedded-id, version,
basic, embedded, many-to-one, one-to-one, one-to-many,
many-to-many, or element-collection element.

12.2.2.5. sequence-generator

The generator defined by the
sequence-generator subelement applies to the persistence unit. It is
undefined if multiple mapping files for the persistence unit contain
generators of the same name.

The generator defined is added to any
generators defined in annotations. If a generator of the same name is
defined in annotations, the generator defined by this subelement
overrides that definition.

12.2.2.6. table-generator

The generator defined by the
table-generator subelement applies to the persistence unit. It is
undefined if multiple mapping files for the persistence unit contain
generators of the same name.

The generator defined is added to any
generators defined in annotations. If a generator of the same name is
defined in annotations, the generator defined by this subelement
overrides that definition.

12.2.2.7. named-query

The named query defined by the named-query
subelement applies to the persistence unit. It is undefined if multiple
mapping files for the persistence unit contain named queries of the same
name.

The named query defined is added to the named
queries defined in annotations. If a named query of the same name is
defined in annotations, the named query defined by this subelement
overrides that definition.

12.2.2.8. named-native-query

The named native query defined by the
named-native-query subelement applies to the persistence unit. It is
undefined if multiple mapping files for the persistence unit contain
named queries of the same name.

The named native query defined is added to
the named native queries defined in annotations. If a named query of the
same name is defined in annotations, the named query defined by this
subelement overrides that definition.

12.2.2.9. named-stored-procedure-query

The named stored procedure query defined by
the named-stored-procedure-query subelement applies to the persistence
unit. It is undefined if multiple mapping files for the persistence unit
contain named stored procedure queries of the same name.

The named stored procedure query defined is
added to the named stored procedure queries defined in annotations. If a
named stored procedure query of the same name is defined in annotations,
the named stored procedure query defined by this subelement overrides
that definition.

12.2.2.10. sql-result-set-mapping

The SQL result set mapping defined by the
sql-result-set-mapping subelement applies to the persistence unit. It
is undefined if multiple mapping files for the persistence unit contain
SQL result set mappings of the same name.

The SQL result set mapping defined is added
to the SQL result set mappings defined in annotations. If a SQL result
set mapping of the same name is defined in annotations, the SQL result
set mapping defined by this subelement overrides that definition.

12.2.2.11. entity

The entity subelement defines an entity of
the persistence unit. It is undefined if multiple mapping files for the
persistence unit contain entries for the same entity.

The entity class may or may not have been
annotated as Entity. The subelements and attributes of the entity
element override as specified in Section 12.2.3.

12.2.2.12. mapped-superclass

The mapped-superclass subelement defines a
mapped superclass of the persistence unit. It is undefined if multiple
mapping files for the persistence unit contain entries for the same
mapped superclass.

The mapped superclass may or may not have
been annotated as MappedSuperclass. The subelements and attributes of
the mapped-superclass element override as specified in Section 12.2.4.

12.2.2.13. embeddable

The embeddable subelement defines an
embeddable class of the persistence unit. It is undefined if multiple
mapping files for the persistence unit contain entries for the same
embeddable class.

The embeddable class may or may not have
been annotated as Embeddable. The subelements and attributes of the
embeddable element override as specified in Section 12.2.5.

12.2.2.14. converter

The converter defined by the converter
subelement applies to the persistence unit. It is undefined if multiple
mapping files for the persistence unit contain converters for the same
target type.

The converter defined is added to the
converters defined in annotations. If a converter for the same target
type is defined in annotations, the converter defined by this subelement
overrides that definition.

12.2.3. entity Subelements and Attributes

These apply only to the entity for which they
are subelements or attributes, unless otherwise specified below.

12.2.3.1. metadata-complete

If the metadata-complete attribute of the
entity element is specified as true, any annotations on the entity
class (and its fields and properties) are ignored. When
metadata-complete is specified as true and XML attributes or
sub-elements of the entity element are omitted, the default values for
those attributes and elements are applied.

12.2.3.2. access

The access attribute defines the access
type for the entity. The access attribute overrides any access type
specified by the persistence-unit-defaults element or
entity-mappings element for the given entity. The access type for a
field or property of the entity may be overridden by specifying by
overriding the mapping for that field or property using the appropriate
XML subelement, as described in Section 12.2.3.26 below.

Caution must be exercised in overriding an
access type that was specified or defaulted using annotations, as doing
so may cause applications to break.

12.2.3.3. cacheable

The cacheable attribute defines whether the
entity should be cached or must not be cached when the
shared-cache-mode element of the persistence.xml file is specified
as ENABLE_SELECTIVE or DISABLE_SELECTIVE. If the Cacheable
annotation was specified for the entity, its value is overridden by this
attribute. The value of the cacheable attribute is inherited by
subclasses (unless otherwise overridden for a subclass by the
Cacheable annotation or cacheable XML attribute).

12.2.3.4. name

The name attribute defines the entity name.
The name attribute overrides the value of the entity name defined by
the name element of the Entity annotation (whether explicitly
specified or defaulted). Caution must be exercised in overriding the
entity name, as doing so may cause applications to break.

12.2.3.5. table

The table subelement overrides any Table
annotation (including defaulted Table values) on the entity. If a
table subelement is present, and attributes or subelements of that
table subelement are not explicitly specified, their default values
are applied.

12.2.3.6. secondary-table

The secondary-table subelement overrides
all SecondaryTable and SecondaryTables annotations (including
defaulted SecondaryTable values) on the entity. If a secondary-table
subelement is present, and attributes or subelements of that secondary
- table subelement are not explicitly specified, their default values
are applied.

12.2.3.7. primary-key-join-column

The primary-key-join-column subelement of
the entity element specifies a primary key column that is used to join
the table of an entity subclass to the primary table for the entity when
the joined strategy is used. The primary-key-join-column subelement
overrides all PrimaryKeyJoinColumn and PrimaryKeyJoinColumns
annotations (including defaulted PrimaryKeyJoinColumn values) on the
entity. If a primary-key-join-column subelement is present, and
attributes or subelements of that primary-key-join-column subelement
are not explicitly specified, their default values are applied.

12.2.3.8. id-class

The id-class subelement overrides any
IdClass annotation specified on the entity.

12.2.3.9. inheritance

The inheritance subelement overrides any
Inheritance annotation (including defaulted Inheritance values) on
the entity. If an inheritance subelement is present, and the
strategy attribute is not explicitly specified, its default value is
applied.

This element applies to the entity and its
subclasses (unless otherwise overridden for a subclass by an annotation
or XML element).

Support for the combination of inheritance
strategies is not required by this specification. Portable applications
should use only a single inheritance strategy within an entity
hierarchy.

12.2.3.10. discriminator-value

The discriminator-value subelement
overrides any DiscriminatorValue annotations (including defaulted
DiscriminatorValue values) on the entity.

12.2.3.11. discriminator-column

The discriminator-column subelement
overrides any DiscriminatorColumn annotation (including defaulted
DiscriminatorColumn values) on the entity. If a discriminator-column
subelement is present, and attributes of that discriminator-column
subelement are not explicitly specified, their default values are
applied.

This element applies to the entity and its
subclasses (unless otherwise overridden for a subclass by an annotation
or XML element).

12.2.3.12. sequence-generator

The generator defined by the
sequence-generator subelement is added to any generators defined in
annotations and any other generators defined in XML. If a generator of
the same name is defined in annotations, the generator defined by this
subelement overrides that definition. If a sequence-generator
subelement is present, and attributes or subelements of that
sequence-generator subelement are not explicitly specified, their
default values are applied.

The generator defined by the
sequence-generator subelement applies to the persistence unit. It is
undefined if multiple mapping files for the persistence unit contain
generators of the same name.

12.2.3.13. table-generator

The generator defined by the
table-generator subelement is added to any generators defined in
annotations and any other generators defined in XML. If a generator of
the same name is defined in annotations, the generator defined by this
subelement overrides that definition. If a table-generator subelement
is present, and attributes or subelements of that table-generator
subelement are not explicitly specified, their default values are
applied.

The generator defined by the
table-generator subelement applies to the persistence unit. It is
undefined if multiple mapping files for the persistence unit contain
generators of the same name.

12.2.3.14. attribute-override

The attribute-override subelement is
additive to any AttributeOverride or AttributeOverrides annotations
on the entity. It overrides any AttributeOverride elements for the
same attribute name. If an attribute-override subelement is present,
and attributes or subelements of that attribute-override subelement
are not explicitly specified, their default values are applied.

12.2.3.15. association-override

The association-override subelement is
additive to any AssociationOverride or AssociationOverrides
annotations on the entity. It overrides any AssociationOverride
elements for the same attribute name. If an association-override
subelement is present, and attributes or subelements of that
association-override subelement are not explicitly specified, their
default values are applied.

12.2.3.16. convert

The convert subelement is additive to any
Convert or Converts annotations on the entity. It overrides any
Convert annotation for the same attribute name. If a convert
subelement is present, and attributes or subelements of that convert
subelement are not explicitly specified, their default values are
applied.

12.2.3.17. named-entity-graph

The named-entity-graph subelement is
additive to any NamedEntityGraph annotations on the entity. It
overrides any NamedEntityGraph annotation with the same name.

12.2.3.18. named-query

The named query defined by the named-query
subelement is added to any named queries defined in annotations, and any
other named queries defined in XML. If a named query of the same name is
defined in annotations, the named query defined by this subelement
overrides that definition. If a named-query subelement is present, and
attributes or subelements of that named-query subelement are not
explicitly specified, their default values are applied.

The named query defined by the named-query
subelement applies to the persistence unit. It is undefined if multiple
mapping files for the persistence unit contain named queries of the same
name.

12.2.3.19. named-native-query

The named query defined by the
named-native-query subelement is added to any named queries defined in
annotations, and any other named queries defined in XML. If a named
query of the same name is defined in annotations, the named query
defined by this subelement overrides that definition. If a
named-native-query subelement is present, and attributes or
subelements of that named-native-query subelement are not explicitly
specified, their default values are applied.

The named native query defined by the
named-native-query subelement applies to the persistence unit. It is
undefined if multiple mapping files for the persistence unit contain
named queries of the same name.

12.2.3.20. named-stored-procedure-query

The named stored procedure query defined by
the named-stored-procedure-query subelement is added to any named
stored procedure queries defined in annotations, and any other named
stored procedure queries defined in XML. If a named stored procedure
query of the same name is defined in annotations, the named stored
procedure query defined by this subelement overrides that definition. If
a named-stored-procedure-query subelement is present, and attributes
or subelements of that named-stored-procedure-query subelement are not
explicitly specified, their default values are applied.

The named stored procedure query defined by
the named-stored-procedure-query subelement applies to the persistence
unit. It is undefined if multiple mapping files for the persistence unit
contain named stored procedure queries of the same name.

12.2.3.21. sql-result-set-mapping

The SQL result set mapping defined by the
sql-result-set-mapping is added to the SQL result set mappings defined
in annotations, and any other SQL result set mappings defined in XML. If
a SQL result set mapping of the same name is defined in annotations, the
SQL result set mapping defined by this subelement overrides that
definition. If a sql-result-set-mapping subelement is present, and
attributes or subelements of that sql-result-set-mapping subelement
are not explicitly specified, their default values are applied.

The SQL result set mapping defined by the
sql-result-set-mapping subelement applies to the persistence unit. It
is undefined if multiple mapping files for the persistence unit contain
SQL result set mappings of the same name.

12.2.3.22. exclude-default-listeners

The exclude-default-listeners subelement
applies whether or not the ExcludeDefaultListeners annotation was
specified on the entity.

This element causes the default entity
listeners to be excluded for the entity and its subclasses.

12.2.3.23. exclude-superclass-listeners

The exclude-superclass-listeners subelement
applies whether or not the ExcludeSuperclassListeners annotation was
specified on the entity.

This element causes any superclass listeners
to be excluded for the entity and its subclasses.

12.2.3.24. entity-listeners

The entity-listeners subelement overrides
any EntityListeners annotation on the entity.

These listeners apply to the entity and its
subclasses unless otherwise excluded.

12.2.3.25. pre-persist, post-persist, pre-remove, post-remove, pre-update, post-update, post-load

These subelements override any lifecycle
callback methods defined by the corresponding annotations on the entity.

12.2.3.26. attributes

The attributes element groups the mapping
subelements for the fields and properties of the entity. It may be
sparsely populated to include only a subset of the fields and
properties. If the value of metadata-complete is true, the
remainder of the attributes will be defaulted according to the default
rules. If metadata-complete is not specified, or is false, the
mappings for only those properties and fields that are explicitly
specified will be overridden.

id

The id subelement overrides the mapping for
the specified field or property. If an id subelement is present, and
attributes or subelements of that id subelement are not explicitly
specified, their default values are applied.

embedded-id

The embedded-id subelement overrides the
mapping for the specified field or property. If an embedded-id
subelement is present, and attributes or subelements of that
embedded-id subelement are not explicitly specified, their default
values are applied.

basic

The basic subelement overrides the mapping
for the specified field or property. If a basic subelement is present,
and attributes or subelements of that basic subelement are not
explicitly specified, their default values are applied.

version

The version subelement overrides the
mapping for the specified field or property. If a version subelement
is present, and attributes or subelements of that version subelement
are not explicitly specified, their default values are applied.

many-to-one

The many-to-one subelement overrides the
mapping for the specified field or property. If a many-to-one
subelement is present, and attributes or subelements of that
many-to-one subelement are not explicitly specified, their default
values are applied.

one-to-many

The one-to-many subelement overrides the
mapping for the specified field or property. If a one-to-many
subelement is present, and attributes or subelements of that
one-to-many subelement are not explicitly specified, their default
values are applied.

one-to-one

The one-to-one subelement overrides the
mapping for the specified field or property. If a one-to-one
subelement is present, and attributes or subelements of that
one-to-one subelement are not explicitly specified, their default
values are applied.

many-to-many

The many-to-many subelement overrides the
mapping for the specified field or property. If a many-to-many
subelement is present, and attributes or subelements of that
many-to-many subelement are not explicitly specified, their default
values are applied.

element-collection

The element-collection subelement overrides
the mapping for the specified field or property. If an
element-collection subelement is present, and attributes or
subelements of that element-collection subelement are not explicitly
specified, their default values are applied.

embedded

The embedded subelement overrides the
mapping for the specified field or property. If an embedded subelement
is present, and attributes or subelements of that embedded subelement
are not explicitly specified, their default values are applied.

transient

The transient subelement overrides the
mapping for the specified field or property.

12.2.4. mapped-superclass Subelements and Attributes

These apply only to the mapped-superclass for
which they are subelements or attributes, unless otherwise specified
below.

12.2.4.1. metadata-complete

If the metadata-complete attribute of the
mapped-superclass element is specified as true, any annotations on
the mapped superclass (and its fields and properties) are ignored. When
metadata-complete is specified as true and attributes or
sub-elements of the mapped-superclass element are omitted, the default
values for those attributes and elements are applied.

12.2.4.2. access

The access attribute defines the access
type for the mapped superclass. The access attribute overrides any
access type specified by the persistence-unit-defaults element or
entity-mappings element for the given mapped superclass. The access
type for a field or property of the mapped superclass may be overridden
by specifying by overriding the mapping for that field or property using
the appropriate XML subelement, as described in Section 12.2.4.8 below.

Caution must be exercised in overriding an
access type that was specified or defaulted using annotations, as doing
so may cause applications to break.

12.2.4.3. id-class

The id-class subelement overrides any
IdClass annotation specified on the mapped superclass.

12.2.4.4. exclude-default-listeners

The exclude-default-listeners subelement
applies whether or not the ExcludeDefaultListeners annotation was
specified on the mapped superclass.

This element causes the default entity
listeners to be excluded for the mapped superclass and its subclasses.

12.2.4.5. exclude-superclass-listeners

The exclude-superclass-listeners subelement
applies whether or not the ExcludeSuperclassListeners annotation was
specified on the mapped superclass.

This element causes any superclass listeners
to be excluded for the mapped superclass and its subclasses.

12.2.4.6. entity-listeners

The entity-listeners subelement overrides
any EntityListeners annotation on the mapped superclass.

These listeners apply to the mapped
superclass and its subclasses unless otherwise excluded.

12.2.4.7. pre-persist, post-persist, pre-remove, post-remove, pre-update, post-update, post-load

These subelements override any lifecycle
callback methods defined by the corresponding annotations on the mapped
superclass.

12.2.4.8. attributes

The attributes element groups the mapping
subelements for the fields and properties defined by the mapped
superclass. It may be sparsely populated to include only a subset of the
fields and properties. If the value of metadata-complete is true,
the remainder of the attributes will be defaulted according to the
default rules. If metadata-complete is not specified, or is false,
the mappings for only those properties and fields that are explicitly
specified will be overridden.

id

The id subelement overrides the mapping for
the specified field or property. If an id subelement is present, and
attributes or subelements of that id subelement are not explicitly
specified, their default values are applied.

embedded-id

The embedded-id subelement overrides the
mapping for the specified field or property. If an embedded-id
subelement is present, and attributes or subelements of that
embedded-id subelement are not explicitly specified, their default
values are applied.

basic

The basic subelement overrides the mapping
for the specified field or property. If a basic subelement is present,
and attributes or subelements of that basic subelement are not
explicitly specified, their default values are applied.

version

The version subelement overrides the
mapping for the specified field or property. If a version subelement
is present, and attributes or subelements of that version subelement
are not explicitly specified, their default values are applied.

many-to-one

The many-to-one subelement overrides the
mapping for the specified field or property. If a many-to-one
subelement is present, and attributes or subelements of that
many-to-one subelement are not explicitly specified, their default
values are applied.

one-to-many

The one-to-many subelement overrides the
mapping for the specified field or property. If a one-to-many
subelement is present, and attributes or subelements of that
one-to-many subelement are not explicitly specified, their default
values are applied.

one-to-one

The one-to-one subelement overrides the
mapping for the specified field or property. If a one-to-one
subelement is present, and attributes or subelements of that
one-to-one subelement are not explicitly specified, their default
values are applied.

many-to-many

The many-to-many subelement overrides the
mapping for the specified field or property. If a many-to-many
subelement is present, and attributes or subelements of that
many-to-many subelement are not explicitly specified, their default
values are applied.

element-collection

The element-collection subelement overrides
the mapping for the specified field or property. If an
element-collection subelement is present, and attributes or
subelements of that element-collection subelement are not explicitly
specified, their default values are applied.

embedded

The embedded subelement overrides the
mapping for the specified field or property. If an embedded subelement
is present, and attributes or subelements of that embedded subelement
are not explicitly specified, their default values are applied.

transient

The transient subelement overrides the
mapping for the specified field or property.

12.2.5. embeddable Subelements and Attributes

These apply only to the embeddable for which
they are subelements or attributes.

12.2.5.1. metadata-complete

If the metadata-complete attribute of the
embeddable element is specified as true, any annotations on the
embeddable class (and its fields and properties) are ignored. When
metadata-complete is specified as true and attributes and
sub-elements of the embeddable element are omitted, the default values
for those attributes and elements are applied.

12.2.5.2. access

The access attribute defines the access
type for the embeddable class. The access attribute overrides any
access type specified by the persistence-unit-defaults element or
entity-mappings element for the given embeddable class. The access
type for a field or property of the embeddable class may be overridden
by specifying by overriding the mapping for that field or property using
the appropriate XML subelement, as described in Section 12.2.5.3 below.

Caution must be exercised in overriding an
access type that was specified or defaulted using annotations, as doing
so may cause applications to break.

12.2.5.3. attributes

The attributes element groups the mapping
subelements for the fields and properties defined by the embeddable
class. It may be sparsely populated to include only a subset of the
fields and properties. If the value of metadata-complete is true,
the remainder of the attributes will be defaulted according to the
default rules. If metadata-complete is not specified, or is false,
the mappings for only those properties and fields that are explicitly
specified will be overridden.

basic

The basic subelement overrides the mapping
for the specified field or property. If a basic subelement is present,
and attributes or subelements of that basic subelement are not
explicitly specified, their default values are applied.

many-to-one

The many-to-one subelement overrides the
mapping for the specified field or property. If a many-to-one
subelement is present, and attributes or subelements of that
many-to-one subelement are not explicitly specified, their default
values are applied.

one-to-many

The one-to-many subelement overrides the
mapping for the specified field or property. If a one-to-many
subelement is present, and attributes or subelements of that
one-to-many subelement are not explicitly specified, their default
values are applied.

one-to-one

The one-to-one subelement overrides the
mapping for the specified field or property. If a one-to-one
subelement is present, and attributes or subelements of that
one-to-one subelement are not explicitly specified, their default
values are applied.

many-to-many

The many-to-many subelement overrides the
mapping for the specified field or property. If a many-to-many
subelement is present, and attributes or subelements of that
many-to-many subelement are not explicitly specified, their default
values are applied.

element-collection

The element-collection subelement overrides
the mapping for the specified field or property. If an
element-collection subelement is present, and attributes or
subelements of that element-collection subelement are not explicitly
specified, their default values are applied.

embedded

The embedded subelement overrides the
mapping for the specified field or property. If an embedded subelement
is present, and attributes or subelements of that embedded subelement
are not explicitly specified, their default values are applied.

transient

The transient subelement overrides the
mapping for the specified field or property.

12.3. XML Schema

This section provides the XML
object/relational mapping schema for use with the Persistence API.

<?xml version="1.0" encoding="UTF-8"?>

<!-- Jakarta Persistence API object/relational mapping file schema -->
<xsd:schema targetNamespace="https://jakarta.ee/xml/ns/persistence/orm"
 xmlns:orm="https://jakarta.ee/xml/ns/persistence/orm"
 xmlns:xsd="http://www.w3.org/2001/XMLSchema"
 elementFormDefault="qualified"
 attributeFormDefault="unqualified"
 version="3.2">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 This is the XML Schema for the persistence object/relational mapping file.
 The file may be named "META-INF/orm.xml" in the persistence
 archive or it may be named some other name which would be
 used to locate the file as resource on the classpath.

 Object/relational mapping files must indicate the object/relational
 mapping file schema by using the persistence namespace:

 https://jakarta.ee/xml/ns/persistence/orm

 and indicate the version of the schema by
 using the version element as shown below:

 <entity-mappings xmlns="https://jakarta.ee/xml/ns/persistence/orm"
 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 xsi:schemaLocation="https://jakarta.ee/xml/ns/persistence/orm
 https://jakarta.ee/xml/ns/persistence/orm/orm_3_2.xsd"
 version="3.2">
 ...
 </entity-mappings>

 ]]></xsd:documentation>
 </xsd:annotation>

 <xsd:complexType name="emptyType"/>

 <xsd:simpleType name="versionType">
 <xsd:restriction base="xsd:token">
 <xsd:pattern value="[0-9]+(\.[0-9]+)*"/>
 </xsd:restriction>
 </xsd:simpleType>

 <!-- **************************************************** -->

 <xsd:element name="entity-mappings">
 <xsd:complexType>
 <xsd:annotation>
 <xsd:documentation>

 The entity-mappings element is the root element of a mapping
 file. It contains the following four types of elements:

 1. The persistence-unit-metadata element contains metadata
 for the entire persistence unit. It is undefined if this element
 occurs in multiple mapping files within the same persistence unit.

 2. The package, schema, catalog and access elements apply to all of
 the entity, mapped-superclass and embeddable elements defined in
 the same file in which they occur.

 3. The sequence-generator, table-generator, converter, named-query,
 named-native-query, named-stored-procedure-query, and
 sql-result-set-mapping elements are global to the persistence
 unit. It is undefined to have more than one sequence-generator
 or table-generator of the same name in the same or different
 mapping files in a persistence unit. It is undefined to have
 more than one named-query, named-native-query, sql-result-set-mapping,
 or named-stored-procedure-query of the same name in the same
 or different mapping files in a persistence unit. It is also
 undefined to have more than one converter for the same target
 type in the same or different mapping files in a persistence unit.

 4. The entity, mapped-superclass and embeddable elements each define
 the mapping information for a managed persistent class. The mapping
 information contained in these elements may be complete or it may
 be partial.

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string"
 minOccurs="0"/>
 <xsd:element name="persistence-unit-metadata"
 type="orm:persistence-unit-metadata"
 minOccurs="0"/>
 <xsd:element name="package" type="xsd:string"
 minOccurs="0"/>
 <xsd:element name="schema" type="xsd:string"
 minOccurs="0"/>
 <xsd:element name="catalog" type="xsd:string"
 minOccurs="0"/>
 <xsd:element name="access" type="orm:access-type"
 minOccurs="0"/>
 <xsd:element name="sequence-generator" type="orm:sequence-generator"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="table-generator" type="orm:table-generator"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="named-query" type="orm:named-query"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="named-native-query" type="orm:named-native-query"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="named-stored-procedure-query"
 type="orm:named-stored-procedure-query"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="sql-result-set-mapping"
 type="orm:sql-result-set-mapping"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="mapped-superclass" type="orm:mapped-superclass"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="entity" type="orm:entity"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="embeddable" type="orm:embeddable"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="converter" type="orm:converter"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="version" type="orm:versionType"
 fixed="3.2" use="required"/>
 </xsd:complexType>
 </xsd:element>

 <!-- **************************************************** -->

 <xsd:complexType name="persistence-unit-metadata">
 <xsd:annotation>
 <xsd:documentation>

 Metadata that applies to the persistence unit and not just to
 the mapping file in which it is contained.

 If the xml-mapping-metadata-complete element is specified,
 the complete set of mapping metadata for the persistence unit
 is contained in the XML mapping files for the persistence unit.

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="xml-mapping-metadata-complete" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="persistence-unit-defaults"
 type="orm:persistence-unit-defaults"
 minOccurs="0"/>
 </xsd:sequence>
 </xsd:complexType>

 <!-- **************************************************** -->

 <xsd:complexType name="persistence-unit-defaults">
 <xsd:annotation>
 <xsd:documentation>

 These defaults are applied to the persistence unit as a whole
 unless they are overridden by local annotation or XML
 element settings.

 schema - Used as the schema for all tables, secondary tables, join
 tables, collection tables, sequence generators, and table
 generators that apply to the persistence unit
 catalog - Used as the catalog for all tables, secondary tables, join
 tables, collection tables, sequence generators, and table
 generators that apply to the persistence unit
 delimited-identifiers - Used to treat database identifiers as
 delimited identifiers.
 access - Used as the access type for all managed classes in
 the persistence unit
 cascade-persist - Adds cascade-persist to the set of cascade options
 in all entity relationships of the persistence unit
 entity-listeners - List of default entity listeners to be invoked
 on each entity in the persistence unit.
 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="schema" type="xsd:string"
 minOccurs="0"/>
 <xsd:element name="catalog" type="xsd:string"
 minOccurs="0"/>
 <xsd:element name="delimited-identifiers" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="access" type="orm:access-type"
 minOccurs="0"/>
 <xsd:element name="cascade-persist" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="entity-listeners" type="orm:entity-listeners"
 minOccurs="0"/>
 </xsd:sequence>
 </xsd:complexType>

 <!-- **************************************************** -->

 <xsd:complexType name="entity">
 <xsd:annotation>
 <xsd:documentation>

 Defines the settings and mappings for an entity. Is allowed to be
 sparsely populated and used in conjunction with the annotations.
 Alternatively, the metadata-complete attribute can be used to
 indicate that no annotations on the entity class (and its fields
 or properties) are to be processed. If this is the case then
 the defaulting rules for the entity and its subelements will
 be recursively applied.

 @Target(TYPE) @Retention(RUNTIME)
 public @interface Entity {
 String name() default "";
 }
