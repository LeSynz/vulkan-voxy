package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;

import java.util.Deque;

import static org.lwjgl.opengl.ARBDirectStateAccess.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.ARBMapBufferRange.*;
import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL42C.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT;
import static org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
import static org.lwjgl.opengl.GL45C.glFlushMappedNamedBufferRange;

//OpenGL upload stream: persistently mapped buffer + glCopyNamedBufferSubData
public class GlUploadStream extends UploadStream {
    private final GlPersistentMappedBuffer uploadBuffer;

    private static final boolean USE_COHERENT = false;

    public GlUploadStream(long size) {
        super(size);
        this.uploadBuffer = new GlPersistentMappedBuffer(size,GL_CLIENT_STORAGE_BIT|GL_MAP_WRITE_BIT|GL_MAP_UNSYNCHRONIZED_BIT|(USE_COHERENT?GL_MAP_COHERENT_BIT:GL_MAP_FLUSH_EXPLICIT_BIT)).name("UploadStream");
    }

    @Override
    public long getBaseAddress() {
        return this.uploadBuffer.addr();
    }

    @Override
    protected void flushRange(long offset, long size) {
        if (!USE_COHERENT) {
            glFlushMappedNamedBufferRange(this.uploadBuffer.id, offset, size);
        }
    }

    @Override
    protected void executeCopies(Deque<UploadData> uploads) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        //Execute all the copies
        for (var entry : uploads) {
            glCopyNamedBufferSubData(this.uploadBuffer.id, ((GlBuffer)entry.target()).id, entry.uploadOffset(), entry.targetOffset(), entry.size());
        }
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);//|GL_SHADER_STORAGE_BARRIER_BIT|GL_UNIFORM_BARRIER_BIT //expected + other barriers which may cause issues if not
    }

    @Override
    protected IGpuFence createFence() {
        return new GlFence();
    }

    @Override
    protected void finish() {
        glFinish();
    }

    public int getRawBufferId() {
        return this.uploadBuffer.id;
    }

    //GL only accessor for code paths that bind ranges of the upload buffer directly
    public static GlUploadStream gl() {
        return (GlUploadStream) INSTANCE;
    }
}
