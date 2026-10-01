package me.cortex.voxy.client.core.vulkan.render;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

//Minecraft's main render target (the one sodium renders terrain into) as seen by voxy's vulkan renderer
public record VkFrameTarget(long colourView, long depthView, int width, int height, int colourFormat, int depthFormat) {
    public static VkFrameTarget of(GpuTextureView colour, GpuTextureView depth) {
        var c = (VulkanGpuTextureView) colour;
        var d = (VulkanGpuTextureView) depth;
        return new VkFrameTarget(c.vkImageView(), d.vkImageView(),
                d.getWidth(0), d.getHeight(0),
                VulkanConst.toVk(c.texture().getFormat()), VulkanConst.toVk(d.texture().getFormat()));
    }
}
