package me.cortex.voxy.client.core.vulkan;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import static org.lwjgl.vulkan.VK10.*;

//Asynchronous read back of one level of a minecraft RGBA8 texture (replacement for glGetTextureImage). Voxy can
// not force minecraft to submit, so the result becomes available a frame or two later.
public final class VkTextureReadback {
    private final VkMappedBuffer buffer;
    private final VkGpuFence fence;
    public final int width;
    public final int height;

    private VkTextureReadback(GpuTexture texture, int level) {
        this.width = texture.getWidth(level);
        this.height = texture.getHeight(level);
        this.buffer = new VkMappedBuffer((long) this.width * this.height * 4, false).name("TextureReadback");
        var ctx = VkContext.get();
        var cmd = ctx.stream.cmd();
        VkBarriers.allToTransfer(cmd);
        try (var stack = MemoryStack.stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(this.width, this.height, 1);
            vkCmdCopyImageToBuffer(cmd, ((VulkanGpuTexture) texture).vkImage(), VK_IMAGE_LAYOUT_GENERAL, this.buffer.buffer, region);
        }
        VkBarriers.transferToHost(cmd);
        //Make sure the copy is part of the submission the fence tracks
        ctx.stream.flush();
        this.fence = new VkGpuFence();
    }

    //Must be called outside of a minecraft render pass
    public static VkTextureReadback start(GpuTexture texture, int level) {
        return new VkTextureReadback(texture, level);
    }

    public boolean isDone() {
        return this.fence.signaled();
    }

    //Returns the texels (RGBA8, same layout as glGetTextureImage with GL_RGBA/GL_UNSIGNED_BYTE) and frees the buffer
    public int[] takeResult() {
        if (!this.isDone()) {
            throw new IllegalStateException("Readback not complete");
        }
        long size = (long) this.width * this.height * 4;
        this.buffer.invalidate(0, size);
        var out = new int[this.width * this.height];
        MemoryUtil.memIntBuffer(this.buffer.addr(), out.length).get(out);
        this.buffer.free();
        return out;
    }
}
