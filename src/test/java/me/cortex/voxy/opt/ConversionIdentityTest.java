package me.cortex.voxy.opt;

import me.cortex.voxy.common.voxelization.DataLayerLightingSupplier;
import me.cortex.voxy.common.voxelization.ReferenceWorldConversionFactory;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.opt.reference.ReferenceLighting;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

//World ingest conversion (minecraft section -> voxy voxels) must be identical to the original
public class ConversionIdentityTest {
    @Test
    public void conversionIsIdentical() {
        var mapper = TestWorlds.createMapper();
        var r = new Random(5);
        for (int n = 0; n < 400; n++) {
            var sec = TestChunks.create(r, r.nextInt(24) - 4, r.nextInt(4) == 0 ? 0 : r.nextInt(300), r.nextBoolean(), n % 4);
            var a = WorldConversionFactory.convert(VoxelizedSection.createEmpty(), mapper, sec.blocks(), sec.biomes(),
                    DataLayerLightingSupplier.of(sec.blockLight(), sec.skyLight()));
            var b = ReferenceWorldConversionFactory.convert(VoxelizedSection.createEmpty(), mapper, sec.blocks(), sec.biomes(),
                    ReferenceLighting.create(sec.blockLight(), sec.skyLight()));
            assertArrayEquals(b.section, a.section);
            assertEquals(b.lvl0NonAirCount, a.lvl0NonAirCount);
            //The per voxel path of the new supplier must also match
            var lightA = DataLayerLightingSupplier.of(sec.blockLight(), sec.skyLight());
            var lightB = ReferenceLighting.create(sec.blockLight(), sec.skyLight());
            for (int i = 0; i < 4096; i++) {
                assertEquals(lightB.supply(i & 15, i >> 8, (i >> 4) & 15), lightA.supply(i & 15, i >> 8, (i >> 4) & 15));
            }
        }
    }
}
