package me.cortex.voxy.client.mixin.minecraft.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import me.cortex.voxy.client.core.vulkan.VkContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VulkanCommandEncoder.class, remap = false)
public class MixinVulkanCommandEncoder {
    //Voxy's command buffers come from minecraft's per submit command pool, so anything still recording must be
    // spliced into this submission before it is closed (otherwise the pool is reset under it)
    @Inject(method = "submit", at = @At("HEAD"))
    private void voxy$flushBeforeSubmit(CallbackInfo ci) {
        if (VkContext.isInitialized()) {
            VkContext.get().stream.flush();
        }
    }
}
