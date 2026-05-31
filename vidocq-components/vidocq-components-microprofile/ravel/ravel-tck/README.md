# Ravel TCK Runner

Arquillian harness for the official **MicroProfile Config 3.1 TCK**
(`org.eclipse.microprofile.config:microprofile-config-tck:3.1`).

## ⚠ Out-of-reactor

This module is intentionally **excluded from the Ravel reactor** and uses a standalone
`Model 4.0.0` POM — see the comment at the top of `pom.xml`.

Do not re-add to `<subprojects>` in `ravel/pom.xml` until ShrinkWrap Maven Resolver
supports Maven Model 4.1.0.

## Running

Always via the script at the Ravel root:

```bash
cd ravel
./run-official-tck-mp-config-3.1.sh         # smoke test
./run-official-tck-mp-config-3.1.sh all     # full suite
./run-official-tck-mp-config-3.1.sh -Dtest=TestName
```

The script first installs the reactor (`mvn install -DskipTests`) then
invokes `mvn -f ravel-tck/pom.xml -Ptck-official test`.

## Status

Phase M0: skeleton; the Arquillian harness will be implemented in M5
(see `ROADMAP.md`).
