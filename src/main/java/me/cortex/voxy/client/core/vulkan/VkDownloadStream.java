package me.cortex.voxy.client.core.vulkan;

import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.rendering.util.DownloadStream;
import org.lwjgl.vulkan.VkBufferCopy;

import java.util.Deque;

import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

//Vulkan download stream: vkCmdCopyBuffer into a host cached mapped buffer, results delivered once minecraft's
// submission containing the copy has completed
public class VkDownloadStream extends DownloadStream {
    private final VkMappedBuffer downloadBuffer;

    public VkDownloadStream(long size) {
        super(size);
        this.downloadBuffer = new VkMappedBuffer(size, false).name("DownloadStream");
    }

    @Override
    protected long getBaseAddress() {
        return this.downloadBuffer.addr();
    }

    @Override
    protected void executeCopies(Deque<DownloadData> downloads) {
        var cmd = VkContext.get().stream.cmd();
        VkBarriers.allToTransfer(cmd);
        var region = VkBufferCopy.calloc(1);
        try {
            for (var entry : downloads) {
                entry.target().assertNotFreed();
                region.get(0).set(entry.targetOffset(), entry.downloadStreamOffset(), entry.size());
                vkCmdCopyBuffer(cmd, ((VkGpuBuffer) entry.target()).buffer, this.downloadBuffer.buffer, region);
            }
        } finally {
            region.free();
        }
        VkBarriers.transferToHost(cmd);
        //Later commands may overwrite the source buffers
        VkBarriers.transferToAll(cmd);
    }

    @Override
    protected IGpuFence createFence() {
        return new VkGpuFence();
    }

    @Override
    protected void invalidate(long offset, long size) {
        this.downloadBuffer.invalidate(offset, size);
    }

    @Override
    protected void finish() {
        for (var fence : this.pendingFences()) {
            if (!((VkGpuFence) fence).await()) {
                break;
            }
        }
    }

    //On vulkan voxy can not force minecraft to submit, so anything recorded into the current submission can not
    // be waited on. Process everything that can complete and drop the callbacks of the rest.
    @Override
    public void flushWaitClear() {
        VkContext.get().stream.flush();
        this.commit();
        this.finish();
        this.tick();
        this.discardPendingCallbacks();
    }

    @Override
    public void waitDiscard() {
        this.finish();
        this.discardPendingCallbacks();
    }
}
