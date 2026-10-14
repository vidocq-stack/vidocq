/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package fixture.proc;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Test fixture: lists the types annotated with {@code fixture.Other} in {@code META-INF/fixture/b.list}. */
@SupportedAnnotationTypes("fixture.Other")
public class ProcessorB extends AbstractProcessor {

    private final List<String> types = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (TypeElement annotation : annotations) {
            for (Element e : round.getElementsAnnotatedWith(annotation)) {
                types.add(((TypeElement) e).getQualifiedName().toString());
            }
        }
        if (round.processingOver() && !types.isEmpty()) {
            try (Writer w = processingEnv.getFiler()
                    .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/fixture/b.list").openWriter()) {
                for (String type : types) {
                    w.write(type + "\n");
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return false;
    }
}
