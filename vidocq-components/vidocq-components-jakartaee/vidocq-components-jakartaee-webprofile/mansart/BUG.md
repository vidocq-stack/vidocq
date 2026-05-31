# Mansart — Bug tracker

Reproducible bugs in Mansart (Jakarta Data 1.0 + Persistence 3.2). Format per workspace CLAUDE.md:
short id, date, symptom, minimal repro, cause hypothesis, status. Update on each investigation.

---

## MANSART-001 — `boolean`/`Boolean` entity field breaks metamodel generation

- **Date**: 2026-05-31
- **Status**: FIXED 2026-05-31 (see "Fix" below; 145 tests green incl. new `BooleanAttributeTest`)
- **Severity**: medium (blocked any entity with a boolean column; easy workaround)

### Symptom
An `@Entity` with a `boolean` (or `Boolean`) persistent field makes the APT-generated metamodel
fail to compile:

```
_<Entity>.java: type argument java.lang.Boolean is not within bounds of type-variable V
_<Entity>.java: cannot infer type arguments for io.vidocq.mansart.data.dialect.attribute.NumericAttribute<>
  reason: inference variable V has incompatible bounds
    equality constraints: java.lang.Boolean
    upper bounds: java.lang.Number
<Entity>RepositoryImpl.java: is not abstract and does not override abstract method ...
```

The metamodel writer emits a `NumericAttribute<Boolean>`, but `NumericAttribute<V extends Number>`
excludes `Boolean`, so `javac` rejects the generated `_<Entity>.java` (and the repository impl that
depends on it).

### Minimal repro
```java
@Entity
class Flag {
    @Id String id;
    boolean enabled;   // <-- Boolean also fails
}

@Repository
interface FlagRepository extends BasicRepository<Flag, String> {}
```
`mvn compile` with the Mansart APT on the processor path → compilation failure above.

### Cause hypothesis
`mansart-data-processor/.../MansartMetamodelWriter` (and the parallel runtime path in
`mansart-data-core/.../RuntimeEntityModelBuilder`) classify a `boolean`/`Boolean` field as a numeric
attribute. There is no dedicated boolean attribute type next to
`TextAttribute`/`NumericAttribute`/`EnumAttribute`/`TemporalAttribute` (see
`mansart-data-dialect-spi/.../attribute/`), and boolean is not otherwise routed, so it falls through
to `NumericAttribute`, whose type variable is bounded to `Number`.

### Fix (implemented 2026-05-31)
Added a dedicated `BooleanAttribute<E>` (record, `javaType() → Boolean.class`) to the dialect SPI and
routed `boolean`/`Boolean` fields to it in both code paths:
- `mansart-data-dialect-spi`: new `attribute/BooleanAttribute.java`; added to the `sealed Attribute`
  `permits` list.
- `mansart-data-processor`: new `AttributeKind.BOOLEAN` + `isBoolean(fqn)` check (before NUMERIC) in
  `EntityScanner`; new `case BOOLEAN` in `MansartMetamodelWriter` (emits `BooleanAttribute<>`).
- `mansart-data-core`: `RuntimeEntityModelBuilder.describeField` returns `BooleanAttribute` for
  `Boolean.class` (before the numeric check).

No downstream change was needed: dialects key off `Attribute.javaType()` (PostgreSQL/H2 already map
`Boolean.class → Types.BOOLEAN`), and no exhaustive `switch` over the sealed subtypes exists.

Regression test: `mansart-data-tests` — new entity `BooleanFlag` (primitive `boolean` + boxed
`Boolean`) + `BooleanAttributeTest` asserting both APT (`_BooleanFlag`) and runtime paths type the
fields as `BooleanAttribute`.

### Historical workaround (no longer required)
Before the fix, the flag had to be modelled as a String-backed enum. Arago's `Speaker` initially used
an enum, then moved back to a plain `boolean` once this was fixed.

---

## MANSART-002 — `java.time.Instant` field fails to INSERT on PostgreSQL

- **Date**: 2026-05-31
- **Status**: FIXED 2026-05-31 (regression test `PostgresqlCrudIntegrationTest#instantFieldRoundTripsThroughTimestamptz`)
- **Severity**: high (any entity with an `Instant` column fails to persist on PostgreSQL)

### Symptom
Saving an entity with a `java.time.Instant` field mapped to `TIMESTAMPTZ` fails on PostgreSQL:

```
org.postgresql.util.PSQLException: Cannot convert an instance of java.time.Instant to type Types.TIMESTAMP_WITH_TIMEZONE
```

Not caught earlier because the unit tests run on H2 and the existing test entities use `LocalDate`,
not `Instant`. Surfaced by Arago (`Speaker.invitedAt` is an `Instant`).

### Cause
`PostgresqlDialect.bind` (and `H2Dialect.bind`) bound the value with
`ps.setObject(idx, value, Types.TIMESTAMP_WITH_TIMEZONE)`. The PG JDBC driver cannot convert a raw
`Instant` for `TIMESTAMP_WITH_TIMEZONE` — it expects an `OffsetDateTime` (or `Timestamp`).

### Fix (implemented 2026-05-31)
In both dialects' `bind`, add an `Instant` branch that binds
`instant.atOffset(ZoneOffset.UTC)` instead of the raw `Instant`. The read path (`extract`) already
converted `OffsetDateTime → Instant`, so only writes were affected. Regression: new `Event` entity
(an `Instant` column) + a PG round-trip test under the `pg-it` tag.
