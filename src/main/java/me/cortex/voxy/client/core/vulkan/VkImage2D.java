package me.cortex.voxy.client.core.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.*;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRSynchronization2.vkCmdPipelineBarrier2KHR;
import static org.lwjgl.vulkan.VK13.*;

//2D image with mips, the vulkan equivalent of GlTexture. Like minecraft's own vulkan textures it lives in
// VK_IMAGE_LAYOUT_GENERAL for its whole lifetime, so only memory barriers are ever needed.
public class VkImage2D extends TrackedObject implements Destroyable {
    private static int COUNT;
    private static long ESTIMATED_TOTAL_SIZE;

    public final long image;
    private final long allocation;
    public final int format;
    public final int width;
    public final int height;
    public final int levels;
    public final boolean isDepth;
    public final boolean hasStencil;

    //View of every mip level, only the depth aspect for depth formats (for sampling)
    public final long sampleView;
    //Per level views: attachment views use every aspect, sample views only the colour/depth aspect
    private final long[] attachmentViews;
    private final long[] levelSampleViews;

    public VkImage2D(int format, int levels, int width, int height) {
        var ctx = VkContext.get();
        this.format = format;
        this.width = width;
        this.height = height;
        this.levels = levels;
        this.isDepth = VkFormats.isDepth(format);
        this.hasStencil = VkFormats.hasStencil(format);

        int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if (this.isDepth) {
            usage |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        } else {
            usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            if (VkFormats.supportsStorage(format)) {
                usage |= VK_IMAGE_USAGE_STORAGE_BIT;
            }
        }

        try (var stack = MemoryStack.stackPush()) {
            var ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(levels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ici.extent().set(width, height, 1);
            var aci = VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var pImage = stack.mallocLong(1);
            var pAlloc = stack.mallocPointer(1);
            int res = vmaCreateImage(ctx.vma, ici, aci, pImage, pAlloc, null);
            if (res != VK_SUCCESS) {
                throw new VkOutOfMemoryException("Failed to allocate " + width + "x" + height + " image (VkResult " + res + ")", res);
            }
            this.image = pImage.get(0);
            this.allocation = pAlloc.get(0);
        }

        this.sampleView = this.createView(0, levels, this.sampleAspect());
        this.attachmentViews = new long[levels];
        this.levelSampleViews = new long[levels];

        //Transition to general, this is the only layout the image will ever be in
        try (var stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                    .srcAccessMask(VK_ACCESS_2_NONE)
                    .dstStageMask(VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT)
                    .dstAccessMask(VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image);
            barrier.subresourceRange().set(this.fullAspect(), 0, levels, 0, 1);
            var dep = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier);
            vkCmdPipelineBarrier2KHR(ctx.stream.cmd(), dep);
        }

        COUNT++;
        ESTIMATED_TOTAL_SIZE += this.estimatedSize();
    }

    public int fullAspect() {
        if (this.isDepth) {
            return VK_IMAGE_ASPECT_DEPTH_BIT | (this.hasStencil ? VK_IMAGE_ASPECT_STENCIL_BIT : 0);
        }
        return VK_IMAGE_ASPECT_COLOR_BIT;
    }

    public int sampleAspect() {
        return this.isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    }

    private long createView(int baseLevel, int levelCount, int aspect) {
        try (var stack = MemoryStack.stackPush()) {
            var vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(this.format);
            vci.subresourceRange().set(aspect, baseLevel, levelCount, 0, 1);
            var pView = stack.mallocLong(1);
            VkContext.check(vkCreateImageView(VkContext.get().vkDevice, vci, null, pView), "vkCreateImageView");
            return pView.get(0);
        }
    }

    //View usable as a render attachment (all aspects) of a single level
    public long attachmentView(int level) {
        if (this.attachmentViews[level] == 0) {
            this.attachmentViews[level] = this.createView(level, 1, this.fullAspect());
        }
        return this.attachmentViews[level];
    }

    //View of a single level for sampling (depth aspect only for depth formats)
    public long levelSampleView(int level) {
        if (this.levelSampleViews[level] == 0) {
            this.levelSampleViews[level] = this.createView(level, 1, this.sampleAspect());
        }
        return this.levelSampleViews[level];
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getLevels() {
        return this.levels;
    }

    //Equivalent of GlTexture.zero, clears every level
    public VkImage2D zero() {
        return this.clear(0, 0, 0, 0, 0);
    }

    public VkImage2D clear(float r, float g, float b, float a, int stencil) {
        var cmd = VkContext.get().stream.cmd();
        VkBarriers.allToTransfer(cmd);
        try (var stack = MemoryStack.stackPush()) {
            var range = VkImageSubresourceRange.calloc(1, stack);
            if (this.isDepth) {
                range.get(0).set(this.fullAspect(), 0, this.levels, 0, 1);
                var value = VkClearDepthStencilValue.calloc(stack).depth(r).stencil(stencil);
                vkCmdClearDepthStencilImage(cmd, this.image, VK_IMAGE_LAYOUT_GENERAL, value, range);
            } else {
                range.get(0).set(VK_IMAGE_ASPECT_COLOR_BIT, 0, this.levels, 0, 1);
                var value = VkClearColorValue.calloc(stack);
                if (VkFormats.isInteger(this.format)) {
                    value.uint32(0, (int) r).uint32(1, (int) g).uint32(2, (int) b).uint32(3, (int) a);
                } else {
                    value.float32(0, r).float32(1, g).float32(2, b).float32(3, a);
                }
                vkCmdClearColorImage(cmd, this.image, VK_IMAGE_LAYOUT_GENERAL, value, range);
            }
        }
        VkBarriers.transferToAll(cmd);
        return this;
    }

    public VkImage2D clearDepthStencil(float depth, int stencil) {
        if (!this.isDepth) throw new IllegalStateException("Not a depth image");
        return this.clear(depth, 0, 0, 0, stencil);
    }

    public VkImage2D name(String name) {
        VkContext.get().setObjectName(VK_OBJECT_TYPE_IMAGE, this.image, name);
        return this;
    }

    private long estimatedSize() {
        long size = 0;
        long elem = VkFormats.texelSize(this.format);
        for (int lvl = 0; lvl < this.levels; lvl++) {
            size += Math.max(((long) this.width) >> lvl, 1) * Math.max(((long) this.height) >> lvl, 1) * elem;
        }
        return size;
    }

    @Override
    public void free() {
        this.free0();
        COUNT--;
        ESTIMATED_TOTAL_SIZE -= this.estimatedSize();
        VkContext.get().destroyLater(this);
    }

    @Override
    public void destroy() {
        var ctx = VkContext.get();
        vkDestroyImageView(ctx.vkDevice, this.sampleView, null);
        for (long v : this.attachmentViews) if (v != 0) vkDestroyImageView(ctx.vkDevice, v, null);
        for (long v : this.levelSampleViews) if (v != 0) vkDestroyImageView(ctx.vkDevice, v, null);
        vmaDestroyImage(ctx.vma, this.image, this.allocation);
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getEstimatedTotalSize() {
        return ESTIMATED_TOTAL_SIZE;
    }
}
