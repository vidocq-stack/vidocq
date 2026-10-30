# BUG — Vidocq Runtime

Tracking reproducible bugs in the Vidocq runtime (orchestrator + extension wrappers).
Vidocq workspace convention: short id, date, symptom, minimal repro, cause hypothesis, status.

---

## BUG-20261030-08 — Continuous testing does not print the failing tests and their causes

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/dev-mode-08-12`: `vidocq:dev` prints each failure under the headline, with the line of the test where it failed (first frame of the test class in the Surefire stack trace), then the log; a green run stays one line (`TestSummaryPrinterTest.vidocqDevNamesTheFailuresUnderTheHeadline`, `SurefireReportsTest.theLocationIsTheFirstFrameOfTheTestClass`)
- **Reported by**: the vidocq-tutorial session (batch 3, item 2)
- **Minimal reproduction**: `vidocq dev` with continuous testing on, a failing `@VidocqTest`: the console says tests failed, without the test names or assertion messages.
- **Cause hypothesis**: the surefire reports are read for counts only.

## BUG-20261030-09 — `vidocq extension add mansart-data` does not bring `jakarta.persistence-api` nor the `requires` it needs

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/extension-add-requires-09`: `vidocq-runtime-mansart-data-extension` depends on `jakarta.persistence-api` and `requires transitive jakarta.persistence`; `vidocq-runtime-humboldt-telemetry-extension` `requires transitive io.opentelemetry.api` and `io.opentelemetry.instrumentation_annotations` (`ApplicationModuleReadsTest` in both modules). A created application with cassini-rest, mansart-data, mansart-transactions, cervantes-jwt and humboldt-telemetry compiles an entity, a repository, `@Transactional`, `@RolesAllowed`, `JsonWebToken`, `Span` and `@WithSpan` with no hand-written `requires`. `jakarta.data`, `jakarta.transaction`, `jakarta.annotation` and `org.eclipse.microprofile.jwt` were already readable through the extensions' transitive requires; the tutorial's `requires` for them are redundant
- **Reported by**: the vidocq-tutorial session (batch 3, item 3)
- **Minimal reproduction**: `vidocq extension add mansart-data` on a created application, then an `@Entity`: compilation fails until the dependency and `requires jakarta.persistence` are added by hand.
- **Cause hypothesis**: the extension's catalog entry names its own artifact only; `module-info.requires` does not list the Persistence module.

## BUG-20261030-10 — A missing PostgreSQL dialect is found at the first query, not at boot; the dialects and `devservice-postgres` are not managed by the BOM

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/dialect-check-10`: `vidocq-runtime-mansart-data-extension` brings `mansart-data-dialect-h2` and `-postgresql` (both leave their driver `provided`), so no dialect needs a version; the boot connectivity check asks the dialects for the database and fails with what to add (`DialectCheckTest`). `vidocq-runtime-devservice-postgres` was already in the BOM, which manages the runtime's own artifacts only (`ApplicationBomAndParentTest`). The extensions page used `${vidocq.version}`, defined nowhere: now `{project-version}`
- **Reported by**: the vidocq-tutorial session (batch 3, item 4)
- **Minimal reproduction**: an application with the PostgreSQL dev service and no `mansart-dialect-postgresql`; the docs show `${vidocq.version}`, which no pom defines.
- **Cause hypothesis**: no start-up check matches the JDBC URL with an installed dialect; the BOM does not list the dialect artifacts.

## BUG-20261030-11 — `vidocq extension add flyway-migration` does not write `opens db.migration;`

- **Date**: 2026-10-30
- **Status**: FIXED on `feat/flyway-opens-migration-package`: the catalog key `module-info.opens-resource-package = db.migration` (Flyway and Liquibase); `vidocq extension add` writes the `opens` once the package holds a file, and otherwise says where the migrations go (`ExtensionEditorTest.addingFlywayOpensTheMigrationPackageWhenItHoldsAMigration`, `…WithoutMigrationSaysWhereTheyGo`). Opening an absent package fails the build, which is why `check-module-info` only asks for it once the package exists
- **Reported by**: the vidocq-tutorial session (batch 3, item 5)
- **Minimal reproduction**: add the extension, add `src/main/resources/db/migration/V1__init.sql`: `vidocq:check-module-info` fails, or Flyway finds no migration on the module path.
- **Cause hypothesis**: `ModuleInfoEditor` writes `requires` only; the `opens` the extension declares are checked, not written.

## BUG-20261030-12 — `vidocq dev` exits on the first compile failure

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/dev-mode-08-12`: `vidocq dev` passes `-Dmaven.compiler.failOnError=false` so Maven reaches the goal, and a first build that leaves no class in `target/classes` waits for the next change and builds again instead of failing (`CompilerOutputTest.aFirstBuildThatDoesNotCompileWaitsForAChangeAndBuildsAgain`, `RunGoalsTest`)
- **Reported by**: the vidocq-tutorial session (batch 3, item 6)
- **Minimal reproduction**: start `vidocq dev` on a project that does not compile: the goal fails instead of waiting for a fix.
- **Cause hypothesis**: the first build is run as a precondition, outside the reload loop that tolerates failures.

## BUG-20261030-13 — Automatic-module warnings and Flyway logging the database twice at start-up

- **Date**: 2026-10-30
- **Status**: Flyway's double `Database:` line is NOT A BUG: the tutorial session checked one "Successfully applied" / "up to date" per start; the second line is the connection Flyway opens for `info()`, which the dev console's migration panel reads. The automatic-module warnings (Flyway and the PostgreSQL driver have no `module-info`) stay OPEN, with the jlink item of BUG-20261030-17, which they share a cause with
- **Reported by**: the vidocq-tutorial session (batch 3, item 7)
- **Minimal reproduction**: a created application with `flyway-migration` and the PostgreSQL driver.
- **Cause hypothesis**: to investigate: the automatic modules are reported by the layer builder, the double log comes from two Flyway configurations.

## BUG-20261030-14 — `META-INF/openapi.yaml` edited under `vidocq dev` is not re-read on hot reload

- **Date**: 2026-10-30
- **Status**: NOT REPRODUCED on main 070881f2 (grimm main): an application with `META-INF/openapi.yaml` under `vidocq dev`, the title edited, the hot reload swapped the layer and `/openapi` served the new title. To verify against the tutorial's step; reopen with its exact sequence if it still happens
- **Reported by**: the vidocq-tutorial session (batch 5, item 2)
- **Minimal reproduction**: edit the file while `vidocq dev` runs: `/openapi` keeps the old content until a restart.
- **Cause hypothesis**: resources are not part of the watched roots, or Grimm's model cache outlives the reload.

## BUG-20261030-15 — Dev console, Mansart Data › JDQL: the placeholder names `Product`, not one of the application's entities

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/devconsole-14-15-19`: the example of the JDQL query editor, and of the CSV export, is `FROM` the application's first entity in name order, `ORDER BY` its id when the catalogue knows it (`JdqlActionsTest.theExampleQueryNamesAnEntityOfTheApplication`)
- **Reported by**: the vidocq-tutorial session (batch 5, item 6)
- **Minimal reproduction**: open the panel in an application with entities `Book` and `Loan`.
- **Cause hypothesis**: the placeholder is a constant.

## BUG-20261030-16 — The Keycloak dev service publishes no issuer, token URL nor admin console, and has no fixed port

- **Date**: 2026-10-30
- **Status**: FIXED on `feat/keycloak-dev-service-dx`: the service publishes `vidocq.dev.keycloak.{issuer,token-url,admin-console,admin-user}`, which the connection-information block and file list as `keycloak.*`; `vidocq.dev.keycloak.port` pins the host port; `describe` adds the token endpoint and the admin login (`DevServicesReportTest.theServicesOwnCoordinatesAreListedToo`, `KeycloakDevServiceTest`)
- **Reported by**: the vidocq-tutorial session (batch 6)
- **Minimal reproduction**: `vidocq dev` with `vidocq.dev.keycloak.realm-import`: `target/vidocq-dev-services.properties` holds the datasource only; the issuer port changes at every start.
- **Cause hypothesis**: `describe` and the published keys only cover what the application consumes; no `vidocq.dev.keycloak.port` key.

## BUG-20261030-17 — `vidocq build` packaging: docker does not chain jlink, automatic modules stop jlink, test jars in the distribution, no JVM options hook, no `--help` per command, incomplete extension list

- **Date**: 2026-10-30
- **Status**: item 4 (no JVM options hook) FIXED on `feat/launcher-java-opts`: the launchers pass `JAVA_OPTS` after `<jvmArgs>` (`VidocqPackageMojoTest.javaOptsReachTheJvmAfterTheConfiguredJvmArgs`); item 5 (`--help` per command) FIXED on `fix/packaging-17-cli-help-extensions` (`CliParserHelpTest`); item 6 (start-up list without the CDI-only extensions) FIXED on the same branch: they are listed as _CDI only_ (`ExtensionLoaderTest`); items 1-3 OPEN
- **Status**: item 4 (no JVM options hook) FIXED on `feat/launcher-java-opts`: the launchers pass `JAVA_OPTS` after `<jvmArgs>` (`VidocqPackageMojoTest.javaOptsReachTheJvmAfterTheConfiguredJvmArgs`); item 3 (test jars in `lib/`): `opentelemetry-sdk-testing` no longer reaches it, FIXED on `fix/packaging-17-lib-scope` with humboldt#46 (`NoTestLibraryTest`; the other jars listed, `flyway-database-cockroachdb`, `jackson-annotations`, `commons-text`, `annotations`, are Flyway's own runtime dependencies, not a scope error); items 1, 2, 5, 6 OPEN
- **Reported by**: the vidocq-tutorial session (batch 8)
- **Minimal reproduction**: `vidocq build docker --skip-tests` on the tutorial application (flyway + PostgreSQL + telemetry).
- **Cause hypothesis**: six separate causes, listed in the batch; jlink refuses `flyway.core` (automatic module), `opentelemetry-sdk-testing` reaches `lib/` through a wrong scope, `bin/<app>.sh` passes `$@` after `--module`.

## BUG-20261030-18 — `VidocqApplication.request` cannot send headers; a test cannot read the dev services' configuration

- **Date**: 2026-10-30
- **Status**: FIXED: headers on `feat/junit-request-headers` (`VidocqApplicationTest.aRequestCarriesTheHeadersOfTheTest`); the dev services' keys reach the test JVM as system properties (`DevServicesSessionListenerTest`, `vidocq test` folds them the same way), and the application a `@VidocqTest` starts gets them too since BUG-20261030-20 (Vidocq/vidocq#417). The tutorial reads `vidocq.dev.keycloak.token-url` from a test
- **Reported by**: the vidocq-tutorial session (batch 9, items 1 and 2)
- **Minimal reproduction**: a `@VidocqTest` calling a `@RolesAllowed` endpoint needs `Authorization: Bearer …` and a token from the Keycloak dev service.
- **Cause hypothesis**: the test API has no header overload, and the dev-service keys are given to the application only.

## BUG-20261030-19 — Startup report: "no dev service: not started by …" under `vidocq dev` without any provider; the default listener printed twice

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/devconsole-14-15-19`: under `vidocq dev` without a state file, the section says _no dev service configured (no vidocq-runtime-devservice-* dependency, or vidocq.dev.devServices=false)_ (`DevServicesSectionTest.underVidocqDevNoStateFileMeansNoDevServiceConfigured`); the default listener printed twice was already fixed by BUG-20261030-04 (one line per listener under the summary)
- **Reported by**: the vidocq-tutorial session (batch 9, item 3)
- **Minimal reproduction**: a created application with no dev-service dependency under `vidocq dev`.
- **Cause hypothesis**: partly fixed by BUG-20261030-05 (provider found, host given); the listener line comes from both the Chappe section and the summary.

