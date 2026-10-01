package me.cortex.voxy.client.core.vulkan;

import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.vulkan.VkBufferCopy;

import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TreeMap;

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
        VkBarriers.allToTransfer(cmd);
        //GL executes the copies one after another, vulkan executes the regions of one vkCmdCopyBuffer in any
        // order, so copies are grouped per destination into batches without overlapping destination ranges.
        // A copy overlapping an earlier one in the current batch starts a new batch after a barrier.
        var batches = new IdentityHashMap<VkGpuBuffer, List<UploadData>>();
        var ranges = new IdentityHashMap<VkGpuBuffer, TreeMap<Long, Long>>();
        for (var entry : uploads) {
            entry.target().assertNotFreed();
            var dst = (VkGpuBuffer) entry.target();
            var used = ranges.computeIfAbsent(dst, k -> new TreeMap<>());
            long start = entry.targetOffset();
            long end = start + entry.size();
            var below = used.floorEntry(start);
            var above = used.ceilingEntry(start);
            boolean overlaps = (below != null && below.getValue() > start) || (above != null && above.getKey() < end);
            if (overlaps) {
                this.recordBatches(cmd, batches);
                batches.clear();
                ranges.clear();
                VkBarriers.barrier(cmd, org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
                used = ranges.computeIfAbsent(dst, k -> new TreeMap<>());
            }
            used.put(start, end);
            batches.computeIfAbsent(dst, k -> new ArrayList<>()).add(entry);
        }
        this.recordBatches(cmd, batches);
        VkBarriers.transferToAll(cmd);
    }

    private void recordBatches(org.lwjgl.vulkan.VkCommandBuffer cmd, IdentityHashMap<VkGpuBuffer, List<UploadData>> batches) {
        for (var group : batches.entrySet()) {
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
