package me.cortex.voxy.client.core.vulkan;

import com.mojang.renderpearl.api.commands.GpuFence;
import me.cortex.voxy.client.core.backend.IGpuFence;

//Fence on minecraft's submit timeline semaphore. Covers everything recorded (by minecraft or by voxy through
// VkCommandStream) into the current submission up to its creation.
public class VkGpuFence implements IGpuFence {
    private final GpuFence fence;
    private boolean signaled;

    public VkGpuFence() {
        this.fence = VkContext.get().createFence();
    }

    @Override
    public boolean signaled() {
        if (!this.signaled) {
            this.signaled = this.fence.awaitCompletion(0);
        }
        return this.signaled;
    }

    //Blocks until signaled. Returns false if the fence belongs to the submission that is still being recorded
    // (it can never signal before minecraft submits, so waiting would dead lock)
    public boolean await() {
        if (this.signaled) {
            return true;
        }
        try {
            this.signaled = this.fence.awaitCompletion(GpuFence.NO_TIMEOUT);
        } catch (IllegalStateException e) {
            return false;
        }
        return this.signaled;
    }

    @Override
    public void free() {
        //Nothing to release, the fence is just a timeline value
    }
}
