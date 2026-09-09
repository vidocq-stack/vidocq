# TASKS-JKP — https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html

One card = one behaviour = one failing test. Cards are numbered by the
generator; do not renumber by hand.

---

## M0 — the TCK

No card below M0 can be called done without a counter to check it against.

TCK: `jakarta.tck:persistence-tck-spec-tests:3.2.1`

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M0-T001 | Create the TCK runner module at **`mansart-jakarta-persistence/mansart-jakarta-persistence-tck`** (standalone POM, out of reactor) | — | That exact directory holds a pom.xml and `./scripts/build.sh` builds it, exit 0. **The path is not yours to choose** — it comes from `docs/spec-src/JKP/module.conf`. |
| M0-T002 | Depend on `jakarta.tck:persistence-tck-spec-tests:3.2.1` | — | `./scripts/build.sh dependency:resolve` lists the jar. **Removing the dependency to make the build green is not a fix** — an agent did exactly that. |
| M0-T003 | Arquillian container + ArchiveAppender injecting our implementation | — | A deployment archive is produced |
| M0-T004 | Run script + persistence.xml template for the suite | — | The script starts the suite and writes a log |
| M0-T005 | First run | — | **The TCK produces a counter, ANY counter. PASS=0 is success: the instrument exists.** |

Copy the layout from a runner that already passes here: `mansart-jakarta-data/mansart-data-tck`, `mansart-transactions/mansart-transactions-tck`

## M1 — entities

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M1-T001 Entity class requirements | 2.1 | Entity class is annotated with @Entity or declared in XML, is top-level or static inner class, has public/protected no-arg constructor, is non-final, all methods and persistent fields are non-final |
| M1-T002 Persistent fields and properties visibility | 2.2 | Instance variables have private/protected/package visibility, property accessor methods are public/protected, collection-valued fields use Collection/Set/List/Map interface types |
| M1-T003 Persistent attribute type consistency | 2.2.1 | No conflicting mapping annotations on same attribute; persistence provider detects and reports contradictory combinations |
| M1-T004 Property access JavaBeans conventions | 2.2.2 | Getter and setter methods for every persistent property, collection-valued properties use interface types from Section 2.2, portable apps must not override superclass mapping metadata |
| M1-T005 Default access type consistency | 2.3.1 | All classes in entity hierarchy with default access type consistently place mapping annotations on either fields or properties |
| M1-T006 Explicit access type annotation placement | 2.3.2 | Mapping annotations for property access placed on getters, mapping annotations for field access placed on fields |
| M1-T007 Simple primary key declaration | 2.4 | Every entity has exactly one primary key declared at root entity hierarchy level, using @Id or @IdClass, portable types limited to long/int/UUID/String/Long/Integer |
| M1-T008 Composite primary key requirements | 2.4.1 | Primary key class defines equals/hashCode, has public/protected no-arg constructor or is a Java record, properties are public/protected, represented as embeddable or id class with matching names and types |
| M1-T009 Derived identity relationships | 2.4.2 | Dependent entity with @Id or @MapsId ManyToOne/OneToOne relationship cannot be made persistent until parent entity reference is assigned |
| M1-T010 Derived identity specification | 2.4.2.1 | Id class names/types match @Id attributes or parent primary key class; embedded id uses @MapsId; single primary key uses OneToOne with @MapsId; no @EmbeddedId or @IdClass on dependent entity |
| M1-T011 Entity version management | 2.5 | Version field/property identified by @Version, type limited to int/short/long/LocalDateTime/Instant/Timestamp, only persistence provider updates, single version per hierarchy |
| M1-T012 Basic types support | 2.6 | Primitives, wrapper classes, String, UUID, BigInteger/BigDecimal, java.time types, java.util/Sql Date/Time/Timestamp, byte[]/char[], enums, Serializable types |
| M1-T013 Embeddable class requirements | 2.7 | Annotated with @Embeddable or XML, not abstract, follows entity class requirements except not @Entity, supports nested embeddables, collections of basic/embeddable types, and entity relationships |
| M1-T014 Element collection constraints | 2.8 | Nested embeddables in element collections cannot contain nested element collections or relationships other than many-to-one/one-to-one, embeddable class owns the relationship mapped by foreign key |
| M1-T015 Map key requirements | 2.9.1 | Embeddable map key implements hashCode/equals consistently with database columns, MapKeyClass annotation required when generic types not used |
| M1-T016 Map value requirements | 2.9.2 | ElementCollection targetClass or OneToMany/ManyToMany targetEntity specifies value type when generic types not used |
| M1-T017 Mapping defaults for non-relationship fields | 2.10 | Error if no annotation present and not Embeddable or basic types from Section 2.6 |
| M1-T018 Entity relationship modeling | 2.11 | OneToOne/OneToMany/ManyToOne/ManyToMany annotations on persistent property or field, targetEntity specified when generic types not used |
| M1-T019 Bidirectional relationship mapping defaults | 2.12 | Inverse side uses mappedBy on OneToOne/OneToMany/ManyToMany, many side owns one-to-many/many-to-one, unidirectional one-to-many uses JoinColumn, join table mappings use JoinTable, annotations on owning side, consistency with relationship modeling annotation, empty collection returned for multi-valued relationships with no associated entities |
| M1-T020 Bidirectional OneToOne ownership | 2.12.1 | Entity A specified as owner of the relationship |
| M1-T021 Bidirectional ManyToOne/OneToMany ownership | 2.12.2 | Entity A is the owner of the relationship |
| M1-T022 Unidirectional single-valued relationships | 2.12.3 | Unidirectional relationship has only an owning side (Entity A) |
| M1-T023 Bidirectional ManyToMany ownership | 2.12.4 | Entity A is the owner of the relationship |
| M1-T024 Unidirectional multi-valued relationships | 2.12.5 | Unidirectional relationship has only an owning side (Entity A) |
| M1-T025 Mapped superclass constraints | 2.13.2 | Mapped superclass is not queryable, cannot be passed to EntityManager or Query operations, persistent relationships must be unidirectional |
| M1-T026 Non-entity classes in inheritance hierarchy | 2.13.3 | Non-entity classes cannot be passed to EntityManager or Query interfaces and cannot bear mapping information |
| M1-T027 Inheritance mapping strategies | 2.14 | Single table per class hierarchy and joined subclass strategies supported |
| M1-T028 Database object naming | 2.15 | Database object names treated as undelimited identifiers by default, delimited identifiers via <delimited-identifiers/> in XML, cannot be overridden, annotation delimited identifiers use escaped double quotes |

