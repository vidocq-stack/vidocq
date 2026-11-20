# BUG — Vidocq Runtime

Tracking reproducible bugs in the Vidocq runtime (orchestrator + extension wrappers).
Vidocq workspace convention: short id, date, symptom, minimal repro, cause hypothesis, status.

---

## BUG-20261120-01 — `CreateThenBuildIT` cannot resolve bcutil on some CI runners

- **Date**: 2026-11-20
- **Status**: FIXED — the invoker staging installs `org.bouncycastle:bcutil-jdk18on:1.81` into the fixture's
  repository (`extraArtifacts` of `vidocq-runtime-it-create`).
- **Module**: `vidocq-runtime-it-create` (`CreateThenBuildIT`)
- **Symptom**: four tests that build a created application with its build identity fail with
  `Failed to execute goal io.github.git-commit-id:git-commit-id-maven-plugin:9.2.0:revision (vidocq-build-info) on
  project demo-app … No versions available for org.bouncycastle:bcutil-jdk18on:jar:[1.81,1.82) within specified range`.
- **Repro**: the `producer` job of vidocq#682, twice, on two runners (runs 5461 and 5507); the same test passed on
  vidocq#695 on a third runner (run 5488).
- **Cause**: git-commit-id-plugin-core 6.2.0 takes `bcpkix-jdk18on` 1.81, whose pom asks for `bcutil-jdk18on`
  `[1.81,1.82)`. The fixture's builds read every artifact from the outer local repository, through a `file:` mirror.
  A version range needs that repository's `maven-metadata.xml`, and a local repository holds only
  `maven-metadata-<repository id>.xml`. The range resolves only where the fixture's own repository already has the
  artifact.

## BUG-20261119-80 — Every stop in the tests of `vidocq-runtime-core` fails to call `ShutdownObserver`

- **Date**: 2026-11-19
- **Status**: FIXED by Vauban BUG-20261119-72 (vauban#354, snapshot `0.4.0-20261119.100429-81`, build-info
  `e099b7ef`). No change to the fixture.
- **Module**: `vidocq-runtime-core` (tests)
- **Symptom**: 37 warnings per run of the module's tests, in seven test classes (`StartupReportBootTest`,
  `StartupReportViewTest`, `ProgrammaticShutdownTest`, ...): `The lifecycle observer
  io.vidocq.runtime.core.ShutdownObserver.onShutdown of Shutdown threw ContainerOperationException`, `Cannot
  reflectively access io.vidocq.runtime.core.ShutdownObserver on the module path`. Found by VAUBAN.
- **Repro**: `ProgrammaticShutdownTest.aStopLogsNoFailureOfAnObserverItDiscovered`: a deployment that does not add
  the observer, a stop, and the records of `io.vidocq.vauban`. Red on Vauban snapshot 80.
- **Cause**: the class path scan of `VidocqBootstrap` reads the test round's `vauban-beans.list` and finds the
  `@ApplicationScoped` fixture, but not its generated code. The module's main sources have no bean, so the test round
  emits a plain `_VaubanComponents` that no `provides` names, and a `META-INF/services` file is ignored in the named
  module the test classes are patched into. The container then went reflective and could not reach the package.
  Two fixes on the Vidocq side were tried and fail: `@Vetoed` with `addBeanClass` (the latter honours the veto), and
  no bean defining annotation (the processor then generates nothing, and `addBeanClass` registered no observer and
  said nothing: Vauban BUG-20261119-73).
- **Fix**: Vauban takes the generated provider of each package that a bean list names, when the package is exported
  or opened to it. `io.vidocq.runtime.core` is exported. The observer is now called through its generated code at
  every stop, and returns at once there, as the fixture intends.

## BUG-20261119-93 — A unit that names another provider stops the start of the application

- **Date**: 2026-11-19
- **Status**: FIXED on `fix/third-party-provider-units`: `VidocqPersistenceUnitBootstrap` creates the factory through
  Noether's `ContainerFactories`, and the startup report skips a factory that is not Noether's.
- **Module**: `vidocq-runtime-noether-persistence-extension` (`VidocqPersistenceUnitBootstrap`,
  `NoetherPersistenceExtension.onStart`)
- **Symptom**: found by the Jakarta EE 11 Platform TCK for Persistence (`ee.pluggability`), whose units name the TCK's
  alternate provider. A unit whose `<provider>` names another provider than Noether (Jakarta Persistence 3.2, §9.1)
  stopped the start with `Persistence unit registry (JTA, on the pool 'notes') could not start: No Persistence provider
  for EntityManager named registry`. Once the factory was created, the start failed again: the startup report unwrapped
  every factory to Noether's `UnitDescription`, and the alternate provider's factory names no unit (`getName()` is
  `null`, a `NullPointerException`) or refuses to unwrap.
- **Minimal reproduction**: `NoetherPersistenceOtherProviderModulePathTest` (`vidocq-runtime-it-noether-persistence`),
  unit `registry` of its `persistence.xml`, provider `RecordingProvider`.
- **Hypothesised cause**: the bootstrap called `Persistence.createEntityManagerFactory`, the Java SE bootstrap, where a
  provider is asked by unit name and the TCK's alternate provider declines; a container calls the provider's
  `createContainerEntityManagerFactory` with the unit (§9.6). Noether BUG-20261119-90 adds
  `ContainerFactories.create`, which does that for a unit of another provider.
- **Investigations**:
  - 2026-11-19: reproduced red with the test above: the start failed with the message above. With the bootstrap fixed
    and the report unchanged, the start failed with `RecordingProvider unwraps to nothing`.
  - 2026-11-19: fixed. The report counts Noether's units only: `NoetherPersistenceReportModulePathTest` still reads
    `5 persistence units, 5 entities` with the sixth unit `registry` in the descriptor.

## BUG-20261118-94 — A dev reload no longer names a leaked thread when the application has the Ravel extension

- **Date**: 2026-11-18
- **Status**: FIXED — `LayerThreads.holding` walks the parent chain of each thread's loaders.
- **Module**: `vidocq-runtime-core` (`LayerThreads`, read by `VidocqAppLayer.resetForReload`)
- **Symptom**: under `vidocq:dev`, a reload that leaves a thread of the application running warns
  `Hot reload: 1 thread(s) still hold the previous application layer after shutdown and keep it in memory:
  acme-pages-poller. …`. Once the application depends on `vidocq-runtime-ravel-config-extension`, the same reload
  logs no warning, though the poller thread still runs and still keeps the layer in memory.
- **Repro**: the `app` module of the fixture of `vidocq-runtime-it-app-layer` depends on the Ravel extension.
  `AppLayerDevIT.theLookupSurvivesAReloadAndTheLeakedThreadIsNamed` failed with
  `no 'acme-pages-poller' within PT3M`, twice in a row; it passed without the dependency.
