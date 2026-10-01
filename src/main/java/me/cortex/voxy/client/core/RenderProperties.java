package me.cortex.voxy.client.core;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import org.lwjgl.vulkan.VK10;

public record RenderProperties(boolean isZero2One, boolean isReverseZ, boolean useBlockAtlasUVs) {

    public VkShaderBuilder apply(VkShaderBuilder builder) {
        return builder.defineIf("USE_ZERO_ONE_DEPTH", this.isZero2One)
                .defineIf("USE_REVERSE_Z", this.isReverseZ);
    }

    public int vkCloserEqualDepthCompare() {
        return this.isReverseZ? VK10.VK_COMPARE_OP_GREATER_OR_EQUAL: VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
    }

    public int vkCloserDepthCompare() {
        return this.isReverseZ? VK10.VK_COMPARE_OP_GREATER: VK10.VK_COMPARE_OP_LESS;
    }

    public int vkFurtherDepthCompare() {
        return this.isReverseZ? VK10.VK_COMPARE_OP_LESS: VK10.VK_COMPARE_OP_GREATER;
    }

    public float clearDepth() {
        return this.isReverseZ?0.0f:1.0f;
    }

    public float inverseClearDepth() {
        return this.isReverseZ?1.0f:0.0f;
    }







    private static boolean useReverseZ() {
        return DepthStencilState.DEFAULT.depthTest().equals(CompareOp.GREATER_THAN_OR_EQUAL);
    }

    public static RenderProperties getRenderProperties() {
        return new RenderProperties(
                RenderSystem.getDevice().getDeviceInfo().isZZeroToOne(),
                useReverseZ(),
                false);
    }
}