## M2 — metadata

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M2-T001 Entity | 10.1 | name defaults to unqualified class name, used to refer to entity in queries |
| M2-T002 Callback Annotations | 10.2 | EntityListeners specifies callback listener classes on entity/mapped superclass, ExcludeSuperclassListeners/ExcludeDefaultListeners control listener exclusion, callback lifecycle annotations on entity/mapped superclass/listener class methods |
| M2-T003 NamedEntityGraph | 10.3.1 | name unique within persistence unit, attributeNodes/subgraphs/subclassSubgraphs/list of subgraphs specified, default name to entity name when omitted |
| M2-T004 NamedAttributeNode | 10.3.2 | value specifies attribute name, subgraph/keySubgraph reference NamedSubgraph specifications |
| M2-T005 NamedSubgraph | 10.3.3 | name references from NamedAttributeNode, type for subclass subgraphs, attributeNodes lists included attributes |
| M2-T006 NamedQuery | 10.4.1 | name identifies query, query string in JPQL, resultClass/lockMode/hints specified, applied to entity/mapped superclass |
| M2-T007 NamedNativeQuery | 10.4.2 | name identifies query, native SQL string, resultClass/resultSetMapping/columns/entities specified, applied to entity/mapped superclass |
| M2-T008 NamedStoredProcedureQuery | 10.4.3 | name and procedureName specified, all parameters specified via StoredProcedureParameter with name/mode/type, resultClasses/resultSetMappings specified |
| M2-T009 SqlResultSetMapping | 10.4.4 | name given to result set mapping, entities/classes/columns/fields/discriminatorColumn mapping defined, FieldResult/ConstructorResult/ColumnResult sub-elements |
| M2-T010 PersistenceContext | 10.5.1 | name for JNDI access, unitName/type/synchronization specified, transaction-scoped or extended persistence context |
| M2-T011 PersistenceUnit | 10.5.2 | name for JNDI access, unitName specified, entity manager factory dependency |
| M2-T012 Attribute Converter | 10.6 | implements AttributeConverter, annotated with Converter/autoApply, converter never applies to id/version/relationship/Enumerated/Temporal attributes |
| M2-T013 Access | 11.1.1 | value specifies access type applied to class or attribute |
| M2-T014 AssociationOverride | 11.1.2 | dot notation for nested attributes, joinTable/joinColumns/foreignKey specified for relationship override |
| M2-T015 AttributeOverride | 11.1.4 | key./value. prefix for map attributes, dot notation for nested embedded attributes |
| M2-T016 Basic | 11.1.6 | supports JDBC column types, java.time.Instant/Year/math/UUID/char[] mappings, EAGER eager fetch requirement |
| M2-T017 Cacheable | 11.1.7 | Cacheable(false) means entity and state not cached by provider |
| M2-T018 CollectionTable | 11.1.8 | default values when missing, may not apply to non-ElementCollection fields, foreignKey strategy |
| M2-T019 Column | 11.1.9 | default values from Table 12, portable precision/scale for numeric/decimal columns |
| M2-T020 Convert | 11.1.10 | converter specified, disableConversion controls auto-applied converters, not for id/version/relationship/Enumerated/Temporal, attributeName for embedded/map attributes |
| M2-T021 Converts | 11.1.11 | multiple converters not applied to same basic attribute |
| M2-T022 DiscriminatorColumn | 11.1.12 | SINGLE_TABLE/JOINED strategy discriminator column, defaults to "DTYPE"/STRING, columnDefinition consistent with discriminator type |
| M2-T023 DiscriminatorValue | 11.1.13 | only on concrete entity class, consistent with discriminator column type, default entity name for STRING type |
| M2-T024 ElementCollection | 11.1.14 | must specify for collection table mapping, EAGER eager fetch requirement |
| M2-T025 Embeddable | 11.1.15 | class stored as intrinsic part of owning entity, shares entity identity |
| M2-T026 Embedded | 11.1.16 | embedded object properties mapped to entity table, embeddable class must be annotated as Embeddable |
| M2-T027 EmbeddedId | 11.1.17 | composite primary key as embeddable class, embeddable class must be annotated as Embeddable, only one EmbeddedId with no Id |
| M2-T028 Enumerated | 11.1.18 | enum persisted as string or integer, default ORDINAL |
| M2-T029 Final | 11.1.19 | field declared final, type is enum, not null, distinct value for each enum value |
| M2-T030 ConstraintMode | 11.1.20 | NO_CONSTRAINT means provider must not generate foreign key constraint |
| M2-T031 GeneratedValue | 11.1.21 | primary key generation strategy (TABLE/SEQUENCE/UUID/AUTO), TABLE uses database table, UUID stored in canonical form |
| M2-T032 Id | 11.1.23 | marks primary key, corresponding fields in composite key class must match names/types |
| M2-T033 Index | 11.1.24 | column names specified, ordering observed by provider |
| M2-T034 Joined | 11.1.25 | inheritance strategy, combination of inheritance strategies not required |
| M2-T035 JoinColumn | 11.1.26 | foreign key mapping, multiple columns require name and referencedColumnName |
| M2-T036 JoinTable | 11.1.27 | join columns that map relationship, name and referencedColumnName required when grouped |
| M2-T037 ManyToMany | 11.1.30 | cascading operations, fetch strategy, target entity class, bidirectional mappedBy, EAGER eager fetch |
| M2-T038 ManyToOne | 11.1.31 | cascading operations, fetch strategy, optional relationship, bidirectional mappedBy, EAGER eager fetch |
| M2-T039 OneToMany | 11.1.33 | cascading operations, fetch strategy, target entity class, bidirectional mappedBy, EAGER eager fetch |
| M2-T040 OneToOne | 11.1.42 | cascading operations, fetch strategy, optional relationship, bidirectional mappedBy, orphan removal, EAGER eager fetch |
| M2-T041 MapKey | 11.1.33 | map key type specified, MapKeyClass optional when generics used |
| M2-T042 MapKeyClass | 11.1.35 | map key class specified, not required when map uses generics |
| M2-T043 MapKeyEnumerated | 11.1.38 | enum key type, map key class optional when generics used |
| M2-T044 MapKeyTemporal | 11.1.54 | Date/Calendar key type, map key class optional when generics used |
| M2-T045 MapKeyJoinColumn | 11.1.37 | map key join columns specified, name and referencedColumnName required when grouped |
| M2-T046 MapKeyJoinColumns | 11.1.37 | grouped map key join columns, name and referencedColumnName required per annotation |
| M2-T047 OrderBy | 11.1.43 | ordering by basic/embeddable properties, dot notation for embeddable attributes |
| M2-T048 OrderColumn | 11.1.44 | integral order column, first element value 0, contiguous ordering maintained |
| M2-T049 SecondaryTable | 11.1.48 | secondary tables that map the entity class |
| M2-T050 SequenceGenerator | 11.1.50 | sequence generator mappings defined |
| M2-T051 TableGenerator | 11.1.53 | table generator mappings defined |
| M2-T052 Temporal | 11.1.54 | Date/Calendar fields mapped, required unless converter applied |
| M2-T053 Transient | 11.2.3 | strategy indicated, provider must use if supported by target database |
| M2-T054 UniqueConstraint | 11.2.5 | column names forming constraint, ordering observed when creating constraint |

