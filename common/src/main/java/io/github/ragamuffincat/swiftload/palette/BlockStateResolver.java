package io.github.ragamuffincat.swiftload.palette;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// A palette entry the way BlockState.CODEC reads it. "ns:id" is the default state. {id, properties} starts from the
// default state and takes each value the property accepts; bad values and unknown names are skipped silently, like
// vanilla. Unknown ids and anything malformed give null, because vanilla logs those and substitutes air.
final class BlockStateResolver implements FastPalettedCodec.EntryResolver<BlockState> {

    private record BlockInfo(BlockState defaultState, StateDefinition<Block, BlockState> definition, boolean singleton) {
    }

    private final ConcurrentHashMap<String, BlockInfo> blocks = new ConcurrentHashMap<>();

    @Override
    public BlockState resolve(Tag entry) {
        if (entry instanceof StringTag s) {
            BlockInfo b = block(s.value());
            return b == null ? null : b.defaultState;
        }
        if (entry instanceof CompoundTag c) {
            if (!(c.get("id") instanceof StringTag id)) {
                return null;
            }
            BlockInfo b = block(id.value());
            if (b == null) {
                return null;
            }
            if (b.singleton || !(c.get("properties") instanceof CompoundTag properties)) {
                return b.defaultState;
            }
            BlockState state = b.defaultState;
            for (String name : properties.keySet()) {
                Property<?> property = b.definition.getProperty(name);
                if (property != null && properties.get(name) instanceof StringTag value) {
                    state = withValue(state, property, value.value());
                }
            }
            return state;
        }
        return null;
    }

    private static <V extends Comparable<V>> BlockState withValue(BlockState state, Property<V> property, String name) {
        Optional<V> value = property.getValue(name);
        return value.isPresent() ? state.setValue(property, value.get()) : state;
    }

    // Only real blocks are cached. Registry.get and not getValue, which returns air for unknown ids
    private BlockInfo block(String id) {
        BlockInfo b = blocks.get(id);
        if (b != null) {
            return b;
        }
        Identifier location = Identifier.tryParse(id);
        if (location == null) {
            return null;
        }
        Optional<Holder.Reference<Block>> holder = BuiltInRegistries.BLOCK.get(location);
        if (holder.isEmpty()) {
            return null;
        }
        Block block = holder.get().value();
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        b = new BlockInfo(block.defaultBlockState(), definition, definition.isSingletonState());
        blocks.putIfAbsent(id, b);
        return b;
    }
}
