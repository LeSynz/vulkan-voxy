package me.cortex.voxy.client.mixin.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import me.cortex.voxy.client.core.vulkan.VoxyVulkanFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

@Mixin(value = VulkanFeatureSets.class, remap = false)
public class MixinVulkanFeatureSets {
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true)
    private static void voxy$addVoxyFeatureSet(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        var sets = new HashSet<>(cir.getReturnValue());
        sets.add(VoxyVulkanFeatures.FEATURE_SET);
        cir.setReturnValue(sets);
    }
}
