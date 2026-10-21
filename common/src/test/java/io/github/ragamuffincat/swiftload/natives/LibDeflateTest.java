package io.github.ragamuffincat.swiftload.natives;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// libdeflate against java.util.zip both ways, we read what vanilla wrote and vanilla reads what we write
class LibDeflateTest {

    @BeforeAll
    static void configure() throws IOException {
        LibDeflate.configure(Files.createTempDirectory("swiftload-test-natives"), true, true);
        assertTrue(LibDeflate.isAvailable(), LibDeflate.status());
    }

    @Test
    void statusLine() {
        String s = LibDeflate.status();
        assertTrue(s.startsWith("libdeflate v1.26 ("), s);
        assertTrue(s.endsWith(", bridge)"), "expected the unnamed-module bridge route: " + s);
    }

    // Empty, tiny, incompressible, extremely compressible and NBT-like
    static List<byte[]> payloads() {
        Random r = new Random(1234);
        List<byte[]> l = new ArrayList<>();
        l.add(new byte[0]);
        l.add(new byte[]{42});
        l.add("minecraft:stone".getBytes());
        byte[] random = new byte[100_000];
        r.nextBytes(random);
        l.add(random);
        // 3 MiB of zeros: about 1000:1, forces retries
        l.add(new byte[3 << 20]);
        // Repeated keys, runs and noise, like chunk NBT
        byte[] nbtLike = new byte[200_000];
        for (int i = 0; i < nbtLike.length; i++) {
            nbtLike[i] = (byte) ((i % 97 < 60) ? "palette".charAt(i % 7) : ((i / 64) % 5 == 0 ? r.nextInt(256) : i % 16));
        }
        l.add(nbtLike);
        return l;
    }

    static byte[] jdkZlib(byte[] data, int level) {
        Deflater d = new Deflater(level);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    static byte[] jdkUnzlib(byte[] data, int expected) throws DataFormatException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        byte[] out = new byte[expected];
        int n = 0;
        while (!inf.finished() && n < expected) {
            n += inf.inflate(out, n, expected - n);
        }
        boolean finished = inf.finished() || expected == 0;
        inf.end();
        assertTrue(finished, "java.util.zip did not see the end of the stream");
        return out;
    }