## M3 — entity managers

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M3-T001 Container-managed and application-managed EMs + persistence contexts supported in web/EJB containers | 7.1 | Both container-managed and application-managed entity managers and their persistence contexts are supported in Jakarta EE web and EJB containers |
| M3-T002 Only application-managed EMs supported in Java SE / application client containers | 7.1 | Only application-managed entity managers are supported in Java SE and Jakarta EE application client containers |
| M3-T003 Application manages EM and persistence context lifecycle via EMF | 7.2 | Application uses entity manager factory to manage entity manager and persistence context lifecycle |
| M3-T004 Entity manager must not be shared among multiple concurrently executing threads | 7.2 | Entity manager and persistence context are not shared across concurrent threads |
| M3-T005 Entity managers must only be accessed in a single-threaded manner | 7.2 | Entity managers are accessed in a single-threaded manner |
| M3-T006 `jakarta.persistence` namespace reserved for specification use | 7.4 | Entries using `jakarta.persistence` and subnamespaces are not used for vendor-specific information |
| M3-T007 Container-managed EM must be a JTA entity manager | 7.5 | Container-managed entity managers are JTA entity managers |
| M3-T008 JTA and resource-local EMs supported in web/EJB containers | 7.5 | Both JTA and resource-local entity managers are supported in Jakarta EE web and EJB containers |
| M3-T009 Resource-local EM: persistence provider marks transaction for rollback on exception | 7.5.3 | Persistence provider marks transaction for rollback when resource-local entity manager triggers exception |
| M3-T010 EntityTransaction.commit failure triggers rollback | 7.5.3 | Persistence provider rolls back transaction when EntityTransaction.commit fails |
| M3-T011 EntityManager closed before runInTransaction/callInTransaction returns | 7.6 | EntityManager is closed before the method returns, regardless of return value or exception |
| M3-T012 JTA with existing transaction + exception = mark for rollback + rethrow | 7.6 | JTA transaction is marked for rollback and exception is rethrown when argument function throws |
| M3-T013 Resource-local or no JTA transaction + exception = roll back + rethrow | 7.6 | Transaction is rolled back and exception is rethrown when argument function throws |
| M3-T014 runInTransaction/callInTransaction attempts commit on successful return, rethrows on failure | 7.6 | Transaction commit is attempted on successful return; exception is rethrown on commit failure |
| M3-T015 Unsynchronized persistence context not flushed unless joined to transaction | 7.7.1 | Persistence context of type UNSYNCHRONIZED is not flushed to database unless joined to a transaction |
| M3-T016 Persistence contexts not propagated to remote tiers | 7.7.4 | Persistence contexts are not propagated to remote tiers |

