package io.github.ragamuffincat.swiftload.palette;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

// Decodes a section's block_states or biomes without the DataFixerUpper codecs, then calls vanilla's
// PalettedContainer.unpack like the vanilla codec does. Only the plain case: NbtOps, a non-empty palette where every
// entry resolves, data missing or a long array. Anything else goes to the vanilla codec for the whole container, so
// partial results and errors stay vanilla's. Encoding is always vanilla's.
public final class FastPalettedCodec<T, C extends PalettedContainerRO<T>> implements Codec<C> {

    public interface EntryResolver<T> {
        // Null if the vanilla codec has to decide
        T resolve(Tag entry);
    }

    private final Codec<C> vanilla;
    private final Strategy<T> strategy;
    private final EntryResolver<T> resolver;
    private final PalettedContainerRO.Unpacker<T, C> unpacker;

    public FastPalettedCodec(Codec<C> vanilla, Strategy<T> strategy, EntryResolver<T> resolver,
                             PalettedContainerRO.Unpacker<T, C> unpacker) {
        this.vanilla = vanilla;
        this.strategy = strategy;
        this.resolver = resolver;
        this.unpacker = unpacker;
    }

    public Codec<C> vanilla() {
        return vanilla;
    }

    EntryResolver<T> resolver() {
        return resolver;
    }

    @Override
    public <I> DataResult<Pair<C, I>> decode(DynamicOps<I> ops, I input) {
        if (ops != NbtOps.INSTANCE || !(input instanceof CompoundTag tag)) {
            return vanilla.decode(ops, input);
        }
        long t0 = Features.DEBUG_STATS ? System.nanoTime() : 0L;
        // The vanilla record codec reads these two fields and ignores other keys
        if (!(tag.get("palette") instanceof ListTag list) || list.isEmpty() || list.size() > strategy.entryCount()) {
            return fallback(ops, input, "palette is not a usable list");
        }
        Tag data = tag.get("data");
        if (data != null && !(data instanceof LongArrayTag)) {
            return fallback(ops, input, "data is not a long array");
        }
        int n = list.size();
        List<T> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            T value = resolver.resolve(list.get(i));
            if (value == null) {
                return fallback(ops, input, list.get(i));
            }
            entries.add(value);
        }
        // unpack copies the stream into its own array, so the container never shares the tag's
        Optional<LongStream> storage = data == null ? Optional.empty()
                : Optional.of(LongStream.of(((LongArrayTag) data).getAsLongArray()));
        DataResult<C> result = unpacker.read(strategy, new PalettedContainerRO.PackedData<>(entries, storage));
        if (Features.DEBUG_STATS) {
            Stats.hit(Stats.Feature.FAST_PALETTE, System.nanoTime() - t0);
        }
        // Vanilla's MapCodec-backed codec pairs the value with its input
        return result.map(c -> Pair.of(c, input));
    }

    private <I> DataResult<Pair<C, I>> fallback(DynamicOps<I> ops, I input, Object why) {
        //Swiftload.LOG.info("palette fallback: {} in {}", why, input);
        if (Features.DEBUG_STATS) {
            Stats.fallback(Stats.Feature.FAST_PALETTE, why);
        }
        return vanilla.decode(ops, input);
    }

    @Override
    public <I> DataResult<I> encode(C input, DynamicOps<I> ops, I prefix) {
        return vanilla.encode(input, ops, prefix);
    }

    @Override
    public String toString() {
        return "Swiftload[" + vanilla + "]";
    }
}
