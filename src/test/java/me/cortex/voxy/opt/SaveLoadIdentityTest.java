package me.cortex.voxy.opt;

import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.ReferenceSaveLoadSystem3;
import me.cortex.voxy.common.world.SaveLoadSystem3;
import me.cortex.voxy.common.world.WorldSection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

//Serialized bytes and deserialized sections must be identical to the original implementation
public class SaveLoadIdentityTest {
    private static byte[] bytes(MemoryBuffer buffer) {
        byte[] out = new byte[(int) buffer.size];
        for (int i = 0; i < out.length; i++) out[i] = org.lwjgl.system.MemoryUtil.memGetByte(buffer.address + i);
        return out;
    }

    private static List<WorldSection> sections() {
        var r = new Random(99);
        List<WorldSection> out = new ArrayList<>();
        for (int lvl = 0; lvl <= 4; lvl++) {
            for (int i = 0; i < 20; i++) {
                out.add(TestSections.terrain(r, lvl, r.nextInt(40) - 4));
                out.add(TestSections.sparseNoise(r, lvl));
            }
            out.add(TestSections.denseNoise(r, lvl, 2));
            out.add(TestSections.denseNoise(r, lvl, 300));
            out.add(TestSections.denseNoise(r, lvl, 20000));
            out.add(WorldSection._createRawUntrackedUnsafeSection(lvl, 0, 0, 0));//all zero
        }
        return out;
    }

    @Test
    public void serializeIsIdentical() {
        for (var sec : sections()) {
            byte[] expected = bytes(ReferenceSaveLoadSystem3.serialize(sec));
            byte[] actual = bytes(SaveLoadSystem3.serialize(sec));
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void deserializeIsIdentical() {
        for (var sec : sections()) {
            byte[] serialized = bytes(ReferenceSaveLoadSystem3.serialize(sec));
            var buffer = new MemoryBuffer(serialized.length);
            for (int i = 0; i < serialized.length; i++) org.lwjgl.system.MemoryUtil.memPutByte(buffer.address + i, serialized[i]);

            var a = WorldSection._createRawUntrackedUnsafeSection(sec.lvl, sec.x, sec.y, sec.z);
            var b = WorldSection._createRawUntrackedUnsafeSection(sec.lvl, sec.x, sec.y, sec.z);
            assertTrue(SaveLoadSystem3.deserialize(a, buffer));
            assertTrue(ReferenceSaveLoadSystem3.deserialize(b, buffer));
            assertArrayEquals(ReferenceSaveLoadSystem3.data(b), ReferenceSaveLoadSystem3.data(a));
            assertEquals(b.getNonEmptyChildren(), a.getNonEmptyChildren());
            assertEquals(ReferenceSaveLoadSystem3.nonEmptyBlockCount(b), ReferenceSaveLoadSystem3.nonEmptyBlockCount(a));
            //Wrong key path
            var c = WorldSection._createRawUntrackedUnsafeSection(sec.lvl, sec.x + 1, sec.y, sec.z);
            assertFalse(SaveLoadSystem3.deserialize(c, buffer));
            buffer.free();
        }
    }
}
