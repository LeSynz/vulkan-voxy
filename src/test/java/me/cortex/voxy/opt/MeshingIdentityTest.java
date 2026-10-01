package me.cortex.voxy.opt;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.ReferenceRenderDataFactory;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

//RenderDataFactory.generateMesh must produce exactly the same BuiltSection as the original
public class MeshingIdentityTest {
    private static byte[] bytes(BuiltSection s) {
        if (s.geometryBuffer == null) return null;
        byte[] b = new byte[(int) s.geometryBuffer.size];
        for (int i = 0; i < b.length; i++) b[i] = org.lwjgl.system.MemoryUtil.memGetByte(s.geometryBuffer.address + i);
        return b;
    }

    private static void check(MeshScenario sc) {
        check(sc, false);
    }

    private static void check(MeshScenario sc, boolean expectThrows) {
        var current = new RenderDataFactory(sc.world, sc.models.factory, false);
        var reference = new ReferenceRenderDataFactory(sc.world, sc.models.factory, false);
        int quads = 0, thrown = 0, meshed = 0;
        for (int i = 0; i < sc.positions.size(); i++) {
            var section = sc.acquire(i);
            BuiltSection a = null, b = null;
            Throwable ea = null, eb = null;
            try { a = current.generateMesh(section); } catch (Throwable t) { ea = t; }
            try { b = reference.generateMesh(section); } catch (Throwable t) { eb = t; }
            section.release();
            if (eb != null || ea != null) {
                assertNotNull(ea, "reference threw but current did not: " + eb);
                assertNotNull(eb, "current threw but reference did not: " + ea);
                assertEquals(eb.getClass(), ea.getClass());
                if (eb instanceof IdNotYetComputedException ib) {
                    var ia = (IdNotYetComputedException) ea;
                    assertEquals(ib.id, ia.id);
                    assertEquals(ib.isIdBlockId, ia.isIdBlockId);
                    assertEquals(ib.auxBitMsk, ia.auxBitMsk);
                }
                thrown++;
                continue;
            }
            assertEquals(b.position, a.position);
            assertEquals(b.childExistence, a.childExistence);
            assertEquals(b.aabb, a.aabb, "aabb");
            assertArrayEquals(b.offsets, a.offsets, "offsets");
            assertArrayEquals(bytes(b), bytes(a), "geometry");
            if (b.geometryBuffer != null) {
                quads += (int) (b.geometryBuffer.size / 8);
                meshed++;
            }
            a.free();
            b.free();
        }
        current.free();
        reference.free();
        System.out.println("meshing identity: " + sc.positions.size() + " sections, " + meshed + " with geometry, " + quads + " quads, " + thrown + " threw (same exception in both)");
        if (expectThrows) {
            assertTrue(thrown > 0);
        } else {
            assertTrue(quads > 0);
            assertEquals(0, thrown);
        }
    }

    @Test
    public void terrainIsIdentical() {
        for (int lvl = 0; lvl < 3; lvl++) {
            check(new MeshScenario(100 + lvl, "terrain", lvl, 4, 2, 4));
        }
    }

    @Test
    public void noiseIsIdentical() {
        check(new MeshScenario(7, "noise", 0, 3, 3, 3));
        check(new MeshScenario(8, "sparse", 1, 3, 3, 3));
    }

    //Block ids whose model is not baked yet must fail the same way (same id reported)
    @Test
    public void missingModelsAreIdentical() {
        var sc = new MeshScenario(9, "terrain", 0, 3, 2, 3);
        sc.models.idMappings[MeshingFixture.M_FLOWER] = -1;
        sc.models.idMappings[MeshingFixture.M_GLASS] = -1;
        check(sc, true);
        var sc2 = new MeshScenario(10, "sparse", 0, 2, 2, 2);
        for (int b = MeshingFixture.FIXED_MODELS; b < sc2.models.blockCount; b += 7) sc2.models.idMappings[b] = -1;
        check(sc2, true);
    }
}
