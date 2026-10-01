package me.cortex.voxy.client.core.vulkan.render;

import org.lwjgl.vulkan.VkCommandBuffer;

//Render targets the section renderer draws into, provided by the vulkan render pipeline
// (replaces AbstractRenderPipeline.setupAndBindOpaque/Translucent)
public interface IVkTerrainTargets {
    int colourFormat();
    //Depth/stencil format of the targets
    int depthFormat();
    void beginOpaque(VkCommandBuffer cmd, VkViewport viewport);
    void beginTranslucent(VkCommandBuffer cmd, VkViewport viewport);
}