## M4 — entity operations

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M4-T001 EntityManager transaction-scoped operations (persist/merge/remove/refresh) throw TransactionRequiredException when no transaction active | 3.2 | EntityManager.persist/merge/remove/refresh invoked without transaction context throws TransactionRequiredException; methods specifying lock mode other than NONE also require active transaction |
| M4-T002 Query, TypedQuery, StoredProcedureQuery, CriteriaBuilder, Metamodel, EntityTransaction lifetime tied to entity manager | 3.2 | Objects obtained from entity manager remain valid while manager is open; property/hint semantics documented and portable behavior defined |
| M4-T003 Persist operation: new entities become managed and scheduled for database insert; detached entities may throw EntityExistsException; cascade=PERSIST/ALL applied to referenced entities | 3.3.2 | New entity entered into database at or before transaction commit or flush; detached entity triggers EntityExistsException or PersistenceException; cascading to referenced entities with cascade=PERSIST or cascade=ALL |
| M4-T004 Remove operation: managed entity becomes removed and scheduled for database deletion; detached entity throws IllegalArgumentException; removed entity state preserved except generated state | 3.3.3 | Managed entity removed from database at or before transaction commit or flush; detached entity triggers IllegalArgumentException or transaction failure; entity state after remove except generated state preserved |
| M4-T005 Synchronization to database at transaction commit; flushModeType.COMMIT controls flush timing; flush cascades persist/remove operations based on cascade annotations | 3.3.4 | State synchronized at transaction commit; flushModeType.COMMIT delays flush to commit; flush cascades persist/remove based on cascade annotations; IllegalStateException thrown for new/removed entities without cascade=PERSIST/ALL |
| M4-T006 Refresh operation: managed entity state overwritten from database; cascaded with cascade=REFRESH/ALL; IllegalArgumentException for new/detached/removed entities | 3.3.5 | Managed entity state refreshed from database overwriting local changes; cascaded to relationships annotated with cascade=REFRESH or cascade=ALL; IllegalArgumentException thrown for non-managed entities |
| M4-T007 Detach operation: managed or removed entity becomes detached; cascaded with cascade=DETACH/ALL; portable applications must flush before detach | 3.3.6 | Entity becomes detached; changes written to database if flush called prior to detach; cascaded to relationships with cascade=DETACH or cascade=ALL; removed entities should not be passed to further EntityManager operations |
| M4-T008 Merge operation: detached entity state copied onto managed entity; new entity creates new managed copy; removed entity throws IllegalArgumentException; managed entity ignored; lazy fields not merged | 3.3.7.1 | Detached entity state copied onto existing or new managed entity; new entity creates new managed copy; removed entity triggers IllegalArgumentException; managed entity ignored with cascade=MERGE/ALL cascading; lazy fields not merged; version columns checked |
| M4-T009 Detached entity serialization/deserialization across JVMs with lazy loading support; cross-vendor interoperability requires no lazy loading | 3.3.7.2 | Serialization and deserialization of detached entities with lazy properties supported across JVMs; cross-vendor interoperability documented to not use lazy loading |
| M4-T010 Application must ensure entity instance managed in only single persistence context | 3.3.8 | Entity managed in single persistence context; application responsibility documented |
| M4-T011 Entity load state: EAGER attributes loaded; embeddable and collection attributes loaded recursively; PersistenceUtil.isLoaded returns true/false based on load conditions | 3.3.9 | EAGER attributes (including relationships and collections) loaded from database or assigned; embeddable and collection-valued attributes loaded recursively; PersistenceUtil.isLoaded returns correct loaded state |
| M4-T012 LockModeType.OPTIMISTIC_FORCE_INCREMENT: versioned object version column incremented on update/remove even without explicit lock call | 3.5.4.1 | Version column incremented when versioned object updated or removed; OPTIMISTIC_FORCE_INCREMENT requirements met without explicit EntityManager.lock call |
| M4-T013 Pessimistic locking (READ/WRITE): prevents dirty read (P1) and non-repeatable read (P2); lock obtained immediately and retained until transaction completes | 3.5.4.2 | Dirty read (P1) and non-repeatable read (P2) prevented; pessimistic lock obtained immediately and retained until commit/rollback; supported on both versioned and non-versioned entities |
| M4-T014 Pessimistic lock failure handling: transaction-level rollback throws PessimisticLockException and marks transaction for rollback; statement-level rollback throws LockTimeoutException without marking transaction | 3.5.4.2 | PessimisticLockException thrown with transaction marked for rollback on transaction-level lock failure; LockTimeoutException thrown without transaction marking on statement-level failure |
| M4-T015 Pessimistic READ lock converted to exclusive lock on entity update during flush; conversion failure handled per lock failure type | 3.5.4.2 | PESSIMISTIC_READ lock converted to exclusive lock when entity flushed after update; conversion failure throws PessimisticLockException (transaction rollback) or LockTimeoutException (statement rollback) |
| M4-T016 Pessimistic lock on versioned entity in persistence context triggers optimistic version checks; OptimisticLockException thrown on version check failure | 3.5.4.2 | Optimistic version checks performed when obtaining pessimistic lock on versioned entity already in persistence context; OptimisticLockException thrown if version checks fail |
| M4-T017 PESSIMISTIC_FORCE_INCREMENT on versioned object: prevents P1/P2 and forces version column increment; on non-versioned object throws PersistenceException | 3.5.4.2 | Version column incremented when PESSIMISTIC_FORCE_INCREMENT called on versioned object; PersistenceException thrown on non-versioned objects; P1/P2 prevented for both versioned and non-versioned |
| M4-T018 Updated versioned entity locked with PESSIMISTIC_READ/WRITE must meet PESSIMISTIC_FORCE_INCREMENT requirements | 3.5.4.2 | Version column incremented when versioned entity locked with PESSIMISTIC_READ or PESSIMISTIC_WRITE is subsequently updated |
| M4-T019 jakarta.persistence.lock.scope property observed; lock.timeout hint serves as default overridable per interface; vendor-specific hints must not use jakarta.persistence namespace | 3.5.5 | lock.scope property honored; lock.timeout hint overridable across EntityManager/Query/TypedQuery/NamedQuery/persistence.xml; vendor hints ignore jakarta.persistence namespace; override order: method argument > NamedQuery > createEntityManagerFactory > persistence.xml |
| M4-T020 Entity listener class requires public no-arg constructor; CDI lifecycle steps followed when CDI enabled; listeners not invoked when CDI disabled | 3.6.1 | Entity listener has public no-arg constructor; CDI lifecycle (BeanManager, AnnotatedType, InjectionTarget, CreationalContext, produce, inject, postConstruct, preDestroy, dispose, release) executed when CDI enabled; listeners not invoked when CDI disabled |
| M4-T021 Lifecycle callback methods: annotated or XML-mapped; not static or final; runtime exception marks transaction for rollback; inherited callbacks may be overridden; XML may override annotation order | 3.6.2 | Callback methods annotated or XML-mapped; not static or final; runtime exception marks transaction for rollback; inherited callback methods may be overridden; XML descriptor may override annotation-specified invocation order |
| M4-T022 Lifecycle callback methods: no generics; no native queries; runtime exception marks transaction for rollback | 3.6.5 | Callback methods do not use generics; do not execute native queries; runtime exception marks transaction for rollback when persistence context joined |
| M4-T023 Callback methods invoked in order specified; single class may not have more than one lifecycle callback method for same event | 3.6.4 / 3.5.4.2 | Callback methods invoked in specified order; single class restricted to one lifecycle callback method per event type |
| M4-T024 Attribute converter implements jakarta.persistence.AttributeConverter; annotated Converter or XML-declared; autoApply applies to all attributes of target type | 3 (Attribute Converters) | Converter implements AttributeConverter; annotated with Converter or declared in XML descriptor; autoApply=true applies to all attributes of target type including embedded |
| M4-T025 Converter not applied to Id/version/relationship/Enumerated/Temporal attributes; auto-apply skipped for excluded attributes; conversion applied in query path expressions and result processing | 3 (Attribute Converters) | Basic type conversion supported excluding Id/version/relationships/Enumerated/Temporal; excluded attributes not auto-applied; conversion applied in JPQL/criteria query path expressions and to query results |
| M4-T026 CDI lifecycle supported for attribute converters; exception from conversion method wrapped in PersistenceException and transaction marked for rollback | 3 (Attribute Converters) | CDI lifecycle steps (BeanManager, AnnotatedType, InjectionTarget, CreationalContext, produce, inject, postConstruct/preDestroy, dispose, release) executed for converters; conversion exception wrapped in PersistenceException with transaction marked for rollback |
| M4-T027 Second-level cache eligibility determined by annotations and shared-cache-mode; modes NONE/ENABLE_SELECTIVE/DISABLED/UNSPECIFIED behavior defined | 3.10 | Cache mode property (shared-cache-mode or jakarta.persistence.sharedCache.mode) determines eligibility; NONE disables all caching; ENABLE_SELECTIVE caches only @Cacheable entities; DISABLED caches all except @Cacheable(false); UNSPECIFIED provider-specific defaults |
| M4-T028 Second-level cache: cache modes ignored when caching disabled or entity not eligible; cache modes respected regardless of enablement mechanism | 3.10.2 | Cache modes ignored when shared-cache-mode is NONE or entity not eligible; applications not specifying shared-cache-mode documented as not portable |
| M4-T029 TypedQuery result type determined by CriteriaQuery or resultClass; Query returns Object[] for multi-expression select lists; parameter validation throws IllegalArgumentException | 3.11 | TypedQuery result type from CriteriaQuery resultClass or Query createQuery/NamedQuery resultClass; Query returns Object[] for multi-expression select lists; IllegalArgumentException thrown for invalid/unknown parameters |
| M4-T030 setMaxResults/setFirstResult undefined with fetch joins over collections; not supported for stored procedure queries; query methods not required within transaction unless lock mode specified | 3.11 | setMaxResults/setFirstResult behavior undefined with fetch joins; pagination not supported for stored procedures; transaction not required for query methods without lock mode; runtime exceptions from getParameters/getParameter/getParameterValue/getLockMode/Tuple/Parameter do not mark transaction for rollback |
| M4-T031 Query timeout causes PersistenceException (not QueryTimeoutException) on platforms where timeout triggers transaction rollback | 3.11 | PersistenceException thrown when query timeout causes transaction rollback on database platform |
| M4-T032 FlushModeType.AUTO: all entity state updates affecting query result visible during query processing; no flush when transaction not active or persistence context not joined | 3.11.2 | FlushModeType.AUTO ensures visible updates affecting query result; no flush when transaction inactive or persistence context not joined to transaction |
| M4-T033 Lock mode other than NONE requires transaction; locking supported for JPQL select and criteria queries only; setLockMode/getLockMode on non-eligible queries throws IllegalStateException | 3.11.3 | TransactionRequiredException thrown when lock mode other than NONE without active transaction; setLockMode/getLockMode on non-JPQL-select/criteria queries throws IllegalStateException |
| M4-T034 jakarta.persistence.query.timeout hint specifies query timeout in milliseconds | 3.11.4 | Query timeout hint in milliseconds supported |
| M4-T035 SQL result set mapping not used for non-persistent entity state; named parameters undefined for native SQL; only positional parameters portable | 3.11.11.2 | SQL result set mapping excludes non-persistent entity state; native SQL queries use positional parameters only; named parameters undefined for portable native SQL |
| M4-T036 Stored procedure result sets without resultClasses/resultSetMappings returned as Object[] lists; result set mapping order matches stored procedure return order; combining strategies undefined | 3.11.12.1 | Result sets without explicit mapping returned as Object[]; result set mapping order matches stored procedure invocation order; combining different mapping strategies undefined |
| M4-T037 StoredProcedureQuery metadata: parameters specified in stored procedure order; positional parameters assumed when names not specified; mixing named and positional parameters invalid | 3.11.12.1 | StoredProcedureParameter metadata provided for all parameters in stored procedure order; positional parameters assumed when names omitted; named and positional parameter mixing invalid |
| M4-T038 Dynamically-specified stored procedure: all parameters registered via StoredProcedureQuery.registerStoredProcedureParameter | 3.11.12.2 | All parameters of dynamic stored procedure registered via registerStoredProcedureParameter method |
| M4-T039 Stored procedure execution: IN/INOUT parameters set via setParameter; execute called implicitly by getResultList/getSingleResult/getSingleResultOrNull/getUpdateCount; execute returns true for result sets, false for update counts | 3.11.12.3 | IN/INOUT parameters set via setParameter; execute called implicitly by result retrieval methods; execute returns true for result sets (obtain via getResultList/getSingleResult/getSingleResultOrNull) or false for update counts (obtain via getUpdateCount); results processed before INOUT/OUT parameter extraction |
| M4-T040 Stored procedure execution: getUpdateCount returns zero or greater or -1; INOUT/OUT parameters retrieved via getOutputParameterValue after results exhausted; REF_CURSOR result sets processed before extraction | 3.11.12.3 | getUpdateCount returns update count (zero+) or -1; getOutputParameterValue retrieves INOUT/OUT parameter values after results exhausted; REF_CURSOR result sets processed before parameter extraction; result set mappings applied to REF_CURSOR results in registration order |
| M4-T041 PersistenceException thrown for unexpected errors; all specification exceptions are subclasses; all except NoResultException/NonUniqueResultException/LockTimeoutException/QueryTimeoutException mark transaction for rollback | 3.12 | PersistenceException thrown for unexpected errors; all specification exceptions extend PersistenceException; exceptions except NoResultException/NonUniqueResultException/LockTimeoutException/QueryTimeoutException mark active joined transaction for rollback |
| M4-T042 TransactionRequiredException thrown when transaction required but not active; OptimisticLockException thrown on optimistic locking conflict (API call, flush, or commit); transaction marked for rollback | 3.12 | TransactionRequiredException thrown when no active transaction; OptimisticLockException thrown at API call, flush, or commit time; transaction marked for rollback |
| M4-T043 PessimisticLockException thrown on pessimistic locking conflict (transaction marked for rollback); LockTimeoutException thrown on statement-level lock failure (transaction not marked); RollbackException thrown on EntityTransaction.commit failure | 3.12 | PessimisticLockException thrown with transaction marked for rollback; LockTimeoutException thrown without transaction marking; RollbackException thrown when EntityTransaction.commit fails |
| M4-T044 EntityExistsException thrown when persist invoked on existing entity or at flush/commit; EntityNotFoundException thrown when getReference accessed but entity does not exist, refresh on non-existent entity, or lock on non-existent entity with pessimistic locking | 3.12 | EntityExistsException thrown at persist invocation, flush, or commit; EntityNotFoundException thrown when getReference accessed for non-existent entity, refresh on non-existent entity, or pessimistic lock on non-existent entity; transaction marked for rollback |
| M4-T045 NoResultException thrown when Query.getSingleResult returns no result (transaction not marked for rollback); NonUniqueResultException thrown when Query.getSingleResult/getSingleResultOrNull returns multiple results (transaction not marked); QueryTimeoutException thrown on statement-level timeout (transaction not marked) | 3.12 | NoResultException thrown when getSingleResult returns no result without marking transaction; NonUniqueResultException thrown when getSingleResult/getSingleResultOrNull returns multiple results without marking transaction; QueryTimeoutException thrown on statement-level timeout without marking transaction |

