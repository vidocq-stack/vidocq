# Jakarta Persistence 3.2 TCK Runner

This module runs the official Jakarta Persistence 3.2 TCK against the Mansart implementation.

## Prerequisites

- **Java 25**: Use SDKMAN: `sdk install java 25.0.3-open`
- **Maven 3.9.16 or later**: Use SDKMAN: `sdk install maven 3.9.16`
- **Docker**: Required if you want to run the TCK against PostgreSQL (`--pg`)
- **Mansart Reactor**: Must be installed in your local Maven repository:

```bash
cd ../..
mvn -ntp install -DskipTests
```

## Running the TCK

### Default (H2 in-memory, EntityTests)
```bash
./run-official-tck-jakarta-persistence-3.2.sh
```

### With PostgreSQL (via Testcontainers)
```bash
./run-official-tck-jakarta-persistence-3.2.sh --pg
```

### With Signature Tests (binary API compliance)
```bash
./run-official-tck-jakarta-persistence-3.2.sh --sig
```

### Full Suite (Entity + Signature)
```bash
./run-official-tck-jakarta-persistence-3.2.sh --full
```

### Smoke Test Only (no TCK)
```bash
./run-official-tck-jakarta-persistence-3.2.sh --smoke
```

## Notes

- The TCK JAR (`jakarta.tck:persistence-tck-spec-tests:3.2.1`) is pulled from Maven Central. No local install required.
- The `tck-run` profile enables the TCK. All other profiles are cumulative.
- Signature tests require JDK 17+ and expose internal VM annotations. This harness activates the required `--add-exports` and `--add-opens` flags.
