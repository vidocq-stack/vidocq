/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.runtime.maven;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The main class of the goals that launch an application when {@code vidocq.mainClass} names none: the one class of the
 * application annotated {@code @VidocqMain}, read from the compiled classes. A trampoline written by hand then runs, with
 * the {@code VidocqApp} it passes to {@code Vidocq.run}, without a property to keep in step with it.
 */
public final class AnnotatedMainClass {

    private static final String VIDOCQ_MAIN = "Lio/vidocq/runtime/spi/VidocqMain;";

    private AnnotatedMainClass() {
    }

    /**
     * The main class to launch: {@code configured} when it names an application main class, else the class of
     * {@code classes} annotated {@code @VidocqMain}, said through {@code info}, else {@code configured} unchanged.
     *
     * @throws MojoExecutionException when several classes are annotated {@code @VidocqMain} and none is configured
     */
    public static String resolve(String configured, File classes, Consumer<String> info) throws MojoExecutionException {
        if (ApplicationMainClass.normalize(configured) != null || classes == null || !classes.isDirectory()) {
            return configured;
        }
        List<String> annotated;
        try {
            annotated = annotated(classes.toPath());
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot look for the @VidocqMain class in " + classes + ": " + e, e);
        }
        if (annotated.isEmpty()) {
            return configured;
        }
        if (annotated.size() > 1) {
            throw new MojoExecutionException("Several classes are annotated @VidocqMain: " + String.join(", ", annotated)
                    + ". Set " + ApplicationMainClass.PROPERTY + " to the one to launch.");
        }
        String main = annotated.getFirst();
        info.accept("Main class: " + main + " (@VidocqMain); set " + ApplicationMainClass.PROPERTY
                + " to launch another one.");
        return main;
    }

    private static List<String> annotated(Path classes) throws IOException {
        try (Stream<Path> files = Files.walk(classes)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".class"))
                    .filter(Files::isRegularFile)
                    .map(AnnotatedMainClass::vidocqMain)
                    .flatMap(Optional::stream)
                    .sorted()
                    .toList();
        }
    }

    private static Optional<String> vidocqMain(Path file) {
        ClassModel model;
        try {
            model = ClassFile.of().parse(file);
        } catch (IOException | IllegalArgumentException _) {
            return Optional.empty();
        }
        boolean annotated = model.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(RuntimeVisibleAnnotationsAttribute::annotations)
                .stream()
                .flatMap(List::stream)
                .anyMatch(annotation -> VIDOCQ_MAIN.equals(annotation.className().stringValue()));
        return annotated ? Optional.of(model.thisClass().asInternalName().replace('/', '.')) : Optional.empty();
    }
}
