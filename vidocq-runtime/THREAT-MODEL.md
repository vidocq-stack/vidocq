# Vidocq Runtime — Threat model

> Status: **draft v0, 2026-10-19.** It is the input of the 1.0.0 security work: the internal
> audit (#277) and the external audit (#278). Tracking issue: #276. It is reviewed and updated
> at every release. Facts below cite the documentation or the bug files they come from; a
> mitigation that is not verified in code says so.

## 1. Scope

The Vidocq runtime as an application gets it: the runtime core and its extensions, and the
bricks that face the network or untrusted input.

| Brick | Role | Untrusted input it handles |
|---|---|---|
| Chappe | HTTP/1.1, HTTP/2, TLS, WebSocket, gRPC server | every byte from the network |
| Foy | Servlet 6.1 container | requests, sessions, multipart, `web.xml` and fragments, static resources |
| Cassini | Jakarta REST 4.0 | requests, entity bodies (through Champollion and providers) |
| Ibarra | Jakarta Pages 4.0 | request data rendered in pages (pages are compiled at build time) |
| Peano | Expression Language 6.0 | expressions over request data |
| Champollion | JSON-P, JSON-B | JSON bodies |
| Cervantes | MicroProfile JWT | bearer tokens |
| Mansart | Jakarta Data, pool, transactions | query parameters reaching SQL |
| Runtime core | boot, configuration, dev console, packaging | configuration sources, the dev console (dev only) |

Out of scope for v0: the build tooling (Maven plugins, annotation processors), which runs on
the build machine; the documentation sites; the CI infrastructure (see §6, supply chain).

## 2. Assets

- **Availability** of the application: threads, memory, file descriptors, connections.
- **Confidentiality and integrity** of the application's data and of its users' sessions.
- **Identity**: authenticated principals (FORM, BASIC, CLIENT-CERT, JWT), their roles.
- **Secrets** in configuration: keys, database credentials, passwords.
- **The artifacts we publish** on Maven Central, and their users.

## 3. Trust boundaries and attackers

1. **The network** → Chappe. Attacker: any client, unauthenticated, possibly many (DoS).
2. **Authenticated clients** → the application. Attacker: a user trying to escalate, or to
   reach another user's data or session.
3. **Application code and configuration** → the runtime. Trusted, but mistakes are likely:
   safe defaults matter.
4. **Dependencies and build** → the published artifacts. Attacker: a compromised dependency
   or build step (supply chain).

## 4. Threats and current mitigations (STRIDE)

### 4.1 Denial of service (network → Chappe, Foy)

| Threat | Mitigation today | Source | Gap |
|---|---|---|---|
| Slow or stalled clients (slowloris), handshakes that never end | `idleTimeout` 60 s, bounding the whole TLS handshake too; `readTimeout` 30 s per body read; `writeTimeout` 30 s per write | chappe `reference.adoc` (CHAPPE-028) | only two slowloris tests; no chaos suite (#273) |
| Oversized headers and bodies | `maxHeaderSize` 8 KiB (431); `maxRequestSize` 10 MiB (413); `maxDrainSize` 1 MiB; Foy `maxChunkedRequestBodySize`, `maxFormBodySize`, `maxMultipartParts`, `maxMultipartPartHeaderSize` | chappe and foy `reference.adoc` | limits not exercised by fuzzing (#271) |
| HTTP/2 abuse (stream floods, HPACK bombs, large header blocks) | header block and decoded list bounds with `GOAWAY(ENHANCE_YOUR_CALM)`; HPACK table bounded; per-stream flow control | chappe `reference.adoc` (RFC 9113 §10.5) | no fuzzing of the frame and HPACK decoders (#271) |
| Unbounded buffering in non-blocking I/O | none: bytes still waiting at the end of a cycle are sent in blocking mode, bounded only by `writeTimeout` | foy BUG-20261010-03 | to fix or bound (#273) |
| Thread exhaustion | one virtual thread per connection | chappe design | behaviour at the connection ceiling not documented as a limit (#273) |

### 4.2 Tampering and injection

| Threat | Mitigation today | Source | Gap |
|---|---|---|---|
| Response header injection (CR/LF, non-Latin-1) | header names and values validated; invalid ones throw or answer 500; the message never echoes the value | chappe CHAPPE-008, cassini#58 | — |
| HTTP/2 malformed responses | connection-specific fields dropped, values trimmed | chappe CHAPPE-018 | — |
| XML external entities in `web.xml` | the DOCTYPE is parsed, never resolved; entity references refused | foy `usage.adoc` (legacy descriptors) | include the fragments in fuzzing (#271) |
| Path traversal in static resources | normalised paths; case check against the real name | foy BUG-20261011-05 (fixed) | case folding on Linux case-insensitive mounts: foy BUG-20261013-01, documented limit |
| SQL injection through Jakarta Data | queries are parameterised (named and positional parameters) | mansart processor and runtime | not verified by a dedicated test yet (#277) |
| Expression injection | pages are compiled at build time; expressions come from the page source, not from requests | ibarra design | a page that evaluates request text as an expression is an application bug; document it (#277) |
| Cross-site scripting | EL output is not escaped by default, as in the spec | Pages 4.0 | document the escaping duty; the Bistro example shows `bistro:escape` |

### 4.3 Spoofing and elevation of privilege

| Threat | Mitigation today | Source | Gap |
|---|---|---|---|
| Session fixation | session id rotated at login | foy `whats-new.adoc` (FORM login) | — |
| Credential storage | in-memory identity store with salted SHA-256 | foy `whats-new.adoc` | SHA-256 is fast: a slow hash (PBKDF2, scrypt, Argon2) for stored passwords (#277) |
| TLS renegotiation changing the client certificate | renegotiation refused | chappe `SslHandler` | — |
| Client certificate handshake stall | handshake unwraps buffered records before reading | chappe CHAPPE-036 | — |
| JWT validation | MicroProfile JWT TCK 206/206 | cervantes | algorithm and key handling to review (#277) |
| Security constraints bypass | `<security-constraint>` and `@ServletSecurity` enforced before filters; Servlet TCK security tests 59/59 | foy `TCK.md` | — |

### 4.4 Information disclosure

| Threat | Mitigation today | Source | Gap |
|---|---|---|---|
| Stack traces or exception messages in responses | 500 without detail; the log names the exception class only | cassini#58 (JDK HTTP adapter), Chappe adapter (cassini#56) | review every brick's error path (#277) |
| Dev console exposed in production | active only in a dev launch; listens on `127.0.0.1` by default | `DevConsoleProbe`, `dev-console.adoc` | a production check that refuses `vidocq.devconsole.host=0.0.0.0` outside dev (#277) |
| Secrets in logs | not verified | — | review (#277) |
| JSP source served as text | Foy maps `*.jsp` to the engine only when an engine is present | foy `reference.adoc` | not verified: without the Ibarra extension, the default servlet may serve a `.jsp` under `META-INF/resources` as text; `vidocq:generate` already warns when pages are not compiled (vidocq#232). To test, and refuse if so (#277) |

### 4.5 Repudiation

No audit log of security events (logins, denials). To decide whether the runtime offers one (#277).

## 5. Defaults that must be safe in production

To verify, one by one, in the internal audit (#277): TLS protocol versions and cipher suites;
HSTS and secure cookie flags; session timeout (15 minutes in the examples); the dev console
off; error pages without detail; request limits on; no default credentials outside dev.

## 6. Supply chain

Today: releases and commits are GPG-signed; CI governance checks (CLA, DCO, signatures).
Missing, planned for 0.4.0: dependency and code scanning (#269), a CycloneDX SBOM per release
(#270), `SECURITY.md` and a vulnerability process (#268). The rule "zero external dependency
in what we ship" keeps the runtime's own dependency surface to the Jakarta and MicroProfile APIs.

## 7. External audit — proposed scope (#278)

- **Targets:** Chappe (HTTP/1.1, HTTP/2, TLS), Foy (request handling, sessions, security
  constraints, multipart, static resources), Cassini (entity handling), Cervantes (JWT),
  the runtime defaults (§5).
- **Method:** source review of the targets above and a penetration test of a reference
  deployment (REST + Servlet + JSP + Data, behind TLS), with this document as the starting
  point.
- **Out of scope:** build tooling, documentation sites, CI infrastructure, the application
  code of the examples beyond the reference deployment.
- **Time box:** to agree with the vendor; the internal audit and the fuzzing campaign run
  first, so the external audit does not spend its time on what tooling finds.
- **Deliverables:** a report with severities, a retest of the fixes, a public summary.
