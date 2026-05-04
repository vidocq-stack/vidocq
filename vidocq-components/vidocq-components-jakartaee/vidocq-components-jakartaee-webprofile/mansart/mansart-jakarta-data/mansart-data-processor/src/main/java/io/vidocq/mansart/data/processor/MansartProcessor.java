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
        "io.vidocq.mansart.data.Entity",
        "jakarta.persistence.Entity",
        "jakarta.data.repository.Repository"
})
public final class MansartProcessor extends AbstractProcessor {

    private EntityScanner             scanner;
    private MansartMetamodelWriter    mansartWriter;
    private JpaMetamodelWriter        jpaWriter;
    private RepositoryWriter          repositoryWriter;
    private boolean                   jpaPresent;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        this.jpaPresent       = EntityScanner.isJpaPresent(env);
        this.scanner          = new EntityScanner(env, jpaPresent);
        this.mansartWriter    = new MansartMetamodelWriter(env.getFiler());
        this.jpaWriter        = jpaPresent ? new JpaMetamodelWriter(env.getFiler()) : null;
        this.repositoryWriter = new RepositoryWriter(env.getFiler(),
                env.getElementUtils(), env.getTypeUtils());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        if (round.processingOver()) return false;

        Set<TypeElement> entities     = new LinkedHashSet<>();
        Set<TypeElement> repositories = new LinkedHashSet<>();

        for (TypeElement annotation : annotations) {
            String fqn = annotation.getQualifiedName().toString();
            for (Element e : round.getElementsAnnotatedWith(annotation)) {
                if (!(e instanceof TypeElement t)) continue;
                if (fqn.equals("jakarta.data.repository.Repository")) repositories.add(t);
                else                                                  entities.add(t);
            }
        }

        for (TypeElement type : entities) {
            EntityScanner.EntityDescriptor descriptor = scanner.scan(type);
            if (descriptor == null) continue;
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
                repositoryWriter.writeIfRepository(repo);
            } catch (IOException ex) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "[mansart-data] Failed to write repository impl for "
                                + repo.getQualifiedName() + ": " + ex.getMessage(), repo);
            }
        }
        return true;
    }
}
