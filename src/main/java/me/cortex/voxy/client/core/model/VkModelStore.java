package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import static org.lwjgl.vulkan.VK10.*;

//Vulkan model store: device local buffers + RGBA8 atlas image, texture uploads are staged through the upload
// stream and copied with vkCmdCopyBufferToImage
public class VkModelStore extends ModelStore {
    public final VkImage2D textures;
    public final long blockSampler;

    public VkModelStore() {
        super(new VkGpuBuffer(MODEL_SIZE * (1<<16)).name("ModelData"), new VkGpuBuffer(4 * (1<<16)).name("ModelColour"));
        this.textures = new VkImage2D(VK_FORMAT_R8G8B8A8_UNORM,
                Integer.numberOfTrailingZeros(ModelFactory.MODEL_TEXTURE_SIZE),
                ModelFactory.MODEL_TEXTURE_SIZE*3*256,
                ModelFactory.MODEL_TEXTURE_SIZE*2*256).name("ModelTextures").zero();

        //Limit the mips of the texture to match that of the terrain atlas
        int mipLvl = ((TextureAtlas) Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png")))
                .maxMipLevel;
        //GL_NEAREST_MIPMAP_LINEAR / GL_NEAREST, GL default wrap (repeat), lod 0..mipLvl
        this.blockSampler = VkSamplers.get(VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_LINEAR, false, mipLvl);
    }

    public VkGpuBuffer modelBuffer() {
        return (VkGpuBuffer) this.modelBuffer;
    }

    public VkGpuBuffer modelColourBuffer() {
        return (VkGpuBuffer) this.modelColourBuffer;
    }

    @Override
    public void beginTextureUploads() {
        VkBarriers.allToTransfer(VkContext.get().stream.cmd());
    }

    @Override
    public void uploadTexture(int level, int x, int y, int width, int height, long addr) {
        int size = width*height*4;
        long offset = UploadStream.INSTANCE.rawUploadAddress(size);
        MemoryUtil.memCopy(addr, UploadStream.INSTANCE.getBaseAddress() + offset, size);
        try (var stack = MemoryStack.stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(offset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.get(0).imageSubresource().set(VK_IMAGE_ASPECT_COLOR_BIT, level, 0, 1);
            region.get(0).imageOffset().set(x, y, 0);
            region.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(VkContext.get().stream.cmd(), ((VkUploadStream) UploadStream.INSTANCE).getBuffer(),
                    this.textures.image, VK_IMAGE_LAYOUT_GENERAL, region);
        }
    }

    @Override
    public void endTextureUploads() {
        VkBarriers.transferToAll(VkContext.get().stream.cmd());
    }

    @Override
    public void free() {
        this.modelBuffer.free();
        this.modelColourBuffer.free();
        this.textures.free();
    }
}
