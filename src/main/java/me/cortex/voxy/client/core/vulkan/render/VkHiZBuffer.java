package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.shader.VkComputePipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

//Depth pyramid used by the traversal for occlusion culling. Built with compute, up to 4 levels per dispatch (see
// hiz/hiz.comp), in a single channel float image
public class VkHiZBuffer {
    private final VkComputePipeline reduce;
    private VkImage2D texture;
    private int levels;
    private int width;
    private int height;

    public static VkShaderBuilder hizShader(RenderProperties properties) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .add(ShaderType.COMPUTE, "voxy:hiz/hiz.comp");
    }

    public VkHiZBuffer(RenderProperties properties) {
        this.reduce = hizShader(properties).compileCompute();
    }

    private void alloc(int width, int height) {
        this.levels = (int)Math.ceil(Math.log(Math.max(width, height))/Math.log(2));
        this.texture = new VkImage2D(VK_FORMAT_R32_SFLOAT, this.levels, width, height).name("HiZ");
        this.width  = width;
        this.height = height;
    }

    //srcDepthView: depth only view of the source depth buffer
    public void buildMipChain(VkCommandBuffer cmd, long srcDepthView, int width, int height) {
        if (this.width != Integer.highestOneBit(width) || this.height != Integer.highestOneBit(height)) {
            if (this.texture != null) {
                this.texture.free();
                this.texture = null;
            }
            this.alloc(Integer.highestOneBit(width), Integer.highestOneBit(height));
        }
        long sampler = VkSamplers.nearestMipNearestClamp();
        VkBarriers.attachmentToSampled(cmd);
        long src = srcDepthView;
        int level = 0;
        while (level < this.levels) {
            int count = Math.min(4, this.levels - level);
            int dimX = Math.max(this.width >> level, 1);
            int dimY = Math.max(this.height >> level, 1);
            this.reduce.texture(0, src, sampler);
            for (int i = 0; i < 4; i++) {
                //Unused outputs are bound to a valid view, they are never written
                this.reduce.image(i, this.texture.attachmentView(Math.min(level + i, level + count - 1)));
            }
            this.reduce.bind(cmd);
            this.reduce.pushConstants(cmd, dimX, dimY, count);
            this.reduce.dispatch(cmd, (dimX + 15) / 16, (dimY + 15) / 16, 1);
            level += count;
            if (level < this.levels) {
                VkBarriers.storageToSampled(cmd);
                src = this.texture.levelSampleView(level - 1);
            }
        }
        VkBarriers.storageToAll(cmd);
    }

    public void free() {
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        this.reduce.free();
    }

    public long getHizView() {
        return this.texture.sampleView;
    }

    public int getPackedLevels() {
        return (this.width<<16)|this.height;
    }
}
