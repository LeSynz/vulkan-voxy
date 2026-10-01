package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;


//Has a base index buffer of 16380 quads, and also a 1 cube byte index buffer at the end
public class SharedIndexBuffer {
    public static final int CUBE_INDEX_OFFSET = (1<<16)*6*2;
    public static final SharedIndexBuffer INSTANCE = new SharedIndexBuffer();
    public static final SharedIndexBuffer INSTANCE_BYTE = new SharedIndexBuffer(true);
    public static final SharedIndexBuffer INSTANCE_BB_BYTE = new SharedIndexBuffer(true, true);

    private final GlBuffer indexBuffer;

    public SharedIndexBuffer() {
        this.indexBuffer = new GlBuffer((1<<16)*6*2 + 6*2*3);
        var quadIndexBuff = generateQuadIndicesShort(16380);
        var cubeBuff = generateCubeIndexBuffer();

        long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size());
        quadIndexBuff.cpyTo(ptr);
        cubeBuff.cpyTo((1<<16)*2*6 + ptr);

        quadIndexBuff.free();
        cubeBuff.free();
        UploadStream.INSTANCE.commit();
    }

    private SharedIndexBuffer(boolean type2) {
        this.indexBuffer = new GlBuffer((1<<8)*6 + 6*2*3);
        var quadIndexBuff = generateQuadIndicesByte(63);
        var cubeBuff = generateCubeIndexBuffer();

        long ptr = UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size());
        quadIndexBuff.cpyTo(ptr);
        cubeBuff.cpyTo((1<<8)*6 + ptr);

        quadIndexBuff.free();
        cubeBuff.free();
    }

    private SharedIndexBuffer(boolean type2, boolean type3) {
        this.indexBuffer = new GlBuffer(6*2*3*(256/8));
        var cubeBuff = generateByteCubesIndexBuffer(256/8);

        cubeBuff.cpyTo(UploadStream.INSTANCE.upload(this.indexBuffer, 0, this.indexBuffer.size()));
        UploadStream.INSTANCE.commit();
        cubeBuff.free();
    }

    private static MemoryBuffer generateCubeIndexBuffer() {
        return IndexData.generateCubeIndexBuffer();
    }

    private static MemoryBuffer generateByteCubesIndexBuffer(int cnt) {
        return IndexData.generateByteCubesIndexBuffer(cnt);
    }

    public static MemoryBuffer generateQuadIndicesByte(int quadCount) {
        return IndexData.generateQuadIndicesByte(quadCount);
    }

    public static MemoryBuffer generateQuadIndicesShort(int quadCount) {
        return IndexData.generateQuadIndicesShort(quadCount);
    }

    public static MemoryBuffer generateQuadIndicesInt(int quadCount) {
        return IndexData.generateQuadIndicesInt(quadCount);
    }

    public int id() {
        return this.indexBuffer.id;
    }
}
