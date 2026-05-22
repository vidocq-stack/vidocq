# Knock MP Health API

Ce module est un fork minimal de `org.eclipse.microprofile.health:microprofile-health-api:4.0.1`.
Il ajoute un `module-info.java` pour rendre le module compatible JPMS/jlink.

Modifications par rapport a l'upstream :
- ajout de `module-info.java` (nom de module : `microprofile.health.api`)
- suppression des annotations OSGi `@org.osgi.annotation.versioning.Version` dans les `package-info.java`
  afin d'eviter une dependance vers un module automatique (bloquant jlink)

Les sources sont copiees depuis le JAR *sources* officiel et restent sous license Apache 2.0.
Les fichiers `META-INF/LICENSE` et `META-INF/NOTICE` sont inclus tels quels.
