# 12. XML Object/Relational Mapping Descriptor (part 3/3)

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="query" type="xsd:string"/>
 <xsd:element name="hint" type="orm:query-hint"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="entity-result" type="orm:entity-result"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="constructor-result" type="orm:constructor-result"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="column-result" type="orm:column-result"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="result-class" type="xsd:string"/>
 <xsd:attribute name="result-set-mapping" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="named-query">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(NamedQueries.class)
 @Target({TYPE}) @Retention(RUNTIME)
 public @interface NamedQuery {
 String name();
 String query();
 LockModeType lockMode() default LockModeType.NONE;
 QueryHint[] hints() default {};
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="query" type="xsd:string"/>
 <xsd:element name="lock-mode" type="orm:lock-mode-type" minOccurs="0"/>
 <xsd:element name="hint" type="orm:query-hint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
</xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="named-stored-procedure-query">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(NamedStoredProcedureQueries.class)
 @Target({TYPE}) @Retention(RUNTIME)
 public @interface NamedStoredProcedureQuery {
 String name();
 String procedureName();
 StoredProcedureParameter[] parameters() default {};
 Class[] resultClasses() default {};
 String[] resultSetMappings() default{};
 QueryHint[] hints() default {};
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="parameter"
 type="orm:stored-procedure-parameter"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="result-class" type="xsd:string"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="result-set-mapping" type="xsd:string"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="hint" type="orm:query-hint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="procedure-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="named-subgraph">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({}) @Retention(RUNTIME)
 public @interface NamedSubgraph {
 String name();
 Class<?> type() default void.class;
 NamedAttributeNode[] attributeNodes();
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="named-attribute-node"
 type="orm:named-attribute-node"
 minOccurs="0"
 maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="class" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

<xsd:complexType name="one-to-many">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface OneToMany {
 Class<?> targetEntity() default void.class;
 CascadeType[] cascade() default {};
 FetchType fetch() default FetchType.LAZY;
 String mappedBy() default "";
 boolean orphanRemoval() default false;
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
 <xsd:choice>
 <xsd:element name="join-table" type="orm:join-table"
 minOccurs="0"/>
 <xsd:sequence>
 <xsd:element name="join-column" type="orm:join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 </xsd:choice>
 <xsd:element name="cascade" type="orm:cascade-type"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="target-entity" type="xsd:string"/>
 <xsd:attribute name="fetch" type="orm:fetch-type"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 <xsd:attribute name="mapped-by" type="xsd:string"/>
 <xsd:attribute name="orphan-removal" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="one-to-one">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface OneToOne {
 Class<?> targetEntity() default void.class;
 CascadeType[] cascade() default {};
 FetchType fetch() default FetchType.EAGER;
 boolean optional() default true;
 String mappedBy() default "";
 boolean orphanRemoval() default false;
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:choice>
 <xsd:sequence>
 <xsd:element name="primary-key-join-column"
 type="orm:primary-key-join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="primary-key-foreign-key"
 type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
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
 <xsd:attribute name="mapped-by" type="xsd:string"/>
 <xsd:attribute name="orphan-removal" type="xsd:boolean"/>
 <xsd:attribute name="maps-id" type="xsd:string"/>
 <xsd:attribute name="id" type="xsd:boolean"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="order-by">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface OrderBy {
 String value() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:string"/>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="order-column">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface OrderColumn {
 String name() default "";
 boolean nullable() default true;
 boolean insertable() default true;
 boolean updatable() default true;
 String columnDefinition() default "";
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="nullable" type="xsd:boolean"/>
 <xsd:attribute name="insertable" type="xsd:boolean"/>
 <xsd:attribute name="updatable" type="xsd:boolean"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="parameter-mode">
 <xsd:annotation>
 <xsd:documentation>

 public enum ParameterMode { IN, INOUT, OUT, REF_CURSOR }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="IN"/>
 <xsd:enumeration value="INOUT"/>
 <xsd:enumeration value="OUT"/>
 <xsd:enumeration value="REF_CURSOR"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="post-load">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PostLoad {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="post-persist">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PostPersist {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="post-remove">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PostRemove {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="post-update">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PostUpdate {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="pre-persist">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PrePersist {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="pre-remove">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PreRemove {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="pre-update">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD}) @Retention(RUNTIME)
 public @interface PreUpdate {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="method-name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="primary-key-join-column">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(PrimaryKeyJoinColumns.class)
 @Target({TYPE, METHOD, FIELD}) @Retention(RUNTIME)
 public @interface PrimaryKeyJoinColumn {
 String name() default "";
 String referencedColumnName() default "";
 String columnDefinition() default "";
 String options() default "";
 ForeignKey foreignKey() default @ForeignKey(PROVIDER_DEFAULT);
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="referenced-column-name" type="xsd:string"/>
 <xsd:attribute name="column-definition" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="query-hint">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface QueryHint {
 String name();
 String value();
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="value" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="secondary-table">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(SecondaryTables.class)
 @Target({TYPE}) @Retention(RUNTIME)
 public @interface SecondaryTable {
 String name();
 String catalog() default "";
 String schema() default "";
 PrimaryKeyJoinColumn[] pkJoinColumns() default {};
 ForeignKey foreignKey() default @ForeignKey(ConstraintMode.PROVIDER_DEFAULT);
 UniqueConstraint[] uniqueConstraints() default {};
 Index[] indexes() default {};
 CheckConstraint[] check() default {};
 String comment() default "";
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:sequence>
 <xsd:element name="comment" type="xsd:string" minOccurs="0" />
 <xsd:element name="primary-key-join-column"
 type="orm:primary-key-join-column"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="primary-key-foreign-key"
 type="orm:foreign-key"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:element name="unique-constraint" type="orm:unique-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="index" type="orm:index"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="foreign-key" type="orm:foreign-key"
 minOccurs="0"/>
 <xsd:element name="check-constraint" type="orm:check-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="catalog" type="xsd:string"/>
 <xsd:attribute name="schema" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="sequence-generator">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(SequenceGenerators.class)
 @Target({TYPE, METHOD, FIELD, PACKAGE}) @Retention(RUNTIME)
 public @interface SequenceGenerator {
 String name() default "";
 String sequenceName() default "";
 String catalog() default "";
 String schema() default "";
 int initialValue() default 1;
 int allocationSize() default 50;
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="sequence-name" type="xsd:string"/>
 <xsd:attribute name="catalog" type="xsd:string"/>
 <xsd:attribute name="schema" type="xsd:string"/>
 <xsd:attribute name="initial-value" type="xsd:int"/>
 <xsd:attribute name="allocation-size" type="xsd:int"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="sql-result-set-mapping">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(SqlResultSetMappings.class)
 @Target({TYPE}) @Retention(RUNTIME)
 public @interface SqlResultSetMapping {
 String name();
 EntityResult[] entities() default {};
 ConstructorResult[] classes() default{};
 ColumnResult[] columns() default {};
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="entity-result" type="orm:entity-result"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="constructor-result" type="orm:constructor-result"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="column-result" type="orm:column-result"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="stored-procedure-parameter">
 <xsd:annotation>
 <xsd:documentation><![CDATA[

 @Target({}) @Retention(RUNTIME)
 public @interface StoredProcedureParameter {
 String name() default "";
 ParameterMode mode() default ParameterMode.IN;
 Class<?> type();
 }

 ]]></xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string"
 minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="class" type="xsd:string" use="required"/>
 <xsd:attribute name="mode" type="orm:parameter-mode"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="table">
 <xsd:annotation>
 <xsd:documentation>

 @Target({TYPE}) @Retention(RUNTIME)
 public @interface Table {
 String name() default "";
 String catalog() default "";
 String schema() default "";
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

 <xsd:complexType name="table-generator">
 <xsd:annotation>
 <xsd:documentation>

 @Repeatable(TableGenerators.class)
 @Target({TYPE, METHOD, FIELD, PACKAGE}) @Retention(RUNTIME)
 public @interface TableGenerator {
 String name() default "";
 String table() default "";
 String catalog() default "";
 String schema() default "";
 String pkColumnName() default "";
 String valueColumnName() default "";
 String pkColumnValue() default "";
 int initialValue() default 0;
 int allocationSize() default 50;
 UniqueConstraint[] uniqueConstraints() default {};
 Indexes[] indexes() default {};
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="description" type="xsd:string" minOccurs="0"/>
 <xsd:element name="unique-constraint" type="orm:unique-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="index" type="orm:index"
 minOccurs="0" maxOccurs="unbounded"/>
 <xsd:element name="check-constraint" type="orm:check-constraint"
 minOccurs="0" maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="table" type="xsd:string"/>
 <xsd:attribute name="catalog" type="xsd:string"/>
 <xsd:attribute name="schema" type="xsd:string"/>
 <xsd:attribute name="pk-column-name" type="xsd:string"/>
 <xsd:attribute name="value-column-name" type="xsd:string"/>
 <xsd:attribute name="pk-column-value" type="xsd:string"/>
 <xsd:attribute name="initial-value" type="xsd:int"/>
 <xsd:attribute name="allocation-size" type="xsd:int"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:simpleType name="temporal">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Temporal {
 TemporalType value();
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="orm:temporal-type"/>
 </xsd:simpleType>

 <!-- **************************************************** -->

 <xsd:simpleType name="temporal-type">
 <xsd:annotation>
 <xsd:documentation>

 @Deprecated(since = "3.2")
 public enum TemporalType {
 DATE, // java.sql.Date
 TIME, // java.sql.Time
 TIMESTAMP // java.sql.Timestamp
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:restriction base="xsd:token">
 <xsd:enumeration value="DATE"/>
 <xsd:enumeration value="TIME"/>
 <xsd:enumeration value="TIMESTAMP"/>
 </xsd:restriction>
 </xsd:simpleType>

<!-- **************************************************** -->

 <xsd:complexType name="transient">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Transient {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="unique-constraint">
 <xsd:annotation>
 <xsd:documentation>

 @Target({}) @Retention(RUNTIME)
 public @interface UniqueConstraint {
 String name() default "";
 String[] columnNames();
 String options() default "";
 }

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="column-name" type="xsd:string"
 maxOccurs="unbounded"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string"/>
 <xsd:attribute name="options" type="xsd:string"/>
 </xsd:complexType>

<!-- **************************************************** -->

 <xsd:complexType name="version">
 <xsd:annotation>
 <xsd:documentation>

 @Target({METHOD, FIELD}) @Retention(RUNTIME)
 public @interface Version {}

 </xsd:documentation>
 </xsd:annotation>
 <xsd:sequence>
 <xsd:element name="column" type="orm:column" minOccurs="0"/>
 <xsd:element name="temporal" type="orm:temporal" minOccurs="0"/>
 </xsd:sequence>
 <xsd:attribute name="name" type="xsd:string" use="required"/>
 <xsd:attribute name="access" type="orm:access-type"/>
 </xsd:complexType>

</xsd:schema>
