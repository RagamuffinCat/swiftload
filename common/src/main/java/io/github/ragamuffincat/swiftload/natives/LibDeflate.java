package io.github.ragamuffincat.swiftload.natives;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.DataFormatException;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

// libdeflate through the FFM API, zlib and gzip. When it isn't available the callers stay on java.util.zip, and
// status() says why. If a call throws, the caller runs vanilla's code on the same input.
// Each thread gets its own native handles and scratch buffer.
public final class LibDeflate {

    // libdeflate_result
    static final int SUCCESS = 0;
    static final int INSUFFICIENT_SPACE = 3;

    // Critical calls pass heap arrays without copying but hold off GC safepoints while they run, so they're only
    // used while both buffers are small. A typical chunk takes about 20 us.
    public static final int CRITICAL_MAX_INPUT = 1 << 20;
    public static final int CRITICAL_MAX_OUTPUT = 8 << 20;
    // Bigger output is refused, the caller falls back to vanilla's streaming
    public static final long MAX_OUTPUT = 512L << 20;
    static final int SCRATCH_KEEP = 4 << 20;

    private static volatile Path cacheRoot = Path.of(System.getProperty("java.io.tmpdir"), "swiftload-natives");
    private static volatile boolean allowBridge = true;
    private static volatile boolean enabled = true;
    private static volatile boolean configured;

    private LibDeflate() {
    }

    // Has to run before anything else here; later calls are ignored. A null root keeps the temp dir.
    public static void configure(Path extractionRoot, boolean useBridge, boolean useNatives) {
        if (configured) {
            return;
        }
        if (extractionRoot != null) {
            cacheRoot = extractionRoot;
        }
        allowBridge = useBridge;
        enabled = useNatives;
        configured = true;
    }

    public static boolean isAvailable() {
        return Native.AVAILABLE;
    }

    // For the startup line, e.g. "libdeflate v1.26 (windows-x86_64, extracted, bridge)"
    public static String status() {
        return Native.STATUS;
    }

    public static byte[] zlibDecompress(byte[] src, int off, int len, int sizeHint) throws DataFormatException {
        return decompress(false, src, off, len, sizeHint);
    }

    public static byte[] gzipDecompress(byte[] src, int off, int len) throws DataFormatException {
        long hint = 0;
        if (len >= 18) {
            // ISIZE trailer: uncompressed size mod 2^32
            int e = off + len;
            hint = (src[e - 4] & 0xFFL) | (src[e - 3] & 0xFFL) << 8 | (src[e - 2] & 0xFFL) << 16 | (src[e - 1] & 0xFFL) << 24;
            // DEFLATE can't expand more than about 1032:1, a bigger claim is corrupt
            if (hint > 1032L * len + 1024) {
                hint = 0;
            }
        }
        return decompress(true, src, off, len, hint);
    }

    // Levels 1..12 on libdeflate's scale
    public static byte[] zlibCompress(byte[] src, int off, int len, int level) {
        return compress(false, src, off, len, level);
    }

    public static byte[] gzipCompress(byte[] src, int off, int len, int level) {
        return compress(true, src, off, len, level);
    }

