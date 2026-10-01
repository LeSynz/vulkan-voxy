package me.cortex.voxy.client.core.vulkan.shader;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

//Base of compute and graphics pipelines. Descriptors are set by GL binding number + kind (like AutoBindingShader)
// and pushed with VK_KHR_push_descriptor (required by minecraft) when the pipeline is bound.
public abstract class VkPipeline extends TrackedObject implements Destroyable {
    private record Descriptor(int type, long buffer, long offset, long range, long view, long sampler, TrackedObject owner) {}

    protected final VkPipelineLayout layout;
    protected final int bindPoint;
    protected long pipeline;
    private final Map<String, String> defines;
    private final Int2ObjectOpenHashMap<Descriptor> descriptors = new Int2ObjectOpenHashMap<>();
    private final String name;

    protected VkPipeline(VkShaderBuilder builder, Map<ShaderType, ByteBuffer> spirv, int bindPoint) {
        this.defines = new HashMap<>(builder.defines);
        this.bindPoint = bindPoint;
        this.layout = new VkPipelineLayout(spirv);
        this.name = builder.stages().toString();
    }

    protected static long createModule(ByteBuffer spirv) {
        try (var stack = MemoryStack.stackPush()) {
            var smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
            var p = stack.mallocLong(1);
            VkContext.check(vkCreateShaderModule(VkContext.get().vkDevice, smci, null, p), "vkCreateShaderModule");
            return p.get(0);
        }
    }

    private int defineBinding(String define) {
        var val = this.defines.get(define);
        if (val == null) {
            throw new IllegalArgumentException("Define not set: " + define);
        }
        return Integer.parseInt(val.trim());
    }

    public boolean hasDefine(String define) {
        return this.defines.containsKey(define);
    }

    private VkPipeline put(int binding, Descriptor descriptor) {
        this.descriptors.put(binding, descriptor);
        return this;
    }

    // ---- storage buffers (GL_SHADER_STORAGE_BUFFER bindings)
    public VkPipeline ssbo(int binding, VkGpuBuffer buffer) {
        return this.ssbo(binding, buffer, 0, VK_WHOLE_SIZE);
    }

    public VkPipeline ssbo(int binding, VkGpuBuffer buffer, long offset, long range) {
        return this.put(VkShaderCompiler.SSBO_BINDING_BASE + binding, new Descriptor(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, buffer.buffer, offset, range, 0, 0, buffer));
    }

    public VkPipeline ssboRaw(int binding, long buffer, long offset, long range) {
        return this.put(VkShaderCompiler.SSBO_BINDING_BASE + binding, new Descriptor(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, buffer, offset, range, 0, 0, null));
    }

    public VkPipeline ssbo(String define, VkGpuBuffer buffer) {
        return this.ssbo(this.defineBinding(define), buffer);
    }

    public VkPipeline ssboIf(String define, VkGpuBuffer buffer) {
        if (this.defines.containsKey(define)) {
            return this.ssbo(define, buffer);
        }
        return this;
    }

    // ---- uniform buffers (GL_UNIFORM_BUFFER bindings)
    public VkPipeline ubo(int binding, VkGpuBuffer buffer) {
        return this.ubo(binding, buffer, 0, buffer.size());
    }

    public VkPipeline ubo(int binding, VkGpuBuffer buffer, long offset, long range) {
        return this.put(VkShaderCompiler.UBO_BINDING_BASE + binding, new Descriptor(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, buffer.buffer, offset, range, 0, 0, buffer));
    }

    public VkPipeline uboRaw(int binding, long buffer, long offset, long range) {
        return this.put(VkShaderCompiler.UBO_BINDING_BASE + binding, new Descriptor(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, buffer, offset, range, 0, 0, null));
    }

    public VkPipeline ubo(String define, VkGpuBuffer buffer) {
        return this.ubo(this.defineBinding(define), buffer);
    }

    // ---- combined image samplers (texture units)
    public VkPipeline texture(int unit, long view, long sampler) {
        return this.put(VkShaderCompiler.TEXTURE_BINDING_BASE + unit, new Descriptor(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 0, 0, 0, view, sampler, null));
    }

    public VkPipeline texture(String define, long view, long sampler) {
        return this.texture(this.defineBinding(define), view, sampler);
    }

    // ---- storage images (glBindImageTexture units)
    public VkPipeline image(int unit, long view) {
        return this.put(VkShaderCompiler.IMAGE_BINDING_BASE + unit, new Descriptor(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 0, 0, 0, view, 0, null));
    }

    public void bind(VkCommandBuffer cmd) {
        this.assertNotFreed();
        vkCmdBindPipeline(cmd, this.bindPoint, this.pipeline);
        this.pushDescriptors(cmd);
    }

    //Re-pushes the descriptors (after changing a binding while the pipeline is bound)
    public void pushDescriptors(VkCommandBuffer cmd) {
        int count = this.layout.bindings.size();
        if (count == 0) return;
        try (var stack = MemoryStack.stackPush()) {
            var writes = VkWriteDescriptorSet.calloc(count, stack);
            int i = 0;
            for (var entry : this.layout.bindings.int2IntEntrySet()) {
                int binding = entry.getIntKey();
                var d = this.descriptors.get(binding);
                if (d == null) {
                    throw new IllegalStateException("Pipeline " + this.name + " has no descriptor for binding " + binding);
                }
                if (d.type != entry.getIntValue()) {
                    throw new IllegalStateException("Pipeline " + this.name + " binding " + binding + " expects descriptor type " + entry.getIntValue() + " got " + d.type);
                }
                if (d.owner != null) {
                    d.owner.assertNotFreed();
                }
                var w = writes.get(i++).sType$Default()
                        .dstBinding(binding)
                        .descriptorCount(1)
                        .descriptorType(d.type);
                if (d.type == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER || d.type == VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER) {
                    w.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(d.buffer).offset(d.offset).range(d.range));
                } else {
                    w.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(d.view).sampler(d.sampler).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
                }
            }
            vkCmdPushDescriptorSetKHR(cmd, this.bindPoint, this.layout.layout, 0, writes);
        }
    }

    public void pushConstants(VkCommandBuffer cmd, ByteBuffer data) {
        if (data.remaining() > this.layout.pushConstantSize) {
            throw new IllegalArgumentException("Push constant data larger than declared block (" + data.remaining() + " > " + this.layout.pushConstantSize + ")");
        }
        vkCmdPushConstants(cmd, this.layout.layout, this.layout.pushConstantStages, 0, data);
    }

    //Writes ints at increasing 4 byte offsets from 0
    public void pushConstants(VkCommandBuffer cmd, int... values) {
        try (var stack = MemoryStack.stackPush()) {
            var buf = stack.malloc(values.length * 4);
            for (int i = 0; i < values.length; i++) buf.putInt(i * 4, values[i]);
            this.pushConstants(cmd, buf);
        }
    }

    public int pushConstantSize() {
        return this.layout.pushConstantSize;
    }

    public long layoutHandle() {
        return this.layout.layout;
    }

    @Override
    public void free() {
        this.free0();
        VkContext.get().destroyLater(this);
    }

    @Override
    public void destroy() {
        vkDestroyPipeline(VkContext.get().vkDevice, this.pipeline, null);
        this.layout.destroy();
    }

    protected static void freeAll(Map<ShaderType, ByteBuffer> spirv) {
        spirv.values().forEach(MemoryUtil::memFree);
    }
}
