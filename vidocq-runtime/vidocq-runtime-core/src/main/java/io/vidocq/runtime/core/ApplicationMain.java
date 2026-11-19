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
package io.vidocq.runtime.core;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The main method of an application class, chosen exactly as the Java SE launcher chooses it
 * (vidocq#88).
 *
 * <p>Since Java 25 finalized JEP 512, {@code java -m app/acme.Main} accepts {@code
 * main(String[])} or {@code main()}, static or an instance method, and never requires {@code
 * public}. An IDE on JDK 25 flags the {@code public} of a main as an unnecessary modifier, so
 * an application that follows its advice must still start under Vidocq — which reaches its
 * main by reflection, not through the launcher.
 *
 * <p>Selection mirrors {@code jdk.internal.misc.MethodFinder.findMainMethod}, which Vidocq
 * cannot call ({@code jdk.internal.misc} is not exported): a public {@code main(String[])}
 * first, then one of any access, and only then {@code main()}. Each of those three searches
 * walks the hierarchy the way {@code Class.getMethodsRecursive} does — the declared methods
 * of the class, which end the search whether or not they are usable, then the superclass,
 * then the directly implemented interfaces, whose <em>static</em> methods never count. A
 * candidate returns {@code void} and is not {@code private}; an instance candidate is run on
 * an instance built with the class' no-argument constructor, which must not be private.
 *
 * <p>The consequences are not obvious and they are all observable with {@code java} itself: a
 * {@code default void main()} on an implemented interface starts the application; a {@code
 * private main(String[])} declared in the class hides a perfectly good {@code main(String[])}
 * of its superclass, and the launch falls back to {@code main()}; a main inherited from
 * another package is run from there, which is the package the layer then has to open.
 */
final class ApplicationMain {

    private final Class<?> mainClass;
    private final Method method;
    private final boolean takesArguments;

    private ApplicationMain(Class<?> mainClass, Method method) {
        this.mainClass = mainClass;
        this.method = method;
        this.takesArguments = method.getParameterCount() == 1;
    }

    /**
     * The main method of {@code mainClass}, or an {@link IllegalStateException} naming what
     * stands in the way.
     */
    static ApplicationMain of(Class<?> mainClass) {
        var withArguments = findMethod(mainClass, true, String[].class);
        if (withArguments == null) {
            withArguments = findMethod(mainClass, false, String[].class);
        }
        if (isUsable(withArguments)) {
            return new ApplicationMain(mainClass, withArguments);
        }
        var withoutArguments = findMethod(mainClass, false);
        if (isUsable(withoutArguments)) {
            return new ApplicationMain(mainClass, withoutArguments);
        }
        // Nothing was selected. A method named main that just missed tells the author more
        // than the list of accepted shapes does, so it leads the message.
        throw new IllegalStateException(
                noCandidate(mainClass, withArguments != null ? withArguments : withoutArguments));
    }

    /**
     * The classes whose package the layer has to export and open before {@link #invoke}: the
     * application class, which is instantiated for an instance main, and the class that
     * declares the method, which is a different one — in a different module, even — as soon
     * as the main is inherited.
     */
    Set<Class<?>> typesToOpen() {
        return new LinkedHashSet<>(List.of(mainClass, method.getDeclaringClass()));
    }

    /** Runs it: with {@code args} when it takes them, on a fresh instance when it is not static. */
    void invoke(String[] args) throws ReflectiveOperationException {
        accessible(method, describe());
        Object receiver = Modifier.isStatic(method.getModifiers()) ? null : newInstance();
        if (takesArguments) {
            method.invoke(receiver, (Object) args);
        } else {
            method.invoke(receiver);
        }
    }

    /** A method the launcher would run: it exists, returns {@code void} and is not private. */
    private static boolean isUsable(Method method) {
        return method != null
                && method.getReturnType() == void.class
                && !Modifier.isPrivate(method.getModifiers());
    }

    /**
     * The launcher's {@code Class.findMethod}: the most specific of the matches the recursive
     * walk collects, by return type, the first one on a tie.
     */
    private static Method findMethod(Class<?> mainClass, boolean publicOnly, Class<?>... parameterTypes) {
        var matches = collect(mainClass, true, publicOnly, parameterTypes);
        Method mostSpecific = null;
        for (var match : matches) {
            if (mostSpecific == null
                    || (match.getReturnType() != mostSpecific.getReturnType()
                            && mostSpecific.getReturnType().isAssignableFrom(match.getReturnType()))) {
                mostSpecific = match;
            }
        }
        return mostSpecific;
    }

    /**
     * {@code Class.getMethodsRecursive}: the declared methods end the search — a match there
     * overrides anything above it, usable or not — then the superclass, then the directly
     * implemented interfaces, which never contribute a static method.
     */
    private static List<Method> collect(
            Class<?> type, boolean includeStatic, boolean publicOnly, Class<?>[] parameterTypes) {
        var declared = new ArrayList<Method>();
        for (var candidate : type.getDeclaredMethods()) {
            if (!"main".equals(candidate.getName())
                    || !Arrays.equals(candidate.getParameterTypes(), parameterTypes)
                    || (!includeStatic && Modifier.isStatic(candidate.getModifiers()))
                    || (publicOnly && !Modifier.isPublic(candidate.getModifiers()))) {
                continue;
            }
            declared.add(candidate);
        }
        if (!declared.isEmpty()) {
            return declared;
        }
        var inherited = new ArrayList<Method>();
        var superclass = type.getSuperclass();
        if (superclass != null) {
            inherited.addAll(collect(superclass, includeStatic, publicOnly, parameterTypes));
        }
        for (var implemented : type.getInterfaces()) {
            for (var fromInterface : collect(implemented, false, publicOnly, parameterTypes)) {
                merge(inherited, fromInterface);
            }
        }
        return inherited;
    }

    /**
     * {@code PublicMethods.MethodList.merge}, for the one signature at hand: a class wins over
     * an interface, a subtype wins over the type it overrides, and two unrelated interfaces
     * both stay.
     */
    private static void merge(List<Method> collected, Method method) {
        var declaring = method.getDeclaringClass();
        var iterator = collected.iterator();
        while (iterator.hasNext()) {
            var existing = iterator.next();
            if (existing.getReturnType() != method.getReturnType()) {
                continue;
            }
            var existingDeclaring = existing.getDeclaringClass();
            if (declaring.isInterface() != existingDeclaring.isInterface()) {
                if (declaring.isInterface()) {
                    return; // a class already declares it: the interface method is shadowed
                }
                iterator.remove(); // a class declares it: it knocks the interface method out
            } else if (declaring.isAssignableFrom(existingDeclaring)) {
                return; // what is already there is the same method, or overrides it
            } else if (existingDeclaring.isAssignableFrom(declaring)) {
                iterator.remove(); // the new one overrides it
            }
        }
        collected.add(method);
    }

    private Object newInstance() throws ReflectiveOperationException {
        if (Modifier.isAbstract(mainClass.getModifiers())) {
            throw new IllegalStateException(describe() + " is an instance method, and "
                    + mainClass.getName() + " is abstract: nothing can be instantiated to run it.");
        }
        Constructor<?> constructor;
        try {
            constructor = mainClass.getDeclaredConstructor();
        } catch (NoSuchMethodException none) {
            throw new IllegalStateException(describe() + " is an instance method, and "
                    + mainClass.getName() + " has no no-argument constructor to instantiate it with.", none);
        }
        if (Modifier.isPrivate(constructor.getModifiers())) {
            throw new IllegalStateException(describe() + " is an instance method, and the no-argument "
                    + "constructor of " + mainClass.getName() + " is private: the Java launcher requires "
                    + "a constructor that is not private.");
        }
        accessible(constructor, "the no-argument constructor of " + mainClass.getName());
        return constructor.newInstance();
    }

    /** Opens the member up, and says which package has to be opened when it cannot. */
    private static void accessible(AccessibleObject member, String what) {
        try {
            member.setAccessible(true);
        } catch (InaccessibleObjectException closed) {
            var owner = ((Member) member).getDeclaringClass();
            throw new IllegalStateException(what + " is not public, and package "
                    + owner.getPackageName() + " of " + owner.getModule()
                    + " is not open to " + ApplicationMain.class.getModule()
                    + ": declare it public, or open the package.", closed);
        }
    }

    private String describe() {
        return method.getDeclaringClass().getName()
                + "#main(" + (takesArguments ? "String[]" : "") + ")";
    }

    private static String noCandidate(Class<?> mainClass, Method nearMiss) {
        String miss = null;
        if (nearMiss != null) {
            var shape = "main(" + (nearMiss.getParameterCount() == 1 ? "String[]" : "") + ")";
            var named = nearMiss.getDeclaringClass().getName() + "#" + shape;
            miss = Modifier.isPrivate(nearMiss.getModifiers())
                    ? named + " is private"
                    : named + " returns " + nearMiss.getReturnType().getSimpleName() + " instead of void";
        }
        return "Cannot run application main " + mainClass.getName() + ": "
                + (miss != null ? miss + ", so it is not a main method. " : "it has no main method. ")
                + "Java 25 accepts main(String[]) or main(), static or not, in the class, a superclass "
                + "or an implemented interface, as long as it returns void and is not private.";
    }
}