## M5 — metamodel

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M5-T001 Static Metamodel Class Structure | 5.1.1 | annotated with StaticMetamodel, extends S_ for superclass metamodel, contains class_ field of type EntityType/EmbeddableType/MappedSuperclassType |
| M5-T002 Persistent Attribute String Fields | 5.1.1 | public static final String Y field for every persistent attribute, name transformed per Java identifier rules (lowercase-to-uppercase with underscore insertion) |
| M5-T003 Singular Attribute Fields | 5.1.1 | public static volatile SingularAttribute<X, Y> for every persistent non-collection-valued attribute |
| M5-T004 Collection Attribute Fields | 5.1.1 | public static volatile CollectionAttribute/SetAttribute/ListAttribute/MapAttribute for every persistent collection-valued attribute based on collection type |
| M5-T005 Named Query/Graph/Mapping String Fields | 5.1.1 | public static final String T_N field for every named query/graph/mapping, prefix QUERY/GRAPH/MAPPING + uppercase name with identifier conversion |
| M5-T006 Named Query Result Class References | 5.1.1 | public static volatile TypedQueryReference<R> for every named query with query result class R |
| M5-T007 Named Entity Graph References | 5.1.1 | public static volatile EntityGraph<X> for every named entity graph |
| M5-T008 Import Statements | 5.1.1 | import statements for needed jakarta.persistence, jakarta.persistence.metamodel types and all referenced classes X, Y, Z, R, K |
| M5-T009 Canonical Metamodel Support | 5.1.2 | persistence providers must support use of canonical metamodel classes |

