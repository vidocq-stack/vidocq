# Chapter 18, Section 9 — Container and Provider Contracts for Deployment and Bootstrapping

## 9. Jakarta EE Deployment

9.1 — Each persistence unit deployed into a Jakarta EE container consists of a single persistence.xml file, any number of mapping files, and any number of class files.
9.1 — At deployment time the container is responsible for scanning the locations specified in Section 8.2 and discovering the persistence.xml files and processing them.
9.1 — When the container finds a persistence.xml file, it must process the persistence unit definitions that it contains.
9.1 — The container must validate the persistence.xml file against the persistence_3_2.xsd, persistence_3_0.xsd or persistence_2_2.xsd schema in accordance with the version specified by the persistence.xml file and report any validation errors.
9.1 — Provider or data source information not specified in the persistence.xml file must be provided at deployment time or defaulted by the container.
9.1 — The container must implement the PersistenceUnitInfo interface described in Section 9.6 and pass the metadata—in the form of a PersistenceUnitInfo instance—to the persistence provider as part of the createContainerEntityManagerFactory call.
9.1 — If a Bean Validation provider exists in the container environment and the validation-mode NONE is not specified, a ValidatorFactory instance must be made available by the container.
9.1 — The container is responsible for passing the ValidatorFactory instance via the map that is passed as an argument to the createContainerEntityManagerFactory call. The map key used must be the standard property name jakarta.persistence.validation.factory.
9.1 — If CDI is enabled, a BeanManager instance must be made available by the container.
9.1 — The container is responsible for passing the BeanManager instance via the map that is passed as an argument to the createContainerEntityManagerFactory call. The map key used must be the standard property name jakarta.persistence.bean.manager.
9.1 — Only one EntityManagerFactory is permitted to be created for each deployed persistence unit configuration.

## 9.2. Bootstrapping in Java SE Environments

9.2 — A persistence provider implementation running in a Java SE environment should also act as a service provider by supplying a service provider configuration file as defined by the Java SE platform.
9.2 — The provider supplies the provider configuration file by creating a text file named jakarta.persistence.spi.PersistenceProvider and placing it in the META-INF/services directory of one of its JAR files.
9.2 — The Persistence bootstrap class must locate all of the persistence providers using the PersistenceProviderResolver mechanism described in Section 9.3 and call createEntityManagerFactory on them in turn until an appropriate backing provider returns an EntityManagerFactory instance.
9.2 — If a provider does not qualify as the provider for the named persistence unit, it must return null when createEntityManagerFactory is invoked on it.

## 9.2.1. Schema Generation (Java SE)

9.2.1 — The Persistence bootstrap class must locate all of the persistence providers using the PersistenceProviderResolver mechanism described in Section 9.3 and call generateSchema on them in turn until an appropriate backing provider returns true.
9.2.1 — If a provider does not qualify as the provider for the named persistence unit, it must return false when generateSchema is invoked on it.

## 9.3. Determining the Available Persistence Providers

9.3 — The implementation of PersistenceProviderResolverHolder must be threadsafe.
9.3 — The container is allowed to implement and set a specific PersistenceProviderResolver provided that it respects the PersistenceProviderResolver contract.
9.3 — If no PersistenceProviderResolver is set, the PersistenceProviderResolverHolder must return a PersistenceProviderResolver that returns the providers whose persistence provider jars have been installed or made available as service providers or extensions.
9.3 — A PersistenceProviderResolver must be threadsafe.
9.3 — The PersistenceProviderResolver.getPersistenceProviders() method must be used to determine the list of available persistence providers.
9.3 — The results of calling the PersistenceProviderResolverHolder.getPersistenceProviderResolver and the PersistenceProviderResolver.getPersistenceProviders methods must not be cached.
9.3 — The following methods must use the PersistenceProviderResolver instance returned by the PersistenceProviderResolverHolder.getPersistenceProviderResolver method to determine the list of available providers: Persistence.createEntityManagerFactory(String), Persistence.createEntityManagerFactory(String, Map), PersistenceUtil.isLoaded(Object), PersistenceUtil.isLoaded(Object, String).
9.3 — These methods must not cache the list of providers and must not cache the PersistenceProviderResolver instance.

## 9.4. Schema Generation

