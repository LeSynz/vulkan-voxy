package me.cortex.voxy.client.core.vulkan.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vulkan.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.bounding.IBoundStore;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.core.vulkan.VkBarriers;
import me.cortex.voxy.client.core.vulkan.VkContext;
import me.cortex.voxy.client.core.vulkan.VkGpuBuffer;
import me.cortex.voxy.client.core.vulkan.VkRendering;
import me.cortex.voxy.client.core.vulkan.VkFormats;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsPipeline;
import me.cortex.voxy.client.core.vulkan.shader.VkGraphicsState;
import me.cortex.voxy.client.core.vulkan.shader.VkShaderBuilder;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.*;

//Vulkan port of BoundRenderer: rasterizes the back faces of the loaded vanilla chunk AABBs into the viewport's
// depth bounding buffer
public class VkBoundRenderer {
    private final VkGpuBuffer uniformBuffer = new VkGpuBuffer(128).name("BoundUniform");
    private final VkGraphicsPipeline rasterShader;
    private final RenderProperties properties;
    private final VkSharedIndexBuffers indices;

    public static VkShaderBuilder outlineShader(RenderProperties properties) {
        return VkShaderBuilder.make()
                .add(ShaderType.VERTEX, "voxy:chunkoutline/outline.vsh")
                .add(ShaderType.FRAGMENT, "voxy:chunkoutline/outline.fsh")
                .apply(properties::apply);
    }

    public VkBoundRenderer(RenderProperties properties, VkSharedIndexBuffers indices) {
        this.properties = properties;
        this.indices = indices;
        this.rasterShader = outlineShader(properties).compileGraphics(VkGraphicsState.create()
                .depthFormat(VkFormats.depth24())
                //need to reverse the winding order since we want the back faces of the AABB, not the front (GL: glFrontFace(GL_CW))
                .cullBackGlCW()
                .depthTest(properties.vkFurtherDepthCompare())
                .depthWrite(true));
        this.rasterShader.ubo(0, this.uniformBuffer);
    }

    public void render(VkViewport viewport, IBoundStore store) {
        store.preRender(viewport);
        int count = store.getCount();
        if (count == 0) {
            //Nothing to rasterize, just clear
            var depth = viewport.depthBounding.getImage();
            var cmd = VkContext.get().stream.cmd();
            VkRendering.begin(cmd, depth.width, depth.height, new long[0], depth.attachmentView(0), false, true, this.properties.inverseClearDepth(), 0);
            VkRendering.end(cmd);
            VkBarriers.attachmentToSampled(cmd);
            return;
        }
        this.rasterShader.ssbo(1, (VkGpuBuffer) store.getBuffer());
        final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance()*16;//In blocks
        this.renderInner(viewport, renderDistance, count);
        store.postRender(viewport);
    }

    private void renderInner(VkViewport viewport, float renderDistanceBlocks, int chunkCount) {
        long ptr = UploadStream.INSTANCE.upload(this.uniformBuffer, 0, 128);
        long matPtr = ptr; ptr += 4*4*4;
        {
            int bx = (int)Math.floor(viewport.cameraX);
            int by = (int)Math.floor(viewport.cameraY);
            int bz = (int)Math.floor(viewport.cameraZ);
            new Vector3i(bx, by, bz).getToAddress(ptr); ptr += 4*4;

            var negInnerBlock = new Vector3f(
                    (float) (viewport.cameraX - bx),
                    (float) (viewport.cameraY - by),
                    (float) (viewport.cameraZ - bz));
            negInnerBlock.getToAddress(ptr); ptr += 4*3;
            viewport.MVP.translate(negInnerBlock.negate(), new Matrix4f()).getToAddress(matPtr);
            MemoryUtil.memPutFloat(ptr, renderDistanceBlocks); ptr += 4;
        }
        UploadStream.INSTANCE.commit();

        var cmd = VkContext.get().stream.cmd();
        VkBarriers.shaderStorage(cmd);
        var depth = viewport.depthBounding.getImage();
        VkRendering.begin(cmd, depth.width, depth.height, new long[0], depth.attachmentView(0), false, true, this.properties.inverseClearDepth(), 0);
        this.rasterShader.bind(cmd);
        vkCmdBindIndexBuffer(cmd, this.indices.boundingBoxCubes.buffer, 0, VK_INDEX_TYPE_UINT16);
        //Batch the draws into groups of size 32
        int count = chunkCount;
        if (count >= 32) {
            vkCmdDrawIndexed(cmd, 6 * 2 * 3 * 32, count/32, 0, 0, 0);
        }
        if (count%32 != 0) {
            vkCmdDrawIndexed(cmd, 6 * 2 * 3 * (count%32), 1, 0, 0, (count/32)*32);
        }
        VkRendering.end(cmd);
        VkBarriers.attachmentToSampled(cmd);
    }

    public void free() {
        this.rasterShader.free();
        this.uniformBuffer.free();
    }
}
