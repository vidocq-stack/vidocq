package io.vidocq.runtime.ext.cyrano;

import io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension;

/**
 * BCE relay local to the wrapper module to republish the CDI Cyrano extension
 * via ServiceLoader and provides JPMS.
 */
public final class CyranoBuildCompatibleExtension extends CyranoRestClientCdiExtension {
}