    private static byte[] decompress(boolean gzip, byte[] src, int off, int len, long sizeHint)
            throws DataFormatException {
        Objects.checkFromIndexSize(off, len, src.length);
        Native.require();
        State s = State.get();
        long cap = Math.min(MAX_OUTPUT, Math.max(64 * 1024L, Math.max(sizeHint, 16L * len)));
        try {
            while (true) {
                int rc;
                if (len <= CRITICAL_MAX_INPUT && cap <= CRITICAL_MAX_OUTPUT) {
                    byte[] out = s.scratch((int) cap);
                    MethodHandle h = gzip ? Native.GZIP_DECOMPRESS_CRIT : Native.ZLIB_DECOMPRESS_CRIT;
                    rc = (int) h.invokeExact(s.decompressor, MemorySegment.ofArray(src).asSlice(off, len), (long) len,
                            MemorySegment.ofArray(out), cap, s.actualSeg);
                    if (rc == SUCCESS) {
                        // TODO hand out the scratch buffer itself and skip this copy. The reader would have to be done
                        //  with it before the next call on this thread
                        return Arrays.copyOf(out, (int) s.actual[0]);
                    }
                } else {
                    try (Arena arena = Arena.ofConfined()) {
                        MemorySegment in = arena.allocate(len);
                        MemorySegment.copy(src, off, in, ValueLayout.JAVA_BYTE, 0, len);
                        MemorySegment out = arena.allocate(cap);
                        MemorySegment actual = arena.allocate(JAVA_LONG);
                        MethodHandle h = gzip ? Native.GZIP_DECOMPRESS : Native.ZLIB_DECOMPRESS;
                        rc = (int) h.invokeExact(s.decompressor, in, (long) len, out, cap, actual);
                        if (rc == SUCCESS) {
                            long n = actual.get(JAVA_LONG, 0);
                            if (n > Integer.MAX_VALUE - 16) {
                                throw new DataFormatException("decompressed size too large: " + n);
                            }
                            return out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
                        }
                    }
                }
                if (rc != INSUFFICIENT_SPACE) {
                    throw new DataFormatException("libdeflate: invalid " + (gzip ? "gzip" : "zlib") + " data (rc=" + rc + ")");
                }
                if (cap >= MAX_OUTPUT) {
                    throw new DataFormatException("decompressed size exceeds " + MAX_OUTPUT + " bytes");
                }
                cap = Math.min(MAX_OUTPUT, cap * 2);
            }
        } catch (DataFormatException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("libdeflate call failed", t);
        } finally {
            s.trimScratch();
        }
    }

