package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.vulkan.VkFormats;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;

//Vulkan viewport: the GL Viewport's hiz/depth bounding resources plus the MDICViewport buffers
public class VkViewport extends Viewport<VkViewport> {
    public final VkHiZBuffer hiz;
    public final VkDepthTarget depthBounding = new VkDepthTarget(VkFormats.depth24());

    public final VkGpuBuffer drawCountCallBuffer = new VkGpuBuffer(1024).name("DrawCountCall").indirectUse();
    public final VkGpuBuffer drawCallBuffer = new VkGpuBuffer(5*4*(VkSectionRenderer.OPAQUE_DRAW_COUNT+VkSectionRenderer.TRANSLUCENT_DRAW_COUNT+VkSectionRenderer.TEMPORAL_DRAW_COUNT)).name("DrawCalls").indirectUse();
    public final VkGpuBuffer positionScratchBuffer = new VkGpuBuffer(8*400000).name("PositionScratch");
    public final VkGpuBuffer indirectLookupBuffer = new VkGpuBuffer(VkHierarchicalOcclusionTraverser.MAX_QUEUE_SIZE*4+4).name("IndirectLookup");
    public final VkGpuBuffer visibilityBuffer;

    public VkViewport(RenderProperties properties, int maxSectionCount) {
        super(properties);
        this.hiz = new VkHiZBuffer(properties);
        this.visibilityBuffer = new VkGpuBuffer(maxSectionCount*4L).name("SectionVisibility");
    }

    @Override
    protected void resizeResources() {
        if (this.depthBounding.resize(this.width, this.height)) {
            this.depthBounding.clear(this.properties.inverseClearDepth());
        }
    }

    @Override
    protected void delete0() {
        this.hiz.free();
        this.depthBounding.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public VkGpuBuffer getRenderList() {
        return this.indirectLookupBuffer;
    }
}
