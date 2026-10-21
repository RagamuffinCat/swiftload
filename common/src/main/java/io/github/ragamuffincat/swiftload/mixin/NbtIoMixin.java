package io.github.ragamuffincat.swiftload.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.ragamuffincat.swiftload.Features;
import io.github.ragamuffincat.swiftload.io.SwiftDataInputStream;
import io.github.ragamuffincat.swiftload.nbt.FastNbt;
import io.github.ragamuffincat.swiftload.stats.Stats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.spongepowered.asm.mixin.Mixin;

import java.io.DataInput;
import java.io.InputStream;
import java.io.OutputStream;

@Mixin(NbtIo.class)
public abstract class NbtIoMixin {

    // Region chunks get parsed from the inflated array in one pass. If the fast reader gives up, vanilla reads the
    // same stream from the start.
    @WrapMethod(method = "read(Ljava/io/DataInput;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;")
    private static CompoundTag swiftload$read(DataInput input, NbtAccounter accounter, Operation<CompoundTag> original) {
        long t0 = Features.DEBUG_STATS ? System.nanoTime() : 0L;
        try {
            if (Features.FAST_NBT && input instanceof SwiftDataInputStream s) {
                CompoundTag tag = FastNbt.tryRead(s.source(), accounter);
                if (tag != null) {
                    return tag;
                }
            }
            return original.call(input, accounter);
        } finally {
            if (Features.DEBUG_STATS) {
                Stats.hit(Stats.Feature.NBT_READ_ALL, System.nanoTime() - t0);
            }
        }
    }

    // level.dat, player data, data/*.dat and structure templates. The Path overload delegates here.
    @WrapMethod(method = "readCompressed(Ljava/io/InputStream;Lnet/minecraft/nbt/NbtAccounter;)Lnet/minecraft/nbt/CompoundTag;")
    private static CompoundTag swiftload$readCompressed(InputStream in, NbtAccounter accounter, Operation<CompoundTag> original) {
        return Features.GZIP_FILES ? FastNbt.readCompressed(in, accounter) : original.call(in, accounter);
    }

    @WrapMethod(method = "writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/io/OutputStream;)V")
    private static void swiftload$writeCompressed(CompoundTag tag, OutputStream out, Operation<Void> original) {
        if (Features.FAST_WRITE) {
            FastNbt.writeCompressed(tag, out);
        } else {
            original.call(tag, out);
        }
    }
}