    static byte[] jdkGzip(byte[] data) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
            g.write(data);
        }
        return bo.toByteArray();
    }

    static byte[] jdkGunzip(byte[] data) throws IOException {
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return g.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 6, 9})
    void readsZlibWrittenByJavaUtilZip(int level) throws Exception {
        for (byte[] p : payloads()) {
            byte[] packed = jdkZlib(p, level);
            assertArrayEquals(p, LibDeflate.zlibDecompress(packed, 0, packed.length, 0), "len=" + p.length);
        }
    }

    @Test
    void readsGzipWrittenByJavaUtilZip() throws Exception {
        for (byte[] p : payloads()) {
            byte[] packed = jdkGzip(p);
            assertArrayEquals(p, LibDeflate.gzipDecompress(packed, 0, packed.length), "len=" + p.length);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 6, 9, 12})
    void javaUtilZipReadsOurZlib(int level) throws Exception {
        for (byte[] p : payloads()) {
            byte[] packed = LibDeflate.zlibCompress(p, 0, p.length, level);
            assertArrayEquals(p, jdkUnzlib(packed, p.length), "len=" + p.length);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 6, 12})
    void javaUtilZipReadsOurGzip(int level) throws Exception {
        for (byte[] p : payloads()) {
            byte[] packed = LibDeflate.gzipCompress(p, 0, p.length, level);
            assertArrayEquals(p, jdkGunzip(packed), "len=" + p.length);
        }
    }

    @Test
    void level6NotWorseThanVanilla() {
        byte[] p = payloads().get(5);
        int ours = LibDeflate.zlibCompress(p, 0, p.length, 6).length;
        int vanilla = jdkZlib(p, Deflater.DEFAULT_COMPRESSION).length;
        assertTrue(ours <= vanilla * 1.02, "libdeflate 6 = " + ours + " B, java.util.zip default = " + vanilla + " B");
    }

    @Test
    void growsOutputPastTheCriticalLimit() throws Exception {
        // 20 MiB of zeros packs to about 20 KB: the first guess (16x input) is far too small and the result is
        // bigger than CRITICAL_MAX_OUTPUT, so this covers the retry and the switch to the copying path
        byte[] p = new byte[20 << 20];
        Arrays.fill(p, 0, 1000, (byte) 7);
        byte[] packed = jdkZlib(p, 6);
        assertTrue(packed.length * 16L < p.length);
        assertArrayEquals(p, LibDeflate.zlibDecompress(packed, 0, packed.length, 0));
    }

    @Test
    void largeInputTakesTheCopyingPath() throws Exception {
        byte[] p = new byte[3 << 20];
        // Incompressible, so the compressed input is bigger than CRITICAL_MAX_INPUT
        new Random(9).nextBytes(p);
        byte[] packed = jdkZlib(p, 1);
        assertTrue(packed.length > LibDeflate.CRITICAL_MAX_INPUT);
        assertArrayEquals(p, LibDeflate.zlibDecompress(packed, 0, packed.length, p.length));
        assertArrayEquals(p, jdkUnzlib(LibDeflate.zlibCompress(p, 0, p.length, 6), p.length));
    }

    @Test
    void respectsOffsetAndLength() throws Exception {
        byte[] p = payloads().get(5);
        byte[] packed = jdkZlib(p, 6);
        byte[] padded = new byte[packed.length + 300];
        new Random(5).nextBytes(padded);
        System.arraycopy(packed, 0, padded, 100, packed.length);
        assertArrayEquals(p, LibDeflate.zlibDecompress(padded, 100, packed.length, 0));
        byte[] ours = LibDeflate.zlibCompress(padded, 100, packed.length, 6);
        assertArrayEquals(Arrays.copyOfRange(padded, 100, 100 + packed.length), jdkUnzlib(ours, packed.length));
    }

    @Test
    void corruptInputThrows() {
        byte[] p = payloads().get(5);
        byte[] packed = jdkZlib(p, 6);
        byte[] corrupt = packed.clone();
        for (int i = 10; i < 40; i++) {
            corrupt[i] ^= (byte) 0x5A;
        }
        assertThrows(DataFormatException.class, () -> LibDeflate.zlibDecompress(corrupt, 0, corrupt.length, 0));
        assertThrows(DataFormatException.class, () -> LibDeflate.zlibDecompress(packed, 0, packed.length / 2, 0));
        // zlib is not gzip
        assertThrows(DataFormatException.class, () -> LibDeflate.gzipDecompress(packed, 0, packed.length));
        assertThrows(IndexOutOfBoundsException.class, () -> LibDeflate.zlibDecompress(packed, 5, packed.length, 0));
    }

    @Test
    void isSafeAcrossThreads() throws Exception {
        List<byte[]> ps = payloads();
        List<byte[]> packed = new ArrayList<>();
        for (byte[] p : ps) {
            packed.add(jdkZlib(p, 6));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int seed = t;
                fs.add(pool.submit(() -> {
                    Random r = new Random(seed);
                    for (int i = 0; i < 150; i++) {
                        int k = r.nextInt(ps.size());
                        byte[] p = ps.get(k);
                        byte[] got = LibDeflate.zlibDecompress(packed.get(k), 0, packed.get(k).length, 0);
                        if (!Arrays.equals(p, got)) {
                            throw new AssertionError("thread " + seed + " payload " + k);
                        }
                        byte[] mine = LibDeflate.zlibCompress(p, 0, p.length, 1 + r.nextInt(12));
                        if (!Arrays.equals(p, jdkUnzlib(mine, p.length))) {
                            throw new AssertionError("compress " + k);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void returnsFreshArrays() throws Exception {
        byte[] a = "first payload".getBytes(), b = "second, longer payload".getBytes();
        byte[] pa = jdkZlib(a, 6), pb = jdkZlib(b, 6);
        byte[] ra = LibDeflate.zlibDecompress(pa, 0, pa.length, 0);
        byte[] rb = LibDeflate.zlibDecompress(pb, 0, pb.length, 0);
        assertEquals(a.length, ra.length);
        // Not overwritten when the second call reuses the scratch buffer
        assertArrayEquals(a, ra);
        assertArrayEquals(b, rb);
    }

    @Test
    void rejectsBadLevels() {
        assertThrows(IllegalArgumentException.class, () -> LibDeflate.zlibCompress(new byte[1], 0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> LibDeflate.gzipCompress(new byte[1], 0, 1, 13));
    }

    @Test
    void extractedUnderTheConfiguredRoot() {
        assertTrue(LibDeflate.status().contains("extracted") || LibDeflate.status().contains("cache"));
        assertTrue(Path.of(System.getProperty("java.io.tmpdir")).toFile().exists());
    }
}
