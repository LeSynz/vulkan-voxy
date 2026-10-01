package me.cortex.voxy.client.mixin.minecraft.vulkan;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.vulkan.VkVoxyRenderSystem;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Vulkan only: voxy's frame is recorded right after minecraft's "Solid"/"Main" render pass closes. That is the start
// of executeOit (improved transparency) or otherwise the start of executeOutline, see ARCHITECTURE_NOTES.md 1.4.
// renderFrame consumes the frame captured during sodium's terrain pass, so the second hook is a no-op.
@Mixin(LevelRenderer.class)
public class MixinLevelRendererVulkan {
    @Inject(method = "executeOit", at = @At("HEAD"))
    private void voxy$renderBeforeOit(CallbackInfo ci) {
        voxy$renderVulkanFrame();
    }

    @Inject(method = "executeOutline", at = @At("HEAD"))
    private void voxy$renderBeforeOutline(CallbackInfo ci) {
        voxy$renderVulkanFrame();
    }

    @Unique
    private static void voxy$renderVulkanFrame() {
        if (IVoxyRenderSystemHolder.getNullable() instanceof VkVoxyRenderSystem vk) {
            vk.renderFrame();
        }
    }
}
