package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.vulkan.VkImage2D;

//Vulkan port of DepthFramebuffer: a resizable depth (or depth stencil) image
public class VkDepthTarget {
    private final int format;
    private VkImage2D depth;

    public VkDepthTarget(int format) {
        this.format = format;
    }

    public boolean resize(int width, int height) {
        if (this.depth == null || this.depth.width != width || this.depth.height != height) {
            if (this.depth != null) {
                this.depth.free();
            }
            this.depth = new VkImage2D(this.format, 1, width, height);
            return true;
        }
        return false;
    }

    public void clear(float depth) {
        this.depth.clearDepthStencil(depth, 0);
    }

    public void clear(float depth, int stencil) {
        this.depth.clearDepthStencil(depth, stencil);
    }

    public VkImage2D getImage() {
        return this.depth;
    }

    public int getFormat() {
        return this.format;
    }

    public void free() {
        if (this.depth != null) {
            this.depth.free();
            this.depth = null;
        }
    }
}
