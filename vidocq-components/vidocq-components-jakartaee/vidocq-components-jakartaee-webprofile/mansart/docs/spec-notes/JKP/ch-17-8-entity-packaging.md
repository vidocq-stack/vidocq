# ch-17-8-entity-packaging — Normative Requirements

8.2 — In Jakarta EE environments, the root of a persistence unit must be one of: an EJB-JAR file, the WEB-INF/classes directory of a WAR file, a jar file in the WEB-INF/lib directory of a WAR file, a jar file in the library directory of an EAR, or an application client JAR file.

8.2 — A persistence unit must have a name.

8.2 — The name of the persistence unit must be unique within a given EJB-JAR file, within a given WAR file, within a given application client JAR, or within an EAR.

8.2.1.2 — The value of the transaction-type element must be JTA or RESOURCE_LOCAL.

8.2.1.7 — If neither jta-data-source nor non-jta-data-source is specified, the deployer must specify a data source at deployment, or a default data source must be provided by the container.

8.2.1.8 — Entity classes, embeddable classes, mapped superclasses, and converter classes must be implicitly or explicitly denoted as managed persistence classes to be included within a persistence unit.

8.2.1.8 — The classes and/or jars named as part of a persistence unit must be on the classpath; referencing them from the persistence.xml file does not cause them to be placed on the classpath.

8.2.1.8 — All classes must be on the classpath to ensure that entity managers from different persistence units that map the same class will be accessing the same identical class.

8.2.1.8 — All classes defined at the level of the Jakarta EE EAR must be accessible to other Jakarta EE components in the application (i.e., to all components loaded by the application classloader).

8.2.1.8 — The object/relational mapping information contained in any given mapping file referenced within the persistence unit must be disjoint at the class level from object/relational mapping information contained in other mapping files referenced within the persistence unit.

8.2.1.11 — When scripts are packaged as part of the persistence application, property values must specify locations relative to the root of the persistence unit.

8.2.1.11 — In Jakarta EE environments, file URLs identifying script locations must be absolute paths.

8.2.1.11 — In Jakarta EE environments, all source and target file locations must be accessible to the application server deploying the persistence unit.

8.2.1.11 — If a persistence provider does not recognize a property (other than a property defined by this specification), the provider must ignore it.

8.2.1.11 — The namespace jakarta.persistence is reserved for use by this specification and must not be used to define vendor-specific properties.

8.2.1.2 — The name attribute of persistence-unit is required; the other attributes and elements are optional.

8.2.1.8 — In Java SE environments, an explicit list of all managed persistence class names must be specified to insure portability.

8.2.1.8 — In Java SE environments, a persistence provider may require that the set of entity classes and other classes to be managed is fully enumerated in each persistence.xml file.
