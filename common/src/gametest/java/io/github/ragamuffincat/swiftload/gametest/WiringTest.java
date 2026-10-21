package io.github.ragamuffincat.swiftload.gametest;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.io.SwiftDataInputStream;
import io.github.ragamuffincat.swiftload.mixin.NbtAccounterAccessor;
import io.github.ragamuffincat.swiftload.nbt.FastNbt;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WiringTest {

    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        Boot.init();
    }

    // Not a static field, Level must not initialize before the registries are bootstrapped
    static RegionStorageInfo info() {
        return new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
    }

    static CompoundTag sample() {
        CompoundTag t = new CompoundTag();
        t.putInt("DataVersion", 5023);
        ListTag l = new ListTag();
        l.add(StringTag.valueOf("minecraft:stone"));
        t.put("palette", l);
        t.putLongArray("data", new long[]{1, 2, 3});
        return t;
    }

    @Test
    void featuresAreOn() {
        assertTrue(Features.REGION_INFLATE, "natives must load in the test JVM");
        assertTrue(Features.FAST_NBT);
        assertTrue(FastNbt.enabled(), "self-test must pass against the game's NbtIo");
        assertInstanceOf(NbtAccounterAccessor.class, NbtAccounter.unlimitedHeap());
    }

    @Test
    void regionReadTakesFastPath(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Features.DEBUG_STATS, "checks hit counts: needs debugStats");
        Path file = dir.resolve("r.0.0.mca");
        ChunkPos pos = new ChunkPos(3, 4);
        CompoundTag tag = sample();
        try (RegionFile region = new RegionFile(info(), file, dir, RegionFileVersion.VERSION_DEFLATE, false)) {
            try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
                NbtIo.write(tag, out);
            }
            long hits = Stats.hits(Stats.Feature.FAST_NBT);
            try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                SwiftDataInputStream s = assertInstanceOf(SwiftDataInputStream.class, in);
                assertTrue(s.source().untouched(), "nothing inflated before the first read (lazy)");
                assertEquals(tag, NbtIo.read(in));
                assertNull(s.source().fallbackReason());
            }
            assertEquals(hits + 1, Stats.hits(Stats.Feature.FAST_NBT));
        }
    }

    @Test
    void corruptChunkFailsLikeVanilla(@TempDir Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(Features.DEBUG_STATS, "checks hit counts: needs debugStats");
        Path file = dir.resolve("r.0.0.mca");
        ChunkPos pos = new ChunkPos(0, 0);
        try (RegionFile region = new RegionFile(info(), file, dir, RegionFileVersion.VERSION_DEFLATE, false)) {
            try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
                NbtIo.write(sample(), out);
            }
        }
        // Damage the compressed stream in sector 2, after the 5-byte chunk header
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            ch.read(b, 8192 + 12);
            b.flip();
            byte v = b.get();
            ch.write(java.nio.ByteBuffer.wrap(new byte[]{(byte) (v ^ 0x5A)}), 8192 + 12);
        }
        long fallbacks = Stats.fallbacks(Stats.Feature.REGION_INFLATE);
        try (RegionFile region = new RegionFile(info(), file, dir, RegionFileVersion.VERSION_DEFLATE, false);
             DataInputStream in = region.getChunkDataInputStream(pos)) {
            SwiftDataInputStream s = assertInstanceOf(SwiftDataInputStream.class, in);
            String ours = outcome(() -> NbtIo.read(in));
            assertFalse(s.source().fallbackReason() == null, "libdeflate rejected it, vanilla's stream took over");
            // Vanilla on the same damaged bytes
            byte[] all = java.nio.file.Files.readAllBytes(file);
            int length = java.nio.ByteBuffer.wrap(all, 8192, 4).getInt();
            java.io.InputStream raw = new java.io.ByteArrayInputStream(all, 8192 + 5, length - 1);
            String vanilla = outcome(() -> NbtIo.read(new DataInputStream(RegionFileVersion.VERSION_DEFLATE.wrap(raw))));
            assertEquals(vanilla, ours);
        }
        assertEquals(fallbacks + 1, Stats.fallbacks(Stats.Feature.REGION_INFLATE));
    }

    interface Read {
        CompoundTag get() throws Exception;
    }

    static String outcome(Read r) {
        try {
            return "ok " + r.get();
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    @Test
    void otherCompressionTypesStayVanilla(@TempDir Path dir) throws IOException {
        for (RegionFileVersion v : new RegionFileVersion[]{RegionFileVersion.VERSION_NONE, RegionFileVersion.VERSION_LZ4,
                RegionFileVersion.VERSION_GZIP}) {
            Path file = dir.resolve("r." + v.getId() + ".0.mca");
            ChunkPos pos = new ChunkPos(v.getId() * 32, 0);
            try (RegionFile region = new RegionFile(info(), file, dir, v, false)) {
                try (DataOutputStream out = region.getChunkDataOutputStream(pos)) {
                    NbtIo.write(sample(), out);
                }
                try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                    assertFalse(in instanceof SwiftDataInputStream, v.getId() + " must use vanilla's stream");
                    assertEquals(sample(), NbtIo.read(in));
                }
            }
        }
    }
}
