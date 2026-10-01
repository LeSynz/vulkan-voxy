package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.backend.GpuBackend;
import me.cortex.voxy.client.core.backend.IGpuBuffer;

//Model data/colour buffers and the model texture atlas, GlModelStore and VkModelStore implement the gpu side
public abstract class ModelStore {
    public static final int MODEL_SIZE = 64;

    final IGpuBuffer modelBuffer;
    final IGpuBuffer modelColourBuffer;

    protected ModelStore(IGpuBuffer modelBuffer, IGpuBuffer modelColourBuffer) {
        this.modelBuffer = modelBuffer;
        this.modelColourBuffer = modelColourBuffer;
    }

    public static ModelStore create() {
        return GpuBackend.isVulkan() ? new VkModelStore() : new GlModelStore();
    }

    public IGpuBuffer getModelBuffer() {
        return this.modelBuffer;
    }

    public IGpuBuffer getModelColourBuffer() {
        return this.modelColourBuffer;
    }

    public abstract void beginTextureUploads();
    //Uploads tightly packed RGBA8 texels into a region of a level of the model atlas
    public abstract void uploadTexture(int level, int x, int y, int width, int height, long addr);
    public abstract void endTextureUploads();

    public abstract void free();
}
