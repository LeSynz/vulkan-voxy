package me.cortex.voxy.client.core.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

//Device local general purpose buffer, the vulkan equivalent of GlBuffer
public class VkGpuBuffer extends TrackedObject implements IGpuBuffer, Destroyable {
    public static final int ALL_USAGE = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
            | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private static int COUNT;
    private static long TOTAL_SIZE;

    public final long buffer;
    private final long allocation;
    private final long size;

    public VkGpuBuffer(long size) {
        this(size, true);
    }

    public VkGpuBuffer(long size, boolean zero) {
        if (size <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive: " + size);
        }
        var ctx = VkContext.get();
        this.size = size;
        try (var stack = MemoryStack.stackPush()) {
            var bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(ALL_USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var aci = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            var pBuffer = stack.mallocLong(1);
            var pAlloc = stack.mallocPointer(1);
            int res = vmaCreateBuffer(ctx.vma, bci, aci, pBuffer, pAlloc, null);
            if (res != VK_SUCCESS) {
                throw new VkOutOfMemoryException("Failed to allocate vulkan buffer of size " + size + " (VkResult " + res + ")", res);
            }
            this.buffer = pBuffer.get(0);
            this.allocation = pAlloc.get(0);
        }
        COUNT++;
        TOTAL_SIZE += size;
        if (zero) {
            this.zero();
        }
    }

    @Override
    public long size() {
        return this.size;
    }

    @Override
    public VkGpuBuffer zero() {
        return this.fillRange(0, VK_WHOLE_SIZE, 0);
    }

    @Override
    public VkGpuBuffer zeroRange(long offset, long size) {
        return this.fillRange(offset, size, 0);
    }

    @Override
    public VkGpuBuffer fill(int data) {
        return this.fillRange(0, VK_WHOLE_SIZE, data);
    }

    public VkGpuBuffer fillRange(long offset, long size, int data) {
        this.assertNotFreed();
        if ((offset & 3) != 0 || (size != VK_WHOLE_SIZE && (size & 3) != 0)) {
            throw new IllegalArgumentException("Fill offset/size must be multiples of 4");
        }
        var cmd = VkContext.get().stream.cmd();
        VkBarriers.allToTransfer(cmd);
        vkCmdFillBuffer(cmd, this.buffer, offset, size, data);
        VkBarriers.transferToAll(cmd, this.indirect);
        return this;
    }

    //Set on buffers that are consumed by indirect draws/dispatches, barriers after copies/fills to them then also make
    // the writes visible to indirect command reads
    public boolean indirect;

    public VkGpuBuffer indirectUse() {
        this.indirect = true;
        return this;
    }

    public VkGpuBuffer name(String name) {
        VkContext.get().setObjectName(VK_OBJECT_TYPE_BUFFER, this.buffer, name);
        return this;
    }

    @Override
    public void free() {
        this.free0();
        COUNT--;
        TOTAL_SIZE -= this.size;
        VkContext.get().destroyLater(this);
    }

    //Called by minecraft's destruction queue once the gpu can no longer be using the buffer
    @Override
    public void destroy() {
        vmaDestroyBuffer(VkContext.get().vma, this.buffer, this.allocation);
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }
}
