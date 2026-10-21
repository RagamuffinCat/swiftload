package io.github.ragamuffincat.swiftload.palette;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// A biome entry as Registry.holderByNameCodec() resolves it. A registered id gives the registry's own
// Holder.Reference, anything else is null. One per biome registry, thread-safe.
final class BiomeResolver implements FastPalettedCodec.EntryResolver<Holder<Biome>> {

    private final Registry<Biome> registry;
    private final ConcurrentHashMap<String, Holder<Biome>> cache = new ConcurrentHashMap<>();

    BiomeResolver(Registry<Biome> registry) {
        this.registry = registry;
    }

    @Override
    public Holder<Biome> resolve(Tag entry) {
        if (!(entry instanceof StringTag s)) {
            return null;
        }
        String id = s.value();
        Holder<Biome> h = cache.get(id);
        if (h != null) {
            return h;
        }
        Identifier location = Identifier.tryParse(id);
        if (location == null) {
            return null;
        }
        Optional<Holder.Reference<Biome>> ref = registry.get(location);
        if (ref.isEmpty()) {
            return null;
        }
        cache.putIfAbsent(id, ref.get());
        return ref.get();
    }
}
