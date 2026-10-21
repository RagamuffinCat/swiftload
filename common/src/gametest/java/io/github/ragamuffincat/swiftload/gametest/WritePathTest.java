package io.github.ragamuffincat.swiftload.gametest;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.io.LazyDeflaterOutputStream;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Everything the write path produces reads back unchanged with java.util.zip and vanilla's NbtIo, at most 2% larger
// than vanilla's output. Chunks go through RegionFile with the hook live, as the game writes them.
class WritePathTest {

    @BeforeAll
    static void boot() {
        Boot.init();
    }

    static RegionStorageInfo info() {
        return new RegionStorageInfo("write-test", Level.OVERWORLD, "chunk");
    }

    // Vanilla's region compression of the same NBT
    static byte[] vanillaDeflate(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bos)))) {
            NbtIo.write(tag, out);
        }
        return bos.toByteArray();
    }

    static byte[] swiftDeflate(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new LazyDeflaterOutputStream(bos, LazyDeflaterOutputStream.Format.ZLIB,
                o -> new BufferedOutputStream(new DeflaterOutputStream(o))))) {
            NbtIo.write(tag, out);
        }
        return bos.toByteArray();
    }

    // Chunks take whole 4 KiB sectors: 4-byte length, compression type, payload
    static long sectors(int payload) {
        return (payload + 5 + 4095) / 4096;
    }

    @Test
    @EnabledIfSystemProperty(named = "swiftload.testworld", matches = ".+")
    void worldChunksReadBackInVanilla(@TempDir Path tmp) throws IOException {
        Assumptions.assumeTrue(Features.FAST_WRITE, "needs -Dswiftload.fastWrite=true");
        Path world = Path.of(System.getProperty("swiftload.testworld"));
        long writes0 = Stats.hits(Stats.Feature.FAST_WRITE);
        long ours = 0, theirs = 0, ourSectors = 0, theirSectors = 0, regionBytes = 0;
        int n = 0;
        List<CompoundTag> chunkTags = new ArrayList<>();
        for (RegionScan.Storage st : RegionScan.scanWorld(world)) {
            Path out = Files.createDirectories(tmp.resolve(world.relativize(st.folder()).toString().replace('\\', '_').replace('/', '_')));
            Map<Path, RegionFile> files = new HashMap<>();
            Map<Path, List<RegionScan.Chunk>> byFile = new HashMap<>();
            for (RegionScan.Chunk c : st.chunks()) {
                byFile.computeIfAbsent(c.regionFile(), k -> new ArrayList<>()).add(c);
            }
            for (Map.Entry<Path, List<RegionScan.Chunk>> e : byFile.entrySet()) {
                Path target = out.resolve(e.getKey().getFileName());
                try (RegionFile region = new RegionFile(info(), target, out, RegionFileVersion.VERSION_DEFLATE, false)) {
                    for (RegionScan.Chunk c : e.getValue()) {
                        CompoundTag tag = WorldHarnessTest.vanilla(c.version(), c.payload());
                        // Through the hooked RegionFileVersion.wrap
                        try (DataOutputStream dos = region.getChunkDataOutputStream(new ChunkPos(c.x(), c.z()))) {
                            NbtIo.write(tag, dos);
                        }
                        int vanillaLength = vanillaDeflate(tag).length;
                        theirs += vanillaLength;
                        theirSectors += sectors(vanillaLength);
                        if (st.type().equals("region") && chunkTags.size() < 1500 && (c.x() + c.z()) % 7 == 0) {
                            chunkTags.add(tag);
                        }
                    }
                }
                regionBytes += Files.size(target);
                // Read back with our own parser, java.util.zip and vanilla NbtIo, so no Swiftload code is involved
                Map<Long, RegionScan.Chunk> source = new HashMap<>();
                for (RegionScan.Chunk c : e.getValue()) {
                    source.put(ChunkPos.pack(c.x(), c.z()), c);
                }
                List<RegionScan.Chunk> written = RegionScan.readFile(target);
                assertEquals(e.getValue().size(), written.size(), target.toString());
                for (RegionScan.Chunk w : written) {
                    assertEquals(2, w.version());
                    RegionScan.Chunk c = source.get(ChunkPos.pack(w.x(), w.z()));
                    CompoundTag back = NbtIo.read(new DataInputStream(new FastBufferedInputStream(new InflaterInputStream(new ByteArrayInputStream(w.payload())))));
                    assertEquals(WorldHarnessTest.vanilla(c.version(), c.payload()), back, target + " " + w.x() + "," + w.z());
                    ours += w.payload().length;
                    ourSectors += sectors(w.payload().length);
                    n++;
                }
            }
        }
        if (Features.DEBUG_STATS) {
            assertEquals(n, Stats.hits(Stats.Feature.FAST_WRITE) - writes0, "every chunk compressed by libdeflate");
        }
        double ratio = (double) ours / theirs;
        assertTrue(ratio <= 1.02, "size " + ours + " vs vanilla " + theirs);
        // What lands on disk is whole sectors
        double sectorRatio = (double) ourSectors / theirSectors;
        assertTrue(sectorRatio <= 1.02, "sectors " + ourSectors + " vs vanilla " + theirSectors);

        // Timing: NbtIo.write + compression in memory, vanilla vs Swiftload
        int rounds = 7;
        double[] v = new double[rounds], f = new double[rounds];
        long sink = 0;
        for (int w = 0; w < 2; w++) {
            for (CompoundTag t : chunkTags) {
                sink += vanillaDeflate(t).length + swiftDeflate(t).length;
            }
        }
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 2; k++) {
                boolean doVanilla = (k == 0) == ((r & 1) == 0);
                long t0 = System.nanoTime();
                for (CompoundTag t : chunkTags) {
                    sink += (doVanilla ? vanillaDeflate(t) : swiftDeflate(t)).length;
                }
                double ms = (System.nanoTime() - t0) / 1e6 / chunkTags.size() * 1000;
                if (doVanilla) {
                    v[r] = ms;
                } else {
                    f[r] = ms;
                }
            }
        }
        double vm = WorldHarnessTest.median(v), fm = WorldHarnessTest.median(f);
        System.out.printf(Locale.ROOT, "%n=== Swiftload write path: %d chunks rewritten through RegionFile, all read back by java.util.zip + vanilla NbtIo;"
                        + " compressed %,d bytes vs vanilla %,d (%.4f), in sectors %,d bytes vs vanilla %,d (%.4f), region files %,d bytes%n"
                        + "    write+compress per 1000 chunk-storage chunks (%d chunks, %d rounds, median): vanilla %.1f ms, Swiftload %.1f ms -> %.2fx  [sink %d]%n",
                n, ours, theirs, ratio, ourSectors * 4096, theirSectors * 4096, sectorRatio, regionBytes,
                chunkTags.size(), rounds, vm, fm, vm / fm, sink % 10);
    }

    @Test
    void gzipFilesReadBackInVanilla() throws Exception {
        Assumptions.assumeTrue(Features.FAST_WRITE, "needs -Dswiftload.fastWrite=true");
        List<CompoundTag> tags = new ArrayList<>();
        for (byte[] gz : GzipFilesTest.structureTemplates()) {
            tags.add(GzipFilesTest.vanilla(gz, NbtAccounter.unlimitedHeap()));
        }
        String world = System.getProperty("swiftload.testworld");
        if (world != null) {
            try (Stream<Path> s = Files.walk(Path.of(world))) {
                for (Path p : s.filter(p -> p.getFileName().toString().matches(".*\\.dat(_old)?$")).toList()) {
                    tags.add(GzipFilesTest.vanilla(Files.readAllBytes(p), NbtAccounter.unlimitedHeap()));
                }
            }
        }
        long ours = 0, theirs = 0;
        for (CompoundTag tag : tags) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            byte[] gz = bos.toByteArray();
            CompoundTag back = NbtIo.read(new DataInputStream(new FastBufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(gz)))));
            assertEquals(tag, back);
            ours += gz.length;
            ByteArrayOutputStream ref = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new java.util.zip.GZIPOutputStream(ref)))) {
                NbtIo.write(tag, out);
            }
            theirs += ref.size();
        }
        // The Path overload writes through the same method
        Path file = Files.createTempFile("swiftload", ".dat");
        NbtIo.writeCompressed(tags.get(0), file);
        assertEquals(tags.get(0), GzipFilesTest.vanilla(Files.readAllBytes(file), NbtAccounter.unlimitedHeap()));
        Files.delete(file);
        double ratio = (double) ours / theirs;
        assertTrue(ratio <= 1.02, ours + " vs " + theirs);
        System.out.printf(Locale.ROOT, "%n=== Swiftload write path: %d gzip NBT files (templates + world data) read back by GZIPInputStream + vanilla NbtIo; %,d bytes vs vanilla %,d (%.4f)%n",
                tags.size(), ours, theirs, ratio);
    }

    @Test
    void emptyAndFailedWrites() throws IOException {
        Assumptions.assumeTrue(Features.FAST_WRITE, "needs -Dswiftload.fastWrite=true");
        // The hooked stream closes its target and writes a valid empty stream
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream s = RegionFileVersion.VERSION_DEFLATE.wrap(bos);
        assertTrue(s instanceof LazyDeflaterOutputStream);
        s.close();
        assertEquals(0, new InflaterInputStream(new ByteArrayInputStream(bos.toByteArray())).readAllBytes().length);
        // Other versions stay vanilla's
        assertTrue(!(RegionFileVersion.VERSION_GZIP.wrap(new ByteArrayOutputStream()) instanceof LazyDeflaterOutputStream));
        assertTrue(!(RegionFileVersion.VERSION_NONE.wrap(new ByteArrayOutputStream()) instanceof LazyDeflaterOutputStream));
    }
}