## M6 — query language

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M6-T001 Query Statement Types and Construction | 4.2 | All statement types (select, update, delete) support parameters; queries may be constructed dynamically or statically defined in metadata annotation or XML descriptor |
| M6-T002 Set Operations (UNION, INTERSECT, EXCEPT) | 4.2.1.1 | Select statements support UNION, UNION ALL, INTERSECT, INTERSECT ALL, EXCEPT, EXCEPT ALL; provider must support constituent queries with same number of select clause items; corresponding items must have same type or share a common entity supertype |
| M6-T003 Reserved Identifiers | 4.4.1 | All 85+ reserved identifiers listed in spec are case-insensitive and must not be used as identification variables or result variables |
| M6-T004 Identification Variables and FROM Clause | 4.4, 4.4.2, 4.4.3 | Every FROM element declares an identification variable except implicit `this`; variables are case-insensitive, must be declared in FROM clause, and may share entity names |
| M6-T005 Path Expressions | 4.4.4 | Path expressions navigate entities via identification variables using `.` operator; implicit `this` for single-entity FROM; navigability uses inner join semantics; TREAT, KEY, VALUE, ENTRY operators supported; ENTRY is terminal |
| M6-T006 Like Expressions | 4.6.6 | String pattern matching with `_` (single char), `%` (any sequence), and optional escape character; returns unknown when expression, pattern, or escape is NULL |
| M6-T007 Null Comparison Expressions | 4.6.7 | Tests whether single-valued path expression or input parameter is NULL; embeddable class instances not supported |
| M6-T008 Empty Collection Comparison | 4.6.8 | Tests whether collection-valued path expression is empty; returns unknown when path expression value is unknown |
| M6-T009 Collection Member Expressions | 4.6.9 | MEMBER OF / NOT MEMBER OF tests; embeddable types not supported; returns FALSE/TRUE for empty collections; unknown when path or value expression is NULL |
| M6-T010 Exists Expressions | 4.6.10 | EXISTS predicate true only if subquery result has one or more values, false otherwise |
| M6-T011 ALL and ANY Expressions | 4.6.11 | ALL true if comparison holds for all subquery values or subquery is empty; ANY true if comparison holds for some value; SOME is synonymous with ANY; operators: =, <, <=, >, >=, <> |
| M6-T012 Subqueries | 4.6.12 | Subqueries allowed in WHERE and HAVING clauses; some contexts require scalar subqueries (single result) |
| M6-T013 Null Values and Three-Valued Logic | 4.6.13 | NULL operations yield unknown; two NULLs not equal; IS NULL / IS NOT NULL operators; AND, OR, NOT use three-valued logic; empty string '' is not equal to NULL |
| M6-T014 Equality and Comparison Semantics | 4.6.14 | Only like types comparable (numeric promotion exception); same abstract schema type entities equal iff same primary key; only equality/inequality over enums supported; embeddable/map comparisons not supported |
| M6-T015 Literals | 4.7.1 | String (single-quoted, unicode, no Java escapes), numeric (int, long, float, double, BigInteger, BigDecimal with optional suffixes), enum (fully qualified), date/time/timestamp (JDBC escape), boolean (TRUE/FALSE), entity type (case-insensitive reserved) |
| M6-T016 Input Parameters | 4.7.4 | Values safely interpolated into parameterized queries; positional or named (not mixed); supported in WHERE, HAVING, and SET clauses; null input params yield unknown; IN keyword context makes collection-valued |
| M6-T017 Positional Parameters | 4.7.4.1 | Designated by `?` prefix with integer starting from 1; may occur multiple times; ordering of use need not match numbering |
| M6-T018 Named Parameters | 4.7.4.2 | Prefixed by `:` colon; identifier follows standard identifier rules; case-sensitive; may occur multiple times |
| M6-T019 String Functions | 4.7.7.1 | CONCAT, SUBSTRING, TRIM (defaults: space, BOTH), LOWER, UPPER, LEFT, RIGHT, REPLACE, LOCATE, LENGTH; null/unknown arguments yield unknown |
| M6-T020 Arithmetic Functions | 4.7.7.2 | ABS, CEILING, FLOOR (same type as arg); SIGN (returns int); SQRT, EXP, LN (return double); MOD (two ints, returns int); ROUND (numeric + int, same type as first); POWER (two numerics, returns double); SIZE (collection count, 0 if empty); INDEX (ordered list position) |
| M6-T021 Datetime Functions | 4.7.7.3 | LOCAL DATE/TIME/DATETIME (java.time types); CURRENT_DATE/TIME/TIMESTAMP (java.sql types); EXTRACT with field identifiers (YEAR, QUARTER, MONTH, WEEK, DAY, HOUR, MINUTE return int; SECOND returns float; DATE/TIME return part) |
| M6-T022 Typecasts | 4.7.8 | CAST converts between types; providers must accept: scalar to STRING, string to INTEGER/LONG/FLOAT/DOUBLE; result types: java.lang.String, Integer, Long, Float, Double |
| M6-T023 Database Function Invocation | 4.7.9 | FUNCTION operator invokes database functions (built-in or user-defined); not portable across databases |
| M6-T024 Case Expressions | 4.7.10 | General case expressions, simple case expressions, COALESCE expressions, and NULLIF expressions supported |
| M6-T025 ID and VERSION Functions | 4.7.11 | Evaluate to primary key or version of identification variable or path expression resolving to many-to-one/one-to-one; composite primary keys not required to be supported |
| M6-T026 Entity Type Expressions | 4.7.12 | TYPE operator returns exact type of identification variable, path expression (one-to-one/many-to-one), or input parameter; entity_type_literal specifies by entity name |
| M6-T027 Numeric Expressions and Type Promotion | 4.7.13 | Expression type matches persistent state field type; CASE/COALESCE/NULLIF/arithmetic numeric type determined by operand types (Double > Float > BigDecimal > BigInteger > Long > Integer); rules determine Java object type for SELECT clause |
| M6-T028 GROUP BY and HAVING | 4.8 | WHERE applied before GROUP BY; SELECT items (non-aggregate) must appear in GROUP BY; nulls treated same for grouping; grouping by entity permitted (no serialized/LOB eagerly fetched fields); HAVING filters groups; HAVING without GROUP BY treats result as single group (not required to be supported) |
| M6-T029 SELECT Clause | 4.9 | Returns identification variable, single-valued path expression, scalar expression, aggregate expression, or constructor expression; must be single-valued; DISTINCT eliminates duplicates (undefined for embeddables/map entries); OBJECT operator optional for identification variables; result_variable names valid case-insensitive identifiers; missing SELECT implies `select this` |
| M6-T030 SELECT Clause Result Types | 4.9.1 | Results: entity abstract schema type, state field type, scalar expression result, aggregate function result, or construction operation result; multiple select expressions map in order to result types |
| M6-T031 Constructor Expressions | 4.9.2 | SELECT NEW returns Java class instances; class need not be entity or mapped; constructor name must be fully qualified; entity class constructors return new/detached entities depending on primary key retrieval |
| M6-T032 Null Values in Query Result | 4.9.3 | Null association/state field values returned; Java numeric primitive state field types cannot produce NULL results |
| M6-T033 Embeddables in Query Result | 4.9.4 | Embeddable instances returned by query are not in managed state |
| M6-T034 Aggregate Functions | 4.9.5, 4.1-4.14 | AVG, COUNT, MAX, MIN, SUM, and database-defined functions; COUNT argument may be state field, association field, or identification variable; SUM/AVG args must be numeric; MAX/MIN args must be orderable; DISTINCT supported except for embeddable/map entry types; FUNCTION operator for aggregate database functions |
| M6-T035 ORDER BY Clause | 4.10 | Sorts query results; expressions: state_field_path_expression, identification_variable, or result_variable; earlier items take precedence; ASC (default) / DESC; NULLS FIRST / NULLS LAST (database default if unspecified) |
| M6-T036 Bulk Update and Delete | 4.11 | Single entity class (with subclasses); one abstract schema type per FROM/UPDATE; delete does not cascade; version column must be manually managed; persistence context not synchronized with bulk operation results |

