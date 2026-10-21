package io.github.ragamuffincat.swiftload.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.palette.FastPalettes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PalettedContainerFactory.class)
public abstract class PalettedContainerFactoryMixin {

    // SerializableChunkData.parse decodes every section with these codecs, so parse itself (hooked by other mods)
    // stays untouched
    @ModifyReturnValue(method = "create", at = @At("RETURN"))
    private static PalettedContainerFactory swiftload$fastCodecs(PalettedContainerFactory factory, RegistryAccess registries) {
        return Features.FAST_PALETTE ? FastPalettes.wrap(factory, registries) : factory;
    }
}
