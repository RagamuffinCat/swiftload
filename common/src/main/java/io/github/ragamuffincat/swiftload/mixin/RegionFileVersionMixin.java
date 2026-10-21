package io.github.ragamuffincat.swiftload.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.io.LazyDeflaterOutputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;

import java.io.OutputStream;

@Mixin(RegionFileVersion.class)
public abstract class RegionFileVersionMixin {

    // On wrap itself rather than RegionFile's call, because C2ME and Moonrise call it directly
    @WrapMethod(method = "wrap(Ljava/io/OutputStream;)Ljava/io/OutputStream;")
    private OutputStream swiftload$lazyDeflate(OutputStream out, Operation<OutputStream> original) {
        if (Features.FAST_WRITE && (Object) this == RegionFileVersion.VERSION_DEFLATE) {
            return new LazyDeflaterOutputStream(out, LazyDeflaterOutputStream.Format.ZLIB, original::call);
        }
        return original.call(out);
    }
}
