package me.cortex.voxy.opt;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.ReferenceRenderDataFactory;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

//Meshing of real world sections with the real model tables must match the original exactly (skipped without a dump)
public class RealMeshingIdentityTest {
    @Test
    public void realSectionsAreIdentical() {
        var real = RealData.loadOrNull();
        Assumptions.assumeTrue(real != null, "no real data dump (VOXY_REAL_DATA)");
        var sc = new MeshScenario(real);
        var current = new RenderDataFactory(sc.world, sc.models.factory, false);
        var reference = new ReferenceRenderDataFactory(sc.world, sc.models.factory, false);
        int quads = 0, compared = 0, thrown = 0;
        for (int i = 0; i < sc.positions.size(); i++) {
            var section = sc.acquire(i);
            BuiltSection a = null, b = null;
            Throwable ea = null, eb = null;
            try { a = current.generateMesh(section); } catch (Throwable t) { ea = t; }
            try { b = reference.generateMesh(section); } catch (Throwable t) { eb = t; }
            section.release();
            if (ea != null || eb != null) {
                assertNotNull(ea);
                assertNotNull(eb);
                assertEquals(eb.getClass(), ea.getClass());
                thrown++;
                continue;
            }
            assertEquals(b.childExistence, a.childExistence);
            assertEquals(b.aabb, a.aabb);
            assertArrayEquals(b.offsets, a.offsets);
            if (b.geometryBuffer == null) {
                assertNull(a.geometryBuffer);
            } else {
                assertEquals(b.geometryBuffer.size, a.geometryBuffer.size);
                for (long o = 0; o < b.geometryBuffer.size; o += 8) {
                    assertEquals(org.lwjgl.system.MemoryUtil.memGetLong(b.geometryBuffer.address + o), org.lwjgl.system.MemoryUtil.memGetLong(a.geometryBuffer.address + o), "quad at " + o + " of section " + i);
                }
                quads += (int) (b.geometryBuffer.size / 8);
                a.free();
                b.free();
            }
            compared++;
        }
        System.out.println("real meshing identity: " + compared + " sections compared, " + quads + " quads, " + thrown + " threw in both");
        assertTrue(compared > 0);
    }
}
