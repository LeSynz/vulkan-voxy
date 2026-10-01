package me.cortex.voxy.client.core.vulkan;

import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.vulkan.VkBufferCopy;

import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;

import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

//Vulkan upload stream: host visible mapped staging buffer + vkCmdCopyBuffer recorded into voxy's command stream
public class VkUploadStream extends UploadStream {
    private final VkMappedBuffer uploadBuffer;

    public VkUploadStream(long size) {
        super(size);
        this.uploadBuffer = new VkMappedBuffer(size, true).name("UploadStream");
    }

    @Override
    public long getBaseAddress() {
        return this.uploadBuffer.addr();
    }

    public long getBuffer() {
        return this.uploadBuffer.buffer;
    }

    @Override
    protected void flushRange(long offset, long size) {
        this.uploadBuffer.flush(offset, size);
    }

    @Override
    protected void executeCopies(Deque<UploadData> uploads) {
        var cmd = VkContext.get().stream.cmd();
        //Group the copies by destination so each destination is a single vkCmdCopyBuffer
        var grouped = new IdentityHashMap<VkGpuBuffer, List<UploadData>>();
        for (var entry : uploads) {
            entry.target().assertNotFreed();
            grouped.computeIfAbsent((VkGpuBuffer) entry.target(), k -> new ArrayList<>()).add(entry);
        }
        VkBarriers.allToTransfer(cmd);
        for (var group : grouped.entrySet()) {
            var list = group.getValue();
            //Can be thousands of regions (e.g. node updates), so not on the stack
            var regions = VkBufferCopy.malloc(list.size());
            try {
                for (int i = 0; i < list.size(); i++) {
                    var e = list.get(i);
                    regions.get(i).set(e.uploadOffset(), e.targetOffset(), e.size());
                }
                vkCmdCopyBuffer(cmd, this.uploadBuffer.buffer, group.getKey().buffer, regions);
            } finally {
                regions.free();
            }
        }
        VkBarriers.transferToAll(cmd);
    }

    @Override
    protected IGpuFence createFence() {
        return new VkGpuFence();
    }

    @Override
    protected void finish() {
        //Can only wait for submissions minecraft has already made, the current one can not complete before
        // minecraft submits it
        for (var fence : this.pendingFences()) {
            if (!((VkGpuFence) fence).await()) {
                break;
            }
        }
    }
}
