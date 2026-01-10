package es.ual.testmatriz;

public final class NativeBridge {
    static {
        System.loadLibrary("matrixbench");
    }

    // Devuelve long[2]:
    // [0] = tiempo en ns
    // [1] = checksum como Double.longBitsToDouble(...)
    public static native long[] nativeStencilSingle(int n, int iters);

    public static native long[] nativeStencilPthreads(int n, int iters, int threads);

    // “Bionic”: memcpy bandwidth + checksum
    public static native long[] nativeBionicMemcpy(int n, int iters);
}
