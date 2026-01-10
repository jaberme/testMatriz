#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <pthread.h>

static inline uint64_t now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

// sink global para evitar optimizaciones agresivas
static volatile double G_SINK = 0.0;

static void fill_f32(float *a, int len) {
    for (int i = 0; i < len; i++) {
        a[i] = (float)((i & 1023) * 0.001);
    }
}

static void stencil_rows(const float *in, float *out, int n, int r0, int r1) {
    for (int r = r0; r < r1; r++) {
        int base = r * n;
        for (int c = 1; c < n - 1; c++) {
            int i = base + c;
            out[i] = 0.2f * (in[i] + in[i - 1] + in[i + 1] + in[i - n] + in[i + n]);
        }
    }
}

static double checksum_sample(const float *a, int len) {
    double s = 0.0;
    int step = len / 8192;
    if (step < 1) step = 1;
    for (int i = 0; i < len; i += step) {
        s += a[i];
    }
    return s;
}

static jlongArray pack_result(JNIEnv *env, uint64_t timeNs, double checksum) {
    jlong out[2];
    out[0] = (jlong)timeNs;

    uint64_t bits = 0;
    memcpy(&bits, &checksum, sizeof(bits));
    out[1] = (jlong)bits;

    jlongArray arr = (*env)->NewLongArray(env, 2);
    (*env)->SetLongArrayRegion(env, arr, 0, 2, out);
    return arr;
}

// -------------------- 3) C single-thread stencil --------------------
JNIEXPORT jlongArray JNICALL
Java_es_ual_testmatriz_NativeBridge_nativeStencilSingle(JNIEnv *env, jclass clazz, jint n, jint iters) {
    (void)clazz;
    int N = (int)n;
    int I = (int)iters;
    int len = N * N;

    float *in  = (float*)malloc((size_t)len * sizeof(float));
    float *out = (float*)malloc((size_t)len * sizeof(float));
    if (!in || !out) {
        free(in); free(out);
        return pack_result(env, 0, 0.0);
    }

    fill_f32(in, len);

    // Warm-up
    stencil_rows(in, out, N, 1, N - 1);
    float *tmp = in; in = out; out = tmp;

    uint64_t t0 = now_ns();
    for (int k = 0; k < I; k++) {
        stencil_rows(in, out, N, 1, N - 1);
        float *sw = in; in = out; out = sw;
    }
    uint64_t t1 = now_ns();

    double chk = checksum_sample(in, len);
    G_SINK = chk;

    free(in);
    free(out);
    return pack_result(env, t1 - t0, chk);
}

// -------------------- 4) C pthreads stencil --------------------

typedef struct {
    pthread_mutex_t m;
    pthread_cond_t  c;
    int trip;
    int count;
    int gen;
} barrier_t;

static void barrier_init(barrier_t *b, int trip) {
    pthread_mutex_init(&b->m, NULL);
    pthread_cond_init(&b->c, NULL);
    b->trip = trip;
    b->count = 0;
    b->gen = 0;
}

static void barrier_wait(barrier_t *b) {
    pthread_mutex_lock(&b->m);
    int g = b->gen;
    b->count++;
    if (b->count == b->trip) {
        b->gen++;
        b->count = 0;
        pthread_cond_broadcast(&b->c);
    } else {
        while (g == b->gen) {
            pthread_cond_wait(&b->c, &b->m);
        }
    }
    pthread_mutex_unlock(&b->m);
}

typedef struct {
    int tid;
    int threads;
    int n;
    int iters;
    int r0, r1;

    // compartido
    float **pin;
    float **pout;
    barrier_t *bar;
} worker_ctx_t;

