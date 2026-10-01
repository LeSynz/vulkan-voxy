package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.bench.VoxyBenchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftBench {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void voxy$bench(boolean advance, CallbackInfo ci) {
        if (VoxyBenchmark.ENABLED) {
            VoxyBenchmark.onFrame((Minecraft) (Object) this);
        }
    }
}