    private static byte[] compress(boolean gzip, byte[] src, int off, int len, int level) {
        Objects.checkFromIndexSize(off, len, src.length);
        if (level < 1 || level > 12) {
            throw new IllegalArgumentException("level must be 1..12: " + level);
        }
        Native.require();
        State s = State.get();
        MemorySegment c = s.compressor(level);
        try {
            long bound = (long) (gzip ? Native.GZIP_COMPRESS_BOUND : Native.ZLIB_COMPRESS_BOUND).invokeExact(c, (long) len);
            if (len <= CRITICAL_MAX_INPUT && bound <= CRITICAL_MAX_OUTPUT) {
                byte[] out = s.scratch((int) bound);
                MethodHandle h = gzip ? Native.GZIP_COMPRESS_CRIT : Native.ZLIB_COMPRESS_CRIT;
                long n = (long) h.invokeExact(c, MemorySegment.ofArray(src).asSlice(off, len), (long) len,
                        MemorySegment.ofArray(out), bound);
                if (n <= 0) {
                    throw new IllegalStateException("libdeflate compress returned " + n);
                }
                return Arrays.copyOf(out, (int) n);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment in = arena.allocate(len);
                MemorySegment.copy(src, off, in, ValueLayout.JAVA_BYTE, 0, len);
                MemorySegment out = arena.allocate(bound);
                MethodHandle h = gzip ? Native.GZIP_COMPRESS : Native.ZLIB_COMPRESS;
                long n = (long) h.invokeExact(c, in, (long) len, out, bound);
                if (n <= 0) {
                    throw new IllegalStateException("libdeflate compress returned " + n);
                }
                return out.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("libdeflate call failed", t);
        } finally {
            s.trimScratch();
        }
    }

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    private static final class State {
        final MemorySegment decompressor;
        // Indexed by level
        final MemorySegment[] compressors = new MemorySegment[13];
        final long[] actual = new long[1];
        final MemorySegment actualSeg = MemorySegment.ofArray(actual);
        private byte[] scratch = new byte[256 * 1024];

        static State get() {
            State s = STATE.get();
            if (s == null) {
                s = new State();
                STATE.set(s);
            }
            return s;
        }

        private State() {
            try {
                decompressor = (MemorySegment) Native.ALLOC_DECOMPRESSOR.invokeExact();
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            if (decompressor.equals(MemorySegment.NULL)) {
                throw new OutOfMemoryError("libdeflate_alloc_decompressor");
            }
            CLEANER.register(this, new Free(decompressor, compressors));
        }

        MemorySegment compressor(int level) {
            MemorySegment c = compressors[level];
            if (c == null) {
                try {
                    c = (MemorySegment) Native.ALLOC_COMPRESSOR.invokeExact(level);
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
                if (c.equals(MemorySegment.NULL)) {
                    throw new OutOfMemoryError("libdeflate_alloc_compressor");
                }
                compressors[level] = c;
            }
            return c;
        }

        byte[] scratch(int min) {
            if (scratch.length < min) {
                long grown = Math.max(min, Math.min(Integer.MAX_VALUE - 16L, scratch.length * 2L));
                scratch = new byte[(int) grown];
            }
            return scratch;
        }

        void trimScratch() {
            if (scratch.length > SCRATCH_KEEP) {
                scratch = new byte[256 * 1024];
            }
        }
    }

    // Frees a thread's handles once its State is gone. Must not reference the State itself.
    private record Free(MemorySegment decompressor, MemorySegment[] compressors) implements Runnable {

        @Override
        public void run() {
            try {
                Native.FREE_DECOMPRESSOR.invokeExact(decompressor);
                for (MemorySegment c : compressors) {
                    if (c != null) {
                        Native.FREE_COMPRESSOR.invokeExact(c);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // Bound on first use, after configure()
    private static final class Native {
        static final boolean AVAILABLE;
        static final String STATUS;
        static final MethodHandle ALLOC_DECOMPRESSOR, FREE_DECOMPRESSOR, ALLOC_COMPRESSOR, FREE_COMPRESSOR,
                ZLIB_DECOMPRESS, ZLIB_DECOMPRESS_CRIT, GZIP_DECOMPRESS, GZIP_DECOMPRESS_CRIT,
                ZLIB_COMPRESS, ZLIB_COMPRESS_CRIT, GZIP_COMPRESS, GZIP_COMPRESS_CRIT,
                ZLIB_COMPRESS_BOUND, GZIP_COMPRESS_BOUND;

        static {
            MethodHandle[] h = null;
            String status;
            boolean ok = false;
            if (!enabled || "false".equalsIgnoreCase(System.getProperty("swiftload.natives"))) {
                status = "libdeflate disabled (natives=false)";
            } else {
                NativeLibrary.Located located = NativeLibrary.locate(cacheRoot, LibDeflate.class.getClassLoader());
                if (located instanceof NativeLibrary.Found found) {
                    try {
                        NativeBinder.Bound bound = NativeBinder.bind(found.path(), specs(), allowBridge
                                && !"false".equalsIgnoreCase(System.getProperty("swiftload.natives.bridge")));
                        h = bound.handles();
                        selfTest(h);
                        ok = true;
                        status = "libdeflate " + found.version() + " (" + found.platform() + ", " + found.source()
                                + ", " + bound.route().name().toLowerCase() + ")";
                    } catch (Throwable t) {
                        h = null;
                        status = "libdeflate failed to load: " + t;
                    }
                } else {
                    status = "libdeflate unavailable: " + ((NativeLibrary.Unavailable) located).reason();
                }
            }
            AVAILABLE = ok;
            STATUS = status;
            MethodHandle[] x = h != null ? h : new MethodHandle[14];
            ALLOC_DECOMPRESSOR = x[0];
            FREE_DECOMPRESSOR = x[1];
            ALLOC_COMPRESSOR = x[2];
            FREE_COMPRESSOR = x[3];
            ZLIB_DECOMPRESS = x[4];
            ZLIB_DECOMPRESS_CRIT = x[5];
            GZIP_DECOMPRESS = x[6];
            GZIP_DECOMPRESS_CRIT = x[7];
            ZLIB_COMPRESS = x[8];
            ZLIB_COMPRESS_CRIT = x[9];
            GZIP_COMPRESS = x[10];
            GZIP_COMPRESS_CRIT = x[11];
            ZLIB_COMPRESS_BOUND = x[12];
            GZIP_COMPRESS_BOUND = x[13];
        }

        static void require() {
            if (!AVAILABLE) {
                throw new IllegalStateException(STATUS);
            }
        }

        // Name, descriptor, critical; in the order of the handles above
        private static Object[] specs() {
            FunctionDescriptor decompress = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS);
            FunctionDescriptor compress = FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
            FunctionDescriptor bound = FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG);
            return new Object[]{
                    "libdeflate_alloc_decompressor", FunctionDescriptor.of(ADDRESS), false,
                    "libdeflate_free_decompressor", FunctionDescriptor.ofVoid(ADDRESS), false,
                    "libdeflate_alloc_compressor", FunctionDescriptor.of(ADDRESS, JAVA_INT), false,
                    "libdeflate_free_compressor", FunctionDescriptor.ofVoid(ADDRESS), false,
                    "libdeflate_zlib_decompress", decompress, false,
                    "libdeflate_zlib_decompress", decompress, true,
                    "libdeflate_gzip_decompress", decompress, false,
                    "libdeflate_gzip_decompress", decompress, true,
                    "libdeflate_zlib_compress", compress, false,
                    "libdeflate_zlib_compress", compress, true,
                    "libdeflate_gzip_compress", compress, false,
                    "libdeflate_gzip_compress", compress, true,
                    "libdeflate_zlib_compress_bound", bound, false,
                    "libdeflate_gzip_compress_bound", bound, false,
            };
        }

        // A round trip through the handles the hot paths use, so a broken build fails at startup and not mid-game
        private static void selfTest(MethodHandle[] h) throws Throwable {
            byte[] data = "swiftload self-test: the quick brown fox jumps over the lazy dog 0123456789".repeat(40)
                    .getBytes(StandardCharsets.US_ASCII);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment c = (MemorySegment) h[2].invokeExact(6);
                MemorySegment d = (MemorySegment) h[0].invokeExact();
                if (c.equals(MemorySegment.NULL) || d.equals(MemorySegment.NULL)) {
                    throw new IllegalStateException("alloc failed");
                }
                try {
                    // 0 = zlib, 1 = gzip
                    for (int fmt = 0; fmt < 2; fmt++) {
                        MemorySegment in = arena.allocate(data.length);
                        MemorySegment.copy(data, 0, in, ValueLayout.JAVA_BYTE, 0, data.length);
                        long bnd = (long) h[12 + fmt].invokeExact(c, (long) data.length);
                        MemorySegment packed = arena.allocate(bnd);
                        long n = (long) h[8 + 2 * fmt].invokeExact(c, in, (long) data.length, packed, bnd);
                        if (n <= 0) {
                            throw new IllegalStateException("self-test compress failed");
                        }
                        byte[] packedHeap = packed.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE);
                        byte[] back = new byte[data.length];
                        long[] act = new long[1];
                        int rc = (int) h[5 + 2 * fmt].invokeExact(d, MemorySegment.ofArray(packedHeap), n,
                                MemorySegment.ofArray(back), (long) back.length, MemorySegment.ofArray(act));
                        if (rc != SUCCESS || act[0] != data.length || !Arrays.equals(back, data)) {
                            throw new IllegalStateException("self-test round trip mismatch (rc=" + rc + ")");
                        }
                    }
                } finally {
                    h[3].invokeExact(c);
                    h[1].invokeExact(d);
                }
            }
        }
    }
}
