# TCK.md — Cervantes (MicroProfile JWT 2.1)

## Artifact

Framework: **Arquillian** (the MP JWT TCK is driven by Arquillian + REST, unlike the
MP FT TCK which is TestNG-only). The container starts the full Vidocq stack
(chappe + cassini + vauban + cervantes) to serve protected endpoints and present them
with forged tokens.

Official (non-public) artifact to install in the local M2:

```
org.eclipse.microprofile.jwt:microprofile-jwt-auth-tck:2.1
```

Detailed installation procedure in `cervantes-tck/README.md` (to be created at milestone M6).

## Launch

```bash
./run-official-tck-mp-jwt-2.1.sh            # smoke test
./run-official-tck-mp-jwt-2.1.sh all        # full suite
./run-official-tck-mp-jwt-2.1.sh -Dtest=... # targeted test
```

> `cervantes-tck` is **in-reactor behind the `tck` Maven profile**: a plain `mvn install`
> skips it. Use the script above, or directly `./mvnw -P"tck,smoke" -pl cervantes-tck test`
> (`tck-official` for the full suite). The former out-of-reactor constraint (ShrinkWrap
> Maven Resolver 3.3 vs Model 4.1.0) is obsolete since Maven 3.9.16 / Model 4.0.0.

## Score & Exclusions

| Date | PASS | FAIL | SKIP | Note |
|------|------|------|------|------|
| 2026-06-04 | **206** | 0 | 0 | Full official suite — **100% PASS** |

**206/206 — 100% of the official MicroProfile JWT 2.1 TCK passes.**

Any test exclusion will be documented here with its justification (non-portable spec
interpretation, environment limitation, etc.).

_No functional challenge — 100% of the official suite passes._
