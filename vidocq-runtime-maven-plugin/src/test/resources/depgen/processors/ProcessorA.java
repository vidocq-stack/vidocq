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

/**
 * Test fixture: generates {@code <Type>$$Gen} for every type annotated with {@code fixture.Marked} and lists them
 * in {@code META-INF/fixture/a.list}. It walks the root elements and their nested types itself, so a nested type
 * passed to javac on its own as well as through its outer type is visited twice and the second source fails.
 */
@SupportedAnnotationTypes("fixture.Marked")
public class ProcessorA extends AbstractProcessor {

    private final List<String> types = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        try {
            for (Element root : round.getRootElements()) {
                visit(root);
            }
            if (round.processingOver() && !types.isEmpty()) {
                try (Writer w = processingEnv.getFiler()
                        .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/fixture/a.list").openWriter()) {
                    for (String type : types) {
                        w.write(type + "\n");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return false;
    }

    private void visit(Element element) throws IOException {
        if (!(element instanceof TypeElement type)) {
            return;
        }
        boolean marked = type.getAnnotationMirrors().stream()
                .anyMatch(m -> m.getAnnotationType().toString().equals("fixture.Marked"));
        if (marked) {
            String pkg = processingEnv.getElementUtils().getPackageOf(type).getQualifiedName().toString();
            String name = type.getSimpleName() + "$$Gen";
            try (Writer w = processingEnv.getFiler().createSourceFile(pkg + "." + name, type).openWriter()) {
                w.write("package " + pkg + ";\npublic final class " + name + " {\n"
                        + "    public static final String TARGET = \"" + type.getQualifiedName() + "\";\n}\n");
            }
            types.add(type.getQualifiedName().toString());
        }
        for (Element enclosed : type.getEnclosedElements()) {
            visit(enclosed);
        }
    }
}
