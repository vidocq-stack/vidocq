package io.vidocq.mansart.data.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

@SupportedSourceVersion(SourceVersion.RELEASE_25)
@SupportedAnnotationTypes({
        "jakarta.persistence.Entity",
        "jakarta.data.repository.Repository"
})
public final class MansartProcessor extends AbstractProcessor {

    private EntityScanner             scanner;
    private MansartMetamodelWriter    mansartWriter;
    private JpaMetamodelWriter        jpaWriter;
    private RepositoryWriter          repositoryWriter;
    private EntityRegistry            entityRegistry;
    private final java.util.List<String> repoEntries = new java.util.ArrayList<>();

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.scanner          = new EntityScanner(env);
        this.mansartWriter    = new MansartMetamodelWriter(env.getFiler());
        // M7-29 — JPA static metamodel emission only when the SingularAttribute SPI is present
        // on the user's classpath (jakarta.persistence-api 3.2 ships it). Always-on otherwise
        // would force the dep on every consumer.
        this.jpaWriter        = env.getElementUtils()
                .getTypeElement("jakarta.persistence.metamodel.SingularAttribute") != null
                ? new JpaMetamodelWriter(env.getFiler()) : null;
        this.entityRegistry   = new EntityRegistry();
        this.repositoryWriter = new RepositoryWriter(env.getFiler(),
                env.getElementUtils(), env.getTypeUtils(), entityRegistry);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) {
            writeRepositoriesIndex();
            return false;
        }

        Set<TypeElement> entities     = new LinkedHashSet<>();
        Set<TypeElement> repositories = new LinkedHashSet<>();

        for (TypeElement annotation : annotations) {
            String fqn = annotation.getQualifiedName().toString();
            for (Element e : round.getElementsAnnotatedWith(annotation)) {
                if (!(e instanceof TypeElement t)) continue;
                if (fqn.equals("jakarta.data.repository.Repository")) repositories.add(t);
                else if (fqn.equals("jakarta.persistence.Entity"))    entities.add(t);
            }
        }

        for (TypeElement type : entities) {
            EntityScanner.EntityDescriptor descriptor = scanner.scan(type);
            if (descriptor == null) continue;
            // M8-3i — register the entity facet so RepositoryWriter can resolve
            // method-name path expressions (findByAuthorName) in the same round.
            entityRegistry.register(type.getQualifiedName().toString(), descriptor);
            try {
                mansartWriter.write(descriptor);
                if (jpaWriter != null) jpaWriter.write(descriptor);
            } catch (IOException ex) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[mansart-data] Failed to write metamodel for "
                                + type.getQualifiedName() + ": " + ex.getMessage(), type);
            }
        }

        for (TypeElement repo : repositories) {
            try {
                if (repositoryWriter.writeIfRepository(repo)) {
                    String pkg = processingEnv.getElementUtils().getPackageOf(repo)
                            .getQualifiedName().toString();
                    String simple = repo.getSimpleName().toString();
                    String itfFqn  = repo.getQualifiedName().toString();
                    String implFqn = pkg.isEmpty() ? simple + "Impl" : pkg + "." + simple + "Impl";
                    repoEntries.add(itfFqn + "=" + implFqn);
                }
            } catch (IOException ex) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[mansart-data] Failed to write repository impl for "
                                + repo.getQualifiedName() + ": " + ex.getMessage(), repo);
            }
        }

        return true;
    }

    /** Writes {@code META-INF/mansart-repositories.list} consumed by the CDI BCE. */
    private void writeRepositoriesIndex() {
        if (repoEntries.isEmpty()) return;
        try {
            javax.tools.FileObject f = processingEnv.getFiler().createResource(
                    javax.tools.StandardLocation.CLASS_OUTPUT, "",
                    "META-INF/mansart-repositories.list");
            try (java.io.Writer w = f.openWriter()) {
                w.write("# Generated by mansart-data-processor — one line per @Repository\n");
                w.write("# format: <interface-fqn>=<impl-fqn>\n");
                for (String e : repoEntries) { w.write(e); w.write('\n'); }
            }
        } catch (IOException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[mansart-data] Failed to write META-INF/mansart-repositories.list: " + ex.getMessage());
        }
    }
}
