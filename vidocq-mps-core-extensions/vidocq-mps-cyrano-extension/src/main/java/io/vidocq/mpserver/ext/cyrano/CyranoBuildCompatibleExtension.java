package io.vidocq.mpserver.ext.cyrano;

import io.vidocq.cyrano.cdi.internal.CyranoRestClientCdiExtension;

/**
 * Relais BCE local au module wrapper pour republier l'extension CDI Cyrano
 * via ServiceLoader et provides JPMS.
 */
public final class CyranoBuildCompatibleExtension extends CyranoRestClientCdiExtension {
}

