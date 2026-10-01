package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.backend.IGpuFence;
import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlFence;
import me.cortex.voxy.client.core.gl.GlPersistentMappedBuffer;

import java.util.Deque;

import static org.lwjgl.opengl.GL11.glFinish;
import static org.lwjgl.opengl.GL30C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL42.GL_BUFFER_UPDATE_BARRIER_BIT;
import static org.lwjgl.opengl.GL42.glMemoryBarrier;
import static org.lwjgl.opengl.GL44.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
import static org.lwjgl.opengl.GL45.glCopyNamedBufferSubData;

//OpenGL download stream: glCopyNamedBufferSubData into a persistently mapped buffer
public class GlDownloadStream extends DownloadStream {
    private final GlPersistentMappedBuffer downloadBuffer;

    public GlDownloadStream(long size) {
        super(size);
        this.downloadBuffer = new GlPersistentMappedBuffer(size, GL_MAP_READ_BIT);//|GL_MAP_COHERENT_BIT
    }

    @Override
    protected long getBaseAddress() {
        return this.downloadBuffer.addr();
    }

    @Override
    protected void executeCopies(Deque<DownloadData> downloads) {
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        //Copies all the data from target buffers into the download stream
        for (var entry : downloads) {
            glCopyNamedBufferSubData(((GlBuffer)entry.target()).id, this.downloadBuffer.id, entry.targetOffset(), entry.downloadStreamOffset(), entry.size());
        }
        glMemoryBarrier(GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
    }

    @Override
    protected IGpuFence createFence() {
        return new GlFence();
    }

    @Override
    protected void invalidate(long offset, long size) {
        //Persistent mapping, made visible by GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT + the fence
    }

    @Override
    protected void finish() {
        glFinish();
    }
}
