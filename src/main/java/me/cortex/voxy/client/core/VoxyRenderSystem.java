package me.cortex.voxy.client.core;

import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.backend.GpuBackend;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.vulkan.VkVoxyRenderSystem;
import me.cortex.voxy.common.thread.ServiceManager;
import me.cortex.voxy.common.world.WorldEngine;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;

//Backend independent face of voxy's renderer, GlVoxyRenderSystem (OpenGL) and VkVoxyRenderSystem (Vulkan)
// are selected by minecraft's active graphics api
public abstract class VoxyRenderSystem {
    protected final WorldEngine worldIn;
    protected final RenderProperties properties;
    public final StreamedBoundStore visbleSectionStream;

    protected VoxyRenderSystem(WorldEngine world) {
        //Keep the world loaded, NOTE: this is done FIRST, to keep and ensure that even if the rest of loading takes more
        // than timeout, we keep the world acquired
        world.acquireRef();
        this.worldIn = world;
        this.properties = RenderProperties.getRenderProperties();
        this.visbleSectionStream = new StreamedBoundStore();
    }

    public static VoxyRenderSystem create(WorldEngine world, ServiceManager sm) {
        if (GpuBackend.isVulkan()) {
            return new VkVoxyRenderSystem(world, sm);
        }
        return new GlVoxyRenderSystem(world, sm);
    }

    public abstract Viewport<?> setupViewport(Matrix4fc vanillaProjection, Matrix4fc modelView, FogParameters fogParameters, int width, int height, double cameraX, double cameraY, double cameraZ);

    public abstract Viewport<?> getViewport();

    public abstract void setRenderDistance(float renderDistance);

    public abstract void addDebugInfo(List<String> debug);

    public abstract void shutdown();

    public WorldEngine getEngine() {
        return this.worldIn;
    }

    public static float getVanillaRenderDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance()*16;
    }

    protected static Matrix4f computeProjectionMat(RenderProperties properties, Matrix4fc base, float farPlane) {

        //this jank is to capture the extra crap they inject like viewbobbing
        var rawMCProj = Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix;
        var extraProjection = rawMCProj.invert(new Matrix4f()).mul(base);

        float near = getVanillaRenderDistance()<=32.0f?8f:16f;
        near = VoxyClient.disableSodiumChunkRender()?0.1f:near;

        float far = farPlane;

        //Flip near and far on reverse depth
        if (properties.isReverseZ()) {
            float tmp = near;
            near = far;
            far = tmp;
        }

        return extraProjection.mulLocal(
                new Matrix4f(rawMCProj)
                .m22((properties.isZero2One()?far:(far+near)) / (near - far))
                .m32((properties.isZero2One()?far:(far+far)) * near / (near - far))
        );
    }
}
