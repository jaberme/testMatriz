package es.ual.testmatriz;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView tvOut, tvN;
    private SeekBar seekN;
    private EditText etIters, etThreads;
    private Button btnRunAll;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Evita “dead code elimination” en Java
    private static volatile double JAVA_SINK = 0.0;



    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cierra el Executor para evitar fugas de hilos.
        bg.shutdownNow();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvOut = findViewById(R.id.tvOut);
        tvN = findViewById(R.id.tvN);
        seekN = findViewById(R.id.seekN);
        etIters = findViewById(R.id.etIters);
        etThreads = findViewById(R.id.etThreads);
        btnRunAll = findViewById(R.id.btnRunAll);

        // SeekBar 0..14 -> N = 256 * (idx+2) => 512..4096
        seekN.setProgress(2); // N=1024
        updateNLabel();

        seekN.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { updateNLabel(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnRunAll.setOnClickListener(v -> bg.submit(this::runAll));
    }

    private void updateNLabel() {
        int n = getN();
        tvN.setText(String.format(Locale.US, "N = %d", n));
    }

    private int getN() {
        int idx = seekN.getProgress();
        return 256 * (idx + 2); // 512..4096
    }

    private int getIters() {
        try { return Integer.parseInt(etIters.getText().toString().trim()); }
        catch (Exception e) { return 30; }
    }

    private int getThreads() {
        try { return Integer.parseInt(etThreads.getText().toString().trim()); }
        catch (Exception e) { return Runtime.getRuntime().availableProcessors(); }
    }

    private static final class Result {
        final long timeNs;
        final double checksum;
        Result(long timeNs, double checksum) { this.timeNs = timeNs; this.checksum = checksum; }
    }

    private void appendLine(String s) {
        ui.post(() -> tvOut.append(s + "\n"));
    }

    private void setText(String s) {
        ui.post(() -> tvOut.setText(s));
    }

    private void runAll() {
        int n = getN();
        int iters = getIters();
        int threads = Math.max(1, getThreads());

        setText(String.format(Locale.US,
                "Running N=%d iters=%d threads=%d\n(Consejo: prueba en RELEASE y móvil frío)\n\n",
                n, iters, threads));

        // 1) Java single
        Result r1 = javaStencilSingle(n, iters);
        appendLine(format("1) Java single", r1, iters));

        // 2) Java multithread
        Result r2 = javaStencilThreads(n, iters, threads);
        appendLine(format("2) Java threads", r2, iters));

        // 3) Native C single
        Result r3 = fromNative(NativeBridge.nativeStencilSingle(n, iters));
        appendLine(format("3) JNI C single", r3, iters));

        // 4) Native C pthreads
        Result r4 = fromNative(NativeBridge.nativeStencilPthreads(n, iters, threads));
        appendLine(format("4) JNI C pthreads", r4, iters));

        // 5) “Bionic” memcpy bandwidth
        Result r5 = fromNative(NativeBridge.nativeBionicMemcpy(n, iters));
        appendLine(format("5) JNI bionic memcpy", r5, iters));

        appendLine("\nNotas:");
        appendLine("- 1..4 ejecutan el MISMO kernel (stencil 5-puntos).");
        appendLine("- 5 mide memoria usando memcpy() (bionic), no es el mismo kernel.");
        appendLine("- Si quieres un benchmark serio: fija governor/temperatura y usa AndroidX Benchmark.");
    }

    private static String format(String name, Result r, int iters) {
        double ms = r.timeNs / 1_000_000.0;
        double msPerIter = ms / Math.max(1, iters);
        return String.format(Locale.US,
                "%s -> %.3f ms total (%.3f ms/iter), checksum=%.6f",
                name, ms, msPerIter, r.checksum);
    }

    private static Result fromNative(long[] arr) {
        long t = arr[0];
        double chk = Double.longBitsToDouble(arr[1]);
        return new Result(t, chk);
    }

    // ---------- Java kernels ----------

    private static void fill(float[] a) {
        for (int i = 0; i < a.length; i++) {
            a[i] = (float)((i & 1023) * 0.001);
        }
    }

    private static Result javaStencilSingle(int n, int iters) {
        int len = n * n;
        float[] in = new float[len];
        float[] out = new float[len];
        fill(in);

        // Warm-up ligero (JIT)
        stencilOnce(in, out, n, 1, n - 1);
        float[] tmp = in; in = out; out = tmp;

        long t0 = System.nanoTime();
        for (int k = 0; k < iters; k++) {
            stencilOnce(in, out, n, 1, n - 1);
            float[] swap = in; in = out; out = swap;
        }
        long t1 = System.nanoTime();

        double checksum = checksum(in);
        JAVA_SINK = checksum;
        return new Result(t1 - t0, checksum);
    }

    private static Result javaStencilThreads(int n, int iters, int threads) {
        int len = n * n;
        float[] in = new float[len];
        float[] out = new float[len];
        fill(in);

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            // Warm-up
            runParallel(pool, in, out, n, threads);
            float[] tmp = in;
            in = out;
            out = tmp;

            long t0 = System.nanoTime();
            for (int k = 0; k < iters; k++) {
                runParallel(pool, in, out, n, threads);
                float[] swap = in;
                in = out;
                out = swap;
            }
            long t1 = System.nanoTime();

            pool.shutdown();

            double checksum = checksum(in);
            JAVA_SINK = checksum;
            return new Result(t1 - t0, checksum);
        }finally{
            pool.shutdown();
        }
    }

    private static void runParallel(ExecutorService pool, float[] in, float[] out, int n, int threads) {
        int rows = n - 2;          // filas interiores [1..n-2]
        int chunk = (rows + threads - 1) / threads;
        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int r0 = 1 + t * chunk;
            final int r1 = Math.min(n - 1, r0 + chunk); // exclusivo
            pool.execute(() -> {
                try {
                    if (r0 < r1) {
                        stencilOnce(in, out, n, r0, r1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        try { latch.await(); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // procesa filas [rStart..rEnd) (excluye bordes)
    private static void stencilOnce(float[] in, float[] out, int n, int rStart, int rEnd) {
        for (int r = rStart; r < rEnd; r++) {
            int base = r * n;
            for (int c = 1; c < n - 1; c++) {
                int i = base + c;
                out[i] = 0.2f * (in[i] + in[i - 1] + in[i + 1] + in[i - n] + in[i + n]);
            }
        }
    }

    private static double checksum(float[] a) {
        double s = 0.0;
        // muestreo (más barato que reducir todo) pero suficiente para evitar DCE
        int step = Math.max(1, a.length / 8192);
        for (int i = 0; i < a.length; i += step) {
            s += a[i];
        }
        return s;
    }
}
