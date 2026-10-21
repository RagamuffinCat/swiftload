package io.github.ragamuffincat.swiftload.gametest;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.FastBufferedInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Hooked NbtIo.readCompressed against vanilla's, rebuilt from pieces the hook doesn't touch, on every structure
// template and every gzip file of the test world. Corrupt files have to fail the same way.
class GzipFilesTest {

    @BeforeAll
    static void boot() {
        Boot.init();
    }

    // What vanilla's readCompressed(InputStream) does
    static CompoundTag vanilla(byte[] gz, NbtAccounter acc) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FastBufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(gz))))) {
            return NbtIo.read(dis, acc);
        }
    }

    interface Read {
        CompoundTag get() throws Exception;
    }

    static String outcome(Read r) {
        try {
            return "ok " + r.get().hashCode();
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    // Every structure template in the Minecraft jar
    static List<byte[]> structureTemplates() throws IOException, URISyntaxException {
        Path jar = Path.of(NbtIo.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<byte[]> out = new ArrayList<>();
        if (Files.isDirectory(jar)) {
            Path root = jar.resolve("data/minecraft/structure");
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                    out.add(Files.readAllBytes(p));
                }
            }
            return out;
        }
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : z.stream().filter(e -> e.getName().startsWith("data/minecraft/structure/") && e.getName().endsWith(".nbt")).toList()) {
                try (InputStream in = z.getInputStream(e)) {
                    out.add(in.readAllBytes());
                }
            }
        }
        return out;
    }

    static void checkEqual(String what, byte[] gz) throws IOException {
        NbtAccounter va = NbtAccounter.unlimitedHeap(), fa = NbtAccounter.unlimitedHeap();
        CompoundTag expected = vanilla(gz, va);
        CompoundTag actual = NbtIo.readCompressed(new ByteArrayInputStream(gz), fa);
        assertEquals(expected, actual, what);
        assertEquals(va.getUsage(), fa.getUsage(), what + ": accounter usage");
        String shape = Shapes.diff(expected, actual, "");
        if (shape != null) {
            throw new AssertionError(what + ": " + shape);
        }
    }

    @Test
    void structureTemplatesAreIdentical() throws Exception {
        List<byte[]> templates = structureTemplates();
        assertTrue(templates.size() > 100, "found " + templates.size() + " templates");
        long gz0 = Stats.hits(Stats.Feature.GZIP_FILES), fb0 = Stats.fallbacks(Stats.Feature.GZIP_FILES);
        long bytes = 0;
        for (int i = 0; i < templates.size(); i++) {
            checkEqual("template " + i, templates.get(i));
            bytes += templates.get(i).length;
        }
        if (Features.DEBUG_STATS) {
            assertEquals(templates.size(), Stats.hits(Stats.Feature.GZIP_FILES) - gz0, "every template inflated by libdeflate");
            assertEquals(0, Stats.fallbacks(Stats.Feature.GZIP_FILES) - fb0);
        }
        // Timing: all templates, vanilla vs hooked, alternating rounds, medians
        int rounds = 9;
        double[] v = new double[rounds], f = new double[rounds];
        long sink = 0;
        for (int w = 0; w < 3; w++) {
            for (byte[] t : templates) {
                sink += vanilla(t, NbtAccounter.unlimitedHeap()).size()
                        + NbtIo.readCompressed(new ByteArrayInputStream(t), NbtAccounter.unlimitedHeap()).size();
            }
        }
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 2; k++) {
                boolean doVanilla = (k == 0) == ((r & 1) == 0);
                long t0 = System.nanoTime();
                for (byte[] t : templates) {
                    sink += (doVanilla ? vanilla(t, NbtAccounter.unlimitedHeap())
                            : NbtIo.readCompressed(new ByteArrayInputStream(t), NbtAccounter.unlimitedHeap())).size();
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                if (doVanilla) {
                    v[r] = ms;
                } else {
                    f[r] = ms;
                }
            }
        }
        double vm = WorldHarnessTest.median(v), fm = WorldHarnessTest.median(f);
        System.out.printf(Locale.ROOT, "%n=== Swiftload gzip: %d vanilla structure templates (%d KB gzip) identical; all of them: vanilla %.1f ms, Swiftload %.1f ms -> %.2fx  [sink %d]%n",
                templates.size(), bytes / 1024, vm, fm, vm / fm, sink % 10);
    }

    @Test
    @EnabledIfSystemProperty(named = "swiftload.testworld", matches = ".+")
    void worldFilesAreIdentical() throws IOException {
        Path world = Path.of(System.getProperty("swiftload.testworld"));
        List<Path> files;
        try (Stream<Path> s = Files.walk(world)) {
            files = s.filter(p -> p.getFileName().toString().matches(".*\\.dat(_old)?$")).sorted().toList();
        }
        assertTrue(files.size() >= 10, "found " + files.size());
        for (Path p : files) {
            byte[] gz = Files.readAllBytes(p);
            checkEqual(world.relativize(p).toString(), gz);
            // And through the Path overload, which the game's storage classes call
            NbtAccounter va = NbtAccounter.uncompressedQuota(), fa = NbtAccounter.uncompressedQuota();
            assertEquals(vanilla(gz, va), NbtIo.readCompressed(p, fa), p.toString());
            assertEquals(va.getUsage(), fa.getUsage());
        }
        System.out.printf(Locale.ROOT, "%n=== Swiftload gzip: %d world files (level.dat, data/*.dat, dimension data) identical%n", files.size());
    }

    @Test
    void corruptFilesFailLikeVanilla() throws Exception {
        byte[] gz = structureTemplates().get(7);
        Random rnd = new Random(99);
        int n = 0;
        for (int cut = 0; cut < gz.length; cut += Math.max(1, gz.length / 120)) {
            byte[] t = Arrays.copyOf(gz, cut);
            assertEquals(outcome(() -> vanilla(t, NbtAccounter.unlimitedHeap())),
                    outcome(() -> NbtIo.readCompressed(new ByteArrayInputStream(t), NbtAccounter.unlimitedHeap())), "cut " + cut);
            n++;
        }
        for (int i = 0; i < 150; i++) {
            byte[] c = gz.clone();
            c[rnd.nextInt(c.length)] ^= (byte) (1 << rnd.nextInt(8));
            assertEquals(outcome(() -> vanilla(c, NbtAccounter.unlimitedHeap())),
                    outcome(() -> NbtIo.readCompressed(new ByteArrayInputStream(c), NbtAccounter.unlimitedHeap())), "flip " + i);
            n++;
        }
        byte[] notGzip = "not gzip at all".getBytes();
        assertEquals(outcome(() -> vanilla(notGzip, NbtAccounter.unlimitedHeap())),
                outcome(() -> NbtIo.readCompressed(new ByteArrayInputStream(notGzip), NbtAccounter.unlimitedHeap())));
        // A corrupt 4 GiB size claim in the trailer must not be trusted
        byte[] bigClaim = gz.clone();
        Arrays.fill(bigClaim, bigClaim.length - 4, bigClaim.length, (byte) 0xFF);
        assertEquals(outcome(() -> vanilla(bigClaim, NbtAccounter.unlimitedHeap())),
                outcome(() -> NbtIo.readCompressed(new ByteArrayInputStream(bigClaim), NbtAccounter.unlimitedHeap())));
        // A small quota trips at the same point on both sides
        assertEquals(outcome(() -> vanilla(gz, NbtAccounter.create(200))),
                outcome(() -> NbtIo.readCompressed(new ByteArrayInputStream(gz), NbtAccounter.create(200))));
        assertTrue(n > 200);
    }
}