9.4 — In Jakarta EE environments, any strings corresponding to file URLs for script sources or targets must specify absolute paths (not relative).
9.4 — In Jakarta EE environments, all source and target file locations must be accessible to the application server deploying the persistence unit.
9.4 — If the jakarta.persistence.schema-generation.database.action property is not specified, no schema generation actions must be taken on the database.
9.4 — If this property [jakarta.persistence.schema-generation.scripts.action] is not specified, no scripts will be generated.
9.4 — If either of the values "metadata-then-script" or "script-then-metadata" is specified and the resulting database actions are not disjoint, the results are undefined and schema generation may fail.
9.4 — If either of the values "metadata-then-script" or "script-then-metadata" is specified and the resulting database actions are not disjoint, the results are undefined and the dropping of database artifacts may fail.
9.4 — If scripts are to be generated by the persistence provider and a connection to the target database is not supplied, the jakarta.persistence.database-product-name property must be specified.
9.4 — If DDL scripts are to be used in Java SE environments or if the Jakarta EE container delegates the execution of scripts to the persistence provider, the jakarta.persistence.sql-load-script-source property must be specified.

## 9.4.1. Data Loading

9.4.1 — If a load script is to be used in Java SE environments or if the Jakarta EE container delegates the execution of the load script to the persistence provider, the jakarta.persistence.sql-load-script-source property must be specified.

## 9.5. Responsibilities of the Persistence Provider

9.5 — The persistence provider must implement the PersistenceProvider SPI.
9.5 — In Jakarta EE environments, the persistence provider must process the metadata that is passed to it at the time createContainerEntityManagerFactory method is called and create an instance of EntityManagerFactory using the PersistenceUnitInfo metadata for the factory.
9.5 — In Java SE environments, the persistence provider must validate the persistence.xml file against the persistence schema that corresponds to the version specified by the persistence.xml file and report any validation errors.
9.5 — When the entity manager factory for a persistence unit is created, it is the responsibility of the persistence provider to initialize the state of the metamodel classes of the persistence unit.
9.5 — The persistence provider must validate any object/relational mapping files against the object/relational mapping schema version specified by the object/relational mapping file and report any validation errors.
9.5 — The object relational mapping file must specify the object/relational mapping schema that it is written against by indicating the version element.
9.5 — If no ValidatorFactory instance is provided by the application, and if a Bean Validation provider is present in the classpath, the persistence provider must instantiate the ValidatorFactory using the default bootstrapping approach as defined by the Bean Validation specification.

## 9.5.1. jakarta.persistence.spi.PersistenceProvider

9.5.1 — The PersistenceProvider interface found in Section E.3 must be implemented by the persistence provider.
9.5.1 — The PersistenceProvider implementation class must have a public constructor with no parameters.

## 9.6. jakarta.persistence.spi.PersistenceUnitInfo Interface

9.6 — The getSharedCacheMode method must return UNSPECIFIED if the shared-cache-mode element has not been specified for the persistence unit.

## 9.7. jakarta.persistence.Persistence Class

9.7 — When properties are specified in the Map parameter passed to the createEntityManagerFactory method, their values override the values of the corresponding elements and attributes in the persistence.xml file for the named persistence unit.
9.7 — Any vendor-specific properties that make use of the namespace jakarta.persistence and its subnamespaces must not be used for vendor-specific information. The namespace jakarta.persistence is reserved for use by this specification.
9.7 — If a persistence provider does not recognize a property (other than a property defined by this specification), the provider must ignore it.

## 9.8. jakarta.persistence.PersistenceConfiguration Class

9.8 — A provider must support configuration via any instance of PersistenceConfiguration or of any subclass of PersistenceConfiguration.

## 9.9. PersistenceUtil Interface

9.9.1 — The implementation of the PersistenceUtil.isLoaded(Object) method must determine the list of persistence providers available in the runtime environment and call the ProviderUtil.isLoaded(Object) method on each of them until one provider returns LoadState.LOADED, one provider returns LoadState.NOT_LOADED, or all providers return LoadState.UNKNOWN.
9.9.1 — The implementation of the PersistenceUtil.isLoaded(Object,String) method must determine the list of persistence providers available in the environment and call the ProviderUtil.isLoadedWithoutReference method on each of them until one provider returns LoadState.LOADED, one provider returns LoadState.NOT_LOADED, or all providers return LoadState.UNKNOWN (in which case it then calls ProviderUtil.isLoadedWithReference on each of the providers).
