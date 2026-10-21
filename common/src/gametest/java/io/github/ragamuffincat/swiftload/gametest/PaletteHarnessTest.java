package io.github.ragamuffincat.swiftload.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Lifecycle;
import io.github.ragamuffincat.swiftload.palette.FastPalettedCodec;
import io.github.ragamuffincat.swiftload.stats.Stats;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Every section of the test world through vanilla's container codecs and ours, then both timed
@EnabledIfSystemProperty(named = "swiftload.testworld", matches = ".+")
class PaletteHarnessTest {

    @BeforeAll
    static void boot() {
        Boot.init();
    }

    // The biome registry from the built-in worldgen bootstrap, as a vanilla world has it
    static RegistryAccess biomeAccess() {
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    static net.minecraft.world.level.chunk.Strategy<Holder<Biome>> BIOME_STRATEGY;

    @Test
    @SuppressWarnings("unchecked")
    void everySectionDecodesIdentically() throws IOException {
        Path world = Path.of(System.getProperty("swiftload.testworld"));
        // The mixin wraps whatever create() builds; the vanilla codecs stay reachable for comparison
        PalettedContainerFactory factory = PalettedContainerFactory.create(biomeAccess());
        BIOME_STRATEGY = factory.biomeStrategy();
        FastPalettedCodec<BlockState, PalettedContainer<BlockState>> fastStates =
                assertInstanceOf(FastPalettedCodec.class, factory.blockStatesContainerCodec());
        FastPalettedCodec<Holder<Biome>, PalettedContainerRO<Holder<Biome>>> fastBiomes =
                assertInstanceOf(FastPalettedCodec.class, factory.biomeContainerCodec());
        Codec<PalettedContainer<BlockState>> vanillaStates = fastStates.vanilla();
        Codec<PalettedContainerRO<Holder<Biome>>> vanillaBiomes = fastBiomes.vanilla();

        // Per chunk: block_states and biomes pairs, flattened
        List<CompoundTag[]> chunks = new ArrayList<>();
        long hits0 = Stats.hits(Stats.Feature.FAST_PALETTE), fb0 = Stats.fallbacks(Stats.Feature.FAST_PALETTE);
        int sections = 0;
        for (RegionScan.Storage st : RegionScan.scanWorld(world)) {
            if (!st.type().equals("region")) {
                continue;
            }
            for (RegionScan.Chunk c : st.chunks()) {
                CompoundTag chunk = WorldHarnessTest.vanilla(c.version(), c.payload());
                List<CompoundTag> containers = new ArrayList<>();
                for (Tag t : chunk.getListOrEmpty("sections")) {
                    CompoundTag s = (CompoundTag) t;
                    var bs = s.getCompound("block_states");
                    var bi = s.getCompound("biomes");
                    if (bs.isEmpty() || bi.isEmpty()) {
                        continue;
                    }
                    String what = c.regionFile().getFileName() + " " + c.x() + "," + c.z() + " y=" + s.getByteOr("Y", (byte) 0);
                    DataResult<PalettedContainer<BlockState>> v1 = vanillaStates.parse(NbtOps.INSTANCE, bs.get());
                    DataResult<PalettedContainer<BlockState>> f1 = fastStates.parse(NbtOps.INSTANCE, bs.get());
                    assertEquals(describe(v1.map(x -> (PalettedContainerRO<?>) x), 16), describe(f1.map(x -> (PalettedContainerRO<?>) x), 16), what + " block_states");
                    DataResult<PalettedContainerRO<Holder<Biome>>> v2 = vanillaBiomes.parse(NbtOps.INSTANCE, bi.get());
                    DataResult<PalettedContainerRO<Holder<Biome>>> f2 = fastBiomes.parse(NbtOps.INSTANCE, bi.get());
                    assertEquals(describe(v2.map(x -> (PalettedContainerRO<?>) x), 4), describe(f2.map(x -> (PalettedContainerRO<?>) x), 4), what + " biomes");
                    if (v1.isSuccess() && v2.isSuccess()) {
                        assertEquals(section(v1.getOrThrow(), v2.getOrThrow()), section(f1.getOrThrow(), f2.getOrThrow()), what + " section");
                    }
                    containers.add(bs.get());
                    containers.add(bi.get());
                    sections++;
                }
                chunks.add(containers.toArray(new CompoundTag[0]));
            }
        }
        long hits = Stats.hits(Stats.Feature.FAST_PALETTE) - hits0, fb = Stats.fallbacks(Stats.Feature.FAST_PALETTE) - fb0;
        StringBuilder report = new StringBuilder("\n=== Swiftload palette harness: ").append(world).append('\n');
        report.append(String.format(Locale.ROOT, "  %d chunks, %d sections identical (containers by identity, sections, counts); fast decodes %d, fallbacks %d%n",
                chunks.size(), sections, hits, fb));
        if (io.github.ragamuffincat.swiftload.Features.DEBUG_STATS) {
            assertEquals(2L * sections, hits, "every container decoded on the fast path");
            assertEquals(0, fb, "no fallbacks on a healthy world");
        }
        timing(chunks, vanillaStates, fastStates, vanillaBiomes, fastBiomes, report);
        System.out.println(report);
    }

    static void timing(List<CompoundTag[]> chunks, Codec<PalettedContainer<BlockState>> vs, Codec<PalettedContainer<BlockState>> fs,
                       Codec<PalettedContainerRO<Holder<Biome>>> vb, Codec<PalettedContainerRO<Holder<Biome>>> fb, StringBuilder report) {
        int rounds = 9;
        // Vanilla states, fast states, vanilla biomes, fast biomes, in us per chunk
        double[][] t = new double[4][rounds];
        long sink = 0;
        for (int w = 0; w < 3; w++) {
            for (CompoundTag[] c : chunks) {
                for (int i = 0; i < c.length; i += 2) {
                    sink += vs.parse(NbtOps.INSTANCE, c[i]).hashCode() + fs.parse(NbtOps.INSTANCE, c[i]).hashCode();
                    sink += vb.parse(NbtOps.INSTANCE, c[i + 1]).hashCode() + fb.parse(NbtOps.INSTANCE, c[i + 1]).hashCode();
                }
            }
        }
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 4; k++) {
                // Alternate the order between rounds
                int which = (r & 1) == 0 ? k : 3 - k;
                long t0 = System.nanoTime();
                for (CompoundTag[] c : chunks) {
                    for (int i = 0; i < c.length; i += 2) {
                        sink += switch (which) {
                            case 0 -> vs.parse(NbtOps.INSTANCE, c[i]).hashCode();
                            case 1 -> fs.parse(NbtOps.INSTANCE, c[i]).hashCode();
                            case 2 -> vb.parse(NbtOps.INSTANCE, c[i + 1]).hashCode();
                            default -> fb.parse(NbtOps.INSTANCE, c[i + 1]).hashCode();
                        };
                    }
                }
                t[which][r] = (System.nanoTime() - t0) / 1e3 / chunks.size();
            }
        }
        // Floor for biomes: vanilla's unpack on prebuilt PackedData, the call the fast codec ends in
        List<List<Holder<Biome>>> entries = new ArrayList<>();
        List<long[]> data = new ArrayList<>();
        for (CompoundTag[] c : chunks) {
            for (int i = 1; i < c.length; i += 2) {
                PalettedContainerRO.PackedData<Holder<Biome>> p = vb.parse(NbtOps.INSTANCE, c[i]).getOrThrow().pack(BIOME_STRATEGY);
                entries.add(p.paletteEntries());
                data.add(p.storage().map(java.util.stream.LongStream::toArray).orElse(null));
            }
        }
        double[] unpackOnly = new double[rounds];
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            for (int k = 0; k < entries.size(); k++) {
                long[] d = data.get(k);
                sink += PalettedContainer.unpack(BIOME_STRATEGY, new PalettedContainerRO.PackedData<>(entries.get(k),
                        d == null ? java.util.Optional.empty() : java.util.Optional.of(java.util.stream.LongStream.of(d)))).hashCode();
            }
            unpackOnly[r] = (System.nanoTime() - t0) / 1e3 / chunks.size();
        }
        double v1 = WorldHarnessTest.median(t[0]), f1 = WorldHarnessTest.median(t[1]), v2 = WorldHarnessTest.median(t[2]), f2 = WorldHarnessTest.median(t[3]);
        report.append(String.format(Locale.ROOT, "  biomes: vanilla unpack alone (incl. re-streaming the longs) %.1f us per chunk%n", WorldHarnessTest.median(unpackOnly)));
        report.append(String.format(Locale.ROOT, "  decode per chunk (%d chunks, %d rounds, median): block states %.1f -> %.1f us (%.2fx), biomes %.1f -> %.1f us (%.2fx)  [sink %d]%n",
                chunks.size(), rounds, v1, f1, v1 / f1, v2, f2, v2 / f2, sink % 10));
    }

    // side is 16 for block states and 4 for biomes, not taken from getAll because mods change how it iterates
    static String describe(DataResult<PalettedContainerRO<?>> r, int side) {
        StringBuilder sb = new StringBuilder(r.isSuccess() ? "success" : "error: " + r.error().get().message());
        r.resultOrPartial().ifPresent(c -> sb.append(" | ").append(fingerprint(c, side)));
        return sb.toString();
    }

    static String fingerprint(PalettedContainerRO<?> c, int side) {
        try {
            StringBuilder sb = new StringBuilder("bits=").append(c.bitsPerEntry()).append(" palette=");
            c.forEachInPalette(v -> sb.append(System.identityHashCode(v)).append(','));
            int h = 1;
            for (int y = 0; y < side; y++) {
                for (int z = 0; z < side; z++) {
                    for (int x = 0; x < side; x++) {
                        h = 31 * h + System.identityHashCode(c.get(x, y, z));
                    }
                }
            }
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            c.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return sb.append(" values=").append(h).append(" wire=").append(Arrays.hashCode(bytes)).append('/').append(bytes.length).toString();
        } catch (Throwable t) {
            return "fingerprint failed: " + t;
        }
    }

    static String section(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        try {
            LevelChunkSection s = new LevelChunkSection(states, biomes);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            s.write(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return Arrays.hashCode(bytes) + " air=" + s.hasOnlyAir() + " ticking=" + s.isRandomlyTicking()
                    + "/" + s.isRandomlyTickingBlocks() + "/" + s.isRandomlyTickingFluids();
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }
}
