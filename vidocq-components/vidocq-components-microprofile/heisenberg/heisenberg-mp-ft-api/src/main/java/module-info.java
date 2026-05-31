/**
 * Explicit module descriptor for the MicroProfile Fault Tolerance 4.1 spec.
 *
 * <p>The official artifact {@code org.eclipse.microprofile.fault-tolerance:microprofile-fault-tolerance-api}
 * published by the Eclipse Foundation does not provide a {@code module-info.class}; jlink refuses
 * this type of module when composing a runtime image. This module-info turns it into an
 * explicit module, without modifying the spec code, while preserving exactly the same module name
 * ({@code microprofile.fault.tolerance.api}) so that any existing {@code requires} continues
 * to work.
 *
 * <p>The name {@code microprofile.fault.tolerance.api} is the one the JDK had been deriving so far
 * from the file name {@code microprofile-fault-tolerance-api-4.1.jar} using the
 * JPMS rule "strip version + replace '-' with '.'" — so no migration is required
 * in modules that already declared {@code requires microprofile.fault.tolerance.api}.
 */
module microprofile.fault.tolerance.api {
    exports org.eclipse.microprofile.faulttolerance;
    exports org.eclipse.microprofile.faulttolerance.exceptions;
}
