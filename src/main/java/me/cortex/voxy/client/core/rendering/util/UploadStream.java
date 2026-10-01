package me.cortex.voxy.client.core.rendering.util;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.backend.GpuBackend;
import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.vulkan.VkUploadStream;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import me.cortex.voxy.common.util.MemoryBuffer;

import java.util.ArrayDeque;
import java.util.Deque;

import static me.cortex.voxy.common.util.AllocationArena.SIZE_LIMIT;

//Backend independent part of the upload stream (allocation and frame retirement), the backend specific gpu
// operations are implemented by GlUploadStream and VkUploadStream
public abstract class UploadStream {
    public static final int BASE_ALLOCATION_ALIGNEMENT = Math.max(Capabilities.INSTANCE.ssboBindingAlignment, 16);

    private final AllocationArena allocationArena = new AllocationArena();
    private final long size;

    private final Deque<UploadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    protected final Deque<UploadData> uploadList = new ArrayDeque<>();

    protected UploadStream(long size) {
        this.size = size;
        this.allocationArena.setLimit(size);
    }

    //Host address of the start of the mapped upload buffer
    public abstract long getBaseAddress();
    //Make cpu writes in the range visible to the gpu
    protected abstract void flushRange(long offset, long size);
    //Execute (or record) every copy in the upload list, including the required barriers
    protected abstract void executeCopies(Deque<UploadData> uploads);
    protected abstract IGpuFence createFence();
    //Block until (as much as possible of) the gpu work using the upload buffer has completed
    protected abstract void finish();

    private long caddr = -1;
    private long offset = 0;
    public void upload(IGpuBuffer buffer, long destOffset, MemoryBuffer data) {//Note: does not free data, nor does it commit
        data.cpyTo(this.upload(buffer, destOffset, data.size));
    }

    public long uploadTo(IGpuBuffer buffer) {
        return this.upload(buffer, 0, buffer.size());
    }

    public long upload(IGpuBuffer buffer, long destOffset, long size) {
        long addr = this.rawUploadAddress((int) size);

        this.uploadList.add(new UploadData(buffer, addr, destOffset, size));

        return this.getBaseAddress() + addr;
    }

    public long rawUpload(int size) {
        return this.getBaseAddress() + this.rawUploadAddress(size);
    }

    public long rawUploadAddress(int size) {
        if (size < 0) {
            throw new IllegalStateException("Negative size");
        }

        //Force natural size alignment, this should ensure that _all_ allocations are aligned to this size, note, this only effects the allocation block
        // not how much data is moved or copied
        size = alignUp(size, BASE_ALLOCATION_ALIGNEMENT);
        //size = (size+15)&~15;//Alignment to 16 bytes

        if (size > this.size) {
            throw new IllegalArgumentException();
        }

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            if (this.caddr!=-1) {
                this.flushRange(this.caddr, this.offset);
            }
            this.caddr = this.allocationArena.alloc((int) size);//TODO: replace with allocFromLargest
            if (this.caddr == SIZE_LIMIT) {
                //Note! we dont commit here, we only try to flush existing memory copies, we dont commit
                // since commit is an explicit op saying we are done any to push upload everything
                //We dont commit since we dont want to invalidate existing upload pointers
                Logger.error("Upload stream full, preemptively committing, this could cause bad things to happen");
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    this.finish();
                    this.tick(false);
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

        return addr;
    }

    public void commit() {
        if (this.caddr != -1) {
            //Flush this allocation
            this.flushRange(this.caddr, this.offset);
        }

        if (this.uploadList.isEmpty()) {
            return;
        }

        //Execute all the copies
        this.executeCopies(this.uploadList);
        this.uploadList.clear();

        this.caddr = -1;
        this.offset = 0;
    }

    public void tick() {
        this.tick(true);
    }
    private void tick(boolean commit) {
        if (commit) {
            this.commit();
        }

        if (!this.thisFrameAllocations.isEmpty()) {
            this.frames.add(new UploadFrame(this.createFence(), new LongArrayList(this.thisFrameAllocations)));
            this.thisFrameAllocations.clear();
        }

        while (!this.frames.isEmpty()) {
            //Since the ordering of frames is the ordering of the gl commands if we encounter an unsignaled fence
            // all the other fences should also be unsignaled
            if (!this.frames.peek().fence.signaled()) {
                break;
            }
            //Release all the allocations from the frame
            var frame = this.frames.pop();
            frame.allocations.forEach(this.allocationArena::free);
            frame.fence.free();
        }
    }

    protected Iterable<IGpuFence> pendingFences() {
        return this.frames.stream().map(UploadFrame::fence).toList();
    }

    private record UploadFrame(IGpuFence fence, LongArrayList allocations) {}
    public record UploadData(IGpuBuffer target, long uploadOffset, long targetOffset, long size) {}

    //A upload instance instead of passing one around by reference
    // MUST ONLY BE USED ON THE RENDER THREAD
    public static final UploadStream INSTANCE = GpuBackend.isVulkan()?new VkUploadStream(1<<26):new GlUploadStream(1<<26);//64 mb upload buffer

    public static long alignUp(long val, long alignment) {
        return ((val+alignment-1)/alignment)*alignment;
    }
    public static int alignUp(int val, int alignment) {
        return ((val+alignment-1)/alignment)*alignment;
    }
    public static int alignUpAlloc(int val) {
        return ((val+BASE_ALLOCATION_ALIGNEMENT-1)/BASE_ALLOCATION_ALIGNEMENT)*BASE_ALLOCATION_ALIGNEMENT;
    }
}
