package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.VkContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

public class VkComputePipeline extends VkPipeline {
    VkComputePipeline(VkShaderBuilder builder) {
        this(builder, builder.compileSpirv(Capabilities.INSTANCE.isIntel));
    }

    private VkComputePipeline(VkShaderBuilder builder, java.util.Map<ShaderType, java.nio.ByteBuffer> spirv) {
        super(builder, spirv, VK_PIPELINE_BIND_POINT_COMPUTE);
        long module = createModule(spirv.get(ShaderType.COMPUTE));
        try (var stack = MemoryStack.stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default()
                    .layout(this.layout.layout)
                    .stage().sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module)
                    .pName(stack.UTF8("main"));
            var p = stack.mallocLong(1);
            VkContext.check(vkCreateComputePipelines(VkContext.get().vkDevice, VK_NULL_HANDLE, ci, null, p), "vkCreateComputePipelines");
            this.pipeline = p.get(0);
        } finally {
            vkDestroyShaderModule(VkContext.get().vkDevice, module, null);
            freeAll(spirv);
        }
    }

    public void dispatch(VkCommandBuffer cmd, int x, int y, int z) {
        if (x == 0 || y == 0 || z == 0) return;
        vkCmdDispatch(cmd, x, y, z);
    }

    public void dispatchIndirect(VkCommandBuffer cmd, long buffer, long offset) {
        vkCmdDispatchIndirect(cmd, buffer, offset);
    }
}
