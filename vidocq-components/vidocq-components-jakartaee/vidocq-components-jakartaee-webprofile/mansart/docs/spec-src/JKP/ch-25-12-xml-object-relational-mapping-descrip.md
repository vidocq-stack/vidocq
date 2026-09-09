# 12. XML Object/Relational Mapping Descriptor (part 2/3)

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="table" type="orm:table"
 minOccurs="0"/>
 <xsd:element name="secondary-table" type="orm:secondary-table"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:sequence>
 <xsd:element name="primary-key-join-column"
 type="orm:primary-key-join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="primary-key-foreign-key"
 type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:element name="id-class" type="orm:id-class" minOccurs="0"/>
 <xsd:element name="inheritance" type="orm:inheritance" minOccurs="0"/>
 <xsd:element name="discriminator-value" type="orm:discriminator-value"
 minOccurs="0"/>
 <xsd:element name="discriminator-column"
 type="orm:discriminator-column"
 minOccurs="0"/>
 <xsd:element name="sequence-generator" type="orm:sequence-generator"
 minOccurs="0"/>
 <xsd:element name="table-generator" type="orm:table-generator"
 minOccurs="0"/>
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
 <xsd:element name="exclude-default-listeners" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="exclude-superclass-listeners" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="entity-listeners" type="orm:entity-listeners"
 minOccurs="0"/>
 <xsd:element name="pre-persist" type="orm:pre-persist" minOccurs="0"/>
 <xsd:element name="post-persist" type="orm:post-persist"
 minOccurs="0"/>
 <xsd:element name="pre-remove" type="orm:pre-remove" minOccurs="0"/>
 <xsd:element name="post-remove" type="orm:post-remove" minOccurs="0"/>
 <xsd:element name="pre-update" type="orm:pre-update" minOccurs="0"/>
 <xsd:element name="post-update" type="orm:post-update" minOccurs="0"/>
 <xsd:element name="post-load" type="orm:post-load" minOccurs="0"/>
 <xsd:element name="attribute-override" type="orm:attribute-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="association-override"
 type="orm:association-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="convert" type="orm:convert"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="named-entity-graph" type="orm:named-entity-graph"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="attributes" type="orm:attributes" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 <xsd:attribute name="cacheable" type="xsd:boolean"/>
 <xsd:attribute name="metadata-complete" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="access-type">
 <xsd:annotation>
 <xsd:documentation>

 This element determines how the persistence provider accesses the
 state of an entity or embedded object.

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="PROPERTY"/>
 <xsd:enumeration value="FIELD"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="association-override">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(AssociationOverrides.class)
 @Target({TYPE, METHOD, FIELD}) @Retention(RUNTIME)
 public @interface AssociationOverride {
 String name();
 JoinColumn[] joinColumns() default{};
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
 JoinTable joinTable() default @JoinTable;
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:choice>
 <xsd:sequence>
 <xsd:element name="join-column" type="orm:join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:element name="join-table" type="orm:join-table"
 minOccurs="0"/>
 </xsd:choice>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="attribute-override">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(AttributeOverrides.class)
 @Target({TYPE, METHOD, FIELD}) @Retention(RUNTIME)
 public @interface AttributeOverride {
 String name();
 Column column();
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="column" type="orm:column"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="attributes">
 <xsd:annotation>
 <xsd:documentation>

 This element contains the entity field or property mappings.
 It may be sparsely populated to include only a subset of the
 fields or properties. If metadata-complete for the entity is true
 then the remainder of the attributes will be defaulted according
 to the default rules.

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:choice>
 <xsd:element name="id" type="orm:id"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="embedded-id" type="orm:embedded-id"
 minOccurs="0"/>
 </xsd:choice>
 <xsd:element name="basic" type="orm:basic"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="version" type="orm:version"
 minOccurs="0"/>
 <xsd:element name="many-to-one" type="orm:many-to-one"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="one-to-many" type="orm:one-to-many"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="one-to-one" type="orm:one-to-one"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="many-to-many" type="orm:many-to-many"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="element-collection" type="orm:element-collection"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="embedded" type="orm:embedded"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="transient" type="orm:transient"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="basic">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Basic {
 FetchType fetch() default FetchType.EAGER;
 boolean optional() default true;
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="column" type="orm:column" minOccurs="0"/>
 <xsd:choice>
 <xsd:element name="lob" type="orm:lob" minOccurs="0"/>
 <xsd:element name="temporal" type="orm:temporal" minOccurs="0"/>
 <xsd:element name="enumerated" type="orm:enumerated" minOccurs="0"/>
 <xsd:element name="convert" type="orm:convert" minOccurs="0"/>
 </xsd:choice>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="fetch" type="orm:fetch-type"/>
 <xsd:attribute name="optional" type="xsd:boolean"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="cascade-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum CascadeType { ALL, PERSIST, MERGE, REMOVE, REFRESH, DETACH }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="cascade-all" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="cascade-persist" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="cascade-merge" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="cascade-remove" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="cascade-refresh" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="cascade-detach" type="orm:emptyType"
 minOccurs="0"/>
 </xsd:sequence>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="check-constraint">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface CheckConstraint {
 String name() default "";
 String constraint();
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="constraint" type="xsd:string" use="required"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="collection-table">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface CollectionTable {
 String name() default "";
 String catalog() default "";
 String schema() default "";
 JoinColumn[] joinColumns() default {};
 ForeignKey foreignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
 UniqueConstraint[] uniqueConstraints() default {};
 Index[] indexes() default {};
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:sequence>
 <xsd:element name="join-column" type="orm:join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:element name="unique-constraint" type="orm:unique-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="index" type="orm:index"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="catalog" type="xsd:string"/>
 <xsd:attribute name="schema" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="column">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Column {
 String name() default "";
 boolean unique() default false;
 boolean nullable() default true;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 int length() default 255;
 int precision() default 0; // decimal precision
 int scale() default 0; // decimal scale
 int secondPrecision() default -1; //fractional second precision
 CheckConstraint[] check() default {};
 String comment() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="comment" type="xsd:string" minOccurs="0" />
 <xsd:element name="check-constraint" type="orm:check-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="unique" type="xsd:boolean"/>
 <xsd:attribute name="nullable" type="xsd:boolean"/>
 <xsd:attribute name="insertable" type="xsd:boolean"/>
 <xsd:attribute name="updatable" type="xsd:boolean"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 <xsd:attribute name="table" type="xsd:string"/>
 <xsd:attribute name="length" type="xsd:int"/>
 <xsd:attribute name="precision" type="xsd:int"/>
 <xsd:attribute name="scale" type="xsd:int"/>
 <xsd:attribute name="second-precision" type="xsd:int"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="column-result">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({}) @Retention(RUNTIME)
 public @interface ColumnResult {
 String name();
 Class<?> type() default void.class;
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="class" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="constraint-mode">
 <xsd:annotation>
 <xsd:documentation>

 public enum ConstraintMode { CONSTRAINT, NO_CONSTRAINT, PROVIDER_DEFAULT }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="CONSTRAINT"/>
 <xsd:enumeration value="NO_CONSTRAINT"/>
 <xsd:enumeration value="PROVIDER_DEFAULT"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="constructor-result">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({}) @Retention(RUNTIME)
 public @interface ConstructorResult {
 Class<?> targetClass();
 ColumnResult[] columns();
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="column" type="orm:column-result"
 maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="target-class" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="convert">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Repeatable(Converts.class)
 @Target({TYPE, METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Convert {
 Class<? extends AttributeConverter> converter() default AttributeConverter.class;
 String attributeName() default "";
 boolean disableConversion() default false;
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="converter" type="xsd:string"/>
 <xsd:attribute name="attribute-name" type="xsd:string"/>
 <xsd:attribute name="disable-conversion" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="converter">
 <xsd:annotation>
 <xsd:documentation>

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface Converter {
 boolean autoApply() default false;
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 <xsd:attribute name="auto-apply" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="discriminator-column">
 <xsd:annotation>
 <xsd:documentation>

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface DiscriminatorColumn {
 String name() default "DTYPE";
 DiscriminatorType discriminatorType() default STRING;
 String columnDefinition() default "";
 String options() default "";
 int length() default 31;
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="discriminator-type" type="orm:discriminator-type"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 <xsd:attribute name="length" type="xsd:int"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="discriminator-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum DiscriminatorType { STRING, CHAR, INTEGER }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="STRING"/>
 <xsd:enumeration value="CHAR"/>
 <xsd:enumeration value="INTEGER"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:simpleType name="discriminator-value">
 <xsd:annotation>
 <xsd:documentation>

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface DiscriminatorValue {
 String value();
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:string"/>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="element-collection">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface ElementCollection {
 Class<?> targetClass() default void.class;
 FetchType fetch() default FetchType.LAZY;
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:choice>
 <xsd:element name="order-by" type="orm:order-by"
 minOccurs="0"/>
 <xsd:element name="order-column" type="orm:order-column"
 minOccurs="0"/>
 </xsd:choice>
 <xsd:choice>
 <xsd:element name="map-key" type="orm:map-key"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="map-key-class" type="orm:map-key-class"
 minOccurs="0"/>
 <xsd:choice>
 <xsd:element name="map-key-temporal"
 type="orm:temporal"
 minOccurs="0"/>
 <xsd:element name="map-key-enumerated"
 type="orm:enumerated"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="map-key-attribute-override"
 type="orm:attribute-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="map-key-convert" type="orm:convert"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 </xsd:choice>
 <xsd:choice>
 <xsd:element name="map-key-column"
 type="orm:map-key-column"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="map-key-join-column"
 type="orm:map-key-join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="map-key-foreign-key"
 type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 </xsd:choice>
 </xsd:sequence>
 </xsd:choice>
 <xsd:choice>
 <xsd:sequence>
 <xsd:element name="column" type="orm:column" minOccurs="0"/>
 <xsd:choice>
 <xsd:element name="temporal"
 type="orm:temporal"
 minOccurs="0"/>
 <xsd:element name="enumerated"
 type="orm:enumerated"
 minOccurs="0"/>
 <xsd:element name="lob"
 type="orm:lob"
 minOccurs="0"/>
 </xsd:choice>
 </xsd:sequence>
 <xsd:sequence>
 <xsd:element name="attribute-override"
 type="orm:attribute-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="association-override"
 type="orm:association-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="convert" type="orm:convert"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 </xsd:choice>
 <xsd:element name="collection-table" type="orm:collection-table"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="target-class" type="xsd:string"/>
 <xsd:attribute name="fetch" type="orm:fetch-type"/>
 <xsd:attribute name="access" type="orm:access-type"/>
</xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="embeddable">
 <xsd:annotation>
 <xsd:documentation>

 Defines the settings and mappings for embeddable objects. Is
 allowed to be sparsely populated and used in conjunction with
 the annotations. Alternatively, the metadata-complete attribute
 can be used to indicate that no annotations are to be processed
 in the class. If this is the case then the defaulting rules will
 be recursively applied.

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface Embeddable {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="attributes" type="orm:embeddable-attributes"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 <xsd:attribute name="metadata-complete" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="embeddable-attributes">
 <xsd:sequence>
 <xsd:element name="basic" type="orm:basic"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="many-to-one" type="orm:many-to-one"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="one-to-many" type="orm:one-to-many"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="one-to-one" type="orm:one-to-one"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="many-to-many" type="orm:many-to-many"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="element-collection" type="orm:element-collection"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="embedded" type="orm:embedded"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="transient" type="orm:transient"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 </xsd:complexType>

 <!-- **************************************************** -->

 <xsd:complexType name="embedded">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Embedded {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="attribute-override" type="orm:attribute-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="association-override"
 type="orm:association-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="convert" type="orm:convert"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="embedded-id">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface EmbeddedId {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="attribute-override" type="orm:attribute-override"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="entity-listener">
 <xsd:annotation>
 <xsd:documentation>

 Defines an entity listener to be invoked at lifecycle events
 for the entities that list this listener.

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="pre-persist" type="orm:pre-persist" minOccurs="0"/>
 <xsd:element name="post-persist" type="orm:post-persist"
 minOccurs="0"/>
 <xsd:element name="pre-remove" type="orm:pre-remove" minOccurs="0"/>
 <xsd:element name="post-remove" type="orm:post-remove" minOccurs="0"/>
 <xsd:element name="pre-update" type="orm:pre-update" minOccurs="0"/>
 <xsd:element name="post-update" type="orm:post-update" minOccurs="0"/>
 <xsd:element name="post-load" type="orm:post-load" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="entity-listeners">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface EntityListeners {
 Class<?>[] value();
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="entity-listener" type="orm:entity-listener"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="entity-result">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({}) @Retention(RUNTIME)
 public @interface EntityResult {
 Class<?> entityClass();
 LockModeType lockMode() default LockModeType.OPTIMISTIC;
 FieldResult[] fields() default {};
 String discriminatorColumn() default "";
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="lock-mode" type="orm:lock-mode-type" minOccurs="0"/>
 <xsd:element name="field-result" type="orm:field-result"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="entity-class" type="xsd:string" use="required"/>
 <xsd:attribute name="discriminator-column" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="enum-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum EnumType { ORDINAL, STRING }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="ORDINAL"/>
 <xsd:enumeration value="STRING"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:simpleType name="enumerated">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Enumerated {
 EnumType value() default ORDINAL;
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="orm:enum-type"/>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:simpleType name="fetch-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum FetchType { LAZY, EAGER }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="LAZY"/>
 <xsd:enumeration value="EAGER"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="field-result">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface FieldResult {
 String name();
 String column();
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="column" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="foreign-key">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface ForeignKey {
 String name() default "";
 ConstraintMode value() default CONSTRAINT;
 String foreign-key-definition() default "";
 String options() default "";
 }

 Note that the elements that embed the use of the annotation
 default this use as @ForeignKey(PROVIDER_DEFAULT).

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="constraint-mode" type="orm:constraint-mode"/>
 <xsd:attribute name="foreign-key-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="generated-value">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface GeneratedValue {
 GenerationType strategy() default AUTO;
 String generator() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="strategy" type="orm:generation-type"/>
 <xsd:attribute name="generator" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="generation-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum GenerationType { TABLE, SEQUENCE, IDENTITY, UUID, AUTO }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="TABLE"/>
 <xsd:enumeration value="SEQUENCE"/>
 <xsd:enumeration value="IDENTITY"/>
 <xsd:enumeration value="UUID"/>
 <xsd:enumeration value="AUTO"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="id">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Id {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="column" type="orm:column"
 minOccurs="0"/>
 <xsd:element name="generated-value" type="orm:generated-value"
 minOccurs="0"/>
 <xsd:element name="temporal" type="orm:temporal"
 minOccurs="0"/>
 <xsd:element name="table-generator" type="orm:table-generator"
 minOccurs="0"/>
 <xsd:element name="sequence-generator" type="orm:sequence-generator"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="id-class">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface IdClass {
 Class<?> value();
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="index">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface Index {
 String name() default "";
 String columnList();
 boolean unique() default false;
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="column-list" type="xsd:string" use="required"/>
 <xsd:attribute name="unique" type="xsd:boolean"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="inheritance">
 <xsd:annotation>
 <xsd:documentation>

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface Inheritance {
 InheritanceType strategy() default InheritanceType.SINGLE_TABLE;
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="strategy" type="orm:inheritance-type"/>
 </xsd:complexType>

 <!-- **************************************************** -->

 <xsd:simpleType name="inheritance-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum InheritanceType { SINGLE_TABLE, TABLE_PER_CLASS, JOINED }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="SINGLE_TABLE"/>
 <xsd:enumeration value="JOINED"/>
 <xsd:enumeration value="TABLE_PER_CLASS"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="join-column">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(JoinColumns.class)
 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface JoinColumn {
 String name() default "";
 String referencedColumnName() default "";
 boolean unique() default false;
 boolean nullable() default true;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 ForeignKey foreignKey() default @ForeignKey();
 CheckConstraint[] check() default {};
 String comment() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="comment" type="xsd:string" minOccurs="0" />
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 <xsd:element name="check-constraint" type="orm:check-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="referenced-column-name" type="xsd:string"/>
 <xsd:attribute name="unique" type="xsd:boolean"/>
 <xsd:attribute name="nullable" type="xsd:boolean"/>
 <xsd:attribute name="insertable" type="xsd:boolean"/>
 <xsd:attribute name="updatable" type="xsd:boolean"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 <xsd:attribute name="table" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="join-table">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface JoinTable {
 String name() default "";
 String catalog() default "";
 String schema() default "";
 JoinColumn[] joinColumns() default {};
 JoinColumn[] inverseJoinColumns() default {};
 ForeignKey foreignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
 ForeignKey inverseForeignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
 UniqueConstraint[] uniqueConstraints() default {};
 Index[] indexes() default {};
 CheckConstraint[] check() default {};
 String comment() default "";
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="comment" type="xsd:string" minOccurs="0" />
 <xsd:sequence>
 <xsd:element name="join-column" type="orm:join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:sequence>
 <xsd:element name="inverse-join-column" type="orm:join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="inverse-foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:element name="unique-constraint" type="orm:unique-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="index" type="orm:index"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="check-constraint" type="orm:check-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="catalog" type="xsd:string"/>
 <xsd:attribute name="schema" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="lob">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Lob {}

 </xsd:documentation>
 </xsd:annotation>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="lock-mode-type">
 <xsd:annotation>
 <xsd:documentation>

 public enum LockModeType implements FindOption, RefreshOption { READ, WRITE, OPTIMISTIC, OPTIMISTIC_FORCE_INCREMENT, PESSIMISTIC_READ, PESSIMISTIC_WRITE, PESSIMISTIC_FORCE_INCREMENT, NONE}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="READ"/>
 <xsd:enumeration value="WRITE"/>
 <xsd:enumeration value="OPTIMISTIC"/>
 <xsd:enumeration value="OPTIMISTIC_FORCE_INCREMENT"/>
 <xsd:enumeration value="PESSIMISTIC_READ"/>
 <xsd:enumeration value="PESSIMISTIC_WRITE"/>
 <xsd:enumeration value="PESSIMISTIC_FORCE_INCREMENT"/>
 <xsd:enumeration value="NONE"/>

 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

<xsd:complexType name="many-to-many">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface ManyToMany {
 Class<?> targetEntity() default void.class;
 CascadeType[] cascade() default {};
 FetchType fetch() default FetchType.LAZY;
 String mappedBy() default "";
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:choice>
 <xsd:element name="order-by" type="orm:order-by"
 minOccurs="0"/>
 <xsd:element name="order-column" type="orm:order-column"
 minOccurs="0"/>
 </xsd:choice>
 <xsd:choice>
 <xsd:element name="map-key" type="orm:map-key"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="map-key-class" type="orm:map-key-class"
 minOccurs="0"/>
 <xsd:choice>
 <xsd:element name="map-key-temporal"
 type="orm:temporal"
 minOccurs="0"/>
 <xsd:element name="map-key-enumerated"
 type="orm:enumerated"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="map-key-attribute-override"
 type="orm:attribute-override"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="map-key-convert" type="orm:convert"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 </xsd:choice>
 <xsd:choice>
 <xsd:element name="map-key-column" type="orm:map-key-column"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="map-key-join-column"
 type="orm:map-key-join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="map-key-foreign-key"
 type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 </xsd:choice>
 </xsd:sequence>
 </xsd:choice>
 <xsd:element name="join-table" type="orm:join-table"
 minOccurs="0"/>
 <xsd:element name="cascade" type="orm:cascade-type"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="target-entity" type="xsd:string"/>
 <xsd:attribute name="fetch" type="orm:fetch-type"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 <xsd:attribute name="mapped-by" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="many-to-one">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface ManyToOne {
 Class<?> targetEntity() default void.class;
 CascadeType[] cascade() default {};
 FetchType fetch() default FetchType.EAGER;
 boolean optional() default true;
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:choice>
 <xsd:sequence>
 <xsd:element name="join-column" type="orm:join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:element name="join-table" type="orm:join-table"
 minOccurs="0"/>
 </xsd:choice>
 <xsd:element name="cascade" type="orm:cascade-type"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="target-entity" type="xsd:string"/>
 <xsd:attribute name="fetch" type="orm:fetch-type"/>
 <xsd:attribute name="optional" type="xsd:boolean"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 <xsd:attribute name="maps-id" type="xsd:string"/>
 <xsd:attribute name="id" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="map-key">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface MapKey {
 String name() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="map-key-class">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface MapKeyClass {
 Class<?> value();
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="map-key-column">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface MapKeyColumn {
 String name() default "";
 boolean unique() default false;
 boolean nullable() default false;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 int length() default 255;
 int precision() default 0; // decimal precision
 int scale() default 0; // decimal scale
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="unique" type="xsd:boolean"/>
 <xsd:attribute name="nullable" type="xsd:boolean"/>
 <xsd:attribute name="insertable" type="xsd:boolean"/>
 <xsd:attribute name="updatable" type="xsd:boolean"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 <xsd:attribute name="table" type="xsd:string"/>
 <xsd:attribute name="length" type="xsd:int"/>
 <xsd:attribute name="precision" type="xsd:int"/>
 <xsd:attribute name="scale" type="xsd:int"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="map-key-join-column">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(MapKeyJoinColumns.class)
 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface MapKeyJoinColumn {
 String name() default "";
 String referencedColumnName() default "";
 boolean unique() default false;
 boolean nullable() default false;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 String table() default "";
 ForeignKey foreignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="referenced-column-name" type="xsd:string"/>
 <xsd:attribute name="unique" type="xsd:boolean"/>
 <xsd:attribute name="nullable" type="xsd:boolean"/>
 <xsd:attribute name="insertable" type="xsd:boolean"/>
 <xsd:attribute name="updatable" type="xsd:boolean"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 <xsd:attribute name="table" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="mapped-superclass">
 <xsd:annotation>
 <xsd:documentation>

 Defines the settings and mappings for a mapped superclass. Is
 allowed to be sparsely populated and used in conjunction with
 the annotations. Alternatively, the metadata-complete attribute
 can be used to indicate that no annotations are to be processed
 If this is the case then the defaulting rules will be recursively
 applied.

 @Target(TYPE) @Retention(RUNTIME)
 public @interface MappedSuperclass {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="id-class" type="orm:id-class" minOccurs="0"/>
 <xsd:element name="exclude-default-listeners" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="exclude-superclass-listeners" type="orm:emptyType"
 minOccurs="0"/>
 <xsd:element name="entity-listeners" type="orm:entity-listeners"
 minOccurs="0"/>
 <xsd:element name="pre-persist" type="orm:pre-persist" minOccurs="0"/>
 <xsd:element name="post-persist" type="orm:post-persist"
 minOccurs="0"/>
 <xsd:element name="pre-remove" type="orm:pre-remove" minOccurs="0"/>
 <xsd:element name="post-remove" type="orm:post-remove" minOccurs="0"/>
 <xsd:element name="pre-update" type="orm:pre-update" minOccurs="0"/>
 <xsd:element name="post-update" type="orm:post-update" minOccurs="0"/>
 <xsd:element name="post-load" type="orm:post-load" minOccurs="0"/>
 <xsd:element name="attributes" type="orm:attributes" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 <xsd:attribute name="metadata-complete" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="named-attribute-node">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface NamedAttributeNode {
 String value();
 String subgraph() default "";
 String keySubgraph() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="subgraph" type="xsd:string"/>
 <xsd:attribute name="key-subgraph" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="named-entity-graph">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(NamedEntityGraphs.class)
 @Target({TYPE}) @Retention(RUNTIME)
 public @interface NamedEntityGraph {
 String name() default "";
 NamedAttributeNode[] attributeNodes() default {};
 boolean includeAllAttributes() default false;
 NamedSubgraph[] subgraphs() default {};
 NamedSubGraph[] subclassSubgraphs() default {};
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="named-attribute-node"
 type="orm:named-attribute-node"
 minOccurs="0"
 maxOccurs="unbounded"/>
 <xsd:element name="subgraph"
 type="orm:named-subgraph"
 minOccurs="0"
 maxOccurs="unbounded"/>
 <xsd:element name="subclass-subgraph"
 type="orm:named-subgraph"
 minOccurs="0"
 maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="include-all-attributes" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="named-native-query">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Repeatable(NamedNativeQueries.class)
 @Target({TYPE}) @Retention(RUNTIME)
 public @interface NamedNativeQuery {
 String name();
 String query();
 QueryHint[] hints() default {};
 Class<?> resultClass() default void.class;
 String resultSetMapping() default ""; //named SqlResultSetMapping
 EntityResult[] entities() default {};
 ConstructorResult[] classes() default {};
 ColumnResult[] columns() default {};
 }
