package io.github.ragamuffincat.swiftload.palette;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import io.github.ragamuffincat.swiftload.testdata.RealChunkNbt;
import io.github.ragamuffincat.swiftload.testdata.Tags;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Against the vanilla codecs on the same tags: identical containers (palette objects by identity, every position,
// bits, network form, the built section) on success, identical errors and partial results otherwise
class FastPalettedCodecTest {

    static PalettedContainerFactory vanilla, fast;

    @BeforeAll
    static void setUp() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess access = biomeAccess();
        // No mixins in this runtime, so these are the vanilla codecs
        vanilla = PalettedContainerFactory.create(access);
        fast = FastPalettes.wrap(vanilla, access);
    }

    // Just the biome registry, with vanilla's biomes from the built-in worldgen bootstrap
    static RegistryAccess biomeAccess() {
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME).listElements()
                .forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        return new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
    }

    static String describeStates(DataResult<PalettedContainer<BlockState>> r) {
        return describe(r.map(c -> (PalettedContainerRO<?>) c));
    }

    static String describeBiomes(DataResult<PalettedContainerRO<Holder<Biome>>> r) {
        return describe(r.map(c -> (PalettedContainerRO<?>) c));
    }

    // Success, error or partial, plus a fingerprint of whatever container came out
    static String describe(DataResult<PalettedContainerRO<?>> r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.isSuccess() ? "success" : "error: " + r.error().get().message());
        r.resultOrPartial().ifPresent(c -> sb.append(" | ").append(fingerprint(c)));
        return sb.toString();
    }

    static String fingerprint(PalettedContainerRO<?> c) {
        try {
            return fingerprint0(c);
        } catch (Throwable t) {
            return "fingerprint failed: " + t;
        }
    }

    static String fingerprint0(PalettedContainerRO<?> c) {
        StringBuilder sb = new StringBuilder("bits=").append(c.bitsPerEntry()).append(" palette=");
        c.forEachInPalette(v -> sb.append(System.identityHashCode(v)).append(','));
        sb.append(" values=");
        int h = 1;
        // Vanilla's getAll visits every entry (no mods in this runtime)
        int side = countEntries(c) == 64 ? 4 : 16;
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    h = 31 * h + System.identityHashCode(c.get(x, y, z));
                }
            }
        }
        sb.append(h);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        c.write(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        sb.append(" wire=").append(Arrays.hashCode(bytes)).append('/').append(bytes.length);
        return sb.toString();
    }

    private static int countEntries(PalettedContainerRO<?> c) {
        int[] n = {0};
        c.getAll(v -> n[0]++);
        return n[0];
    }

    static void checkSection(String what, CompoundTag states, CompoundTag biomes) {
        DataResult<PalettedContainer<BlockState>> vs = vanilla.blockStatesContainerCodec().parse(NbtOps.INSTANCE, states);
        DataResult<PalettedContainer<BlockState>> fs = fast.blockStatesContainerCodec().parse(NbtOps.INSTANCE, states);
        assertEquals(describeStates(vs), describeStates(fs), what + " block_states");
        DataResult<PalettedContainerRO<Holder<Biome>>> vb = vanilla.biomeContainerCodec().parse(NbtOps.INSTANCE, biomes);
        DataResult<PalettedContainerRO<Holder<Biome>>> fb = fast.biomeContainerCodec().parse(NbtOps.INSTANCE, biomes);
        assertEquals(describeBiomes(vb), describeBiomes(fb), what + " biomes");
        if (vs.isSuccess() && vb.isSuccess()) {
            assertEquals(sectionOutcome(vs.getOrThrow(), vb.getOrThrow()), sectionOutcome(fs.getOrThrow(), fb.getOrThrow()),
                    what + " section");
        }
    }

    // Builds the section like parse does (the constructor counts blocks): its state, or the exception
    static String sectionOutcome(PalettedContainer<BlockState> states, PalettedContainerRO<Holder<Biome>> biomes) {
        try {
            return sectionState(new LevelChunkSection(states, biomes));
        } catch (Throwable t) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    static String sectionState(LevelChunkSection s) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        s.write(buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return Arrays.hashCode(bytes) + " air=" + s.hasOnlyAir() + " ticking=" + s.isRandomlyTicking()
                + "/" + s.isRandomlyTickingBlocks() + "/" + s.isRandomlyTickingFluids();
    }

    @Test
    void corpusSectionsAreIdentical() {
        RealChunkNbt gen = new RealChunkNbt(0xC0FFEE, true);
        int sections = 0;
        for (int i = 0; i < 64; i++) {
            CompoundTag chunk = (CompoundTag) Tags.of(gen.buildChunk(i % 8, i / 8));
            // Through the bytes, so the lists are what a loaded chunk holds (mixed palettes unwrapped)
            chunk = roundTrip(chunk);
            for (Tag t : chunk.getListOrEmpty("sections")) {
                CompoundTag s = (CompoundTag) t;
                checkSection("chunk " + i + " section " + s.getByteOr("Y", (byte) 0),
                        s.getCompoundOrEmpty("block_states"), s.getCompoundOrEmpty("biomes"));
                sections++;
            }
        }
        assertEquals(64 * 24, sections);
    }

    static CompoundTag roundTrip(CompoundTag t) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(Tags.bytes(t))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static CompoundTag container(Tag palette, Tag data) {
        CompoundTag c = new CompoundTag();
        if (palette != null) {
            c.put("palette", palette);
        }
        if (data != null) {
            c.put("data", data);
        }
        return c;
    }

    static ListTag list(Tag... tags) {
        ListTag l = new ListTag();
        for (Tag t : tags) {
            l.add(t);
        }
        return l;
    }

    static CompoundTag state(String id, Map<String, String> props) {
        CompoundTag c = new CompoundTag();
        c.putString("id", id);
        if (props != null) {
            CompoundTag p = new CompoundTag();
            props.forEach(p::putString);
            c.put("properties", p);
        }
        return c;
    }

    static final CompoundTag PLAINS = container(list(StringTag.valueOf("minecraft:plains")), null);

    // Storage for 4096 entries, all pointing at palette index `index`
    static LongArrayTag uniform(int index, int bits) {
        int perLong = 64 / bits;
        long word = 0;
        for (int i = 0; i < perLong; i++) {
            word |= (long) index << (i * bits);
        }
        long[] a = new long[(4096 + perLong - 1) / perLong];
        Arrays.fill(a, word);
        return new LongArrayTag(a);
    }

    @Test
    void edgeCasesLikeVanilla() {
        Map<String, CompoundTag> states = new LinkedHashMap<>();
        StringTag stone = StringTag.valueOf("minecraft:stone");
        states.put("single string", container(list(stone), null));
        states.put("two strings", container(list(stone, StringTag.valueOf("minecraft:dirt")), uniform(1, 4)));
        states.put("compound, all properties", container(list(stone, state("minecraft:oak_leaves",
                Map.of("distance", "3", "persistent", "true", "waterlogged", "false"))), uniform(1, 4)));
        states.put("compound without properties", container(list(stone, state("minecraft:oak_log", null)), uniform(1, 4)));
        states.put("partial properties", container(list(stone, state("minecraft:oak_leaves", Map.of("distance", "2"))), uniform(1, 4)));
        states.put("singleton with properties", container(list(state("minecraft:stone", Map.of("axis", "x"))), null));
        states.put("unknown property name", container(list(stone, state("minecraft:oak_log", Map.of("color", "red"))), uniform(1, 4)));
        states.put("invalid property value", container(list(stone, state("minecraft:oak_log", Map.of("axis", "w"))), uniform(1, 4)));
        CompoundTag intValue = state("minecraft:water", null);
        CompoundTag ip = new CompoundTag();
        ip.putInt("level", 3);
        intValue.put("properties", ip);
        states.put("property value not a string", container(list(stone, intValue), uniform(1, 4)));
        CompoundTag strProps = state("minecraft:oak_log", null);
        strProps.putString("properties", "axis=x");
        states.put("properties not a compound", container(list(stone, strProps), uniform(1, 4)));
        states.put("id without namespace", container(list(StringTag.valueOf("stone"), state("dirt", null)), uniform(1, 4)));
        states.put("unknown block string", container(list(stone, StringTag.valueOf("minecraft:no_such_block")), uniform(1, 4)));
        states.put("unknown block compound", container(list(stone, state("minecraft:no_such_block", Map.of("a", "b"))), uniform(1, 4)));
        states.put("invalid id", container(list(stone, StringTag.valueOf("Minecraft:Stone")), uniform(1, 4)));
        CompoundTag noId = new CompoundTag();
        noId.putString("Name", "minecraft:stone");
        states.put("compound without id", container(list(stone, noId), uniform(1, 4)));
        states.put("integer entry", container(list(stone, IntTag.valueOf(1)), uniform(1, 4)));
        CompoundTag leftover = new CompoundTag();
        leftover.putString("", "minecraft:dirt");
        leftover.putInt("x", 1);
        states.put("leftover wrapper", container(list(stone, leftover), uniform(1, 4)));
        states.put("empty palette", container(new ListTag(), null));
        states.put("missing palette", container(null, uniform(0, 4)));
        states.put("palette not a list", container(stone, null));
        states.put("missing data", container(list(stone, StringTag.valueOf("minecraft:dirt")), null));
        states.put("truncated data", container(list(stone, StringTag.valueOf("minecraft:dirt")), new LongArrayTag(new long[100])));
        states.put("empty data", container(list(stone, StringTag.valueOf("minecraft:dirt")), new LongArrayTag(new long[0])));
        states.put("data as a list of longs", container(list(stone, StringTag.valueOf("minecraft:dirt")),
                list(LongTag.valueOf(0), LongTag.valueOf(1))));
        states.put("data on a single-entry palette", container(list(stone), uniform(0, 4)));
        List<Tag> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(state("minecraft:oak_leaves", Map.of("distance", String.valueOf(1 + i % 7),
                    "persistent", i % 2 == 0 ? "true" : "false", "waterlogged", i % 3 == 0 ? "true" : "false")));
        }
        states.put("duplicate entries, 5 bits", container(list(many.toArray(new Tag[0])), uniform(19, 5)));
        for (Map.Entry<String, CompoundTag> e : states.entrySet()) {
            checkSection(e.getKey(), e.getValue(), PLAINS);
        }

        // Storage index outside the palette: both build the container, then building the section fails the same way
        checkSection("index outside palette", container(list(stone, StringTag.valueOf("minecraft:dirt")), uniform(7, 4)), PLAINS);

        Map<String, CompoundTag> biomes = new LinkedHashMap<>();
        biomes.put("two biomes", container(list(StringTag.valueOf("minecraft:plains"), StringTag.valueOf("minecraft:forest")),
                new LongArrayTag(new long[]{0x5555555555555555L})));
        biomes.put("unknown biome", container(list(StringTag.valueOf("minecraft:plains"), StringTag.valueOf("minecraft:nowhere")),
                new LongArrayTag(new long[]{0})));
        biomes.put("biome compound entry", container(list(state("minecraft:plains", null)), null));
        biomes.put("biome without namespace", container(list(StringTag.valueOf("desert")), null));
        biomes.put("biome data list", container(list(StringTag.valueOf("minecraft:plains"), StringTag.valueOf("minecraft:forest")),
                list(LongTag.valueOf(0))));
        biomes.put("biome bad length", container(list(StringTag.valueOf("minecraft:plains"), StringTag.valueOf("minecraft:forest")),
                new LongArrayTag(new long[3])));
        for (Map.Entry<String, CompoundTag> e : biomes.entrySet()) {
            checkSection(e.getKey(), container(list(stone), null), e.getValue());
        }
    }

    // Delegates to a codec and counts decode calls
    static final class Counting<A> implements Codec<A> {
        final Codec<A> inner;
        int decodes;

        Counting(Codec<A> inner) {
            this.inner = inner;
        }

        @Override
        public <I> DataResult<Pair<A, I>> decode(DynamicOps<I> ops, I input) {
            decodes++;
            return inner.decode(ops, input);
        }

        @Override
        public <I> DataResult<I> encode(A input, DynamicOps<I> ops, I prefix) {
            return inner.encode(input, ops, prefix);
        }
    }

    @Test
    void fastPathOnlyWhereExpected() {
        Counting<PalettedContainer<BlockState>> vStates = new Counting<>(vanilla.blockStatesContainerCodec());
        Counting<PalettedContainerRO<Holder<Biome>>> vBiomes = new Counting<>(vanilla.biomeContainerCodec());
        FastPalettedCodec<BlockState, PalettedContainer<BlockState>> states = new FastPalettedCodec<BlockState, PalettedContainer<BlockState>>(vStates,
                vanilla.blockStatesStrategy(), new BlockStateResolver(), (st, d) -> PalettedContainer.<BlockState>unpack(st, d));
        FastPalettedCodec<Holder<Biome>, PalettedContainerRO<Holder<Biome>>> biomes = new FastPalettedCodec<Holder<Biome>, PalettedContainerRO<Holder<Biome>>>(vBiomes,
                vanilla.biomeStrategy(), ((FastPalettedCodec<Holder<Biome>, PalettedContainerRO<Holder<Biome>>>) fast.biomeContainerCodec()).resolver(),
                (st, d) -> PalettedContainer.<Holder<Biome>>unpack(st, d).map(e -> e));
        RealChunkNbt gen = new RealChunkNbt(0xC0FFEE, true);
        for (int i = 0; i < 16; i++) {
            CompoundTag chunk = roundTrip((CompoundTag) Tags.of(gen.buildChunk(i % 8, i / 8)));
            for (Tag t : chunk.getListOrEmpty("sections")) {
                CompoundTag sec = (CompoundTag) t;
                assertTrue(states.parse(NbtOps.INSTANCE, sec.getCompoundOrEmpty("block_states")).isSuccess());
                assertTrue(biomes.parse(NbtOps.INSTANCE, sec.getCompoundOrEmpty("biomes")).isSuccess());
            }
        }
        assertEquals(0, vStates.decodes, "every corpus block_states container decoded on the fast path");
        assertEquals(0, vBiomes.decodes, "every corpus biomes container decoded on the fast path");

        StringTag stone = StringTag.valueOf("minecraft:stone");
        Object[][] cases = {
                {"invalid property value (vanilla: silent default)", container(list(stone, state("minecraft:oak_log", Map.of("axis", "w"))), uniform(1, 4)), 0},
                {"id without namespace", container(list(StringTag.valueOf("stone")), null), 0},
                {"unknown property name", container(list(stone, state("minecraft:oak_log", Map.of("color", "red"))), uniform(1, 4)), 0},
                {"truncated data (unpack's own error)", container(list(stone, StringTag.valueOf("minecraft:dirt")), new LongArrayTag(new long[100])), 0},
                {"unknown block", container(list(stone, StringTag.valueOf("minecraft:no_such_block")), uniform(1, 4)), 1},
                {"integer entry", container(list(stone, IntTag.valueOf(1)), uniform(1, 4)), 1},
                {"data as a list", container(list(stone, StringTag.valueOf("minecraft:dirt")), list(LongTag.valueOf(0))), 1},
                {"empty palette", container(new ListTag(), null), 1},
        };
        for (Object[] c : cases) {
            int before = vStates.decodes;
            states.parse(NbtOps.INSTANCE, (CompoundTag) c[1]);
            assertEquals((int) c[2], vStates.decodes - before, (String) c[0]);
        }
    }

    @Test
    void otherOpsAndInputsGoToVanilla() {
        CompoundTag c = container(list(StringTag.valueOf("minecraft:stone")), null);
        var json = vanilla.blockStatesContainerCodec().encodeStart(JsonOps.INSTANCE,
                vanilla.blockStatesContainerCodec().parse(NbtOps.INSTANCE, c).getOrThrow()).getOrThrow();
        assertEquals(describeStates(vanilla.blockStatesContainerCodec().parse(JsonOps.INSTANCE, json)),
                describeStates(fast.blockStatesContainerCodec().parse(JsonOps.INSTANCE, json)));
        assertEquals(describeStates(vanilla.blockStatesContainerCodec().parse(NbtOps.INSTANCE, StringTag.valueOf("x"))),
                describeStates(fast.blockStatesContainerCodec().parse(NbtOps.INSTANCE, StringTag.valueOf("x"))));
        // Encoding is vanilla's
        PalettedContainer<BlockState> p = vanilla.blockStatesContainerCodec().parse(NbtOps.INSTANCE, c).getOrThrow();
        assertEquals(vanilla.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, p).getOrThrow(),
                fast.blockStatesContainerCodec().encodeStart(NbtOps.INSTANCE, p).getOrThrow());
        assertTrue(FastPalettes.wrap(fast, biomeAccess()) == fast, "wrapping twice is a no-op");
    }
}
