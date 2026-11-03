
package io.github.p4grecomp;

/**
 * Prepares a local Maxwell shader cache from the player's game.
 */
final class Importer {
    static {
        System.loadLibrary("p4gimport");
    }

    static native int run(
        int nspFd,
        String nspName,
        String keysPath,
        String titleKeysPath,
        String dataDir,
        String workDir,
        int jobs
    );

    static native int[] progress();

    static native String error();

    private Importer() {}
}
