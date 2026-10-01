package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

//Index buffer contents shared by the GL SharedIndexBuffer and the vulkan VkSharedIndexBuffers (no gpu objects here)
public final class IndexData {
    public static MemoryBuffer generateCubeIndexBuffer() {
        var buffer = new MemoryBuffer(6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, 6*2*3 );

        //Bottom face
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 1);

        //top face
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        //north face
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 4);

        //south face
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);

        //west face
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 0);
        MemoryUtil.memPutByte(ptr++, (byte) 4);
        MemoryUtil.memPutByte(ptr++, (byte) 2);
        MemoryUtil.memPutByte(ptr++, (byte) 6);

        //east face
        MemoryUtil.memPutByte(ptr++, (byte) 1);
        MemoryUtil.memPutByte(ptr++, (byte) 5);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 7);
        MemoryUtil.memPutByte(ptr++, (byte) 3);
        MemoryUtil.memPutByte(ptr++, (byte) 5);

        return buffer;
    }

    public static MemoryBuffer generateByteCubesIndexBuffer(int cnt) {
        var buffer = new MemoryBuffer((long) cnt *6*2*3);
        long ptr = buffer.address;
        MemoryUtil.memSet(ptr, 0, buffer.size);

        for (int i = 0; i < cnt; i++) {
            int j = i*8;

            //Bottom face
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));

            //top face
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            //north face
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));

            //south face
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));

            //west face
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (0+j));
            MemoryUtil.memPutByte(ptr++, (byte) (4+j));
            MemoryUtil.memPutByte(ptr++, (byte) (2+j));
            MemoryUtil.memPutByte(ptr++, (byte) (6+j));

            //east face
            MemoryUtil.memPutByte(ptr++, (byte) (1+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (7+j));
            MemoryUtil.memPutByte(ptr++, (byte) (3+j));
            MemoryUtil.memPutByte(ptr++, (byte) (5+j));
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesByte(int quadCount) {
        if ((quadCount*4) >= 1<<8) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutByte(ptr + (0), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (1), (byte) (i + 2));
            MemoryUtil.memPutByte(ptr + (2), (byte) (i + 0));
            MemoryUtil.memPutByte(ptr + (3), (byte) (i + 1));
            MemoryUtil.memPutByte(ptr + (4), (byte) (i + 3));
            MemoryUtil.memPutByte(ptr + (5), (byte) (i + 2));

            ptr += 6;
        }

        return buffer;
    }
    public static MemoryBuffer generateQuadIndicesShort(int quadCount) {
        if ((quadCount*4) >= 1<<16) {
            throw new IllegalArgumentException("Quad count to large");
        }
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutShort(ptr + (0*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (1*2), (short) (i + 2));
            MemoryUtil.memPutShort(ptr + (2*2), (short) (i + 0));
            MemoryUtil.memPutShort(ptr + (3*2), (short) (i + 1));
            MemoryUtil.memPutShort(ptr + (4*2), (short) (i + 3));
            MemoryUtil.memPutShort(ptr + (5*2), (short) (i + 2));

            ptr += 6 * 2;
        }

        return buffer;
    }

    public static MemoryBuffer generateQuadIndicesInt(int quadCount) {
        MemoryBuffer buffer = new MemoryBuffer(quadCount * 6L * 2);
        long ptr = buffer.address;
        for(int i = 0; i < quadCount*4; i += 4) {
            MemoryUtil.memPutInt(ptr + (0*4), i);
            MemoryUtil.memPutInt(ptr + (1*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (2*4), (i + 2));
            MemoryUtil.memPutInt(ptr + (3*4), (i + 1));
            MemoryUtil.memPutInt(ptr + (4*4), (i + 3));
            MemoryUtil.memPutInt(ptr + (5*4), (i + 2));
            ptr += 6 * 4;
        }
        return buffer;
    }


    //Widens 8 bit indices to 16 bit (vulkan has no uint8 index type without VK_EXT_index_type_uint8)
    public static MemoryBuffer widenBytesToShorts(MemoryBuffer bytes) {
        var out = new MemoryBuffer(bytes.size*2);
        for (long i = 0; i < bytes.size; i++) {
            MemoryUtil.memPutShort(out.address+i*2, (short) (MemoryUtil.memGetByte(bytes.address+i)&0xFF));
        }
        return out;
    }

    private IndexData() {}
}
