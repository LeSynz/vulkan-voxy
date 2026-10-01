package me.cortex.voxy.common.voxelization;

import net.minecraft.world.level.chunk.DataLayer;
import org.jetbrains.annotations.Nullable;

//Section lighting from minecraft's light layers: (sky | block<<4), a null layer means 0.
// supplyAll reads the nibble arrays directly instead of one interface call + DataLayer.get per voxel.
public final class DataLayerLightingSupplier implements ILightingSupplier {
    private final @Nullable DataLayer blockLight;
    private final @Nullable DataLayer skyLight;

    //Layers that are null or empty are treated as no light (same as the previous per voxel lambdas)
    public static ILightingSupplier of(@Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        boolean sl = skyLight != null && !skyLight.isEmpty();
        boolean bl = blockLight != null && !blockLight.isEmpty();
        return new DataLayerLightingSupplier(bl ? blockLight : null, sl ? skyLight : null);
    }

    private DataLayerLightingSupplier(@Nullable DataLayer blockLight, @Nullable DataLayer skyLight) {
        this.blockLight = blockLight;
        this.skyLight = skyLight;
    }

    @Override
    public byte supply(int x, int y, int z) {
        int block = this.blockLight == null ? 0 : Math.min(15, this.blockLight.get(x, y, z));
        int sky = this.skyLight == null ? 0 : Math.min(15, this.skyLight.get(x, y, z));
        return (byte) (sky|(block<<4));
    }

    @Override
    public void supplyAll(byte[] out) {
        byte[] blk = this.blockLight == null ? null : this.blockLight.data;
        byte[] sky = this.skyLight == null ? null : this.skyLight.data;
        //Constant values for layers without a backing array (null layer -> 0, otherwise the clamped default)
        int blkConst = this.blockLight == null ? 0 : Math.min(15, this.blockLight.defaultValue);
        int skyConst = this.skyLight == null ? 0 : Math.min(15, this.skyLight.defaultValue);
        if (blk != null && sky != null) {
            for (int i = 0; i < 2048; i++) {
                int b = blk[i], k = sky[i];
                out[i*2] = (byte) ((k & 15)|((b & 15)<<4));
                out[i*2+1] = (byte) (((k >> 4) & 15)|(b & 0xF0));
            }
        } else if (blk != null) {
            for (int i = 0; i < 2048; i++) {
                int b = blk[i];
                out[i*2] = (byte) (skyConst|((b & 15)<<4));
                out[i*2+1] = (byte) (skyConst|(b & 0xF0));
            }
        } else if (sky != null) {
            for (int i = 0; i < 2048; i++) {
                int k = sky[i];
                out[i*2] = (byte) ((k & 15)|(blkConst<<4));
                out[i*2+1] = (byte) (((k >> 4) & 15)|(blkConst<<4));
            }
        } else {
            java.util.Arrays.fill(out, 0, 4096, (byte) (skyConst|(blkConst<<4)));
        }
    }
}
