package me.cortex.voxy.common.voxelization;

public interface ILightingSupplier {
    byte supply(int x, int y, int z);

    //Fills out[i] for every voxel of the section, i = y<<8 | z<<4 | x (same order as the conversion loop)
    default void supplyAll(byte[] out) {
        for (int i = 0; i <= 0xFFF; i++) {
            out[i] = this.supply(i&0xF, (i>>8)&0xF, (i>>4)&0xF);
        }
    }
}
