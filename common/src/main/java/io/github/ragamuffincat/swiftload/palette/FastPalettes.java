package io.github.ragamuffincat.swiftload.palette;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;

// The same factory with both codecs wrapped in FastPalettedCodec
public final class FastPalettes {

    private FastPalettes() {
    }

    public static PalettedContainerFactory wrap(PalettedContainerFactory f, RegistryAccess registries) {
        if (f.blockStatesContainerCodec() instanceof FastPalettedCodec<?, ?>) {
            return f;
        }
        // The same unpack calls as vanilla's codecRW and codecRO
        PalettedContainerRO.Unpacker<BlockState, PalettedContainer<BlockState>> rwUnpacker =
                (strategy, data) -> PalettedContainer.<BlockState>unpack(strategy, data);
        FastPalettedCodec<BlockState, PalettedContainer<BlockState>> states = new FastPalettedCodec<>(
                f.blockStatesContainerCodec(), f.blockStatesStrategy(), new BlockStateResolver(), rwUnpacker);
        PalettedContainerRO.Unpacker<Holder<Biome>, PalettedContainerRO<Holder<Biome>>> roUnpacker =
                (strategy, data) -> PalettedContainer.<Holder<Biome>>unpack(strategy, data).map(e -> e);
        FastPalettedCodec<Holder<Biome>, PalettedContainerRO<Holder<Biome>>> biomes = new FastPalettedCodec<>(
                f.biomeContainerCodec(), f.biomeStrategy(), new BiomeResolver(registries.lookupOrThrow(Registries.BIOME)), roUnpacker);
        return new PalettedContainerFactory(f.blockStatesStrategy(), f.defaultBlockState(), states,
                f.biomeStrategy(), f.defaultBiome(), biomes);
    }
}