## M7 — criteria api

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M7-T001 Joins (6.3.3) | 6.3.3 | join methods applicable to Root and Join; inner join by default, outer via JoinType; returns Join/CollectionJoin/SetJoin/ListJoin/MapJoin |
| M7-T002 Fetch Joins (6.3.4) | 6.3.4 | fetch on returned entity/embeddable only; no fetch in subqueries; multi-level fetch joins not required |
| M7-T003 Path Navigation (6.3.5) | 6.3.5 | path navigation uses inner join semantics |
| M7-T004 Restricting Query Result (6.3.6) | 6.3.6 | where with Predicate/Expression<Boolean>; simple predicates via CriteriaBuilder; compound via and/or/not; equality/comparison semantics per §4.6 |
| M7-T005 Expressions (6.3.8) | 6.3.8 | Expression subtypes in select/where/having; arithmetic/string/datetime/case functions; type/index/aggregation/size methods |
| M7-T006 Result Types of Expressions (6.3.8.1) | 6.3.8.1 | getJavaType per TupleElement; most specific common superclass for non-numeric; numeric promotion rules (Double→Float→BigDecimal→BigInteger→Long→Integer) |
| M7-T007 Literals (6.3.8.2) | 6.3.8.2 | literal and nullLiteral methods on CriteriaBuilder |
| M7-T008 Coalesce and Nullif (6.3.9) | 6.3.9 | coalesce returns first non-null or null; nullif returns first arg if unequal, else null |
| M7-T009 Case Expressions (6.3.10) | 6.3.10 | simple and general case via CriteriaBuilder.selectCase |
| M7-T010 Specifying Query Select List (6.3.11) | 6.3.11 | select with Selection; construct/tuple/array for CompoundSelection; multiselect; distinct elimination including embeddables/map entries |
| M7-T011 Specifying Query Result Grouping (6.3.12) | | groupBy and having; non-aggregate selections must match grouping paths |
| M7-T012 Specifying Query Result Ordering (6.3.13) | 6.3.13 | orderBy with Order (asc/desc); orderable state fields or construct/tuple/array Selection |
| M7-T013 CriteriaUpdate, CriteriaDelete, and Root (6.3.15) | 6.3.15 | CriteriaBuilder update/delete queries; from creates Root; multiple roots; cartesian product semantics |
| M7-T014 Bulk Update and Delete (6.1–6.4) | 6.1–6.4 | bulk ops bypass optimistic locking; portable apps must manage version column manually; persistence context not synchronized |
| M7-T015 Query Modification (6.6) | 6.6 | query elements are tied to their CriteriaQuery/CriteriaUpdate/CriteriaDelete instance |
| M7-T016 Query Execution (6.7) | 6.7 | createQuery on EntityManager; modified definition creates new executable object; serializable; cross-JVM deserialization supported |

## M8 — packaging

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M8-T001 Persistence unit root locations must be EJB-JAR, WAR WEB-INF/classes, WEB-INF/lib jar, EAR lib jar, or application client JAR | 8.2 | All root types validated against deployment archive structure |
| M8-T002 Persistence unit must declare a name | 8.2.1.2 | persistence-unit name element present and non-empty |
| M8-T003 Persistence unit name must be unique within EJB-JAR, WAR, application client JAR, or EAR | 8.2.1.2 | Duplicate names across all persistence-unit declarations within same archive scope rejected |
| M8-T004 transaction-type must be JTA or RESOURCE_LOCAL | 8.2.1.2 | Invalid transaction-type values rejected at parse time |
| M8-T005 Data source must be specified via jta-data-source or non-jta-data-source, or provided at deployment | 8.2.1.7 | Missing data source validated at deployment or container default applied |
| M8-T006 Entity classes, embeddables, mapped superclasses, and converters must be implicitly or explicitly denoted as managed | 8.2.1.8 | All managed classes discoverable via annotations or persistence.xml |
| M8-T007 Referenced classes and jars must be on the classpath | 8.2.1.8 | Classpath resolution for all referenced classes succeeds |
| M8-T008 Classes mapping the same entity from different persistence units must resolve to the same class object | 8.2.1.8 | Class identity verified across persistence unit boundaries |
| M8-T009 All EAR-level classes must be accessible to all Jakarta EE components in the application | 8.2.1.8 | Application classloader loads EAR-level classes for all component classloaders |
| M8-T010 Mapping file class-level mapping information must be disjoint across all mapping files in persistence unit | 8.2.1.8 | No class mapped in multiple mapping files |
| M8-T011 Migration scripts must specify paths relative to persistence unit root | 8.2.1.11 | Script file paths resolved relative to persistence unit root |
| M8-T012 Script file URLs must be absolute paths in Jakarta EE environments | 8.2.1.11 | Absolute file URLs constructed for script locations |
| M8-T013 Source and target file locations must be accessible to the application server | 8.2.1.11 | File accessibility check passes for all script I/O paths |
| M8-T014 Unknown provider properties (other than jakarta.persistence namespace) must be ignored | 8.2.1.11 | Unknown properties logged and skipped without error |
| M8-T015 jakarta.persistence namespace must be reserved for specification-defined properties only | 8.2.1.11 | Vendor-specific properties outside jakarta.persistence namespace accepted without error |
| M8-T016 persistence-unit name attribute is required; other attributes and elements are optional | 8.2.1.2 | Missing name attribute causes deployment error |
| M8-T017 Java SE: explicit list of all managed persistence class names must be specified for portability | 8.2.1.8 | javaSE persistence.xml contains class or package-name elements enumerating all managed classes |
| M8-T018 Java SE: persistence provider may require fully enumerated entity class set in persistence.xml | 8.2.1.8 | Provider-specific enumeration requirement validated |

