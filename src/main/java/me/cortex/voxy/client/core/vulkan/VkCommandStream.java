package me.cortex.voxy.client.core.vulkan;

import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;

//Records voxy's gpu work into a transient command buffer allocated from minecraft's own command pool, and
// splices it into minecraft's submission with VulkanCommandEncoder.execute. The buffer must be flushed before
// minecraft submits (the pool is recycled per submit) and must never be flushed while a minecraft RenderPass is open.
public final class VkCommandStream {
    private final VkContext ctx;
    private VkCommandBuffer current;

    VkCommandStream(VkContext ctx) {
        this.ctx = ctx;
    }

    public VkCommandBuffer cmd() {
        if (this.current == null) {
            this.current = this.ctx.encoder.allocateAndBeginTransientCommandBuffer();
            //Everything minecraft recorded before this point is visible to us
            VkBarriers.full(this.current);
        }
        return this.current;
    }

    public boolean isRecording() {
        return this.current != null;
    }

    public void flush() {
        if (this.current == null) {
            return;
        }
        //Everything we recorded is visible to whatever minecraft records next
        VkBarriers.full(this.current);
        VkContext.check(vkEndCommandBuffer(this.current), "vkEndCommandBuffer");
        var cb = this.current;
        this.current = null;
        this.ctx.encoder.execute(cb);
    }
}