- **Cause**: when the bean classes come from more than one loader (Ravel's beans outside the layer, the
  application's inside), Vauban's `VaubanContainerBuilder` installs a `CompositeClassLoader` built on the layer's
  loader as the context class loader of the whole build, and fires `Startup` there. The application's observer
  started its poller under that composite, which the thread inherited. `LayerThreads.holding` compared each thread's
  loader to the layer's by identity and missed the child. A probe printed `CompositeClassLoader`, parent
  `VaubanClassLoader`, in both cycles.
- **Verification**: `LayerThreadsTest.reportsThreadsHoldingAChildOfTheLoader` (red before the fix); the IT above,
  green with the Ravel extension in the fixture, which keeps it.

## BUG-20261118-93 — A thread of the common pool does not see the application under the application layer

- **Date**: 2026-11-18
- **Status**: FIXED — option 1, chosen by the maintainer on 2026-11-19: a thread factory of the common pool.
- **Module**: `vidocq-runtime-core` (`commonpool.CommonPoolThreadFactory`, `VidocqAppLayer`), `vidocq-runtime-maven-plugin`
  (every launcher)
- **Symptom**: under the application layer (`vidocq:run`, `vidocq:dev`, the `vidocq:package` distribution), a task of
  the JVM's common pool (`CompletableFuture.supplyAsync`, a parallel stream) reads `ConfigProvider.getConfig()` and
  misses the application's `META-INF/microprofile-config.properties`; a `ServiceLoader` there misses its services. A
  jlink image, where every module is the JVM's, is not affected.
- **Repro**: the probe of the `app` fixture of `vidocq-runtime-it-app-layer` prints the context class loader of a
  worker: `COMMON-POOL-LOADER jdk.internal.loader.ClassLoaders$AppClassLoader@…` under `vidocq:run` and the
  distribution, `COMMON-POOL-LOADER application` in the image. On vidocq#669, `AppLayerDeliveryIT` read `CFG-ASYNC MISSING`
  from the distribution.
- **Cause**: the JDK gives each worker of the common pool the system class loader as its context class loader; an
  application layer is a child loader the JVM does not know of.
- **Fix**: `CommonPoolThreadFactory` gives the workers the installed layer's loader, and the system class loader
  without one. Every launcher passes
  `-Djava.util.concurrent.ForkJoinPool.common.threadFactory=io.vidocq.runtime.core.commonpool.CommonPoolThreadFactory`
  (`ChildJvm` for `vidocq:run`/`vidocq:dev`, the `.sh`/`.cmd` scripts, `ImageLaunch` for jlink and jpackage); the
  runtime sets the property itself when absent, and warns when the pool started before with the JDK's factory. A dev
  reload moves the live workers to the new layer, and to the loader before it while there is none. The workers are a
  documented exception of the platform-thread rule, which now sees a `ForkJoinWorkerThread` too.
- **Verification**: `CommonPoolThreadFactoryTest` (3); the probe asserts `COMMON-POOL-LOADER application` in
  `AppLayerRunIT` (both modes), `AppLayerPackageIT` (the three launches), `AppLayerImageIT` and `AppLayerDevIT`, twice,
  across a reload; red before the fix under `vidocq:run` and the distribution.


## BUG-20261118-91 — The client proxy of a library class runs a constructor of that class in a jlink image

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/image-proxy-link`
- **Module**: `vidocq-runtime-maven-plugin` (`StagedProxies`, new `StagedProxyLinks`)
- **Symptom**: a producer of a normal scope returns a class of a library that has a package-private member, so its
  client proxy is placed in the library's package (BUG-20261116-06). Under the application layer (`vidocq:dev`,
  `vidocq:run`, the `vidocq:package` distribution) no constructor of the library runs for the proxy. In the
  `vidocq:jlink` image the proxy ran the constructor of the class with the fewest parameters, with `null`, `0` and
  `false`: the fixture's `Gadget` counted two constructions where the layer counts one (`AppLayerImageIT` pinned
  `GADGET-CONSTRUCTED 2`). A class whose only constructor refuses `null` started in every form but the image, which
  stopped at deployment validation (`Normal-scoped bean acme.placed.GadgetProducer must have a non-private no-arg
  constructor, or declare a non-private constructor taking io.vidocq.vauban.api.ProxyLink …`). Reported by a review
  of Vidocq, 2026-11-18; Vauban promises the opposite (`se.adoc`, `internals.adoc`: "the third-party constructors do
  not run for the proxy").
- **Repro**: the `placed` module of the fixture of `vidocq-runtime-it-app-layer`, with the new library class
  `acme.gadget.Strict` (only a `(String)` constructor that calls `requireNonNull`) produced `@ApplicationScoped`.
  `mvn package vidocq:jlink && target/dist/bin/app-layer-placed`.
- **Cause**: `StagedProxies` added the proxy that `DependencyEnhancer` generates to the staged copy of the library,
  and nothing more. Under the application layer, Vauban's `CdiProxifierTransformer` also weaves a
  `protected synthetic (ProxyLink)` constructor into the class, and into the superclasses of its chain that the
  loader defines, and retargets the proxy onto it (`ProxyLinkWeaver`). The image had neither: the proxy kept the
  constructor the generator picks for a class without the marker.
- **Fix**: after the enhancement, `StagedProxyLinks` applies the same `ProxyLinkWeaver` to the staged jars, with the
  rules of the loader: `addMarkerConstructor` on the produced class and on each superclass of its chain that a staged
  jar holds, in the jar that holds it, root and versioned entries; the chain ends at `Object`, at a superclass with
  the marker, or at one with a non-private no-arg constructor; `retargetProxyConstructor` on the proxy. When the chain
  reaches a class outside the staged jars (a class of the JDK) without a no-arg constructor, the build fails before
  linking, with the workarounds (an interface type, `@Singleton` or `@Dependent`), where the loader only warns; nothing
  is rewritten then. A `Serializable` class without a `serialVersionUID` gets the one the JVM computed before the
  weaving (Java Object Serialization Specification §4.6, computed from the class file and checked against
  `ObjectStreamClass`): the added constructor is not private and would change it. Under the application layer the
  loader weaves without pinning it: that drift is Vauban BUG-20261118-95. Since Vauban fixed it (option A, vauban#354),
  `StagedProxyLinks` calls the weaver's `addMarkerConstructorKeepingSerialVersionUid`, which the loader uses for a
  class of a jar, and no longer carries its own copy of the §4.6 computation: both give the same value.
- **Verification**: `StagedProxiesTest`, 7 new tests red before (no marker, no refusal, no `serialVersionUID`), green
  after: a strict class, a strict superclass in the same jar and in another staged jar, a JDK superclass with a no-arg
  constructor, a JDK superclass without one (the build fails), a `Serializable` class keeps its value, the computed
  value equals the JVM's for an abstract class, a nested class and two interfaces. ITs:
  `AppLayerImageIT.theImageHoldsTheProxyOfAThirdPartyClassInItsPackage` now asserts `GADGET-CONSTRUCTED 1` and
  `STRICT-NAME strict constructed 1`, red before (the validation failure above), green after;
  `AppLayerRunIT.theApplicationLayerHoldsTheProxyOfAThirdPartyClassInItsPackage` and the new
  `AppLayerPackageIT.theDistributionPlacesTheProxyOfAThirdPartyClass` green before and after.

## BUG-20261118-90 — Every start of a built application loads the platform's cryptography to check the `vidocq:generate` stamp

- **Date**: 2026-11-18
- **Status**: FIXED on `perf/generate-stamp-without-jca`.
- **Module**: `vidocq-runtime-core` (`GenerateStamp`)
- **Symptom**: `GenerateStamp.staleIn`, called by every boot that is not embedded, recomputed the stamp of the
  application's bean index with `MessageDigest.getInstance("SHA-256")`. That call loads the JCA (`Security`, the
  provider list, the `SUN` provider, the SHA-2 classes) and reads the `java.security` file, in an application that
  needs no cryptography otherwise. It showed up in the attribution of the start regression of 2026-11-18 (vauban#347).
- **Repro**: in a fresh JVM, `-Xlog:class+load`, a probe that computes a SHA-256 of a short index loads 823 classes,
  against 440 for a CRC32 of the same bytes and 415 for a probe that computes nothing.
- **Cause**: the stamp only tells an index an IDE rewrote from the one `vidocq:generate` left. Nobody forges it, so it
  needs no collision resistance.
- **Fix**: the stamp records a CRC-32 (`index=crc32:<8 hex digits>`). `GenerateStampTest` reads the constant pool of
  `GenerateStamp` and fails on any class of `java.security` or `javax.crypto`. The plugin writes the stamp with the
  same method, so an application built by the matching plugin keeps a valid stamp. One stamped by an older plugin and
  started on this runtime counts as stale until its next `vidocq:generate`: the boot scans its unindexed archives
  and prints `VIDOCQ-GEN-001`, as for an IDE build.
- **Verification**: `GenerateStampTest` (12) red on the two assertions before the fix, green after; the plugin's
  `GenerateStampWriteTest`, `VidocqGenerateMojoTest`, `ChangedWhileStoppedTest` (31) green.

## BUG-20261118-80 — A transaction a request leaves active is never rolled back

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/servlet-application-roots`.
- **Module**: `vidocq-runtime-mansart-transactions-extension` (`RequestTransactions`)
- **Symptom**: in the Jakarta EE 11 Platform TCK for Persistence, `elementcollection.Client2#elementCollectionBasicTypeXMLTest`
  begins a transaction in the `pmservlet` vehicle, fails on `flush()` and returns without completing it. Every later
  application whose schema generation drops `CUST_TABLE` then fails with `Timeout trying to lock table "CUST_TABLE"`:
  33 times, in 11 test classes (`elementcollection`, `orderby`, `types.property`), two of them at deployment.
- **Repro**: `RequestTransactionsTest#theContainerRollsBackWhatARequestLeftActiveWhenItsContextEnds`: activate a
  request context through `RequestContextController`, as Foy does for each request, begin a transaction, deactivate
  the context; the transaction is still associated with the thread.
- **Cause**: nothing in the runtime ends a transaction that a web component returns with. Jakarta EE 11 Platform
  §4.2.2: returning from `service` or `doFilter` to the network client with an active transaction context is an
  error, and the web container detects it and aborts the transaction. The transaction stayed associated with a
  thread that no longer ran, with its connection and the locks that connection held, beyond the application's stop.
- **Fix**: a `@RequestScoped` bean of the transactions extension notes the transaction associated with the thread when
  the request context starts (`@Initialized(RequestScoped.class)`), and rolls back the one still associated when the
  context is about to end (`@BeforeDestroyed(RequestScoped.class)`), with a warning that cites §4.2.2. Foy and
  Cassini end the context on the thread that served the request. A transaction already active when the context
  started belongs to its owner and is left alone. Container-neutral: CDI events only, no Foy dependency.
- **Verification**: the three rollback cases of `RequestTransactionsTest` red before (the transaction left associated),
  green after; the two cases that leave a transaction alone, and a failing rollback that does not stop the request
  from ending, green; the module's 11 tests.

## BUG-20261118-63 — `java:comp/UserTransaction` is bound to nothing

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/servlet-application-roots`.
- **Module**: `vidocq-runtime-naming-extension` (`PlatformNames`)
- **Symptom**: the `pmservlet` vehicle of the Jakarta EE 11 Platform TCK for Persistence looks up
  `java:comp/UserTransaction` and gets `NameNotFoundException: java:comp/UserTransaction is bound to nothing in the
  running container`, although the transactions extension produces a `UserTransaction` bean. The same holds for
  `java:comp/TransactionSynchronizationRegistry`.
- **Repro**: `NamingModulePathTest#the_user_transaction_of_the_platform_is_the_container_s` and
  `#the_synchronization_registry_of_the_platform_is_the_container_s` (`vidocq-runtime-it-naming`, with the
  transactions extension).
- **Cause**: the naming extension binds only the names `vidocq.properties` declares. The Jakarta EE Platform binds
  both names for every component (§5.10, §5.11), and code written for a server looks them up without declaring them.
- **Fix**: a `PlatformNames` bean of the naming extension binds both names to the default bean of their type, when
  the application has the type and the container a bean of it. A name `vidocq.properties` declares is left to it. The
  types are written as text, so the naming extension needs no transaction API.
- **Verification**: both tests red before (`NameNotFoundException`), green after; `PlatformNamesTest` (5), the
  module's 17 tests and the 9 tests of `vidocq-runtime-it-naming`.

## BUG-20261118-61 — A persistence unit that cannot start is not named in the error

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/servlet-application-roots`.
- **Module**: `vidocq-runtime-noether-persistence-extension` (`VidocqPersistenceUnitBootstrap`)
- **Symptom**: a TCK war declares the unit `AccountManager` on `jdbc/DB1`, and the start fails with
  `MansartPoolExtension did not publish a DataSource: set vidocq.pool.url`, the error of the default pool. The unit
  that failed was another one, `CTS-EM`, which the key `vidocq.persistence.CTS-EM.properties.*` of the runner's
  `vidocq.properties` declares as documented (a JTA unit on the default pool when no `persistence.xml` declares it).
  Nothing in the error said so.
- **Repro**: `VidocqPersistenceUnitBootstrapTest#a_unit_that_cannot_start_is_named_with_its_pool`: the unit `ledger`
  generates its schema at start on a default pool without database.
- **Cause**: the default pool is a bean even without a database, and fails at its first connection, here the schema
  generation inside `Persistence.createEntityManagerFactory`. `UnitDataSources`'s own "no default pool" refusal is
  never reached, and the pool's error knows no unit.
- **Fix**: a failure of `Persistence.createEntityManagerFactory` is rethrown as a `PersistenceException` that names
  the unit, its transaction type and its pool: `Persistence unit CTS-EM (JTA, on the default pool) could not start:
  MansartPoolExtension did not publish a DataSource ...`.
- **Verification**: the test red before (an `IllegalStateException` without the unit), green after; the module's 26
  tests.

## BUG-20261118-60 — A war without web.xml nor annotated component hands no class to a `@HandlesTypes` initializer

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/servlet-application-roots`.
- **Module**: `vidocq-runtime-spi` (`ApplicationLayer`), `vidocq-runtime-foy-servlet-extension` (`FoyDeployments`),
  `vidocq-runtime-arquillian` (`VidocqEmbeddedContainer`, `DeploymentApplicationLayer`, `MaterializedDeployment`)
- **Symptom**: the Jakarta EE 11 Platform TCK for Persistence, run on the embedded container, deploys a war whose
  `WEB-INF/classes` hold a REST `Application` and no `web.xml`: everything under `/rest` answers 404. An empty
  `web.xml` in the war makes it pass.
- **Repro**: `ApplicationRootsTest` (`vidocq-runtime-foy-servlet-extension`): a `@HandlesTypes(Runnable.class)`
  initializer of a library root, and a `Runnable` class written in an application root without `web.xml`. The
  initializer got `[]`.
- **Cause** (proved by the repro): Servlet 6.1 §8.2.4 hands an initializer the classes of the application. Foy knows
  a root of the application by its `web.xml` or by an annotated component only, and scans no other root; a Vidocq
  application needs no more, as its classes are indexed at build. A war the embedded container deploys as it is has
  no index, and Vidocq never named its root.
- **Fix**: `ApplicationLayer.roots()` (default empty) and `ApplicationLayer.applicationRoots()` name the roots of the
  application's own classes. The embedded container gives the materialized root of the archive, where its
  `WEB-INF/classes` go (not the `WEB-INF/lib` jars, each a fragment or a library), and `FoyDeployments` hands them to
  `FoyChappeBoot.Builder.applicationRoot`.
- **Verification**: `ApplicationRootsTest` red before (`the initializer got []`), green after;
  `MaterializedDeploymentTest#theRootOfTheWebInfClassesIsTheApplicationRoot`; `ApplicationLayerTest`.

## BUG-20261118-07 — `GitCommandTest` fails on a loaded machine: a script that answers is cut at 300 ms

- **Date**: 2026-11-18
- **Status**: FIXED on `refactor/app-layer-settings` (Vidocq/vidocq#663).
- **Module**: `vidocq-runtime-maven-plugin` (tests of `buildinfo.GitCommand`)
- **Symptom**: `aCommandThatAnswersGivesWhatItPrinted` fails with `expected: <Optional[1762421400\n]> but was:
  <Optional.empty>`, and `aProcessTheCommandLeavesBehindDoesNotHoldTheBuild` with `NoSuchFileException …/leftover.pid`.
  The plugin's suite failed with them under `install` while other sessions built on the same machine.
- **Repro**: `./mvnw -o -pl vidocq-runtime-maven-plugin test -Dtest=GitCommandTest` with a load average of 9 to 13 on
  18 cores: two runs out of three failed.
- **Cause**: both tests gave a command that must answer the 300 ms limit of the test of a command that hangs. Under
  load, `/bin/sh` had not run the script by then: `GitCommand` left it out as a hanging one and killed it, before it
  printed or wrote its pid file. `GitCommand` behaves as designed; the build uses `GitStatus.TIMEOUT_SECONDS`.
- **Fix**: the two tests take a limit of 2 s (`ANSWER`); a command that ends is answered when it exits, so they do
  not wait it out. The two tests of a command that hangs keep 300 ms.
- **Verification**: ten runs of `GitCommandTest` on the same loaded machine, all 4 tests green each time (five with
  a 5 s limit, five with 2 s, 2.5 to 3 s a run).

## BUG-20261118-06 — The runtime's main, launched from the module path with `vidocq.app.module`, loads the application twice without a warning

- **Date**: 2026-11-18
- **Status**: FIXED on `refactor/app-layer-settings` (Vidocq/vidocq#663).
- **Module**: `vidocq-runtime-core` (`VidocqAppLayer`, `Vidocq.main`)
- **Symptom**: `java --module-path lib:app --add-modules ALL-MODULE-PATH -Dvidocq.app.module=<module> -m
  io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq` starts, and the application module is in the boot layer and
  in the application layer: `Application layer ready: modules [<module>] (boot-layer detection from <module>)`. Each
  of its classes exists twice and a `ServiceLoader` finds each of its providers twice, as in BUG-20261114-10, but the
  start reports no `VIDOCQ-LAY-001`, which a `@VidocqMain` trampoline launched the same way gets.
- **Repro**: in `vidocq-runtime-it-langchain4j-cdi-mcp`, after `McpLaunchShapesTest`, the launch above with `lib` =
  `target/it-lib`, `app` = `target/classes` and the module `io.vidocq.runtime.it.lc4jcdimcp`: the log has the line
  above and no `VIDOCQ-LAY` line, while the trampoline launches of the same test (`i1-flat`, `i1-ide`) report
  `VIDOCQ-LAY-001`. `VidocqAppLayerModuleTest#theRuntimeMainReLayersTheNamedModule` asserted this re-layer as intended.
- **Cause**: without `vidocq.app.path`, `Vidocq.main` called `VidocqAppLayer.installFromModuleIfConfigured()`, which
  re-layered the named boot-layer module through `installFromBootLayer(Module)`. That overload records no list of the
  modules held twice; only `installFromBootLayer(Class)`, the trampoline's, computes it. The path came with jlink
  images without a main class (651c2016, 2026-10-23). Since an image has one layer (12f4bf53, Vidocq/vidocq#580) and
  `@VidocqTest` passes `vidocq.app.path` (07d6b10a, BUG-20261114-07), no launcher used it: only a launch written by
  hand took it, such as the foy example's `BistroApplication`.
- **Fix**: `vidocq.app.module` names the application and loads nothing. `installFromModuleIfConfigured` is removed,
  and `Vidocq.main` first calls `VidocqAppLayer.checkModuleLaunch()`, which stops a launch that names the module
  without `vidocq.app.path` and without `vidocq.app.layer.off=true`: `Vidocq startup failed in application layer
  (vidocq.app.module=<module>): …`, naming both. `BistroApplication` hands its module over with `vidocq.app.path`.
- **Verification**: `VidocqAppLayerModuleTest#theRuntimeMainStopsALaunchThatNamesTheModuleAlone`, a child JVM, red
  before (exit 0 after `Application layer ready: modules [acme.app] (boot-layer detection from acme.app)`), green
  after; unit tests of `checkModuleLaunch` with the name alone, with a path, with the layer off and without the name.
  The foy example's 9 tests: red with the new core and the old launch (`the application did not listen`, the new
  message in its log), green with `vidocq.app.path`. The repro above now exits 1 with the message.

## BUG-20261118-65 — A persistence reference declared on a class is not under `java:comp/env`

- **Date**: 2026-11-18
- **Status**: FIXED on `feat/persistence-references`, with Noether BUG-20261118-64 (same branch name).
- **Module**: `vidocq-runtime-noether-persistence-extension` (`PersistenceNames`)
- **Symptom**: the `pmservlet` vehicle of the Jakarta EE 11 Platform TCK for Persistence declares
  `@PersistenceContext(name = "persistence/CTS-EM", unitName = "CTS-EM")` on its class and looks up
  `java:comp/env/persistence/CTS-EM`: `NameNotFoundException`, with the naming extension installed.
- **Repro**: `NoetherPersistenceReferencesModulePathTest` (`vidocq-runtime-it-noether-persistence`, with the naming
  extension): `Shelf` declares a context and a unit reference on its class; both lookups threw
  `NameNotFoundException: java:comp/env/persistence/ledger is bound to nothing in the running container`.
- **Cause**: Noether read only the annotations of fields and setters (its BUG-20261118-64), and nothing in Vidocq
  bound a name for a persistence reference.
- **Fix**: Noether lists the references in a `PersistenceReferences` bean, each with a `@Singleton` bean; the
  `PersistenceNames` bean of this extension, a `JndiBindings`, binds each name under `java:comp/env` (a name that
  starts with `java:` as it is). The extension now depends on `vauban-naming`.
- **Verification**: both tests red before, green after; `PersistenceNamesTest` (3); the extension's tests and the
  module path ITs of `vidocq-runtime-it-noether-persistence`.
- **Second cause (2026-11-18)**: in the Platform TCK spike the name was still bound to nothing. The container held two
  `PersistenceReferences` beans, one for each part Noether's extension ran on (the runner's tests at build, the war
  at start), and `PersistenceNames` bound nothing when the selection was not resolvable. It now reads every bean of
  the type, binds each name once and refuses a name two parts give to different beans
  (`PersistenceNamesTest`: three tests red before, six green after).

## BUG-20261118-05 — An application with the MCP extension generates two classes at each start

- **Date**: 2026-11-18
- **Status**: FIXED. Part 2 (Cassini's adapter of `McpEndpoint`, Vidocq/vidocq#662) by 3cc57d6f (Vidocq/vidocq#675),
  with the hints that Vidocq/cassini#152 (1ea1d1f2) ships in `cassini-processor`. Part 1 (Vauban's client proxy) is
  FIXED by Vidocq/vauban#348 and #351. `McpLaunchShapesTest` fails on any Vauban or Cassini fallback block. The MCP
  extension and its codegen bundle move to VCU with Vidocq/vidocq#204.
- **Module**: `vidocq-runtime-langchain4j-cdi-mcp-extension`, `vidocq-runtime-cassini-rest-extension-codegen`; Vauban
  for the first class.
- **Symptom**: every application that depends on the MCP extension prints two `RUNTIME CODE GENERATION FALLBACK`
  warnings at start, in every launch shape, also with `cdi extensions  3 extensions frozen at build`: Vauban's, for
  `client proxy dev.langchain4j.cdi.mcp.invoker.cdi41.McpCdi41InvokerProvider_ClientProxy`, and Cassini's, for the
  adapter of `dev.langchain4j.cdi.mcp.server.transport.McpEndpoint`. The reference page said that the server's beans
  run generated code, without these two.
- **Repro**: `McpLaunchShapesTest`, then `grep -A2 "RUNTIME CODE GENERATION FALLBACK" target/it-logs/i1-*.log` in
  `vidocq-runtime-it-langchain4j-cdi-mcp`.
- **Cause**:
  1. `McpCdi41InvokerProvider` is a synthetic bean of a normal scope that langchain4j-cdi's
     `McpInvokerBuildCompatibleExtension` registers, and its bean class is a class of the invoker jar. No processor
     sees a synthetic bean, `@ClientProxies` takes interfaces only, and the freeze records a descriptor, not a proxy
     (Vauban BUG-20261113-19). Vauban's warning advises adding `vauban-processor` to the module that declares the
     class, which is a jar of another project. Vidocq/vauban#342.
  2. `McpEndpoint` is a JAX-RS resource class of a dependency jar. `vidocq:generate` runs a brick's processor over
     the dependency jars that the brick's hints select, and the hints of the Cassini REST codegen bundle
     (`META-INF/vidocq/dependency-codegen.properties`) select for Champollion's bindings only: nothing makes
     Cassini's processor generate the adapter of a resource class of a dependency.
- **Leads**: for 1, Vidocq/vauban#342: the freeze generates the client proxies of synthetic beans (maintainer's
  decision, 2026-11-18). For 2, Vidocq/vidocq#662; two routes exist and neither covers this case:
  - hints for Cassini's own processor in the dependency codegen, as Foy and Mansart have. `vidocq:generate` gives a
    processor the class names of a jar (`javac -proc:only`), and `CassiniResourceProcessor` has only been run over
    sources: to be shown first. A hints file names one brick, and the Cassini bundle's names Champollion;
  - Cassini's `cassini:generate` goal with `scope=dependencies` and `repackageModularDependencies=true`, which writes
    the adapters into a copy of the jar under `target/cassini-repackaged/`. Two examples of this reactor run it for a
    sealed copy of a brick's JAX-RS wrapper (`vidocq-runtime-knock-health-example`), and `vidocq:jlink` is the only
    goal that reads that folder.
- **Investigations**:
  - 2026-11-18: found while fixing BUG-20261118-04. The logs of the three shapes, with the extensions frozen, show
    the two warnings and nothing else generated at start.
  - 2026-11-19, reported by VAUBAN: part 1 is not fixed by Vidocq/vauban#348 in Vidocq's layout. Vauban
    0.4.0-SNAPSHOT build 78 (build-info `475b64a4`, vauban#348 merged), Vidocq `origin/main` `cb0410ba`:
    `McpLaunchShapesTest` passes 8/8 on a private repository. All six `i1` logs (flat, `app_path`, `ide`, and their
    frozen variants, each `3 extensions frozen at build`) still print the Vauban fallback for
    `McpCdi41InvokerProvider_ClientProxy`, plus Cassini's `McpEndpoint` adapter (part 2, Vidocq/vidocq#662).
    The build writes the proxy:
    `target/classes/META-INF/vauban/placed/dev/langchain4j/cdi/mcp/invoker/cdi41/McpCdi41InvokerProvider_ClientProxy.class`,
    and `required-opens.list` lists `McpCdi41InvokerProvider`. The start cannot place it:
    `VaubanClassLoader.placesClass` requires the produced type to be defined by that Vauban loader. The application
    layer holds only the application module, and the langchain4j-cdi jar sits in the runtime layer. Part 1 stays
    OPEN, now as a Vauban gap: a placed proxy for a class outside the Vauban layer (Vauban BUG-20261119-70, filed by
    VAUBAN).
  - 2026-11-19, reported by VAUBAN: part 1 is fixed. Vauban 0.4.0-SNAPSHOT build 80 (build-info `32497fb1`,
    vauban#351 and #352 merged), Vidocq `origin/main` `321fcb13`, a private repository: `McpLaunchShapesTest` passes
    8/8. Each of the six `i1` logs prints one fallback block, Cassini's for `McpEndpoint`; no Vauban block, and
    `McpCdi41InvokerProvider_ClientProxy` appears nowhere. Both shape tests now assert that no
    `VAUBAN — RUNTIME CODE GENERATION FALLBACK` block is printed. FOY's rerun on the same snapshot agrees, with one
    exception: `i2-keys`, a dev launch whose start refuses the build's record (`3 extensions run at start (the record
    of the build is not this deployment's)`), still generates the proxy at start. The proxy of a synthetic bean comes
    with the frozen record (vauban#342), so a start that runs its extensions has none: expected, and not asserted.
  - 2026-11-19, fix of part 2 (route 1 of the leads): `cassini-processor` ships its own
    `META-INF/vidocq/dependency-codegen.properties` (brick Cassini, `class-bytes=jakarta/ws/rs/Path`, the
    `ResourceAdapter` and `RouteProvider` service files as markers and merge entries), and `vidocq:generate` reads the
    hints of every jar in the closure of a codegen bundle, not only of the bundle's own jar (`ArtifactResolver
    .resolveClosure`, `CodegenBundles`): a hints file names one brick, and the REST bundle's names Champollion. The MCP
    extension gets a codegen bundle of its own, `vidocq-runtime-langchain4j-cdi-mcp-extension-codegen`, which depends
    on the REST bundle; the application declares it. Run on a private repository: `vidocq:generate:
    langchain4j-cdi-mcp-server-1.4.0-SNAPSHOT.jar — Cassini (2 classes, generated)`, `McpLaunchShapesTest` 8/8
    (6 of 8 red before) with no fallback block, the 10 tests of the module green. The extension is not in the extension
    catalog while it is left out of the releases (the catalog lists the published extensions), so nothing wires the
    bundle for an application that does not declare it: add the descriptor, with `codegen =`, when vidocq#204 releases
    the extension.

## BUG-20261118-04 — The jar of the MCP extension carries a record of frozen extensions, so its applications run theirs at each start

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/mcp-extension-frozen-record`.
- **Module**: `vidocq-runtime-langchain4j-cdi-mcp-extension` (its `pom.xml`)
- **Symptom**: an application that depends on the MCP extension runs its build compatible extensions at every
  start, in every launch shape, although its build froze them. The report reads `cdi extensions  3 extensions run at
  start (the record of the build is not this deployment's)` and the start logs
  `[VIDOCQ-GEN-003] 3 build compatible extensions ran at start: 2 records of frozen extensions on the path
  ([jar:file:…/vidocq-runtime-langchain4j-cdi-mcp-extension-0.4.0-SNAPSHOT.jar!/META-INF/vauban-frozen-extensions.properties,
  file:…/target/classes/META-INF/vauban-frozen-extensions.properties]): none can be told to be this deployment's.`
  With `-Dvidocq.startup.require-frozen-extensions=true` the application does not start.
- **Repro**: `unzip -l` of the extension's jar lists `META-INF/vauban-frozen-extensions.properties` and its two
  companion files; `McpLaunchShapesTest#theStartLoadsTheExtensionsFrozenAtBuild` before the fix.
- **Cause**: the extension's build runs `vidocq:generate` to write `META-INF/vauban-beans.list` from the
  langchain4j-cdi jar. Since Vidocq/vidocq#548 (6c78bc0a, 2026-11-13) the goal also freezes the extensions, by
  default, and nothing tells it that this project is a library: the record went into the extension's jar. Vauban
  uses a record only when it is the only one on the path (`FrozenExtensions.find`), so the application's own record
  was refused. No test asked the start what it did with the extensions.
- **Scope**: the extension is left out of the releases and of the published snapshots while langchain4j-cdi is a
  SNAPSHOT (`maven.deploy.skip`), so only local builds of this repository were affected. Vidocq/vidocq#204 ships it
  with the release.
- **Fix**: `<freezeExtensions>false</freezeExtensions>` on the extension's `vidocq:generate`: its jar carries no
  record. The reference page of the plugin says that a library module sets it.
- **Verification**: `McpLaunchShapesTest#theStartLoadsTheExtensionsFrozenAtBuild`, red before in the three shapes
  on acfe2dae with Vauban bd38e952 (`3 extensions run at start`), green after (`3 extensions frozen at build`, no
  `VIDOCQ-GEN-003`); the ten tests of the module pass. `NoFrozenRecordInRuntimeJarsTest`, in the architecture
  tests, fails when a module of the runtime carries a record: red with the fix removed, naming the extension's jar,
  green with it.
- **Investigations**:
  - 2026-11-18: no other module of this reactor runs `vidocq:generate` as a library. The protection stays thin:
    any jar on an application's path that carries a record turns the freeze off for that application, and only the
    start says so. Vidocq/vauban#341 asks Vauban to use the record whose fingerprint is the deployment's.

## BUG-20261118-03 — The dev console's CDI inventory matches a parameterized type without its owner

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`, as the source companion of Vauban BUG-20261117-21.
- **Module**: `vidocq-runtime-devconsole-extension` (`CdiInventory.type`)
- **Symptom**: with Vauban BUG-20261117-21, `TypeInfo.ParameterizedType` gains a third component, `owner`. The
  record pattern `TypeInfo.ParameterizedType(var rawType, var typeArguments)` of `CdiInventory.type` no longer
  compiles.
  Vauban BUG-20261118-47 also gives `Outer<String>.Plain`, a class without type parameters inside a generic class,
  as a parameterized type with an owner and no argument: joining no argument would show it as `Plain<>`.
- **Repro**: compile `vidocq-runtime-devconsole-extension` against a Vauban that carries BUG-20261117-21;
  `CdiPanelTest#aMemberOfAParameterizedTypeWithoutArgumentsIsShownByItsSimpleName` for the type without argument.
- **Cause**: a record pattern lists every component of the record. Vauban keeps the 2-argument constructor and the
  accessors, so the change is binary compatible: a jar built before it still runs, and the tests that use the
  2-argument constructor compile unchanged.
- **Fix**: the pattern names the owner as `_`: the panel shows the raw type and its arguments, as before, and the
  simple name alone for a type without argument. The owner stays out, as the panel shows simple names. The fix, and
  the test that builds that type with the 3-argument constructor, compile only against the new Vauban.
- **Verification**: pending, until the Vauban change is in the stack.

## BUG-20261118-02 — The EL proxy unwrapper names a class a Vauban client proxy does not stand for

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`, on Vauban's `ClientProxyClass` (BUG-20261118-20 and -21 of Vauban).
- **Module**: `vidocq-runtime-peano-el-extension` (`VaubanProxyUnwrapper`)
- **Symptom**: `proxiedClass` named the superclass of any class named `*_ClientProxy` in the same package. Past a
  `final` method of the bean class, Vauban's proxy extends a class above the bean class: the unwrapper named that
  class, which the proxy does not stand for, so EL took that class's Peano table for the bean. It also named the
  superclass of an application class that is only named like a proxy.
- **Repro**: `VaubanProxyUnwrapperTest#namesNothingForAProxyThatIsNoInstanceOfItsBeanClassNorForALookalike`.
- **Cause** (proved by the repro): the unwrapper kept its own copy of the name rule, written when a proxy always
  extended its bean class. Vauban's `ClientProxyClass` reads the bean class on every path and knows a proxy is final.
- **Fix**: `proxiedClass` asks `ClientProxyClass.beanClassOf` and names the class only when the proxy is an instance
  of it, in its package: Peano's table casts the proxy to it. A proxy that extends its bean class gets the same answer
  as before; any other is read by reflection. The module requires `io.vidocq.vauban.api`.
- **Verification**: red on 921c0573's main code (`vidocq-bug0202-red.log`, "Red run 1": `Shelf` named for the proxy
  of `Locked`, 1 failure of 2). Green: `vidocq-bug0202-dev.log`, "Dev run 1" (the module's 5 tests).

## BUG-20261118-01 — A filtered REST mount never ends a `@Dependent` resource

- **Date**: 2026-11-18
- **Status**: FIXED on `fix/vauban-bug-stack`, with Cassini's companion of the same name (CASSINI-062).
- **Module**: `vidocq-runtime-cassini-rest-extension` (`CassiniMountHandlerProvider.PackageScopedBeanProvider`)
- **Symptom**: on a mount with `include-packages` or `exclude-packages`, a `@Dependent` resource's `@PreDestroy`
  never runs: after two requests, two instances made, none destroyed. An unfiltered mount ends each with its request.
- **Repro**: `PackageScopedRequestReleaseTest#aFilteredMountEndsADependentResourceWithEachRequest` and
  `PackageScopedBeanProviderTest#aResourceForARequestIsTheDelegatesWithItsRelease`.
- **Cause** (proved): Cassini asks for a resource with `BeanProvider.getRequestBean`, which returns it with what ends
  it (CASSINI-062). The package filter delegates `getBean` and `contextualInstance` only, so the SPI's default
  served it, `getBean` and nothing to release.
- **Fix**: `PackageScopedBeanProvider` delegates `getRequestBean` too.
- **Verification**: red on `origin/main`'s main code (`stack-l3-vidocq-red.log`, "BUG-20261118-01 red, run 3": the
  delegate's release lost, and 2 made, 0 destroyed). Green: `stack-l3-vidocq-green.log`, "BUG-20261118-01 fix, run 2"
  (the module's 34 tests), then the module and its dependants, `stack-l3-vidocq-amd.log`.

## BUG-20261117-13 — An application class that has only another qualifier than `@Default` is created by its constructor

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/app-class-lifecycle`. The runtime looks the bean up through the `BeanContainer`: the
  beans with `@Default` first, as before, then the bean of the class itself under `@Any`, whatever its qualifiers.
  A producer the class declares is still told apart by its types. Reproduction enabled:
  `ApplicationClassBeanTest.anApplicationClassWithAnotherQualifierIsRunWithItsInjections`, red on `main`
  (`expected: <1> but was: <0>`).
- **Module**: `vidocq-runtime-core` (`Vidocq.run(Class, String...)`)
- **Symptom**: an `@ApplicationScoped` application class with a qualifier of its own is a bean, and the runtime
  creates it with its public no-arg constructor. Its injected fields are `null` (`expected: <1> but was: <0>`), and
  nothing says so.
- **Repro**: the disabled test above.
- **Cause**: `CDI.current().select(layerClass)` names no qualifier, so it asks for `@Default`, which that bean does
  not have.

## BUG-20261117-12 — The `@PreDestroy` of a `@Dependent` application class never runs

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/app-class-lifecycle`. The runtime destroys the `@Dependent` instance it asked the
  container for when it stops, after the tasks before the stop and before any extension stops, so its `@PreDestroy`
  still has the data source, the server and the configuration (`VidocqBootstrap.releaseOnStop`). The bounded path of
  BUG-20261117-11 goes the same way. Reproductions enabled:
  `ApplicationClassBeanTest.theInstanceOfADependentApplicationClassIsDestroyedWhenTheRuntimeStops`, and
  `theInstanceOfABoundedApplicationClassLivesAsLongAsTheOneOfAnyDependentApplicationClass`, which now asks for one
  destroyed instance of each, not equal counts. Red on `main`, `expected: <1> but was: <0>`.
- **Module**: `vidocq-runtime-core` (`Vidocq.run(Class, String...)`)
- **Symptom**: an application class annotated `@Dependent` has a `@PreDestroy` method. The method does not run when
  the runtime stops (`expected: <1> but was: <0>`).
- **Repro**: the disabled test above.
- **Cause hypothesis**: the runtime asks `CDI.current()` for the instance and never gives it back. A `@Dependent`
  instance obtained from `CDI.current()` belongs to the caller, which has to destroy it (`Instance.destroy`): the
  container does not do it when it stops. An application class with a bounded type variable, necessarily
  `@Dependent`, is in the same case since BUG-20261117-11. Today neither instance is destroyed, so
  `ApplicationClassBeanTest.theInstanceOfABoundedApplicationClassLivesAsLongAsTheOneOfAnyDependentApplicationClass`
  compares 0 with 0: it fails only the day a fix destroys one and not the other. `VaubanCDI.destroy` does nothing:
  the fix goes through the `Instance` that `select` returned, or releases the creational context of the bounded
  path.

## BUG-20261117-11 — An application class with a bounded type variable is created by its constructor, without its injections

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/bean-class-lookup`
- **Module**: `vidocq-runtime-core` (`Vidocq.run(Class, String...)`)
- **Symptom**: `Vidocq.run(App.class, args)` runs the application class as a CDI bean when it is one. A class
  `App<T extends Number>` annotated `@Dependent` is one, and the runtime still creates it with its public no-arg
  constructor: its injected fields are `null`, and nothing says so. Found by the audit of the lookups by raw class
  made for Vidocq/vauban#335 (Vidocq/vidocq#652). The ticket expected the case once #335 is merged: it is there on
  the Vauban of `main` (455067f5) too.
- **Repro**: `ApplicationClassBeanTest.anApplicationClassWithABoundedTypeVariableIsRunWithItsInjections`. The
  application returns 0, the value it gives when its constructor alone made it (`expected: <1> but was: <0>`).
- **Cause**: `Vidocq.instantiateInLayer` asked `CDI.current().select(layerClass)`. The raw class of a bean whose
  class has a bounded type variable is not a type that bean can be looked up by (CDI 4.1 §5.2.4). The lookup is
  unsatisfied, and the runtime falls back to the constructor.
- **Fix**: when the lookup by type finds no bean and the class has type variables, the runtime takes the bean of that
  class among the container's beans that have `@Default`: the one whose bean class is the class and that has a
  parameterized type of that class among its bean types. That type tells it from a producer the class declares
  (`BeanContainer.getBeans`, `resolve`, `getReference`). The qualifiers of the lookup do not change. The beans are
  walked once, when the application starts, and only for a class with type variables. The instance lives as long as
  the one of any `@Dependent` application class (BUG-20261117-12).
- **Also fixed, found by the review**: when the container knows the class but cannot give its bean (a
  `@PostConstruct` or an injection that throws, an ambiguous lookup), the runtime still falls back to the
  constructor, as before, but no longer in silence: a warning names the class and the root cause
  (`ApplicationClassBeanTest.anApplicationClassTheContainerCannotCreateIsReportedBeforeItsConstructorRuns`). The
  reflective reads of the bounded path are listed with their reason in `ArchitectureRules.REFLECTIVE_FALLBACKS`.
- **Not handled**: an enabled alternative that extends a bounded application class is not chosen (the bean class must
  be the class itself); nothing chose it before either.

## BUG-20261117-10 — On the class path, 13 runtime modules are never found: their services are in `module-info.java` only

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/extension-services-class-path`
- **Module**: the extensions `noether-persistence`, `mansart-transactions`, `naming`, `peano-el`, `ibarra-pages`,
  `bodoni-jstl`, `foy-websocket`, `migration`, `flyway-migration`, `liquibase-migration`,
  `mansart-pool-telemetry`, `crac`, and `vaadin-extension-dev`
- **Symptom**: found by a spike that runs the Jakarta EE 11 Platform TCK for Persistence on the assembled runtime, as
  every `vidocq-runtime-tck-*` runner does: on the class path. The deployment of a TCK war starts 6 extensions, without
  `mansart-transactions` and `noether-persistence`, although both jars are on the class path. The war's persistence
  units never start, and its beans that inject an `EntityManager` are left unsatisfied. Twelve other services are
  missing the same way: the `VidocqExtension` of `naming`, `peano-el`, `ibarra-pages`, `bodoni-jstl`,
  `foy-websocket`, `migration` and `crac`, the `SchemaMigrator` of Flyway and Liquibase, Peano's `ProxyUnwrapper`,
  Ibarra's `ServletDeploymentListener` and `CdiContextListeners`, Foy WebSocket's `ServletInitParameters`, the pool
  telemetry's `JdbcConnectionDecorator`, and the Vaadin dev module's `RedefinitionListener`.
- **Repro**: put `vidocq-runtime-noether-persistence-extension` on the class path of an Arquillian test of
  `vidocq-runtime-arquillian` and deploy a war with a `persistence.xml`: the startup log lists the discovered
  extensions without `noether-persistence`. `ClassPathServicesTest` (`vidocq-runtime-architecture-tests`) lists every
  module of the runtime in the same case.
- **Cause**: on the class path, `ServiceLoader` reads `META-INF/services` and ignores the `provides` clauses of
  `module-info.java`. These modules declared their services in `module-info.java` alone, so they work on the module
  path, where Vidocq runs an application, and nowhere else. Kerckhoffs' extension had the file and its own test
  (`ClassPathServicesTest` of that module); nothing checked the others.
- **Fix**: each module lists every service of its `module-info.java` in `src/main/resources/META-INF/services`. The
  architecture tests gain `ClassPathServicesTest`, which reads the `module-info.class` of every `io.vidocq.runtime`
  module on their class path and fails on a `provides` the module's services file does not list. The CRaC extension
  builds in the `crac` profile only, outside the architecture tests: its file is added, and no test reads it.

## BUG-20261117-09 — Under `vidocq:dev`, a page fails with `NoClassDefFoundError` while a recompile runs or while the sources do not compile, and may keep failing afterwards

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-app-classes-loaded-first`
- **Module**: `vidocq-runtime-core` (new `ClassPreload`; `VidocqDevReloadLoop`), `vidocq-runtime-maven-plugin`
  (`VidocqDevMojo`: the `vidocq.dev.preloadClasses` parameter), `vidocq-runtime-dev-agent` (`HotSwap`)
- **Symptom**: found by the probes written for the reload guard (Vidocq/vidocq#631). Under `mvn process-classes
  vidocq:dev`, with the application started and its first page served:
  - Bistro example (`vidocq-runtime-foy-servlet-example`), a save that does not compile. The log says `Compile failed
    (exit 1); keeping previous JVM up.` Of the 72 class files of the output, 25 are left. `GET /`, `/basket` and
    `/kitchen` answer 200. `GET /receipt` answers 500, `NoClassDefFoundError:
    io/vidocq/runtime/examples/servlet/Receipt`, caused by a `ClassNotFoundException`, caused by a
    `NoSuchFileException`. `/about.jsp`, `/specials.jsp` and `/tally.jsp` answer 500 too (`BistroFunctions`,
    `OutTag`). Of the 71 classes of the application, the JVM had not loaded 19 after the start and the first page;
  - Bistro example, a change in one method body, which compiles: `Receipt.class` leaves the output while the
    recompile runs, and `GET /receipt` answers 500 during that time;
  - REST example (`vidocq-runtime-cassini-rest-example`), a change in the body of `StatsService.report()`: `GET
    /api/stats` answers 500 while the recompile runs. The reload is a redefinition in place, `Hot reload - redefined 1
    class in 4 ms, the application keeps running: StatsService`. Once it is over and the class file is back, `GET
    /api/stats` answers 500, three times of three, with `NoClassDefFoundError:
    io/vidocq/runtime/examples/rest/StatsResource_ClientProxy`. `/api/todos` answers 200. The page stays broken until
    a change swaps the layer.
- **Repro**: `AppLayerBrokenSourcesIT`. Start `vidocq:dev` on an application with a REST resource and call nothing.
  Save a source with an error and wait for `Compile failed`. Call the resource: 500. Save the source as it was: the
  output is the one the application runs, nothing is reloaded, and the resource still answers 500.
- **Cause**: three facts together.
  1. The application layer reads a class from the output of the build when the application first uses it:
     `VaubanClassLoader` lists the classes of its archives when the layer is created, and reads the bytes of each
     one at its definition. After the start and a first page, the classes of the other pages, the client proxies of
     the beans no request reached, and the classes used only on some paths are not loaded yet.
  2. `maven-compiler-plugin` (3.15.0), when it finds a change, deletes every file its last compile created before it
     compiles the module again. Javac writes them back at the end of the compile; when javac fails, they stay
     deleted. `vidocq:dev` keeps the application running in both cases.
  3. The JVM records a class resolution that failed (JVMS 5.4.3): the same reference fails again with the same
     error, whether or not the class file is back. Only a new application layer forgets it. A redefinition in
     place, or a recompile whose output did not change, keeps the layer.
- **Fix**: `ClassPreload`. As soon as the dev loop (`VidocqDevReloadLoop`) has installed an application layer, at the
  first start and at each layer swap, it loads every class of the directories of the application path through the
  loader of the layer: `Class.forName(name, false, loader)`. Nothing is left to read from the output later.
  - Loading is not initializing: no static initializer runs, and the application's code starts as before.
  - The directories only: the same roots as the hot swap snapshot of the plugin. No build writes the jars of the
    application path while the application runs.
  - The client proxies the build leaves under `META-INF/vauban/placed/` are loaded too, under their own names: the
    annotation processor writes them during the compile, so they leave the output with the classes. The layer
    defines a placed proxy in the package of its produced type, so this holds for a type the layer defines.
  - A class that cannot be loaded at that time is left to its first use, as before: one whose supertype comes from
    an optional dependency that is absent, or one a recompile removed while the layer started. The pass never fails
    a start.
  - One pass per layer is enough: a class added or deleted swaps the layer (`Later is a new class`), so the classes
    of a layer do not change while it runs.
  - The logger `io.vidocq.runtime.core.VidocqDevReloadLoop` at DEBUG counts the classes and gives the time of the
    pass. BENCH.md (2026-11-17) records what it costs at a start.
  - `-Dvidocq.dev.preloadClasses=false` reads each class when it is first used, as before. The keys under
    `vidocq.dev.` are the build's and are not forwarded to the child, so the goal tells the child itself.
  - A class loaded ahead may be one the JVM cannot link, for instance a class whose verification needs a class of an
    optional dependency that is absent. The JVM links a loaded class before it redefines it and, when it cannot,
    answers `InternalError: class redefinition failed: invalid class`, which is no `LinkageError`. `HotSwap.redefine`
    let it through: it ended the thread of the dev loop, and no later change was applied. It is now a refusal like
    the others, so the layer is swapped: `Hot reload - redefinition refused (class redefinition failed: invalid
    class): restarting on a fresh application layer`. Before this fix that error took a class the application had
    used and failed to link; a class it had never used was only reported not loaded. The review of this fix found it.

  Tests: `ClassPreloadTest` (a class loaded ahead no longer needs its file, and the control without the loading; the
  classes of a real application layer; no class is initialized; a class that cannot be loaded is named and the
  others are loaded; a jar and an absent directory; the order of the names; the module descriptor and `META-INF`;
  the names of the placed client proxies, and the proxy of a type of a real layer, loaded through its loader; a
  link to a directory; the line; the switch), three tests of `VidocqDevReloadLoopTest`,
  `VidocqDevMojoTest.theChildIsToldOnlyWhenTheClassesAreLeftToTheirFirstUse`,
  `PluginDescriptorDevDebugTest.preloadClassesIsABooleanReadingItsPropertyAndDefaultsToTrue`,
  `HotSwapProcessTest.aLoadedClassTheJvmCannotLinkIsRefused` (a real JVM with the agent), and
  `AppLayerBrokenSourcesIT`, a real `vidocq:dev`: the resource answers while the sources do not compile, on the first
  layer and on the layer a swap started; with `-Dvidocq.dev.preloadClasses=false` it answers the 500 above, which
  keeps the premise of the test and the switch from the goal to the application. Against the code without the fix
  the first test fails with that 500.
- **Alternatives not taken**:
  - A copy of the output for the child: `vidocq:dev` would give the child a directory of its own, and copy the
    output there after each recompile that succeeds. It covers everything the child reads, the generated resources
    included, and a rebuild by an IDE or a `mvn clean` as well. It changes where the application runs from
    (`vidocq.app.path`, the static mounts on the class path, the pages, what the dev console shows), and each of
    those would need a check. To decide with the maintainer if the cases under "Not covered" matter in practice.
  - `-Dmaven.compiler.useIncrementalCompilation=false` for the recompiles of the dev loop: the compiler plugin then
    compiles only the sources that changed, and the annotation processors need them all.
  - Classes put back after a compile that fails: the time of the compile itself stays uncovered, and so does the
    resolution the JVM recorded.
- **Not covered**:
  - A resource that an annotation processor writes into the output is deleted with the classes, and is read from
    the output when the application asks for it. The resources Maven copies are not deleted.
  - A layer that starts while a recompile runs: the classes that are out at that time are left to their first use.
    With the hot swap on, `vidocq:dev` waits for the application to acknowledge a reload before the next recompile,
    two minutes at most, so this takes a save made while the application starts for the first time. With the hot
    swap off (`-Dvidocq.dev.hotSwap=false`, or a start that says `Hot swap off: ...`), there is no such wait, and a
    save made during any layer swap does it.
  - The placed client proxy of a type the layer does not define, such as a type of a library under
    `vidocq.app.layer=classes`: the layer cannot load it by its name, and it is left to its first use.
  - `-Dvidocq.dev.hotReload=false` and `-Dvidocq.dev.layer=false`: the child runs without the dev loop, and each
    change restarts the JVM. A class not loaded yet is missing while the recompile runs and while the sources do
    not compile; the restart that follows a compile that succeeds forgets it.
  - A build made by another tool while the application runs, such as an IDE that writes into `target/classes` or a
    `mvn clean`: the classes already loaded stay, a class added since the layer started is not loaded.

## BUG-20261117-08 — A bare `mvn vidocq:dev` builds first and still starts on the old class of a source saved while the last build compiled it, or compiled by an IDE

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-bare-start-class-in-doubt`
- **Module**: `vidocq-runtime-maven-plugin` (`ChangedWhileStopped`, with `SavedWhileCompiling`)
- **Symptom**: the case BUG-20261117-07 listed under "Not covered", and a second one beside it. A source is saved
  while a Maven run compiles it, after javac read it; or an IDE saves a source and compiles it into `target/classes`.
  The next bare `mvn vidocq:dev` sees the change, says `Since the last build, Menu.java changed: building the
  project first (mvn process-classes).` and runs that build, which compiles nothing. In the first case the
  application starts on the code of before the save. In the second it runs the saved code, but the annotation
  processors did not run on it: the generated code is the one of the last build of Maven.
- **Repro**: build a project (`mvn process-classes`), add a method to a source and give the file the time of its
  class, run `mvn vidocq:dev`: the class the application loads has no such method.
  `ContinuousTestingDevGoalIT.aSourceSavedWhileTheLastBuildCompiledItIsCompiledBeforeTheApplicationStarts` does so.
- **Cause**: `maven-compiler-plugin` takes a source for compiled when its class is not older. The class javac wrote
  from the text it read before the save, and the class an IDE compiled after the save, are both not older than the
  source. `ChangedWhileStopped` knows from the record of the compiler that the source changed and starts the build;
  that build then finds nothing stale.
- **Fix**: before it starts the build, `ChangedWhileStopped` deletes the class of each source saved since the
  compiler of the last build listed its sources, when that class is not older than the source. Without that class
  the plugin compiles the module, with the annotation processors. It is the rule of BUG-20261117-03 inside a session
  and of BUG-20261117-05 at the start of this Maven, and the same class applies it, `SavedWhileCompiling`: the log
  names the sources the same way, `Menu.java was saved since the last build started and its class is not older:
  compiling it again.`
  - A class older than its source stays: the compiler plugin replaces it by itself.
  - The class of a source that was not saved since the last build stays.
  - A Maven that cannot be run once a class was deleted stops the start, since the output is no longer the one of
    the last build: `Cannot build the project (…). The class of Menu.java was deleted to be compiled again, so the
    output is incomplete: run "mvn process-classes", then vidocq:dev again.` When no class was deleted it stays the
    warning of BUG-20261117-07, and the application starts on the classes of the last build.

  Tests: `ChangedWhileStoppedTest` (`aSourceSavedWhileTheLastBuildCompiledItLosesItsClassBeforeTheBuild`,
  `aSourceAnIdeCompiledSinceTheLastBuildIsBuiltFirst`, `theClassOfASourceThatDidNotChangeStays`,
  `aMavenThatCannotRunOnceAClassWentStopsTheStart`, `aMavenThatCannotRunOnceSeveralClassesWentNamesTheirSources`),
  `ContinuousTestingDevGoalIT.aSourceSavedWhileTheLastBuildCompiledItIsCompiledBeforeTheApplicationStarts`. With the
  deletion switched off, that integration test fails on the content of the class.
- **Alternatives not taken**: giving the class a time before the one of its source, so that the plugin finds the
  source stale. The deletion is the rule `SavedWhileCompiling` already applies and tests inside a session and at the
  start; a second way for the same case, which writes into the output a time that never was, was not added.
- **Not covered**: a build that left no record, as one whose pom sets `useIncrementalCompilation` to `false`. Only
  the times of the output are known then, and a class that is not older than its source is taken for compiled, as
  before.

## BUG-20261117-07 — A bare `mvn vidocq:dev` starts without what changed while it did not run, and nothing says so

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-bare-start-builds-first`
- **Module**: `vidocq-runtime-maven-plugin` (new `ChangedWhileStopped`; `CompilerOutput`, `SourceWatcher`,
  `VidocqDevMojo`)
- **Symptom**: found by the probe of BUG-20261117-05, in its round under a bare `mvn vidocq:dev`. A source edited
  after the last build and before the command is not compiled: the application starts with the class of that build,
  and no line says so. It runs the old code until a watched file is saved again. The same goes for a source or a
  resource added since, and for a source deleted since, whose class stays in the output.
  `mvn process-classes vidocq:dev`, the command of `vidocq dev` and of the IDE configurations, is not affected. The
  documentation shows the bare command (Getting started, the dev console page, the examples).
- **Repro**: build a project (`mvn process-classes`), append a method to a source, run `mvn vidocq:dev`: the class
  the application loads has no such method.
  `ContinuousTestingDevGoalIT.aSourceEditedWhileVidocqDevDidNotRunIsBuiltBeforeTheApplicationStarts` does so.
- **Cause**: `vidocq:dev` declares no `@Execute` phase, so a bare goal runs no phase of the lifecycle.
  `VidocqDevMojo.ensureCompiled` builds in two cases only: an output without any class (`CompilerOutput.ensureBuilt`,
  BUG-20261030-12) and an output that lacks files the compiler created (`ensureComplete`, Vidocq/vidocq#237). An
  output that is complete and older than the sources starts as it is. BUG-20261117-05 covers the files saved since
  Maven started, not the ones saved before.
- **Fix**: `ChangedWhileStopped`, which `ensureCompiled` calls between the two checks. It reads the record
  `maven-compiler-plugin` leaves of its last run, `inputFiles.lst` under
  `target/maven-status/maven-compiler-plugin/compile/<execution>/`: the plugin writes that list each time it runs,
  compiling or not, after it looked for stale sources and before javac reads any. Two signs then start one
  `mvn process-classes` before the application starts:
  - a watched file (the directories of `vidocq.dev.watchDirs`, the extensions the watcher reports) of a later
    millisecond than that list, saved before Maven started;
  - a source of that list, under one of the project's own source roots, that no longer exists. The compiler plugin
    then compiles the module again and deletes the classes it had created, the one of that source included.

  The log names the files, three at most of each kind: `Since the last build, Menu.java changed: building the
  project first (mvn process-classes).`
  - The time of the record, not the times of the output: an IDE that compiles into `target/classes` writes a class
    newer than the source it saved, and `vidocq:generate` did not run on that class.
  - A file saved since Maven started is left to `SavedWhileStarting` (BUG-20261117-05): one save is built and named
    once. Under `mvn process-classes vidocq:dev` this very Maven wrote the list: no file is in between, and nothing
    more is built.
  - A build that left no list, as one whose pom sets `useIncrementalCompilation` to `false`, is compared by the
    newest file of its output, where `vidocq:generate` writes its stamp at every build.
  - While the build fails, a warning says so, `The project does not build (exit 1): fix the sources, vidocq dev
    builds again on the next change.`, and `vidocq:dev` waits for the next change, as it does for a first build that
    fails. The save that fixes the sources was made since Maven started: it is compiled a second time before the
    application starts, as a save made while the goal started (BUG-20261117-05).
  - A directory that cannot be read, or a Maven that cannot run, is a warning, and the application starts on the
    classes of the last build.

  Tests: `ChangedWhileStoppedTest`, `CompilerOutputTest` (the last compile, and its sources that are gone),
  `SourceWatcherTest.savedBetweenLeavesOutTheFilesOfTheEndAndLater`,
  `VidocqDevMojoSessionTest.aSourceEditedWhileTheSessionDidNotRunIsBuiltBeforeTheChildStarts`,
  `ContinuousTestingDevGoalIT.aSourceEditedWhileVidocqDevDidNotRunIsBuiltBeforeTheApplicationStarts`.
- **Alternatives not taken**:
  - `@Execute(phase = PROCESS_CLASSES)` on the goal, as `vidocq:run` has: a compile error would end Maven before the
    goal, against BUG-20261030-12, and `mvn process-classes vidocq:dev` would run the lifecycle twice.
  - a build at every start that ran no lifecycle: one Maven run for nothing at each start of an unchanged project.
- **Not covered**:
  - a change of the `pom.xml`, which is not watched: the classes are those compiled before it.
  - a file restored with a time before the last build, and a deleted resource: Maven's own build does not see them
    either, and the copy of the resource stays in the output until `mvn clean`.
  - a source saved while an earlier Maven run compiled it, after javac read it: its class is not older, and the
    compiler plugin takes it for compiled, in the build this fix starts too. BUG-20261117-03 covers that save inside
    a session and BUG-20261117-05 at the start of this Maven; here the save and the build are those of an earlier
    command. Covered since by BUG-20261117-08.
  - a file system that keeps whole seconds: a file saved in the second the compiler wrote its list, after it, is not
    later.

## BUG-20261117-06 — An application built from a linked worktree with `vidocq-runtime-parent` names the main checkout's commit in its build identity

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/build-info-linked-worktree`
- **Module**: `vidocq-runtime-parent` (the build identity), `vidocq-runtime-maven-plugin` (`VidocqBuildInfoMojo`,
  `buildinfo/BuildIdentity`, new `GitCommitTime` and `GitCommand`)
- **Symptom**: under `vidocq-runtime-parent`, an application built in a linked worktree (`git worktree add`, the mani
  layout) gets the main checkout's commit in `META-INF/vidocq/build-info/<artifactId>.properties`, with that commit's
  time: the banner, the dev console and `vidocq info` name code the classes were not built from. Measured on a
  `vidocq create --with-parent` application: `d3dd3c62` (the main checkout) for a worktree at `b90c0cde`. An
  application without the parent was right: `vidocq:build-info` follows the worktree's `.git` file.
- **Repro**: `CreateThenBuildIT#anApplicationBuiltFromALinkedWorktreeNamesTheWorktreesCommit` (with the parent):
  commit the created application, add a worktree one commit ahead, `mvn process-resources` there, read
  `git.commit.id.abbrev`.
- **Cause**: the parent wrote the file with `git-commit-id-maven-plugin` 9.2.0, which resolves a worktree's `.git` file
  to the main repository and reads `HEAD` there. `<useNativeGit>true</useNativeGit>` is no fix: it runs `git` in the
  main checkout, so it names the same commit, fails the build on a machine without `git` (`Cannot run program "git"`),
  and writes no commit for a source archive.
- **Fix**: the parent binds `vidocq:build-info` at `initialize`, as the plugin does without the parent, and no longer
  runs `git-commit-id-maven-plugin`: `ApplicationBuildParentAlignmentTest` compares all six executions. The goal now
  also writes `git.commit.time`, which `BuildInfo.committedAt` reads (the date a release jar shows, having no build
  time), from `git show -s --format=%ct <commit>`, as it reads the dirty flag from `git status`: without an answer from
  the `git` command both are left out, and the log line says `…, without the dirty flag and the commit time: the git
  command did not answer`. `vidocq-runtime-it-create` stops staging `git-commit-id-maven-plugin` for the created
  projects. A library module of a multi-module application, which does not declare the plugin, no longer gets the
  file, as without the parent: nothing reads it, the runtime reading the application module's and the bricks'.
  Checked by hand on a multi-module application with the parent out of any repository (two libraries, `package`):
  the build passes with no warning, and one `Vidocq build-info: unversionned (no git repository)` line.
  Found on the way, and fixed since the goal now runs two `git` commands: the 10-second limit never held, on `main`
  too. The output was read to its end before the wait, so a `git` that hung with its output open held the build
  until it ended. `GitCommand` now reads it on a virtual thread while it waits and stops the command at the limit;
  a process the command leaves behind holding its output stops the reading at the limit too.
- **Tests**: `CreateThenBuildIT`, each with and without the parent: the worktree's commit and commit time; a `PATH`
  without `git` (the commit named, no dirty flag, no commit time, the log line); a source archive (`unversionned`).
  Red before the fix: the parent named the main checkout's commit, wrote no commit for the archive and, through JGit,
  a commit time without `git`; without the parent, no commit time and no log line. `BuildIdentityTest` and
  `VidocqBuildInfoMojoTest` for the commit time and the log line. `GitCommandTest`, against a 300 ms limit: a command that
  hangs, and one that leaves a process holding its output, which must still run when the answer comes; both red
  with `main`'s reading, which waited for the `sleep` to end. The second accepts what the command printed or nothing:
  the JDK collects the output at the exit unless the reader already waits in `read` (Linux in CI got the output). An
  interrupted build leaves the command out and keeps its interrupt (red without the `interrupt()` call).
- **Not covered**: this repository's own modules and the fixtures that inherit `vidocq-runtime-build` still get the
  file from `io.vidocq:vidocq-parent`'s `git-commit-id-maven-plugin`, so a local build from a linked worktree still
  names the main checkout's commit there (seen on a plugin jar installed from a worktree at `c53d19a8`: `053f4e8d`).
  `vidocq-parent` cannot bind `vidocq:build-info`, the plugin being built with it: an issue there.

## BUG-20261117-05 — A save made while `vidocq:dev` starts is not applied: the application starts without it, and nothing says so

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-save-while-starting`
- **Module**: `vidocq-runtime-maven-plugin` (new `SavedWhileStarting`; `SourceWatcher`, `SavedWhileCompiling`,
  `HotSwapSession`, `VidocqDevMojo`)
- **Symptom**: found by a probe written for the case BUG-20261117-03 left as not covered. On the Bistro example
  (`vidocq-runtime-foy-servlet-example`) the probe starts `mvn process-classes vidocq:dev`, appends a method to
  `Menu.java` at a chosen moment of the start, and reads `Menu.class` once the application runs. Four rounds of five
  lost the save:
  - saved 0.4 s after the `Compiling` line of the compiler plugin, while javac ran: the class is newer than the
    source and has no such method; the application boots 3.6 s later, and no recompile follows;
  - saved at the banner of the `dev` goal, 5.4 s before the application boots;
  - saved at the `Watching:` line, 0.5 s before the application boots;
  - under a bare `mvn vidocq:dev`, saved at the banner of the goal, 0.8 s before the application boots.

  The fifth save, 1.5 s after `Compiling`, came 0.2 s before the boot, when the dev loop watched already: `Hot reload
  signalled after 6556 ms (layer swap: Menu gained a method savedAtStart1).` A lost save shows nowhere: the
  application runs the old code until a watched file is saved again, a Java source for the save javac missed.
- **Repro**: the state, without the race: build the project, append a method to a source, then stamp the source and
  its class ahead of the clock, the class last. That is what a save leaves when javac read the source before it and
  wrote the class after it. Run `mvn vidocq:dev`: the application starts without the method.
  `ContinuousTestingDevGoalIT.aSourceSavedWhileVidocqDevStartsIsCompiledBeforeTheApplicationStarts` does so. The race
  itself: save a source while `mvn process-classes vidocq:dev` starts, before the line `Child JVM started`.
- **Cause**: `VidocqDevMojo` registers its `SourceWatcher` last, in `runSession`: after the build of the command line,
  `ensureCompiled`, the launch, the dev services, the dev tools and the setup of the tests. The line `Watching: …` is
  printed before the dev services open, seconds before the watch on an application with containers. So a save falls
  in one of three windows that nobody watches:
  1. while the build compiles, the `process-classes` of the command line or the first build of `vidocq:dev`: javac
     read the source before the save and writes the class after it. The compiler plugin takes the source for
     compiled, as in BUG-20261117-03, and no `SavedWhileCompiling` noted that compile;
  2. between the build and the watch: nothing compiles the save;
  3. a resource, in either window: the build copied the file as it was.

  On the Bistro example the second window alone is five seconds; dev services make it longer.
- **Fix**: `SavedWhileStarting`. Once the watcher is registered, it looks for the watched files (the directories of
  `vidocq.dev.watchDirs`, the extensions the watcher reports) whose time is not before the start of Maven
  (`MavenSession.getStartTime()`), in milliseconds. When there is one, `vidocq:dev` recompiles before the application
  starts, and names the files, three at most: `Menu.java was saved while vidocq:dev started: compiling it before the
  application starts.`
  - The watcher is registered first and the files are read next: a later save is the watcher's, and
    BUG-20261117-03 covers the one made while this recompile runs.
  - `SavedWhileCompiling.anEarlierCompileStarted` tells the tracker of the dev loop when the build started. A source
    saved since, whose class is not older, loses its class before the recompile: `Menu.java was saved since the build
    started and its class is not older: compiling it again.` Without it, the compiler plugin would take the source of
    the first window for compiled once more.
  - No margin before the start of Maven: an IDE that saves every file before it runs Maven would pay a recompile at
    each start. A start with no save compiles nothing more.
  - While that recompile fails, a warning says so, `What was saved while vidocq:dev started does not compile (exit
    1): fix the sources, vidocq dev compiles again on the next change.`, and `vidocq:dev` waits for the next change,
    as it does for a first build that fails: there is no application to keep up.
  - `HotSwapSession.compiledAgainBeforeTheStart` moves the base of the hot swap session to the output the child
    loads. Without it, a save taken back once the application runs would compare equal to the first build, and
    nothing would reload.
  - A directory that cannot be read, or a Maven that cannot run, is a warning, and the application starts.

  Tests: `SavedWhileStartingTest`, `SourceWatcherTest.savedSinceListsTheWatchedFilesThatAreNotOlder`, the
  `anEarlierCompile` tests of `SavedWhileCompilingTest`, the three tests of `HotSwapSessionTest` on an output
  compiled before the start, `VidocqDevMojoSessionTest` (a file saved since Maven started is compiled before the
  child; one saved before is left to the build; no child starts while the saves do not compile, and Ctrl+C ends the
  session; the child starts once a later save compiles),
  `VidocqDevMojoTest.noApplicationStartsWhenTheWatchEndsWhileTheSavesOfTheStartDoNotCompile`,
  `ContinuousTestingDevGoalIT`. The same probe with the fix loses
  none of five. The four rounds that lost the save log `Menu.java was saved while vidocq:dev started: compiling it
  before the application starts.`, and the application boots once, with the method. The round saved while javac ran
  logs the second line too: `Menu.java was saved since the build started and its class is not older: compiling it
  again.`
- **Not covered**:
  - a source edited before Maven started and never compiled, under a bare `mvn vidocq:dev`: no phase of the
    lifecycle runs, and `vidocq:dev` builds only when the output has no class at all. The last round of the probe
    showed it before the fix; with the fix, the recompile that the save of that round starts compiles this source
    too, and a start with no save still leaves it. `mvn process-classes vidocq:dev`, the command of `vidocq dev`,
    compiles it. A bug of its own: BUG-20261117-07, fixed since.
  - a Maven that cannot be run for that recompile (no `mvnw` up the directories, no `mvn` on the `PATH`): the
    warning says so and the application starts, but `SavedWhileCompiling` deleted the class of a source saved while
    the build compiled it before Maven was tried, and that class is missing. Every recompile of such a session
    fails the same way. To fix in `SavedWhileCompiling`: a class in doubt made older than its source, not deleted.
  - a file system that keeps whole seconds: a file saved in the second Maven started, after it, can bear a time
    before that start.

## BUG-20261117-04 — Under `vidocq:dev`, a public method added to a view loses the HTTP session the verdict said it would keep

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-verdict-serial-version-uid`, with Foy's half (Vidocq/foy#118, Foy 412ca8b6, Foy BUG-20261117-01)
- **Module**: `vidocq-runtime-maven-plugin` (`SessionVerdict`, `ClassFingerprint`); Foy's session import
- **Symptom**: found by the replay of the dev session handover (#635) on VidocqTools/vaadin-on-vidocq, with Vidocq
  67e17d5a, Foy 18ff8067 and Vauban 0e39add0. A `public int count()` added to the application's `@SessionScoped`
  `Serializable` bean, then a `public String title()` added to `MainView`: the log says `Hot reload - restarting on a
  fresh application layer: Visits gained a method count`, no `HTTP sessions not kept` line, then `WARN ... Importing
  the HTTP sessions dropped 1 session(s) that could not be restored, as new ones start instead; first cause: class
  io.vidocq.tools.vaadin.Visits` (then `...MainView`). The page silently starts a new session, and the bean's counter
  is back at 1. A listener appended in the same view (a private lambda method) keeps the session, and so did every
  case of the earlier replay.
- **Repro**: `class Visits implements Serializable { private int clicks; public int next() { return ++clicks; } }`,
  then the same with `public int count() { return clicks; }`: `serialver` gives -2180439236505541769, then
  -3816310884943761011.
- **Cause**: neither class declares a `serialVersionUID`, and the default one Java computes hashes the non-private
  methods and constructors. The read of the exported session refuses a stream class whose UID differs from the local
  class's (`InvalidClassException`), and Foy drops the session. The verdict's rules looked at the fields, the
  supertypes, the lambdas and the anonymous classes, never at the UID, and the guides did not list the case: the most
  common change behind a swap, a method added to a view, lost the session with a WARNING that named the class alone.
- **Fix**: two halves, agreed between the sessions.
  - Foy's import reads a class whose UID moved with the new class's descriptor when neither version declares a
    `serialVersionUID`, is `Externalizable` or declares `writeObject`, and the serializable fields, the nearest
    serializable superclass and the interfaces are the same, read from the loaded classes; otherwise it drops the
    session and its WARNING gives the reason.
  - The verdict predicts it: `ClassFingerprint.defaultSerialVersionUid()` computes the default UID from the class file
    (Java Object Serialization Specification §4.6), and `serialVersionUidDifference` drops, with its reason, every
    case Foy's import would drop: `changed its serialVersionUID from 1 to 2`, `declared serialVersionUID 1, not its
    default ...`, `no longer declares serialVersionUID 1`, `changed its default serialVersionUID and declares
    writeObject` (or `is Externalizable`, or `has a serialVersionUID field that is not static final`). A class that
    stopped being `Serializable` drops too (`changed its interfaces`). Records and enums are left out.

  Tests: `ClassFingerprintTest.theDefaultSerialVersionUidIsTheOneJavaSerializationComputes` (ten classes against
  `ObjectStreamClass.lookup`, among them a protected nested class, an `assert`, interfaces with and without methods
  and a bridge method), `theUidJavaReadsIsTheDeclaredOneOrElseTheDefaultOne`, and in `SessionVerdictTest` a test per
  reason, plus the cases that keep: a method added with or without a declared UID, a UID declared with the old
  default value, a private method added beside `writeObject`, a record and an enum.
- **Not covered**: a `serialPersistentFields` that changed (Foy drops the session when the UIDs differ too; the verdict
  sees no field change); a `serialVersionUID` computed at class initialization rather than a constant (the verdict
  cannot read its value, and takes two such declarations as equal).

## BUG-20261117-03 — A source saved while a recompile or a test run compiles it is not compiled: the save shows only once another Java source is saved

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-save-during-recompile`
- **Module**: `vidocq-runtime-maven-plugin` (new `SavedWhileCompiling`; `VidocqDevMojo`, `TestRunner`, `TestOutputs`,
  `ContinuousTesting`, `VidocqTestMojo`)
- **Symptom**: found by the reload probe of #631 on the Bistro example (`vidocq-runtime-foy-servlet-example`). The
  probe appends a method `saveA1` to `Menu.java`, waits for the recompile of that save to reach javac, and appends
  `saveB1`. The dev loop logs `Hot reload signalled after 7703 ms (layer swap: Menu gained a method saveA1).`, then,
  for the second save, `Recompiled in 2108 ms: the compiled output did not change, nothing reloaded.` The source has
  both methods and is stamped 02:43:00.732; `Menu.class` was written at 02:43:01.198 and has `saveA1` only. No later
  recompile compiles it: the application runs without `saveB1` until a Java source is saved again. Four rounds of
  four lost the second save. The test runs lose a save the same way: on a small module, the fixture of
  `ContinuousTestingDevGoalIT` with its two main classes and two test classes, two rounds of eight left
  `GreeterTest.class` without the second method, and the next runs tested the old class. On the tests of the Bistro
  example, whose runs take seconds more, the same probe lost none of five.
- **Repro**: the state, without the race: build the example, append a method to `Menu.java`, then `touch` its
  `Menu.class`, which makes the class newer than the source, as javac leaves it when it read the source before the
  save. Run the recompile (`mvn process-classes -DskipTests -Dvidocq.moduleinfo.dev=true`): `Nothing to compile - all
  classes are up to date.`, and `javap` shows the class without the method. The race itself: under `vidocq:dev`,
  save a source, and save it again while the recompile of the first save runs, once javac has started.
  `ContinuousTestingDevGoalIT` makes the state in a running `vidocq:dev`: it saves `Greeter.java` with the time of
  the class the previous recompile left. Against the plugin of `main` the dev loop answers `Recompiled in 1433 ms:
  the compiled output did not change, nothing reloaded.`
- **Cause**: the compiler plugin (3.15.0) takes a source for compiled when its class is not older, in milliseconds.
  javac reads every source first and writes the classes last, seconds later on a real module: seven seconds in the
  round above, between the start of the recompile (02:42:54.198) and the class (02:43:01.198). A source saved in
  between is older than the class javac writes from what it read before the save. The watcher reports the save and
  the dev loop recompiles, but the plugin finds every class up to date and compiles nothing. Saving another Java
  source repairs it, as the plugin then compiles the whole module.
  - Nothing stops a recompile of the dev loop: every save made while one runs, after javac read the source, is lost.
  - A change stops a test run, and a stopped run loses what it wrote (BUG-20261117-01), which covers the save. But a
    run that ends before the watcher reports the save is not stopped: in the two lost rounds the log has two
    complete runs and no `Tests: cancelled`, and the second run found the class newer than the source.
- **Fix**: `SavedWhileCompiling`, one for each output a compile builds. It notes the time of each source of the
  project (the compile source roots outside the build directory) when a compile starts, and the time of each class
  when the compile ends. When the next compile starts, a source whose time changed since the previous start, and
  whose class is the one the previous compile left, not older than the source, loses its class. The compiler plugin
  then finds a source without a class and compiles the module, as it does for any change. An info line names the
  sources, three at most: `Menu.java was saved since the previous recompile started and its class is not older:
  compiling it again.`
  - The dev loop: `VidocqDevMojo.recompile` calls it around each recompile, for the main sources.
  - The test runs: `TestRunner.launch` calls it before the Maven of each run, and once the run ended, for the
    outputs the runs build (`TestOutputs.Output`: the test sources under `vidocq:dev`, the main ones too under
    `vidocq:test`). Its lines start with `Tests: `.
  - A class written after the compile ended is another build's, an IDE that builds on save for instance: it was
    compiled from the save, and it stays. Without that rule, every save in such an IDE would cost a compile of the
    whole module.
  - A compile whose end is unknown (the wait for it was interrupted, its classes could not be read) leaves every
    class in doubt.
  - A source saved before javac read it is compiled a second time: the output does not change and nothing reloads.
  - A file time that cannot be read, or a class that cannot be deleted, is a warning, and the compile goes on:
    `Cannot tell whether a source was saved since the previous recompile started (…): if a save does not show, save
    the file again.`

  Tests: `SavedWhileCompilingTest`, `VidocqDevMojoTest.aSaveMadeWhileTheRecompileRunsIsCompiledByTheNextRecompile`
  and `TestRunnerTest.aSaveMadeWhileARunCompilesIsCompiledByTheNextRun` (a Maven that writes the class after the
  source was saved again), `TestOutputsTest.theNextRunCompilesTheTestSourceSavedWhileARunCompiledIt` and
  `underVidocqTestTheMainSourcesAreTheRunsToo`, the `testOutputs` tests of the two mojos, `ContinuousTestingDevGoalIT`.
  The same probes with the fix: the dev loop loses none of four, and each round logs the line above, then `layer
  swap: Menu gained a method saveB1`; the test runs lose none of eight: five rounds log `Tests: GreeterTest.java was
  saved since the previous run started and its class is not older: compiling it again.`, and a change stopped the
  run of the three others.
- **Not covered**: a source saved while `vidocq:dev` starts, during the build that precedes the dev loop: nothing
  noted its time, and the watcher is not there yet. Observed since, and fixed: BUG-20261117-05.

## BUG-20261117-02 — Under `vidocq:dev`, a recompile that compiles nothing restarts an application with pages and Peano accessors: `layer swap: module-info changed`

- **Date**: 2026-11-17
- **Status**: FIXED in Ibarra (IBARRA-159, Vidocq/ibarra#93, in the SNAPSHOT since `0.4.0-20261117.021301-14`). Here,
  the guard: `IbarraPagePrecompilationTest`.
- **Module**: Ibarra `ibarra-compiler` (the page index among the `provides` of the module descriptor); seen through
  `vidocq-runtime-maven-plugin` (`IbarraPagePrecompilation`, `PeanoAccessorRegistration`)
- **Symptom**: found by the save probe of BUG-20261117-01 on the Bistro example
  (`vidocq-runtime-foy-servlet-example`: Foy, Ibarra pages, Peano accessors). The probe saves `Menu.java` twice in a
  row. The recompile of the first save compiles both and swaps the layer (`layer swap: Menu gained a method saveA2`).
  The recompile of the second save compiles nothing, and restarts the application all the same: `Hot reload signalled
  after 11216 ms (layer swap: module-info changed).` Of 15 recompiles, 7 swapped the layer for that reason alone. No
  source had changed, and the module declares the same services.
- **Repro**: `IbarraPagePrecompilationTest.the_pages_and_the_peano_registration_run_again_leave_the_descriptor_as_it_was`:
  a module with a page that has an EL expression and a Peano accessor provider; run the two steps of
  `vidocq:generate` that complete the descriptor, `IbarraPagePrecompilation.apply` then
  `PeanoAccessorRegistration.apply`, then both again, as a recompile that compiles nothing does. Against the Ibarra
  SNAPSHOT of before the fix: `the services the module provides, in the file's order ==> expected: <[PageIndex,
  AccessorProvider]> but was: <[AccessorProvider, PageIndex]>`.
- **Cause**: the compiler plugin leaves `module-info.class` alone when it compiles nothing, so the steps of
  `vidocq:generate` run again on the descriptor they completed. Ibarra's page compilation then moved the `provides` of
  its page index to the end of the list, after the accessor provider that Peano's registration had added behind it:
  the same module, other bytes. `vidocq:dev` compares the compiled output by content, and a `module-info.class` that
  changed swaps the layer.
- **Fix**: in Ibarra, the page index keeps its place among the `provides` (Vidocq/ibarra#93). Nothing changes in the
  runtime: the test above keeps the two steps stable together, byte for byte. With the fixed compiler, the same probe
  on the Bistro example: 20 recompiles, 9 of them compile nothing and each says `Recompiled in 2422 ms: the compiled
  output did not change, nothing reloaded.`; no `module-info changed`.

## BUG-20261117-01 — Under `vidocq:dev`, a save just after a reload is not applied: `Missing compiled Peano accessor provider`, then every recompile fails

- **Date**: 2026-11-17
- **Status**: FIXED on `fix/dev-tests-leave-main-output`
- **Module**: `vidocq-runtime-maven-plugin` (`TestRunner`, `ContinuousTesting`, `VidocqDevMojo`, `VidocqTestMojo`, new
  `TestOutputs`)
- **Symptom**: found by the reload probe of #631 on the Bistro example (`vidocq-runtime-foy-servlet-example`). The
  probe appends a method to `Menu.java` as soon as each boot serves. Reloads 1 to 4 swap the layer; at the fifth the
  dev loop logs `vidocq:generate failed: Missing compiled Peano accessor provider
  io.vidocq.runtime.examples.servlet.Tally__PeanoAccessors`, then `Compile failed (exit 1); keeping previous JVM up.`
  The save is not applied, and a recompile fails the same way, after a resource change for instance, until a Java
  source is saved again. What the project held then: `target/vidocq-dev-tests.json` says
  `{"state":"cancelled","trigger":"change","startedAt":"2026-11-16T23:13:50Z","durationMillis":1691,…}`; 37 classes
  of `target/classes` were written two seconds after that start, by the cancelled run; `Tally__PeanoAccessors.class`
  is missing, its source is there; the compiler plugin's `createdFiles.lst` is the recompile's of a second before.
- **Repro**: the state, without the race: build the example, delete `Tally__PeanoAccessors.class`, run the recompile
  (`mvn -q process-classes -DskipTests -Dvidocq.moduleinfo.dev=true`): `Nothing to compile - all classes are up to
  date.`, then the `vidocq:generate` failure. The other half: touch a main source and run the lifecycle of a test run
  (`mvn -o process-test-classes -Dvidocq.dev.devServices=false`): `Recompiling the module because of changed source
  code. Compiling 23 source files`. The race itself: under `vidocq:dev` with continuous testing, save a main source
  within about a second of the end of a reload.
- **Cause**: a test run is `mvn process-test-classes surefire:test`, and that lifecycle compiles the main sources,
  copies the main resources and runs `vidocq:generate`. In the dev loop the recompile has done it before each reload,
  so a run finds nothing to do, unless a save lands between the recompile and the run: the run then compiles the main
  sources itself. The change stops the run (`TestRunner.destroyTree`) while javac writes its classes, and javac writes
  the classes of the sources an annotation processor generated last. The output then has the class of every
  hand-written source, newer than its source, and not `Tally__PeanoAccessors.class`. The compiler plugin (3.15.0)
  compiles a source when its class is missing or older: the recompile compiles nothing, and `vidocq:generate` fails on
  the missing class. A run stopped in its test compile can leave `target/test-classes` in the same state, and so can
  a run of `vidocq:test`, the only build of that goal, in both outputs; neither was observed.
- **Fix**: two rules.
  - Under `vidocq:dev` the recompile alone builds `target/classes`: a run gets `-Dmaven.main.skip=true
    -Dmaven.resources.skip=true -Dvidocq.generate.skip=true` (`TestOutputs.ofVidocqDev`). The one file a run still
    writes there is the build information (`META-INF/vidocq/build-info/`), which every Maven run rewrites.
  - A run that a change or a close stopped loses what it wrote: before the next run, `ContinuousTesting` has its
    launcher delete the files modified since the run started, two seconds earlier included for a file system with
    coarse times, in the outputs the runs build (`TestOutputs.discard`: `target/test-classes`, and `target/classes`
    under `vidocq:test`). The next run finds sources without a class and compiles them all.

  Tests: `TestOutputsTest`, `TestRunnerTest.underVidocqDevARunLeavesTheMainOutputToTheRecompile`,
  `ContinuousTestingTest.aRunThatAChangeStoppedIsDiscardedBeforeTheNextRunStarts` and the three beside it,
  `VidocqTestMojoTest.theMavenOfARunIsToldToSkipNothing`, `ContinuousTestingDevGoalIT` (a main class older than its
  source stays as it is across a test run). On the Bistro example, with the fix: eight saves, each as soon as the
  boot serves, give eight layer swaps and no failed recompile; with every file of both outputs deleted, the worst a
  discard can do, a run builds the same 96 and 10 files again and its 9 tests pass.
- **Not covered**: a Maven stopped from outside the dev loop, a Ctrl+C in the middle of a recompile or of a
  `mvn compile`, can leave the same output; `mvn clean`, or saving a Java source, repairs it.

## BUG-20261116-11 — A hot reload keeps the layer of an application that served a file of a static mount on the class path

- **Date**: 2026-11-16
- **Status**: FIXED in Chappe (CHAPPE-061, Vidocq/chappe#87, in the SNAPSHOT since `0.4.0-20261116.232917-15`). Here,
  the guard that found it: `AppLayerReloadLeakIT` (Vidocq/vidocq#631).
- **Module**: Chappe `chappe-api` (`StaticFileHandler`); seen through `vidocq-runtime-chappe-webserver-extension`
  (`StaticMountHandlerProvider`)
- **Symptom**: under `vidocq:dev`, each layer swap of an application with a static mount on the class path
  (`vidocq.http.mount.<name>.type=static`, `.classpath=...`) keeps the layer it discards, once the mount served one
  file during that boot. After 5 reloads the child JVM holds 6 `VaubanClassLoader` where one is expected: every
  class of the application, its beans and its generated code stay, for each reload, until the JVM stops. Nothing is
  logged: the reset names leaked threads, and no thread is involved.
- **Repro**: `./mvnw -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-app-layer verify
  -Dit.test=AppLayerReloadLeakIT` against Chappe before the fix: `6 application layers are alive after 5 reloads,
  one is expected`. By hand: `vidocq:dev` on the fixture `src/it/fixture/reload`, add a resource method five times,
  load `/index.html` after each boot, then `jcmd <pid> GC.class_histogram` and read the row of
  `io.vidocq.vauban.classloader.VaubanClassLoader`.
- **Cause**: found by elimination, then by a test in Chappe. The same five reloads with the REST and configuration
  requests alone leave one layer; with the static request alone, six. `StaticFileHandler` cached the index of each
  class loader in a static map keyed by the loader (`StaticIndex.PER_LOADER`), filled at the first class path
  lookup and never emptied; the mount is built on the context class loader, which is the application layer's.
  The field was on the list of latent leaks of 2026-11-02 (vidocq-workspace#40, item 26), "none held a layer in the
  applications measured": the audit application had served no static file.
- **Fix**: in Chappe, each classpath source owns its index and no static is left (CHAPPE-061). Nothing to change in
  the runtime. `AppLayerReloadLeakIT` stays as the guard: REST with a JSON body, a configuration value and a static
  mount, five swaps, one layer loader alive; `AppLayerDevIT` is its control (a thread keeps the discarded layer,
  the count reads two).

## BUG-20261116-10 — The build of `main` fails when the Grafana LGTM container of `OtelDevServiceTest` needs more than its three minutes

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/app-class-lifecycle`, by the wait: `OtelDevService.STARTUP_TIMEOUT` is six minutes,
  twice the three that ran out. A stack that answers sooner is used at once, so a quiet build does not wait longer.
  No reproduction on demand: the proof is the next builds of `main` on runner `6c935f1a`. The Keycloak dev service
  keeps its three minutes, since no build has seen it run out.
- **Module**: `vidocq-runtime-devservice-otel` (`OtelDevService.container`, `OtelDevServiceTest`)
- **Symptom**: `CI / build-and-deploy (push)` fails in `vidocq-runtime-devservice-otel`, so that commit publishes no
  SNAPSHOT: `OtelDevServiceTest.startsTheStackAndPublishesAnEndpointThatAcceptsTraces:113 » ContainerLaunch Container
  startup failed for image grafana/otel-lgtm:0.37.0`, caused by `Timed out waiting for URL to be accessible
  (http://172.17.0.1:33737/api/health should return HTTP [200])` and, under it, `java.net.SocketException: Unexpected
  end of file from server`. The test class took 185.9 s.
- **Repro**: none on demand. What the logs of the builds of `main` of 2026-11-16 say, per runner (time of the test
  class):

  | Runner | Platform | Job image | `OtelDevServiceTest` | Runs |
  |---|---|---|---|---|
  | `6c935f1a` | amd64 | `node:24.22.1-trixie` | 75.5 s, 92.6 s, then 185.9 s and the failure | 4740, 4698, 4846 |
  | `0908d017` | amd64 | `node:24.22.1-trixie` | 12.9 s, 48.3 s, 63.4 s | 4762, 4730, 4659 |
  | `f924a289` | arm64 | `node:24.22.1-trixie` | 2.9 s, 3.9 s, 4.0 s | 4715, 4675, 4812 |
  | `ababeffb`, `9d388c1b` | amd64 | `node:20-bookworm` | one test skipped, 0.8 s to 1.3 s | 4795, 4781, 4667 |

  The skipped test is the container test, the only one with an assumption: it skips when Docker is not available.
  In run 4846 the build took 8 min 48 s from the start of the reactor to this test; runs 4740 and 4698, on the same
  runner, took 4 min 29 s and 4 min 42 s. Two pull requests had been merged in the same minute (#630 here and
  Vidocq/vauban#318), and their builds ran together.
- **Cause**: `OtelDevService.container` waits three minutes for Grafana's `/api/health`
  (`withStartupTimeout(Duration.ofMinutes(3))`). On runner `6c935f1a` the stack needs 75 s to 93 s in a quiet build;
  in a build that ran about twice as slow, it did not answer within the wait. This is a hypothesis from the timings
  above: the container's own log was not kept.
- **Not decided**: a longer wait (the Keycloak dev service waits three minutes too), fewer jobs at once on that runner,
  or nothing beyond not merging in two repositories in the same minute. Two runners skip the container test, so what
  a build of `main` proves depends on the runner that takes it: the runners are Vidocq/ci#121.

## BUG-20261116-09 — A swap cannot keep the HTTP session of an open Vaadin page: the Vaadin dev companion is not serializable

- **Date**: 2026-11-16
- **Status**: FIXED on `feat/dev-session-handover`
- **Module**: `vidocq-runtime-vaadin-extension-dev` (`OpenUis`, `DevServiceInit`, `RedefinedEvents`)
- **Symptom**: found while replaying the session handover on VidocqTools/vaadin-on-vidocq. A swap the plugin vouched
  for (`MainView` gained a lambda) keeps no session: Foy logs `HTTP session ... cannot move to the new deployment: its
  attribute com.vaadin.flow.server.VaadinSession.com.vaadin.flow.server.startup.ServletDeployer, of class
  com.vaadin.flow.server.VaadinSession, cannot be written (java.io.NotSerializableException:
  io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev.OpenUis). The session ends here.`
- **Repro**: `vidocq:dev` on vaadin-on-vidocq with the Vaadin `-dev` companion, open the page, append a listener to
  `MainView`.
- **Cause**: `OpenUis.opened` added a detach listener to each UI, a lambda that captured the `OpenUis`: the UI, and so
  the Vaadin session, held an object of the companion that cannot be written. A second gap stood behind it: a page the
  swap restored never runs `uiInit` in the new layer, so a later redefinition would not have refreshed it.
- **Fix**: the UI holds no listener of the companion. `OpenUis` keeps the UIs weakly and leaves out the closing ones;
  it reads a restored page's Vaadin session through the application's Vaadin service (`findVaadinSession`, only when
  the HTTP session holds it, so none is created) when the page opens its event stream again, its first request to the
  new deployment (`OpenUisTest`, `DevServiceInitTest`, `RedefinedEventsServletTest.theStreamOfARestoredPageOpensItsUis`).

## BUG-20261116-08 — Every start of `vidocq:dev` on a Vaadin application warns that the event stream is resolved by reflection

- **Date**: 2026-11-16
- **Status**: FIXED on `feat/dev-hotswap-listeners` (Vidocq/vidocq#629)
- **Module**: `vidocq-runtime-vaadin-extension-dev` (`RedefinedEvents`, `RedefinedEvents.Initializer`)
- **Symptom**: found while replaying the session handover (6c) on VidocqTools/vaadin-on-vidocq. Each start, and each
  swap, logs two warnings: `foy: io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev.RedefinedEvents$Initializer
  resolved by reflection (module io.vidocq.runtime.extensions.jakartaee.web.vaadin.dev does not open ... to module
  io.vidocq.foy.core); add foy-processor to the annotation processor path or open the package to io.vidocq.foy.core`,
  and the same for `RedefinedEvents`. The replay of #629 itself had them too, unnoticed.
- **Repro**: `vidocq:dev` on vaadin-on-vidocq with the Vaadin `-dev` companion of #629.
- **Cause**: the `-dev` companion was compiled without `foy-processor`, so Foy found no generated component for its
  servlet and initializer, and the package is not open to `io.vidocq.foy.core`.
- **Fix**: `foy-processor` on the module's processor path, `foy-api` provided, `requires static io.vidocq.foy.api`
  (`FoyComponentsTest`: both classes have their `$$FoyComponent`).

## BUG-20261116-07 — With an open Vaadin page, each swap and each stop of `vidocq:dev` waits two seconds for the event stream

- **Date**: 2026-11-16
- **Status**: FIXED on `feat/dev-hotswap-listeners` (Vidocq/vidocq#629)
- **Module**: `vidocq-runtime-vaadin-extension-dev` (`RedefinedEvents`), `vidocq-runtime-core` (`VidocqBootstrap`,
  `VidocqDevReloadLoop`, `RedefinitionListeners`), `vidocq-runtime-devconsole-spi` (`RedefinitionListener`)
- **Symptom**: found while replaying the session handover (6c) on VidocqTools/vaadin-on-vidocq. With one page open,
  each swap logs `The undeploy stopped waiting for the requests still in service (1), 2 010 ms after it began: the
  filters and servlets are destroyed while they run`, then `previous deployment stopped in 2045 ms`; Ctrl+C waits
  the same. The (c) replay log had the same lines.
- **Repro**: `vidocq:dev` on vaadin-on-vidocq, open the page, add a field to `MainView`.
- **Cause**: the page keeps the event stream of `RedefinedEvents` open: a request in service. Foy's undeploy waits up
  to two seconds for the requests in service (Servlet 6.1 section 2.3.4, Foy BUG-20261115-03) before it destroys the
  servlets, and `RedefinedEvents.destroy()` was what ended the streams. The CDI `Shutdown` event comes too late to
  help: `VidocqBootstrap.stop()` closes the container after the extensions stopped, Foy's undeploy included.
- **Fix**: `RedefinitionListener.stopping()`, a default method, is called before each stop of the application while
  it still serves requests: the dev reload loop gives each boot a task, `VidocqBootstrap.beforeStop`, that runs first
  in `stop()`, whoever stops it (the swap, the JVM hook of Ctrl+C, `Vidocq.shutdown()`). The Vaadin listener ends
  the open streams there (`BeforeStopTest`, `RedefinitionListenersTest`, `VidocqDevReloadLoopTest`,
  `RedefinedEventsServletTest.theListenerEndsTheStreamsBeforeTheApplicationStops`).

## BUG-20261116-06 — A jlink image does not start when a producer returns a library class with a package-private method

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/image-placed-proxies`
- **Module**: `vidocq-runtime-maven-plugin` (`VidocqJlinkMojo`, new `StagedProxies`)
- **Symptom**: an application has a producer of a normal scope that returns a class of a library, and that class has a
  package-private or protected method. It starts under `vidocq:dev`, `vidocq:run` and as a `vidocq:package`
  distribution. Its `vidocq:jlink` image stops at the start, and so do the `vidocq:jpackage` application and the
  `vidocq:docker` image of the `jlink` kind built on it:
  `WARN OpensApplier: Vauban needs [acme.gadget] of acme.gadget opened to io.vidocq.vauban.core for a runtime producer
  proxy, and will not do it on its own`, then `DeploymentException: No client proxy can be generated for
  acme.gadget.Gadget, produced by acme.placed.GadgetProducer with the normal scope @ApplicationScoped: Vauban cannot
  define a class in package acme.gadget (not opened to io.vidocq.vauban.core)`. Found while checking the points
  Vidocq/vidocq#580 left unproven, not reported by a user.
- **Repro**: the `placed` module of the fixture of `vidocq-runtime-it-app-layer`: a library `acme.gadget` whose
  `Gadget` has a package-private `label(String)`, and an application with `@Produces @ApplicationScoped Gadget`.
  `mvn package vidocq:jlink && target/dist/bin/app-layer-placed`.
- **Cause**: only a class of the library's own package can be the client proxy of such a class. Vauban's processor
  ships that proxy in the application jar (`META-INF/vauban/placed/`) and lists the type in
  `META-INF/vauban/required-opens.list`. Under the application layer, Vauban's class loader defines the proxy in the
  library's package at each start. An image has one layer, the JVM's own (BUG-20261114-11): no loader places the
  proxy, the container falls back to generating it at the start, and that needs the package opened.
- **Fix**: `vidocq:jlink` does the placement at build, after it staged the modules and before it promotes their
  `provides` (`VidocqJlinkMojo.placeProxies`, `StagedProxies`). For each listed type it generates the proxy, and the
  `_VaubanComponents` that provides it, into the staged copy of the library, with the engine of
  `vauban:enhance-dependencies` (`DependencyEnhancer`). It gives the versioned descriptors of a multi-release jar the
  same `provides` and `requires`, which that engine writes into the root descriptor only (Vauban BUG-20261116-03; the
  step goes once the build reads a Vauban that has the fix). It then takes the type off
  the staged placement manifest, so the start has nothing to place and logs no warning. The build logs
  `<jar>: client proxy of <type> added to its package`. A type it cannot place stays listed, with a build warning
  (`client proxy left to the container at the start: …`): a class whose constructors are all private, a library that
  already holds generated Vauban components in that package, a class that refers to a type the image does not hold.
  One difference with the application layer was left, measured by the fixture and documented (`#jlink-differences` of
  the plugin page): the proxy of the image called a constructor of the library's class with default values, where the
  proxy of the layer runs none. BUG-20261118-91 removed it. Tests: `StagedProxiesTest`,
  `VidocqJlinkMojoStagingTest.aLibraryGetsTheClientProxyOnlyItsPackageCanHold`,
  `AppLayerImageIT.theImageHoldsTheProxyOfAThirdPartyClassInItsPackage` (red before the fix: the image exits with the
  messages above) and its control under the application layer,
  `AppLayerRunIT.theApplicationLayerHoldsTheProxyOfAThirdPartyClassInItsPackage`.

## BUG-20261116-05 — The first change of each `vidocq:dev` session of a Vaadin application swaps the layer

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/dev-build-files`
- **Module**: `vidocq-runtime-maven-plugin` (`HotSwapClassifier`, `StartTimeDeclarations`), the start-time
  declarations of `vidocq-runtime-vaadin-extension`
- **Symptom**: found while replaying the hot swap on VidocqTools/vaadin-on-vidocq. The first change of a session, even
  a comment, logs `Hot reload - restarting on a fresh application layer:
  META-INF/VAADIN/config/flow-build-info.json was removed`; the following changes are classified as they should be. A
  method body change then swaps and loses the session instead of being redefined.
- **Repro**: in vaadin-on-vidocq (`build-frontend` bound to `process-classes`, as the Vaadin guide says), run
  `vidocq:dev`, then add a comment to `MainView`.
- **Cause**: Vaadin's `build-frontend` (flow-maven-plugin 25.3) calls `File.deleteOnExit()` on its token file after
  `updateBuildFile`, unconditionally. The first build of `vidocq:dev` runs in the Maven JVM that stays for the whole
  session, so the file is in `target/classes` when the first snapshot is taken; each recompile is a Maven run of its
  own, which deletes the file when it ends. Two `process-classes` runs of a fresh copy leave no such file.
- **Fix**: a `build-files` key in `META-INF/vidocq/dev/start-time.properties` names files of the class output that a
  build tool writes for its own use; the classifier leaves them out, as it does `META-INF/vidocq/build-info/`. The
  Vaadin extension declares `META-INF/VAADIN/config/flow-build-info.json`.

## BUG-20261116-04 — The 500-node graph layout test fails on a CI runner: 3.2 s for a one-second bound

- **Date**: 2026-11-16
- **Status**: FIXED on `feat/noether-persistence-dev` (Vidocq/vidocq#625)
- **Module**: `vidocq-runtime-devconsole-extension` (`GraphCoreTest.fiveHundredNodesLayOutUnderOneSecond`)
- **Symptom**: the `sonar` job of #625 (run 4745, runner Villejuif-runner1) failed on
  `fiveHundredNodesLayOutUnderOneSecond`: 3.2 s for the test, whose bound is one second for the measured layout. The
  `producer` job of the same commit passed it, and so do local builds: 0.23 s without the coverage agent, 0.55 s with
  it.
- **Repro**: run `GraphCoreTest` under `-Pquality` on a loaded, slower machine; the time scales with the machine.
- **Cause**: the test bounds a wall-clock time. It runs the page's script in GraalJS's interpreter, under the JaCoCo
  agent in the quality profile, on shared runners: a time that depends on the machine. The layout itself grows in
  proportion to the graph (50, 100, 250 and 500 nodes with three edges each: 6, 13, 32 and 63 ms locally, 11, 21, 60
  and 120 ms under JaCoCo).
- **Fix**: `theLayoutTimeGrowsInProportionToTheGraph` replaces it. Five times the nodes and edges, 500 against 100,
  must cost less than ten times as much, best of three runs each. A layout quadratic in them fails it: a loop over
  every pair of edges added to `graphLayout` gave 33 ms against 566 ms, 17 times.

## BUG-20261116-03 — A distribution unpacked by one user is not usable by another: rewritten jars `rw-------`, launcher `rwxr--r--`

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/dist-file-modes`
- **Module**: `vidocq-runtime-maven-plugin` (`EnrichedJars.writeJar`, `JpmsPatches.enrich`, `SharedResources.rewrite`,
  `VidocqPackageMojo` launcher)
- **Symptom**: after BUG-20261116-02, the VAADIN session checked the `-dist.zip` of vaadin-on-vidocq with `zipinfo`:
  the two jars Foy enriches (`atmosphere-runtime-3.0.5.slf4jvaadin1.jar`, `flow-server-25.3.3.jar`) are `rw-------`, in
  the archive and in `target/…/app/`, and `bin/vaadin-on-vidocq.sh` is `rwxr--r--`. Unpacked by root in `/opt` and run
  by a service user, the launcher cannot be executed and those jars cannot be read.
- **Repro**: `vidocq build && zipinfo target/*-dist.zip | grep -E 'rw-------|rwxr--r--'`.
- **Cause**: the jars the plugin rewrites are written through `Files.createTempFile`, which creates `rw-------`, and
  moved in place with that mode; `File.setExecutable(true)` adds the execute bit for the owner only.
- **Fix**: `StagedFiles.temporarySibling` creates the temporary file with `Files.createFile`, so it has the mode of a
  plain new file under the user's umask; `StagedFiles.makeExecutable` gives the execute bit to each class of user that
  can read the launcher (`rw-r--r--` becomes `rwxr-xr-x`, no right for who cannot read it). Tests: `StagedFilesTest`.
  Not fixed: the external attributes of the ZIP entries carry no `S_IFREG` type bits (`zipinfo` prints `?rwx…`),
  which `jdk.zipfs` does not expose; Info-ZIP `unzip` ignores it. The `depgen` memo files keep `createTempFile`: they
  are a build cache, never part of a distribution.

## BUG-20261116-02 — The `-dist.zip` of `vidocq:package` loses the execute bit of `bin/<name>.sh`

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/dist-zip-exec-bit`
- **Module**: `vidocq-runtime-maven-plugin` (`VidocqPackageMojo.createZip`)
- **Symptom**: `vidocq build` (or `mvn package`) on vaadin-on-vidocq writes `target/vaadin-on-vidocq-0.4.0-SNAPSHOT-dist.zip`.
  Unzipped elsewhere, `./bin/vaadin-on-vidocq.sh` fails with `Permission denied`: the file is `-rw-r--r--`. `zipinfo`
  shows every entry made on a `fat` host with no Unix mode (`-rw----  2.0 fat  721 bl defN … bin/vaadin-on-vidocq.sh`),
  while the unpacked copy in `target/` is `rwxr--r--`. `sh ./bin/vaadin-on-vidocq.sh` starts the application fine.
  Found by the VAADIN session.
- **Repro**: `vidocq build && mkdir /tmp/d && cd /tmp/d && unzip -q <project>/target/*-dist.zip && ./*/bin/*.sh`.
- **Cause**: `createZip` writes the archive with `java.util.zip.ZipOutputStream`, which has no API for the Unix mode of
  an entry; the `setExecutable(true)` on the staged launcher does not reach the archive.
- **Fix**: `DistributionZip` writes the archive through the `jdk.zipfs` file system with
  `enablePosixFileAttributes=true` (JDK only, no dependency): each entry keeps the POSIX permissions of its staged
  file, and on a build machine without POSIX permissions (Windows) the `bin/*.sh` launcher gets `rwx------`. Checked
  by hand: `unzip` gives `-rwxr-xr-x` and the launcher starts. Tests: `DistributionZipTest`
  (`eachEntryKeepsTheModeOfItsStagedFile`, `withoutPosixPermissionsTheLauncherIsExecutableByItsOwner`,
  `theArchiveHoldsEveryFileUnderTheDistributionDirectory`). The docs now name the launchers `bin/<artifactId>.sh` and
  `.cmd` instead of `bin/run.sh` and `bin/run.cmd`.

## BUG-20261116-01 — The Application run configuration of `vidocq idea --kind application` cannot start a Vaadin application

- **Date**: 2026-11-16
- **Status**: FIXED on `fix/idea-application-vm-options`
- **Module**: `vidocq-runtime-maven-plugin` (`vidocq:idea`, `-Dvidocq.idea.kind=application`)
- **Symptom**: on `VidocqTools/vaadin-on-vidocq` with a `@VidocqMain` trampoline (`Vidocq.run(args)`) named by
  `vidocq.mainClass`, `vidocq idea --kind application` writes `.run/App.run.xml`: Make, the before-run Maven
  `vidocq:generate`, then the main class, with no VM options. Launched as IntelliJ launches a modular main class, the
  start fails: `[VIDOCQ-LAY-001] 1 application module is in the boot layer and in the application layer:
  io.vidocq.tools.vaadin`, then `Vidocq startup failed in build after 96 ms (java.lang.NoClassDefFoundError)` and
  `Exception in thread "main" java.lang.NoClassDefFoundError: org/slf4j/Logger` at
  `ObserverDisposerDiscovery.getAllInheritedMethods` ← `BeanDiscovery.discoverObservers` ←
  `VaubanContainerBuilder.build` ← `VidocqBootstrap.boot` ← `Vidocq.run` ← `App.main`. Nothing in the output says what
  to add. Found by the VAADIN session (dev loop on snapshot 0.4.0-20261115.225206-140, Vauban -55).
- **Repro**: vaadin-on-vidocq at 97a2d46, plus `App.java` (`@VidocqMain`, `Vidocq.run(args)`),
  `requires io.vidocq.runtime.core;` and `<vidocq.mainClass>io.vidocq.tools.vaadin.App</vidocq.mainClass>`, then
  `./mvnw process-classes` and `java -Dvaadin.productionMode=true -p target/classes:<runtime dependencies> -m
  io.vidocq.tools.vaadin/io.vidocq.tools.vaadin.App` (the command IntelliJ builds from the generated configuration;
  emulated outside the IDE).
  - With `--add-modules ALL-MODULE-PATH` added, the application starts and renders its view (with `VIDOCQ-LAY-001`
    for 8 modules and the Vauban runtime code generation fallback for `AtmosphereProducers_ClientProxy`: the IDE takes
    the jars that `vidocq:generate` did not enrich; that part is the layer design, not this bug).
  - Without `-Dvaadin.productionMode=true`, which the configuration does not carry from `vidocq.run.jvmArgs`, `GET /`
    answers 500: `Frontend development bundle is expected to be in the project or on the classpath, but not found`.
  - Also: `vidocq idea --kind application` on an application without `vidocq.mainClass` contradicts itself
    (`has no application main class … use the default Maven kind`, then `no Vidocq application in this build: a
    module needs vidocq-runtime-maven-plugin in its <build><plugins> and a vidocq.mainClass property` although the
    plugin is there, then three `was generated by vidocq:idea but no application of this build maps to it` warnings
    on the `(dev)`, `(run)` and `(debug)` files the default kind had written).
- **Cause**: with `-m`, the boot layer resolves the application module, what it requires, and every automatic module,
  but an explicit module that only automatic modules need, here `org.slf4j` for the Vaadin jars, is never resolved.
  Vidocq's own launchers pass `--add-modules ALL-MODULE-PATH`; the Application configuration passed nothing, and
  neither the JVM arguments of the pom.
- **Fix**: the Application configuration writes `VM_PARAMETERS` with `--add-modules ALL-MODULE-PATH` and the module's
  own `vidocq.run.jvmArgs`. Without any main class, the goal says once that no Application configuration is written
  (each application already reported), and names the files of the other kind as such (INFO) instead of orphans.
  Tests: `RunConfigurationRendererTest.body_addsTheWholeModulePathAndTheRunJvmArgs`,
  `VidocqIdeaMojoTest.theApplicationKindPassesTheRunJvmArgsOfThePom`,
  `VidocqIdeaMojoTest.theApplicationKindWithoutAnyMainClassDoesNotContradictItself`.

## BUG-20261115-10 — `vidocq doctor --fix` does not write the `opens` of a sub-folder the build asks for

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/doctor-resource-subpackages`
- **Module**: `vidocq-runtime-tooling` (`ProjectDoctor`, `ExtensionEditor`, `KnownExtensions`)
- **Symptom**: with a Liquibase `includeAll path="db/changelog/changes/"`, the build fails with "module-info of ... is
  missing 1 directive(s) required by its Vidocq extensions — `vidocq doctor --fix` adds them ... opens
  db.changelog.changes;", but `vidocq doctor` says every check is ok and `--fix` writes nothing. Found by the tutorial
  session, replaying BUG-20261115-06 on snapshot -136.
- **Repro**: `ProjectDoctorTest.aClosedSubFolderOfAResourcePackageFailsAndIsOpened`.
- **Cause**: BUG-20261115-06 taught `vidocq:check-module-info` to ask for the `opens` of each sub-folder of scripts
  below a resource package, from the compiled output. Doctor and its fix compute the directives from the catalog
  alone, which names the package and not its sub-folders.
- **Fix**: `ResourcePackages.below` finds those sub-folders (a file, no class or source file); the build check reads
  the compiled output with it, doctor and `--fix` read `src/main/resources`.

## BUG-20261115-09 — `CreateThenBuildIT` fails on the build machine's repository, not on the created project

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/bce-processed-source-location`
- **Module**: `vidocq-runtime-it-create` (`CreateThenBuildIT`)
- **Symptom**: `aPackagedServletApplicationInjectsTheRequestTheSessionAndTheContext[1]` (servlet-rest) fails with
  `the created servlet-rest project builds with warnings`, then passes on Failsafe's rerun (the job reports success
  with a `Flakes:` line). The build prints, for each Vauban POM it fetches (ten lines in all, no other warning):
  `[WARNING] Could not validate integrity of download from file:///root/.m2/repository/io/vidocq/vauban/vauban-core/0.4.0-SNAPSHOT/vauban-core-0.4.0-SNAPSHOT.pom`
- **Repro**: a producer's `pr-validate` downstream job that builds Vidocq (Vauban PR #311, run 2065, job 22): Run 1
  failed, Run 2 passed. Any producer whose downstream job builds Vidocq hits it on the first fixture build.
- **Cause**: the fixture mirrors every repository to the outer local repository as a `file:` URL
  (`src/it/it-settings.xml`), and the producer's artifacts are *installed* there, so they have no checksum files.
  Maven's default `warn` checksum policy prints one warning per file. The test asserts that the build prints no
  `[WARNING]`. The first fixture build fills the fixture's own local repository, so the rerun prints none.
- **Fix**: `BuildWarnings.warningsOf` keeps every `[WARNING]` line except "Could not validate integrity of download
  from" the outer repository's URL, taken as the fixture computes it; the same warning from another repository still
  fails the test (`BuildWarningsTest`).

## BUG-20261115-08 — A packaged application without a main class has no Noether `default` unit at start

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/package-app-module`
- **Module**: `vidocq-runtime-maven-plugin` (`VidocqPackageMojo` launchers, `ChildJvm`)
- **Symptom**: an application with an entity and no `persistence.xml` passes its tests, but its `vidocq:package`
  distribution fails at start: `Default persistence injection requires exactly one named persistence unit; found []`,
  or, frozen, `persistence unit default was declared when the beans were built, not at start`. Found by the tutorial
  session (step 03 on Noether).
- **Repro**: `VidocqPackageMojoTest.layerLaunchers_nameTheApplicationModuleWhenTheRuntimeMainRuns` and
  `ChildJvmTest.layer_mode_names_the_application_module_without_a_main_class`; by hand, `bin/<app>.sh` of such an
  application starts with `JAVA_OPTS=-Dvidocq.app.module=<module>` and fails without it.
- **Cause**: the layer launchers of `vidocq:package`, and the child JVM of `vidocq:run`, pass `-Dvidocq.app.main`
  when the application has a main class, and nothing otherwise. The runtime's own main then finds no application
  module (`ApplicationLayer.applicationModule()` is empty), and the Noether Persistence extension declares its
  `default` unit only for the application module. The build and `@VidocqTest` name the module, so they see the unit.
- **Fix**: without a main class, the launchers pass `-Dvidocq.app.module` with `vidocq.mainModule`, or, for
  `vidocq:package` when it is unset, the module the application jar declares.

## BUG-20261115-07 — The stop of an application sometimes logs nothing, not even "Vidocq - Shutting down"

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/console-log-shutdown`
- **Module**: `vidocq-runtime-core` (`ConsoleLogHandler`)
- **Symptom**: on SIGTERM or `System.exit`, the line `Vidocq - Shutting down` and the lines of the stop after it
  are printed on some runs and missing on others. Found by the tutorial session.
- **Repro**: `ConsoleLoggingProcessTest.aLineLoggedByAShutdownHookStillReachesTheConsole`: a child JVM with Vidocq's
  console exits, and a shutdown hook logs after 500 ms. Before the fix the line never appears.
- **Cause**: `LogManager` registers its own shutdown hook, the thread `Logging-Cleaner`, which calls `reset()`:
  every handler is removed from every logger and closed. The JVM starts all shutdown hooks at once, so Vidocq's
  `vidocq-shutdown` races it, and a record logged after the reset reaches no handler.
- **Fix**: `ConsoleLogHandler.close()`, when the `Logging-Cleaner` thread calls it, puts the handler back on the root
  logger. The reset still sets the levels back (root `INFO`, the others inherited). With
  `vidocq.log.console=jdk`, the JDK's `ConsoleHandler` keeps the JDK behaviour.

## BUG-20261115-06 — `vidocq create -x liquibase-migration` prepares the folder Flyway reads, not Liquibase's

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/liquibase-changelog-folder`
- **Module**: `vidocq-runtime-tooling` (extension catalog), `vidocq-runtime-liquibase-migration-extension`
  (`extension.properties`)
- **Symptom**: the CLI creates `src/main/resources/db/migration` ("where liquibase-migration reads its files") and
  writes `opens db.migration;`, while Liquibase reads `db/changelog/db.changelog-master.xml` by default. The
  folder is of no use until `vidocq.migration.locations` points there. Found by the tutorial session. In a jlink
  image, a module that does not open `db.changelog` and `db.changelog.changes` (an `includeAll` folder) stops the
  start with `The file db/changelog/db.changelog-master.xml was not found in the configured search path`, and
  `vidocq:check-module-info` passed the build: it knew only `db.migration`, and only the package itself.
- **Repro**: `ExtensionEditorTest.addingLiquibasePreparesTheChangelogFolder`,
  `ModuleRequirementsCollectorTest.opensReachesTheSubPackagesThatHoldAFile` and
  `catalogResourcePackagesAreCollectedAsOpens`.
- **Cause**: the Liquibase entry copied Flyway's `module-info.opens-resource-package = db.migration`, and
  `check-module-info` read the opens of a resource folder from one generic descriptor, without its sub-folders.
- **Fix**: Liquibase's resource package is `db.changelog`, in the extension's descriptor and the catalog.
  `check-module-info` asks for the opens of the catalog's resource packages, and of each sub-package below them
  that holds resources and no class.

## BUG-20261115-05 — A packaged servlet application runs its build compatible extensions at every start

- **Date**: 2026-11-15
- **Status**: FIXED in Vauban (BUG-20261115-07, `vauban-core`), on the companion branches
  `fix/bce-processed-source-location` of Vauban and Vidocq; nothing to change in Vidocq's code
- **Module**: the packaged application (`vidocq:package` layout, application layer); found by
  `CreateThenBuildIT.aPackagedServletApplicationInjectsTheRequestTheSessionAndTheContext` (`vidocq-runtime-it-create`)
- **Symptom**: `vidocq create -x foy-servlet` with one `@WebServlet`, then `mvn verify`: `vidocq:generate` says
  `Vauban: the build compatible extensions are not frozen, the container start runs them: the deployment has no build
  compatible extension.` and writes no record. The distribution's start then runs `FoyWebExtension` and
  `WebContextsExtension` (`cdi extensions  2 extensions run at start` in the detailed report); with
  `-Dvidocq.startup.require-frozen-extensions=true` it fails: `[VIDOCQ-GEN-003] … 2 build compatible extensions ran at
  start: no record of the build is on the path`. The application works; its extensions run at every start, against
  "build compatible extensions work at build, and only there". The same project with `cassini-rest` too is frozen
  (`4 extensions frozen at build`) and starts on the record.
- **Minimal repro**: the IT's `servlet` case: launch it with `-Dvidocq.startup.require-frozen-extensions=true`. By
  hand: on the distribution's `lib/` class path, `VaubanClassLoader.of(List.of(app/demo-app.jar), …)` lists the
  marker as `jar:file:///…/app/demo-app.jar!/META-INF/vauban-bce-processed`, while
  `WhoAmIServlet`'s code source is `file:/…/app/demo-app.jar`; a `URLClassLoader` gives `jar:file:/…` for both.
- **Cause**: every bean archive of this deployment was processed at compile (the application by the Foy codegen
  bundle, `foy-cdi-vauban` at Foy's build): both carry `META-INF/vauban-bce-processed`. `vidocq:generate` freezes on a
  `URLClassLoader` over `target/classes` and the jars, where `VaubanContainerBuilder.isClassFromBceProcessedSource`
  matches each class to its archive's marker, so no extension needs to run and there is nothing to freeze. The start
  defines the application in Vauban's application layer: `VaubanClassLoader` gives a jar's resources
  `"jar:" + path.toUri() + "!/"` (`file:///`) and its classes the code source `path.toUri().toURL()` (`file:/`), and
  the container compares the two as strings (`ContainerScanner.extractSourceRoot` against
  `CodeSource.getLocation().toString()`). The application's classes look unprocessed, and every extension runs.
  Not caused by Vidocq/foy#107: any packaged application whose bean archives were all processed at compile has it.
- **Fix (Vauban)**: the container compares the archive that holds the marker with the class's code source as
  locations, not as strings (Vauban BUG-20261115-07). The build of such an application now says
  `Vauban: nothing to freeze: every build compatible extension of the deployment ran at compile.` (Vauban's
  `FrozenExtensions.ALL_AT_COMPILE`, at INFO), and the start runs no extension: the detailed report has no
  `cdi extensions` row, and `-Dvidocq.startup.require-frozen-extensions=true` starts.
- **Test**: the `servlet` case of `CreateThenBuildIT.aPackagedServletApplicationInjectsTheRequestTheSessionAndTheContext`
  now starts under `require-frozen-extensions=true`, as the `servlet-rest` case does, and asserts the build's line,
  no record, no `cdi extensions` row, no `VIDOCQ-GEN-` anomaly and the three HTTP answers. Against the published
  Vauban it fails on the build's line (`… are not frozen, the container start runs them: the deployment has no build
  compatible extension.`), and on the start (`[VIDOCQ-GEN-003] … 2 build compatible extensions ran at start`) when
  the build assertion is left out.

## BUG-20261115-04 — An application with the Liquibase extension does not start on the module path

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/liquibase-without-jaxb`
- **Module**: `vidocq-runtime-liquibase-migration-extension` (`pom.xml`)
- **Symptom**: `vidocq create -x liquibase-migration`, then `vidocq:run`, the distribution or `vidocq:jlink`:
  `FindException: Module java.activation not found, required by java.xml.bind`. Found by the tutorial session.
- **Repro**: `ModulePathClosureTest` (every explicit module of the extension's runtime path finds what it requires).
- **Cause**: liquibase-core 4.33.0 declares `javax.xml.bind:jaxb-api:2.3.1` and excludes the activation jar.
  jaxb-api is the explicit module `java.xml.bind`, which requires `java.activation`: fine on a class path, refused on
  the module path. Liquibase itself never references `javax.xml.bind` (jdeps finds no class of it).
- **Fix**: the extension excludes `jaxb-api` from liquibase-core.

## BUG-20261115-03 — An encrypted jar without its key stops the start with a bare stack trace and no failure line

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/app-layer-failure-line`
- **Module**: `vidocq-runtime-core` (`VidocqAppLayer.installLayer`)
- **Symptom**: the distribution of an application that depends on an encrypted jar, started without
  `VAUBAN_SJAR_KEY`, prints `Exception in thread "main" java.lang.SecurityException: No SJAR key found. …` and a
  20-line stack trace from Vauban's layer factory, with no banner and no `Vidocq startup failed` line. Found by the
  tutorial session.
- **Repro**: `VidocqAppLayerFailureTest.aMissingKeyFailsTheStartWithItsMessage`.
- **Cause**: the application layer is created before the first boot phase, so the failure line of a failed boot
  is never logged, and `installLayer` turned only an `IOException` or a `FindException` into a layer failure.
- **Fix**: a `SecurityException` is a layer failure too, and `installLayer` logs it as a failed boot does:
  `Vidocq startup failed in application layer (<origin>): <message>`, then throws it.

## BUG-20261115-02 — In a jlink image, Flyway finds no migration: the schema is not created

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/flyway-flat-image`
- **Affected module**: vidocq-runtime-spi (`ApplicationLayer`), vidocq-runtime-flyway-migration-extension
  (`FlywaySchemaMigrator`), vidocq-runtime-liquibase-migration-extension (`LiquibaseSchemaMigrator`)
- **Symptom**: in the image of `vidocq:jlink`, and in the `vidocq:jpackage` application and the `vidocq:docker` image
  of the `jlink` kind, an application with the Flyway extension starts without its schema: `No migrations found. Are
  your locations set up correctly?`, `Migration done: … applied=0`, the anomaly `[VIDOCQ-MIG-001]`, then every
  request fails on a missing table. The `vidocq:package` distribution of the same build applies the migrations.
  Found by the tutorial session on main 688eaf7a, the day the image got one layer (Vidocq/vidocq#584); the image of
  ab6d15fe, with its application layer, applied them.
- **Minimal repro**: the tutorial at step 11 (`flyway-migration`, `src/main/resources/db/migration/V1__books.sql`,
  `opens db.migration;`), `./mvnw -DskipTests verify vidocq:jlink`, then `target/dist/bin/library` on an empty
  database. With the image's `bin/java`: `getResources("db/migration")` is empty, while
  `getResources("db/migration/V1__books.sql")` returns `jrt:/com.example.library/db/migration/V1__books.sql`.
- **Cause**: an image has no directory entry, so no class loader lists `db/migration`, and Flyway's class-path
  scanner starts from that directory (it has no `jrt:` scanner either). The extension already listed the migrations
  from the modules for a launch in the application layer (vidocq#96), but only when there was such a layer: an image
  no longer has one. The package is open and the file is found by name: this is not the documented difference about
  a resource in a package of another module.
- **Fix**: `ApplicationLayer.home()` gives the layer whose modules hold the application: the application layer, else
  the layer of the application module, as in an image. `ApplicationLayer.list` skips the modules of the JDK, which
  that layer holds too. The Flyway extension lists from `home()`, and so does the Liquibase extension, which had the
  same condition and was not tried in an image. The other callers of `ApplicationLayer.current()` (Foy, Ibarra,
  Bodoni, Noether) already fall back to the boot layer.
- **Validation**: `ApplicationLayerTest` (a module of the JDK is never listed; no runtime, no home).
  `AppLayerImageIT` and `AppLayerPackageIT` (`-Pit`): the fixture prints what `ApplicationLayer.list(home(), …)` finds,
  `[META-INF/acme/index.html]`, in the image and in the distribution. Not validated here: Flyway itself in an image,
  which needs a database; the tutorial session runs it on the published SNAPSHOT.
- **Documentation**: the difference is added to "What differs from a launch in the application layer" and to the
  page "Choosing how to ship an application".

## BUG-20261115-01 — An application stop cuts every WebSocket without a Close frame

- **Date**: 2026-11-15
- **Status**: FIXED on `fix/websocket-stop-order`
- **Affected modules**: vidocq-runtime-chappe-webserver-extension (`ChappeMountPoint`, `ChappeServerBootstrap`),
  vidocq-runtime-foy-servlet-extension (`FoyExtension`)
- **Symptom**: at application stop every open Jakarta WebSocket connection drops with no Close frame (the peer sees
  1006), the endpoint may get `onError` before `onClose`, and `Servlet.destroy()` and
  `ServletContextListener.contextDestroyed` run after the server is gone.
- **Minimal repro**: boot `vidocq-runtime-it-foy-websocket`, open a raw WebSocket to the Foy endpoint, shut the runtime
  down, read the socket. Test: `FoyWebSocketModulePathTest.anApplicationStopClosesTheConnectionWith1001BeforeTheServerStops`
  (red before the fix: the client reads no frame).
- **Cause**: extensions stop in reverse start order. `ChappeServerBootstrap` (priority 10000) stops first, so the
  servers were gone when `FoyExtension.onStop` (priority 600) ran `deployments.closeAll()`, after the 1001 Foy sends at
  undeploy (BUG-20261114-05 in Vidocq/foy) could no longer be sent.
- **Fix**: `ChappeMountPoint.addBeforeStopHook`, run by `ChappeServerBootstrap.onStop` before any server stops;
  `FoyExtension` registers `deployments::closeAll` there at start. `onStop` keeps its idempotent `closeAll()`.

## BUG-20261114-13 — A refused WebSocket origin names a setting the Vidocq user never wrote

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/websocket-origin-setting-name`
- **Affected modules**: vidocq-runtime-chappe-webserver-extension (`ChappeServerBootstrap`),
  vidocq-runtime-foy-websocket-extension (`AllowedOriginsParameter`)
- **Symptom**: a handshake from a foreign origin answers 403, and the WARNING names Chappe's Java API for a native
  route (_... not allowed by Router.Builder.webSocketOrigins_) or Foy's init parameter for a Jakarta WebSocket
  endpoint (_... the origins listed in the context init parameter io.vidocq.foy.websocket.allowed-origins_). The
  Vidocq user sets `vidocq.websocket.allowed-origins`, which neither message mentions.
- **Minimal repro**: boot with `vidocq.websocket.allowed-origins` absent, send a WebSocket upgrade with
  `Origin: http://evil.example` to a native route and to a Foy endpoint, read the WARNING. Tests:
  `ChappeServerEndToEndTest.theRefusalNamesTheKeyWhenItIsAbsent` and `...WhenItDoesNotListTheOrigin`,
  `FoyWebSocketModulePathTest.theRefusalsNameTheKeyTheUserSets`, `FoyWebSocketExtensionTest`.
- **Cause**: upstream, CHAPPE-059 in Vidocq/chappe (`WebSocketOrigins.configuredBy`) and BUG-20261114-04 in
  Vidocq/foy (init parameter `io.vidocq.foy.websocket.allowed-origins.setting-name`). This repository only had to pass
  the key's name to both bricks.
- **Fix**: `ChappeServerBootstrap` builds the policy with `.configuredBy("vidocq.websocket.allowed-origins")`, also
  when the key is absent; `AllowedOriginsParameter` always forwards the setting-name parameter to Foy.

## BUG-20261114-12 — A malformed `vidocq.websocket.allowed-origins` fails the start with Foy's parameter name

- **Date**: 2026-11-14
- **Status**: FIXED on `feat/websocket`
- **Affected module**: vidocq-runtime-foy-websocket-extension (`AllowedOriginsParameter`)
- **Symptom**: with `vidocq.websocket.allowed-origins=ftp://x` the start fails in `servlet-foy` with _Foy deployment
  failed: Invalid WebSocket origin '"ftp://x"' ... (init parameter io.vidocq.foy.websocket.allowed-origins)_. The
  message never names the key the user set.
- **Minimal repro**: `FoyWebSocketModulePathTest.aMalformedAllowedOriginStopsTheStart` (vidocq-runtime-it-foy-websocket).
- **Cause**: the Servlet extension (priority 600) starts before the Chappe web server extension (10000), which owns
  the check that names the key. Foy therefore refuses the forwarded value first.
- **Fix**: `AllowedOriginsParameter` parses the value with Chappe's `WebSocketOrigins.parse` and throws
  `IllegalArgumentException("vidocq.websocket.allowed-origins is invalid: ...")`. Unit test
  `aMalformedOriginIsRefusedWithTheNameOfTheKey`.

## BUG-20261114-11 — A jlink image loads the application module twice: in the boot layer and in the application layer

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/jlink-boot-twin` (stacked on `fix/trampoline-boot-twin`); the fix replaced on
  `feat/flat-images` (2026-11-15, see the last point)
- **Affected module**: vidocq-runtime-maven-plugin (`ImageLaunch`, `VidocqJlinkMojo`, `VidocqJpackageMojo`, new
  `LayerServiceFiles`), vidocq-runtime-core (`VidocqAppLayer.installFromModuleIfConfigured`, `ExtensionLoader`)
- **Symptom**: in the image of `vidocq:jlink`, and in the `vidocq:jpackage` application and the `vidocq:docker` image
  built on it, `ServiceLoader.load` through the context loader returns each provider of the application twice, two
  `Class` objects of one name, as in BUG-20261114-07; Vaadin 25 stops with "multiple eligible instantiator
  implementations". With a `@VidocqMain` trampoline, the image also logged `[VIDOCQ-LAY-001]`, the warning meant for
  an IDE run. Vidocq/vidocq#574.
- **Minimal repro**: the `it-app-layer` fixture, whose module `provides java.util.spi.ToolProvider`, built with
  `mvn package vidocq:jlink` and started from `target/dist/bin/app-layer-app`: `TOOL-PROVIDERS 2`, with or without
  `vidocq.mainClass`.
- **Cause**: the image rooted the application module in the boot layer (`--add-modules=<mainModule>,<every staged
  module>`, BUG-20261114-05, and the launcher `<mainModule>/<mainClass>` of a trampoline), then `Vidocq.run` or the
  runtime's main re-layered it from there and left the boot copies in place. Dropping the module from
  `--add-modules` is not enough: the JVM binds into the boot layer every module that provides a service a resolved
  module uses (`java.base` uses `ToolProvider`, Vauban uses `VaubanComponentProvider`), so `--show-module-resolution`
  printed `java.base binds acme.app` with no root.
- **Fix**:
  1. `vidocq:jlink` splits the staged modules at build with `LayerSplit`, as `vidocq:package` splits its jars
     (`vidocq.app.layer`, `vidocq.app.layer.keep`). Every staged module is still linked.
  2. The launcher always runs the runtime's main. The image bakes `-Dvidocq.app.module`, `-Dvidocq.app.modules=<the
     application layer>`, `-Dvidocq.app.main` for a trampoline, `-Dvidocq.app.layer`, and an `--add-modules` that
     roots the parent layer and the JDK modules the image needs, never a module of the application layer.
  3. Before jlink, the `provides` of the application layer's staged modules move into their `META-INF/services` files
     (`LayerServiceFiles`, Class-File API, every `module-info.class` of a multi-release jar): nothing binds them into
     the boot layer any more, and Vauban's layer turns the files back into `provides` when it defines the module.
  4. `VidocqAppLayer.installFromModuleIfConfigured` defines the application layer from the listed modules, read from
     the image (`jrt:/modules/<name>`), all of them roots, so a resource-only module is still resolved
     (BUG-20261114-05). The startup report names the origin `jlink image`.
  5. jlink writes `VIDOCQ_APP_MODULES=<the application layer>` into the image's `release` file; `vidocq:jpackage`
     reads it and starts the image as `vidocq:jlink` baked it.
  6. The extension list at start (`ExtensionLoader`) reads the descriptors of the application layer too. The Vaadin
     extension reads `flow.server`, so it sits in that layer, and the line `vaadin (CDI only)` had left the list, under
     `vidocq:package` as in the fixed image; the old image listed it only because of its boot copy. The descriptors
     are read through each module's `ModuleReader`: `Module.getResourceAsStream` asks Vauban's loader, whose
     `findResource(moduleName, name)` ignores the module name, so every module of the layer answered with the
     Vaadin extension's file (71 `vaadin (CDI only)` lines in the first try).
- **Rejected**: baking `--limit-modules` (option (c) of Vidocq/vidocq#574). It keeps the application out of the boot
  layer too, but the JVM then turns CDS off ("CDS is disabled when the --limit-modules option is specified") and
  refuses to record an AOT cache ("Cannot use the following option when dumping the shared archive:
  --limit-modules"), on JDK 25, and `java --list-modules` hides the application modules.
- **Validation**: `AppLayerImageIT` (`-Pit`): both launch shapes count `TOOL-PROVIDERS 1`, the layer is `3 modules,
  2 libraries (jlink image)`, the library's loader is the layer's and reads the application's resource from a
  `jrt:/` URL (Foy#96), `SQL-OK` (a JDK module only the layer requires, vidocq#254), no `VIDOCQ-LAY-001`; a flight
  recording, `jcmd <pid> VM.version`, and an AOT cache recorded then opened (`Opened AOT cache`). Red before the
  fix. `CreateThenBuildIT`: the created project's image puts `acme.resources` in its `jlink image` layer, and the
  resolution of its own launch names neither that module nor the application's. `ImageLaunchTest`,
  `ImageLaunchMojosTest`, `LayerServiceFilesTest`, `VidocqAppLayerModuleTest`. By hand, the image of a Vaadin 25.3.2
  application (`vauban:modularize` all-automatic, an app shell with `@StyleSheet("aura/aura.css")`,
  `@Push(transport = LONG_POLLING)` and `provides AppShellConfigurator`): `GET /` and the stylesheet answer 200, the
  layer's loader returns `jrt:/vaadin.aura.theme/META-INF/resources/aura/aura.css`, Vaadin's `Lookup` and
  `ServiceLoader` each find the app shell once, and a click's update reaches the page over long polling.
- **2026-11-15, the fix replaced (Vidocq/vidocq#580)**: an image has one layer, the JVM's own, so the duplicate
  cannot exist. The launcher runs the trampoline, or the runtime's main with `-Dvidocq.app.module`; the image bakes
  `-Dvidocq.app.layer.off=true` and an `--add-modules` with every staged module (point 2 above no longer holds). The
  build no longer splits the staged modules (point 1), and where it moved the `provides` out (point 3,
  `LayerServiceFiles`, deleted) it now adds to the staged modules the `provides` their services files declare
  (`StagedProvides`, with the rule of Vauban's layer, `VaubanLayerFactory.promotingServices`, public since
  Vidocq/vauban#305). `VidocqAppLayer` no longer reads a layer from `jrt:` (point 4), and the `release` key is gone
  (point 5). `vidocq.app.layer` set on an image goal is named in a warning. Proved first by a throwaway spike: the
  flat image of the `it-app-layer` fixture (`TOOL-PROVIDERS 1`, `no application layer`, `anomalies none`) and of a
  Vaadin 25 application of 96 modules (page and `aura/aura.css` answer 200, no exception). `AppLayerImageIT` and
  `CreateThenBuildIT` now assert the one layer; `StagedProvidesTest`, `ImageLaunchTest`, `ImageLaunchMojosTest`.

## BUG-20261114-10 — Two launchers put a trampoline application in the boot layer and in a Vauban layer

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/trampoline-boot-twin`
- **Affected module**: vidocq-runtime-maven-plugin (`VidocqPackageMojo`, `ChildJvm`), vidocq-runtime-core
  (`Vidocq.run`)
- **Symptom**: the application module is loaded twice, once by the JVM's application loader in the boot layer and
  once by the Vauban loader in the application layer, as in BUG-20261114-07. `ServiceLoader.load` through the context
  loader returns each provider of the application twice, two `Class` objects of one name; Vaadin 25 then stops with
  "multiple eligible instantiator implementations". Found in two launches, both with a main class that calls
  `Vidocq.run` (a trampoline):
  1. `vidocq:package` with `vidocq.app.layer=classes`: the launchers ran `--module-path lib:app --module <app>/<main>`.
  2. the escape hatches `vidocq.package.layer=false` and `vidocq.dev.layer=false`, which promise the legacy launch
     with everything on the module path and no layer: the trampoline built a layer anyway.
- **Minimal repro**: an application module that `provides java.util.spi.ToolProvider` and counts, from a CDI
  `Startup` observer, the providers of its own type that `ServiceLoader.load(ToolProvider.class)` finds:
  `TOOL-PROVIDERS 2` under the two launches above, `1` under `vidocq:run` and the default `vidocq:package`.
- **Cause**: both launches put the application module on the module path, so in the boot layer, and
  `Vidocq.run` re-layers what the boot layer holds (`VidocqAppLayer.installFromBootLayer`), as it does for an IDE
  run. The `classes` branch of the package launchers predates `-Dvidocq.app.path` there; under `all` they already
  handed `app/` over as the documentation says ("also for an application with a main class"). Nothing told the
  trampoline that a launch wanted no layer.
- **Fix**:
  1. the `vidocq:package` layer launchers always hand `app/` over through `-Dvidocq.app.path` and the main class
     through `-Dvidocq.app.main`, under `classes` as under `all`;
  2. the launchers without a layer (`vidocq.package.layer=false`, `vidocq.dev.layer=false`) pass
     `-Dvidocq.app.layer.off=true` (`LayerSplit.OFF_PROPERTY`), and `Vidocq.run` then keeps the application where the
     JVM resolved it: no second copy, the boot module is the application, the startup report says
     `no application layer`.
  3. the one launch left with the duplicate, a trampoline run from the IDE with the application on the module path,
     warns: `Vidocq.run` records the modules the boot layer holds too (`VidocqAppLayer.Installation.twice`), and the
     start logs the anomaly `VIDOCQ-LAY-001`, recalled by the report's `anomalies` section, which names them and
     points to `vidocq:dev` / `vidocq:run` (the default configurations of `vidocq:idea`). The maintainer chose the
     warning over a relaunch, which would take the IDE debugger away.
- **Behaviour change**: without a layer, nothing promotes the `META-INF/services` files of the processors, so an
  application that ran the legacy layout through the second copy must now declare its `_VaubanComponents` provider or
  open its bean packages to `io.vidocq.vauban.core`, as one without a trampoline always had to; the start says so
  (the fixture of `AppLayerPackageIT` needed the `opens`).
- **Validation**: `TrampolineLaunchProcessTest` (a real trampoline launch from the module path: one provider, the
  application loader, no `VIDOCQ-LAY-001` with the switch; two providers and `VIDOCQ-LAY-001` naming the module
  without it); `ModulesTwiceTest`; `VidocqPackageMojoTest` and `ChildJvmTest` (the launchers); `AppLayerPackageIT`
  (`classes`, and the legacy layout) and `AppLayerDevIT` (`vidocq.dev.layer=false`) count one provider and no
  `VIDOCQ-LAY-001` in a real launch (`-Pit`). All red before the fix.

## BUG-20261114-09 — `ChappeServerEndToEndTest` fails now and then on a port another process took

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/test-free-ports`, which also carries the `ChappeServerEndToEndTest` fix of
  `fix/chappe-e2e-port`
- **Affected module**: vidocq-runtime-chappe-webserver-extension (test only: `ChappeServerEndToEndTest`); the same
  pattern in another test of that module, in the tests of 12 other modules, and in the main code of
  vidocq-runtime-junit, which every `@VidocqTest` of an application runs: all listed under Fix
- **Symptom**: a full `-Pquality install` of `fix/dev-test-dev-services`, run while other sessions built and ran on the
  same machine, failed in `ChappeServerEndToEndTest.multiListenerEachServesItsOwnRouter`:
  `ChappeException$ServerException: Failed to bind to 127.0.0.1:49335`, caused by
  `java.net.BindException: Address already in use`, at `ChappeServer.start` from `ChappeServerBootstrap.onStart`
  (line 134 of the test). The `default` listener had started; the `admin` one failed. The same build, resumed, passed.
- **Minimal repro**: not deterministic. The window is between the test's `freePort()` and the server's bind: any
  process that binds an ephemeral port in that window can get the same one.
- **Cause**: `freePort()` opened a `ServerSocket(0)`, read its port, closed it, and the server bound that port later.
  Once closed, the port is free for anyone: another JVM of the machine, or the second `freePort()` of the same test,
  since `multiListenerEachServesItsOwnRouter` picked two ports one after the other, the first one already closed. The
  log does not say which of the two took 49335. The same pattern Vidocq/cassini#144 removed from Cassini's tests.
- **Fix**: every listener of the test binds port `0`, and the test reads the port each got from
  `ChappeServerBootstrap.boundAddresses()`: the system picks the port as the server binds it, so nothing can take it
  first. `multiListenerEachServesItsOwnRouter` also checks the two listeners got two ports, and
  `httpPortAliasAloneBindsTheAdvertisedPort` sets the alias to `0` and checks the default listener did not bind its
  default port, 8080, which only a listener that ignores the alias would.
  `fix/test-free-ports` applies the same fix to the other tests: the server binds port `0`, and the test reads the
  port it got from the server, or from the line the application logs once it listens.
  - In the test's JVM: `ChappeServerSuspendTest` asks `ChappeServerBootstrap.boundAddresses()`. The module-path tests
    of `it-foy-servlet`, `it-ibarra-pages` and `it-bodoni-jstl` read Chappe's start line,
    `Chappe listener 'default' started on http://127.0.0.1:<port>/`, with a JUL handler (`BoundPort`, one per module).
    The `TransactionalRollbackTest` of the Mansart H2 and Petstore examples and the Mansart H2 example's
    `DevConsoleSnapshotTest` never used their port: they only set the key to `0`.
  - In another JVM: the Servlet example's `BistroApplication`, `it-langchain4j-cdi-mcp`'s `LaunchedServer`,
    `it-app-layer`'s `AppLayerJspIT`, `it-continuous-testing`'s `ContinuousTestingDevGoalIT` and `it-devservices`'s
    `DevServicesDevGoalIT` read Chappe's start line or the console's, `Vidocq dev console: http://127.0.0.1:<port>/`,
    from the application's output. `it-dependency-codegen`'s `ListenerFromADependencyRunsIT` and `it-devservices`'s
    `DevServicesRunGoalIT` never talk to the application: they only pass `0`. The port ranges 18090-18099 these ITs
    scanned are gone.
  - A closed port: `McpClientTest` and `McpInspectorTest` (`langchain4j-cdi-mcp-extension-dev`) need a port nothing
    listens on. They bound one, closed it, and connected to it, which any process could listen on meanwhile. They now
    call port `0`, where no socket can listen: the connection fails at once. A socket bound to a port and never
    listening is no substitute: macOS drops a connection to it (40 connects out of 40 timed out), and a full build
    failed on `timed out after 5 s` while the test used one.
  - `ChappeServerSuspendTest` keeps one probe, `new ServerSocket(port)` once the listener is suspended: binding the
    port is the check that suspending released it, and once released, another process may take it before the probe or
    the request after it. That race is inherent to checking a release, and the test says so.
  - `@VidocqTest` (vidocq-runtime-junit, main code): `VidocqTestExtension` took a port from
    `VidocqApplication.freePort()`, a `ServerSocket(0)` closed at once, gave it to the application JVM it starts, and
    waited until something listened on it. Another process could take the port first: the application then failed to
    bind, and the wait timed out. `ApplicationLaunch.command` now passes port `0` and
    `-Dvidocq.chappe.listener.default.port-file`, a temporary file `VidocqApplication.start` creates empty and deletes
    when the application stops. `ChappeServerBootstrap` writes the port the `default` listener bound to that file, a
    sibling temporary file moved over it atomically, at the start and after each resume; a write failure is a
    WARNING, never a failed start. The start polls the file, and the process with it (the application runs in its
    own JVM since BUG-20261114-07, so the test JVM has no bound address to ask). A port `@VidocqTest(properties)`
    names comes later on the command line and wins, as before; the start now reads it too, where it waited for the
    port it had picked and timed out. `freePort()` and the connect probe are gone. A first version read the port from
    Chappe's INFO start line: an application whose logging configuration keeps WARNING and above, a common one, would
    have timed out under every `@VidocqTest`. The Cassini example's `TodoResourceWarningLoggingTest` starts one with
    `.level=WARNING`, checks its output holds no start line, and gets an answer.
- **Investigations**:
  - 2026-11-14: 14 other test files of the repository pick a port the same way (`new ServerSocket(0)`, closed, bound
    later), among them `ChappeServerSuspendTest`, which needs a fixed port to check that a resume binds it again, and
    tests that start the application in another JVM, which would have to read the port from its log. Not changed
    here.
  - 2026-11-14 (`fix/test-free-ports`): fixed as above. Kept as they are, since they bind a port on purpose:
    `ChappeListenerBindingTest` and `DevConsoleExtensionTest` hold a port to show what a taken port does, and
    `DevConsoleExtensionTest.aReloadKeepsAFreePortAndPrintsTheUrlOnlyWhenItChanges` holds the port the console bound,
    once released, the same race as the suspend probe; `VidocqRunMojoTest` and `VidocqDevMojoTest` hold a port as a
    running dev session; `ProjectDoctorTest.aBoundPortIsNotFree` holds the port the doctor must report as taken.
    `JdwpAgentTest.aFreePortIsKeptSilently` needs a fixed port free when `JdwpAgent.withFreePort` probes it: a held
    port would read as busy, so the pick-and-close race is the test's own subject.
  - 2026-11-14: `VidocqApplication.freePort()` of `vidocq-runtime-junit` did the same for every `@VidocqTest`, and
    `VidocqApplicationTest` with it. Found as an open item, fixed on the same branch (see Fix). Red first:
    `VidocqTestExtensionTest` asked the application which port it was given, `expected: <0 61028> but was:
    <61028 61028>`. Unchanged, as before: an environment variable such as `VIDOCQ_HTTP_PORT`, or a
    `vidocq.http.port` in `@VidocqTest(properties)`, does not move the listener, since the extension's
    `-Dvidocq.chappe.listener.default.port` outranks the alias.

## BUG-20261114-08 — The tests of `vidocq dev` wipe the data of the running application

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/dev-test-dev-services`
- **Affected modules**: vidocq-runtime-maven-plugin (`VidocqDevMojo`, `ContinuousTesting`, `TestRunner`), vidocq-runtime-devservices-host
  (`DevServicesSession`, `DefaultDevServiceContext`, `StateFile`)
- **Symptom**: under `vidocq:dev`, continuous testing ran the tests against the dev session's own containers. A test's
  `TRUNCATE … RESTART IDENTITY` wiped the data the developer's running application had just collected in the same
  PostgreSQL. Reported by the FORGE-DASHBOARD session.
- **Minimal repro**: `vidocq dev` on an application with the PostgreSQL dev service and a test that truncates a table;
  store a row through the application, save a test file: the row is gone. In a unit test, the keys `TestRunner` handed
  each run were `DevServicesSession.foldInto` of the dev session.
- **Second path**: `vidocq:test`, or `mvn test` and an IDE's test run (the JUnit listener, host `test`), beside
  `vidocq:dev`, with `vidocq.dev.reuse=true` in `vidocq.properties` (the example of `dev-services.adoc`). Their
  providers read the reuse key as the dev session's did, so the reused container's name, a digest of the
  application's path and the container's configuration, was the dev container's, and Testcontainers handed it back:
  same symptom. With `vidocq.dev.postgres.port` set, the test run failed to bind the dev container's port instead.
- **Cause**: the design (continuous-testing spec §2.5, "Shared data"). `VidocqDevMojo.startContinuousTesting` folded
  the dev session's keys into every run, with `-Dvidocq.dev.devServices=false` so that the test JVM started none.
  `vidocq:test` and `mvn test` beside `vidocq:dev` had containers of their own (BUG-20261106-03), but only while the
  reuse key was off: no host of a test run ignored the reuse and port keys.
- **Fix**: the maintainer's decision of 2026-11-14, the Quarkus model. The plugin JVM opens a second session for the
  tests, host `vidocq:dev-tests` (`DevTestServices`, through `DevServicesSession.open` as `openDevServices` does). It
  opens at the first run (`ContinuousTesting.Launcher.prepare`, outside the worker's lock, the run shown `running`),
  every later run reuses it, and `ContinuousTesting.close` stops it after the run in flight on every path that ends
  the goal, waiting for a session still opening. It starts from a copy of the explicit values the dev session started
  from, so a URL the user set opts the tests' container out too. Its providers see `DefaultDevServiceContext.isolated()`:
  `vidocq.dev.reuse` reads `false` and `vidocq.dev.<service>[.<name>].port` unset, since a reused container is the dev
  one, found again by its name, and a pinned port is the dev container's. Its lines start with `Tests: `. Dev
  services off, or none started for the application: no test session, as before. A session that cannot open is a
  warning, and the next run tries again. `DevTestServicesTest`, `ContinuousTestingTest`, `TestRunnerTest`,
  `VidocqDevMojoTest`, `MavenSystemLoggerTest`, `DefaultDevServiceContextTest`, `DevServicesSessionTest`.
- **Fix, second path**: every host of a test run, `DevServicesSession`'s `TEST_HOSTS` (`test`, `vidocq:test`,
  `vidocq:dev-tests`), gets the isolated context, so `mvn test`, an IDE's test run and `vidocq:test` reuse no container
  and pin no host port either. `DevServicesSessionTest.noTestRunSharesAContainerWithTheApplication` (red: `vidocq:test`
  saw `reuse=true` and port `55432`).
- **Fix, files**: each host of a test run writes a pair of its own: `vidocq-dev-services-test.*` for the JUnit
  listener, `vidocq-dev-services-vidocq-test.*` for `vidocq:test`, `vidocq-dev-services-vidocq-dev-tests.*` for the
  tests of `vidocq:dev`. `DevServicesSessionTest.eachTestHostKeepsFilesOfItsOwn` (red: the `vidocq:dev-tests` session
  wrote `vidocq-dev-services-test.json`).
- **Investigations**:
  - 2026-11-14: the reuse and port keys came up while reading the providers: with `vidocq.dev.reuse=true` and
    `vidocq.dev.postgres.port=55432`, the example of `dev-services.adoc`, a second session gets the dev container back
    by name, or fails to bind the port. Not run against Docker.
  - 2026-11-14: the shared `-test` files. Their readers: `DevServicesExtension`, at the start of an application the
    JUnit listener booted (it alone sets `vidocq.devservices.state` to a test run's file; `vidocq:test` and the tests
    of `vidocq:dev` hand their runs keys, not the file), for the startup report and the dev console panel; and a person
    or tool that connects to a test database with the `.properties` (`psql`, DataGrip). No dev console, IDE file,
    doctor check or CLI command reads them, and no host stops containers through them: each session stops its own,
    in memory. So a `mvn test` beside the long-lived sessions of `vidocq:test` or of the tests of `vidocq:dev` could
    not use or stop their containers, but it misled a reader: while it ran, the `.properties` held its database's
    coordinates, and when it ended, the state file said `stopped` while their containers ran. Fixed by the files of
    each host's own, above. Two JUnit runs at once on one project still share the `-test` pair, as they share
    `target/` itself, which Maven does not support either.

## BUG-20261114-07 — Under `@VidocqTest` the application module is loaded twice: each of its `provides` is found twice, and Vaadin does not start

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/vidocq-test-boot-twin`
- **Affected module**: vidocq-runtime-junit (`ApplicationLaunch`, `VidocqTestExtension`), vidocq-runtime-core
  (`Vidocq.applicationModule`)
- **Symptom**: under `@VidocqTest`, a Vaadin 25.3 application whose module declares
  `provides com.vaadin.flow.di.InstantiatorFactory with …VaubanInstantiatorFactory` answers 500 to its first Vaadin
  request (`/?v-r=uidl&v-uiId=0`): `VaadinService.loadInstantiators` throws _Cannot init VaadinService because there
  are multiple eligible instantiator implementations: [VaubanInstantiator@7e18ced7, VaubanInstantiator@305b43ca]_. The
  same application started from its packaged distribution (`bin/`, `lib/`, `app/`) works. Reported by the
  forge-dashboard session on 0.4.0-SNAPSHOT.
- **Minimal repro** (no Vaadin): a module `demo.dup` with Foy, `uses` and `provides java.util.spi.ToolProvider with
  demo.dup.DemoTool`, and a servlet that lists the `DemoTool` instances of `ServiceLoader.load(ToolProvider.class)`
  (the context class loader, as Vaadin's `Lookup` does). Under `@VidocqTest` it lists two, of two `Class` objects:
  one defined by the `VaubanClassLoader` (application layer), one by the JDK's `AppClassLoader` (boot layer). Launched
  as `vidocq:run` launches it (`-Dvidocq.app.path=target/classes`, `target/classes` off `-p`) it lists one.
- **Cause**: `@VidocqTest` started the runtime's main with `target/classes` on the module path,
  `--add-modules ALL-MODULE-PATH` and `-Dvidocq.app.module`: the JVM resolved the application module into the boot
  layer, then `VidocqAppLayer.installFromModuleIfConfigured` re-layered it, and under `vidocq.app.layer=all` its
  libraries, into the application layer, leaving the boot copies in place. A `ServiceLoader` through the layer's loader
  walks the layer, then the parent loader, the `AppClassLoader`, whose boot-layer modules declare the same `provides`:
  every provider of a re-layered module comes twice. Vaadin's `LookupImpl.lookupAll`, and Vidocq's `ModuleLookup`,
  drop a `ServiceLoader` result whose class is already registered, but the two classes differ, so both factories stay,
  each makes an instantiator, and `loadInstantiators` refuses two. Neither hypothesis of the report: Foy's
  `@HandlesTypes` scan loads the factory through the layer's loader, the same class `ServiceLoader` finds first, and
  Vaadin drops that duplicate; the second instance is the boot copy. `vidocq:run`, `vidocq:dev` and the packaged
  scripts hand the application through `vidocq.app.path` and keep it off the module path ("a module must not be on
  both paths", `ApplicationLaunch` of the plugin): no boot copy.
- **Fix**: `ApplicationLaunch.layout` divides the path of the tests with `LayerSplit`, the rule of `vidocq:run`: the
  application module and, under `all`, the libraries the split gives it go to `-Dvidocq.app.path`; the runtime, the
  bricks and what they read stay on `-p`; the JDK modules the application layer requires join `--add-modules`.
  `vidocq.app.layer` and `vidocq.app.layer.keep` come from `@VidocqTest(properties)`; a default `all` that cannot be
  honoured falls back to `classes` with a warning, as in `vidocq:run`. `-Dvidocq.app.module` is still passed, and
  `Vidocq.applicationModule()` now looks for it in the application layer first, so the banner, the stale-index scan and
  `ApplicationLayer.applicationModule()` keep the application module. `vidocq-runtime-junit` depends on
  `vidocq-runtime-core` (`provided`) for `LayerSplit`.
- **Validation**: `VidocqTestExtensionTest` (the application module of the fixture is now a module of its own: the
  child JVM gets it in `vidocq.app.path` and not in `jdk.module.path`; red before the fix), `ApplicationLaunchTest`
  (split under `all`, `classes`, a pin, the fallback, an unreadable entry, a missing module, the JDK modules),
  `InstalledApplicationLayerTest.theNamedModuleIsTheOneOfTheApplicationLayer` (red before: `null`), and the repro
  above with the fixed jar: one provider, from the application layer.
- **Also exposed, not changed here**: the other launches that re-layer a module the boot layer resolved keep its boot
  copy too: a jlink image without an application main class (`ImageLaunch`: `--add-modules=<module>
  -Dvidocq.app.module=<module>`) and a `@VidocqMain` trampoline run from an IDE. The scratch launch
  `-p target/classes:… -Dvidocq.app.module=demo.dup`, the image's shape, lists two providers; not checked on an image.
- **Workaround before the fix**: test the Vaadin UI on the packaged distribution, or let the boot copy of the factory
  decline: `createInstantitor` returns `null` when `getClass().getModule().getLayer() == ModuleLayer.boot()` and
  `ApplicationLayer.current().isPresent()`; Vaadin skips a `null` instantiator.

## BUG-20261114-06 — A failure of a test class itself is printed with an empty method name

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/class-failure-name`
- **Affected module**: vidocq-runtime-maven-plugin (`SurefireReports`)
- **Symptom**: when a test class fails as a whole (an `@BeforeAll`, or the extension that starts the application),
  `vidocq dev` and `vidocq:test` print `FAILED com.example.library.HelloResourceTest# — IllegalStateException: The
  application stopped with exit code 1 …`: a `#` followed by nothing. The `tests` panel of the dev console shows the
  same name. Reported by the tutorial-verification session on 0.4.0-SNAPSHOT (ee4b1110).
- **Minimal repro**: `SurefireReportsTest.aClassLevelFailureIsNamedByItsClassAlone`, a Surefire report whose
  `<testcase name="">` holds an `<error>`.
- **Cause**: Surefire writes a class-level failure as a testcase with an empty `name`, and `SurefireReports` named every
  failure `<classname>#<name>`.
- **Fix**: a testcase with no name is named by its class alone. `TestResults.rerunList` already reruns the whole class
  for such a name, and still accepts the `<class>#` form. `SurefireReportsTest`, `TestResultsTest`.

## BUG-20261114-05 — A jlink image resolves only the application's module graph: resource-only modules are linked but never served

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/jlink-root-every-module`
- **Affected module**: vidocq-runtime-maven-plugin (`ImageLaunch`, `VidocqJlinkMojo`, `VidocqJpackageMojo`)
- **Symptom**: in the jlink image of a Vaadin application, `GET /VAADIN/static/push/vaadinPush-min.js`, `/aura/aura.css`
  and `/lumo/lumo.css` answer Vaadin's 404 page, while `vidocq:run` and `vidocq:package` serve them. With `@Push` the
  view never renders (`window.vaadinPush` is undefined). `jimage list` shows the files in the image (`flow.push`,
  `vaadin.aura.theme`, `vaadin.lumo.theme`).
- **Minimal repro**: modules `app` and `res` (`res` holds only `META-INF/resources/x.css`, `app` does not require it),
  `jlink --add-modules app,res --launcher app=app/app.Main`: in the image, `ModuleLayer.boot().findModule("res")` is
  empty and `getResources("META-INF/resources/x.css")` returns nothing; `--add-modules=res` finds it. With Vidocq:
  `bin/java --show-module-resolution --dry-run -m io.vidocq.runtime.core/io.vidocq.runtime.core.Vidocq` does not list
  the module; 45 of the 112 modules of the Vaadin e2e image were never resolved.
- **Cause**: `vidocq:jlink` links every staged module (`resolveAddModules`), but the image's launch rooted only the
  application module (`ImageLaunch.of`: `--add-modules=<mainModule>` without a main class, nothing beyond `-m` with
  one). A module nothing `requires` and that provides no bound service is in the image but in no layer:
  `VidocqAppLayer.installFromBootLayer` re-layers only what the boot configuration resolved. No loader sees it, so
  Foy's `ServletContext.getResource` returns `null` (Foy BUG-20261111-01 serves an image module only when the
  application's loader sees it). The jar launches root every module: `vidocq.app.path` and `ALL-MODULE-PATH`
  (`vidocq:package`). Predates `feat/websocket`; also explains Vaadin's finding 7 (`aura.css` 404 in a jlink image).
- **Fix**: `ImageLaunch.of(…, stagedModules)` bakes `--add-modules=<mainModule>,<every other staged module>` into the
  image's options for both launch shapes; `launcherArgs` always adds `--add-options`; `vidocq:jpackage` passes the
  non-JDK modules read from the `release` file of the image it packages.
- **Validation**: `ImageLaunchTest.everyStagedModuleIsResolvedAtStart` (both shapes), `ImageLaunchMojosTest`
  (jlink and jpackage arguments), and `CreateThenBuildIT`: the created application gets a resource-only module it does
  not require, and the image's own start resolves it (red before the fix, green after).
- **Workaround before the fix**: `JDK_JAVA_OPTIONS=--add-modules=flow.push,vaadin.lumo.theme,vaadin.aura.theme`.

## BUG-20261114-04 — `vidocq dev` speaks of a reload when the application dies at its first start, and never says it is down

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/dev-dead-app-message`
- **Affected module**: vidocq-runtime-maven-plugin (`ReloadAck.gate`)
- **Symptom**: the application dies before it is ready (BUG-20261114-03: a busy debug port; or a failing start). Dev mode
  prints _Tests: the application stopped before its reload completed; running the tests anyway_, even at the first
  start, where nothing was reloaded. Nothing says that the application stays down until the next source change, which
  starts it again.
- **Minimal repro**: `vidocq dev` on an application whose start throws; or, in a unit test,
  `ReloadAck.gate(file, 42, Duration.ofSeconds(5), () -> false, warnings::add).await(() -> false)`.
- **Cause**: the same gate guards the first start and each reload, and its wording was written for the reload only.
- **Fix**: the warning now reads _Tests: the application stopped before it was ready (its output above says why); it
  starts again on the next change. Running the tests anyway_. The child JVM inherits the console, so its own output
  above gives the reason. `ReloadAckTest`.
- **Investigations**:
  - 2026-11-14: found while fixing BUG-20261114-03. `recompileAndReload` replaces a dead child on the next change
    (`child.stop`, then `newChild`), so the application does start again then.

## BUG-20261114-03 — A second `vidocq dev` dies at start on the debug port, and nothing says so

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/dev-debug-port-busy`
- **Affected module**: vidocq-runtime-maven-plugin (`VidocqDevMojo`, `VidocqRunMojo`, `JdwpAgent`)
- **Symptom**: a second `vidocq dev` on the machine, its HTTP and dev console ports moved (`VIDOCQ_HTTP_PORT`,
  `VIDOCQ_DEVCONSOLE_PORT=0`) but not its debug port. The child JVM dies at start with _ERROR: transport error 202:
  bind failed: Address already in use_ and _JDWP exit error AGENT_ERROR_TRANSPORT_INIT(197)_; dev mode then prints
  _Tests: the application stopped before its reload completed; running the tests anyway_ and runs the tests. Nothing
  says the debug port was the cause. `-Dvidocq.dev.debugPort=25080` works around it.
- **Minimal repro**: a `vidocq dev` running, then a second one on another application with
  `-Dvidocq.chappe.listener.default.port=0 -Dvidocq.devconsole.port=0`; or, in a unit test, a `ServerSocket` holding
  the agent's port.
- **Cause**: both goals handed `-agentlib:jdwp=…,address=127.0.0.1:5005` to the JVM without checking the port, and the
  port had a default value, so the goal could not tell a port the user chose from the default one.
- **Fix**: `JdwpAgent.withFreePort` binds the agent's address before the fork, as the agent will. `vidocq.dev.debugPort`
  and `vidocq.run.debug.port` have no default value any more (5005 when unset). Unset and busy, the agent moves to a
  free port of the same host, with a warning that names both ports and the property; the announcement, the JVM
  argument and so the startup report carry it. Set and busy, the goal fails before the application starts, naming the
  port and the property. `0` (the JVM chooses) is not checked. `JdwpAgentTest`, `VidocqDevMojoTest`, `VidocqRunMojoTest`,
  `PluginDescriptorDevDebugTest`, `PluginDescriptorRunTest`.
- **Investigations**:
  - 2026-11-14: reported by the tutorial-verification session on 0.4.0-SNAPSHOT. A bind probe and the JDWP agent were
    compared on macOS for a loopback or wildcard holder and a loopback, wildcard or `localhost` agent: they agree in
    all five cases.

## BUG-20261114-02 — `CreateThenBuildIT` leaves the JVM of the jlink image running

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/it-jlink-orphan`
- **Affected module**: vidocq-runtime-it-create (`CreateThenBuildIT.assertStartsOnTheFrozenExtensions`)
- **Symptom**: after each run of the IT, a `.../junit-*/demo-app/target/dist/bin/java -m io.vidocq.runtime.core/...`
  process keeps running, its parent `init`, its temporary directory deleted, listening on its port. Three were found on
  the maintainer's machine on 2026-11-14; one, from a run before the IT moved to port 0, held 8080 and was taken for
  another session's process.
- **Minimal repro**: `mvn -Pit -pl vidocq-runtime-integration-tests/vidocq-runtime-it-create verify`, then
  `ps -axo pid,ppid,command | grep dist/bin/java`.
- **Cause**: the launcher jlink writes (`bin/<launcher>`) runs `$DIR/java ... "$@"` without `exec`, so the JVM is a
  child of the shell. `Process.destroy()` stopped the shell only. The launcher of `vidocq:package` uses `exec` and was
  not affected.
- **Fix**: the IT stops the descendants of the launcher first, waits for them while the launcher still runs (it reaps
  them: orphaned, they stay zombies in a CI container whose first process is `tail -f /dev/null`, which a first
  version of the fix met), fails when one of them is still running 30 s later, and stops the launcher.

## BUG-20261114-01 — The Vidocq configuration does not expand `${...}` property expressions

- **Date**: 2026-11-14
- **Status**: FIXED on `fix/config-property-expressions`
- **Affected module**: vidocq-runtime-core (`VidocqConfigImpl.getValue`)
- **Symptom**: an application whose `vidocq.properties` sets `vidocq.chappe.listener.default.port=${DASHBOARD_PORT:8080}`
  fails to start: _Vidocq startup failed in onStart chappe-bootstrap — java.lang.NumberFormatException: For input
  string: "${DASHBOARD_PORT:8080}"_, at `VidocqConfigImpl.getValue` called by `ChappeServerBootstrap.valueOrAlias`.
- **Minimal repro**:
  ```
  # src/main/resources/vidocq.properties
  vidocq.chappe.listener.default.port=${DASHBOARD_PORT:8080}
  mvn vidocq:run
  ```
- **Cause**: `VidocqConfigImpl.getValue` returned the raw value of the first source that has the key. With Ravel the
  Vidocq configuration reads Ravel's sources, raw too: the same key gave the expanded value through
  `ConfigProvider.getConfig()` and the expression as written through `VidocqConfig`, which every extension reads.
- **Fix**: `VidocqConfig` expands the MicroProfile Config property expressions the way Ravel does — `${key}`,
  `${key:default}`, nested and composed keys, `\$` for a literal `$`, an expression without a default whose key has no
  value making the property absent, a circular one an `IllegalArgumentException`, and
  `mp.config.property.expressions.enabled=false` to turn the expansion off. `PropertyExpressionsTest`,
  `VidocqConfigImplTest`.
- **Investigations**:
  - 2026-11-14: reported by another session on 0.4.0-SNAPSHOT; the `VIDOCQ_HTTP_PORT` environment variable works
    around it.

## BUG-20261113-04 — The Noether extension reports each persistence unit twice

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/duplicate-unit-names`
- **Affected module**: vidocq-runtime-noether-persistence-extension (`NoetherPersistenceExtension.onStart`)
- **Symptom**: an application with the units `ledger` and `archive` starts with "Noether Persistence: persistence
  units [ledger, archive, ledger, archive] started".
- **Minimal repro**: `vidocq-runtime-it-noether-units-per-module`, `units-run.log`.
- **Cause**: the report named the unit of every `EntityManagerFactory` bean, and `noether-jpa-cdi` gives each unit two:
  the injectable `@ApplicationScoped` one and the internal one its persistence contexts share, both on the unit's one
  factory. Only the report was wrong: one factory per unit is created.
- **Fix**: the report names each unit once, sorted. `UnitsPerModuleIT` asserts the line.

## BUG-20261113-03 — The Arquillian adapter deploys the `$$Intercepted` subclass of a bean as a second bean

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/arquillian-generated-classes`
- **Affected module**: vidocq-runtime-arquillian (`MaterializedDeployment.beanClassNames`)
- **Symptom**: an Arquillian deployment fails with _Ambiguous dependency: field TraceTestResource.service of type
  TraceTestService. Matching beans: [TraceTestService, TraceTestService$$Intercepted]_ once the build generates the
  intercepted subclass of a bean the archive adds with `addClass`. Surfaced by the Vauban fix of BUG-20261113-13
  (Vidocq/vauban), which makes the build generate `TraceTestService$$Intercepted` for the `@SpanBinding` Humboldt's
  extension adds: `HumboldtIntegrationTest` fails with that Vauban.
- **Minimal reproduction**: `MaterializedDeploymentTest.theClassesVaubanGeneratesForABeanAreNoBeanClasses`, red on
  `origin/main`: `beanClassNames()` lists `Service$$Intercepted` and `Service_ClientProxy`.
  ```sh
  ./mvnw -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-arquillian -am package -Dtest=MaterializedDeploymentTest -Dsurefire.failIfNoSpecifiedTests=false
  ```
- **Confirmed cause**: ShrinkWrap's `addClass(Bean.class)` also adds the classes whose name starts with `Bean$`, as
  inner classes, so `Bean$$Intercepted` goes into the archive. The adapter lists every class of the archive as a bean
  class, and the boot adds each one explicitly: the subclass inherits the bean's scope, and becomes a bean of the same
  types. Vauban's own scan leaves `$$` and `_ClientProxy` names out; the adapter did not. Latent before: the build
  generated the subclass only for a binding the source declares.
- **Fix**: `beanClassNames()` leaves out the classes Vauban generates for a bean, by the same names as Vauban's scan.

---

## BUG-20261113-02 — The Telemetry extension has no codegen bundle: Humboldt's Build Compatible Extension never runs at build

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/humboldt-codegen-bundle` (Vidocq/vidocq#546)
- **Affected module**: vidocq-runtime-humboldt-telemetry-extension (its descriptor named no `codegen`), and the new
  vidocq-runtime-humboldt-telemetry-extension-codegen
- **Symptom**: an application with `humboldt-telemetry` compiles with one Build Compatible Extension less than it
  starts with (_[Vauban] Discovered 9 Build Compatible Extension(s)_ at build, 10 at start, for the ten extensions
  of the tutorial application). `humboldt-cdi`, which carries `HumboldtBuildCompatibleExtension`, is not on the
  compiler's processor path, so the `@SpanBinding` that extension adds to each class and method annotated
  `@WithSpan` is not frozen in `META-INF/vauban-enhancements.properties`: the binding exists only because the
  container runs the extension again at each start. Once it no longer does (Vidocq/vauban#288), `@WithSpan` would
  produce no span, without an error.
- **Minimal reproduction**: `vidocq create -x cassini-rest -x humboldt-telemetry`, a bean with a `@WithSpan` method,
  then `mvn -X compile`: no `humboldt-cdi` on the processor path, no `target/classes/META-INF/vauban-enhancements.properties`.
  In this repository, red on `origin/main`:
  ```sh
  ./mvnw -ntp -pl vidocq-runtime-tooling,vidocq-runtime-maven-plugin test -Dtest='KnownExtensionsTest,CodegenPathsTest' -Dsurefire.failIfNoSpecifiedTests=false
  ./mvnw -ntp -pl vidocq-runtime-integration-tests/vidocq-runtime-it-humboldt-cassini test -Dtest=SpanBindingFrozenAtBuildTest
  ```
- **Confirmed cause**: every other MicroProfile extension whose brick has a Build Compatible Extension ships a
  `-codegen` bundle with the brick's `*-cdi` jar (BUG-20261030-23); Telemetry had none, so neither the plugin's
  `CodegenPathsParticipant` nor `vidocq extension add` had anything to put on the processor path.
- **Fix**: `vidocq-runtime-humboldt-telemetry-extension-codegen` carries `humboldt-cdi` and the CDI API that jar
  leaves `provided` (it brings the Interceptor and Annotation APIs). The extension's descriptor names it
  (`codegen = …`), so the catalog, the plugin and the CLI wire it; the BOM and the root `dependencyManagement`
  manage it; `vidocq-runtime-it-humboldt-cassini` and `vidocq-runtime-tck-humboldt-telemetry`, which list their
  bundles by hand, declare it. `checkpom` now asks for it in a pom that lists its bundles by hand.
- **The lesson of BUG-20261030-27** (an extension run at build and again at start): `HumboldtBuildCompatibleExtension`
  has an `@Enhancement` phase only and registers no synthetic bean, and Vauban applies the frozen patch in place of
  the replay for the classes it names. `HumboldtIntegrationTest` now asserts one span per call of a `@WithSpan`
  method.
- **Investigations**:
  - 2026-11-13: `CodegenPathsTest.theTelemetryExtensionGetsItsCodegenBundle`,
    `KnownExtensionsTest.humboldtTelemetryShipsACodegenBundle` and `SpanBindingFrozenAtBuildTest` red before the
    bundle, green with it; the IT's compilation goes from 2 to 3 discovered extensions and prints _Froze
    enhancement patch for 2 target(s)_.
  - 2026-11-13: checked by hand on an application written by `vidocq:create -Dextensions=cassini-rest,humboldt-telemetry`
    with this branch's plugin: `mvn -X process-classes` shows `vidocq-runtime-humboldt-telemetry-extension-codegen`,
    `humboldt-cdi` and `jakarta.enterprise.cdi-api` on the processor path, 3 discovered extensions, no warning, and
    `vauban-enhancements.properties` names the `@WithSpan` method of the bean.
  - 2026-11-13: not fixed here, Vauban's side: with the binding frozen, the processor still writes no
    `$$Intercepted` subclass for the bean at build (`InterceptedArtefacts.isInterceptedTarget` reads the bindings
    of the source, not those an `@Enhancement` adds), so the container generates it at start.

## BUG-20261113-01 — A named pool declared only in `vidocq.properties` gets no `@Named` DataSource bean

- **Date**: 2026-11-13
- **Status**: FIXED on `fix/datasources-from-properties`
- **Affected module**: vidocq-runtime-mansart-pool-datasources-codegen (`VidocqDataSourcesProcessor`)
- **Symptom**: an application with `vidocq.pool.archive.url` in its `vidocq.properties` and the datasources codegen
  on its processor path, but no `@VidocqDataSources`, has no `@Named("archive") DataSource` bean: the startup report
  raises MANSART-POOL-002, and an injection or a repository routed to the pool fails. `DEV_SERVICES.md`, the
  annotation's Javadoc and the processor's Javadoc all say the property alone declares the pool.
- **Minimal reproduction**: `VidocqDataSourcesProcessorTest.theNamesOfTheBuildsPropertiesNeedNoAnnotation`, red on
  `origin/main`:
  ```sh
  ./mvnw -ntp -pl vidocq-runtime-extensions/vidocq-runtime-extensions-jakartaee-web/vidocq-runtime-mansart-pool-datasources-codegen test
  ```
- **Confirmed cause**: the processor declared `@SupportedAnnotationTypes` for `@VidocqDataSources` only, and javac
  runs a processor only for the annotations present in the sources; once run, it also returned early when no element
  carried the annotation, which gave it the package of the holders.
- **Fix**: the processor runs on every compilation (`*`, claiming nothing). Without the annotation, the module
  declaration the compilation compiles anchors the names of the properties, in the first package of the module. A
  compilation without one (the tests compiled into the module, a classpath build) still needs the annotation, so the
  tests of a module never generate a second bean per name. MANSART-POOL-002 now also names the other cause: a name
  the build cannot see.
- **Investigations**:
  - 2026-11-13: found while adding a unit on a named pool to the Noether persistence IT (Vidocq/vidocq#541): the
    `archive` pool had no bean until a class carried `@VidocqDataSources("archive")`.

## BUG-20261111-01 — `vidocq-runtime-vaadin-extension` does not resolve with a Vaadin whose `flow-server` names its module

- **Date**: 2026-11-11
- **Status**: FIXED on `fix/vaadin-module-names`
- **Affected module**: vidocq-runtime-vaadin-extension
- **Symptom**: the extension's descriptor `requires flow.server`, the name Java derives from the file name of
  `flow-server` up to Vaadin 25.3. Vaadin is giving its jars an `Automatic-Module-Name` (vaadin/flow#26731, a draft
  on 2026-11-11), which makes `flow-server` the module `com.vaadin.flow.server`. With such a Vaadin, resolution fails:
  `FindException: Module flow.server not found, required by io.vidocq.runtime.extensions.jakartaee.web.vaadin`.
  Found while reviewing vaadin/flow#7482 against Vidocq/vidocq#256, before any Vaadin release had the name.
- **Minimal reproduction**: `FlowServerModuleNamesTest.theAutomaticModuleNameIsRead`: the real `flow-server` jar with
  `Automatic-Module-Name: com.vaadin.flow.server` in its manifest, this module and the Servlet API in a layer. It
  fails as above with the descriptor of `origin/main`.
- **Fix**: the descriptor requires both names, `static`: a static dependence is read when the module is resolved,
  and the application, which requires Vaadin, resolves the one its Vaadin has. `javac` and the release's `javadoc`
  need a module for every name a `requires` gives, a static one included, so the build compiles an empty
  `com.vaadin.flow.server` from `src/module-stubs` first (execution `module-stubs`) and passes it to both with
  `--upgrade-module-path`. It is never packaged. `javadoc` refuses that option with `--release`, so the extension's
  `javadoc` takes `--source` instead. `FlowServerModuleNamesTest` loads `ModuleLookupInitializer` against both names.
- **Known noise**: the second execution of `compiler:compile` makes Maven log `Overwriting artifact's file` once per
  build; the jar holds `target/classes` only.

## BUG-20261109-02 — every example test that boots the runtime fails when something already listens on 8080

- **Date**: 2026-11-09
- **Status**: WORKED AROUND in the tests on `fix/example-tests-free-port`; the fixed port in the examples'
  own configuration is left as it is (see **Not fixed here**).
- **Reported by**: a full `mvn install` on a machine running an unrelated container that publishes 8080:
  `TransactionalRollbackTest.aWriteInsideAFailedTransactionIsRolledBack` fails with
  `ChappeException$ServerException: Failed to bind to 0.0.0.0:8080`, caused by
  `java.net.BindException: Address already in use`.
- **Minimal reproduction**: `docker run -p 8080:80 nginx`, then
  `./mvnw -pl vidocq-runtime-examples/vidocq-runtime-petstore-example test`.
- **Cause**: six examples pin `vidocq.chappe.listener.default.port=8080` in their own
  `src/main/resources/vidocq.properties`, and the three tests that boot the runtime inherit it.
  None of them makes an HTTP call -- the petstore test's own javadoc says "no HTTP call is needed to
  reproduce the defect" -- so the listener is incidental to every one of them.
- **Fix (tests only)**: each test takes a port from `new ServerSocket(0)` and sets
  `vidocq.chappe.listener.default.port` as a system property before booting, restoring the previous value
  afterwards. `SystemPropertiesConfigSource` has ordinal 400 against `PropertiesFileConfigSource`'s 100, so
  the property wins over the packaged file and no production configuration changes.
  Not chosen: overriding the port on the Maven command line, which would have to be repeated by every
  contributor and every CI job rather than fixed once.
- **Not affected**: `vidocq-runtime-cyrano-rest-client-example`, which already uses 18091, and the dev
  console, which already binds a dynamic port (its snapshot test matches `http://127.0.0.1:(\d+)/`).
- **Not fixed here**: that an example ships a fixed application port its tests then inherit. A test that
  needs no HTTP listener should arguably not start one at all, and an example that demonstrates a port
  should not dictate the test's. Both are design questions for the maintainers rather than a workaround.
- **Known limitation**: `ServerSocket(0)` then bind is a race -- the port can be taken in between. It is
  the portable approach and `ChappeServer.bindWithRetry` gives some slack, but it is not a guarantee.

## BUG-20261109-01 — a catalog `requires` was demanded of every module depending on the extension, not only of its users

- **Date**: 2026-11-09
- **Status**: FIXED on `fix/module-info-catalog-requires`, in the same change that introduced the defect
  (BUG-20261107-02 made the catalog's `requires` reach the check for the first time, so nothing had ever needed
  to decide whom they apply to).
- **Reported by**: the reactor build itself, twice. `vidocq:check-module-info` failed
  `vidocq-runtime-cassini-rest-extension-dev` with five directives — `requires jakarta.cdi`, `jakarta.inject`,
  `jakarta.ws.rs`, `jakarta.json.bind` and `requires static java.compiler` — although that module imports only
  `io.vidocq.cassini.spi.http`, two `io.vidocq.runtime.extensions.jakartaee.core.cassini.live` packages and four
  dev-console SPI types. It then failed `vidocq-runtime-it-cassini-rest`, a test-only module whose
  `target/classes` holds no class file at all.
- **Minimal reproduction**: `ModuleRequirementsCollector.applicableTo(Set.of(Requirement.requires(
  "jakarta.ws.rs", "...")), outputDirectory)` returned the requirement for any module, whatever its classes
  referred to.
- **Cause**: `applicableTo` filtered only `opens`, by whether the module contains the package. The catalog's
  `module-info.requires` states what an application USING the extension's API must declare; a module that
  depends on the extension to extend it uses none of it. Adding those directives would have made the module read
  four modules it never touches — against the workspace rule of minimal `requires`.
- **Fix**: `ReflectedPackages.referencedPackages(Path)` collects every package the compiled classes refer to
  (constant-pool owners plus field and method descriptor types), `ModuleRequirementsCollector.modulesInUse`
  maps the module path through `ModuleFinder.compose(of(artifacts), ofSystem())` and keeps the modules whose
  unqualified exports intersect it, and `applicableTo` filters `REQUIRES`/`REQUIRES_STATIC` by that set.
  Composing with the system modules is what keeps `requires static java.compiler` exactly for a module that
  really uses `javax.annotation.processing`.
- **Not affected**: the descriptor path. The only `module-requirements.properties` in the tree declares `opens`
  alone, so no descriptor-derived `requires` exists to filter.
- **Second iteration**: an output directory without a single class file first returned "cannot tell", which kept
  every requirement and broke the test-only IT module. Any real class refers to at least `java.lang`, so an
  empty result means no main code and the module needs nothing. `null` — keep everything, stay conservative —
  is now reserved for a failed scan or a module path `ModuleFinder` rejects.

---

## BUG-20261108-01 — `vidocq pom convert --to parentless` keeps `${project.parent.version}` on the plugin's dev-service dependencies

- **Date**: 2026-11-08
- **Status**: FIXED on `fix/pom-convert-project-parent-version`: `PomConverter` rewrites `${project.parent.version}` to
  `${vidocq.version}` on the dependencies of the Vidocq plugin going parentless, and back going to the parent (a literal
  version is kept). `PomConverterTest.theDevServicesOfThePluginFollowTheVersionTheStyleUses` compares both ways, byte for
  byte, with what `PomEditor.addPluginDependency` writes in each style. A pom already converted by an earlier CLI is
  repaired by running `convert --to parentless` again, and `--check` reports it (exit 1) since it would change.
- **Affected module**: vidocq-runtime-tooling (`PomConverter`), a #380 follow-up
- **Symptom**: on a pom written under `vidocq-runtime-parent` whose dev services `extension add` wired on the plugin
  (`<dependencies>` of `vidocq-runtime-maven-plugin`, at `${project.parent.version}`), `vidocq pom convert --to parentless`
  removes the parent, writes `vidocq.version`, `maven.compiler.release`, the BOM imports, the plugin's version and
  `<extensions>true</extensions>`, and leaves the `${project.parent.version}` lines. Without a parent the expression
  resolves to nothing and Maven stops before any goal:
  `[ERROR] Internal error: java.lang.IllegalArgumentException: Invalid Collect Request: io.vidocq.runtime:vidocq-runtime-maven-plugin:jar:0.4.0-SNAPSHOT () -> [io.vidocq.runtime:vidocq-runtime-devservice-postgres:jar:${project.parent.version} (runtime), io.vidocq.runtime:vidocq-runtime-devservice-keycloak:jar:${project.parent.version} (runtime), io.vidocq.runtime:vidocq-runtime-devservice-otel:jar:${project.parent.version} (runtime)]`.
  `--check` reports nothing on the converted pom, and a second `convert` says the pom already has the style.
- **Minimal reproduction** (CLI 0.4.0-20261108.002217-65): `vidocq create --name demo --with-parent -x cassini-rest
  && cd demo && vidocq extension add mansart-data mansart-pool mansart-transactions flyway-migration` writes the
  PostgreSQL dev service at `${project.parent.version}`; `vidocq pom convert --to parentless` leaves that line
  (`grep project.parent.version pom.xml`), and `./mvnw package` fails as above. Seen first on the tutorial's step-11
  pom, with its three dev services.
- **Cause**: `PomConverter` rewrites the elements the scaffolder writes and leaves the rest of the pom where it is, by
  design. The dev-service dependencies are `extension add`'s, which names `${project.parent.version}` under the parent
  and `${vidocq.version}` on a parentless pom: the converter removes the parent the expression points to and does not
  rewrite the expression.
- **Fix to make**: `--to parentless` rewrites `${project.parent.version}` to `${vidocq.version}`, and `--to parent` the
  reverse, at least under the Vidocq plugin's `<dependencies>`; `--check` flags the expression; a test with a pom
  holding the three dev services, both ways, byte for byte against what `extension add` writes in each style.
- **Reported by**: the tutorial replay on the 2026-11-08 SNAPSHOT (vidocq-tutorial `FINDINGS.md` 12.5).

## BUG-20261108-02 — Under Maven 3.10.0 every build with Flyway prints a `prefixes.txt … HTTP Status: 401` WARNING

- **Date**: 2026-11-08
- **Status**: DOCUMENTED as expected on `fix/app-class-lifecycle`: `vidocq-runtime-extensions.adoc#flyway-prefixes-warning`.
  `flyway-parent` 13.9.0, the version the backend uses, still declares the repository.
- **Affected module**: the Maven wrapper `vidocq create` writes (3.10.0 since #487), with the `flyway-migration` extension; docs
- **Symptom**: from the first build after `vidocq extension add flyway-migration`, with or without `vidocq-runtime-parent`,
  with or without `-U`:
  `[WARNING] Could not transfer metadata /.meta/prefixes.txt from/to flyway-community-db-support (https://maven.pkg.github.com/flyway/flyway-community-db-support): HTTP Status: 401`,
  right after `[INFO] Loaded 24409 auto-discovered prefixes for remote repository maven-central`. The build goes on.
  Maven 3.9.16 printed nothing: the tutorial's "no `[WARNING]`" checks of 2026-11-02 ran on it.
- **Minimal reproduction**: `vidocq create --name demo -x cassini-rest && cd demo && vidocq extension add mansart-data
  mansart-pool mansart-transactions flyway-migration && ./mvnw package`.
- **Cause**: Maven 3.10.0 discovers the remote-repository-filter prefixes (`.meta/prefixes.txt`) of every repository the
  resolution meets; `flyway-parent` (10.x and 11.x) declares the GitHub Packages repository `flyway-community-db-support`,
  which answers 401 to anonymous reads, and the resolver logs the miss as a warning. Nothing in Vidocq declares that
  repository.
- **Options**: say the warning is expected in `getting-started.adoc`, next to the javac last-round warnings
  (BUG-20261024-11); or find the resolver switch that turns the prefixes discovery off for a repository and let
  `vidocq create` write it in `.mvn/maven.config`.
- **Reported by**: the tutorial replay on the 2026-11-08 SNAPSHOT (vidocq-tutorial `FINDINGS.md` 12.5).

## BUG-20261107-01 — `vidocq:build-info` writes Maven's placeholder `1980-02-01T00:00:00Z` as the build time

- **Date**: 2026-11-07
- **Status**: FIXED on `fix/380-build-time-placeholder` (Vidocq/vidocq#513, a #380 follow-up): `BuildIdentity.buildTime`
  treats a timestamp that is not after `MAVEN_PLACEHOLDER` (1980-02-01T00:00:00Z) as no build time and writes the
  time of the build; `BuildIdentityTest.theBuildTimeIsTheOutputTimestampWhenTheProjectSetsOne` covers the placeholder,
  `1980-01-01` and epoch `0` (red first).
- **Module**: `vidocq-runtime-maven-plugin`, `io.vidocq.runtime.maven.buildinfo.BuildIdentity.buildTime`,
  `VidocqBuildInfoMojo`.
- **Reported by**: the retest of the #380 stack on the published 0.4.0-SNAPSHOT (2026-11-07, Maven 3.10.0).
- **Symptom**: an application built without `vidocq-runtime-parent` gets
  `META-INF/vidocq/build-info/<artifactId>.properties` with `git.build.time=1980-02-01T00:00:00Z`, so the banner, the
  dev console and `vidocq info` say `built 1980-02-01T00:00:00Z`. The parent's `git-commit-id-maven-plugin` writes
  the current time.
- **Minimal reproduction**: `vidocq create --name andy-demo -x cassini-rest` (CLI 0.4.0-SNAPSHOT of 2026-11-07), then
  `./mvnw verify` with the wrapper's Maven 3.10.0, then
  `cat target/classes/META-INF/vidocq/build-info/andy-demo.properties`.
  `./mvnw help:evaluate -Dexpression=project.build.outputTimestamp -q -DforceStdout` prints `1980-02-01T00:00:00Z`
  although the pom sets nothing.
- **Cause**: Maven 3.10 gives `project.build.outputTimestamp` a default (reproducible builds by default): the oldest
  zip date, 1980-02-01. The mojo takes the parameter as the pom's choice, and `BuildIdentity.buildTime` writes it as
  it is.
- **Fix**: a timestamp that is not after 1980-02-01 is Maven's placeholder, not a build time: `buildTime` treats it
  as absent (the current time, to the second) and keeps a real timestamp the pom sets.

---

## BUG-20261107-02 — `check-module-info` never checks the `requires` the extension catalog states

- **Date**: 2026-11-07
- **Status**: FIXED on `fix/module-info-catalog-requires`: `ModuleRequirementsCollector.collect` now unions the
  extension catalog with the `module-requirements.properties` descriptor, through a new
  `KnownExtensions.moduleRequires(id)` that mirrors `resourcePackagesToOpen`.
  `ModuleRequirementsCollectorTest.catalogRequiresAreCollectedForAnExtensionWithoutADescriptor` (red: `expected:
  <[io.vidocq.cassini.api, jakarta.cdi, jakarta.inject, jakarta.json.bind, jakarta.ws.rs]> but was: <[]>`; green
  with the collector change), plus `anArtifactOutsideTheCatalogContributesNothing` and
  `catalogRequiresStaticIsNotReportedAsAPlainRequires`. Module tests: 1925, 0 failures; `verify` green including
  the license headers.
- **Reported by**: the port of `social-metricbot` from Open Liberty to Vidocq 0.4.0-RC2, where the module-info
  goals had nothing to say about an application using `cassini-rest`, `mansart-data` and `foy-servlet`.
- **Minimal reproduction**: pass a `cassini-rest` artifact to `ModuleRequirementsCollector.collect` -- any
  `org.apache.maven.artifact.Artifact` with groupId `io.vidocq.runtime.extensions.jakartaee.core` and artifactId
  `vidocq-runtime-cassini-rest-extension`. The result is empty, although
  `cassini-rest.module-info.requires` in `extension-catalog.properties` names five modules. Since
  `vidocq:check-module-info` reports what the collector returns, it cannot report any of them missing.
- **Cause**: an extension's module requirements live in two unrelated descriptors.
  `META-INF/vidocq/extension.properties`, aggregated into the generated `extension-catalog.properties`, carries
  `module-info.requires`, `module-info.requires.static`, `module-info.opens-application-package` and
  `module-info.opens-resource-package`; three ids use `module-info.requires` (`cassini-rest`, `ibarra-pages`,
  `peano-el`). `META-INF/vidocq/module-requirements.properties` carries `requires`, `opens`, `opens.to.<module>`
  and `reason.<name>`, and exactly one extension of 24 ships it (`migration`). `KnownExtensions` reads the first,
  `ModuleRequirementsCollector` and `ModuleRequirementsDescriptor` read only the second, so the catalog's
  `requires` never reach the check. Not chosen: generating `module-requirements.properties` into every extension
  jar at build time, which would keep two formats alive and make the catalog a build input to itself.
- **Not affected**: the `opens` half. `ReflectedPackages` derives it from the compiled classes (`@Path`,
  `@Provider`, entity annotations, private interceptor methods), which is stronger than a descriptor flag, and
  `complete-module-info` writing only `opens` is deliberate.
- **Also fixed on this branch**: `requires static` -- see BUG-20261107-03.

---

## BUG-20261107-03 — `Requirement` cannot express `requires static`, so two catalog entries stay unchecked

- **Date**: 2026-11-07
- **Status**: FIXED on `fix/module-info-catalog-requires`: `Requirement.Kind` gains `REQUIRES_STATIC` and
  `ModuleInfoRequirements` keeps the subset of declared `requires` carrying the `STATIC` modifier, so satisfaction
  is asymmetric -- a static requirement accepts either flavour, a plain one accepts only a non-static declaration.
  Four tests in `ModuleInfoRequirementsTest`;
  `ModuleRequirementsCollectorTest.catalogRequiresStaticIsNotReportedAsAPlainRequires` keeps its invariant (a
  `mansart-data` artifact must not contribute `java.compiler` as a plain `requires`) and additionally asserts the
  static kind.
- **Reported by**: the same `social-metricbot` port, while fixing BUG-20261107-02.
- **Minimal reproduction**: `cassini-rest.module-info.requires.static = java.compiler` and
  `mansart-data.module-info.requires.static = java.compiler` in `extension-catalog.properties`. There is no way to
  pass either to `vidocq:check-module-info`: `Requirement.Kind` in `vidocq-runtime-codegen-commons` is
  `{REQUIRES, OPENS}`, so a static requirement can only be reported as a plain one, which would demand a stronger
  directive than the extension needs and would fail an application that correctly wrote `requires static`.
- **Cause**: `requires static` was never modelled. `Requirement` has two kinds, and
  `ModuleInfoRequirements.missing(...)` compares names without looking at
  `ModuleDescriptor.Requires.modifiers()`, so `STATIC` is invisible on both sides -- in what the catalog asks for
  and in what the application's `module-info` already declares.
- **Fix**: a third kind (`REQUIRES_STATIC`) rather than a boolean on `Requirement` -- adding a constant
  breaks exactly two exhaustive switches, while changing the record's components breaks every call site; read
  `module-info.requires.static` in `KnownExtensions.moduleRequires`'s sibling accessor, and make
  `ModuleInfoRequirements.missing(...)` treat a declared `requires static X` as satisfying a static requirement
  for `X` -- and a declared plain `requires X` as satisfying it too, since it is stronger.

---

## BUG-20261106-04 — The Mansart Data dev panel was dropped from an application without JTA

- **Date**: 2026-11-06
- **Status**: FIXED on `fix/mansart-data-dev-panel-jta`: `VidocqDevMojo.resolveRuntime` resolves a companion through
  `DevModules.WITHOUT_OPTIONAL`, which leaves out an optional dependency and everything under it.
  `DevModulesTest.aCompanionsOptionalDependenciesAreNotResolved` (red with a filter that keeps every node, then
  green). End to end: `vidocq:dev` of this branch's plugin on the RC2 todo application without
  `mansart-transactions` adds `vidocq-runtime-mansart-data-extension-dev`, with no warning, and the dev console's
  `mansart-data` panel is live; the 0.4.0-RC2 plugin on the same copy prints the warning.
- **Reported by**: the replay of the 0.4.0-RC2 CLI for the release post.
- **Minimal reproduction**: `vidocq create todo -x mansart-data` (no `mansart-transactions`), then `vidocq dev`:
  `[WARNING] Dev tools: io.vidocq.runtime.extensions.jakartaee.web:vidocq-runtime-mansart-data-extension-dev:0.4.0-RC2
  needs jakarta.transaction:jakarta.transaction-api, which the application does not have; its panel shows its boot
  facts only`. `vidocq extension add mansart-transactions` makes it go away.
- **Cause**: the `-dev` module is built to do without JTA: `jakarta.transaction-api` is `<optional>` in its pom, its
  `module-info` `requires static jakarta.transaction`, and only `JtaDemarcation` names a JTA type; without it, a
  write commits. But `vidocq:dev` resolved the companion as the root of a `CollectRequest`, and Maven Resolver drops
  optional dependencies only below the root's direct ones: the JTA API came back, and `DevModules` took it for
  runtime code the application lacks. Not chosen: making `extension add mansart-data` bring `mansart-transactions`
  (Mansart Data does not need JTA), or making the `-dev` module bring the API (it would put an API on the module path
  that the application does not have).
- **Not fixed here**: on a project with no `@Repository` yet, the boot still warns `Mansart Data: no @Repository
  interface discovered. Did the APT processor (mansart-data-processor) run on the application module?`. Telling
  that case apart from a processor that did not run needs either a marker from `mansart-data-processor`, in the
  Mansart repository, or a scan of the application's classes. Neither is a small change.

---

## BUG-20261106-03 — `vidocq test` overwrote the dev session's dev services files

- **Date**: 2026-11-06
- **Status**: FIXED on `fix/dev-services-file-per-session`: a test run's hosts, `vidocq:test` and the JUnit
  listener's `test`, write `target/vidocq-dev-services-test.json` and `-test.properties`; `vidocq:dev` and
  `vidocq:run` keep `target/vidocq-dev-services.json` and `.properties`. The application finds its file through
  `vidocq.devservices.state`, which every host sets to its own session's file, so no reader changed.
  `DevServicesSessionTest.aTestRunLeavesTheFilesOfTheApplicationsSessionAlone` (red: the test session wrote the
  project's files) and `theApplicationsHostsKeepTheProjectsFiles`.
- **Reported by**: the replay of the 0.4.0-RC2 CLI for the release post.
- **Minimal reproduction**: `vidocq dev` on an application with PostgreSQL, its container on port 32783; in another
  terminal, `vidocq test --once`, which starts its own container on 32785 and stops it at the end; then save a main
  source. The reload's report reads `postgres (... localhost:32785) — vidocq:test, stopped`, although the dev
  container still runs and the application still uses it.
- **Cause**: `DevServicesSession.open` wrote `<basedir>/target/vidocq-dev-services.json` and `.properties` whatever
  the host, so every session of a project shared one pair of files. The test session overwrote the dev session's
  state, then rewrote it `stopped` on close; the dev application, pointed at the same path by
  `vidocq.devservices.state`, read it again on its next reload. The Dev services page listed it as a limitation
  ("One state file per project").

---

## BUG-20261106-02 — The line of a failed test is never printed for a Vidocq application

- **Date**: 2026-11-06
- **Status**: FIXED on `fix/failed-test-line-module-frames`: `SurefireReports.FRAME` accepts the up to two segments
  the JDK puts before the class, the class loader and the module with its version. `SurefireReportsTest`: the
  frame shapes the JDK prints (red on the real frame, then green).
- **Affected modules**: vidocq-runtime-maven-plugin (`vidocq:dev`, `vidocq:test`)
- **Reported by**: the replay of the 0.4.0-RC2 CLI for the release post.
- **Symptom**: a red run prints
  `FAILED com.acme.todo.todos.TodoResourceTest#createsATodo(VidocqApplication) — AssertionFailedError: a new todo
  answers 201 Created ==> expected: <201> but was: <200>` with no `(TodoResourceTest.java:18)`, although 0.4.0-RC2's
  What's new and the Continuous testing page promise the line of the test where it failed (BUG-20261030-08).
- **Minimal reproduction**: a created application with a `@VidocqTest` whose assertion fails, under `vidocq dev` or
  `vidocq test --once`. The stack trace in `target/vidocq-dev-tests.log` reads
  `at com.acme.todo@1.0.0-SNAPSHOT/com.acme.todo.todos.TodoResourceTest.createsATodo(TodoResourceTest.java:18)`.
- **Cause**: the frame pattern, `at ([\w.$]+)\.[\w$<>]+\(([\w$]+\.java:\d+)\)`, expected the class right after `at`.
  `StackTraceElement.toString` puts the module and its version before it (`com.acme@1.0/`), and sometimes the class
  loader (`app//`, `loader/module@1.0/`). A Vidocq application's tests always run in a named module, so no frame of
  the test class ever matched; the tests of BUG-20261030-08 only used class-path frames.

---

## BUG-20261106-01 — The first continuous-test run of a new project fails offline under `vidocq dev`

- **Date**: 2026-11-06
- **Status**: FIXED on `fix/dev-tests-offline-first-run`: a run that misses an artifact offline runs again once
  online, with the same tests (`TestRunner.Running`, `TestRunner.missedOffline`). `TestRunnerTest`: an offline miss
  runs again online, another failure does not, and a cancel between the offline miss and the online run stops the
  run (red, then green).
- **Affected modules**: vidocq-runtime-maven-plugin (`vidocq:dev`, `vidocq:test`)
- **Reported by**: the replay of the 0.4.0-RC2 CLI for the release post, on a machine whose local repository had no
  0.4.0-RC2 test artifacts yet.
- **Symptom**: on the first change after `vidocq dev` starts a new project, the console says
  `Tests: the run failed (change), see target/vidocq-dev-tests.log`, and the log holds only Maven's
  `Cannot access central (…) in offline mode and the artifact io.vidocq.runtime:vidocq-runtime-junit:jar:0.4.0-RC2 has
  not been downloaded from it before`, the same for `vidocq-runtime-devservices-junit`. No test ran.
- **Minimal reproduction**: `vidocq create --name todo -x cassini-rest -x mansart-data -x flyway-migration` with a
  local repository that never resolved the project's test scope, `vidocq dev`, then save any source. By hand, on a
  fresh `-Dmaven.repo.local`: `./mvnw process-classes` passes, then
  `./mvnw -o process-test-classes surefire:test -DfailIfNoTests=false` fails on the Surefire plugin and the
  test-scope jars, with the wording above; the same command online resolves them and runs the tests.
- **Cause**: continuous testing runs every run offline (`mvn -o`, spec §2.3), while `vidocq dev` runs
  `process-classes vidocq:dev`, which resolves the runtime scope only: the test-scope jars, Surefire and its JUnit
  provider are downloaded by nothing before the first run. The limit was documented ("as they are after one
  `mvn test`"), but a new project meets it on its first change. `vidocq:test` resolves the test scope at start (#166),
  so it met it only for Surefire's provider (#138).

---

## BUG-20261105-03 — A checkpoint fails once the Telemetry extension has exported over OTLP

- **Date**: 2026-11-05
- **Status**: FIXED on `feat/telemetry-crac-suspend` (with Vidocq/humboldt#55,
  humboldt BUG-20261105-01): `HumboldtExtension` is `Suspendable`; `suspend()` calls
  `AutoConfiguredHumboldt.release()`, which exports what is queued and closes the OTLP exporters' HTTP clients, and
  `resume()` calls `reopen()`. `HumboldtSuspendTest` (red with `suspend()` doing nothing, then green).
- **Reported by**: the review of the extensions before the CRaC benchmark: an application with the Telemetry
  extension and `OTEL_SDK_DISABLED=false` holds, in each OTLP exporter, a JDK `HttpClient` with its connection to
  the collector and its selector.
- **Minimal reproduction**: a probe in the CI runners' image, Zulu CRaC 25.0.4.1 with `-XX:CRaCEngine=warp`: one
  request to a stub collector, then `jdk.crac.Core.checkpointRestore()` fails with a `BusySelectorException` and
  `CheckpointOpenSocketException`s for the epoll descriptors and the `SocketChannel` to the collector. After
  `HttpClient.close()` the checkpoint is taken, and after the restore a new client gets HTTP 200.
- **Cause**: the Telemetry extension was not `Suspendable`, and Humboldt had no way to let its exporters'
  connections go while the SDK stays the same object.

---

## BUG-20261105-02 — `-Dvidocq.http.port` was ignored when the application's file set the listener key

- **Date**: 2026-11-05
- **Status**: FIXED on `fix/http-port-alias-precedence`: `ChappeServerBootstrap.resolveListeners` reads the key of
  the `default` listener and its `vidocq.http.*` alias from whichever the source of highest ordinal sets; within one
  source the listener key still wins. `ChappeServerBootstrapTest` (red, then green: four cases across sources).
- **Reported by**: the CRaC end-to-end runs (Vidocq/vidocq#488): `EXTRA_OPTS=-Dvidocq.http.port=…` left the cassini
  example on 8080.
- **Minimal reproduction**: an application whose `vidocq.properties` sets `vidocq.chappe.listener.default.port=8080`
  (the cassini example does), started with `-Dvidocq.http.port=9099` or `VIDOCQ_HTTP_PORT=9099`: it listens on 8080,
  with no message.
- **Cause**: the listener key won over the alias whatever their sources, so the documented alias, from the system
  properties (ordinal 400) or the environment (300), could not override the key of a file (100). The same held for
  `vidocq.http.host`. `vidocq start --port` was not affected: it passes the listener key itself.

---

## BUG-20261105-01 — The CRaC nightly died on a Zulu download cut short, piped straight into tar

- **Date**: 2026-11-05
- **Status**: FIXED on `fix/crac-zulu-download`: `install-zulu-crac.sh <dest-dir>`, used by `ci.yml` (the CRaC
  extension's deploy pass) and `crac.yml`, downloads the archive of the runner's architecture into a file, extracts it
  only once it matches the SHA-256 pinned in the script, and tries four times with a growing pause;
  `install-zulu-crac-test.sh` (red, then green; offline, a `file://` archive and a curl that cuts downloads short),
  run by `pr.yml`.
- **Reported by**: the scheduled `crac.yml`, run 3034 (2026-11-05 03:30 UTC), on `main` at `6772de82`, which had
  passed at 02:14 (run 3032).
- **Minimal reproduction**: any run of `crac.yml`, or of `ci.yml` on `main`, while the download of the Zulu CRaC
  archive from `cdn.azul.com` is cut: the step "Install Azul Zulu CRaC" fails after 3 min 24 s with
  `curl: (56) OpenSSL SSL_read: … unexpected eof while reading`, `gzip: stdin: unexpected end of file`,
  `tar: Error is not recoverable`; nothing is built.
- **Cause**: both workflows ran `curl -fsSL <archive> | tar -xz`: one attempt for a 238 MB download, and no check
  of what tar was given. Azul publishes no checksum for this build (no `.sha256` on the CDN; its metadata API does
  not list `zulu25.36.205-ca-crac-jdk25.0.4.1`): the pins are the hashes of the archives downloaded on 2026-11-05, at
  the sizes the CDN announces and gzip-tested; the x64 one was also downloaded independently in the runners' image
  (`node:24.22.1-trixie`), same hash, and `java -version` answers there.
- **Not fixed here**: the archive is still downloaded on every run; caching it would remove most of the exposure.

---

## BUG-20261102-06 — A hot reload kept the stopped layer reachable through Ravel's configuration registry

- **Date**: 2026-11-02
- **Status**: FIXED on `fix/release-ravel-config-at-stop`: the new `RavelConfigExtension` (Ravel config extension)
  takes hold of the layer's class loader in `configure` and, in `onStop`, has Ravel's resolver release the
  configuration of that loader and of every loader built on top of it (`LayeredConfigProviderResolver`, new in the
  Ravel SPI; another resolver gets a plain `releaseConfig` of the configuration of the layer's loader);
  `RavelConfigExtensionTest` (red, then green: the class loader of a stopped layer is collected after `System.gc()`).
- **Reported by**: the static audit of item 23 of vidocq-workspace#40 (heap dump of 15 hot reloads: 16 live
  `VaubanClassLoader` instead of 1)
- **Minimal reproduction**: `vidocq dev` on any application with the Config extension, touch a source file 15
  times, count the `VaubanClassLoader` instances with `jcmd <pid> GC.class_histogram`.
- **Cause**: `ConfigProviderResolver.instance` (a JVM-wide static) holds `RavelConfigProviderResolver.registry`, a map
  keyed by class loader whose values reference their key (the generated converters and custom sources of the
  layer); nothing called `releaseConfig`, so no entry was ever removed. A weak key cannot work, the value keeps the
  key alive, and the entries were keyed by loaders the container builds on top of the layer's
  (`VaubanContainerBuilder$CompositeClassLoader`), which the standard `releaseConfig(Config)` cannot reach without
  the `Config` itself.
- **Weld/Liberty**: unaffected, the extension is a Vidocq runtime extension; the resolver behaves as before.

## BUG-20261102-05 — A JSP could not read a @Named CDI bean by name

- **Date**: 2026-11-02
- **Status**: FIXED on `fix/jsp-cdi-named-beans`: the Pages extension registers `CdiBeanNameELResolver` in the
  pages' EL context once the application is deployed, before its first request (`CdiPageNames`, through the Foy
  extension's new `ServletDeploymentListener`); `BistroPagesTest.aPageReadsANamedCdiBeanByName` (red, then green,
  with `-Dpeano.reflection=deny`), `CdiBeanNameELResolverTest`.
- **Reported by**: the wave-4 reflection work on Peano/Ibarra (BUG-20261102-03 follow-up)
- **Minimal reproduction**: `specials.jsp` with `${specials.dish}` on a `@Named("specials") @RequestScoped` bean
  rendered an empty string: no resolver of the page `ELContext` knew CDI bean names.
- **Cause**: Jakarta EE has the CDI container add its EL resolver to the JSP application
  (`BeanManager.getELResolver()`); nothing did in Vidocq, and Vauban, a CDI Lite container, has no
  `getELResolver()` (it throws). The resolver is now built on `BeanManager.getBeans(String)`.
- **`@Dependent` beans**: an instance a page creates by name is destroyed when the request ends (`RequestDependents`,
  a `CdiContextListeners` provider Foy registers ahead of the application's listeners;
  `BistroPagesTest.aDependentBeanReadByNameIsDestroyedAtTheEndOfItsRequest`, `RequestDependentsTest`). CDI Full
  destroys it when the EL evaluation completes; JSP has no hook there, the request is the nearest end.
- **Known limit**: names with dots (`@Named("a.b")`) are not split into a bean name and properties.
- **Weld/Liberty**: unaffected — the registration lives in the Vidocq Pages extension only.
## BUG-20261102-04 — Production boots read the Mansart Data catalogue and created holder beans by reflection

- **Date**: 2026-11-02
- **Status**: FIXED on `perf/holder-factories-lazy-catalogue`. (1) `MansartPoolExtension` and `HumboldtExtension` add their holder bean with `addBeanClass`, which Vauban creates through its constructor by reflection (the dev console's coverage panel: "reflection"); each now also registers `addFactory(Holder.class, Holder::new)` (`MansartPoolExtensionTest.theHolderIsCreatedByItsFactoryNotByReflection`, `HumboldtHolderFactoryTest`, both red before; the tests read the builder's factories with a test-only `--add-opens`). (2) `MansartDataIntegrationExtension` built the catalogue — every repository method, generic signature and entity model, by reflection — at every boot, production included; it now keeps what `onStart` found and builds the catalogue the first time the dev console (`MansartDataLive.catalogue()`) or a startup report in dev or at `detailed` asks for it. A production report at `summary` or `off` reads `N repositories` and never builds it, so MANSART-DATA-001 is not raised there (`inProductionTheCatalogueIsNeverBuilt`, `theDevConsoleBuildsTheCatalogueOnDemandOnce`, `aDetailedProductionReportBuildsTheCatalogue`; red before). (3) The dev console's `CdiInventory.label` gets a `default` branch, so a codegen verdict a newer Vauban adds (a bean created by a registered factory) reads as its name instead of breaking the build (`CodegenLabelTest`).
- **Reported by**: the reflection audit of the standard Vidocq path
- **Minimal reproduction**: start a packaged application with `mansart-pool` and `mansart-data`: the coverage panel lists `MansartPoolHolder`'s constructor as reflection, and the boot reads every repository and entity for a report section that only prints a summary.
- **Cause hypothesis**: the holders predate `addFactory`; the catalogue was written for the dev console and the report alike, built eagerly.

## BUG-20261102-03 — A normal-scoped @Named bean was read by reflection in EL, its accessor table never applied

- **Date**: 2026-11-02
- **Status**: FIXED on `feat/peano-vauban-proxies`, with Vidocq/peano#78 (EL-022): the Peano EL extension provides
  `VaubanProxyUnwrapper` (a `ProxyUnwrapper` that names the bean class of a `<Bean>_ClientProxy`), so the table Peano
  generates for the bean serves the proxy EL receives (`VaubanProxyUnwrapperTest`); Peano also selects `@Named`/`@Model`
  beans and the types their properties hold without `@ELAccessible`.
- **Reported by**: the reflection audit of the standard Vidocq path (2026-11-02)
- **Minimal reproduction**: a `@RequestScoped @Named("cart")` bean read as `${cart.total}`: the base is the client proxy,
  a subclass; generated tables accepted their exact class only, so the reflective `BeanELResolver` answered.
- **Cause hypothesis**: Peano had no notion of a container proxy; selection needed an annotation on every type.
- **Not covered here**: Vidocq does not register the CDI `BeanManager.getELResolver()` in the page `ELContext` yet, so a
  JSP cannot resolve `${cart}` by bean name at all today; the tables serve `ELProcessor`/programmatic EL with a CDI
  resolver, and pages once that resolver is wired.
## BUG-20261102-02 — The Telemetry extension reads MicroProfile Config by reflection at startup

- **Date**: 2026-11-02
- **Status**: FIXED (fix/humboldt-config-direct): the extension `requires static org.eclipse.microprofile.config` and reads the `otel.*` properties through `ConfigProvider.getConfig()` in `MpConfigBridge`; when the Config module is not in the runtime, or answers no Config, the reflective reading runs as before, unchanged. Tests: `MpConfigBridgeTest` (red first: Config was reached through `Method.invoke`; the fallback test drives the reflective path and sees the property).
- **Reported by**: the Vidocq reflection audit (tracing agent on a multi-extension application)
- **Minimal reproduction**: start an application with the Telemetry extension under the audit agent: `Class.forName(ConfigProvider)`, `Class.forName(Config)`, three `getMethod` and one `Method.invoke` per `otel.*` property in `HumboldtExtension.bridgeFromMpConfig`.
- **Cause hypothesis**: the bridge was written reflective so the extension starts without a Config implementation; a `requires static` module gives the same tolerance without reflection.

## BUG-20261102-01 — The build check asked to open every package of entities, which Mansart no longer reaches by reflection

- **Date**: 2026-11-02
- **Status**: FIXED on `feat/mansart-entities-no-opens`, with mansart#74 (MANSART-038): Mansart's processor generates, per package, a `_MansartComponents` service that hands Mansart the entity models and the repository implementations by plain calls. `ReflectedPackages` (`vidocq:check-module-info`, `vidocq doctor`, `vidocq:complete-module-info`, `vidocq dev`) reads the classes the services file `META-INF/services/io.vidocq.mansart.data.core.GeneratedComponents` lists and no longer starts from an entity they name; an entity they do not name — Mansart's reflective fallback — is still reported, as "an entity (Jakarta Data) without a model generated at build time" instead of "a JPA entity", and an entity a REST resource returns is still reached for JSON-B. Tests: `ReflectedPackagesTest` (`anEntityWithAModelMansartGeneratedNeedsNoDirective`, `anEntityTheGeneratedComponentsDoNotNameNeedsItsPackageOpened`, `anEntityWithAGeneratedModelAResourceReturnsStillNeedsItsPackageForJsonb`, `anEntityMansartHasNoGeneratedModelForNeedsItsPackageOpened`; red before). Checked end to end with Mansart from mansart#74 on a `vidocq create` application (`cassini-rest`, `mansart-data`, `-pool`, `-transactions`, `flyway-migration`) whose entity package is not opened: `mvn package` passes (`opens the 0 package(s)`), the packaged application and `vidocq:dev` answer POST, a derived query and JDQL record projections with 200 on PostgreSQL, and `vidocq:dev` leaves `module-info.java` unchanged; with the services file removed from `target/classes`, `check-module-info` asks for `opens com.example.shelf.book` with the new reason.
- **Reported by**: the maintainer (Mansart without reflection under Vidocq)
- **Minimal reproduction**: an application with a Jakarta Data entity in a package the module does not open: the build failed asking for `opens`, although Mansart reaches the entity through generated code.
- **Cause hypothesis**: the check treated every `@Entity` as reached by Jakarta Persistence's reflection.

## BUG-20261101-04 — Plain POJO DTOs (non-final, with a base class) still needed `opens`

- **Date**: 2026-11-01
- **Status**: FIXED with champollion#66 (champollion BUG-20261101-03, -01): the REST codegen bundle's processor now generates the binding of any concrete class with a no-argument constructor a resource exchanges, inherited properties included; the runtime no longer overflows on a type that holds itself. `ReflectedPackages` needs no change — it already skips bound types — and `aSubclassOfABoundTypeNeedsNoDirective` pins the rule for subclasses: JSON-B maps a value by its declared type, so a subclass returned under a bound type is written by that binding and needs no `opens`. Only the entity of a `Response`, whose class JAX-RS hands JSON-B at run time, stays outside the check (documented). Checked end to end on `vidocq create -x cassini-rest` with `Product extends BaseEntity` in two packages and no `opens`: `package` passes, `GET /products` and `POST /products` answer 200, `BaseEntity`'s properties first. BENCH.md: same startup, first request ≈ 2 ms faster, ≈ +3 % req/s.
- **Reported by**: the maintainer (JSON-B at build time, step A)
- **Minimal reproduction**: a resource returning `class Product extends BaseEntity`: after BUG-20261101-01 the build still asked for `opens` on both packages.
- **Cause hypothesis**: the generated bindings covered records and final Object-rooted classes only.

## BUG-20261101-03 — A created application never ran `vidocq:generate`: a library's repositories and beans got no generated code

- **Date**: 2026-11-01
- **Status**: FIXED on `feat/generate-by-default`. `vidocq-runtime-parent` binds `vidocq:generate` (execution id `generate`, `process-classes`, before `check-module-info`), so every application build enriches the dependency jars its bricks select, with no goal in the pom. The goal takes `-Dvidocq.generate.skip` and runs once per project build: a pom that also names it under another id (`default`, as `reader` did by hand) runs it once, and `vidocq:checkpom` names that execution for removal. `vidocq:test-path` names the enriched copy of a dependency, and `@VidocqTest` puts it on the application's module path in place of the jar Surefire put there (same file name): `mvn test` and `vidocq test` now run the code `dev` and `package` run. `vidocq doctor` shows **Dependency codegen** (OK under a parent from 0.4.0 except 0.4.0-RC1, or with an execution of its own; a warning when skipped or absent). Tests: `VidocqGenerateMojoTest` (skip, second execution), `VidocqTestPathMojoTest`, `ApplicationLaunchTest`, `VidocqCheckPomMojoTest`, `ProjectDoctorTest`. Checked with the CLI built from the branch: `vidocq create -x cassini-rest -x mansart-data -x flyway-migration` plus the `bookshelf-lib` jar (compiled without processor nor `-parameters`) and nothing else — `mvn package` logs `bookshelf-lib-1.0.0.jar — Mansart (3 classes, generated)`, `mvn test` runs a `@VidocqTest` on the library's repository green (red with `-Dvidocq.generate.skip`: the boot fails), `vidocq dev` answers by-title 1, by-author 2, exact 1. Timings (M-series laptop, JDK 25, Maven 3.9.16, median of 3): an application with no such dependency, `clean package` 1.82 s with the goal vs 1.78 s skipped, a no-change `package` 1.38 s vs 1.34 s; with the library, the cached processor output keeps a clean build at 2.08 s vs 2.02 s skipped (2.51 s on a cold cache).
- **Reported by**: the maintainer, after mansart#40 (the E2E needed `<goal>generate</goal>` added by hand)
- **Minimal reproduction**: `vidocq create -x mansart-data …`, add a dependency jar holding a `@Repository` interface, inject it: the build never runs `vidocq:generate`, the jar gets no implementation, and the boot fails on the unsatisfied repository.
- **Cause hypothesis**: the parent bound `checkpom`, `test-path`, `check-module-info` and `package` but not `generate`, which the examples and the docs declared by hand; and the tests' module path took the original jars, not the enriched copies.

## BUG-20261101-01 — The DTOs a REST resource exchanges are bound by reflection, so their packages need `opens`

- **Date**: 2026-11-01
- **Status**: FIXED on `feat/jsonb-build-time-bindings`, with champollion#63 (champollion BUG-20261101-02): the REST codegen bundle (`vidocq-runtime-cassini-rest-extension-codegen`) brings `champollion-codegen-apt`, which generates at build time the JSON-B binding of every record and final Object-rooted class a resource takes or returns, and of the types they hold. The REST extension reads `champollion-jsonb` at compile scope and `requires transitive io.vidocq.champollion.jsonb`, so the application compiles the bindings without a new `requires`. `ReflectedPackages` (check-module-info, doctor) skips the types the `JsonbBinding` services file binds, except as JPA entities (`ReflectedPackagesTest.aTypeWithAGeneratedJsonbBindingNeedsNoDirective`, `aJpaEntityWithABindingStillNeedsItsPackageOpenedForJpa`). Checked end to end on a `vidocq create -x cassini-rest` application with `record Book`/`record Author` in `com.example.jb.book` and no `opens`: `package` passes, GET and POST answer 200; with the services file emptied the same request answers 500 "does not open package com.example.jb.book"; `vidocq dev` reloads a changed record. The petstore example's own `champollion-jsonb` dependency lost its `runtime` scope.
- **Reported by**: Yann (the goal: in a Vidocq environment, avoid reflection; generate at build time)
- **Minimal reproduction**: a resource returning a record of a package the module does not open: the build asked for `opens`, and without it the first request failed (BUG-20261030-24).
- **Cause hypothesis**: Champollion's processor only handled `@JsonbStatic` records, and nothing wired it into an application.

## BUG-20261031-17 — The build check missed superclasses and implementations; `extension add mansart-data` left the application without a DataSource

- **Date**: 2026-10-31
- **Status**: FIXED on `feat/reflection-supertypes-pool-companion`. (1) `ReflectedPackages` follows the superclass of every type it reaches (application classes only), whose fields and getters JSON-B and JPA read too, and, for an interface or an abstract class, the application classes that implement or extend it directly: a field typed by an application interface holds one of them. Over-opening an implementation's package is safe (it only allows reflection), so every direct implementation is opened rather than none; a field typed by `Object`, by a JDK or library interface, or by a concrete class whose subclass it holds stays out of reach (`ReflectedPackagesTest`: superclass with the types it holds, interface implementation). (2) A new descriptor key, `requires-extensions`: `mansart-data` requires `mansart-pool`. `extension add`, `create -x` and `ProjectSpec` add the required extensions transitively, before the extension needing them, so the pool comes with its dev service, driver and test block; `remove mansart-data` keeps the pool, which works alone, and names the command that drops it; removing the pool while `mansart-data` stays warns; `vidocq doctor` shows **Required extensions** (warning, not fixable: the application may produce its own DataSource). Catalog regenerated. Checked with the CLI built from the branch: `extension add mansart-data` alone adds the pool, the PostgreSQL driver and dev service; an entity extending a `@MappedSuperclass` and a DTO extending `shared.Labeled` in another package — `mvn package` lists `opens com.example.lib.shared;   (… superclass of com.example.lib.api.LoanView)`, `doctor --fix` writes it, `vidocq dev` starts PostgreSQL and `GET /loans` answers 200 with the inherited `label`.
- **Reported by**: the maintainer, after BUG-20261031-15/-16 (limits documented in "Java modules in a Vidocq application"; `mansart-data` without a DataSource found while checking #462)
- **Minimal reproduction**: (1) a DTO `api.LoanView extends shared.Labeled` returned by a resource: the build passed, the request failed on `shared`. (2) `vidocq extension add mansart-data`, `vidocq dev`: "Mansart Data extension is enabled but no DataSource bean is exposed by CDI".
- **Cause hypothesis**: (1) the traversal followed fields and getters only; (2) the catalog had no notion of an extension requiring another, only of glue added with a pair (`companion-of`).

## BUG-20261031-16 — Every package an application reflects on needs its own `opens` line; there was no way to opt out

- **Date**: 2026-10-31
- **Status**: FIXED on `feat/open-module`. Follow-up of BUG-20261031-10/-14/-15, asked by the maintainer: `vidocq create --open-module` (and `vidocq:create -DopenModule=true`, `ProjectSpec.openModule`) declares an `open module`, which opens every package. `ModuleInfoEditor` knows an open module (`isOpenModule`, comments skipped): `add` writes its `requires` and no `opens`, `openForReflection` changes nothing, `missing` asks for no `opens` — so `extension add`, `create -x`, `doctor --fix`, `vidocq:complete-module-info` and the `vidocq dev` writer never put an `opens` line where javac refuses it. `vidocq doctor` fails, fixable, on an open module that keeps `opens` lines; `--fix` drops them (`toOpenModule`) and names the hand-written ones. `ModuleInfoRequirements.missing(ModuleElement, …)`, the annotation-processor variant, now lets an open module satisfy every `opens` requirement, as the `ModuleDescriptor` variant already did. The Flyway folder's README and the CLI's messages no longer claim an `opens` for an open module. Tests: `ModuleInfoEditorTest` (5), `ProjectDoctorTest` (2), `CliParserCreateTest` (3), `VidocqCreateMojoTest`, `ExtensionEditorTest`, `ModuleInfoRequirementsTest`. Checked with the CLI built from the branch: `vidocq create --open-module -x cassini-rest`, `extension add flyway-migration mansart-data mansart-pool`, an entity package and a DTO package — `module-info.java` holds no `opens`, `mvn package` passes, `vidocq dev` starts PostgreSQL and `GET /books` answers 200; an existing module made `open module` by hand: `vidocq doctor` fails on its 3 `opens`, `--fix` removes them, `package` passes.
- **Reported by**: the maintainer (simplest path for an application)
- **Minimal reproduction**: a new application had no way to declare an open module through the CLI, and the tooling would write `opens` lines into one made open by hand, which then fails to compile ("'opens' not allowed in an open module").
- **Cause hypothesis**: the module tooling assumed a plain module.

## BUG-20261031-15 — A package a library reaches by reflection at run time fails with a bare IllegalAccessException

- **Date**: 2026-10-31
- **Status**: FIXED on `feat/module-access-diagnostics`. Follow-up of BUG-20261031-10/-14: the build check finds the packages the runtime is known to reflect on (JPA entities, the types of REST resources, interceptors with a private method), not a use decided at run time. `ModuleAccessDiagnostics` (vidocq-runtime-core), installed on the root logger by every boot, reads the cause chain of each logged failure — a request's 500, a failed boot — and on an `InaccessibleObjectException` or `IllegalAccessException` into a package of the application's module logs, once per package, the package and the `opens` line to add. Under `vidocq dev` the application writes the package to `target/vidocq-dev-opens.request`; the plugin (`OpensRequestFile`) opens it in `module-info.java` (`// vidocq:reflection`), and the source watcher recompiles and reloads. `ModuleAccessDiagnosticsTest` (a real non-opened module: field and method denials, no message for the JDK or another module, once per package, the request file), `OpensRequestFileTest`. Checked on a generated application whose resource calls `JsonbBuilder.create().toJson(new Report())`, `Report` in a package nothing opens: `vidocq dev` answers 500, logs the error, writes `opens com.example.m.report; // vidocq:reflection`, reloads, and the next request answers 200 six seconds later. Page "Java modules in a Vidocq application".
- **Reported by**: the maintainer, after the tutorial replay (what remains when the build check cannot see a use)
- **Minimal reproduction**: a resource returning `JsonbBuilder.create().toJson(new Report())`, `Report` in a package the module does not open: the build passes, the request answers 500 with `IllegalAccessException … does not export com.example.m.report`.
- **Cause hypothesis**: static detection cannot see reflection decided at run time (programmatic JSON-B, `Class.forName`, third-party libraries); the error came from deep in the library, with no word of `opens`.

## BUG-20261031-14 — Each fix adds another "added by vidocq" block to `module-info.java`, with extension lines between them

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/module-info-one-block`: `ModuleInfoEditor` keeps one layout. The extension lines (`// vidocq:<id>`) stay together after the hand-written ones; the packages opened for reflection go in one block, last, under one header, each line marked `// vidocq:reflection` (an id no extension has, so `extension remove` keeps them). `openForReflection` extends that block for `doctor --fix`, `vidocq:complete-module-info` and the dev writer of BUG-20261031-10; `add` and `remove` merge older blocks (several headers, unmarked `opens` under them, the `complete-module-info` header) on the next write (`ModuleInfoEditorTest.openingForReflectionTwiceExtendsTheOneBlock`, `anExtensionAddedAfterAFixGoesBeforeTheBlock`, `severalOldBlocksAreMergedOnTheNextWrite`, `removingAnExtensionKeepsTheBlock`). Checked with the CLI built from the branch on a created application: five chapters (doctor --fix, extension add, doctor --fix, complete-module-info, the dev writer, extension add), then a step-11 file with three headers, one block each time, `package` green
- **Reported by**: the vidocq-tutorial session (T3)
- **Minimal reproduction**: after several chapters, `module-info.java` holds three identical "// added by vidocq: the runtime reaches these packages by reflection" headers, with the `requires` of later `extension add` between them.
- **Cause hypothesis**: each fix appended a new block before the closing brace, and `extension add` wrote before the closing brace too, so after the latest block.

## BUG-20261031-11 — `check-module-info` throws a NullPointerException on its session; the hand-written plugin.xml did not declare it

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/check-module-info-session`: `plugin.xml` declares `session` and `dev` (`vidocq.moduleinfo.dev`) for `check-module-info`, and also the parameters other goals had lost the same way: `linkInContainer` and `jdkImage` of `vidocq:docker` (`-Dvidocq.docker.linkInContainer=false` was ignored), the `vidocq.includeResources` property of `vidocq:jlink`. `PluginDescriptorCompletenessTest` reads every mojo's `@Parameter` fields from the class files and fails when the descriptor misses one or its property/default value. Checked through Maven on a generated application with a JSON-B type in a package it does not open: `package` fails with the `opens` line and `vidocq doctor --fix`, `-Dvidocq.moduleinfo.dev=true` writes the line and builds.
- **Reported by**: the vidocq-tutorial session (replay of SNAPSHOT 0.4.0-20261031.051920-15)
- **Minimal reproduction**: `vidocq create -x cassini-rest`, a JSON-B record and a resource returning it in a new package, no `opens`: `./mvnw package` and `vidocq dev` fail with `Cannot invoke "org.apache.maven.execution.MavenSession.getGoals()" because "this.session" is null` at `VidocqCheckModuleInfoMojo.execute`, no message on what to add (regression of BUG-20261031-10).
- **Cause hypothesis**: the plugin descriptor is written by hand; a field annotated `@Parameter` but missing from it is never set by Maven, while the unit tests build the mojo and set the field themselves.

## BUG-20261031-10 — `vidocq dev` stops on a package the runtime reaches by reflection and the module does not open

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/dev-writes-opens`: under `vidocq dev` (a `vidocq:dev` among the goals, or the recompile, which passes `-Dvidocq.moduleinfo.dev=true`), `check-module-info` opens such a package in `module-info.java`, in the block `vidocq doctor --fix` and `complete-module-info` write, and in the compiled `module-info.class` (`ModuleInfoOpener`, Class-File API; `ModulePackages` updated when present), prints `✔ module-info.java: opens …;   (reason)` and goes on, so the application starts on that build (`ModuleInfoOpenerTest`, `VidocqCheckModuleInfoMojoTest.theCheckRunsInDevModeUnderVidocqDevOrItsRecompile`, `RecompileRunnerTest.the_recompile_runs_check_module_info_in_dev_mode`). Outside dev the failure names `vidocq doctor --fix` and `./mvnw vidocq:complete-module-info` instead of a bare `mvn`, and no longer says "the first request using them would fail": the build fails before any (`theUnopenedPackagesNameTheCommandsThatOpenThem`)
- **Reported by**: the vidocq-tutorial session (S1)
- **Minimal reproduction**: under `vidocq dev`, drop `opens com.example.lib.book;` (or add a package of entities, as the loan, health and enrich chapters do): "[ERROR] module-info … does not open 1 package(s) … `mvn vidocq:complete-module-info` adds them", BUILD FAILURE, no application.
- **Cause hypothesis**: the check reported and failed in every context; under dev, where the tooling may edit the project, it can write the line itself.

## BUG-20261031-01 — `vidocq doctor` says ✔ Module directives while `vidocq:check-module-info` fails the build

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/module-directives-one-check`: `KnownExtensions.moduleDirectives` now holds the `opens` of the resource packages an extension reads, so `doctor`, `extension add`, `create` and `doctor --fix` ask for and write the same directives that `check-module-info` checks (`ProjectDoctorTest.aClosedMigrationPackageFailsAndIsOpened`). The goal's failure names `vidocq doctor --fix`. Then on `fix/doctor-reflected-packages`, after the replay of SNAPSHOT 20261031.015020-10 (doctor still ✔ while `package` failed on `opens com.example.lib.book;` / `.support;`): `ReflectedPackages` moved to `vidocq-runtime-tooling`, and with a compiled `target/classes` `doctor` reports the packages the runtime reaches by reflection that neither `module-info.class` nor `module-info.java` opens, and `--fix` opens them in the block `vidocq:complete-module-info` writes (`ModuleInfoEditor.appendBlock`, shared); before a compile it says to compile for them (`ProjectDoctorTest.aPackageReachedByReflectionAndClosedFailsAndIsOpened`, `withoutCompiledClassesTheReflectedPackagesWaitForACompile`). Its line no longer counts extensions, which read as a contradiction next to the Extensions check (8 pom extensions, 7 `VidocqExtension` providers: `jdbc-telemetry` has none)
- **Reported by**: the vidocq-tutorial session (overnight replay, R1)
- **Minimal reproduction**: `vidocq create -x cassini-rest`, `vidocq extension add flyway-migration`, then `src/main/resources/db/migration/V1__books.sql` without `opens db.migration;`: `vidocq doctor` 11/11 ✔, `./mvnw package` fails in `check-module-info`.
- **Cause hypothesis**: `doctor` checked the catalog's `requires` and application-package `opens` only; the resource-package `opens` were known to `extension add` and to the build's descriptors, not to `doctor`.

## BUG-20261031-02 — After a compile error, `check-module-info` reports a module without `module-info.java`

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/module-directives-one-check`: when `target/classes` holds no `module-info.class` but a compile source root holds `module-info.java`, the goal warns that the module is not compiled yet and checks nothing (`VidocqCheckModuleInfoMojoTest`); its messages say "Java module" instead of "JPMS"
- **Reported by**: the vidocq-tutorial session (overnight replay, R2)
- **Minimal reproduction**: under `vidocq dev` (`failOnError=false`), one compile error in a module whose `module-info.java` already declares `opens db.migration;`: "This module depends on Vidocq extensions that require JPMS directives, but it has no module-info.java (it is not modular). … Add a module-info.java declaring: opens db.migration;".
- **Cause hypothesis**: a missing `module-info.class` was read as a non-modular module; the compilation that did not produce it was not considered.

## BUG-20261031-03 — `opens db.migration;` needs a second `vidocq extension add flyway-migration`

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/module-directives-one-check`: `extension add`, `create` and `doctor --fix` create `src/main/resources/db/migration` with a `README.md` and write the `opens` at once (`ExtensionEditorTest.addingFlywayWithoutMigrationCreatesTheFolderAndOpensIt`, `ProjectDoctorTest.aCreatedProjectWithFlywayHasItsMigrationFolderOpened`). A hidden file such as `.gitkeep` would not do: the JVM ignores it in the exploded module `vidocq dev` runs ("Package db.migration not found in module"), and the README is not a Flyway script. Replaces the warning of BUG-20261030-11
- **Reported by**: the vidocq-tutorial session (overnight replay, R7)
- **Minimal reproduction**: `vidocq extension add flyway-migration` on a project without `src/main/resources/db/migration`: a ⚠ and no directive; the first script then fails `check-module-info` until the extension is added again.
- **Cause hypothesis**: opening a package without a file makes the JVM refuse the module, so the edit waited for a script instead of creating the package.

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
- **Status**: FIXED on `feat/flyway-opens-migration-package`: the catalog key `module-info.opens-resource-package = db.migration` (Flyway and Liquibase); `vidocq extension add` writes the `opens` once the package holds a file, and otherwise says where the migrations go (`ExtensionEditorTest.addingFlywayOpensTheMigrationPackageWhenItHoldsAMigration`, `…WithoutMigrationSaysWhereTheyGo`). Opening an absent package fails the build, which is why `check-module-info` only asks for it once the package exists. Since BUG-20261031-03 the edit creates the package with a README and opens it at once
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
- **Status**: NOT REPRODUCED on main 070881f2 (grimm main): an application with `META-INF/openapi.yaml` under
  `vidocq dev`, the title edited, the hot reload swapped the layer and `/openapi` served the new title. Closed on
  2026-11-19: no report since. Reopen with the tutorial's exact sequence if it happens again.
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
- **Status**: FIXED (all six items; status line closed on 2026-11-19). Item 4 (no JVM options hook) on `feat/launcher-java-opts`: the launchers pass `JAVA_OPTS` after `<jvmArgs>` (`VidocqPackageMojoTest.javaOptsReachTheJvmAfterTheConfiguredJvmArgs`). Item 1 (docker without jlink) on `fix/packaging-17-docker-jlink`: `vidocq build docker` runs `package vidocq:jlink vidocq:docker` (`BuildTypeTest.dockerBuildsTheJlinkImageFirst`). Item 2 (automatic modules stop jlink) on the same branch: `vidocq:jlink` modularizes them first (`VidocqJlinkMojoStagingTest.anAutomaticModuleIsModularizedBeforeStaging`; a scaffolded application with cassini-rest, flyway-migration, humboldt-telemetry and the PostgreSQL driver: 12 automatic modules, image built and started). Item 3 (test jars in `lib/`): `opentelemetry-sdk-testing` no longer reaches it, on `fix/packaging-17-lib-scope` with humboldt#46 (`NoTestLibraryTest`; the other jars listed, `flyway-database-cockroachdb`, `jackson-annotations`, `commons-text`, `annotations`, are Flyway's own runtime dependencies, not a scope error). Item 5 (`--help` per command) on `fix/packaging-17-cli-help-extensions` (`CliParserHelpTest`). Item 6 (start-up list without the CDI-only extensions) on the same branch: they are listed as _CDI only_ (`ExtensionLoaderTest`). The image linked on macOS (reported with these items, then as finding S3 of the tutorial replay: no image built, yet "✔ Done.") FIXED on `fix/docker-on-macos`: `vidocq:jlink` leaves a host-independent recipe in `target/jlink-image/` (`PortableImage`), and `vidocq:docker` writes a two-stage Dockerfile that links the image again inside `eclipse-temurin:<feature>-jdk` when the image is not Linux (`vidocq.docker.linkInContainer`, `VidocqDockerMojoTest.aMacOsImageIsLinkedAgainInsideALinuxJdk`); the entry point runs `bin/java -m`, since the distroless base has no shell for the launcher script; `vidocq build docker` passes `-Dvidocq.docker.build=true` when `docker info` answers and warns that the image is not built otherwise (`DockerBuildTest`). Checked on macOS (OrbStack, linux/arm64): a scaffolded application with cassini-rest, flyway-migration and the PostgreSQL driver, `vidocq build docker` → image built, container started, `GET /hello` 200.
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
- **Status**: FIXED on `feat/codegen-bundles-bce`: new `vidocq-runtime-cervantes-jwt-extension-codegen` (cervantes-cdi-vauban, cdi-api, the Config bundle); the Metrics bundle adds dirac-cdi-vauban with cdi-api. JWT TCK 208/208, Metrics 127/127. OpenAPI FIXED on `fix/grimm-bundle-bce`: the OpenAPI bundle adds grimm-cdi-vauban with cdi-api, once Vauban types an `@Inject` constructor from that constructor (vauban VAU-DISC-003) and Grimm merges the `ScannedTypes` of the build-time and start-up runs (grimm BUG-20261030-05); `vidocq-runtime-it-grimm-openapi` 5/5, OpenAPI TCK 364/364)
- **Reported by**: the vidocq-tutorial session (batch 7, item 4; batch 10)
- **Minimal reproduction**: `vidocq create -x cassini-rest -x dirac-metrics -x grimm-openapi -x cervantes-jwt`, then `mvn compile`: _[Vauban] … Build Compatible Extension(s) present on the compile/module path are NOT on the annotation-processor path and will not run: [CervantesClaimExtension, DiracExtension, GrimmExtension]_.
- **Cause hypothesis**: JWT had no codegen bundle; the Metrics and OpenAPI bundles carried their processor only, not the jar of their extension's BCE, nor the CDI API those jars leave `provided`.
- **_Unsatisfied dependency … JsonWebToken_ at build time**: FIXED in Vauban (VAU-APT-006, https://codefloe.com/Vidocq/vauban/pulls/250). The processor now validates against the beans of the bean archive modules on the module path, so `JsonWebTokenProducer` of cervantes-cdi-vauban satisfies the injection point at build. Checked on `vidocq create -x cassini-rest -x cervantes-jwt` with `@Inject JsonWebToken`: no warning; a type nobody provides still warns. Effective once that Vauban SNAPSHOT is published; nothing changes in vidocq.

## BUG-20261030-25 — A request's trace has no database span

- **Date**: 2026-10-30
- **Status**: FIXED on `feat/jdbc-spans-telemetry`. Step 2: `vidocq-runtime-mansart-pool-telemetry-extension` (catalog id `jdbc-telemetry`, added with `humboldt-telemetry` + `mansart-pool`) provides the tracing decorator; the delegating wrappers are generated from `java.sql` by `JdbcDelegatesGenerator` (test sources) and kept in sync by `JdbcDelegatesTest` instead of being hand-written (`TracingConnectionTest`: a prepared query inside a request is a child CLIENT span on H2, plain-statement literals become `?`, failures are recorded, a batch is one span; `SqlStatementsTest`, `DbTargetTest`; tooling: `KnownExtensionsTest.theJdbcSpansGlueGoesWithTelemetryAndThePool`, `ProjectSpecTest.aProjectWithTelemetryAndThePoolGetsTheirCompanion`, `ExtensionEditorTest.theCompanionOfAPairComesAndGoesWithIt`). Step 1, on `fix/jdbc-spans-25`: the hook. Every connection the Vidocq DataSources hand out (the `@Default` pool and each `@Named` one) goes through the `JdbcConnectionDecorator`s found by `ServiceLoader`, given the datasource's name. With none installed, the pool's own connection comes back (`JdbcConnectionDecoratorsTest`). Step 2, the tracing decorator, is OPEN; design below.
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
  - Vauban (VAU-APT-007): its classes are written before javac's last round, and what the BCEs log at build time becomes javac messages (`GenerationRoundTest`, `BceLoggingTest`);
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
## BUG-20261031-05 — `extension add mansart-pool` leaves the dev-service provider to the user, and the boot error ignores it

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/devservice-deps-by-cli`: an extension descriptor names its dev service (`devservice`, catalog keys), and `vidocq extension add`, `vidocq create` and `vidocq doctor --fix` declare the provider among `vidocq-runtime-maven-plugin`'s dependencies (`DevServiceWiring`, `DevServiceWiringTest`, `ExtensionEditorTest.anExtensionWithADevServiceBringsWhatItNeeds`, `ProjectScaffolderExtensionsTest.anExtensionWithADevServiceIsCreatedWithWhatItNeeds`); `vidocq doctor` warns under _Dev services_ and `vidocq:checkpom` in the build (`ProjectDoctorTest.aDevServiceWithoutWhatItNeedsWarnsAndIsFixed`, `VidocqCheckPomMojoTest.aDevServiceWithoutItsPiecesIsReported`); the boot error names both ways out (`MissingDataSourceTest`). Same for `cervantes-jwt` (Keycloak) and `humboldt-telemetry` (collector, plugin only)
- **Reported by**: the vidocq-tutorial session (replay of 2026-10-31, R4)
- **Minimal reproduction**: `vidocq create -x cassini-rest`, `vidocq extension add mansart-data mansart-pool`, `vidocq dev`: _MansartPoolExtension did not publish a DataSource — is vidocq.pool.url set?_; `vidocq doctor` 11/11 ok. The provider had to be added by hand to the plugin's `<dependencies>`.
- **Cause hypothesis**: the catalog knew each extension's codegen bundle and module directives, not its dev service; nothing wrote or checked the provider.

## BUG-20261031-06 — Without the PostgreSQL driver, the dev service says so as an INFO line, then the boot fails on the URL

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/devservice-deps-by-cli`: the pool's descriptor names the driver (`devservice.driver = org.postgresql:postgresql:42.7.10`), written in runtime scope unless the project declares a JDBC driver already (then on another database, nothing is written); `DevService.skipNeedsAction` lets a provider say that not starting is a problem, and the host logs it as a WARNING (`DevServiceManagerTest.aSkipThatNeedsTheUsersActionIsAWarning`, `PostgresDevServiceTest.onlyTheMissingDriverAsksTheUserToAct`); doctor and checkpom name the missing driver
- **Reported by**: the vidocq-tutorial session (replay of 2026-10-31, R5)
- **Minimal reproduction**: the provider on the plugin, no `org.postgresql:postgresql`; `vidocq dev` logs `[INFO] DevService 'postgres' not started: no vidocq.pool.url and no PostgreSQL driver (org.postgresql.Driver) on the class path`, then fails with _is vidocq.pool.url set?_
- **Cause hypothesis**: every skip reason was logged at INFO, including the one that leaves the application without a database.

## BUG-20261031-12 — `vidocq extension add mansart-pool` writes no `vidocq.pool.*` settings

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/pool-config-and-config-set`: the pool's descriptor has a block of `config.<NN>` lines, the three keys commented, as the PostgreSQL dev service sets them under `vidocq dev` and the tests (`ExtensionConfigTest.thePoolWritesItsConnectionCommented`)
- **Reported by**: the vidocq-tutorial session (full rebuild after BUG-20261031-09, T1)
- **Minimal reproduction**: `vidocq extension add mansart-pool`: no `vidocq.pool.url`, `.username` or `.password` in `vidocq.properties`; the tutorial writes them by hand for `vidocq run`.
- **Cause hypothesis**: BUG-20261031-09 gave a block to JWT and Telemetry only.

## BUG-20261031-13 — `vidocq config set` on a key written commented appends a second line at the end

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/pool-config-and-config-set`: `PropertiesText.set` rewrites an active assignment in place, else uncomments the first `#key=value` assignment in place, else appends; a comment that only names the key is left alone (`PropertiesTextTest.setUncommentsACommentedAssignmentInPlace`, `anActiveAssignmentWinsAndProseIsNoAssignment`)
- **Reported by**: the vidocq-tutorial session (full rebuild after BUG-20261031-09, T2)
- **Minimal reproduction**: after `vidocq extension add cervantes-jwt`, `vidocq config set mp.jwt.verify.issuer https://idp/realms/app`: the block keeps `#mp.jwt.verify.issuer=…` and a new active line lands at the end of the file, away from the comment that explains it.
- **Cause hypothesis**: `set` only knew active assignments.

## BUG-20261031-09 — `vidocq extension add cervantes-jwt humboldt-telemetry` leaves their settings to be written by hand

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/extension-config-keys`: an extension descriptor lists the settings it needs (`config.<NN>` lines, `{app}` for the artifactId); `vidocq extension add` and `vidocq create -x` append them to `vidocq.properties` as one block, once, leaving out every key the application sets (`ExtensionConfigTest`, `ExtensionEditorTest.anExtensionWritesItsConfigurationOnceAndRemovingItLeavesIt`, `ProjectScaffolderExtensionsTest.aCreatedProjectGetsTheConfigurationOfItsExtensions`). Telemetry gets `otel.sdk.disabled=false`, its service name and a local collector; JWT gets comments only, as the Keycloak dev service sets the verification keys and a realm import of a missing file would stop it. `vidocq doctor` warns about JWT without a verification key (`ProjectDoctorTest.jwtWithoutAVerificationKeyWarnsUntilTheApplicationSetsOne`). `remove` leaves the file alone
- **Reported by**: the vidocq-tutorial session (replay of chapters 4-11, S2)
- **Minimal reproduction**: `vidocq extension add cervantes-jwt humboldt-telemetry`: `vidocq.properties` still holds `vidocq.http.port` only; the tutorial writes `mp.jwt.verify.issuer`, `mp.jwt.verify.publickey.location`, `vidocq.dev.keycloak.realm-import`, `vidocq.dev.keycloak.realm` (chapter 8) and `otel.sdk.disabled`, `otel.service.name`, `otel.exporter.otlp.endpoint`, `otel.exporter.otlp.protocol` (chapter 9) by hand.
- **Cause hypothesis**: the catalog knew an extension's dependency, codegen bundle, directives and dev service, not its configuration.

## BUG-20261031-07 — A plain `mvn test` needs the dev-service provider again, in test scope, with `vidocq-runtime-devservices-junit`

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/devservice-deps-by-cli`: `vidocq extension add` writes `vidocq-runtime-devservices-junit` and the provider in test scope (not the collector, `devservice.in-tests = false`), `vidocq extension remove` takes them away; doctor and checkpom report a missing pair; the pool's boot error points to `vidocq-runtime-devservices-junit` for `mvn test`. The plugin's providers do not serve Surefire: they run in the plugin's class realm, and Surefire never sees it, so the test-scope pair stays, written by the CLI instead of the user
- **Reported by**: the vidocq-tutorial session (replay of 2026-10-31, R6)
- **Minimal reproduction**: the provider on the plugin only; `vidocq test` passes, `./mvnw test` fails every `@VidocqTest` with _did not publish a DataSource — is vidocq.pool.url set?_; doctor silent.
- **Cause hypothesis**: two hosts discover providers in two places (the plugin realm, the test class path); only the documentation said so.

## BUG-20261030-24 — A package the runtime reaches by reflection, and the module does not open, fails at the first request

- **Date**: 2026-10-30
- **Status**: FIXED on `fix/opens-check-24`: `vidocq:check-module-info` reads the compiled classes (`ReflectedPackages`): JPA entities, and the types a REST resource or provider takes or returns, with the types their fields and getters hold. A package among them that the module neither opens nor exports fails the build with the `opens` line to add (`ReflectedPackagesTest`, 4 cases)
- **Reported by**: the vidocq-tutorial session (batch 2, item 3)
- **Minimal reproduction**: `vidocq create -x cassini-rest`, then a `BookResource` returning a `Book` DTO in `com.acme.shop.book` (the scaffold opens `com.acme.shop` only): the build passes, and `GET /books` answers 500 with _IllegalAccessException: … RuntimeBindingRegistry$MethodAccessor (in module io.vidocq.champollion.jsonb) cannot access class com.acme.shop.book.Book … because module com.acme.shop does not export com.acme.shop.book_.
- **Cause hypothesis**: `check-module-info` only checked the directives the extensions declare (`opens db.migration`); the packages of the application itself were checked by nobody before the first request.

## BUG-20261031-04 — A CDI interceptor's package, not opened, fails the first request; the build and doctor say nothing

- **Date**: 2026-10-31
- **Status**: FIXED on `fix/opens-interceptors-jsonb`: `ReflectedPackages` also lists the package of each class annotated `@Interceptor`, and asks for its `opens` (an export is not enough) unless the class and every method its container calls (`@AroundInvoke`, `@AroundConstruct`, `@AroundTimeout`, `@PostConstruct`, `@PreDestroy`) are public (`ReflectedPackagesTest.anInterceptorWithANonPublicMethodNeedsItsPackageOpenedNotOnlyExported`). `vidocq:complete-module-info` writes the `opens` of every package `check-module-info` reports, the JSON-B and JPA ones included (`VidocqCompleteModuleInfoMojoTest.opensThePackagesTheRuntimeReachesByReflection`), and the check's message names the goal. On the tutorial's compiled classes the check reports `com.example.lib.book` and `com.example.lib.support`. The JSON-B half (the error named no access cause) is Champollion BUG-20261031-01. Then narrowed on `fix/interceptor-opens-private-only`, after vauban#256 (VAU-INT-011): Vauban calls non-private interceptor methods through the provider generated in their package, so only a `private` one still needs the package opened (`ReflectedPackagesTest.aPrivateInterceptorMethodNeedsItsPackageOpenedNotOnlyExported`, `aPackagePrivateInterceptorMethodNeedsNoDirective`).
- **Reported by**: the vidocq-tutorial session (overnight replay, finding R3)
- **Minimal reproduction**: the tutorial's step-03 sources (an `@Audited` `AuditedInterceptor` with a package-private `@AroundInvoke`, in `com.example.lib.support`), and a module-info holding only the CLI lines and `opens db.migration;`: `package` and `doctor` are green, and `GET /books` answers 500 `IllegalAccessException: … VaubanInvocationContext$InterceptorInvocation (in module io.vidocq.vauban.core) cannot access class com.example.lib.support.AuditedInterceptor`.
- **Cause**: the check of BUG-24 covered the JSON-B and JPA types, not the interceptors; Vauban calls interceptor methods by reflection (Vauban VAU-INT-011), so their package needs `opens`, or an export when everything is public.

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
- **Status**: FIXED on `fix/datasources-module-anchor` (Vidocq/vidocq#505)
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
  - 2026-11-07: fixed in b9bb3ba6 (Vidocq/vidocq#505). A module anchor takes the first package of the module in name
    order, a type or package anchor wins when there is one, and a module with no package gets a compile
    error naming `@VidocqDataSources` instead of the `NullPointerException`. Tests
    `VidocqDataSourcesProcessorTest.generatesHolderWhenTheAnnotationIsOnTheModuleDeclaration` and
    `refusesAModuleWithoutAnyPackageInsteadOfCrashing`.

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