static void* worker_main(void *arg) {
    worker_ctx_t *w = (worker_ctx_t*)arg;

    // warm-up sincronizado
    barrier_wait(w->bar);
    const float *in = *(w->pin);
    float *out = *(w->pout);
    stencil_rows(in, out, w->n, w->r0, w->r1);
    barrier_wait(w->bar);
    if (w->tid == 0) {
        float *tmp = *(w->pin);
        *(w->pin) = *(w->pout);
        *(w->pout) = tmp;
    }
    barrier_wait(w->bar);

    for (int k = 0; k < w->iters; k++) {
        barrier_wait(w->bar);
        in  = *(w->pin);
        out = *(w->pout);

        stencil_rows(in, out, w->n, w->r0, w->r1);

        barrier_wait(w->bar);
        if (w->tid == 0) {
            float *tmp = *(w->pin);
            *(w->pin) = *(w->pout);
            *(w->pout) = tmp;
        }
        barrier_wait(w->bar);
    }

    return NULL;
}

JNIEXPORT jlongArray JNICALL
Java_es_ual_testmatriz_NativeBridge_nativeStencilPthreads(JNIEnv *env, jclass clazz, jint n, jint iters, jint threads) {
    (void)clazz;
    int N = (int)n;
    int I = (int)iters;
    int T = (int)threads;
    if (T < 1) T = 1;

    int len = N * N;
    float *a = (float*)malloc((size_t)len * sizeof(float));
    float *b = (float*)malloc((size_t)len * sizeof(float));
    if (!a || !b) {
        free(a); free(b);
        return pack_result(env, 0, 0.0);
    }
    fill_f32(a, len);

    float *in = a;
    float *out = b;

    barrier_t bar;
    barrier_init(&bar, T);

    pthread_t *ths = (pthread_t*)malloc((size_t)T * sizeof(pthread_t));
    worker_ctx_t *ctx = (worker_ctx_t*)malloc((size_t)T * sizeof(worker_ctx_t));
    if (!ths || !ctx) {
        free(ths); free(ctx);
        free(a); free(b);
        return pack_result(env, 0, 0.0);
    }

    // repartir filas interiores 1..N-2
    int rows = N - 2;
    int chunk = (rows + T - 1) / T;

    for (int t = 0; t < T; t++) {
        int r0 = 1 + t * chunk;
        int r1 = r0 + chunk;
        if (r1 > N - 1) r1 = N - 1;

        ctx[t].tid = t;
        ctx[t].threads = T;
        ctx[t].n = N;
        ctx[t].iters = I;
        ctx[t].r0 = r0;
        ctx[t].r1 = r1;
        ctx[t].pin = &in;
        ctx[t].pout = &out;
        ctx[t].bar = &bar;

        pthread_create(&ths[t], NULL, worker_main, &ctx[t]);
    }

    uint64_t t0 = now_ns();
    // Iteraciones controladas por los propios hilos (con barreras)
    // Espera a fin
    for (int t = 0; t < T; t++) {
        pthread_join(ths[t], NULL);
    }
    uint64_t t1 = now_ns();

    double chk = checksum_sample(in, len);
    G_SINK = chk;

    free(ths);
    free(ctx);
    free(a);
    free(b);

    return pack_result(env, t1 - t0, chk);
}

// -------------------- 5) “Bionic” memcpy bandwidth --------------------
// Nota: memcpy() viene de bionic (libc). Esto mide memoria/ancho de banda.
JNIEXPORT jlongArray JNICALL
Java_es_ual_testmatriz_NativeBridge_nativeBionicMemcpy(JNIEnv *env, jclass clazz, jint n, jint iters) {
    (void)clazz;
    int N = (int)n;
    int I = (int)iters;
    int len = N * N;
    size_t bytes = (size_t)len * sizeof(float);

    float *in  = (float*)malloc(bytes);
    float *out = (float*)malloc(bytes);
    if (!in || !out) {
        free(in); free(out);
        return pack_result(env, 0, 0.0);
    }
    fill_f32(in, len);

    // Warm-up
    memcpy(out, in, bytes);
    float *tmp = in; in = out; out = tmp;

    uint64_t t0 = now_ns();
    for (int k = 0; k < I; k++) {
        memcpy(out, in, bytes);
        float *sw = in; in = out; out = sw;
    }
    uint64_t t1 = now_ns();

    double chk = checksum_sample(in, len);
    G_SINK = chk;

    free(in);
    free(out);
    return pack_result(env, t1 - t0, chk);
}

