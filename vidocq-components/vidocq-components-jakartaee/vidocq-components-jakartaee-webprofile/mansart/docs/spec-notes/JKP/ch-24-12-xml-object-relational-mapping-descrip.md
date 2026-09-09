# 12. XML Object/Relational Mapping Description - Normative Requirements

Extracted from: ch-24-12-xml-object-relational-mapping-descrip.md
Section: 12 (part 1/3)

- `12.2.3` Caution must be exercised in overriding an access type that was specified or defaulted using annotations, as doing so may cause applications to break.
- `12.2.3` Caution must be exercised in overriding the entity name, as doing so may cause applications to break.
- `12.2.3` Support for the combination of inheritance strategies is not required by this specification.
- `12.2.3` The cacheable attribute defines whether the entity should be cached or must not be cached when the shared-cache-mode element of the persistence.xml file is specified as ENABLE_SELECTIVE or DISABLE_SELECTIVE.
- `12.2.4` Caution must be exercised in overriding an access type that was specified or defaulted using annotations, as doing so may cause applications to break.
- `12.2.5` </xsd:documentation> </xsd:annotation> <xsd:sequence> <xsd:element name="description" type="xsd:string" minOccurs="0"/> <xsd:element name="persistence-unit-metadata" type="orm:persistence-unit-metadata" minOccurs="0"/> <xsd:element name="package" type="xsd:string" minOccurs="0"/> <xsd:element name="schema" type="xsd:string" minOccurs="0"/> <xsd:element name="catalog" type="xsd:string" minOccurs="0"/> <xsd:element name="access" type="orm:access-type" minOccurs="0"/> <xsd:element name="sequence-generator" type="orm:sequence-generator" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="table-generator" type="orm:table-generator" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="named-query" type="orm:named-query" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="named-native-query" type="orm:named-native-query" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="named-stored-procedure-query" type="orm:named-stored-procedure-query" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="sql-result-set-mapping" type="orm:sql-result-set-mapping" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="mapped-superclass" type="orm:mapped-superclass" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="entity" type="orm:entity" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="embeddable" type="orm:embeddable" minOccurs="0" maxOccurs="unbounded"/> <xsd:element name="converter" type="orm:converter" minOccurs="0" maxOccurs="unbounded"/> </xsd:sequence> <xsd:attribute name="version" type="orm:versionType" fixed="3.2" use="required"/> </xsd:complexType> </xsd:element>
- `12.2.5` Caution must be exercised in overriding an access type that was specified or defaulted using annotations, as doing so may cause applications to break.
- `12.2.5` Object/relational mapping files must indicate the object/relational mapping file schema by using the persistence namespace:

---
Requirements: 8
