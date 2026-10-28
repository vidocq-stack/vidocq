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
package io.vidocq.heisenberg.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vidocq.heisenberg.internal.subpackage.ProtectedFallbackParent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.faulttolerance.ExecutionContext;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.FallbackHandler;
import org.eclipse.microprofile.faulttolerance.exceptions.FaultToleranceDefinitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MP FT 4.1 §6 rules enforced by {@link FallbackResolver}: fallback-method lookup (signature,
 * generics, visibility, interfaces), handler validation and the return-type checks.
 */
class FallbackResolverRulesTest {

    private static final String GUARDED = "guarded";
    private static final String RECOVER = "recover";
    private static final String BACKEND = "backend";
    private static final String VALUE = "value";

    private final FallbackResolver resolver = new FallbackResolver();

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        return type.getDeclaredMethod(name, parameters);
    }

    private void assertInvalid(Class<?> beanClass, Method guarded) {
        Fallback fallback = guarded.getAnnotation(Fallback.class);
        assertThrows(FaultToleranceDefinitionException.class,
                () -> resolver.validateDefinition(beanClass, guarded, fallback));
    }

    private void assertValid(Class<?> beanClass, Method guarded) {
        Fallback fallback = guarded.getAnnotation(Fallback.class);
        assertDoesNotThrow(() -> resolver.validateDefinition(beanClass, guarded, fallback));
    }

    private Object resolve(Object target, Method guarded, Object[] parameters) throws Exception {
        return resolver.resolve(guarded.getAnnotation(Fallback.class), target, guarded, parameters,
                new IOException(BACKEND));
    }

    // ------------------------------------------------------------------
    // Exceptions thrown by a fallback method
    // ------------------------------------------------------------------

    @Test
    void checkedExceptionOfTheFallbackMethodPropagatesAsIs() throws Exception {
        Method guarded = method(ThrowingService.class, "checked");
        ThrowingService service = new ThrowingService();

        IOException thrown = assertThrows(IOException.class, () -> resolve(service, guarded, null));

        assertSame(ThrowingService.CHECKED, thrown);
    }

    @Test
    void errorOfTheFallbackMethodPropagatesAsIs() throws Exception {
        Method guarded = method(ThrowingService.class, "error");
        ThrowingService service = new ThrowingService();

        StackOverflowError thrown = assertThrows(StackOverflowError.class, () -> resolve(service, guarded, null));

        assertSame(ThrowingService.ERROR, thrown);
    }

    @Test
    void plainThrowableOfTheFallbackMethodIsWrapped() throws Exception {
        Method guarded = method(ThrowingService.class, "throwable");
        ThrowingService service = new ThrowingService();

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> resolve(service, guarded, null));

        assertSame(ThrowingService.THROWABLE, thrown.getCause());
    }

    // ------------------------------------------------------------------
    // Fallback-method signature
    // ------------------------------------------------------------------

    @Test
    void fallbackMethodMustHaveTheSameParameterCount() throws Exception {
        assertInvalid(SignatureService.class, method(SignatureService.class, "arity", String.class));
    }

    @Test
    void fallbackMethodParametersMustAcceptTheGuardedOnes() throws Exception {
        assertInvalid(SignatureService.class, method(SignatureService.class, "parameterType", String.class));
    }

    @Test
    void fallbackMethodGenericParametersMustMatch() throws Exception {
        assertInvalid(SignatureService.class, method(SignatureService.class, "genericArgument", List.class));
    }

    @Test
    void fallbackMethodWildcardsMustMatch() throws Exception {
        assertInvalid(SignatureService.class, method(SignatureService.class, "wildcard", List.class));
    }

    @Test
    void fallbackMethodGenericRawTypesMustMatch() throws Exception {
        assertInvalid(SignatureService.class, method(SignatureService.class, "rawType", Map.class));
    }

    @Test
    void identicalGenericParametersAreAccepted() throws Exception {
        Method guarded = method(SignatureService.class, "sameGeneric", Map.class);

        Object result = resolve(new SignatureService(), guarded, new Object[]{Map.of("k", List.of(1))});

        assertEquals("same:1", result);
    }

    @Test
    void typeVariablesAreNotCheckedForEquivalence() throws Exception {
        GenericService<Integer> service = new GenericService<>();

        assertValid(GenericService.class, method(GenericService.class, "variable", Object.class));
        assertValid(GenericService.class, method(GenericService.class, "parameterized", List.class));
        assertValid(GenericService.class, method(GenericService.class, "array", Object[].class));
        assertValid(GenericService.class, method(GenericService.class, "upperBound", List.class));
        assertValid(GenericService.class, method(GenericService.class, "lowerBound", List.class));
        assertEquals("variable:7", resolve(service, method(GenericService.class, "variable", Object.class),
                new Object[]{7}));
    }

    // ------------------------------------------------------------------
    // Visibility and lookup through the hierarchy
    // ------------------------------------------------------------------

    @Test
    void abstractFallbackMethodIsNotACandidate() throws Exception {
        assertInvalid(AbstractService.class, method(AbstractService.class, GUARDED, String.class));
    }

    @Test
    void protectedFallbackMethodOfASuperclassInAnotherPackageIsReachable() throws Exception {
        Method guarded = method(ProtectedService.class, GUARDED, String.class);

        assertEquals("protected:zeta", resolve(new ProtectedService(), guarded, new Object[]{"zeta"}));
    }

    @Test
    void fallbackMethodIsFoundOnASuperInterface() throws Exception {
        Method guarded = method(NestedInterfaceService.class, GUARDED, String.class);

        assertEquals("nested:eta", resolve(new NestedInterfaceService(), guarded, new Object[]{"eta"}));
    }

    @Test
    void interfaceMethodsWithTheWrongShapeAreSkipped() throws Exception {
        assertInvalid(MismatchedInterfaceService.class,
                method(MismatchedInterfaceService.class, GUARDED, String.class));
    }

    // ------------------------------------------------------------------
    // Strategy selection
    // ------------------------------------------------------------------

    @Test
    void handlerAndFallbackMethodTogetherAreRejected() throws Exception {
        assertInvalid(StrategyService.class, method(StrategyService.class, "both"));
    }

    @Test
    void configWithoutAnyStrategyIsRejected() throws Exception {
        Method guarded = method(StrategyService.class, "both");
        FallbackConfig config = new FallbackConfig(new Class[]{Throwable.class}, new Class[0], null, null);

        assertThrows(FaultToleranceDefinitionException.class,
                () -> resolver.validateDefinition(StrategyService.class, guarded, config));
    }

    @Test
    void configFallbackMethodIsInvoked() throws Exception {
        Method guarded = method(SignatureService.class, "sameGeneric", Map.class);
        FallbackConfig config = new FallbackConfig(new Class[]{Throwable.class}, new Class[0], "sameRecover", null);

        Object result = resolver.resolve(config, new SignatureService(), guarded,
                new Object[]{Map.of("k", List.of(4))}, new IOException(BACKEND));

        assertEquals("same:4", result);
    }

    @Test
    void configFallbackHandlerIsInvoked() throws Exception {
        Method guarded = method(StrategyService.class, "both");
        FallbackConfig config = new FallbackConfig(new Class[]{Throwable.class}, new Class[0], "",
                StringHandler.class);

        Object result = resolver.resolve(config, new StrategyService(), guarded, null, new IOException(BACKEND));

        assertEquals("handled:both:0", result);
    }

    // ------------------------------------------------------------------
    // Fallback handlers
    // ------------------------------------------------------------------

    @Test
    void handlerTypeArgumentMustMatchTheReturnType() throws Exception {
        assertInvalid(HandlerService.class, method(HandlerService.class, "mismatch"));
    }

    @Test
    void handlerWithoutNoArgConstructorIsRejected() throws Exception {
        assertInvalid(HandlerService.class, method(HandlerService.class, "noDefaultConstructor"));
    }

    @Test
    void handlerInheritingFallbackHandlerIsAccepted() throws Exception {
        Method guarded = method(HandlerService.class, "inherited");

        assertEquals("handled:inherited:0", resolve(new HandlerService(), guarded, null));
    }

    @Test
    void handlerWithOtherGenericInterfacesIsAccepted() throws Exception {
        Method guarded = method(HandlerService.class, "comparable");

        assertEquals("comparable", resolve(new HandlerService(), guarded, new Object[0]));
    }

    @Test
    void voidMethodIgnoresTheHandlerResult() throws Exception {
        Method guarded = method(HandlerService.class, "nothing");

        assertNull(resolve(new HandlerService(), guarded, null));
    }

    @Test
    void nullHandlerResultIsAcceptedForAReferenceType() throws Exception {
        Method guarded = method(HandlerService.class, "nullable");

        assertNull(resolve(new HandlerService(), guarded, null));
    }

    @Test
    void nullHandlerResultIsRejectedForAPrimitiveType() throws Exception {
        Method guarded = method(HandlerService.class, "primitiveNull");
        HandlerService service = new HandlerService();

        FaultToleranceDefinitionException thrown = assertThrows(FaultToleranceDefinitionException.class,
                () -> resolve(service, guarded, null));

        assertTrue(thrown.getMessage().contains("int"), thrown::getMessage);
    }

    @Test
    void handlerResultOfTheWrongTypeIsRejected() throws Exception {
        Method guarded = method(RawHandlerService.class, "wrongType");
        RawHandlerService service = new RawHandlerService();

        FaultToleranceDefinitionException thrown = assertThrows(FaultToleranceDefinitionException.class,
                () -> resolve(service, guarded, null));

        assertTrue(thrown.getMessage().contains("java.lang.String"), thrown::getMessage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"anInt", "aLong", "aDouble", "aFloat", "aBoolean", "aShort", "aByte", "aChar"})
    void primitiveReturnTypesAcceptTheirBoxedValue(String name) throws Exception {
        Method guarded = method(RawHandlerService.class, name);

        Object result = resolve(new RawHandlerService(), guarded, null);

        assertEquals(RawHandler.valueFor(name), result);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    static class ThrowingService {
        static final IOException CHECKED = new IOException("checked");
        static final StackOverflowError ERROR = new StackOverflowError("error");
        static final Throwable THROWABLE = new Throwable("throwable");

        @Fallback(fallbackMethod = "checkedRecover")
        String checked() {
            return GUARDED;
        }

        String checkedRecover() throws IOException {
            throw CHECKED;
        }

        @Fallback(fallbackMethod = "errorRecover")
        String error() {
            return GUARDED;
        }

        String errorRecover() {
            throw ERROR;
        }

        @Fallback(fallbackMethod = "throwableRecover")
        String throwable() {
            return GUARDED;
        }

        String throwableRecover() throws Throwable {
            throw THROWABLE;
        }
    }

    static class SignatureService {
        @Fallback(fallbackMethod = "arityRecover")
        String arity(String value) {
            return value;
        }

        String arityRecover() {
            return RECOVER;
        }

        @Fallback(fallbackMethod = "parameterTypeRecover")
        String parameterType(String value) {
            return value;
        }

        String parameterTypeRecover(Integer value) {
            return RECOVER + value;
        }

        @Fallback(fallbackMethod = "genericArgumentRecover")
        String genericArgument(List<String> value) {
            return VALUE;
        }

        String genericArgumentRecover(List<Integer> value) {
            return RECOVER + value;
        }

        @Fallback(fallbackMethod = "wildcardRecover")
        String wildcard(List<? extends Number> value) {
            return VALUE;
        }

        String wildcardRecover(List<? super Integer> value) {
            return RECOVER + value;
        }

        @Fallback(fallbackMethod = "rawTypeRecover")
        String rawType(Map<String, List<Integer>> value) {
            return VALUE;
        }

        String rawTypeRecover(Map<String, Iterable<Integer>> value) {
            return RECOVER + value;
        }

        @Fallback(fallbackMethod = "sameRecover")
        String sameGeneric(Map<String, List<Integer>> value) {
            return VALUE;
        }

        String sameRecover(Map<String, List<Integer>> value) {
            return "same:" + value.get("k").getFirst();
        }
    }

    static class GenericService<T> {
        @Fallback(fallbackMethod = "variableRecover")
        String variable(T value) {
            return VALUE;
        }

        String variableRecover(T value) {
            return "variable:" + value;
        }

        @Fallback(fallbackMethod = "parameterizedRecover")
        String parameterized(List<T> value) {
            return VALUE;
        }

        String parameterizedRecover(List<T> value) {
            return RECOVER + value;
        }

        @Fallback(fallbackMethod = "arrayRecover")
        String array(T[] value) {
            return VALUE;
        }

        String arrayRecover(T[] value) {
            return RECOVER + value.length;
        }

        @Fallback(fallbackMethod = "upperBoundRecover")
        String upperBound(List<? extends T> value) {
            return VALUE;
        }

        String upperBoundRecover(List<? extends T> value) {
            return RECOVER + value;
        }

        @Fallback(fallbackMethod = "lowerBoundRecover")
        String lowerBound(List<? super T> value) {
            return VALUE;
        }

        String lowerBoundRecover(List<? super T> value) {
            return RECOVER + value;
        }
    }

    abstract static class AbstractService {
        @Fallback(fallbackMethod = RECOVER)
        String guarded(String value) {
            return value;
        }

        abstract String recover(String value);
    }

    static class ProtectedService extends ProtectedFallbackParent {
        @Fallback(fallbackMethod = "fallback")
        String guarded(String value) {
            return value;
        }
    }

    interface Unrelated {
        default String other(String value) {
            return value;
        }
    }

    interface NestedFallback {
        default String nestedRecover(String value) {
            return "nested:" + value;
        }
    }

    interface IntermediateContract extends NestedFallback {
    }

    static class NestedInterfaceService implements Unrelated, IntermediateContract {
        @Fallback(fallbackMethod = "nestedRecover")
        String guarded(String value) {
            return value;
        }
    }

    interface AbstractRecover {
        String recover(String value);
    }

    interface IncompatibleRecover {
        default String recover(Integer value) {
            return RECOVER + value;
        }
    }

    abstract static class MismatchedInterfaceService implements Unrelated, AbstractRecover, IncompatibleRecover {
        @Fallback(fallbackMethod = RECOVER)
        String guarded(String value) {
            return value;
        }
    }

    static class StrategyService {
        @Fallback(value = StringHandler.class, fallbackMethod = RECOVER)
        String both() {
            return VALUE;
        }

        String recover() {
            return RECOVER;
        }
    }

    public static class StringHandler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return "handled:" + context.getMethod().getName() + ":" + context.getParameters().length;
        }
    }

    public abstract static class BaseHandler implements FallbackHandler<String> {
    }

    public static class InheritedHandler extends BaseHandler {
        @Override
        public String handle(ExecutionContext context) {
            return "handled:" + context.getMethod().getName() + ":" + context.getParameters().length;
        }
    }

    public static class ComparableHandler implements Comparable<ComparableHandler>, FallbackHandler<String> {
        @Override
        public int compareTo(ComparableHandler other) {
            return 0;
        }

        @Override
        public String handle(ExecutionContext context) {
            return "comparable";
        }
    }

    public static class NoDefaultConstructorHandler implements FallbackHandler<String> {
        private final String prefix;

        public NoDefaultConstructorHandler(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String handle(ExecutionContext context) {
            return prefix;
        }
    }

    public static class VoidHandler implements FallbackHandler<Void> {
        @Override
        public Void handle(ExecutionContext context) {
            return null;
        }
    }

    public static class NullStringHandler implements FallbackHandler<String> {
        @Override
        public String handle(ExecutionContext context) {
            return null;
        }
    }

    public static class NullIntegerHandler implements FallbackHandler<Integer> {
        @Override
        public Integer handle(ExecutionContext context) {
            return null;
        }
    }

    static class HandlerService {
        @Fallback(StringHandler.class)
        Integer mismatch() {
            return 1;
        }

        @Fallback(NoDefaultConstructorHandler.class)
        String noDefaultConstructor() {
            return VALUE;
        }

        @Fallback(InheritedHandler.class)
        String inherited() {
            return VALUE;
        }

        @Fallback(ComparableHandler.class)
        String comparable() {
            return VALUE;
        }

        @Fallback(VoidHandler.class)
        void nothing() {
            // the fallback result of a void method is ignored
        }

        @Fallback(NullStringHandler.class)
        String nullable() {
            return VALUE;
        }

        @Fallback(NullIntegerHandler.class)
        int primitiveNull() {
            return 1;
        }
    }

    public abstract static class GenericHandler<T> implements FallbackHandler<T> {
    }

    /**
     * Declares no {@code FallbackHandler} type argument itself, so only the runtime check of the
     * returned value applies.
     */
    public static class RawHandler extends GenericHandler<Object> {

        static Object valueFor(String method) {
            return switch (method) {
                case "anInt" -> 1;
                case "aLong" -> 2L;
                case "aDouble" -> 3.0d;
                case "aFloat" -> 4.0f;
                case "aBoolean" -> Boolean.TRUE;
                case "aShort" -> (short) 5;
                case "aByte" -> (byte) 6;
                case "aChar" -> 'c';
                default -> 42;
            };
        }

        @Override
        public Object handle(ExecutionContext context) {
            return valueFor(context.getMethod().getName());
        }
    }

    static class RawHandlerService {
        @Fallback(RawHandler.class)
        String wrongType() {
            return VALUE;
        }

        @Fallback(RawHandler.class)
        int anInt() {
            return 0;
        }

        @Fallback(RawHandler.class)
        long aLong() {
            return 0;
        }

        @Fallback(RawHandler.class)
        double aDouble() {
            return 0;
        }

        @Fallback(RawHandler.class)
        float aFloat() {
            return 0;
        }

        @Fallback(RawHandler.class)
        boolean aBoolean() {
            return false;
        }

        @Fallback(RawHandler.class)
        short aShort() {
            return 0;
        }

        @Fallback(RawHandler.class)
        byte aByte() {
            return 0;
        }

        @Fallback(RawHandler.class)
        char aChar() {
            return 'a';
        }
    }
}
