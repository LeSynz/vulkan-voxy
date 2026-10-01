package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.backend.GpuBackend;
import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.vulkan.VkDownloadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.function.Consumer;

import static me.cortex.voxy.common.util.AllocationArena.SIZE_LIMIT;

//Backend independent part of the download stream, gpu operations are implemented by GlDownloadStream and VkDownloadStream
public abstract class DownloadStream {
    public interface DownloadResultConsumer {
        void consume(long ptr, long size);
    }

    private final AllocationArena allocationArena = new AllocationArena();
    private final long size;

    private final Deque<DownloadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<DownloadData> downloadList = new ArrayDeque<>();
    private final ArrayList<DownloadData> thisFrameDownloadList = new ArrayList<>();

    protected DownloadStream(long size) {
        this.size = size;
        this.allocationArena.setLimit(size);
    }

    //Host address of the start of the mapped download buffer
    protected abstract long getBaseAddress();
    //Execute (or record) the copies from the target buffers into the download buffer, including barriers
    protected abstract void executeCopies(Deque<DownloadData> downloads);
    protected abstract IGpuFence createFence();
    //Make gpu writes to the range visible to the cpu
    protected abstract void invalidate(long offset, long size);
    //Block until (as much as possible of) the outstanding gpu work has completed
    protected abstract void finish();

    private long caddr = -1;
    private long offset = 0;

    //Pulls the entire buffer from the gpu
    public void download(IGpuBuffer buffer, DownloadResultConsumer resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(IGpuBuffer buffer, Consumer<MemoryBuffer> resultConsumer) {
        this.download(buffer, 0, buffer.size(), resultConsumer);
    }

    public void download(IGpuBuffer buffer, long downloadOffset, long size, Consumer<MemoryBuffer> consumer) {
        this.download(buffer, downloadOffset, size, (ptr,size2)-> {
            consumer.accept(MemoryBuffer.createUntrackedUnfreeableRawFrom(ptr, size));
        });
    }

    public void download(IGpuBuffer buffer, long downloadOffset, long size, DownloadResultConsumer resultConsumer) {
        if (size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException();
        }
        if (size <= 0) {
            throw new IllegalArgumentException();
        }
        if (downloadOffset+size > buffer.size()) {
            throw new IllegalArgumentException();
        }

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            this.caddr = this.allocationArena.alloc((int) size);//TODO: replace with allocFromLargest
            if (this.caddr == SIZE_LIMIT) {
                Logger.warn("Download stream full, preemptively committing, this could cause bad things to happen");
                this.commit();
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    this.finish();
                    this.tick();
                    this.caddr = this.allocationArena.alloc((int) size);
                }
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate memory segment big enough for upload even after force flush");
                }
            }
            this.thisFrameAllocations.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
        } else {//Could expand the allocation so just update it
            addr = this.caddr + this.offset;
            this.offset += size;
        }

        if (this.caddr + size > this.size) {
            throw new IllegalStateException();
        }

        this.downloadList.add(new DownloadData(buffer, addr, downloadOffset, size, resultConsumer));

        //TODO: maybe not auto-commit
        this.commit();
    }


    public void commit() {
        if (this.downloadList.isEmpty()) {
            return;
        }
        //Copies all the data from target buffers into the download stream
        this.executeCopies(this.downloadList);
        this.thisFrameDownloadList.addAll(this.downloadList);
        this.downloadList.clear();

        this.caddr = -1;
        this.offset = 0;
    }

    public void tick() {
        this.commit();
        if (!this.thisFrameAllocations.isEmpty()) {
            this.frames.add(new DownloadFrame(this.createFence(), new LongArrayList(this.thisFrameAllocations), new ArrayList<>(this.thisFrameDownloadList)));
            this.thisFrameAllocations.clear();
            this.thisFrameDownloadList.clear();
        }

        while (!this.frames.isEmpty()) {
            //Since the ordering of frames is the ordering of the gl commands if we encounter an unsignaled fence
            // all the other fences should also be unsignaled
            if (!this.frames.peek().fence.signaled()) {
                break;
            }

            //Release all the allocations from the frame
            var frame = this.frames.pop();

            //Apply all the callbacks
            for (var data : frame.data) {
                this.invalidate(data.downloadStreamOffset, data.size);
                data.resultConsumer.consume(this.getBaseAddress() + data.downloadStreamOffset, data.size);
            }

            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    //Synchonize force flushes everything
    public void waitDiscard() {
        this.finish();
        var fence = this.createFence();
        this.finish();
        while (!fence.signaled())
            Thread.onSpinWait();
        fence.free();
        while (!this.frames.isEmpty()) {
            var frame = this.frames.pop();
            while (!frame.fence.signaled()) Thread.onSpinWait();
            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    public void flushWaitClear() {
        this.finish();
        this.tick();
        var fence = this.createFence();
        this.finish();
        while (!fence.signaled()) {
            this.finish();
            Thread.onSpinWait();
        }
        fence.free();
        this.tick();
        if (!this.frames.isEmpty()) {
            throw new IllegalStateException();
        }
    }

    //Drops the callbacks of every frame that has not completed yet, their memory is released once they complete.
    // Used when the gpu work can not be waited for (vulkan, work still in the current submission)
    protected void discardPendingCallbacks() {
        for (var frame : this.frames) {
            frame.data.clear();
        }
        for (var data : this.thisFrameDownloadList) {
            Logger.warn("Discarding download that could not complete before shutdown");
        }
        this.thisFrameDownloadList.clear();
    }

    protected boolean hasPendingFrames() {
        return !this.frames.isEmpty();
    }

    protected Iterable<IGpuFence> pendingFences() {
        return this.frames.stream().map(DownloadFrame::fence).toList();
    }

    private record DownloadFrame(IGpuFence fence, LongArrayList allocations, ArrayList<DownloadData> data) {}
    public record DownloadData(IGpuBuffer target, long downloadStreamOffset, long targetOffset, long size, DownloadResultConsumer resultConsumer) {}


    // Global download stream
    public static final DownloadStream INSTANCE = GpuBackend.isVulkan()?new VkDownloadStream(1<<25):new GlDownloadStream(1<<25);//32 mb download buffer
}
