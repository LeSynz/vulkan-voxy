package me.cortex.voxy.client.core.vulkan.shader;

import static org.lwjgl.vulkan.VK10.*;

//Fixed function state baked into a graphics pipeline, replaces the glEnable/glDepthFunc/glBlendFunc/... calls
// voxy makes around each draw. Defaults match GL's defaults (everything disabled, colour writes on).
public final class VkGraphicsState {
    int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    int cullMode = VK_CULL_MODE_NONE;
    //Minecraft's vulkan backend keeps GL's memory layout (no y flip), which inverts the apparent winding, so
    // GL's default CCW front face is CLOCKWISE here (minecraft does the same)
    int frontFace = VK_FRONT_FACE_CLOCKWISE;
    int polygonMode = VK_POLYGON_MODE_FILL;

    boolean depthTest = false;
    boolean depthWrite = true;
    int depthCompare = VK_COMPARE_OP_LESS;

    boolean stencilTest = false;
    int stencilFailOp = VK_STENCIL_OP_KEEP;
    int stencilDepthFailOp = VK_STENCIL_OP_KEEP;
    int stencilPassOp = VK_STENCIL_OP_KEEP;
    int stencilCompare = VK_COMPARE_OP_ALWAYS;
    int stencilReference = 0;
    int stencilCompareMask = 0xFF;
    int stencilWriteMask = 0xFF;

    boolean blend = false;
    int srcColor = VK_BLEND_FACTOR_ONE;
    int dstColor = VK_BLEND_FACTOR_ZERO;
    int srcAlpha = VK_BLEND_FACTOR_ONE;
    int dstAlpha = VK_BLEND_FACTOR_ZERO;
    int colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

    int[] colorFormats = new int[0];
    int depthFormat = VK_FORMAT_UNDEFINED;

    public static VkGraphicsState create() {
        return new VkGraphicsState();
    }

    public VkGraphicsState colorFormats(int... formats) {
        this.colorFormats = formats.clone();
        return this;
    }

    //Depth (and stencil if the format has it) attachment format
    public VkGraphicsState depthFormat(int format) {
        this.depthFormat = format;
        return this;
    }

    public VkGraphicsState depthTest(int compareOp) {
        this.depthTest = true;
        this.depthCompare = compareOp;
        return this;
    }

    public VkGraphicsState depthWrite(boolean write) {
        this.depthWrite = write;
        return this;
    }

    //glStencilFunc(func, ref, mask) + glStencilOp(sfail, dpfail, dppass) + glStencilMask(writeMask)
    public VkGraphicsState stencil(int compareOp, int reference, int compareMask, int failOp, int depthFailOp, int passOp, int writeMask) {
        this.stencilTest = true;
        this.stencilCompare = compareOp;
        this.stencilReference = reference;
        this.stencilCompareMask = compareMask;
        this.stencilFailOp = failOp;
        this.stencilDepthFailOp = depthFailOp;
        this.stencilPassOp = passOp;
        this.stencilWriteMask = writeMask;
        return this;
    }

    public VkGraphicsState blend(int srcColor, int dstColor, int srcAlpha, int dstAlpha) {
        this.blend = true;
        this.srcColor = srcColor;
        this.dstColor = dstColor;
        this.srcAlpha = srcAlpha;
        this.dstAlpha = dstAlpha;
        return this;
    }

    public VkGraphicsState colorWriteMask(int mask) {
        this.colorWriteMask = mask;
        return this;
    }

    //GL_CULL_FACE with GL's (default CCW) front face, culling back faces
    public VkGraphicsState cullBackGlCCW() {
        this.cullMode = VK_CULL_MODE_BACK_BIT;
        this.frontFace = VK_FRONT_FACE_CLOCKWISE;
        return this;
    }

    //GL_CULL_FACE with glFrontFace(GL_CW), culling back faces
    public VkGraphicsState cullBackGlCW() {
        this.cullMode = VK_CULL_MODE_BACK_BIT;
        this.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        return this;
    }

    public VkGraphicsState wireframe() {
        this.polygonMode = VK_POLYGON_MODE_LINE;
        return this;
    }

    public VkGraphicsState topology(int topology) {
        this.topology = topology;
        return this;
    }

    public VkGraphicsState copy() {
        var c = new VkGraphicsState();
        c.topology = this.topology; c.cullMode = this.cullMode; c.frontFace = this.frontFace; c.polygonMode = this.polygonMode;
        c.depthTest = this.depthTest; c.depthWrite = this.depthWrite; c.depthCompare = this.depthCompare;
        c.stencilTest = this.stencilTest; c.stencilFailOp = this.stencilFailOp; c.stencilDepthFailOp = this.stencilDepthFailOp;
        c.stencilPassOp = this.stencilPassOp; c.stencilCompare = this.stencilCompare; c.stencilReference = this.stencilReference;
        c.stencilCompareMask = this.stencilCompareMask; c.stencilWriteMask = this.stencilWriteMask;
        c.blend = this.blend; c.srcColor = this.srcColor; c.dstColor = this.dstColor; c.srcAlpha = this.srcAlpha; c.dstAlpha = this.dstAlpha;
        c.colorWriteMask = this.colorWriteMask; c.colorFormats = this.colorFormats.clone(); c.depthFormat = this.depthFormat;
        return c;
    }
}
