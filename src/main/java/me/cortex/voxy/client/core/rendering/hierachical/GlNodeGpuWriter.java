package me.cortex.voxy.client.core.rendering.hierachical;

import me.cortex.voxy.client.core.backend.IGpuBuffer;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.shader.Shader;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.rendering.section.geometry.ISectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.GlUploadStream;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.UnsafeUtil;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase;
import static org.lwjgl.opengl.GL30C.glUniform1ui;
import static org.lwjgl.opengl.GL42C.GL_UNIFORM_BARRIER_BIT;
import static org.lwjgl.opengl.GL42C.glMemoryBarrier;
import static org.lwjgl.opengl.GL43C.*;

//OpenGL implementation (the original AsyncNodeManager code)
public class GlNodeGpuWriter implements INodeGpuWriter {
    private final Shader scatterWrite = Shader.make()
            .define("INPUT_BUFFER_BINDING", 0)
            .define("OUTPUT_BUFFER1_BINDING", 1)
            .define("OUTPUT_BUFFER2_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/scatter.comp")
            .compile();

    private final Shader multiMemcpy = Shader.make()
            .define("INPUT_HEADER_BUFFER_BINDING", 0)
            .define("INPUT_DATA_BUFFER_BINDING", 1)
            .define("OUTPUT_BUFFER_BINDING", 2)
            .add(ShaderType.COMPUTE, "voxy:util/memcpy.comp")
            .compile();

    @Override
    public void uploadGeometry(ISectionGeometryData geometry, long headerAddr, int copies, long dataAddr, int scratchSize) {
        int upCopies = UploadStream.alignUpAlloc(copies*16);
        int upScratchSize = UploadStream.alignUpAlloc(scratchSize);
        long ptr = UploadStream.INSTANCE.rawUploadAddress(upScratchSize + upCopies);
        UnsafeUtil.memcpy(headerAddr, UploadStream.INSTANCE.getBaseAddress() + ptr, copies * 16L);
        UnsafeUtil.memcpy(dataAddr, UploadStream.INSTANCE.getBaseAddress() + ptr + upCopies, scratchSize);
        UploadStream.INSTANCE.commit();//Commit the buffer

        this.multiMemcpy.bind();
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, GlUploadStream.gl().getRawBufferId(), ptr, upCopies);
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1, GlUploadStream.gl().getRawBufferId(), ptr+upCopies, upScratchSize);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((GlBuffer)geometry.getGeometryBuffer()).id);

        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute(copies, 1, 1);//Execute the copies
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public void scatterWrite(ISectionGeometryData geometry, IGpuBuffer nodeBuffer, long srcAddr, int count) {
        int chunks = (count+3)/4;
        int streamSize = chunks*80;//80 bytes per chunk, it is guaranteed the buffer is big enough
        long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);//Internally implicitly aligned alloc
        MemoryUtil.memCopy(srcAddr, UploadStream.INSTANCE.getBaseAddress() + ptr, streamSize);
        UploadStream.INSTANCE.commit();//Commit the buffer

        this.scatterWrite.bind();
        glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0, GlUploadStream.gl().getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, ((GlBuffer)nodeBuffer).id);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, ((GlBuffer)geometry.getMetadataBuffer()).id);
        glUniform1ui(0, count);
        glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        glDispatchCompute((count+127)/128, 1, 1);
        glMemoryBarrier(GL_UNIFORM_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
    }

    @Override
    public void free() {
        this.scatterWrite.free();
        this.multiMemcpy.free();
    }
}
