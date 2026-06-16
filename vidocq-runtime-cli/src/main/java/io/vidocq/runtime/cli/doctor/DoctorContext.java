package io.vidocq.runtime.cli.doctor;

/**
 * Pure inputs for {@link Diagnostics}. Every value is gathered by the (impure)
 * caller — JVM properties, environment, filesystem probes, ServiceLoader scan —
 * so that the diagnostics engine itself stays deterministic and unit-testable.
 *
 * @param javaFeatureVersion  running JVM feature version, e.g. {@code 25}
 * @param javaVersionString   full {@code java.version} string for display
 * @param minimumJavaVersion  minimum feature version Vidocq requires
 * @param javaHome            value of {@code JAVA_HOME}, or {@code null} if unset
 * @param javaHomeIsDirectory whether {@code javaHome} points to an existing directory
 * @param mavenWrapperPresent whether an {@code mvnw}/{@code mvnw.cmd} was found
 * @param pomPresent          whether a {@code pom.xml} exists in the working directory
 * @param vidocqProject       whether that {@code pom.xml} references the Vidocq runtime
 * @param extensionCount      number of {@code VidocqExtension} providers on the classpath
 */
public record DoctorContext(
        int javaFeatureVersion,
        String javaVersionString,
        int minimumJavaVersion,
        String javaHome,
        boolean javaHomeIsDirectory,
        boolean mavenWrapperPresent,
        boolean pomPresent,
        boolean vidocqProject,
        int extensionCount
) {
    public boolean javaHomeSet() {
        return javaHome != null && !javaHome.isBlank();
    }
}
