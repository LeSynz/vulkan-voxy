package me.cortex.voxy.client.mixin.minecraft.vulkan;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

//Vulkan only: voxy runs after minecraft's Main pass closes, so with classic transparency vanilla water (drawn in that
// pass) would sit in front of, and hide, the LODs behind it. Improved transparency draws translucents in a separate
// pass after voxy, matching the OpenGL order, so it is forced on while voxy's vulkan renderer is active.
// See ARCHITECTURE_NOTES.md 1.4.
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererVulkan {
    @Shadow @org.spongepowered.asm.mixin.Final private GameRenderState gameRenderState;

    @ModifyReturnValue(method = "useImprovedTransparency", at = @At("RETURN"))
    private boolean voxy$forceImprovedTransparency(boolean original) {
        if (!original
                && !this.gameRenderState.levelRenderState.renderWireframeTerrain//vanilla disables it for wireframe as well
                && IVoxyRenderSystemHolder.getNullable() instanceof VoxyRenderSystem) {
            return true;
        }
        return original;
    }
}
