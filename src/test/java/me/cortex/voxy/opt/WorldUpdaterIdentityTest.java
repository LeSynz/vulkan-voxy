package me.cortex.voxy.opt;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.ReferenceSaveLoadSystem3;
import me.cortex.voxy.common.world.ReferenceWorldUpdater;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

//Inserting a voxelized section (all mip levels) into world sections must match the original exactly
public class WorldUpdaterIdentityTest {
    private static void fill(Random r, long[] arr, int mode) {
        for (int i = 0; i < arr.length; i++) {
            arr[i] = switch (mode) {
                case 0 -> 0;
                case 1 -> r.nextInt(3) == 0 ? Mapper.composeMappingId((byte) r.nextInt(), r.nextInt(5), 1) : 0;
                default -> Mapper.composeMappingId((byte) r.nextInt(), r.nextInt(100), r.nextInt(4));
            };
        }
    }

    @Test
    public void insertIsIdentical() {
        var r = new Random(3);
        for (int n = 0; n < 3000; n++) {
            int lvl = n % 5;
            var vs = VoxelizedSection.createEmpty().setPosition(r.nextInt(64) - 32, r.nextInt(16) - 8, r.nextInt(64) - 32);
            fill(r, vs.section, r.nextInt(3));
            var a = WorldSection._createRawUntrackedUnsafeSection(lvl, 0, 0, 0);
            var b = WorldSection._createRawUntrackedUnsafeSection(lvl, 0, 0, 0);
            long[] initial = new long[a._unsafeGetRawDataArray().length];
            fill(r, initial, r.nextInt(3));
            if (n % 7 == 0) {//Insert the same data that is already there (no state change)
                int base = VoxelizedSection.getBaseIndexForLevel(lvl);
                ReferenceWorldUpdater.insertSectionLvlIntoWorld(vs, WorldSection._createRawUntrackedUnsafeSection(lvl, 0, 0, 0));
            }
            System.arraycopy(initial, 0, a._unsafeGetRawDataArray(), 0, initial.length);
            System.arraycopy(initial, 0, b._unsafeGetRawDataArray(), 0, initial.length);
            if (n % 7 == 0) {
                ReferenceWorldUpdater.insertSectionLvlIntoWorld(vs, a);
                ReferenceWorldUpdater.insertSectionLvlIntoWorld(vs, b);
            }
            long sa = WorldUpdater.insertSectionLvlIntoWorld(vs, a);
            long sb = ReferenceWorldUpdater.insertSectionLvlIntoWorld(vs, b);
            assertEquals(sb, sa);
            assertArrayEquals(ReferenceSaveLoadSystem3.data(b), ReferenceSaveLoadSystem3.data(a));
        }
    }
}
