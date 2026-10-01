package me.cortex.voxy.client.core.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK13.*;

//Dynamic rendering scopes, the replacement for binding a GlFramebuffer. Attachments are always loaded and
// stored (GL semantics) and are in VK_IMAGE_LAYOUT_GENERAL.
public final class VkRendering {
    //depthView == 0: no depth attachment. stencil: whether the depth view also has a stencil aspect
    public static void begin(VkCommandBuffer cmd, int width, int height, long[] colorViews, long depthView, boolean stencil) {
        begin(cmd, width, height, colorViews, depthView, stencil, false, 0, 0);
    }

    //clearDepthStencil: clears the depth (and stencil) attachment when the pass begins instead of loading it, replaces a
    // separate vkCmdClearDepthStencilImage and its barriers
    public static void begin(VkCommandBuffer cmd, int width, int height, long[] colorViews, long depthView, boolean stencil, boolean clearDepthStencil, float clearDepth, int clearStencil) {
        VkBarriers.passCount++;
        try (var stack = MemoryStack.stackPush()) {
            var colors = VkRenderingAttachmentInfo.calloc(colorViews.length, stack);
            for (int i = 0; i < colorViews.length; i++) {
                colors.get(i).sType$Default()
                        .imageView(colorViews[i])
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }
            var info = VkRenderingInfo.calloc(stack).sType$Default()
                    .layerCount(1)
                    .viewMask(0)
                    .pColorAttachments(colors);
            info.renderArea().offset().set(0, 0);
            info.renderArea().extent().set(width, height);
            if (depthView != 0) {
                var depth = VkRenderingAttachmentInfo.calloc(stack).sType$Default()
                        .imageView(depthView)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .loadOp(clearDepthStencil ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                if (clearDepthStencil) {
                    depth.clearValue().depthStencil().set(clearDepth, clearStencil);
                }
                info.pDepthAttachment(depth);
                if (stencil) {
                    info.pStencilAttachment(depth);
                }
            }
            vkCmdBeginRenderingKHR(cmd, info);

            var viewport = VkViewport.calloc(1, stack).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            vkCmdSetViewport(cmd, 0, viewport);
            var scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(width, height);
            vkCmdSetScissor(cmd, 0, scissor);
        }
    }

    public static void end(VkCommandBuffer cmd) {
        vkCmdEndRenderingKHR(cmd);
    }

    private VkRendering() {}
}
