package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.SSAOMode;
import me.cortex.voxy.client.core.vulkan.Capabilities;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.*;
import me.cortex.voxy.client.core.vulkan.shader.VkComputePipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import org.joml.Matrix4f;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

//Vulkan port of SSAO (same compute shader, matrices in a uniform buffer instead of loose uniforms)
public class VkSSAO {
    public static VkSSAO create(RenderProperties properties, SSAOMode mode) {
        return switch (mode) {
            case BASIC -> new VkSSAO(properties, false, 0);
            case BETTER -> new VkSSAO(properties, true, 12);
            case BEST -> new VkSSAO(properties, true, 24);
            //No gpu memory query on vulkan, same fallback as GL without NVX_gpu_memory_info
            case AUTO -> Capabilities.INSTANCE.isAmd ? create(properties, SSAOMode.BETTER) : create(properties, SSAOMode.BASIC);
        };
    }

    public static VkShaderBuilder ssaoShader(RenderProperties properties, boolean betterSSAO, int samples) {
        var builder = VkShaderBuilder.make()
                .apply(properties::apply)
                .add(ShaderType.COMPUTE, "voxy:post/ssao.comp");
        if (betterSSAO) {
            builder.define("BETTER_SSAO")
                    .defineIf("SSAO_STEPS", samples!=0, samples)
                    .defineIf("USE_GENERATED_SAMPLE_POINTS", true);
            String array = "";
            for (int i = 0; i < samples; i++) {
                array += "vec2(";
                float a = (((float) i) + 0.5f) * (1.0f / samples);
                float base = (float) (i * (1.0 / 1.6180339887) + 0.5);
                float r = (float) Math.sqrt(base % 1);
                float theta = a * 6.2831853f;
                array += (float) (r * Math.cos(theta));
                array += "f, ";
                array += (float) (r * Math.sin(theta));
                array += "f)";
                if (i != samples - 1) {
                    array += ", ";
                }
            }
            builder.replace("%%CONST_ARRAY%%", array);
        }
        return builder;
    }

    private final VkComputePipeline ssaoCompute;
    private final boolean isBetterSSAO;
    private final int spp;
    private final VkGpuBuffer uniform = new VkGpuBuffer(256).name("SSAOUniform");

    public VkSSAO(RenderProperties properties, boolean betterSSAO, int samples) {
        this.spp = samples;
        this.isBetterSSAO = betterSSAO;
        this.ssaoCompute = ssaoShader(properties, betterSSAO, samples).compileCompute();
    }

    private long depthSampler(VkImage2D depth) {
        if (this.isBetterSSAO || !VkFormats.supportsLinearFilter(depth.format)) {
            return VkSamplers.nearestMipNearestClamp();
        }
        return VkSamplers.linearClamp();
    }

    //colourOut/colourIn: RGBA8 images of the viewport size, baseDepth: voxy's depth, sourceDepthView: vanilla depth
    public void computeSSAO(VkCommandBuffer cmd, VkViewport viewport, VkImage2D colourOut, VkImage2D colourIn, VkImage2D baseDepth, long sourceDepthView) {
        long ptr = UploadStream.INSTANCE.upload(this.uniform, 0, 256);
        var scratch = new Matrix4f();
        if (this.isBetterSSAO) {
            viewport.projection.getToAddress(ptr);
            viewport.projection.invert(scratch).getToAddress(ptr+64);
            viewport.modelView.getToAddress(ptr+128);
            viewport.vanillaProjection.invert(scratch).getToAddress(ptr+192);
        } else {
            viewport.MVP.getToAddress(ptr);
            viewport.MVP.invert(scratch).getToAddress(ptr+64);
        }
        UploadStream.INSTANCE.commit();

        this.ssaoCompute
                .image(0, colourOut.sampleView)
                .texture(1, colourIn.sampleView, VkSamplers.nearestClamp())
                .texture(2, baseDepth.sampleView, this.depthSampler(baseDepth))
                .ubo(4, this.uniform);
        if (this.isBetterSSAO) {
            this.ssaoCompute.texture(3, sourceDepthView, VkSamplers.nearestMipNearestClamp());
        }
        VkBarriers.attachmentToSampled(cmd);
        this.ssaoCompute.bind(cmd);
        this.ssaoCompute.dispatch(cmd, (viewport.width+7)/8, (viewport.height+7)/8, 1);
        //Storage image writes -> later attachment use/sampling
        VkBarriers.storageToAll(cmd);
    }

    public void free() {
        this.ssaoCompute.free();
        this.uniform.free();
    }

    public void addDebugInfo(List<String> debugLines) {
        debugLines.add("SSAO: "+(this.isBetterSSAO?("new ("+this.spp+" spp)"):"basic"));
    }
}
