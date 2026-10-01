package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsPipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsState;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

//Vulkan port of HiZBuffer: depth pyramid built with one fullscreen pass per level, each level reading the
// previous one through a single level view (GL used GL_TEXTURE_BASE/MAX_LEVEL + glTextureBarrier for this)
public class VkHiZBuffer {
    private final VkGraphicsPipeline hiz;
    private final int format;
    private VkImage2D texture;
    private int levels;
    private int width;
    private int height;

    public static VkShaderBuilder hizShader(RenderProperties properties) {
        return VkShaderBuilder.make()
                .apply(properties::apply)
                .add(ShaderType.VERTEX, "voxy:hiz/blit.vsh")
                .add(ShaderType.FRAGMENT, "voxy:hiz/blit.fsh");
    }

    public VkHiZBuffer(RenderProperties properties) {
        this.format = VkFormats.depthStencil();//Same as GL (DEPTH24_STENCIL8, or D32S8 where unsupported)
        this.hiz = hizShader(properties).compileGraphics(VkGraphicsState.create()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN)
                .depthFormat(this.format)
                .depthTest(VK_COMPARE_OP_ALWAYS)
                .depthWrite(true));
    }

    private void alloc(int width, int height) {
        this.levels = (int)Math.ceil(Math.log(Math.max(width, height))/Math.log(2));
        this.texture = new VkImage2D(this.format, this.levels, width, height).name("HiZ");
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
        int cw = this.width;
        int ch = this.height;
        for (int i = 0; i < this.levels; i++) {
            VkRendering.begin(cmd, cw, ch, new long[0], this.texture.attachmentView(i), this.texture.hasStencil);
            this.hiz.texture(0, i == 0 ? srcDepthView : this.texture.levelSampleView(i - 1), sampler);
            this.hiz.bind(cmd);
            vkCmdDraw(cmd, 4, 1, 0, 0);
            VkRendering.end(cmd);
            VkBarriers.attachmentToSampled(cmd);
            cw = Math.max(cw/2, 1); ch = Math.max(ch/2, 1);
        }
    }

    public void free() {
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
        this.hiz.free();
    }

    public long getHizView() {
        return this.texture.sampleView;
    }

    public int getPackedLevels() {
        return (this.width<<16)|this.height;
    }
}
