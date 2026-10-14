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
package io.vidocq.runtime.maven.depgen;

import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The processors of a set of bricks, each loaded from its own brick's processor path and wrapped so that what it
 * reports through its {@link Messager}, and an exception it throws, are attributed to that brick and to the element
 * concerned. javac's own diagnostics cannot do that: an element read from a class file has no source.
 */
final class AttributedProcessors implements AutoCloseable {

    /** An error or warning a brick's processor reported. {@code what} names the element, or "the jar". */
    record Report(String brick, Diagnostic.Kind kind, String what, String message) {
        boolean isError() {
            return kind == Diagnostic.Kind.ERROR;
        }
    }

    private final List<URLClassLoader> loaders = new ArrayList<>();
    private final List<Processor> processors = new ArrayList<>();
    private final List<Report> reports = new ArrayList<>();
    private String thrownBy;

    /**
     * Loads the processors of every brick: one class loader per brick over its processor path, whose parent is the
     * platform class loader. A processor class already loaded for an earlier brick is not loaded again, so a shared
     * jar does not run twice. A brick whose processors cannot be loaded gets an error report.
     */
    AttributedProcessors(List<Brick> bricks) throws IOException {
        Set<String> seen = new HashSet<>();
        try {
            for (Brick brick : bricks) {
                String name = brick.hints().brick();
                URL[] urls = new URL[brick.processorPath().size()];
                for (int i = 0; i < urls.length; i++) {
                    urls[i] = brick.processorPath().get(i).toUri().toURL();
                }
                var loader = new URLClassLoader("vidocq-depgen-" + name, urls, ClassLoader.getPlatformClassLoader());
                loaders.add(loader);
                try {
                    for (Processor processor : ServiceLoader.load(Processor.class, loader)) {
                        if (seen.add(processor.getClass().getName())) {
                            processors.add(new Attributed(name, processor));
                        }
                    }
                } catch (ServiceConfigurationError e) {
                    reports.add(new Report(name, Diagnostic.Kind.ERROR, "the jar",
                            "its processors cannot be loaded: " + e.getMessage()));
                }
            }
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** The wrapped processors, to hand to {@code CompilationTask.setProcessors}. */
    List<Processor> processors() {
        return processors;
    }

    /** What the processors reported, in order. */
    List<Report> reports() {
        return reports;
    }

    /** The brick whose processor threw, or {@code null}. */
    String thrownBy() {
        return thrownBy;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                failure = failure == null ? e : failure;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** {@code Type.member} for a member, the binary name for a type, "the jar" without an element. */
    static String describe(Elements elements, Element element) {
        return switch (element) {
            case null -> "the jar";
            case TypeElement type -> elements.getBinaryName(type).toString();
            case PackageElement pkg -> pkg.getQualifiedName().toString();
            case ModuleElement module -> module.getQualifiedName().toString();
            case ExecutableElement method -> describe(elements, method.getEnclosingElement()) + "." + method.getSimpleName();
            // A parameter is named by its method: <type>.<method>.
            case VariableElement variable when variable.getEnclosingElement() instanceof ExecutableElement method ->
                    describe(elements, method);
            default -> element.getEnclosingElement() == null ? element.getSimpleName().toString()
                    : describe(elements, element.getEnclosingElement()) + "." + element.getSimpleName();
        };
    }

    private final class Attributed implements Processor {
        private final String brick;
        private final Processor delegate;

        Attributed(String brick, Processor delegate) {
            this.brick = brick;
            this.delegate = delegate;
        }

        @Override
        public Set<String> getSupportedOptions() {
            return delegate.getSupportedOptions();
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return delegate.getSupportedAnnotationTypes();
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return delegate.getSupportedSourceVersion();
        }

        @Override
        public void init(ProcessingEnvironment env) {
            try {
                delegate.init(new Env(env, new Recording(brick, env.getMessager(), env.getElementUtils())));
            } catch (RuntimeException | Error e) {
                thrownBy = brick;
                throw e;
            }
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
            try {
                return delegate.process(annotations, round);
            } catch (RuntimeException | Error e) {
                thrownBy = brick;
                throw e;
            }
        }

        @Override
        public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation,
                                                             ExecutableElement member, String userText) {
            return delegate.getCompletions(element, annotation, member, userText);
        }
    }

    /** Records each error and warning, then forwards it so that javac still reports and logs it. */
    private final class Recording implements Messager {
        private final String brick;
        private final Messager delegate;
        private final Elements elements;

        Recording(String brick, Messager delegate, Elements elements) {
            this.brick = brick;
            this.delegate = delegate;
            this.elements = elements;
        }

        private void record(Diagnostic.Kind kind, CharSequence message, Element element) {
            Diagnostic.Kind recorded = switch (kind) {
                case ERROR -> Diagnostic.Kind.ERROR;
                case WARNING, MANDATORY_WARNING -> Diagnostic.Kind.WARNING;
                default -> null;
            };
            if (recorded != null) {
                reports.add(new Report(brick, recorded, describe(elements, element), String.valueOf(message)));
            }
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message) {
            record(kind, message, null);
            delegate.printMessage(kind, message);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element) {
            record(kind, message, element);
            delegate.printMessage(kind, message, element);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element,
                                 AnnotationMirror annotation) {
            record(kind, message, element);
            delegate.printMessage(kind, message, element, annotation);
        }

        @Override
        public void printMessage(Diagnostic.Kind kind, CharSequence message, Element element,
                                 AnnotationMirror annotation, AnnotationValue value) {
            record(kind, message, element);
            delegate.printMessage(kind, message, element, annotation, value);
        }
    }

    /** javac's environment with the recording {@link Messager}. */
    private record Env(ProcessingEnvironment delegate, Messager messager) implements ProcessingEnvironment {
        @Override
        public Map<String, String> getOptions() {
            return delegate.getOptions();
        }

        @Override
        public Messager getMessager() {
            return messager;
        }

        @Override
        public Filer getFiler() {
            return delegate.getFiler();
        }

        @Override
        public Elements getElementUtils() {
            return delegate.getElementUtils();
        }

        @Override
        public Types getTypeUtils() {
            return delegate.getTypeUtils();
        }

        @Override
        public SourceVersion getSourceVersion() {
            return delegate.getSourceVersion();
        }

        @Override
        public Locale getLocale() {
            return delegate.getLocale();
        }

        @Override
        public boolean isPreviewEnabled() {
            return delegate.isPreviewEnabled();
        }
    }
}
