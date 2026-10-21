package io.github.ragamuffincat.swiftload.mixin;

import net.minecraft.nbt.NbtAccounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Resets an accounter before vanilla reads the payload again. No NbtAccounter method is touched, other mods hook them.
@Mixin(NbtAccounter.class)
public interface NbtAccounterAccessor {

    @Accessor("usage")
    void swiftload$setUsage(long usage);

    @Accessor("depth")
    void swiftload$setDepth(int depth);
}
