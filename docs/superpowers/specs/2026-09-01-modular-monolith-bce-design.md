# Specification: BCE-based Modular Monolith on Vidocq

**Author:** Antoine
**Revision:** 0.3 (draft) — revised after architecture review; added Spring Modulith comparison and tooling scope

## Abstract

This document specifies the architecture of a modular monolith for applications running on the Vidocq runtime, built on the Boundary-Control-Entity (BCE) pattern. The goal is to ship a single deployable artifact while preserving the ability to extract specific modules into standalone services (REST or gRPC) when scalability needs or a distinct lifecycle justify it.

The intent is deliberately comparable to Spring Modulith: an architectural approach *plus tooling* for building modular monoliths that can be split into services when the need arises. The ambition is to reproduce those concepts on stronger foundations — platform-verified boundaries and build-time generation instead of convention-plus-test verification and runtime infrastructure (see [Comparison with Spring Modulith](#comparison-with-spring-modulith)).

Three pillars structure the solution:

- **BCE** provides the responsibility semantics within each functional unit.
- **Java Modules** lock down boundaries in a way that is verified by the platform itself, at compile time and at load time.
- **CDI** provides the dynamic wiring and the inter-module facade, combining events (pub/sub) and ports (request/response).

The technical target is Jakarta EE Core Profile 11 plus selected Vidocq extensions, MicroProfile 7.1, and JDK 25.

## Table of Contents

- [Context and Goals](#context-and-goals)
- [Architectural Principles](#architectural-principles)
- [Data Ownership](#data-ownership)
- [Java Modules Modularity](#java-modules-modularity)
- [Facade Mechanism](#facade-mechanism)
- [Event Semantics and the Extraction Gap](#event-semantics-and-the-extraction-gap)
- [Transactional Consistency](#transactional-consistency)
- [Platform Coverage](#platform-coverage)
- [Testing Strategy](#testing-strategy)
- [Comparison with Spring Modulith](#comparison-with-spring-modulith)
- [Tooling Scope](#tooling-scope)
- [Open Decisions](#open-decisions)
- [Glossary](#glossary)

## Context and Goals

### Context

Vidocq implements Jakarta EE Core Profile 11 and MicroProfile 7.1 on JDK 25, assembled from independent building blocks (chappe, vauban, champollion, cassini, foy, mansart, and the MicroProfile bricks). The runtime favors modularity verified by the platform over modularity by convention, and its ecosystem philosophy imposes three constraints that shape this specification:

1. **Strict Java Modules** — minimal `exports`, no unjustified `opens`, no classpath.
2. **Maximum static code generation** — Class-File API (JEP 484) and APT at build time; no dynamic proxies, no runtime bytecode generation, no on-the-fly reflection.
3. **Virtual Threads everywhere for I/O** — blocking code is cheap; asynchrony is not a signature requirement.

This specification describes the architecture of an *application* built on that runtime, not of the runtime itself. Application module names in examples (`orders`, `catalogue`) are placeholders; the naming convention is an open decision (see [Open Decisions](#open-decisions)) and must not collide with runtime brick names (e.g. `grimm` is the MicroProfile OpenAPI brick).

### Goals

1. Define a consistent internal structure per functional unit, based on BCE.
2. Guarantee the tightness of inter-module boundaries through Java Modules, rather than through discipline.
3. Offer a single inter-module facade whose transport (in-process, REST, gRPC) is a deployment decision, not a code decision.
4. Preserve a monolith-to-distributed-service extraction path at a controlled cost — where the cost is understood to be **semantic** (consistency, delivery guarantees) at least as much as syntactic (transport).

### Non-Goals

- Designing a distributed microservices architecture from the outset. Distribution remains an extraction option, not the starting point.
- Mandating gRPC. gRPC is outside the strict Core Profile and remains an optional extension.
- Hiding distribution. The abstraction makes transport interchangeable; it does not pretend a remote call is identical to a local one — neither in failure modes nor in delivery semantics.

## Architectural Principles

### The BCE Pattern

Each functional unit is broken down into three types of objects:

| Type | Responsibility |
|------|-----------------|
| **Boundary** | The module's boundary. Handles all interactions with the outside of the unit: inbound endpoint (Jakarta REST resource), event reception and emission, port implementations. The only exposed point of the module. |
| **Control** | Orchestration of a use case internal to the module. Coordinates Boundary and Entity. Does not hold durable business state. Does not coordinate other modules directly. |
| **Entity** | The business model and invariant rules of the module's bounded context. Persisted via Jakarta Persistence. Independent of the use cases that manipulate it. |

The dependency rule follows bce.design: **direct interactions flow only in the B → C → E direction**. The Boundary delegates to Control and may read Entities for simple operations; Control orchestrates Entities; no external request reaches Entity or Control without passing through the Boundary. The reverse direction (E → C → B) is only expressed through events — an Entity emits a domain event rather than calling a Control; a Control publishes an event rather than invoking a Boundary. This reverse-via-events rule is not a stylistic preference here: it is the same mechanism the inter-module facade relies on, applied inside the module.

### Lineage and Divergences

The pattern originates in Ivar Jacobson's work and is adopted here in the modern interpretation of Adam Bien's [bce.design](https://bce.design): business components named after domain responsibilities (not technical concerns), maximal cohesion within a component, minimal coupling between components, and the strict B → C → E dependency rule above. Spring Modulith supplies the second inspiration — the tooling ambition (see [Comparison with Spring Modulith](#comparison-with-spring-modulith)).

This specification deliberately diverges from bce.design on three points, all in the direction of *stricter* isolation, because extraction is a stated goal:

1. **No shared-entity common component.** bce.design tolerates shared entities placed in a common component; the [Data Ownership](#data-ownership) rules forbid it. A shared persistent model is the coupling that makes extraction impossible.
2. **Cross-component interaction only through the facade.** bce.design allows components to interact through their boundary *or control* layers; here, the facade (ports and events, contract in the `-api` module) is the only lawful crossing, and Control never addresses another module directly.
3. **Boundaries enforced by the platform, not by convention.** bce.design components are packages; here each component is a Java module pair, so cohesion and coupling rules are load-time-verified rather than reviewed.

### Orchestration Is External

Inter-module orchestration (and, after extraction, inter-service orchestration) is not carried by the Control objects. It is externalized, either through event choreography or through a dedicated saga orchestrator. This decision is what makes BCE viable in a distributed perspective: each module remains an autonomous black box whose Control is scoped to a single local use case.

### Inter-Module Facade: Event Is Not Request/Response

The facade between modules explicitly distinguishes two communication regimes, because they do not translate the same way once distributed.

| Regime | Local mechanism | Distributed transposition |
|--------|------------------|----------------------------|
| Notification (pub/sub) | CDI event (`Event<T>`, `@Observes`, `@ObservesAsync`) | Asynchronous message to a broker, via a transactional outbox |
| Request/response | Contract interface (port) resolved via CDI injection | REST stub (MicroProfile REST Client via cyrano) or gRPC |

Guiding rule: do not force request/response into an event. Events serve legitimate notifications; ports (Java interfaces) serve interactions that expect a response. Both cross the same transport boundary, with distinct contracts.

### Design Contracts as Remote-Compatible From the Start

The historical risk of this kind of design (RMI, CORBA) is pretending a remote call is equivalent to a local one. Inter-module contracts are therefore designed, even while running in-process, **for failure**: timeouts, partial failures, idempotency, serializable payloads (DTOs, never Entities).

With virtual threads as the platform default, remote-compatibility does **not** mean asynchronous signatures. A blocking request/response signature is perfectly sound on a virtual thread; `CompletionStage`-shaped APIs are not required and should be avoided unless a use case genuinely needs them. What must be designed upfront is the failure envelope, expressed through MicroProfile Fault Tolerance annotations (heisenberg) on the port implementation from day one: `@Timeout`, `@Retry` (on idempotent operations only), `@CircuitBreaker`. These annotations are no-cost documentation while in-process and become load-bearing at extraction.

## Data Ownership

This section states the single most important extractability prerequisite — more important than Java Modules tightness, because module boundaries that share data are boundaries in name only.

- **Each module owns its persistent model.** Entities are never shared across modules; they never appear in a contract package.
- **No cross-module Jakarta Persistence relationships.** A module never maps an association (`@ManyToOne`, etc.) to another module's Entity. Cross-module references are held as identifiers (typed IDs in DTOs), not object references.
- **No cross-module foreign keys enforced at the schema level** for modules that are extraction candidates. Referential integrity across module boundaries is the job of the consuming module's logic (and, post-extraction, of compensation).
- **Schema-per-module** (or at minimum table-namespace-per-module), so that a module's data can move with it at extraction time.

Anti-corruption applies at the Boundary: what crosses the facade is a DTO belonging to the contract, translated to/from Entities inside the module.

## Java Modules Modularity

### What Java Modules Guarantee

Java Modules provide strong encapsulation verified by the platform. A package that is not exported is inaccessible from another module: direct references fail at compile time, and reflective access fails at runtime. The `requires` / `exports` system makes the dependency graph explicit and acyclic: the module system refuses cycles at startup, which protects the ability to extract modules later.

### Contract Modules: the `-api` Split

**Each BCE unit is materialized as two Java modules:**

- `<unit>-api` — the contract module: port interfaces, event payload types, DTOs. This is the only module other units are allowed to `requires`.
- `<unit>` — the implementation module: Boundary, Control, Entity. `requires` its own `-api` and the `-api` modules of the units it consumes. **No unit ever `requires` another unit's implementation module.**

This split is what makes the extraction promise real. If a port were exported by the implementation module, every caller would `requires` the implementation — and at extraction time the caller would still drag the implementation jar onto its module path. With the `-api` split, the caller's dependency graph is unchanged by extraction: only the binding behind the contract changes. The split also structurally rules out dependency cycles between implementations (two units may consume each other's `-api` without forming a cycle between implementations).

### Java Modules and CDI Working Together

The two mechanisms operate at different levels and complement each other:

- Java Modules act at compile time and load time: type visibility, static boundary.
- CDI acts at runtime: bean resolution, injection, events, interception.

On Vidocq the classical tension between the two — the CDI container needing reflective access, hence `opens ... to` the container — **does not apply**. Vauban is a CDI 4.1 Lite container that discovers and wires beans through build-time code generation, not runtime introspection. The target is therefore **zero `opens`**: implementation modules export nothing (not even to the container) beyond what the generated wiring requires, and the generated wiring lives inside the module. Any `opens` directive in an application module is a design smell to be justified in review.

The same target applies to persistence: the mansart Jakarta Persistence implementation (in progress) follows the ecosystem's static-generation philosophy, so Entity access is expected to go through generated accessors rather than reflective `opens`. Should a transitional `opens ... to` be required while that implementation matures, it must be scoped to the persistence module and tracked as technical debt.

### Chosen Granularity: One Java Module Pair per BCE Unit

The Java Modules boundary is placed at the level of the BCE unit. Each functional unit is thus physically sealed off.

| Consequence | Detail |
|-------------|--------|
| **Benefit** | Maximum encapsulation. The contract is the only possible coupling surface. At extraction time, it is clear exactly what must become a REST or gRPC call, because the module system has forbidden any other coupling. |
| **Cost** | Declaration verbosity grows linearly with the number of units: two `module-info.java` per unit, more `requires` / `exports` to wire. |

This cost is a development and friction cost, not a performance cost (see the clarification below).

### Java Modules Costs: Clarification

> **Important:** Java Modules introduce no significant runtime performance overhead. Accessibility checks are resolved when the module graph is resolved, at startup, not on every call. Once the graph is resolved, an inter-module call is a normal method call. Java Modules are also the enabler of jlink (a reduced runtime image), which is an asset for Vidocq's distribution.

The real costs are:

- **Cognitive and maintenance cost**: each `module-info.java` must be kept up to date (dependencies, exports).
- **Build and tooling cost**: module-path and Maven coexist with some friction (tests, plugins, automatic modules).
- **Ecosystem friction cost**: unmodularized third-party dependencies (automatic modules) introduce fragility (unstable derived name, reading the entire module-path). This cost only concerns third-party dependencies, not Vidocq's own code — and the ecosystem's zero-dependency philosophy keeps it marginal.

## Facade Mechanism

### Overview

The facade relies on an abstract transport boundary. A Control that solicits another module does not call it directly: it emits an event, or invokes a port. Local versus remote resolution is a deployment configuration decision.

```
Module A (Control)                        Module B (Boundary)
     |                                           |
     |-- Event<T> / port -->  [facade]  ------> local observer / port impl.
                                 |
              in-process : generated local binding
              distributed: generated REST/gRPC stub + outbox relay
```

### Notification via CDI Event

For notification-type interactions, the Control emits an event whose payload type lives in the emitter's `-api` module. The emitter does not know the observers. In-process, observation is dispatched by vauban's generated observer registry. Upon extraction, the local observer is replaced with a relay that forwards the event to a broker — see [Event Semantics and the Extraction Gap](#event-semantics-and-the-extraction-gap) for why this replacement is not semantically transparent and what compensates for that.

### Request/Response via Port

For interactions that expect a response, a contract interface (the port) is defined in the target unit's `-api` module. CDI resolves it locally via injection. Upon extraction, the local binding is replaced with a MicroProfile REST Client stub (cyrano) or a gRPC stub, without touching the caller's code.

### Build-Time Generated Routing (Chosen Direction)

Revision 0.1 sketched a CDI interceptor on a custom annotation as the routing mechanism. This is retired for two reasons:

1. CDI interceptors bind to bean implementations, not to injection points of an interface; intercepting "the port" would in fact intercept a specific implementation, defeating the indirection.
2. A runtime routing proxy contradicts the ecosystem's no-dynamic-proxies, static-generation philosophy — and CDI Lite (vauban) does not offer runtime portable extensions to hang such a mechanism on.

The chosen direction is **build-time generation**: an annotation on the port (sketch below) drives an APT / Class-File API step that generates, per port, the candidate bindings — a local delegate and a remote stub. Which binding is *activated* is resolved at startup from MicroProfile Config (ravel), e.g. `facade.catalogue.transport=local|rest`. Transport remains a deployment decision; the decision point is startup wiring of pre-generated code, never runtime proxying.

```java
// Sketch, non-normative — lives in catalogue-api
@ModuleBoundary(module = "catalogue")
public interface CataloguePort {
    // Blocking signature: sound on virtual threads.
    // Failure envelope declared on the binding via Fault Tolerance.
    CatalogueResult lookup(CatalogueQuery query);
}
```

### Cross-Cutting Concerns at the Facade

The facade is also where the runtime's MicroProfile bricks attach, identically for local and remote bindings so that extraction does not change observability or security posture:

| Concern | Brick | Behavior at the facade |
|---------|-------|------------------------|
| Fault tolerance | heisenberg | `@Timeout` / `@Retry` / `@CircuitBreaker` on port bindings, active from day one |
| Telemetry | humboldt | A facade crossing opens a span; trace context propagates in-process (same trace) and over HTTP (W3C headers) identically |
| Identity | cervantes | Caller identity (JWT) available at the Boundary; propagated as token on remote bindings |
| Configuration | ravel | Transport selection, endpoint addresses, per-port tuning |
| Serialization | champollion | Contract DTOs are JSON-B-serializable by construction; this is enforced by contract tests even while in-process |

## Event Semantics and the Extraction Gap

Replacing a local observer with a broker relay does not modify the emitter's *code*, but it does change the *semantics*. This section names the gaps and the mandated mitigations, because they must be designed in while still in-process — retrofitting them at extraction time is the expensive path this architecture exists to avoid.

| Property | In-process `@Observes` | In-process `@ObservesAsync` | Broker (post-extraction) |
|----------|------------------------|------------------------------|--------------------------|
| Delivery | Exactly-once, synchronous | At-most-once, in-memory | At-least-once |
| Transaction | Runs inside the emitter's transaction | Outside | Outside, delayed |
| Ordering | Program order | Unspecified | Per-partition at best |
| Failure visibility | Exception reaches emitter | Lost unless handled | Retry/DLQ policies |

Mandated consequences:

1. **Emitters must not rely on synchronous observation.** Any event that a candidate-for-extraction module emits is treated as if delivered later, possibly more than once, possibly out of order. If the emitter needs the observer's outcome, that interaction is a port, not an event.
2. **Observers must be idempotent.** Event payloads carry a stable event id; consumers deduplicate on it. This is cheap in-process and mandatory post-extraction.
3. **Transactional outbox from day one for extraction candidates.** An event that reflects a state change is persisted in the emitting module's own schema (outbox table) in the same local transaction as the state change; a relay publishes from the outbox. In-process, the relay is a trivial local dispatcher; post-extraction, it publishes to the broker. This closes the dual-write problem *before* it exists.
4. **Event contracts are versioned.** Payload types in `-api` modules evolve additively; breaking changes require a new event type. Serialization compatibility is guarded by contract tests (champollion JSON-B round-trip).

## Transactional Consistency

This is the point to settle early, since it shapes the form of the contracts.

The transactional substrate is mansart-transactions: a **local-only Jakarta Transactions 2.0** implementation, bound to the current virtual thread via `ScopedValue`. Two-phase commit and recovery are explicitly out of scope today. Consequently:

- **In-process**, a single local transaction covers one module's state change plus its outbox write (same datasource). It does **not** span multiple datasources, and this specification does not assume it ever will.
- **Across modules**, even in-process, consistency is *already* eventual by design: module A's transaction commits its state and its outbox entry; module B observes the event afterwards. This is a feature, not a limitation — it means extraction changes latency, not the consistency model.
- **Post-extraction**, nothing changes conceptually: sagas with compensation replace any workflow that needs multi-module agreement, and they can (should) be introduced while still in-process.

Design consequence: contracts of modules that are genuine extraction candidates tolerate eventual consistency (idempotency, compensation) from the start. Modules that genuinely require strong multi-module transactional coupling are thereby declared **non-extractable as separate units** — they must live in the same module or be merged before extraction. Naming which modules fall in which category is an open decision to be settled per bounded context.

## Platform Coverage

Mapped to the actual Vidocq building blocks:

| Capability | Profile status | Vidocq brick |
|-----------|----------------|--------------|
| Synchronous and asynchronous events | Core Profile (CDI 4.1 Lite) | vauban |
| Inbound endpoint | Core Profile (Jakarta REST 4.0) | cassini (over foy/chappe) |
| Contract serialization | Core Profile (JSON-P 2.1 / JSON-B 3.0) | champollion |
| Persistence | **Extension — not in Core Profile** (Jakarta Persistence 3.2, Jakarta Data 1.0) | mansart (Persistence implementation in progress) |
| Local transactions | **Extension — not in Core Profile** (Jakarta Transactions 2.0, local-only) | mansart-transactions |
| Outbound REST port | MicroProfile REST Client | cyrano |
| Fault tolerance on ports | MicroProfile Fault Tolerance 4.1 | heisenberg |
| Tracing across the facade | MicroProfile Telemetry 2.1 | humboldt |
| Identity propagation | MicroProfile JWT 2.1 | cervantes |
| Deployment/routing configuration | MicroProfile Config 3.1 | ravel |
| Health of extracted services | MicroProfile Health 4.0 | knock |
| Outbound gRPC port | Outside Core Profile and MicroProfile | Optional extension, plugged in as an activatable module |

## Testing Strategy

Aligned with the ecosystem's mandatory TDD:

- **Per-module tests** exercise Control and Entity behind the Boundary, on the module path, without other units on the graph — the `-api`-only dependency rule makes this cheap.
- **Contract tests per port and per event type**: for each contract, a consumer-side test (what the caller assumes) and a provider-side test (what the Boundary guarantees), plus a JSON-B round-trip test proving the contract is serialization-clean even while in-process. These tests are the extraction safety net.
- **Facade routing tests**: each port binding (local, remote stub) is exercised against the same contract test suite; the remote binding runs against the real Boundary deployed in a container (Arquillian, per ecosystem convention).
- **Consistency tests**: outbox relay and observer idempotency are tested by replaying duplicate and out-of-order events in-process.

## Comparison with Spring Modulith

Spring Modulith is the reference point for this specification: an architectural approach plus tooling for modular monoliths that can later be split into services. The table below maps its capabilities to this architecture and states where the Vidocq approach is structurally stronger, equivalent, or still to be built.

| Capability | Spring Modulith | This architecture | Assessment |
|-----------|-----------------|-------------------|------------|
| Module boundary definition | Package convention (`ApplicationModules`), sub-packages hidden by convention | Java module pair (`-api` + implementation), non-exported packages inaccessible | **Stronger.** Boundaries are a platform property, not a convention. |
| Boundary verification | `ApplicationModules.verify()` — an ArchUnit-style test that can be skipped, misconfigured, or drift | Compilation and module-graph resolution fail on violation | **Stronger.** No verification test to maintain; violation is a build break, not a red test. |
| Allowed dependencies / named interfaces | `@ApplicationModule(allowedDependencies = …)`, `@NamedInterface` for selective exposure | `requires` / `exports` in `module-info.java` | **Equivalent, native.** The declaration *is* the enforcement. |
| Cycle detection | Verification-time rejection | Module system refuses cycles at startup; `-api` split prevents implementation cycles by construction | **Stronger.** |
| Event Publication Registry | Runtime registry persisting event publications, completion tracking, resubmission | Transactional outbox in the emitter's schema (see [Event Semantics](#event-semantics-and-the-extraction-gap)), relay generated at build time | **Equivalent in intent; to be built.** Static generation instead of runtime infrastructure. |
| Event externalization | `@Externalized` → Kafka/AMQP/JMS bridges | Outbox relay swapped from local dispatcher to broker publisher at extraction | **Equivalent in intent; to be built.** |
| Module integration test slices | `@ApplicationModuleTest` bootstraps one module with neighbors mocked | Per-module test on the module path with generated port doubles (see [Tooling Scope](#tooling-scope)) | **To be built.** Java Modules make the isolation itself free; the tooling gap is generated doubles and harness. |
| Documentation generation | C4 / PlantUML diagrams and module canvases derived from the application model | Derivable from `module-info.java` graphs + `@ModuleBoundary` metadata — the model is machine-readable by construction | **To be built; cheaper.** No separate application model to maintain. |
| Runtime observability | Actuator endpoint + Micrometer spans per module crossing | humboldt (MicroProfile Telemetry) spans opened at each facade crossing; module metadata on spans | **Equivalent; to be wired.** |
| Moments / passage-of-time events | `HourHasPassed` etc. | Out of scope | Deliberate omission — trivially added later if a use case appears. |
| Runtime cost model | Runtime reflection, proxies, application-context machinery | Build-time generation, zero reflection, AOT-compatible (jlink, Leyden CDS) | **Stronger**, aligned with ecosystem philosophy. |

Two structural differences summarize the comparison. First, Spring Modulith *verifies* boundaries that the platform cannot express; this architecture *expresses* boundaries the platform enforces — the entire verification tooling category disappears. Second, Spring Modulith is runtime infrastructure over a reflective container; this architecture is build-time generation over a static one, which is what makes it AOT-friendly and zero-overhead in production.

What Spring Modulith gets right and must not be lost: the developer experience is a *product*. Its adoption comes from the low ceremony (one dependency, one test, free documentation). The tooling scope below exists so that this architecture offers a comparable experience rather than a checklist of hand-written patterns.

## Tooling Scope

The preceding sections define patterns; this section defines the deliverable that automates them. Without this tooling, the architecture is a style guide; with it, it is a product — the Vidocq counterpart of Spring Modulith, provisionally referred to as the **modulith brick** (name to be chosen, see [Open Decisions](#open-decisions)).

The brick is a Vidocq extension (build-time participation, consistent with the runtime's extension SPI) comprising:

1. **Facade code generation** — APT / Class-File API processing of `@ModuleBoundary` ports: local delegate, REST stub (cyrano-backed), outbox-aware event relay. Startup binding driven by ravel configuration. This is the core deliverable and the largest effort.
2. **Outbox support** — generated outbox table mapping per emitting module (coordinated with mansart), relay implementations (in-process dispatcher now, broker publisher at extraction), event-id-based deduplication helper for observers.
3. **Test harness** — a per-module test kit: module-path isolation preconfigured, generated contract-conformant doubles for consumed ports, replay utilities for duplicate/out-of-order event delivery (the Modulith `@ApplicationModuleTest` equivalent, minus the need for boundary-verification tests).
4. **Documentation generation** — a build step rendering the module graph (from `module-info.java` and `@ModuleBoundary` metadata) as C4/PlantUML/Mermaid artifacts, suitable for Antora inclusion in vidocq-docs.
5. **Observability wiring** — humboldt span conventions for facade crossings (module name, port, transport attributes), identical local and remote, so extraction does not change dashboards.

Explicitly out of the brick's scope: saga orchestration (separate concern, see Open Decisions), gRPC transport (optional extension), broker implementations (the relay targets an SPI, not a specific broker).



1. **Application-module naming convention** — bce.design mandates domain-driven names (`checkout`, `inventory`, …), which weighs against extending the ecosystem's historical-figures convention to application modules; historical figures would remain reserved for runtime bricks. To be confirmed.
2. **Consistency classification per module**: which bounded contexts tolerate eventual consistency (extraction candidates) and which require strong local coupling (declared non-extractable).
3. **Policy toward automatic modules**: which unmodularized third-party dependencies are tolerated, and which warrant a modularized alternative. (Largely moot given the zero-dependency philosophy, but must be stated for application-level dependencies.)
4. **Packaging of BCE units relative to the Vidocq extension SPI**: is an application unit packaged as a runtime extension (participating in the runtime's build-time wiring), or as a plain pair of Java modules discovered by the application assembly? This decision determines who runs the facade code generation.
5. **Saga orchestrator placement**: a dedicated module (which would itself be an extraction candidate), or a runtime-provided service.
6. **Outbox relay implementation**: polling vs. synchronous in-process dispatch with persisted fallback; broker abstraction upon extraction.
7. **gRPC extension shape**, if and when a port justifies it.
8. **Name and repository of the modulith brick** — following the ecosystem's historical-figures convention (a candidate theme: a figure associated with assembling independent parts into a coherent whole).

## Glossary

- **BCE**: Boundary-Control-Entity (also Entity-Control-Boundary). A responsibility-distribution pattern from Ivar Jacobson's work, adopted here in the interpretation of [bce.design](https://bce.design) (Adam Bien).
- **Boundary**: The boundary object, the only interaction point of a module with the outside world.
- **Bounded context**: A delimited context in the DDD sense, the coherence scope of a business model.
- **CDI**: Contexts and Dependency Injection. Jakarta EE's dynamic wiring mechanism; Vidocq implements the Lite profile (vauban) with build-time wiring.
- **Contract module (`-api`)**: The Java module carrying a unit's ports, event payload types, and DTOs; the only module other units may `requires`.
- **Java Modules**: The Java Platform Module System, providing encapsulation verified at compile time and load time.
- **Outbox**: A pattern persisting emitted events in the emitter's own transaction, relayed asynchronously; closes the dual-write problem.
- **Port**: A contract interface for a request/response interaction, transposable into a remote call.
- **Saga**: A distributed transaction management pattern using compensable steps.
