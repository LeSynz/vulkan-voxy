package me.cortex.voxy.client.core.vulkan.shader;

import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.client.core.vulkan.VkFormats;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Map;

import static org.lwjgl.vulkan.VK13.*;

//Graphics pipeline for dynamic rendering (VK_KHR_dynamic_rendering is required by minecraft). All voxy draws
// pull their vertex data from storage buffers, so there is no vertex input state. Viewport and scissor are dynamic.
public class VkGraphicsPipeline extends VkPipeline {
    public final VkGraphicsState state;

    VkGraphicsPipeline(VkShaderBuilder builder, VkGraphicsState state) {
        this(builder, state, builder.compileSpirv(Capabilities.INSTANCE.isIntel));
    }

    private VkGraphicsPipeline(VkShaderBuilder builder, VkGraphicsState state, Map<ShaderType, ByteBuffer> spirv) {
        super(builder, spirv, VK_PIPELINE_BIND_POINT_GRAPHICS);
        this.state = state.copy();
        var dev = VkContext.get().vkDevice;
        var modules = new ArrayList<Long>();
        try (var stack = MemoryStack.stackPush()) {
            var stages = VkPipelineShaderStageCreateInfo.calloc(spirv.size(), stack);
            int i = 0;
            for (var entry : spirv.entrySet()) {
                long module = createModule(entry.getValue());
                modules.add(module);
                stages.get(i++).sType$Default()
                        .stage(VkPipelineLayout.stageBit(entry.getKey()))
                        .module(module)
                        .pName(stack.UTF8("main"));
            }

            var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(state.topology)
                    .primitiveRestartEnable(false);
            var viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1)
                    .scissorCount(1);
            var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .depthClampEnable(false)
                    .rasterizerDiscardEnable(false)
                    .polygonMode(state.polygonMode)
                    .cullMode(state.cullMode)
                    .frontFace(state.frontFace)
                    .depthBiasEnable(false)
                    .lineWidth(1.0f);
            var multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            var stencilOp = VkStencilOpState.calloc(stack)
                    .failOp(state.stencilFailOp)
                    .passOp(state.stencilPassOp)
                    .depthFailOp(state.stencilDepthFailOp)
                    .compareOp(state.stencilCompare)
                    .compareMask(state.stencilCompareMask)
                    .writeMask(state.stencilWriteMask)
                    .reference(state.stencilReference);
            var depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
                    .depthTestEnable(state.depthTest)
                    //GL: depth writes only happen while the depth test is enabled
                    .depthWriteEnable(state.depthTest && state.depthWrite)
                    .depthCompareOp(state.depthCompare)
                    .depthBoundsTestEnable(false)
                    .stencilTestEnable(state.stencilTest)
                    .front(stencilOp)
                    .back(stencilOp);
            var attachments = VkPipelineColorBlendAttachmentState.calloc(Math.max(state.colorFormats.length, 0), stack);
            for (int a = 0; a < state.colorFormats.length; a++) {
                attachments.get(a)
                        .blendEnable(state.blend)
                        .srcColorBlendFactor(state.srcColor)
                        .dstColorBlendFactor(state.dstColor)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(state.srcAlpha)
                        .dstAlphaBlendFactor(state.dstAlpha)
                        .alphaBlendOp(VK_BLEND_OP_ADD)
                        .colorWriteMask(state.colorWriteMask);
            }
            var colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default()
                    .logicOpEnable(false)
                    .pAttachments(attachments);
            var dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            var rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .viewMask(0)
                    .colorAttachmentCount(state.colorFormats.length)
                    .pColorAttachmentFormats(stack.ints(state.colorFormats))
                    .depthAttachmentFormat(state.depthFormat)
                    .stencilAttachmentFormat(state.hasStencilAttachment() ? state.depthFormat : VK_FORMAT_UNDEFINED);

            var ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default()
                    .pNext(rendering)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(colorBlend)
                    .pDynamicState(dynamic)
                    .layout(this.layout.layout)
                    .renderPass(VK_NULL_HANDLE)
                    .subpass(0);
            var p = stack.mallocLong(1);
            VkContext.check(vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, ci, null, p), "vkCreateGraphicsPipelines");
            this.pipeline = p.get(0);
        } finally {
            for (long m : modules) vkDestroyShaderModule(dev, m, null);
            freeAll(spirv);
        }
    }
}
