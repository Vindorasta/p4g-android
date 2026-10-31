
package io.github.p4grecomp;

/**
 * Importador de Persona 4 Golden para Android.
 * A biblioteca JNI ainda precisa ser adaptada.
 */
final class Importer {
    static {
        System.loadLibrary("p5rimport");
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
