package io.github.ragamuffincat.swiftload.mixin;

import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.Swiftload;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    // Prints the debugStats counters once the worlds are saved and closed
    @Inject(method = "stopServer", at = @At("TAIL"))
    private void swiftload$printStats(CallbackInfo ci) {
        if (Features.DEBUG_STATS) {
            Swiftload.printStats();
        }
    }
}