## BUG-20261030-20 — The application a `@VidocqTest` starts does not get the dev services of the test run

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/vidocq-test-dev-services`: `@VidocqTest` hands the application each key a dev services session injected into the test JVM, its `vidocq.dev.provided.<key>` marker and the state file, before the annotation's own `properties` (`ApplicationLaunchTest.theApplicationGetsTheKeysTheDevServicesInjected`)
- **Reported by**: the vidocq-tutorial session (batch 10, items 1 and 2)
- **Minimal reproduction**: an application on Flyway and PostgreSQL, no database running, `vidocq test` (or `mvn test` with `vidocq-runtime-devservices-junit`): the PostgreSQL container starts, then every `@VidocqTest` class fails with _Connection to localhost:5432 refused_, the URL of `vidocq.properties`.
- **Cause hypothesis**: the session folds its keys into the system properties of the test JVM, where an in-process bootstrap reads them; since `@VidocqTest` starts the application in its own JVM (#350), it passed that JVM the annotation's `properties` only.

## BUG-20261030-21 — Under `mvn test`, the dev services log in the JDK's two-line format, in the machine's language

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/devservices-junit-log`: the `vidocq-runtime-devservices-junit` listener logs through `ConsoleSystemLogger`, one `[INFO]`/`[WARNING]`/`[ERROR]` line as the goals do since BUG-20261030-07 (`ConsoleSystemLoggerTest`)
- **Reported by**: the vidocq-tutorial session (batch 10, after the train #413-#417)
- **Minimal reproduction**: `mvn test` on an application with `vidocq-runtime-devservices-junit` in test scope, on a French machine: _oct. 30, 2026 … INFOS: …_ in two lines.
- **Cause hypothesis**: the listener used `System.getLogger`, the JDK's default (java.util.logging) format; #410 only changed the loggers of the Maven goals.

## BUG-20261030-22 — The JWT and Rest Client extensions declare a second `BuildCompatibleExtension` beside their brick's

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/remove-bce-relays`: the relays `CervantesJwtBuildCompatibleExtension` and `CyranoBuildCompatibleExtension` are removed, with their `META-INF/services` entries, `provides` and `opens ... to io.vidocq.vauban.core` (`CervantesSingleExtensionTest`, `CyranoSingleExtensionTest`; JWT TCK 208/208, Rest Client TCK 235, 9 skipped, on the assembled runtime)
- **Reported by**: found while adding the codegen bundles of the tutorial's batch 7
- **Minimal reproduction**: list the `BuildCompatibleExtension` providers of the boot layer with `vidocq-runtime-cervantes-jwt-extension` (or the Rest Client one): `CervantesClaimExtension` and its empty subclass `CervantesJwtBuildCompatibleExtension` are both declared.
- **Cause hypothesis**: the wrappers republished their brick's extension through a subclass when the brick did not declare it; `cervantes-cdi-vauban` and `cyrano-cdi-vauban` now declare it themselves, in an exported package. Two extensions doing the same work, and a codegen bundle putting the brick on the processor path saw both.

## BUG-20261030-23 — Vauban warns at each build that the JWT, Metrics and OpenAPI extensions will not run

- **Date**: 2026-10-30
- **Status**: FIXED on `feat/codegen-bundles-bce`: new `vidocq-runtime-cervantes-jwt-extension-codegen` (cervantes-cdi-vauban, cdi-api, the Config bundle); the Metrics bundle adds dirac-cdi-vauban with cdi-api. JWT TCK 208/208, Metrics 127/127. OpenAPI still OPEN: `GrimmExtension` on the processor path registers its synthetic `ScannedTypes` bean at build time, and again when the application starts (`vidocq-runtime-it-grimm-openapi`: _Ambiguous dependency … ScannedTypes_)
- **Reported by**: the vidocq-tutorial session (batch 7, item 4; batch 10)
- **Minimal reproduction**: `vidocq create -x cassini-rest -x dirac-metrics -x grimm-openapi -x cervantes-jwt`, then `mvn compile`: _[Vauban] … Build Compatible Extension(s) present on the compile/module path are NOT on the annotation-processor path and will not run: [CervantesClaimExtension, DiracExtension, GrimmExtension]_.
- **Cause hypothesis**: JWT had no codegen bundle; the Metrics and OpenAPI bundles carried their processor only, not the jar of their extension's BCE, nor the CDI API those jars leave `provided`.
- **Still open**: _Unsatisfied dependency … JsonWebToken_ at build time. The producer is a bean of cervantes-cdi-vauban, which Vauban's build-time validation does not see; it defers to the runtime container, where injection works.

## BUG-20261030-25 — A request's trace has no database span

- **Date**: 2026-10-30
- **Status**: IN PROGRESS. Step 1 done on `fix/jdbc-spans-25`: the hook. Every connection the Vidocq DataSources hand out (the `@Default` pool and each `@Named` one) goes through the `JdbcConnectionDecorator`s found by `ServiceLoader`, given the datasource's name. With none installed, the pool's own connection comes back (`JdbcConnectionDecoratorsTest`). Step 2, the tracing decorator, is OPEN; design below.
- **Reported by**: the vidocq-tutorial session (batch 7, item 5)
- **Minimal reproduction**: an application with `humboldt-telemetry`, `mansart-data` and `mansart-pool` on PostgreSQL, OTLP to the otel-lgtm dev service. `GET /books/2/details` shows the HTTP server span and the REST client span in Tempo, but no span for the SQL the request runs.
- **Cause hypothesis**: nothing instruments JDBC. Mansart Pool is zero-dependency and knows nothing of OpenTelemetry, and Humboldt instruments REST (server and client filters) and `@WithSpan` only.
- **Design (step 2)**:
  - **Where.** A glue module, `vidocq-runtime-mansart-pool-telemetry` (artifactId to confirm). It requires `io.opentelemetry.api` and the pool extension, and declares `provides JdbcConnectionDecorator`. Mansart and the pool extension keep no OpenTelemetry dependency. Without the glue module there is no overhead: the decorator list is empty.
  - **How it gets in.** `vidocq extension add` and the scaffold add the glue module when both `humboldt-telemetry` and `mansart-pool` are declared: a catalog "companion" entry (a new catalog key). `vidocq:checkpom` warns when the pair is present without it.
  - **Wrappers.** Hand-written delegating classes, no dynamic proxy: `TracingConnection` wraps `createStatement`, `prepareStatement` and `prepareCall`; `TracingStatement`, `TracingPreparedStatement` and `TracingCallableStatement` delegate every method. `execute*` and `executeBatch` run in a CLIENT span, a child of `Context.current()`. Generated once from the JDK interfaces by a build-time script and committed, about 400 one-line methods.
  - **Span.**
    - Name: `<db.operation.name> <db.collection.name>` when the statement parses (`SELECT books`), else `db.system.name`.
    - Attributes, per the OpenTelemetry semantic conventions v1.26+: `db.system.name` (from the JDBC URL: postgresql, h2…), `db.namespace` (the database), `db.query.text` (the prepared SQL as is; for a plain `Statement`, literals replaced by `?`), `db.operation.name`, `server.address`, `server.port`, `db.response.status_code` on `SQLException`.
    - The span records the exception and sets status ERROR. No span for `close`, `getConnection` or metadata calls.
  - **Tests.** Unit tests on the sanitizer and on span names. A Humboldt in-memory exporter test: a `@VidocqTest` request that runs a query yields a server span with one database child span, on H2, then PostgreSQL via Testcontainers.
  - **Threads.** No `ThreadLocal`; the span's scope is opened and closed within each `execute*` call, on the caller's virtual thread.
## BUG-20261030-26 — Build noise: javac's "created in the last round" per generated class, a raw JUL line per resource, a grimm-processor line per class

- **Date**: 2026-10-30
- **Status**: FIXED in the bricks, on `fix/build-noise-26`:
  - Vauban (VAU-APT-006): its classes are written before javac's last round, and what the BCEs log at build time becomes javac messages (`GenerationRoundTest`, `BceLoggingTest`);
  - Cassini (CASSINI-038): the default scopes are logged at DEBUG (`CassiniScopeExtensionTest.theDefaultScopesAreSaidAtDebugLevel`);
  - Grimm (BUG-20261030-05): one note per build for the classes left to the run-time scan (`SkippedClassesSummaryTest`).

  Measured on an application created with `cassini-rest`, `cyrano-rest-client` and `grimm-openapi` (an intercepted bean, a `@Tag` resource, a `@Provider` without scope, a rest client). Before: 5 "created in the last round" warnings, 2 two-line French JUL records, 1 grimm line per tagged class. After: none of them, and one grimm note.
- **Reported by**: the vidocq-tutorial session (batch 10, item 4)
- **Minimal reproduction**: `mvn compile` on the tutorial application: 17 `[WARNING] File for type '…_ClientProxy' / '…$$Intercepted' / '…._VaubanComponents' created in the last round will not be subject to annotation processing`, `oct. 30, 2026 … CassiniScopeExtension addProviderDefaultScope` / `INFOS: @Provider class … defaulting to @Dependent`, `[grimm-processor] skipping …$$GrimmModel — … Tag is outside the M1 subset`.
- **Cause hypothesis**:
  - Vauban generated in javac's last round, and javac warns for every file created there, whatever `-Xlint` says;
  - the BCEs Vauban runs at build time logged through the JDK's default console handler;
  - grimm-processor reported its working fallback per class.
- **Still there**: an application whose beans carry `@Inherited` annotations that are not CDI's, JAX-RS's or OpenAPI's keeps the last round, and its warnings. Class-level Fault Tolerance and Metrics annotations are such annotations: their processors would take Vauban's subclasses for their own. `-Avauban.generationRound=last` restores the former behaviour everywhere.
## BUG-20261030-27 — An application with Fault Tolerance and Metrics stops at boot on two `DiscoveredMetrics` beans

- **Date**: 2026-10-30
- **Status**: FIXED in Dirac, https://codefloe.com/Vidocq/dirac/pulls/40 (DRC-008): `GaugeRegistrationBean` registers the union of every `DiscoveredMetrics` bean, each metric once (`DiscoveredMetricsMergeTest`; MP Metrics TCK 127/127; `vidocq-runtime-tck-heisenberg-faulttolerance` 463/463). Nothing to change in this repository once Dirac's SNAPSHOT is published
- **Reported by**: found while measuring the build noise of the tutorial application (BUG-20261030-26)
- **Minimal reproduction**: `vidocq create -x cassini-rest -x heisenberg-fault-tolerance -x dirac-metrics`, a bean with class-level `@Retry` and `@Counted` injected into a resource: _AmbiguousResolutionException: 2 beans match: [DiscoveredMetrics, DiscoveredMetrics]_ from `GaugeRegistrationBean.registerMetrics`, the application exits before it listens.
- **Cause**: since the Metrics codegen bundle carries `dirac-cdi-vauban` (#426), `DiracExtension` runs at build time and again at boot; each run registers a synthetic `DiscoveredMetrics` bean listing the classes it saw. The lists differ, so Vauban keeps both, and `Instance.get()` was ambiguous. Same pattern as Grimm's `ScannedTypes` (grimm#44, BUG-20261030-23).

## BUG-20261030-28 — `vidocq test` starts the Keycloak dev service on realm `master`, ignoring `vidocq.properties`

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/vidocq-test-process-classes`: `vidocq test` runs `process-classes vidocq:test` (`RunGoalsTest.testRunsTheContinuousTestingGoalAfterProcessClasses`)
- **Reported by**: the vidocq-tutorial session (after the train vidocq#434), step 11
- **Minimal reproduction**: a clean project whose `src/main/resources/vidocq.properties` sets `vidocq.dev.keycloak.realm=library` and a realm import; `vidocq test` logs _issuer …/realms/master_, and a test that asks the token URL for a realm user gets 401. `vidocq dev` and `mvn test` use the right realm.
- **Cause hypothesis**: `vidocq:test` opens the dev services from `target/classes/vidocq.properties` before its first run, and the CLI ran the goal alone; a project without a previous build (here, after a history rewrite) had no such file. Not a regression of #434: an older `target/classes` hid it.
## BUG-20261030-24 — A package the runtime reaches by reflection, and the module does not open, fails at the first request

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/opens-check-24`: `vidocq:check-module-info` reads the compiled classes (`ReflectedPackages`): JPA entities, and the types a REST resource or provider takes or returns, with the types their fields and getters hold. A package among them that the module neither opens nor exports fails the build with the `opens` line to add (`ReflectedPackagesTest`, 4 cases)
- **Reported by**: the vidocq-tutorial session (batch 2, item 3)
- **Minimal reproduction**: `vidocq create -x cassini-rest`, then a `BookResource` returning a `Book` DTO in `com.acme.shop.book` (the scaffold opens `com.acme.shop` only): the build passes, and `GET /books` answers 500 with _IllegalAccessException: … RuntimeBindingRegistry$MethodAccessor (in module io.vidocq.champollion.jsonb) cannot access class com.acme.shop.book.Book … because module com.acme.shop does not export com.acme.shop.book_.
- **Cause hypothesis**: `check-module-info` only checked the directives the extensions declare (`opens db.migration`); the packages of the application itself were checked by nobody before the first request.

## BUG-20261030-07 — Dev services log in two localized lines among the `[INFO]` lines of `vidocq dev`

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/devservices-log-through-maven`
- **Affected modules**: vidocq-runtime-devservices-host, vidocq-runtime-maven-plugin
- **Symptom**: on a French-locale machine, `vidocq dev` prints _oct. 30, 2026 1:52:50 PM
  io.vidocq.runtime.devservices.postgres.PostgresDevService start_ / _INFOS: Postgres dev service 'default' ready
  at …_ between the `[INFO]` lines of Maven. Reported by the tutorial session (batch 4, item 2).
- **Minimal reproduction**: `LANG=fr_FR.UTF-8 vidocq dev` on an application with a PostgreSQL dev service.
- **Cause**: the goals gave the session `System.getLogger(…)`, and the context the providers log through made its
  own; both are java.util.logging loggers with the JDK's default `SimpleFormatter`, two lines and localized, in the
  Maven JVM.
- **Fix**: `MavenSystemLogger`, a `System.Logger` that writes to the goal's `Log` (parameters formatted with
  `Locale.ROOT`), given by `vidocq:dev`, `vidocq:run` and `vidocq:test`; `DevServicesSession.open` hands the host's
  logger to the context, so `ctx.log()` is the same. `MavenSystemLoggerTest`, `DefaultDevServiceContextTest`.

## BUG-20261030-04 — The startup report counts and prints the same things twice, differently

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/startup-report-consistency`
- **Affected modules**: vidocq-runtime-cassini-rest-extension (`CassiniExtension`), vidocq-runtime-chappe-webserver-extension
  (`ChappeEngineExtension`)
- **Symptom**: with one `HelloResource`, the log said "Cassini REST extension mounted … (2 resource class(es))" while
  the `rest` section said "1 resource class, 1 route"; the `http` section printed the default listener twice, as its
  summary ("default http://localhost:8080/") and as its line. Reported by the vidocq-tutorial session.
- **Minimal reproduction**: `ChappeListenersSectionTest.declaresEachConfiguredListenerWithTheAddressItBound`; for the
  log, a created application with one resource under `vidocq dev`.
- **Cause**: the log counted every class the bean provider hands Cassini, `@Provider` classes included, where the
  report separates resources from providers; the `http` summary named the listeners the detailed report also prints
  one per line.
- **Fix**: the log reads the counts from the `RestMount` the report reads ("1 resource class(es), 1 provider(s)"); in
  the detailed report the `http` summary is a count ("1 listener"), and it keeps naming the listeners when the report
  prints summaries only.

## BUG-20261030-05 — The dev services section says "not started by vidocq:dev" under vidocq:dev

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/startup-report-consistency`
- **Affected module**: vidocq-runtime-devservices-extension (`DevServicesSection`)
- **Symptom**: under `vidocq dev`, with no dev service provider among the plugin's dependencies, the section said
  "no dev service: not started by vidocq:dev, vidocq:run or the test launcher". Reported by the vidocq-tutorial
  session.
- **Minimal reproduction**: `DevServicesSectionTest.aHostWithoutAnyProviderSaysSo`.
- **Cause**: the section said the same for a missing state file and for a state file a host wrote without any service
  (a test even kept that summary on purpose).
- **Fix**: a state file without services nor skipped providers now says "no dev service: vidocq:dev found no provider
  (a vidocq-runtime-devservice-* dependency)", naming the host that wrote it; without a state file the summary is
  unchanged.
## BUG-20261030-01 — A created application does not build in a git repository without commits

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/build-info-unborn-git-head`
- **Affected module**: vidocq-runtime-maven-plugin (`CodegenPathsParticipant`), vidocq-runtime-parent (`vidocq-build-info`)
- **Symptom**: the beginner's flow `vidocq create --name library -x cassini-rest && cd library && git init && ./mvnw
  package` failed: `git-commit-id-maven-plugin:9.2.0:revision (vidocq-build-info): Could not get HEAD Ref, are you
  sure you have some commits in the dotGitDirectory`. The same project outside any repository builds. Reported by
  the vidocq-tutorial session.
- **Minimal reproduction**: `UnbornGitHeadTest`; end to end, the flow above against the deployed SNAPSHOT.
- **Cause**: `GitCommitIdMojo` turns the `GitCommitIdExecutionException` of an unborn `HEAD` into a
  `MojoExecutionException` whatever `failOnNoGitDirectory` and `failOnUnableToExtractRepoInfo` say (both are already
  `false` in the parent).
- **Fix**: the plugin's lifecycle participant, which the parent loads as a build extension, sets
  `maven.gitcommitid.skip=true` on a project whose repository's `HEAD` names a branch without any commit (no file under
  `refs/`, no line in `packed-refs`). The build identity is then left out, as outside a repository; `BuildInfo` reads
  it as unknown. A value the project sets itself is kept; a `.git` file (worktree, submodule) is left to the plugin.
  After the first commit the identity is written again.

## BUG-20261030-02 — `vidocq doctor` outside a project advises to install Maven

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/build-info-unborn-git-head`
- **Affected module**: vidocq-runtime-tooling (`Diagnostics`)
- **Symptom**: outside a project, `vidocq doctor` warned "Maven wrapper — no mvnw on this path ↳ Run from a project
  that ships ./mvnw, or install Maven", with Maven 3.9.16 on the PATH. Reported by the vidocq-tutorial session.
- **Minimal reproduction**: `DiagnosticsTest.noWrapperOutsideAProjectIsNotAWarning`.
- **Cause**: the check treated a missing wrapper the same inside and outside a project.
- **Fix**: outside a project the check is OK, "not needed outside a project: vidocq create writes ./mvnw"; inside a
  project without `mvnw` it still warns and offers the fix.
## BUG-20261030-03 — `vidocq dev` misses the first files of a package created while it runs

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/watcher-new-package-dirs`
- **Affected module**: vidocq-runtime-maven-plugin (`dev.SourceWatcher`)
- **Symptom**: while `vidocq dev` runs, `mkdir -p src/test/java/com/example/library/book` and a test written there
  ran nothing; the same on the main side for the first files of a new `src/main/java/.../book/` package. Reported by
  the vidocq-tutorial session.
- **Minimal reproduction**: `SourceWatcherTest.a_package_created_later_and_its_files_are_watched`.
- **Cause**: the watcher registered a new directory on its `ENTRY_CREATE` event, but a directory is not a watched
  file, so the event changed nothing, and the files written into it before it was registered raised no event of their
  own: on macOS the `WatchService` polls about every 10 s, so a `mkdir -p` and a save always land in between.
- **Fix**: a directory registered on its creation is walked once, and a watched file already in it is a change of its
  kind, as a watched root that appears already was (#138).
## BUG-20261030-06 — `@VidocqTest` starts the application without its runtime-scope JDBC driver

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/junit-runtime-classpath-jars`
- **Affected modules**: vidocq-runtime-junit, vidocq-runtime-maven-plugin, vidocq-runtime-parent
- **Symptom**: a `@VidocqTest` of an application with Flyway and PostgreSQL fails at boot with
  _FlywayException: No JDBC driver found_, while `vidocq:dev` runs the same application fine. Reported by the
  tutorial session (batch 3, item 1).
- **Minimal reproduction**: a created application with `org.postgresql:postgresql` in `runtime` scope, a resource
  that lists `DriverManager.drivers()`, and a `@VidocqTest` that expects `org.postgresql.Driver` in it: the list is
  empty.
- **Cause**: Surefire puts on the module path only the dependencies the application module requires; the driver,
  which no module requires, stays on the class path of the tests (checked: `jdk.module.path` holds 36 entries
  without it, `java.class.path` has it). `ApplicationLaunch` started the application on `jdk.module.path` with
  `--add-modules <module>`, so it never had the jar, nor would it have resolved it.
- **Fix**: the new goal `vidocq:test-path` (`process-test-classes`, bound by `vidocq-runtime-parent`) puts the compile
  and runtime jars in the project property `vidocq.test.runtimePath`; the parent's Surefire configuration passes it as
  a system property (no default value, so that the expression is resolved when Surefire runs, as jacoco's
  `argLine`). `ApplicationLaunch` adds the jars `jdk.module.path` lacks and resolves `ALL-MODULE-PATH`, as `vidocq:dev`
  and the packaged application do. Continuous testing runs `process-test-classes surefire:test` instead of
  `test-compile surefire:test`, which stopped before the goal. `ApplicationLaunchTest`, `VidocqTestPathMojoTest`,
  `TestRunnerTest`; checked end to end on a created application: the driver test fails before, passes after.

## BUG-20261026-01 — `vidocq:dev` and `vidocq:run` start a Vaadin application from an empty `target/` and answer 404 / 500

- **Date**: 2026-10-26
- **Status**: FIXED on `fix/dev-builds-an-empty-target` for `vidocq:dev` (code), and for `vidocq:run` in the pom (one
  binding), see the Vaadin guide (umbrella #339)
- **Affected module**: vidocq-runtime-maven-plugin
- **Symptom**: after `mvn clean`, `vidocq:dev` starts with `0 servlet(s)` and answers 404 to every request, and
  `vidocq:run` answers 500 with _Unable to find index.html_ (Vaadin 25, `VaadinCreate2026`). Both work after one
  `mvn package`.
- **Minimal reproduction**: `rm -rf target && mvn vidocq:dev` (or `vidocq:run`) in a Vaadin application whose pom
  binds `build-frontend` and `vidocq:generate` as the guide says.
- **Cause**: two different ones. `vidocq:run` runs the lifecycle up to `process-classes`, but Vaadin's `build-frontend`
  is bound to `prepare-package` by default, so the production bundle is never built. `vidocq:dev` runs no phase at
  all (`defaultPhase = NONE`), and its start-up check, `CompilerOutput.ensureComplete`, only compares `target/classes`
  with what the compiler recorded: with nothing built there is no record, so the application starts with no class,
  whatever the framework. The reload loop already ran `mvn process-classes`, the first start did not.
- **Fix**: `vidocq:dev` runs one `mvn process-classes` first when `target/classes` is missing or holds no file
  (`CompilerOutput.ensureBuilt`, with a warning that says so), which runs everything bound to those phases.
  `vidocq:run` needs `build-frontend` bound to `process-classes` in the application's pom (an execution of its own,
  `<phase>process-classes</phase>`), now in the Vaadin guide. `CompilerOutputTest` (9 cases). Checked from an empty
  `target/` on `VaadinCreate2026` with the 0.4.0-SNAPSHOT of 2026-10-25 plus this fix: `dev` and `run` answer 200 on
  the page, `?v-r=init` and the three `VAADIN/build` bundles; without the binding `run` answers 500 and without the
  fix `dev` answers 404.

## BUG-20261025-06 — Eclipse builds a Vidocq application without its annotation processors: no bean index

- **Date**: 2026-10-25
- **Status**: FIXED on `feat/vidocq-eclipse` (umbrella #339)
- **Affected module**: vidocq-runtime-parent
- **Symptom**: a project `vidocq create -x cassini-rest` writes, imported into Eclipse IDE 2026-09 (m2e 2.8) with
  Build Automatically on, gets a `target/classes` with no `META-INF/vauban-beans.list`, no generated factory and no
  Cassini route: the application starts without its beans and answers 404. No `.factorypath` is written.
- **Minimal reproduction**: import the project into a new Eclipse workspace (File > Import > Existing Maven Projects)
  and list `target/classes`.
- **Cause**: m2e configures JDT's annotation processing from the pom only when its "Annotation Processing" preference
  or the pom's `m2e.apt.activation` property asks; Eclipse's default does not. VS Code's Java extension asks by
  itself, which is why VS Code's build had the processors.
- **Fix**: `vidocq-runtime-parent` sets `m2e.apt.activation=jdt_apt`; `ApplicationBomAndParentTest`. Checked in
  Eclipse 2026-09 driven headless: with the property inherited, its build writes the files of a Maven build (without
  the weaving of Vauban's javac plugin, which its compiler does not run), and the application answers 200.
## BUG-20261025-03 — an application compiled without Vauban's processor starts without its beans, and says nothing

- **Date**: 2026-10-25
- **Status**: FIXED on `fix/unindexed-app-anomaly` (umbrella #339)
- **Affected module**: vidocq-runtime-core
- **Symptom**: a project `vidocq create -x cassini-rest` writes, compiled by VS Code's Java extension without Vauban's
  processor (it failed under Eclipse's compiler, Vidocq/vauban VAU-APT-005; or could not load, the extension's server
  on Java 21), starts with `anomalies none`, registers one REST resource class instead of two, and answers 404 to
  `GET /hello`.
- **Minimal reproduction**: the runtime on such a `target/classes` (`-Dvidocq.app.path=…/classes`), or any
  application module with an `@ApplicationScoped` or `@Path` class and no `META-INF/vauban-beans.list`.
- **Cause**: nothing indexes a module the processor did not: the fallback of VIDOCQ-GEN-001 scans `beans.xml` archives
  only, and a created application has none. GEN-001 needs an application module, which an application started by the
  runtime's own main does not name.
- **Fix**: `VIDOCQ-GEN-002`: the boot checks the application module, when named, and every exploded module of the
  application layer; one with bean classes (a CDI scope, `@Singleton`, `@Path`, an interceptor or a decorator) and none
  of the files the processor leaves is logged, with how to build it. Only read when the three files are missing.
  `GenerateStampTest`; checked on the IDE output above (anomaly logged) and on Maven's (none).
## BUG-20261025-05 — the tree-kill test of `vidocq:dev`'s test runner fails at random: "No such process"

- **Date**: 2026-10-25
- **Status**: FIXED on `fix/tree-kill-test-race`
- **Affected module**: vidocq-runtime-maven-plugin (test only)
- **Symptom**: `TestRunnerTest.aDescendantThatIgnoresTheTermSignalDoesNotDelayTheCancel` errors with
  `IOException: No such process` at `gone`, failing `pr-validate` of a pull request that does not touch the plugin
  (vidocq#369, run 1861).
- **Minimal reproduction**: none on demand; a busy Linux runner where the killed descendant is reaped between the two
  reads of its `/proc` entry.
- **Cause**: the test's `gone(pid)` checks that `/proc/<pid>/stat` is readable, then reads it; a process reaped in
  between makes the read fail instead of counting as gone.
- **Fix**: a failed read of the entry counts as gone. The runner itself is not affected.
## BUG-20261025-04 — VS Code cannot import an application on `vidocq-runtime-parent` 0.4.0

- **Date**: 2026-10-25
- **Status**: FIXED on `fix/participant-java17` (umbrella #339; the IDE side is VAU-APT-005 in Vidocq/vauban)
- **Affected module**: vidocq-runtime-maven-plugin
- **Symptom**: VS Code's Java extension (1.56, jdt.ls 1.61, m2e 2.7.8) on its default runtime, Java 21, fails the
  Maven import of a project `vidocq create` writes: `Initialization failed … TypeNotPresentException: Type
  CodegenPathsParticipant not present`. No `.classpath`, nothing compiled, no completion. Also, on any runtime, m2e
  flags `Plugin execution not covered by lifecycle configuration` for `checkpom` and `check-module-info` on the pom.
- **Minimal reproduction**: jdt.ls headless (the extension's `server/`, its own `jre/21…`) on a project
  `vidocq create --name todo -x cassini-rest` writes, after #361.
- **Cause**: #361 loads the plugin as a build extension for its lifecycle participant; the participant was a Java 25
  class file (the plugin's release) and named `vidocq-runtime-tooling` classes, also Java 25. m2e runs on the IDE's
  runtime, older than the build's. The lifecycle-mapping warnings: the plugin carried no m2e mapping for the two
  checks the parent binds.
- **Fix**: `CodegenPaths` and `CodegenPathsParticipant` are compiled for Java 17 in an execution of their own, and read
  the extension catalog as a resource (`META-INF/vidocq/extension-catalog.properties`) instead of through
  `KnownExtensions`; `CodegenPathsParticipantTest` checks both class files (version, no `tooling` class). The plugin
  carries `META-INF/m2e/lifecycle-mapping-metadata.xml`, which has m2e ignore the two checks
  (`LifecycleMappingMetadataTest`). Checked in jdt.ls on Java 21: the import succeeds, the processor path holds the
  indexer and the extension's bundle, no lifecycle warning. Annotation processing itself still needs the language
  server on Java 25 (`java.jdt.ls.java.home`): JDT runs processors in its own JVM, and they are Java 25 class files.
  Eclipse 2026-09 ships Java 25.

## BUG-20261025-02 — a `vidocq:jlink` profile cannot be read: `${vauban.version}` is gone from `vidocq-runtime-parent`

- **Date**: 2026-10-25
- **Status**: FIXED on `fix/parent-manages-vauban-plugin`
- **Affected module**: vidocq-runtime-parent
- **Symptom**: `mvn -Pjlink clean package vidocq:jlink` on `VidocqTools/vaadin-on-vidocq`, or on any pom that copies
  the `vauban:modularize` snippet of the Vaadin guide, stops before building: `'build.plugins.plugin.version' for
  io.vidocq.vauban:vauban-maven-plugin must be a valid version but is '${vauban.version}'`.
- **Minimal reproduction**: an application on the `vidocq-runtime-parent` snapshot of 2026-10-25 14:39 UTC that declares
  `vauban-maven-plugin` with `<version>${vauban.version}</version>`; `mvn validate`.
- **Cause**: until #348, `vidocq-runtime-parent` was the reactor's root and defined `vauban.version`, which applications
  inherited. fbd29643 (#348) made it a parent of its own: the property stayed on the root, `vidocq-runtime-build`, and
  the parent neither defined it nor managed the plugin.
- **Fix**: the parent defines `vauban.version`, kept equal to the root's, and manages `vauban-maven-plugin` at it, so an
  application declares the plugin without a version and an older pom that writes `${vauban.version}` still resolves.
  `ApplicationBomAndParentTest` fails when the parent's `vauban.version` is not the root's or the plugin is unmanaged.
- **Proof**: `vaadin-on-vidocq` (`-Pjlink`) and VaadinCreate2026, with the plugin's `<version>` removed, build their
  image on the fixed parent; each image answers 200 with its frontend bundle.

## BUG-20261025-01 — every application built on `vidocq-runtime-parent` warns of a plugin managed twice

- **Date**: 2026-10-25
- **Status**: FIXED on `feat/vidocq-test` (#352, umbrella #339)
- **Affected module**: vidocq-runtime-parent
- **Symptom**: `mvn verify` on a project `vidocq create` writes prints `'build.pluginManagement.plugins.plugin.(groupId:artifactId)'
  must be unique but found duplicate declaration of plugin org.apache.maven.plugins:maven-deploy-plugin`, and the
  application inherits `deployAtEnd=true`, a setting of this repository's releases.
- **Minimal reproduction**: on 7e7f5d44, `vidocq-runtime-it-create` (`CreateThenBuildIT`), or `mvn validate` on any pom
  whose parent is `vidocq-runtime-parent` at that commit.
- **Cause**: 7e7f5d44 (#285) added a `maven-deploy-plugin` entry with `deployAtEnd` to the parent's
  `pluginManagement`, which already pinned the plugin's version in another entry. Main's build does not build an
  application on the parent, so nothing failed until the create-then-build IT of #352 did.
- **Fix**: one `pluginManagement` entry pins the version; `deployAtEnd` moves to the parent's `<build><plugins>`
  with `<inherited>false</inherited>`, so it batches the parent's own deploy and no application inherits it.
  `ApplicationBomAndParentTest` fails on a plugin managed twice and on a `deployAtEnd` an application would inherit.

## BUG-20261024-01 — `ListenerFromADependencyRunsIT` fails when Central cannot be reached: its fixture downloads plugins

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/flaky-dependency-codegen-offline` (Vidocq/vidocq#285, CI study of the flaky tests)
- **Symptom**: the 3 methods of `ListenerFromADependencyRunsIT` (`vidocq-runtime-it-dependency-codegen`, `-Pit`)
  fail with `the fixture build failed: ... Could not transfer artifact
  org.apache.maven.plugins:maven-resources-plugin:pom:3.4.0 from/to central (https://repo.maven.apache.org/maven2):
  repo.maven.apache.org: Temporary failure in name resolution`. Seen once in the 9 last failures of vauban's
  `pr.yml` (≈1.5 days), in vidocq's downstream rebuild (run 46753, 1 585 runner-s lost); the PR did not cause it.
- **Minimal repro**: build the module once online, then
  `MAVEN_OPTS="-DsocksProxyHost=127.0.0.1 -DsocksProxyPort=9" mvn -o -Pit -pl
  vidocq-runtime-integration-tests/vidocq-runtime-it-dependency-codegen clean verify`: the outer build is offline,
  the fixture's `mvn` has no network. Before the fix: 3 failures out of 3 runs, `maven-compiler-plugin:pom:3.13.0
  ... Can't connect to SOCKS proxy`.
- **Cause**: a test that depends on the network, not a product bug. The fixture's settings added the outer local
  repository as a `file:` repository but kept Central behind it, and the fixture needed artifacts the outer build had
  never resolved, so each run downloaded them: 89 artifacts of `target/it-repo` came from `central`. The `lib`
  module has no parent, so it ran the default plugin versions of whichever `mvn` ran the fixture (compiler 3.13.0,
  surefire 3.2.5, install 3.1.2 with Maven 3.9.16; resources 3.4.0 with the CI's Maven), not the ones the build
  pins; the aggregator POM did the same for `install`; and the version range of the app's inherited
  `git-commit-id-maven-plugin` (bcutil `[1.81,1.82)`) needs a `maven-metadata.xml`, which a local repository read as a
  `file:` repository does not have (it holds `maven-metadata-central.xml`).
- **Fix**: `src/it/it-settings.xml` mirrors every repository (`*`) to the outer local repository, so the fixture
  never reaches the network. The fixture's `lib` and aggregator POMs pin the outer build's plugin versions
  (`@maven.*.plugin.version@`, filtered from `vidocq-parent`). `maven-invoker-plugin:install` stages, as
  `extraArtifacts`, `maven-install-plugin` (which an outer `verify` never resolves) and `git-commit-id-maven-plugin`,
  whose installation writes the `maven-metadata-local.xml` the range needs. What the IT checks is unchanged.
- **Proof**: with the network cut as above, 50 runs out of 50 green (0 failures); `target/it-repo` holds no artifact
  from `central` any more. From an empty local repository, an outer `verify` (no `install`) with `-am` passes the 3
  tests, so `maven-install-plugin` comes from the staging. Module `clean install` green.
- **Same exposure, same fix: `vidocq-runtime-it-app-layer`** (`AppLayerRunIT`, `AppLayerPackageIT`, `AppLayerDevIT`,
  `AppLayerJspIT`). Same settings with Central behind the outer repository, and parentless `lib` and `sql` fixtures
  plus an aggregator on default plugin versions. Before: with the fixture's network cut, 6 failures out of 6 tests
  (`maven-compiler-plugin:pom:3.13.0 ... Can't connect to SOCKS proxy`). Fix: the mirror, the `lib`, `sql` and
  aggregator POMs pinned to `@maven.*.plugin.version@`, and `maven-install-plugin` and `git-commit-id-maven-plugin`
  staged. After: 30 runs out of 30 green with the network cut; module `clean install -Pit` green.
- **Same exposure, same fix: `vidocq-runtime-it-continuous-testing`** (`ContinuousTestingTestGoalIT`,
  `ContinuousTestingDevGoalIT`, `DevToolsPackagingIT`). Same settings; its single-module fixture has the runtime's
  parent, so it only needed the bcutil range of `git-commit-id-maven-plugin` from Central, and, once the mirror closed
  Central, surefire's JUnit Platform provider (`surefire-junit-platform`), which this module, with no unit tests,
  never resolves. Before: with the fixture's network cut, 3 failures out of 3 tests (`bcutil-jdk18on/maven-metadata.xml
  ... Can't connect to SOCKS proxy`). Fix: the mirror, and `surefire-junit-platform` and `git-commit-id-maven-plugin`
  staged. After: 30 runs out of 30 green with the network cut; module `clean install -Pit` green. From an empty local
  repository, an outer `verify` of both modules with `-am` and no unit tests passes their 9 tests.

## BUG-20261022-01 — an enriched jar has no folder entries, so Vaadin's development mode copies no frontend files

- **Date**: 2026-10-22
- **Status**: FIXED on `pr/ybl/enriched-jar-folders` (found on threadlocalrandom/VaadinCreate2026, Vidocq/vidocq#256)
- **Symptom**: with `vaadin-dev-server` and no ready development bundle, `vidocq:run` fails the Vite build:
  `Cannot find module or type declarations for side-effect import of
  'Frontend/generated/jar-resources/vaadin-dev-tools/vaadin-dev-tools.js'`. Vaadin logs `Visited 16 resources`
  while copying the frontend files of its jars, and `vaadin-dev-tools/` stays empty.
- **Minimal repro**: `EnrichedJars.write` on a jar holding `META-INF/frontend/tools/tools.js` and its folder entries:
  the copy keeps the file and drops `META-INF/frontend/` and `META-INF/frontend/tools/`.
- **Cause**: `EnrichedJars.write` skipped every directory entry of the source jar. The JDK finds a folder resource of
  a jar, such as `META-INF/frontend/`, only through its directory entry, so Vaadin found no frontend folder in the
  three jars `vidocq:generate` enriches (`flow-server`, `vaadin-dev-server`, `atmosphere-runtime`), which had 0
  directory entries instead of 17 in the original `vaadin-dev-server`. Vidocq/vauban#177 named Vauban's `vauban:`
  resource URLs instead; that change removes Vaadin's `PinnedNpmVersions` error but the copy works without it.
- **Fix**: the copy keeps the source jar's folder entries, empty ones included, and writes an entry for every other
  folder before its first file, as the jar tool does, the folders of generated packages included.
  `JpmsPatches.enrich`, used at packaging, gives a generated package its folder entry too. The inputs digest gains a
  `layout folders` line, so a copy written before is rewritten once; on Windows, that one rewrite fails while a
  running `vidocq:dev` holds the copy open, and passes once it stops. Tests `theCopyHasAnEntryForEveryFolder`,
  `enrichAddsPatchClassesWithoutTouchingExistingEntries`.

## BUG-20261021-01 — the MCP check reports synthetic methods as methods with unnamed parameters

- **Date**: 2026-10-21
- **Status**: FIXED on `fix/mcp-checks-skip-synthetic` (found by the SonarQube rollout, Vidocq/vidocq#284)
- **Symptom**: under the `quality` profile, whose JaCoCo agent the SonarQube analysis build runs,
  `McpChecksTest.aParameterWithoutAnyNameIsReportedWithItsMethod` fails: `expected: <[Unnamed#echo, Unnamed#greet]>
  but was: <[Unnamed#$jacocoInit, Unnamed#echo, Unnamed#greet]>`.
- **Minimal repro**: `McpChecks.unnamedParameters` on the declared methods of a tool class whose body holds a
  lambda: the lambda becomes a synthetic method whose parameter has no name, reported as `WithLambda#lambda$shout$0`.
- **Cause**: `unnamedParameters` inspected every method it was given, synthetic ones included. Lambda bodies,
  bridges and JaCoCo's `$jacocoInit` (a condy bootstrap taking a `Lookup`, a `String` and a `Class`) are synthetic
  and their parameters have no name. In production the methods come from langchain4j-cdi's registries, so only
  the tests, which pass every declared method of a fixture, fed it synthetic methods.
- **Fix**: synthetic methods are skipped; test `methodsGeneratedByTheCompilerOrAnAgentAreNeverMcpMethods`.

## BUG-20261019-01 — IntelliJ's Rebuild Project fails on the H2 module and leaves the other modules empty

- **Date**: 2026-10-19
- **Status**: FIXED on `fix/240-h2-module-info-outside-sources` (vidocq#240), to be confirmed in IntelliJ
- **Symptom**: *Build → Rebuild Project* in IntelliJ 2026.2 fails on `vidocq-runtime-h2-module-repackaged` with
  59 "package is empty or does not exist: org.h2…" errors; the rebuild having cleaned every output first, the
  other modules are left without classes.
- **Minimal repro**: a Rebuild Project of the reactor in IntelliJ.
- **Cause**: the module's `module-info.java` exports H2's packages, whose classes only the Maven build brings
  (`maven-dependency-plugin` unpacks them at `generate-resources`). IntelliJ compiles the source root alone.
- **Fix**: `module-info.java` moves to `src/main/module-info`, which is not a source root; the `default-compile`
  execution names it in `compileSourceRoots`. IntelliJ has nothing to compile in the module; Maven builds the
  same jar (`module-info.class` byte-identical, same entries).

## BUG-20261018-01 — the `@WebListener` of a web fragment an absolute ordering excludes is registered

- **Date**: 2026-10-18
- **Status**: FIXED on `fix/228-excluded-fragment-listener` (vidocq#228)
- **Symptom**: `servlet.tck.pluggability.aordering4.Aordering4Tests.absoluteOrderingTest` fails on the assembled
  runtime: the WAR's `<absolute-ordering>` excludes `fragment-7.jar`, Foy merges fragments 1 to 5 only, yet
  `RequestListener7`, a `@WebListener` packaged in the excluded jar, is registered.
- **Minimal repro**: `ComponentSourcesTest` (Servlet extension); `-Ptck -pl
  vidocq-runtime-integration-tests/vidocq-runtime-tck-foy-servlet test -Dtest=Aordering4Tests` fails without the fix.
- **Cause**: Foy attributes a component to the code source its class was defined from. The TCK harness loads the
  archive's classes parent-first from the TCK jar, so `RequestListener7`'s code source names the TCK jar, never
  `fragment-7.jar`, and the exclusion cannot match it.
- **Fix**: the Servlet extension hands Foy a `componentSource` (foy, BUG-20261012-01): a class is attributed to the
  root of its class file as the application's loader resolves it (`ComponentSources.classFile`). The harness resolves
  resources from the WAR's own jars first, so the listener is attributed to `fragment-7.jar` and dropped with it. In
  a deployed application the class file and the code source agree, and nothing changes.

## BUG-20261014-02 — an application-layer module requiring a JDK module missing from the boot layer fails

- **Date**: 2026-10-14
- **Status**: FIXED on `fix/app-layer-system-modules` (vidocq#254)
- **Symptom**: `FindException: Module java.sql not found, required by flow.data` at launch, under
  `vidocq.app.layer=all` (and for the application's own module under the layer mode), once
  `vauban:modularize` made Vaadin's `flow-data` an explicit module that `requires java.sql`.
- **Minimal repro**: `ChildJvmTest.layer_mode_adds_the_jdk_modules_the_application_requires`,
  `VidocqPackageMojoTest.layerLaunchersAddTheJdkModulesTheAppModulesRequire`; IT `vidocq-runtime-it-app-layer`
  (fixture module `acme.sql`).
- **Cause**: the application layer is a child of the boot layer, and only the boot layer can define a JDK module.
  The launches resolved the boot layer from the runtime's modules only (`--add-modules ALL-MODULE-PATH`), so a JDK
  module that only an application module requires was never there.
- **Fix**: `ApplicationLaunch.addModules` reads the descriptors of the application path and adds the JDK modules
  they require (`requires static` aside); `vidocq:run`, `vidocq:dev` (`ChildJvm`) and the package scripts use it.
  The runtime names `--add-modules <module>` when a hand-written launch misses one.

---

## BUG-20261014-01 — `vidocq:package` gives a brick-selected automatic jar a synthesized descriptor again

- **Date**: 2026-10-14
- **Status**: FIXED on `fix/package-keeps-brick-automatic` (vidocq#251)
- **Symptom**: `vidocq:generate` keeps a brick-selected jar without a descriptor automatic, as Vaadin's
  `flow-server` needs, but `vidocq:package` rebuilt its enriched copy on the `vauban:modularize` jar. That
  descriptor missed `uses FeatureFlagProvider` (vauban#158), so the packaged application would fail at boot
  with `does not declare uses`, as the dev run did before vidocq#246's fix.
- **Minimal repro**: `DependencyBricksPackagingTest.packageKeepsABrickSelectedAutomaticCopyAutomatic`.
- **Cause**: `DependencyBricks.Packaging.rebase` served both `vidocq:package` and `vidocq:jlink` and always
  rebased a brick copy on the modularized jar. Only jlink needs an explicit module.
- **Fix**: `vidocq:generate` stamps a copy it keeps automatic with `Vidocq-Automatic: brick-selected` (also
  when the brick produced nothing and only Vauban generated providers). `Packaging` knows whether it serves
  jlink; for `vidocq:package`, a stamped copy is kept as `vidocq:generate` wrote it. `vidocq:jlink` still rebases it; its descriptor
  is right once vauban#158 (Vidocq/vauban#159) is in the Vauban build.

---

## BUG-20261012-01 — an IntelliJ build drops the annotation processor output, and Maven does not restore it

- **Date**: 2026-10-12
- **Status**: FIXED (vidocq#238, vidocq#239; vidocq#237 closed)
- **Symptom**: `vidocq-runtime-foy-servlet-example` started from the IntelliJ Dev run configuration
  (`vidocq:dev`) mounts no servlet; the Servlet panel of the dev console is empty. `target/classes`
  holds the hand-written classes only: no `$$FoyComponent`, `_VaubanComponents` or `META-INF/services`.
- **Minimal repro**: `mvn process-classes` (5 `$$FoyComponent`), then rebuild `target/classes` without
  processors (`javac -proc:none`, as IntelliJ's build does), then `mvn process-classes` again: "Nothing to
  compile - all classes are up to date", still 0 `$$FoyComponent`.
- **Cause**: IntelliJ's Maven import ignores an `annotationProcessorPaths` entry of `<type>pom</type>`, the
  shape of every `-codegen` bundle, so its build runs no processor. `maven-compiler-plugin` then sees
  up-to-date classes and never reruns them. `vidocq:dev` starts from `target/classes` as it is, and the
  IntelliJ Dev configuration ran `vidocq:dev` without `process-classes`, unlike the CLI.
- **Fix**: the Dev configuration of `vidocq:idea` runs `process-classes vidocq:dev`; `vidocq:dev` and
  `vidocq:run` compare the compiler's `createdFiles.lst` with `target/classes`, and on a gap reset the
  compiler's incremental state and recompile once (vidocq#238). The codegen bundles are published as
  jars, so IntelliJ's import builds a real annotation-processor profile and its own build runs the
  processors (vidocq#239).

## VIDOCQ-PEANO-003 — Maven processor paths do not discover Peano's module-only processor

- **Date**: 2026-10-11
- **Status**: FIXED with the maintainer-approved Maven-only APT exception (2026-10-11)
- **Symptom**: a real Maven consumer compiles its annotated model successfully but
  produces no Peano provider or accessor fragment through the codegen bundle.
- **Minimal repro**: append the Peano codegen POM bundle to annotationProcessorPaths
  with maven-compiler-plugin 3.15.0 and Maven 3.9.16; compile @ELAccessible Model.
- **Cause**: Maven emits -processorpath; Peano registers its processor only through
  module-info.java, which is ignored by that processor classloader. The same input works
  with javac --processor-module-path, including closed-module deny-mode evaluation.
- **Fix**: Peano adds the Maven processor service resource alongside its unchanged
  named-module service declaration. Runtime applications remain module-path only.
- **Evidence**: isolated real Maven consumer in /private/tmp/vidocq-peano-apt-consumer;
  debug processor path initially contained the processor JAR but produced no fragment.
  After the fix, a real Maven build generates/registers one provider; the closed model
  runs in allow/deny named JVMs. Related Peano bug: PROC-001.

## VIDOCQ-PEANO-002 — generated accessor fragments were not registered in applications

- **Date**: 2026-10-11
- **Status**: FIXED before publication (branch feat/peano-el-extension)
- **Symptom**: an application compiled its annotated model's accessor table but a named
  JVM in peano.reflection=deny could not discover it and denied the property read.
- **Minimal repro**: compile a closed module containing @ELAccessible Model(String name)
  with Peano's APT; evaluate model.name before consuming its generated provider fragment.
- **Cause**: the processor emits META-INF/peano/accessor-providers fragments for the
  application's build tool; Vidocq's generate goal did not consume them.
- **Fix**: process-classes merges the fragments into compiled module-info through the
  Class-File API before dependency-scan early returns. Existing module access and service
  directives remain intact; no source change or package opening is needed.
- **Evidence**: real javac APT consumer denies before registration, then succeeds in
  independent named JVMs in allow/deny modes. Metadata tests check preservation,
  idempotence, provider presence and malformed-fragment failures.

## VIDOCQ-PEANO-001 — prototype extension metadata followed the application version

- **Date**: 2026-10-11
- **Status**: FIXED before publication (branch feat/peano-el-extension)
- **Symptom**: an application versioned 1.2.3 inherited Peano extension/codegen coordinates
  at 1.2.3; the initial codegen management also omitted its POM type. CLI module mapping
  was incomplete while wiring the new catalogue entry.
- **Minimal repro**: inherit the prototype runtime parent in a 1.2.3 application;
  inspect help:effective-pom for both Peano wrapper dependencies. Existing CLI catalogue
  consistency tests also detect the missing module name.
- **Cause**: the new management entries used project.version instead of the runtime's
  literal version, and omitted existing POM-bundle/catalogue conventions.
- **Fix**: pin wrapper versions to 0.4.0-SNAPSHOT, manage the bundle as type pom and
  provide catalogue coordinates, codegen mapping and minimal application directives.
- **Evidence**: effective-POM probe red/green; all 275 CLI tests pass, including the
  Peano codegen/directive regression and catalogue/module-info consistency checks.

## BUG-20261011-04 — a servlet application under a context path other than `/` answers 404

- **Date**: 2026-10-11
- **Status**: FIXED (branch fix/servlet-context-path-mount)
- **Affected module**: vidocq-runtime-foy-servlet-extension (`FoyExtension`, `FoyMountHandlerProvider`)
- **Symptom**: with `vidocq.servlet.context-path=/shop`, or a declarative `type=servlet` mount on `/web`, every
  servlet answers 404; found by the Servlet TCK on the assembled runtime (vidocq#217), whose WARs live under
  `/<war name>`.
- **Minimal repro**: `FoyServletContextPathTest` in `vidocq-runtime-it-foy-servlet`.
- **Cause**: the extension mounted Foy with the prefix stripped, but Foy dispatches on the full request path, its
  context path included; foy-tck's host passes the request unchanged. The IT only used the root context. #221 had
  even refused `strip-prefix=false`, the one setting that worked.
- **Fix**: `MountHandlerProvider.stripPrefix()` gives a provider's default for `strip-prefix`; the servlet provider
  answers `false` and refuses `strip-prefix=true`; the auto-mount keeps the prefix.

## BUG-20261011-03 — a mount on `/` hid every mount registered after it, and a duplicate prefix was silent

- **Date**: 2026-10-11
- **Status**: FIXED (branch feat/servlet-extension)
- **Affected module**: vidocq-runtime-chappe-webserver-extension (`ChappeMountPoint`)
- **Symptom**: Chappe's router tries mounts in registration order and takes the first whose prefix matches. A
  mount on `/` registered before `/api` answered `/api/...` too; two mounts on one prefix left the second one
  unreachable, without any message.
- **Minimal repro**: `ChappeServerEndToEndTest#longerPrefixWinsWhateverTheRegistrationOrder` — mount `""` then
  `/api` on the default listener, `GET /api/items` reached the root mount.
- **Cause**: `ChappeMountPoint.mount` forwarded each mount to the router at once, in call order.
- **Correction**: `ChappeMountPoint` keeps the mounts of each listener and hands them to the router longest prefix
  first when the server starts; a second mount on the same prefix (`""` and `/` are the same) fails with a message
  naming both extensions and the keys that move them.

## BUG-20261011-02 — `@ApplicationPath` is ignored when the `Application` subclass carries no bean-defining annotation

- **Date**: 2026-10-11
- **Status**: FIXED (vidocq#219, with cassini branch fix/application-path-index)
- **Affected module**: vidocq-runtime-cassini-rest-extension (`CassiniExtension.resolveApplicationPath`)
- **Symptom**: `@ApplicationPath("/api") public class Api extends Application {}` mounts the resources on `/`, not
  `/api`. Next to a servlet application on `/`, the boot fails on the duplicate prefix (BUG-20261011-03).
- **Minimal repro**: `vidocq-runtime-it-foy-servlet` without `@ApplicationScoped` on `ItemsApplication`.
- **Cause**: the extension looked for the `Application` among the beans only. Under annotated discovery a class
  without a bean-defining annotation is never discovered; `CassiniScopeExtension` cannot help, since a build-compatible
  extension only enhances discovered classes.
- **Fix**: `cassini-processor` generates an `ApplicationDescriptor` (`<Application>$$CassiniApplication`) per
  `@ApplicationPath`; the extension reads them through `ServiceLoader` (`ApplicationPaths`), the beans remaining the
  source for classes compiled without the processor. The IT's `ItemsApplication` has no scope any more.

## BUG-20261011-01 — the REST codegen bundle leaves Cassini's build-compatible extension out

- **Date**: 2026-10-11
- **Status**: FIXED (bundle carries `cassini-cdi` once Vidocq/cassini#59 is merged)
- **Affected module**: vidocq-runtime-cassini-rest-extension-codegen; cassini-cdi (`CassiniScopeExtension`)
- **Symptom**: compiling a REST application warns "[Vauban] 1 Build Compatible Extension(s) present on the
  compile/module path are NOT on the annotation-processor path and will not run:
  [io.vidocq.cassini.cdi.CassiniScopeExtension]". `@Path` classes without a scope do not get their default
  `@RequestScoped`: under annotated discovery such a resource is no bean, and the REST extension does not serve
  it. Workaround: give resources a scope.
- **Minimal repro**: `vidocq-runtime-it-foy-servlet` without `@RequestScoped` on `ItemsResource`: `/api/items`
  answers an empty 404.
- **Cause**: the bundle only carries `cassini-processor`; the runtime does not run extensions over an application
  the processor already handled (same pattern as BUG-20261008-01).
- **First attempt, reverted**: adding `cassini-cdi` to the bundle alone made `CassiniScopeExtension` give
  `@RequestScoped` to every `@Path` type, the Rest Client interfaces included:
  `vidocq-runtime-cyrano-rest-client-example` no longer compiled (`GreetingClient_ClientProxy`,
  `GreetingClient_Factory`).
- **Fix**: Vidocq/cassini#59 makes the extension skip interfaces; the bundle now carries `cassini-cdi`. The IT's
  `ItemsResource` has no scope any more.

## BUG-20261008-01 — `@Inject @RestClient` is unsatisfied, and a Rest Client application cannot be linked

- **Date**: 2026-10-08
- **Status**: FIXED (branch pr/ybl/rest-client-module-path, with cyrano and vauban branches of the same name)
- **Affected module**: vidocq-runtime-cyrano-rest-client-extension (no codegen bundle), vidocq-runtime-cli
- **Symptom**: an application depending on `vidocq-runtime-cyrano-rest-client-extension` fails at boot with
  `DeploymentException: Unsatisfied dependency: field RelayResource.greetings of type GreetingClient with qualifiers
  [@RestClient, @Any]`. At compile time the Vauban processor only warns that Cyrano's Build Compatible Extensions are
  not on the processor path and "will not run", and that the field is unsatisfied; `vidocq:checkpom` reports "all
  aligned". Adding `cyrano-cdi-vauban` to the processor path by hand turns the warning into a compilation error. The
  same application cannot be linked: `vidocq:jlink` stops on `org.reactivestreams`, an automatic module cyrano-core
  brought along.
- **Minimal repro**: `vidocq-runtime-examples/vidocq-runtime-cyrano-rest-client-example` (a resource calling another
  through `@Inject @RestClient`), on a plain module path or from its jlink image.
- **Cause hypothesis**: four causes in a row. (1) No codegen bundle put Cyrano's extension on the processor path, so
  checkpom had nothing to ask for; the runtime does not run extensions over an application the processor already
  handled. (2) Once on the processor path, the extension skipped interfaces it could not load — every interface the
  application is compiling (cyrano BUG-20261008-06). (3) Declared from the language model, the bean type was dropped
  from the build-time metadata (vauban BUG-20261008-01). (4) cyrano-core passed reactive-streams on to every client
  (cyrano BUG-20261008-07).
- **Correction**: new `vidocq-runtime-cyrano-rest-client-extension-codegen` bundle (→ `cyrano-cdi-vauban` for the
  extension, `cyrano-processor` for compile-time proxies), known to the CLI (`KnownExtensions.codegenBundle
  ("cyrano-rest-client")`) and therefore required by `checkpom` (seen failing the example's build without it, with the
  entry to add); added to the IT and the two TCK runners that declare the extension. With the cyrano and vauban fixes,
  `vidocq-runtime-it-cyrano-jpms` (`RestClientInjectionTest`) injects the client and calls through it on the module
  path — it failed to compile against the published cyrano; the new `vidocq-runtime-cyrano-rest-client-example` answers
  `/api/relay` through `@Inject @RestClient` on a plain module path and from its `vidocq:jlink` image, which no longer
  holds `org.reactivestreams`. The example's module opens nothing.

## BUG-20261006-02 — an application with its own version resolves every Vidocq artifact at that version

- **Date** : 2026-10-06
- **Statut** : FIXED (`fix/parent-literal-versions` for the parent, `feat/cli-update` for the CLI)
- **Module touché** : vidocq-runtime-parent (root pom), vidocq-runtime-cli
- **Symptôme** : an application that declares `<version>1.0.0</version>` under
  `vidocq-runtime-parent` cannot build: Maven looks for `vidocq-runtime-maven-plugin:1.0.0`, and
  for `vidocq-runtime-cassini-rest-extension:1.0.0` when the dependency carries no version.
  `vidocq extension add` wrote dependencies without a version and the codegen path with
  `${project.version}`, so it hit the same wall; `extension remove` left the codegen path behind.
- **Reproduction minimale** : `vidocq create --name app`, add `<version>1.0.0</version>`, then
  `mvn help:effective-pom` — every managed `io.vidocq.*` artifact, and the plugin, is at `1.0.0`.
- **Hypothèse de cause** : the parent's `dependencyManagement` and `pluginManagement` declare
  Vidocq artifacts at `${project.version}`, which Maven interpolates in the child: the deployed
  parent pom still carries the expression, so it becomes the application's own version.
- **Correction** : CLI side, `extension add` pins the parent's version on the dependency and on
  the codegen path, and `extension remove` unwires the codegen path (and the compiler plugin block
  `add` created). Parent side, the 48 dependencyManagement and pluginManagement entries name the
  version literally, which versions:set (run by the release) rewrites; the core codegen processor
  path has no version and takes it from dependencyManagement. An application at 1.0.0 packages.

## BUG-20261006-04 — `vidocq dev` and `vidocq start` ran an empty runtime

- **Date** : 2026-10-06
- **Statut** : FIXED (`fix/cli-dev`)
- **Module touché** : vidocq-runtime-cli, docs
- **Symptôme** : in a project where `mvn vidocq:dev` serves its endpoints, `vidocq dev` logged
  "No Vidocq extensions discovered" and "no application layer", and nothing answered on the port;
  `vidocq start` likewise.
- **Reproduction minimale** : `vidocq create --name todo -x cassini-rest`, add a resource, then
  `vidocq dev` and `curl localhost:8080/<path>`.
- **Hypothèse de cause** : both commands booted `VidocqBootstrap` inside the CLI's own JVM, whose
  module path holds neither the project's classes nor its dependencies and extensions.
- **Correction** : `vidocq dev` runs `mvn process-classes vidocq:dev` and `vidocq start` runs
  `mvn vidocq:run` (`./mvnw` when present), passing `--port` as the canonical listener key,
  `--profile`, `--config` and `--debug` to the goals. The CLI waits for Maven on Ctrl+C. The
  in-JVM watcher, spinner and profile layering (`io.vidocq.runtime.cli.dev`) are gone.

## BUG-20261006-03 — the CLI's HTTP clients never ran a request

- **Date** : 2026-10-06
- **Statut** : FIXED (`feat/cli-update`)
- **Module touché** : vidocq-runtime-cli
- **Symptôme** : every request of `HttpRegistryFetcher` timed out, so `extension list --available`
  always fell back to the built-in catalog — unnoticed while the remote registry does not exist.
- **Hypothèse de cause** : `.executor(Thread.ofVirtual().factory()::newThread)` creates a thread
  per task but never starts it.
- **Correction** : `.executor(task -> Thread.ofVirtual().start(task))`, covered by a test against a
  local `HttpServer` (`HttpDownloadsTest`).

## BUG-20261006-01 — `extension list`, `info` and `doctor` never see the project's extensions

- **Date** : 2026-10-06
- **Statut** : FIXED (`fix/cli-installed-extensions`)
- **Module touché** : vidocq-runtime-cli, docs
- **Symptôme** : in a project scaffolded with `vidocq create --name todo -g com.acme -x cassini-rest`,
  whose `pom.xml` declares `vidocq-runtime-cassini-rest-extension` and whose distribution ships
  Cassini and Chappe, `vidocq extension list` prints `(none found on classpath)`, `vidocq info`
  `(none — add extension JARs to the classpath)` and `vidocq doctor` warns "no extensions on the
  classpath" — the user concludes the scaffold dropped the extension. The doctor hint also
  suggested `vidocq extension add rest`, an id that does not exist.
- **Reproduction minimale** : `vidocq create --name todo -x cassini-rest && cd todo && vidocq extension list`.
- **Hypothèse de cause** : the three commands ran `ServiceLoader.load(VidocqExtension.class)` in the
  CLI's own JVM. The CLI ships no extension and the project's dependencies are not on its module
  path, so the answer was always empty.
- **Correction** : `ProjectExtensions` resolves the project's runtime classpath with
  `maven-dependency-plugin:list` (transitive) and keeps the JARs whose module provides the
  extension SPI (`ModuleFinder`, explicit or automatic module). Direct dependencies are told apart
  from transitive ones (`chappe-webserver` under `cassini-rest`); a Maven failure is reported, not
  hidden as "no extension". The hint now names `cassini-rest`. The answer is cached per project
  (`ExtensionCache`): final for a release-only resolution, re-checked against the local SNAPSHOT
  files otherwise, bypassed by `vidocq extension list --refresh`.

## BUG-20261001-01 — `@Inject @ConfigProperty` fails the compilation of a Vidocq application (ravel#21)

- **Date**: 2026-10-01
- **Status**: FIXED (`fix/ravel-config-codegen-bundle`, with vauban `fix/extension-build-time-signal` and ravel `fix/config-values-checked-at-container-start`)
- **Affected module**: vidocq-runtime-ravel-config-extension (no codegen bundle), vidocq-runtime-cli
- **Symptom**: reported by Sébastien Blanc on 0.3.0 (Vidocq/ravel#21): an application depending on
  `vidocq-runtime-ravel-config-extension` cannot inject `@Inject @ConfigProperty String` — the Vauban
  processor reports "Unsatisfied dependency" and the only way out is `-Avauban.validation=false`, which
  turns off every compile-time check of the module. Resolving
  `vidocq-runtime-ravel-config-extension-codegen` from Central failed: it never existed.
- **Minimal repro**: `vidocq-runtime-integration-tests/vidocq-runtime-it-ravel-config` without
  the bundle on its processor path.
- **Cause hypothesis**: nothing put Ravel's Build Compatible Extension on the processor path, so the
  beans it synthesises for `@ConfigProperty` points did not exist when the processor validated.
- **Correction**: new `vidocq-runtime-ravel-config-extension-codegen` bundle (→ `ravel-cdi-vauban`),
  known to the CLI (`KnownExtensions.codegenBundle("ravel-config")`) and therefore required by
  `checkpom`; added to the two examples and five TCK runners that declare the extension. The IT
  compiles with validation on and boots, injecting both the value and `Config`.

## BUG-20260809-01 — the documented `vidocq.http.port` was read by nobody

- **Date**: 2026-08-09
- **Status**: FIXED (2026-08-09, `pr/ybl/vidocq-http-port-alias`)
- **Affected module**: vidocq-runtime-chappe-webserver-extension, vidocq-runtime-cli,
  vidocq-runtime-core, docs
- **Symptom**: an application setting `vidocq.http.port` silently stayed on 8080. The key is
  published everywhere — reference documentation, getting-started, the scaffolded
  `vidocq.properties`, the blog tutorial — but the only key the extension read was
  `vidocq.chappe.listener.<name>.port`. Invisible whenever the configured value happened to *be*
  the default, which in a quickstart setting it to 8080 it always is.
- **Minimal repro**:
  ```properties
  # src/main/resources/vidocq.properties, nothing else set
  vidocq.http.port=9099
  ```
  ```
  $ ./bin/app.sh && curl http://127.0.0.1:9099/   # connection refused; the app is on 8080
  ```
- **Cause hypothesis**: the key was documented before the multi-listener configuration landed,
  and nothing tied the reference table to the keys the code actually reads.
- **Investigations**:
  - 2026-08-09 : reported by Sébastien Blanc (Vidocq/chappe#7 — filed on the chappe repo, but
    Chappe knows nothing about `vidocq.*` keys: the defect is in the runtime extension). Found
    while building Rossignol, where the inert setting was carried for weeks.
  - Scope was wider than reported. (a) `vidocq start --port` / `vidocq dev --port` published
    `vidocq.http.port` as a system property, so **the CLI's own `--port` option was inert too**.
    (b) The scaffolder writes the key into every generated project. (c) The reference table
    documented three further families read by nobody: `vidocq.https.port` + `vidocq.tls.*`
    (TLS is not implemented) and `vidocq.datasource.*` (the real prefix is `vidocq.pool.*`).
    (d) `vidocq.http.mount.*` *was* live, so the `vidocq.http.` namespace was half-claimed —
    which is precisely why the silence read as normal.
  - Fixed by making `vidocq.http.{host,port}` a declared alias of the `default` listener
    (the explicit listener key still wins), by publishing the canonical listener key from the CLI
    and only when `--port` was actually typed (otherwise the CLI default would outrank the
    project's own configuration), and by correcting the documentation.
  - Generalised, as the reporter suggested: `VidocqExtension.configKeys()` lets an extension
    declare the keys it consumes, and `ConfigKeyAudit` warns at startup about any configured
    `vidocq.*` key that no extension reads. Opt-in per namespace, so an extension that declares
    nothing never produces a false warning.

## VID-001 — Cervantes JWT extension wrapper BCE not openable on the module path

- **Opening date**: 2026-06-02
- **Status**: ✅ FIXED 2026-06-02

### Symptom

Strict module-path boot (Arago Docker) fails:

```
BCE processing failed for io.vidocq.runtime.ext.cervantes.CervantesJwtBuildCompatibleExtension:
class io.vidocq.vauban.core.extensions.BceProcessor (in module io.vidocq.vauban.core) cannot access
... because module io.vidocq.runtime.ext.cervantes.jwt does not export io.vidocq.runtime.ext.cervantes
to module io.vidocq.vauban.core
```

Does not reproduce on the class-path (the cervantes acceptance/TCK run there).

### Cause

The wrapper module `io.vidocq.runtime.ext.cervantes.jwt` declares its own
`BuildCompatibleExtension` in the internal package `io.vidocq.runtime.ext.cervantes` and registers it
via `provides ... with`. vauban-core's `BceProcessor` instantiates BCEs by direct reflection
(`getDeclaredConstructor().newInstance()`), which needs the hosting package **opened** to vauban-core;
`provides ... with` alone only grants `ServiceLoader` access. The package was neither exported nor opened.

### Fix

`vidocq-runtime-cervantes-jwt-extension/module-info`:
`opens io.vidocq.runtime.ext.cervantes to io.vidocq.vauban.core;` (qualified — mirrors knock/dirac).

Verified: Arago Docker boots; cervantes MP-JWT 2.1 TCK 206/206 PASS.

### Related — cyrano wrapper: ✅ FIXED 2026-06-11

`vidocq-runtime-cyrano-rest-client-extension` had the **same** shape (own BCE in a non-opened
package). Fixed with the same one-line `opens io.vidocq.runtime.extensions.microprofile.cyrano to
io.vidocq.vauban.core` — the compile-graph concern recorded earlier did not materialize:
`io.vidocq.vauban.core` is reachable transitively through `io.vidocq.cyrano.cdi.vauban`, exactly as
in the cervantes wrapper, and the opens compiles as-is.

**Verified end-to-end this time**: new reactor module `vidocq-runtime-it-cyrano-jpms` boots the
Vidocq runtime with the wrapper ON THE MODULE PATH (main module-info → surefire module path, plus a
named-module sentinel against silent class-path degradation). The vehicle was proven by mutation:
without the opens the boot test fails with the exact VID-001 symptom; with it, green. This also
gives the runtime its first module-path IT — every other integration test runs on the class path,
where this whole bug class is invisible.

## BUG-20260612-01 — Invalid 0.2.0-SNAPSHOT pom of vidocq-runtime-cassini-rest-extension on central-snapshots

- **Date**: 2026-06-12
- **Status**: CLOSED 2026-10-07 — obsolete
- **Affected module**: vidocq-runtime-cassini-rest-extension (published snapshot, timestamp 0.2.0-20260608.152249-5)
- **Symptom**: any out-of-reactor consumer resolving the published snapshot gets
  "The POM ... is invalid, transitive dependencies (if any) will not be available:
  'dependencies.dependency.version' for io.vidocq.runtime:vidocq-runtime-chappe-webserver-extension:jar is missing"
  → transitive cassini jars silently dropped → `ClassNotFoundException: io.vidocq.cassini.spi.bean.BeanProvider`
  (grimm-tck: 29 Arquillian deployment failures / 719 skips). The resolver PREFERS the remote
  timestamped snapshot over the locally installed one, so a local `mvn install` does not help.
- **Minimal repro**:
  ```
  rm -rf ~/.m2/repository/io/vidocq/runtime
  cd grimm && ./run-official-tck-mp-openapi-4.1.sh all     # before the grimm-tck workaround
  ```
- **Cause hypothesis**: the snapshot published on 2026-06-08 predates the groupId/dependencyManagement
  fix of the chappe-webserver-extension dependency (the current source pom uses the managed
  io.vidocq.runtime.extensions.essentials groupId and is valid). Republishing a fresh snapshot
  via the vidocq CI publish job should fix all consumers.
- **Investigations**:
  - 2026-06-12 : root-caused while re-validating grimm-tck on 0.2.0 jars (frozen-runner trap, CG-06).
    Contained workaround committed in grimm-tck/pom.xml (lost transitives declared explicitly) —
    remove it once a valid snapshot is republished.
  - 2026-10-07 : replayed with a throw-away consumer and an empty local repository (`-Dmaven.repo.local`) against
    central-snapshots: `vidocq-runtime-cassini-rest-extension` 0.4.0-SNAPSHOT (`0.4.0-20261007.171041-82`) resolves
    with no "POM is invalid" warning, and its four cassini jars come transitively. The grimm-tck workaround (cassini
    jars declared by hand) can go; done in grimm.

## BUG-20260704-01 — Released CLI 0.2.0 reports "Vidocq CLI 0.2.0-SNAPSHOT" for --version

- **Date**: 2026-07-04
- **Status**: FIXED (a9d3a59 on main, 9a13640 on hotfix/0.2.1-cli — ships with CLI 0.2.1)
- **Affected module**: vidocq-runtime-cli (version banner)
- **Symptom**: the jar published on Maven Central as `io.vidocq.runtime:vidocq-runtime-cli:0.2.0`
  prints `Vidocq CLI 0.2.0-SNAPSHOT` when invoked with `--version`.
- **Minimal repro**:
  ```
  # pristine local repo, resolve from Central only
  mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=modules \
      -Dmaven.repo.local=$(mktemp -d)   # pom with a single dep on vidocq-runtime-cli:0.2.0
  java -p modules -m io.vidocq.runtime.cli/io.vidocq.runtime.cli.VidocqCli --version
  # → Vidocq CLI 0.2.0-SNAPSHOT
  ```
- **Cause hypothesis**: the version string is not derived from the pom at build time
  (hardcoded constant, or a resource that is not filtered), so the release build — which
  runs `versions:set 0.2.0` on the release branch before packaging — does not update it.
- **Investigations**:
  - 2026-07-04 : found while validating the Central-based CLI install recipe for the blog
    tutorial (pages PR #2). Cosmetic only — the artifact itself is the correct 0.2.0 build.
    Central is immutable, so 0.2.0 will keep the wrong banner; fix for 0.2.1+.
  - 2026-07-10 : root cause confirmed — hardcoded `VidocqCli.VERSION` constant, also used
    for the `<parent><version>` of every scaffolded pom (the actual issue #3 wall: generated
    projects referenced an unresolvable 0.2.0-SNAPSHOT parent). Fixed by deriving
    cliVersion/runtimeVersion from a Maven-filtered version.properties; scaffold now emits
    the runtime parent version. Ships with CLI 0.2.1 (hotfix/0.2.1-cli).

## BUG-20260710-01 — vidocq:package 0.2.0 NPEs when jvmArgs is not configured

- **Date**: 2026-07-10
- **Status**: FIXED (2026-08-09, `pr/ybl/vidocq-40-jvmargs-npe`)
- **Affected module**: vidocq-runtime-maven-plugin / VidocqPackageMojo
- **Symptom**: `Cannot invoke "String.isBlank()" because "this.jvmArgs" is null` — the
  `package` goal fails on any pom that does not set `<jvmArgs>` explicitly. An empty
  `<jvmArgs></jvmArgs>` element does NOT help (Plexus maps both the absent element and
  `defaultValue = ""` to null).
- **Minimal repro**:
  ```
  vidocq create --name demo -g com.acme   # scaffold without <jvmArgs>
  cd demo && mvn package                   # with the plugin's package goal wired
  ```
- **Cause hypothesis**: `@Parameter(defaultValue = "")` yields null under Maven/Plexus;
  the mojo dereferences `jvmArgs.isBlank()` without a null guard.
- **Investigations**:
  - 2026-07-10 : found while validating the scaffolded-project E2E flow for the
    getting-started rewrite (issue #3). Released 0.2.0 is immutable — the scaffold now
    emits `<jvmArgs>-Dfile.encoding=UTF-8</jvmArgs>` as a workaround. Fix the null guard
    on main for the next plugin release.
  - 2026-08-09 : reported again from the outside by Sébastien Blanc (Vidocq/vidocq#40),
    hit on a from-scratch project built for a separate Vauban reproducer — i.e. on the
    exact path a new user takes. Fixed on main: both launcher generators now read the
    parameter through a null-safe `jvmArgsLine()`, and `VidocqPackageMojoTest` pins the
    absent / blank / configured cases. Swept the rest of the plugin for the same
    `defaultValue = ""` trap: `VidocqDevMojo.extraJvmArgs` and `extraSystemProperties`
    were already null-guarded (`splitArgs`), so `package` was the only faulty site.
    The scaffold keeps emitting `<jvmArgs>` while it targets the released 0.2.x plugin;
    drop that workaround (and its assertion in `ProjectScaffolderExtensionsTest`) in the
    commit that bumps the scaffolded version to 0.3.0.

## BUG-20260710-02 — vidocq:package 0.2.0 launcher uses --module <mainClass> without the module name

- **Date**: 2026-07-10
- **Status**: FIXED (vidocq#198, branch `pr/ybl/packaging-launchers`)
- **Affected module**: vidocq-runtime-maven-plugin / VidocqPackageMojo
- **Symptom**: the generated `bin/<app>.sh` launcher runs
  `java --module-path lib --module <mainClass>` — with the default or a plain class name
  this fails at boot with `FindException: Module <mainClass> not found`. The mojo ignores
  `vidocq.mainModule` (used by vidocq:dev/jlink) and passes `mainClass` verbatim where the
  JVM expects `module/class`.
- **Minimal repro**:
  ```
  mvn package   # with vidocq:package wired and vidocq.mainClass=com.acme.demo.DemoApp
  sh target/demo-0.2.0/bin/demo.sh
  ```
- **Cause hypothesis**: the script template concatenates `--module ${mainClass}`
  instead of `--module ${mainModule}/${mainClass}`.
- **Investigations**:
  - 2026-07-10 : found together with BUG-20260710-01. Workaround baked into the scaffold:
    `<mainClass>${vidocq.mainModule}/${vidocq.mainClass}</mainClass>` on the package
    execution. Align the mojo with vidocq:dev/jlink (separate mainModule parameter) on main.
  - 2026-10-07 : narrowed. The default layout (`vidocq.package.layer=true`) launches the runtime with
    `-Dvidocq.app.path`, or the `@VidocqMain` trampoline as `<mainModule>/<mainClass>`, and is correct. The legacy
    layout (`vidocq.package.layer=false`) still wrote `--module <mainClass>`, which failed even with the default
    main class (`--module io.vidocq.runtime.core.Vidocq`).
- **Fix**: `VidocqPackageMojo#legacyModuleRef` gives the legacy launchers `io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq`
  by default, `<mainModule>/<mainClass>` for an application main class, keeps a `module/class` value as is, and fails
  the build, naming `vidocq.mainModule`, for a plain class without it. `VidocqPackageMojoTest` (three tests, failing
  first).

## BUG-20260711-01 — extension add does not wire the codegen bundle, next build fails checkpom

- **Date**: 2026-07-11
- **Status**: FIXED (fix/extension-add-codegen — ships with CLI 0.2.2)
- **Affected module**: vidocq-runtime-cli / CommandRunner + PomEditor
- **Symptom**: `vidocq extension add cassini-rest` adds the dependency but not the
  `vidocq-runtime-cassini-rest-extension-codegen` annotationProcessorPaths entry; since the
  0.2.1 scaffold wires the vidocq plugin (checkpom active), the next `vidocq build` /
  `mvn package` fails: "codegen bundle ... is missing from annotationProcessorPaths".
  Breaks the published blog tutorial flow (create → extension add cassini-rest → build).
- **Minimal repro**:
  ```
  vidocq create --name hello --group-id com.example
  cd hello && vidocq extension add cassini-rest && vidocq build   # checkpom FAIL
  ```
- **Cause hypothesis**: `extension add` only edits `<dependencies>`; the APT wiring rule
  introduced with the 0.2.1 scaffold was not mirrored there.
- **Investigations**:
  - 2026-07-11 : found by replaying the published blog tutorial block-by-block in a clean
    container. Fixed: PomEditor.addAnnotationProcessorPath (append into existing APT block,
    create the compiler plugin block or the whole build section when absent, idempotent)
    wired into `extension add` via KnownExtensions.codegenBundle. Verified E2E: patched
    add + `vidocq build` passes on a 0.2.1-scaffolded project.

## BUG-20260711-02 — vidocq:docker wraps the host-platform jlink image (broken container on macOS)

- **Date**: 2026-07-11
- **Status**: FIXED (vidocq#199, branch `pr/ybl/packaging-launchers`): detected and reported; cross-linking is not supported
- **Affected module**: vidocq-runtime-maven-plugin / VidocqDockerMojo
- **Symptom**: on macOS, `vidocq build jlink` produces a Mach-O arm64 runtime; `vidocq build
  docker` then generates a Dockerfile that COPYs that dist into a Linux base image — the
  resulting container fails at start with `exec /opt/app/bin/todo: no such file or directory`.
  Also note the goal only GENERATES the Dockerfile and prints the `docker build` command
  ("Build with: …") despite logging "Building Docker image…".
- **Minimal repro**:
  ```
  # on macOS
  vidocq build jlink && vidocq build docker
  docker build -t app -f target/Dockerfile target && docker run --rm app   # exec format error
  ```
- **Cause hypothesis**: jlink links against the host's jmods; the mojo neither cross-links
  with Linux jmods nor warns when host OS ≠ linux.
- **Investigations**:
  - 2026-07-11 : found while replaying the published tutorials end to end. Options: warn on
    non-linux hosts, document the CI-only expectation, or support --jmods cross-linking.
  - 2026-10-07 : the misleading "Building Docker image…" log was already gone (the goal says "Dockerfile generated").
    The platform problem remained: on macOS the cassini example's `target/dist/bin/java` is `Mach-O 64-bit
    executable arm64`, and the goal wrapped it without a word.
- **Fix**: `VidocqDockerMojo#nonLinuxPlatform` reads the header of the image's `bin/java` (`java.exe`): ELF passes;
  Mach-O or PE gets a WARNING that names the platform and says the container will not start, and the Dockerfile is
  still generated (failing would break `mvn install` on macOS for every project with the `dist` profile); with
  `vidocq.docker.build=true` the goal fails before running `docker build`. Documented in the plugin page.
  `VidocqDockerMojoTest` (failing first). Linking against Linux jmods from another OS stays out of scope.

## BUG-20260815-01 — jlink launcher requires an explicit `exports … to io.vidocq.runtime.core`

- **Date**: 2026-08-15
- **Status**: CLOSED 2026-10-07 — no longer reproduces on the default launch; residual noted below
- **Affected module**: vidocq-runtime-core / `Vidocq.instantiateInLayer` + `VidocqAppLayer.exportToRuntime`
- **Symptom**: an application packaged with `vidocq:jlink` and started through the generated
  launcher fails at boot unless its main package is exported to the runtime by hand. The same
  application boots under `vidocq:dev` and under surefire without that edge:
  ```
  Exception in thread "main" java.lang.IllegalStateException: Application …ExampleApp failed
  Caused by: java.lang.IllegalAccessException: class io.vidocq.runtime.core.Vidocq
    cannot access class io.vidocq.experiments.lc4j.ExampleApp … because module
    io.vidocq.experiments.lc4j does not export io.vidocq.experiments.lc4j to module io.vidocq.runtime.core
  ```
- **Minimal repro** (`langchain4jcdi-experiment/langchain4jcdi-example`, commit b0470bc):
  ```
  # remove `exports io.vidocq.experiments.lc4j to io.vidocq.runtime.core;` from module-info.java
  ./mvnw -o -pl langchain4jcdi-example clean package
  target/dist/…/lc4j-app        # boot failure, trace above
  ```
- **Cause hypothesis**: the jlink launcher boots the application module on the **boot layer**.
  `Vidocq.instantiateInLayer` (`Vidocq.java:149-165`) grants itself reflective access through
  `VidocqAppLayer.exportToRuntime`, but that method is guarded by
  `if (layer != null && layerClass.getModule().getLayer() == layer.layer())`
  (`VidocqAppLayer.java:210-216`) — true only when the class was re-layered into the Vauban
  application layer. In a jlink image it never is, so the call is a no-op and the export is missing.
- **Investigations**:
  - 2026-08-15: found while validating the LangChain4j-CDI integration end to end (task B4 report,
    section "A third edge the jlink launcher forced"). Worked around application-side with a
    qualified `exports … to io.vidocq.runtime.core;` — an export suffices, class and constructor
    are public. The reference `vidocq-runtime-cassini-rest-example` declares no such edge and is
    likely affected too. Proper fix: make the boot-layer case explicit — either grant the access
    without the re-layering guard (`addExports` on the boot-layer module) or have `vidocq:jlink`
    fail loudly with the required directive rather than at run time.
  - 2026-10-07 : replayed on main `b3dfc30a` with `vidocq-runtime-cassini-rest-example`, whose `module-info` exports
    only its `model` package (no `exports … to io.vidocq.runtime.core`): the jlink image (`-m
    io.vidocq.runtime.examples.rest/…RestExampleApp`) logs `Application layer ready … (boot-layer detection from
    io.vidocq.runtime.examples.rest)`, starts in 121 ms and answers `GET /api/todos` with 200. `Vidocq.run()` now
    re-layers the application from the boot layer, also inside a jlink image (vauban BUG-20260912-02), so
    `exportToRuntime`'s guard holds and the export is granted.
  - Residual: when no application module is found (`VidocqAppLayer` returns before `installLayer`, e.g. an empty
    `applicationPaths`), the application stays in the boot layer and the same `IllegalAccessException` comes back,
    with no hint. Not seen on any launch path we ship.
  - 2026-10-07 : the residual now fails with a message that names the class, the package and both ways out (an
    `exports … to io.vidocq.runtime.core;` or a CDI bean): `Vidocq.inaccessibleApplicationClass`, vidocq#201,
    `BootLayerAccessTest`.


## BUG-20261010-01 — A scaffolded application boots in place and loses its generated code in jlink

- **Date**: 2026-10-10
- **Status**: FIXED (branch `fix/scaffold-main-vidocq-run`)
- **Affected module**: vidocq-runtime-cli / `ProjectScaffolder.buildApp`, `CliParser.parseCreate`
- **Symptom**: `vidocq create -x heisenberg-fault-tolerance`, a bean with a `@Retry` method, no
  `exports` (what the scaffold writes): `vidocq start` works, the jlink image fails on first use with
  `DeploymentException: Failed to create client proxy for normal-scoped bean …Flaky` caused by
  `IllegalAccessException: … cannot access class …Flaky_ClientProxy … does not export … to module
  io.vidocq.vauban.core`. Separately, `vidocq create --name ft-030` writes the module
  `io.example.ft.030`, which does not compile.
- **Minimal reproduction**: the application above, `vidocq build jlink`, `target/dist/bin/<app>`.
- **Cause**: the scaffolded `main` was `VidocqBootstrap.create().configure().start()`, not a
  `Vidocq.run` trampoline. The jlink launcher runs `-m <app>/<main>`, the application stays in the
  boot layer, and its `_VaubanComponents` (listed in `META-INF/services`, never in a `provides` the
  scaffold does not write) is invisible to `ServiceLoader`; Vauban then falls back to reflection,
  which needs the package exported. Under `vidocq start` the runtime re-layers the application
  anyway, which is why only the image failed. The examples already use `Vidocq.run`. The name was
  never checked against the Java package syntax.
- **Fix**: the scaffold writes a `@VidocqMain` class whose `main` is `Vidocq.run(args)`;
  `parseCreate` refuses a package (derived or given) that is not a valid Java package name and
  points to `--package`. `ProjectScaffolderAppTest`, `CliParserCreateTest`.
- **Verification**: CLI module 273 tests; the Fault Tolerance application with the new `main` and no
  `exports`/`opens`: `vidocq:run` and the jlink image (`boot-layer detection`) both run the
  `@Retry` bean (3 calls). Found while checking heisenberg BUG-005 with scaffolded applications.

## BUG-20261010-02 — An application with the Rest Client extension and no REST server has no JSON-B provider

- **Date**: 2026-10-10
- **Status**: FIXED (branch `fix/cyrano-extension-declares-apis`)
- **Affected module**: vidocq-runtime-cyrano-rest-client-extension
- **Symptom**: an application with `vidocq-runtime-cyrano-rest-client-extension` and no Cassini, calling a
  JSON endpoint, fails on the first response body: `ExceptionInInitializerError` at
  `io.vidocq.cyrano.internal.JsonbHolder.get`. With Cyrano's Jakarta REST API now `provided`
  (Vidocq/cyrano#34, vidocq-workspace#17), the same application does not even compile: `module not found:
  jakarta.ws.rs`.
- **Minimal reproduction**: an application with the Rest Client extension only, a `@RegisterRestClient`
  interface returning a JSON-B class, built with `RestClientBuilder` against a local stub, `vidocq:run`.
- **Cause**: `cyrano-core` depends on the JSON-B and JSON-P APIs only, as a brick should; no module
  brought an implementation. Every Rest Client test and example also had the Cassini extension, which brings
  Champollion through `cassini-core`.
- **Fix**: the extension declares `champollion-jsonb` (`runtime`, with `champollion-jsonp`) and
  `jakarta.ws.rs-api`: a brick depends on APIs, its Vidocq runtime extension picks the implementations.

## BUG-20261022-01 — `@VidocqDataSources` on a module declaration crashes javac

- **Date**: 2026-10-22
- **Status**: OPEN
- **Affected module**: vidocq-runtime-mansart-pool-datasources-codegen (`VidocqDataSourcesProcessor`)
- **Symptom**: the annotation's `@Target` allows `MODULE`, but a module annotated with it does not compile:
  `Fatal error compiling: java.lang.NullPointerException: Cannot invoke
  "javax.lang.model.element.PackageElement.getQualifiedName()" because the return value of
  "javax.lang.model.util.Elements.getPackageOf(javax.lang.model.element.Element)" is null`.
- **Minimal reproduction**: a module compiled with `vidocq-runtime-mansart-pool-datasources-codegen` on its
  processor path, whose `module-info.java` reads
  ```
  @io.vidocq.runtime.extensions.jakartaee.web.mansart.pool.VidocqDataSources("audit")
  module com.example.app { requires io.vidocq.runtime.extensions.jakartaee.web.mansart.pool; }
  ```
- **Cause**: `VidocqDataSourcesProcessor.java:113` takes the generated holder's package from
  `elements.getPackageOf(anchor)`, which returns `null` when the anchor is a `ModuleElement`.
- **Investigations**:
  - 2026-10-22: found while writing `vidocq-runtime-it-naming` (vidocq#297); the IT annotates a type instead.
    A fix has to pick a package for a module anchor (for instance the module's first package, or refuse
    `MODULE` in `@Target`).

## BUG-20261023-01 — `extension add` declares a second `maven-compiler-plugin`

- **Date**: 2026-10-23
- **Status**: FIXED (branch `fix/cli-apt-wiring`)
- **Affected module**: vidocq-runtime-cli (`PomEditor.addAnnotationProcessorPath`)
- **Symptom**: on a pom whose `<build><plugins>` already holds a `maven-compiler-plugin` without
  `<annotationProcessorPaths>`, `vidocq extension add cassini-rest` appends a second
  `maven-compiler-plugin` block. Maven merges both, so the build works, but warns
  `'build.plugins.plugin.(groupId:artifactId)' must be unique but found duplicate declaration of plugin
  org.apache.maven.plugins:maven-compiler-plugin`.
- **Minimal reproduction**:
  ```
  vidocq create -n app -g com.acme -x chappe-webserver && cd app
  # add a maven-compiler-plugin with only <compilerArgs> under <build><plugins>
  vidocq extension add cassini-rest && mvn -o validate
  ```
- **Cause**: the method only looks for an existing `<annotationProcessorPaths>`; with none, it writes a new
  plugin block before `</plugins>` instead of filling the compiler plugin already declared.
- **Investigations**:
  - 2026-10-23: reproduced with Maven 3.9 on a scaffolded project (effective pom keeps `-Xlint:all`, the
    core codegen and the Cassini bundle; only the warning is wrong).
- **Fix**: `addAnnotationProcessorPath` fills the project build's compiler plugin: its `annotationProcessorPaths`,
  else its `<configuration>`, else a new one; `remove` takes back what `add` wrapped around the path.

## BUG-20261023-02 — A processor path block without `combine.children="append"` loses the Vauban indexer silently

- **Date**: 2026-10-23
- **Status**: FIXED (branch `fix/cli-apt-wiring`)
- **Affected module**: vidocq-runtime-cli (`PomEditor.addAnnotationProcessorPath`),
  vidocq-runtime-maven-plugin (`VidocqCheckPomMojo`)
- **Symptom**: when the application's `<annotationProcessorPaths>` has no `combine.children="append"`
  (for instance a hand-added `mapstruct-processor`), the parent's `vidocq-runtime-core-codegen` entry is
  replaced, so the Vauban indexer does not run. `vidocq extension add` writes its bundle into that block and
  reports "Wired", and `vidocq:checkpom` reports "all aligned": nothing tells the user.
- **Minimal reproduction**:
  ```
  vidocq create -n app -g com.acme -x chappe-webserver && cd app
  # add a maven-compiler-plugin whose <annotationProcessorPaths> (no combine.children) lists mapstruct-processor
  vidocq extension add cassini-rest && mvn -o validate
  # effective pom: [mapstruct-processor, vidocq-runtime-cassini-rest-extension-codegen], no core-codegen
  ```
- **Cause**: the CLI does not check the attribute of the block it edits, and `checkpom` treats
  `vidocq-runtime-core-codegen` as optional: it never reports it missing.
- **Investigations**:
  - 2026-10-23: reproduced with Maven 3.9 (`help:effective-pom`, `validate`).
  - 2026-10-23: with a bean in the application, the build without `append` has no `_VaubanComponents`, no
    `*_Factory`, no `META-INF/vauban-beans.list`.
- **Fix**: `add` writes `combine.children="append"` on the block it fills (and says so), and warns when it adds
  an extension without codegen to a pom whose block still replaces the parent's; `checkpom` fails a module that
  declares an extension without `vidocq-runtime-core-codegen`, unless it runs no processor (`<proc>none</proc>`).

## BUG-20261023-03 — `extension add` may wire the codegen bundle into a profile or `pluginManagement`

- **Date**: 2026-10-23
- **Status**: FIXED (branch `fix/cli-apt-wiring`)
- **Affected module**: vidocq-runtime-cli (`PomEditor.addAnnotationProcessorPath`,
  `PomEditor.removeAnnotationProcessorPath`)
- **Symptom**: the first `<annotationProcessorPaths` of the file wins, wherever it is: when it sits in a
  `<profile>` (or in `<pluginManagement>`), the bundle lands there, and the default build does not run it
  while the profile is inactive. `remove` looks at the same first block only.
- **Minimal reproduction**: `PomEditor.addAnnotationProcessorPath(pom, cassiniCodegen, "0.4.0")` on a pom
  whose `<profiles>` section, before `<build>`, declares a compiler plugin with `annotationProcessorPaths`:
  the path is appended to the profile's block.
- **Cause**: `pom.indexOf("<annotationProcessorPaths")` scans the whole document.
- **Investigations**:
  - 2026-10-23: reproduced on `PomEditor` directly; not run through Maven.
- **Fix**: the editor skips comments, `<profiles>`, `<pluginManagement>`, `<reporting>` and plugin `<executions>`
  when it looks for the compiler plugin, its paths, or `</plugins>` and `</build>`.

## BUG-20261023-04 — `vidocq help create` shows extension ids that do not exist

- **Date**: 2026-10-23
- **Status**: FIXED (branch `fix/cli-apt-wiring`)
- **Affected module**: vidocq-runtime-cli (help text of `create`)
- **Symptom**: the example `vidocq create --name my-api -g com.acme -x rest -x health` uses `rest` and
  `health`, which are not catalog ids: they resolve by convention to
  `io.vidocq.runtime:vidocq-runtime-rest-extension`, an artifact that does not exist.
- **Minimal reproduction**: `vidocq help create`.
- **Cause**: the example predates the catalog's `<codename>-<spec>` ids (`cassini-rest`, `knock-health`).
- **Investigations**:
  - 2026-10-23: found while reviewing the APT wiring of `extension add`.
- **Fix**: the example reads `-x cassini-rest -x knock-health`; a test checks its ids against the catalog.

## BUG-20261023-05 — `extension remove` leaves the empty `<build><plugins>` that `extension add` created

- **Date**: 2026-10-23
- **Status**: FIXED (branch `fix/cli-apt-wiring-sonar`)
- **Affected module**: vidocq-runtime-cli (`PomEditor.removeAnnotationProcessorPath`)
- **Symptom**: on a pom without `<build>`, `vidocq extension add cassini-rest` then
  `vidocq extension remove cassini-rest` leaves `<build><plugins></plugins></build>` behind. #307 said
  `remove` undoes exactly what `add` added; that held only for a pom that already had `<plugins>`.
- **Minimal reproduction**: `PomEditor.addAnnotationProcessorPath` on `<project><artifactId>bare</artifactId></project>`,
  then `removeAnnotationProcessorPath` on the result: the pom is not the original one.
- **Cause**: the cleanup after the last path stopped at the compiler plugin; the `plugins` and `build`
  elements `add` creates around it were never considered.
- **Investigations**:
  - 2026-10-23: found by the first test of `CommandRunner.editPom`, written to cover the SonarQube
    follow-up of #307.
- **Fix**: once the bare compiler plugin is gone, the project build's `plugins` and then `build` are
  removed too when empty.

## BUG-20261023-06 — in a jlink image, a Vaadin `AppShellConfigurator` makes every page answer with a reset connection

- **Date**: 2026-10-23
- **Status**: FIXED (2026-10-24, option 1) — `vidocq-runtime-vaadin-extension` ships the module-aware
  `LookupInitializer`; the application adds the dependency and `requires io.vidocq.runtime.extensions.jakartaee.web.vaadin`
  (`vaadin.adoc` `#jlink-app-shell`, `vidocq-runtime-extensions.adoc` `#vaadin`)
- **Affected module**: the jlink image of a Vaadin application (`vidocq:jlink` + `vauban:modularize` with
  `vauban.modularize.forceExplicit=true`); `vidocq:dev`, `vidocq:run` and `vidocq:package` are not affected
- **Symptom**: the image boots (`Application layer ready`, `Started in …`), then the first request to `/` gets no
  answer and the log shows
  `java.util.ServiceConfigurationError: org.tlr.AppShell: module flow.server does not declare 'uses'`, from
  `LookupInitializer$LookupImpl.lookup` ← `DefaultInstantiator.getOrCreate` ← `AppShellRegistry.modifyIndexHtml`
  ← `IndexHtmlRequestHandler`. Identical with or without a `@VidocqMain` trampoline; gone when the
  application has no `AppShellConfigurator` class.
- **Minimal reproduction**: a Vaadin 25.3 application of the `vaadin.adoc` setup, with one class
  `public class AppShell implements AppShellConfigurator { … }`, `vauban:modularize` in `all-automatic` mode, then
  `mvn clean package vidocq:jlink -Dvidocq.jlink.mergeSharedResources=true -Dvauban.modularize.forceExplicit=true`,
  `JDK_JAVA_OPTIONS=-Dvaadin.productionMode=true target/dist/bin/<app>`, `curl localhost:8080/`.
- **Cause**: Vaadin creates the app shell with `Instantiator.getOrCreate(appShellClass)`, and
  `DefaultInstantiator.getOrCreate` first asks `Lookup.lookup(type)`, which runs `ServiceLoader.load(type)` from
  `flow.server` for any type it has no registered service for — here the application's own class. `ServiceLoader`
  checks that the calling module declares `uses` for the type. An automatic `flow.server` (`vidocq:package`) may use
  any service; the descriptor `vauban:modularize` gives it for `jlink` can only list the service types the bytecode
  names (`I18NProvider`, `RoutePathProvider`, …), never an application class, which would need a dependency cycle.
  Same family as the documented Collaboration Engine / LitTemplate limit (`Lookup.lookupAll` on behalf of other
  modules).
- **Investigations**:
  - 2026-10-23: reproduced on a copy of a Vaadin 25.3.3 application, main-less and with a trampoline (identical).
    `Module.addUses` is allowed when the caller is the module itself, so a `ServiceLoader` call made from a module
    that first calls `getModule().addUses(type)` passes. Proof: a `LookupInitializer` subclass in the application
    (Vaadin picks a single non-default `AbstractLookupInitializer` among the `@HandlesTypes` classes, which Foy
    passes) whose `LookupImpl` loads through its own module after `addUses`: jlink image `GET /` 200 with the app
    shell's stylesheet links in the page; `vidocq:package` unchanged (200).
  - 2026-10-24: the same initializer, shipped in `vidocq-runtime-vaadin-extension`. Foy hands it to Vaadin through the
    `META-INF/foy/class-index.list` Foy's processor writes into the extension's jar. Under `vidocq:package` its
    `io.vidocq` module name first kept it, and `flow.server` it requires, in the runtime's layer, away from Vaadin's
    SLF4J (`NoClassDefFoundError: org/slf4j/Logger` at boot): the jar now carries `Vidocq-Application-Layer: true`,
    which `LayerSplit` reads to leave it to the application layer. Same application, its `AppShell` kept, the
    extension as its only addition: jlink image `GET /` 200 with the stylesheet links, `vidocq:package` 200 with
    them; without the extension the image still fails.
- **Fix options** (option 1 shipped):
  1. Ship that `LookupInitializer` in a Vidocq Vaadin integration module the application depends on — Vaadin-specific,
     a new artifact.
  2. `vauban:modularize` rewrites, in the copies it patches, every `ServiceLoader.load(Class…)` call whose service type
     is not a constant into `getModule().addUses(type)` + the call, with the Class-File API — generic, and would also
     lift the Collaboration Engine / LitTemplate limit, but it changes third-party bytecode (licence gate to review).
  3. Vaadin calls `ServiceLoader` only for real service types in `getOrCreate` (upstream, Vidocq/vidocq#256).

## BUG-20261023-07 — `extension remove` leaves the empty `<dependencies>` that `extension add` created

- **Date**: 2026-10-23
- **Status**: FIXED (branch `feat/runtime-tooling`, #314)
- **Affected module**: vidocq-runtime-tooling (`PomEditor.remove`, formerly in vidocq-runtime-cli)
- **Symptom**: on a pom without project-level `<dependencies>`, `vidocq extension add knock-health` then
  `vidocq extension remove knock-health` leaves `<dependencies></dependencies>` behind. Same family as
  BUG-20261023-05, for the dependency rather than the processor path.
- **Minimal reproduction**: `PomEditor.add` on `<project><artifactId>app</artifactId></project>`, then
  `PomEditor.remove` on the result: the pom is not the original one.
- **Cause**: `remove` deleted the `<dependency>` block only; the `<dependencies>` element `add` creates around it
  was never considered.
- **Investigations**:
  - 2026-10-23: found by the `ExtensionEditor` tests written while moving the editors to the shared tooling module.
    Fixed: when the removal leaves the project's `<dependencies>` empty, the element goes too.

## BUG-20261023-08 — `extension add` writes into `<dependencyManagement>` when it comes first

- **Date**: 2026-10-23
- **Status**: FIXED (branch `feat/runtime-tooling`, #314)
- **Affected module**: vidocq-runtime-tooling (`PomEditor.add`, formerly in vidocq-runtime-cli)
- **Symptom**: on a pom whose `<dependencyManagement>` precedes the project's `<dependencies>`, the usual order,
  `vidocq extension add knock-health` inserts the dependency into the managed list. The extension is then only
  managed, never a dependency of the application, and `add` reports "Added knock-health". Without project-level
  `<dependencies>`, it lands in the managed list too.
- **Minimal reproduction**: `PomEditor.add` on a pom holding a `<dependencyManagement><dependencies>…` block
  followed by an empty `<dependencies></dependencies>`: `PomDependencies.contains` is false for the coordinate.
- **Cause**: `add` inserted before the first `</dependencies>` of the file, whichever element it closed; `remove`
  already located the project's own `<dependencies>`, outside `<dependencyManagement>`.
- **Investigations**:
  - 2026-10-23: found by a test written for BUG-20261023-07. Fixed: `add` writes into the project's own
    `<dependencies>`, as `remove` reads it, and creates that element when only a `<dependencyManagement>` has one.

## BUG-20261024-01 — `vidocq create -x a -x b` writes the extensions in an order that changes from run to run

- **Date**: 2026-10-24
- **Status**: FIXED (branch `feat/create-goal`, #314)
- **Affected module**: vidocq-runtime-tooling (`ProjectSpec`, formerly `Command.Create` in vidocq-runtime-cli)
- **Symptom**: two runs of `vidocq create -n demo -x cassini-rest -x mansart-data -x knock-health` can write the
  dependencies and the codegen paths of the pom in different orders, and neither is the order given. Found by
  comparing the project of `vidocq:create` with the one of `vidocq create`: same files, dependencies swapped.
- **Minimal reproduction**: `Set.copyOf(new LinkedHashSet<>(List.of("cassini-rest", "mansart-data", "knock-health")))`
  iterated in two JVMs.
- **Cause**: the parser stored the extensions with `Set.copyOf`, whose iteration order depends on a per-JVM salt.
- **Investigations**:
  - 2026-10-24: fixed: the spec keeps the extensions in a `LinkedHashSet`, in the order given.

## BUG-20261024-02 — `extension add` warns that the Vauban indexer is dropped in a pom that has no Vidocq parent

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/extension-catalog` (#328, umbrella #339)
- **Affected module**: vidocq-runtime-tooling (`PomEditor.dropsInheritedProcessorPaths`, `ExtensionEditor`)
- **Symptom**: on a pom with no Vidocq parent whose `<annotationProcessorPaths>` lists
  `vidocq-runtime-core-codegen` itself (no `combine.children="append"`, there is nothing to append to),
  `vidocq extension add dirac-metrics` prints "annotationProcessorPaths replaces the parent's and so drops the Vauban
  indexer". The build is right: the indexer runs, `checkpom` passes, `vidocq dev` discovers every extension. The
  warning tells the user to change a correct pom; it is the setup of the reporter of #47.
- **Minimal reproduction**: `vidocq create --name demo-app -x cassini-rest`; remove `<parent>`, give the project a
  `<version>`, version the plugins, list `io.vidocq.runtime:vidocq-runtime-core-codegen` in a plain
  `<annotationProcessorPaths>`; `vidocq extension add dirac-metrics` warns, `mvn compile` and `vidocq dev` work.
- **Cause**: the check looks only at the `combine.children` attribute. It does not ask whether the pom inherits a
  Vidocq parent, nor whether the block already lists `vidocq-runtime-core-codegen`. Added by the fix of
  BUG-20261023-02.
- **Investigations**:
  - 2026-10-24: reproduced with the CLI built from `main` (9bb14956).
- **Fix**: a block that lists `vidocq-runtime-core-codegen` drops nothing, appending or not. Tests
  `aBlockThatListsTheIndexerItselfDropsNothing`, `replacingPathsThatListTheIndexerDoNotWarn`.

## BUG-20261024-03 — The extension catalog describes `mansart-data` as Jakarta Persistence

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/extension-catalog` (#329, umbrella #339)
- **Affected module**: vidocq-runtime-mansart-data-extension (`META-INF/vidocq/extension.properties`)
- **Symptom**: `vidocq extension list --available` prints `mansart-data — Jakarta Persistence (JPA)`. The extension
  wires Jakarta Data 1.0 repositories, as its pom says. The generated catalog repeats the error in the CLI,
  `vidocq:list-extensions` and `vidocq help`.
- **Minimal reproduction**: `vidocq extension list --available | grep mansart-data`.
- **Cause**: `description = Jakarta Persistence (JPA)` in the descriptor.
- **Fix**: `Jakarta Data 1.0 repositories`; the other descriptions checked against their poms, and
  `humboldt-telemetry` now says traces, metrics and logs, not tracing alone. Test `mansartDataIsDescribedAsJakartaData`.

## BUG-20261024-04 — `extension add mansart-persistence` writes a coordinate that does not exist

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/extension-catalog` (#330, umbrella #339)
- **Affected module**: vidocq-runtime-tooling (`KnownExtensions`, `ProjectSpec`); the orphan
  `vidocq-runtime-mansart-persistence-extension` directories
- **Symptom**: `vidocq extension add mansart-persistence` reports "not a known extension — resolved by convention"
  and writes `io.vidocq.runtime:vidocq-runtime-mansart-persistence-extension`; the next build fails ("was not found
  in …maven-snapshots").
- **Minimal reproduction**: `vidocq create --name demo-app && cd demo-app && vidocq extension add mansart-persistence
  && vidocq test`.
- **Cause**: two causes. First, every id outside the catalog was resolved by convention to
  `io.vidocq.runtime:vidocq-runtime-<id>-extension`, while no Vidocq extension has that groupId: the convention could
  only write a coordinate that does not exist. Second, the first hypothesis, a published extension without a
  descriptor, was wrong: `vidocq-runtime-mansart-persistence-extension` and its `-codegen` are directories no pom
  lists as a module, so they are never built nor published. They are left from Mansart Persistence, now Noether:
  their `module-info.java` was renamed `.bak`, and they depend on `mansart-persistence-*`, which Mansart no longer
  has. The jars in a local repository dated from an older build.
- **Fix**: an id outside the catalog is refused, by `vidocq extension add`, `vidocq create -x` and their goals, with
  the closest catalog ids (a shared word, or two edits at most); an extension outside the catalog is added as
  `groupId:artifactId`. The orphan directories are removed. Reactor tests: every module directory of an extension
  group is a module of its pom (`everyExtensionModuleDirectoryIsInTheReactor`), and an extension with a
  `<artifactId>-codegen` module declares it as its codegen bundle (`everyExtensionDeclaresItsCodegenSibling`).

## BUG-20261024-05 — The runtime parent does not manage every published extension

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/extension-catalog` (#331, umbrella #339)
- **Affected module**: root `pom.xml` (`vidocq-runtime-parent` `<dependencyManagement>`)
- **Symptom**: `vidocq-runtime-grimm-openapi-extension` and `vidocq-runtime-grimm-openapi-ui-extension` are not
  managed (`mansart-persistence`, first listed here, is not published at all: see BUG-20261024-04). An application under the parent
  that declares one without `<version>`, as it can for every other extension, fails with "dependencies.dependency.version
  is missing". `vidocq extension add` hides it by always writing a version.
- **Minimal reproduction**: in a created project, declare
  `io.vidocq.runtime.extensions.microprofile:vidocq-runtime-grimm-openapi-extension` without a version;
  `mvn validate`.
- **Cause**: entries forgotten when the modules were added; no reactor test compares the published extension modules
  with the managed ones.
- **Fix**: both entries added. Test `theRuntimeParentManagesEveryPublishedExtensionModule`: every published
  `*-extension`, `*-extension-codegen` and `*-extension-dev` module of the reactor is managed.

## BUG-20261024-06 — `vidocq create`: the application takes the runtime's version

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/create-scaffold` (#332, umbrella #339)
- **Affected module**: vidocq-runtime-tooling (`ProjectScaffolder`)
- **Symptom**: the created pom has no `<version>`, so the application inherits `vidocq-runtime-parent`'s:
  `target/demo-app-0.4.0-SNAPSHOT.jar`, and the startup banner says `io.example.demo.app 0.4.0-SNAPSHOT`. The
  application's version moves with each Vidocq upgrade.
- **Minimal reproduction**: `vidocq create --name demo-app && cd demo-app && vidocq build && ls target/*.jar`.
- **Cause**: the pom template writes no `<version>`. It was deliberate: the parents up to 0.3.0 named the runtime
  version `${project.version}` in their `dependencyManagement`, which resolves at the child's version
  (BUG-20261006-02); the parent names it literally since 0.4.0.
- **Fix**: `<version>1.0.0-SNAPSHOT</version>` under a parent from 0.4.0; under an older `--parent-version`, the
  project keeps inheriting the parent's version, and `create` says why. Tests `theApplicationHasAVersionOfItsOwn`,
  `underAParentBefore040TheApplicationInheritsItsVersion`; checked end to end: `target/demo-app-1.0.0-SNAPSHOT.jar`,
  banner `demo-app 1.0.0-SNAPSHOT`.

## BUG-20261024-07 — `vidocq create`: the pom carries workarounds for bugs fixed since 0.2.x

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/create-scaffold` (#333, umbrella #339)
- **Affected module**: vidocq-runtime-tooling (`ProjectScaffolder`)
- **Symptom**: every created pom sets `<jvmArgs>-Dfile.encoding=UTF-8</jvmArgs>` "because vidocq:package 0.2.0 fails
  on an absent jvmArgs (BUG-20260710-01)" and `<mainClass>${vidocq.mainModule}/${vidocq.mainClass}</mainClass>` for
  BUG-20260710-02. Both are FIXED (#40, #198).
- **Minimal reproduction**: `vidocq create --name demo-app && grep -n BUG-2026 demo-app/pom.xml`.
- **Cause**: the workarounds were never taken out of the template once the bugs were fixed.
- **Fix**: both removed; `vidocq:package` reads `vidocq.mainClass` and adds the module itself. Checked end to end,
  with and without `--main`: the zip launcher of `vidocq build` and the image of `vidocq build jlink` answer
  `GET /hello` with 200.

## BUG-20261024-08 — `vidocq create` writes no Maven wrapper, so `vidocq doctor` warns on the project it just made

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/create-scaffold` (#334, umbrella #339)
- **Affected module**: vidocq-runtime-tooling (`ProjectScaffolder`)
- **Symptom**: `vidocq create --name demo-app && cd demo-app && vidocq doctor` gives `⚠ Maven wrapper — no mvnw on
  this path`. Without a wrapper, `vidocq dev` / `build` run the `mvn` of the `PATH` (3.9.9 here, not the pinned
  3.9.16), and each Maven run on JDK 25 starts with eight `WARNING:` lines (Jansi `System::load`, Guava
  `sun.misc.Unsafe`).
- **Minimal reproduction**: the commands above.
- **Cause**: the scaffolder writes the pom, sources and `vidocq.properties` only.
- **Fix**: `create` writes the wrapper of this repository (`mvnw` executable, `mvnw.cmd`,
  `.mvn/wrapper/maven-wrapper.properties`), which the tooling jar carries from the repository root, so a Maven
  upgrade of the ecosystem reaches new projects; `vidocq doctor` on a created project reports no warning. The
  `.mvn/jvm.config` first considered is not needed: Maven 3.9.16 on JDK 25 prints no `WARNING:` line (checked
  2026-10-24; the eight lines came from 3.9.9). Test `theScaffoldWritesTheEcosystemsMavenWrapper`.

## BUG-20261024-09 — `vidocq create --name demo-app` names the class `DemoAppApp`

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/create-scaffold` (#335, umbrella #339)
- **Affected module**: vidocq-runtime-tooling (`ProjectScaffolder.appClassName`)
- **Symptom**: `vidocq create --name demo-app --main` writes `DemoAppApp.java`, and `vidocq idea` then writes
  `DemoAppApp (dev).run.xml`. Any name ending in `app` gets `…AppApp`.
- **Minimal reproduction**: `ProjectScaffolder.appClassName("demo-app")` returns `DemoAppApp`.
- **Cause**: the method always appends `App`; its Javadoc (`"my-cool-app" → "MyCoolApp"`) says otherwise.
- **Fix**: `App` is appended only when the name does not already end with it. Tests `theClassNameEndsInAppOnce`,
  `mainOptionNamesTheClassOfADemoAppDemoApp`.

## BUG-20261024-10 — The build of a created application tells the user to add a `provides` it does not need

- **Date**: 2026-10-24
- **Status**: FIXED on `fix/provides-hint`, with Vauban's companion branch (#336, umbrella #339)
- **Affected module**: Vauban `vauban-processor` (`VaubanProcessor`), as used by vidocq-runtime-core-codegen
- **Symptom**: every compile prints `[Vauban] Generated io.example.demo.app._VaubanComponents … On the module path,
  add to module-info: provides io.vidocq.vauban.api.VaubanComponentProvider with io.example.demo.app._VaubanComponents;`.
  The created `module-info.java` has no such `provides`, and the application works without it.
- **Minimal reproduction**: `vidocq create --name demo-app -x cassini-rest && cd demo-app && vidocq build`.
- **Cause hypothesis**: the note addresses an application that uses Vauban alone. Under Vidocq the provider is
  reached another way (`vidocq:generate` service entries / the application layer); to confirm.
- **Investigations**:
  - 2026-10-24: without the `provides`, `/hello` and `/health` answer 200 under `vidocq start`, `vidocq dev` and from
    the `vidocq build jlink` image.
- **Fix**: confirmed: the advice is right for Vauban alone, whose `ServiceLoader` reads `provides` only in a named
  module, and does not apply under Vidocq, whose application layer turns `META-INF/services` into `provides`.
  Vauban gains `-Avauban.providesHint=false` (VAU-APT-004), a supported option that keeps the note and drops the
  advice; the runtime parent sets it on the compiler. Checked on a created application: the note reads
  `[Vauban] Generated io.example.plain.app._VaubanComponents (1 component(s), 0 field(s), 0 method(s)).`, with no
  "not recognized" warning. The only reactor module that replaces the processor paths is the `peano-accessors`
  integration test of the plugin.

## BUG-20261024-11 — The build of a created application prints three javac warnings

- **Date**: 2026-10-24
- **Status**: WONTFIX — documented as expected (#337, umbrella #339)
- **Affected module**: Vauban `vauban-processor`, vidocq-runtime-parent (compiler configuration)
- **Symptom**: the first build of `vidocq create --name demo-app -x cassini-rest` prints `File for type
  'io.example.demo.app.HelloResource_Factory' created in the last round will not be subject to annotation processing`,
  and the same for `HelloResource_ClientProxy` and `_VaubanComponents`. The generated code is right.
- **Minimal reproduction**: the command above, then `vidocq build`.
- **Cause**: javac's `proc.file.create.last.round` warning; Vauban's processor writes sources in the last round on
  purpose (see Vauban's BUG.md on the classes emitted as bytecode). Whether `-Xlint:-processing` covers this key on
  JDK 25 is to confirm.
- **Investigations**:
  - 2026-10-24: javac emits the warning for every source or class file created in the last round, outside any lint
    category: `-Xlint:-processing` and `-Xlint:none` leave the three lines on JDK 25, only `-nowarn` removes them,
    and it would hide every warning of the application. Vauban generates in the last round on purpose (companion
    processors and BCEs must have run first), so neither option of the fix holds as written: the fix is a change of
    Vauban's round strategy, studied in Vidocq/vauban#216.
- **Decision** (2026-10-24): the warnings stay. They have no effect: javac compiles the files created in the last
  round, and no processor needs to read them afterwards; the application built from them boots and serves
  `GET /hello`. Changing Vauban's round strategy (vauban#216, options 1 and 2) would trade a warning for a risk on the
  BCEs or on IDE builds. The getting-started page says the warnings are expected, and why
  (`getting-started.adoc#last-round-warnings`).
- **Update** (2026-10-28): two warnings remain. Vauban no longer generates the `_Factory` classes, which nothing
  loaded (Vidocq/vauban#244): `HelloResource_ClientProxy` and `_VaubanComponents` are the classes it writes last.

## BUG-20261024-12 — `CLI-ROADMAP.md` describes the CLI before #314

- **Date**: 2026-10-24
- **Status**: FIXED on `docs/cli-roadmap` (#338, umbrella #339)
- **Affected module**: vidocq-runtime-cli (`CLI-ROADMAP.md`)
- **Symptom**: the file says `vidocq start` delegates to `VidocqBootstrap`, `vidocq dev` boots the runtime in-process
  without class reloading, and `info` / `extension list` / `doctor` scan the CLI's class path. Since 2cf4cb00 and
  #314, `dev` and `start` run `vidocq:dev` / `vidocq:run` in the project, and the extension reports resolve the
  project's dependencies with Maven. The old description is the confusion of #47.
- **Minimal reproduction**: read M1, M2 and M6 of `vidocq-runtime-cli/CLI-ROADMAP.md`.
- **Cause**: the file was not updated with 2cf4cb00 and #314.
- **Fix**: the file keeps the principles of the CLI and points to the reference page, the CLI/plugin table and
  `whats-new` for the commands, and to #339 for what is next: one description to keep up to date.