## M9 — container contracts

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M9-T001 Persistence unit deployment composition | 9.1 | Single persistence.xml per persistence unit, container scans Section 8.2 locations, validates against persistence_3_2/3_0/2_2 xsd, passes PersistenceUnitInfo to createContainerEntityManagerFactory, provides ValidatorFactory via jakarta.persistence.validation.factory when Bean Validation present and mode not NONE, provides BeanManager via jakarta.persistence.bean.manager when CDI enabled, exactly one EntityManagerFactory per persistence unit |
| M9-T002 Java SE provider discovery via SPI | 9.2 | Provider supplies META-INF/services/jakarta.persistence.spi.PersistenceProvider configuration file, Persistence bootstrap class locates providers via PersistenceProviderResolver, calls createEntityManagerFactory until non-null returned, non-qualifying providers return null |
| M9-T003 Java SE schema generation bootstrap | 9.2.1 | Persistence bootstrap class locates providers via PersistenceProviderResolver, calls generateSchema until true returned, non-qualifying providers return false |
| M9-T004 PersistenceProviderResolver threadsafety and lifecycle | 9.3 | PersistenceProviderResolverHolder is threadsafe, default resolver returns installed/service provider/extension providers, getPersistenceProviders() determines available providers, results must not be cached for provider list or resolver instance |
| M9-T005 Methods requiring live provider resolution | 9.3 | Persistence.createEntityManagerFactory(String), Persistence.createEntityManagerFactory(String, Map), PersistenceUtil.isLoaded(Object), PersistenceUtil.isLoaded(Object, String) must each call PersistenceProviderResolverHolder.getPersistenceProviderResolver() and PersistenceProviderResolver.getPersistenceProviders() without caching |
| M9-T006 Jakarta EE schema generation file requirements | 9.4 | File URLs must be absolute paths, all source/target locations accessible to application server, no action when jakarta.persistence.schema-generation.database.action unspecified, no scripts when jakarta.persistence.schema-generation.scripts.action unspecified, undefined results when metadata-then-script or script-then-metadata produces non-disjoint actions, jakarta.persistence.database-product-name required when no database connection supplied, jakarta.persistence.sql-load-script-source required for DDL scripts in Java SE or when container delegates |
| M9-T007 Data loading script source requirement | 9.4.1 | jakarta.persistence.sql-load-script-source property required when load script used in Java SE or container delegates execution |
| M9-T008 Persistence provider SPI and factory creation | 9.5 | Implements PersistenceProvider SPI, processes PersistenceUnitInfo metadata in createContainerEntityManagerFactory, initializes metamodel classes on factory creation, validates XML mapping against schema, instantiates ValidatorFactory via default bootstrapping when no ValidatorFactory provided but Bean Validation present on classpath |
| M9-T009 PersistenceProvider contract | 9.5.1 | Implements PersistenceProvider interface per Section E.3, must have public no-arg constructor |
| M9-T010 PersistenceUnitInfo shared cache mode default | 9.6 | getSharedCacheMode returns UNSPECIFIED when shared-cache-mode element not specified |
| M9-T011 Persistence createEntityManagerFactory property override | 9.7 | Map parameters override persistence.xml values, jakarta.persistence namespace reserved for specification use, unrecognized properties (other than spec-defined) must be ignored |
| M9-T012 PersistenceConfiguration support requirement | 9.8 | Provider must support configuration via any PersistenceConfiguration instance or subclass |
| M9-T013 PersistenceUtil.isLoaded provider iteration | 9.9.1 | Calls ProviderUtil.isLoaded(Object) on each provider until LoadState.LOADED, LoadState.NOT_LOADED, or all return LoadState.UNKNOWN; isLoaded(Object,String) calls ProviderUtil.isLoadedWithoutReference per provider until resolved or all return UNKNOWN (then calls ProviderUtil.isLoadedWithReference per provider) |

## M10 — xml mapping

| Card | Title | Spec sections | Done-when |
|---|---|---|---|
| M10-T001 Override access type specified or defaulted via annotations (entity/mapped-superclass) | 12.2.3, 12.2.4, 12.2.5 | Application breaks when access type is overridden; validator warns / schema disallows conflicting `access` element with `@Access` annotation |
| M10-T002 Override entity name | 12.2.3 | Application breaks when name is overridden; validator warns / schema enforces name consistency between annotation and XML |
| M10-T003 Inheritance strategies supported (single-table, joined, table-per-class) | 12.2.3 | Unsupported strategy combinations are rejected; supported strategies map correctly to table DDL |
| M10-T004 `Cacheable` entity respects `shared-cache-mode` `ENABLE_SELECTIVE` / `DISABLE_SELECTIVE` | 12.2.3 | Entities annotated `@Cacheable(true)` are cached when mode is `ENABLE_SELECTIVE`; entities annotated `@Cacheable(false)` are excluded when mode is `DISABLE_SELECTIVE` |
| M10-T005 `persistence.xml` schema namespace validation (`orm` namespace, version `3.2`) | 12.2.5 | Descriptor validates against `orm` XSD; invalid namespace or wrong version is rejected |
| M10-T006 XML mapping file root element supports all mapping sub-elements (description, persistence-unit-metadata, package, schema, catalog, access, sequence-generator, table-generator, named-query, named-native-query, named-stored-procedure-query, sql-result-set-mapping, mapped-superclass, entity, embeddable, converter) | 12.2.5 | Schema permits all listed sub-elements; unlisted elements cause validation failure |
| M10-T007 `access` type override in XML mapping file | 12.2.5 | XML `access` element overrides annotation-defaulted access type; validator warns / application breaks documented in spec |
| M10-T008 `schema`, `catalog`, `package` defaults from XML mapping file | 12.2.5 | Schema/catalog/package specified at root apply to all unmapped entities/embeddables; package fallback to `persistence.xml` default package |

