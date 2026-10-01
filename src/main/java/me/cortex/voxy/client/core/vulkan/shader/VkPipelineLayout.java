package me.cortex.voxy.client.core.vulkan.shader;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

//Single push descriptor set + one push constant range, built from SPIR-V reflection of every stage
public final class VkPipelineLayout {
    public final long setLayout;
    public final long layout;
    public final int pushConstantSize;
    public final int pushConstantStages;
    //binding -> descriptor type
    public final Int2IntOpenHashMap bindings = new Int2IntOpenHashMap();

    public static int stageBit(ShaderType type) {
        return switch (type) {
            case VERTEX -> VK_SHADER_STAGE_VERTEX_BIT;
            case FRAGMENT -> VK_SHADER_STAGE_FRAGMENT_BIT;
            case COMPUTE -> VK_SHADER_STAGE_COMPUTE_BIT;
            default -> throw new IllegalArgumentException();
        };
    }

    VkPipelineLayout(Map<ShaderType, ByteBuffer> spirv) {
        var stagesPerBinding = new TreeMap<Integer, Integer>();
        int pushSize = 0;
        int pushStages = 0;
        for (var entry : spirv.entrySet()) {
            int stage = stageBit(entry.getKey());
            var reflection = SpirvReflection.reflect(entry.getValue());
            for (var b : reflection.bindings()) {
                if (this.bindings.containsKey(b.binding()) && this.bindings.get(b.binding()) != b.descriptorType()) {
                    throw new IllegalStateException("Binding " + b.binding() + " has different types in different stages");
                }
                this.bindings.put(b.binding(), b.descriptorType());
                stagesPerBinding.merge(b.binding(), stage, (a, c) -> a | c);
            }
            if (reflection.pushConstantSize() != 0) {
                pushSize = Math.max(pushSize, reflection.pushConstantSize());
                pushStages |= stage;
            }
        }
        this.pushConstantSize = pushSize;
        this.pushConstantStages = pushStages;
        if (pushSize > VkContext.get().maxPushConstantsSize) {
            throw new IllegalStateException("Push constants too large: " + pushSize);
        }

        var dev = VkContext.get().vkDevice;
        try (var stack = MemoryStack.stackPush()) {
            var lb = VkDescriptorSetLayoutBinding.calloc(stagesPerBinding.size(), stack);
            int i = 0;
            for (var e : stagesPerBinding.entrySet()) {
                lb.get(i++).binding(e.getKey())
                        .descriptorType(this.bindings.get((int) e.getKey()))
                        .descriptorCount(1)
                        .stageFlags(e.getValue());
            }
            var dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(lb);
            var p = stack.mallocLong(1);
            VkContext.check(vkCreateDescriptorSetLayout(dev, dslci, null, p), "vkCreateDescriptorSetLayout");
            this.setLayout = p.get(0);

            var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(this.setLayout));
            if (pushSize != 0) {
                var range = VkPushConstantRange.calloc(1, stack);
                range.get(0).stageFlags(pushStages).offset(0).size(pushSize);
                plci.pPushConstantRanges(range);
            }
            VkContext.check(vkCreatePipelineLayout(dev, plci, null, p), "vkCreatePipelineLayout");
            this.layout = p.get(0);
        }
    }

    void destroy() {
        var dev = VkContext.get().vkDevice;
        vkDestroyPipelineLayout(dev, this.layout, null);
        vkDestroyDescriptorSetLayout(dev, this.setLayout, null);
    }
}
