# Knock MP Health API

This module is a minimal fork of `org.eclipse.microprofile.health:microprofile-health-api:4.0.1`.
It adds a `module-info.java` file to make the module Java Modules/jlink compatible.

Changes compared to upstream:
- added `module-info.java` (module name: `microprofile.health.api`)
- removed the OSGi `@org.osgi.annotation.versioning.Version` annotations from the `package-info.java`
  files to avoid a dependency on an automatic module (which blocks jlink)

The sources are copied from the official *sources* JAR and remain under the Apache 2.0 license.
The `META-INF/LICENSE` and `META-INF/NOTICE` files are included as-is.
