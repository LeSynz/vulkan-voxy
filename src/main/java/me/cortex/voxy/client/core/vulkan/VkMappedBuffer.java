package me.cortex.voxy.client.core.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

//Persistently mapped host visible buffer, the vulkan equivalent of GlPersistentMappedBuffer
public class VkMappedBuffer extends TrackedObject implements Destroyable {
    public final long buffer;
    private final long allocation;
    private final long size;
    private final long addr;
    private final boolean coherent;

    //upload: written sequentially by the cpu and read by the gpu, otherwise: written by the gpu and read randomly by the cpu
    public VkMappedBuffer(long size, boolean upload) {
        var ctx = VkContext.get();
        this.size = size;
        try (var stack = MemoryStack.stackPush()) {
            var bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var aci = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST)
                    .flags(VMA_ALLOCATION_CREATE_MAPPED_BIT | (upload ? VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT : VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT));
            var pBuffer = stack.mallocLong(1);
            var pAlloc = stack.mallocPointer(1);
            var info = VmaAllocationInfo.calloc(stack);
            int res = vmaCreateBuffer(ctx.vma, bci, aci, pBuffer, pAlloc, info);
            if (res != VK_SUCCESS) {
                throw new VkOutOfMemoryException("Failed to allocate mapped vulkan buffer of size " + size + " (VkResult " + res + ")", res);
            }
            this.buffer = pBuffer.get(0);
            this.allocation = pAlloc.get(0);
            this.addr = info.pMappedData();
            if (this.addr == 0) {
                throw new IllegalStateException("Mapped buffer has no mapping");
            }
            var flags = stack.mallocInt(1);
            vmaGetAllocationMemoryProperties(ctx.vma, this.allocation, flags);
            this.coherent = (flags.get(0) & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
        }
    }

    public long addr() {
        return this.addr;
    }

    public long size() {
        return this.size;
    }

    //Make cpu writes visible to the gpu (no-op on coherent memory)
    public void flush(long offset, long size) {
        if (!this.coherent) {
            vmaFlushAllocation(VkContext.get().vma, this.allocation, offset, size);
        }
    }

    //Make gpu writes visible to the cpu (no-op on coherent memory)
    public void invalidate(long offset, long size) {
        if (!this.coherent) {
            vmaInvalidateAllocation(VkContext.get().vma, this.allocation, offset, size);
        }
    }

    public VkMappedBuffer name(String name) {
        VkContext.get().setObjectName(VK_OBJECT_TYPE_BUFFER, this.buffer, name);
        return this;
    }

    @Override
    public void free() {
        this.free0();
        VkContext.get().destroyLater(this);
    }

    @Override
    public void destroy() {
        vmaDestroyBuffer(VkContext.get().vma, this.buffer, this.allocation);
    }
}
