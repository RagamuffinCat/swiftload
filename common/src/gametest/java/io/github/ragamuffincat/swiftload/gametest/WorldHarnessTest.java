package io.github.ragamuffincat.swiftload.gametest;

import io.github.ragamuffincat.swiftload.io.LazyInflaterInputStream;
import io.github.ragamuffincat.swiftload.io.SwiftDataInputStream;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.util.FastBufferedInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every chunk of a world read through RegionFileStorage with the hooks live, against vanilla's streams and NbtIo on
// the same bytes, then both timed. Needs -Pswiftload.testworld=<world folder>.
@EnabledIfSystemProperty(named = "swiftload.testworld", matches = ".+")
class WorldHarnessTest {

    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        Boot.init();
    }

    static final LazyInflaterInputStream.VanillaWrapper VANILLA_DEFLATE = RegionFileVersion.VERSION_DEFLATE::wrap;

    @Test
    void everyChunkEqualsVanilla() throws IOException {
        Path world = Path.of(System.getProperty("swiftload.testworld"));
        List<RegionScan.Storage> storages = RegionScan.scanWorld(world);
        assertTrue(!storages.isEmpty(), "no region files under " + world);

        long hits0 = Stats.hits(Stats.Feature.FAST_NBT), inflate0 = Stats.hits(Stats.Feature.REGION_INFLATE);
        long nbtFb0 = Stats.fallbacks(Stats.Feature.FAST_NBT), inflateFb0 = Stats.fallbacks(Stats.Feature.REGION_INFLATE);
        int total = 0, deflate = 0, external = 0;
        List<byte[]> chunkPayloads = new ArrayList<>();
        StringBuilder report = new StringBuilder("\n=== Swiftload whole-world harness: ").append(world).append('\n');
        for (RegionScan.Storage st : storages) {
            int n = 0;
            RegionStorageInfo info = new RegionStorageInfo("harness", Level.OVERWORLD, st.type());
            try (RegionFileStorage storage = new RegionFileStorage(info, st.folder(), false)) {
                for (RegionScan.Chunk c : st.chunks()) {
                    CompoundTag expected = vanilla(c.version(), c.payload());
                    CompoundTag actual = storage.read(new ChunkPos(c.x(), c.z()));
                    assertNotNull(actual, c.regionFile() + " " + c.x() + "," + c.z());
                    assertEquals(expected, actual, c.regionFile() + " chunk " + c.x() + "," + c.z());
                    String shape = Shapes.diff(expected, actual, "");
                    if (shape != null) {
                        throw new AssertionError(c.regionFile() + " chunk " + c.x() + "," + c.z() + ": " + shape);
                    }
                    if (c.version() == 2) {
                        // Same bytes, same accounter state, through our stream and vanilla's
                        NbtAccounter va = NbtAccounter.unlimitedHeap(), fa = NbtAccounter.unlimitedHeap();
                        NbtIo.read(new DataInputStream(RegionFileVersion.VERSION_DEFLATE.wrap(new ByteArrayInputStream(c.payload()))), va);
                        NbtIo.read(new SwiftDataInputStream(new LazyInflaterInputStream(new ByteArrayInputStream(c.payload()),
                                LazyInflaterInputStream.Format.ZLIB, VANILLA_DEFLATE)), fa);
                        assertEquals(va.getUsage(), fa.getUsage(), c.regionFile() + " chunk " + c.x() + "," + c.z() + ": accounter usage");
                        assertEquals(va.getDepth(), fa.getDepth());
                    }
                    n++;
                    if (c.version() == 2) {
                        deflate++;
                        if (st.type().equals("region")) {
                            chunkPayloads.add(c.payload());
                        }
                    }
                }
            }
            total += n;
            external += st.external();
            report.append(String.format(Locale.ROOT, "  %-60s %6d chunks equal%s%n", world.relativize(st.folder()), n,
                    st.external() > 0 ? " (" + st.external() + " external, skipped)" : ""));
        }
        long hits = Stats.hits(Stats.Feature.FAST_NBT) - hits0, inflates = Stats.hits(Stats.Feature.REGION_INFLATE) - inflate0;
        long nbtFb = Stats.fallbacks(Stats.Feature.FAST_NBT) - nbtFb0, inflateFb = Stats.fallbacks(Stats.Feature.REGION_INFLATE) - inflateFb0;
        report.append("  accounter usage identical for every deflate chunk; dialect ")
                .append(io.github.ragamuffincat.swiftload.nbt.FastNbt.newReader(null).dialect()).append('\n');
        report.append("  backing collections identical to vanilla's (e.g. root map: ")
                .append(Shapes.mapClass(vanilla((byte) 2, chunkPayloads.get(0))).getSimpleName()).append(")%n".formatted());
        report.append(String.format(Locale.ROOT, "  total %d chunks (%d deflate): libdeflate %d, fast NBT %d, fallbacks inflate %d / nbt %d%n",
                total, deflate, inflates, hits, inflateFb, nbtFb));
        // Each deflate chunk takes the fast path twice, once in RegionFileStorage.read and once for the accounter check
        if (io.github.ragamuffincat.swiftload.Features.DEBUG_STATS) {
            assertEquals(2L * deflate, inflates, "every deflate chunk inflated by libdeflate");
            assertEquals(2L * deflate, hits, "every deflate chunk parsed by the fast reader");
            assertEquals(0, inflateFb + nbtFb, "no fallbacks on a healthy world");
        }

        timing(chunkPayloads, report);
        System.out.println(report);
    }

    // Vanilla's pipeline on the raw payload
    static CompoundTag vanilla(byte version, byte[] payload) throws IOException {
        RegionFileVersion v = RegionFileVersion.fromId(version);
        return NbtIo.read(new DataInputStream(v.wrap(new ByteArrayInputStream(payload))));
    }

    // Decompress + parse per 1,000 chunks, the IO worker's work after reading the sectors. Alternating rounds, medians.
    static void timing(List<byte[]> payloads, StringBuilder report) throws IOException {
        int rounds = Integer.getInteger("swiftload.harness.rounds", 9);
        double[] van = new double[rounds], fast = new double[rounds];
        long sink = 0;
        for (int warm = 0; warm < 3; warm++) {
            for (byte[] p : payloads) {
                sink += vanillaRead(p).size() + swiftRead(p).size();
            }
        }
        for (int r = 0; r < rounds; r++) {
            boolean vanillaFirst = (r & 1) == 0;
            for (int k = 0; k < 2; k++) {
                boolean doVanilla = (k == 0) == vanillaFirst;
                long t0 = System.nanoTime();
                for (byte[] p : payloads) {
                    sink += (doVanilla ? vanillaRead(p) : swiftRead(p)).size();
                }
                double msPer1000 = (System.nanoTime() - t0) / 1e6 / payloads.size() * 1000.0;
                if (doVanilla) {
                    van[r] = msPer1000;
                } else {
                    fast[r] = msPer1000;
                }
            }
        }
        double v = median(van), f = median(fast);
        report.append(String.format(Locale.ROOT,
                "  timing (%d chunk-storage chunks, %d rounds, median): vanilla %.1f ms / 1000 chunks, Swiftload %.1f ms / 1000 chunks -> %.2fx  [sink %d]%n",
                payloads.size(), rounds, v, f, v / f, sink % 10));
        report.append("    vanilla rounds ").append(Arrays.toString(round1(van))).append('\n');
        report.append("    swift   rounds ").append(Arrays.toString(round1(fast))).append('\n');
    }

    static CompoundTag vanillaRead(byte[] p) throws IOException {
        return NbtIo.read(new DataInputStream(new FastBufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(p)))));
    }

    static CompoundTag swiftRead(byte[] p) throws IOException {
        return NbtIo.read(new SwiftDataInputStream(new LazyInflaterInputStream(new ByteArrayInputStream(p),
                LazyInflaterInputStream.Format.ZLIB, VANILLA_DEFLATE)));
    }

    static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }

    static double[] round1(double[] a) {
        double[] r = new double[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = Math.round(a[i] * 10) / 10.0;
        }
        return r;
    }
}
