package me.cortex.voxy.opt;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.opt.reference.ReferenceMipper;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

//The optimised mipper must produce byte-identical output to the original implementation
public class MipperIdentityTest {
    private static void check(VoxelizedSection section, Mapper mapper) {
        var a = new VoxelizedSection(section.section.clone());
        var b = new VoxelizedSection(section.section.clone());
        WorldVoxilizedSectionMipper.mipSection(a, mapper);
        ReferenceMipper.mipSection(b, mapper);
        assertArrayEquals(b.section, a.section);
    }

    @Test
    public void sectionsAreIdentical() {
        var mapper = TestWorlds.createMapper();
        long[] ids = TestWorlds.blockIds(mapper);
        var r = new Random(42);
        for (int i = 0; i < 300; i++) {
            check(TestWorlds.terrainSection(r, ids, r.nextInt(24) - 4), mapper);
            check(TestWorlds.noiseSection(r, ids), mapper);
        }
        //Uniform sections, all air with random light, and sections made of uniform 2x2x2 cells
        for (int i = 0; i < 100; i++) {
            var s = VoxelizedSection.createEmpty();
            long v = r.nextBoolean() ? Mapper.composeMappingId((byte) r.nextInt(), 0, 0) : Mapper.composeMappingId((byte) r.nextInt(), (int) ids[r.nextInt(ids.length)], r.nextInt(5));
            for (int j = 0; j < 4096; j++) s.section[j] = (i % 3 == 0) ? v : Mapper.composeMappingId((byte) r.nextInt(), 0, 0);
            check(s, mapper);
            var c = VoxelizedSection.createEmpty();
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                long cell = ((x >> 1) * 31 + (y >> 1) * 7 + (z >> 1) * 13 + i) % (ids.length + 1);
                c.section[(y << 8) | (z << 4) | x] = cell == ids.length ? Mapper.composeMappingId((byte) (cell * 17), 0, 0) : Mapper.composeMappingId((byte) cell, (int) ids[(int) cell], 1);
            }
            check(c, mapper);
        }
    }

    @Test
    public void singleMipCallsAreIdentical() {
        var mapper = TestWorlds.createMapper();
        long[] ids = TestWorlds.blockIds(mapper);
        var r = new Random(7);
        long[] pool = new long[ids.length * 2 + 4];
        for (int i = 0; i < ids.length; i++) {
            pool[i] = Mapper.composeMappingId((byte) r.nextInt(), (int) ids[i], 1);
            pool[ids.length + i] = Mapper.composeMappingId((byte) r.nextInt(), (int) ids[i], 2);
        }
        for (int i = 0; i < 4; i++) pool[ids.length * 2 + i] = Mapper.composeMappingId((byte) r.nextInt(), 0, 0);
        for (int n = 0; n < 2_000_000; n++) {
            long[] v = new long[8];
            int distinct = 1 + r.nextInt(3);
            long[] choices = new long[distinct];
            for (int k = 0; k < distinct; k++) choices[k] = pool[r.nextInt(pool.length)];
            for (int k = 0; k < 8; k++) v[k] = choices[r.nextInt(distinct)];
            assertEquals(ReferenceMipper.mip(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], mapper),
                    Mipper.mip(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], mapper));
        }
    }
}
