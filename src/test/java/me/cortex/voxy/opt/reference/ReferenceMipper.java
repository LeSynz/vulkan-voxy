package me.cortex.voxy.opt.reference;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;

import static me.cortex.voxy.common.world.other.Mapper.withLight;

//Verbatim copy of Mipper.mip and WorldVoxilizedSectionMipper.mipSection before optimisation (byte-identical oracle)
public final class ReferenceMipper {
    public static long mip(long I000, long I100, long I001, long I101,
                           long I010, long I110, long I011, long I111,
                           Mapper mapper) {
        int max = -1;
        if (!Mapper.isAir(I111)) {
            max = (mapper.getBlockStateOpacity(I111)<<4)|0b111;
        }
        if (!Mapper.isAir(I110)) {
            max = Math.max((mapper.getBlockStateOpacity(I110)<<4)|0b110, max);
        }
        if (!Mapper.isAir(I011)) {
            max = Math.max((mapper.getBlockStateOpacity(I011)<<4)|0b011, max);
        }
        if (!Mapper.isAir(I010)) {
            max = Math.max((mapper.getBlockStateOpacity(I010)<<4)|0b010, max);
        }
        if (!Mapper.isAir(I101)) {
            max = Math.max((mapper.getBlockStateOpacity(I101)<<4)|0b101, max);
        }
        if (!Mapper.isAir(I100)) {
            max = Math.max((mapper.getBlockStateOpacity(I100)<<4)|0b100, max);
        }
        if (!Mapper.isAir(I001)) {
            max = Math.max((mapper.getBlockStateOpacity(I001)<<4)|0b001, max);
        }
        if (!Mapper.isAir(I000)) {
            max = Math.max((mapper.getBlockStateOpacity(I000)<<4), max);
        }

        if (max != -1) {
            return switch (max&0b111) {
                case 0 -> I000;
                case 1 -> I001;
                case 2 -> I010;
                case 3 -> I011;
                case 4 -> I100;
                case 5 -> I101;
                case 6 -> I110;
                case 7 -> I111;
                default -> throw new IllegalStateException("Unexpected value: " + (max&0b111));
            };
        } else {
            int blockLight = (Mapper.getLightId(I000) & 0xF0) + (Mapper.getLightId(I001) & 0xF0) + (Mapper.getLightId(I010) & 0xF0) + (Mapper.getLightId(I011) & 0xF0) +
                    (Mapper.getLightId(I100) & 0xF0) + (Mapper.getLightId(I101) & 0xF0) + (Mapper.getLightId(I110) & 0xF0) + (Mapper.getLightId(I111) & 0xF0);
            int skyLight = (Mapper.getLightId(I000) & 0x0F) + (Mapper.getLightId(I001) & 0x0F) + (Mapper.getLightId(I010) & 0x0F) + (Mapper.getLightId(I011) & 0x0F) +
                    (Mapper.getLightId(I100) & 0x0F) + (Mapper.getLightId(I101) & 0x0F) + (Mapper.getLightId(I110) & 0x0F) + (Mapper.getLightId(I111) & 0x0F);
            blockLight = (blockLight / 8) & 0xF0;
            skyLight = (int) Math.ceil((double) skyLight / 8);

            return withLight(I111, blockLight | skyLight);
        }
    }

    private static int G(int x, int y, int z) {
        return ((y<<8)|(z<<4)|x);
    }

    private static int H(int x, int y, int z) {
        return ((y<<6)|(z<<3)|x) + 16*16*16;
    }

    private static int I(int x, int y, int z) {
        return ((y<<4)|(z<<2)|x) + 8*8*8 + 16*16*16;
    }

    private static int J(int x, int y, int z) {
        return ((y<<2)|(z<<1)|x) + 4*4*4 + 8*8*8 + 16*16*16;
    }

    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        var data = section.section;
        int i = 0;
        int MSK = 0b1110_1110_1110;
        int iMSK1 = (~MSK)+1;
        int q = 0;
        while (true) {
            data[16*16*16 + i++] = mip(
                    data[q|G(0,0,0)], data[q|G(1,0,0)], data[q|G(0,0,1)], data[q|G(1,0,1)],
                    data[q|G(0,1,0)], data[q|G(1,1,0)], data[q|G(0,1,1)], data[q|G(1,1,1)],
                    mapper
            );
            if (q == MSK)
                break;
            q = (q+iMSK1)&MSK;
        }
        i = 0;
        for (int y = 0; y < 8; y+=2) {
            for (int z = 0; z < 8; z += 2) {
                for (int x = 0; x < 8; x += 2) {
                    data[16*16*16 + 8*8*8 + i++] =
                            mip(
                                    data[H(x, y, z)],       data[H(x+1, y, z)],       data[H(x, y, z+1)],      data[H(x+1, y, z+1)],
                                    data[H(x, y+1, z)],  data[H(x+1, y+1, z)],  data[H(x, y+1, z+1)], data[H(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }
        i = 0;
        for (int y = 0; y < 4; y+=2) {
            for (int z = 0; z < 4; z += 2) {
                for (int x = 0; x < 4; x += 2) {
                    data[16*16*16 + 8*8*8 + 4*4*4 + i++] =
                            mip(
                                    data[I(x, y, z)],       data[I(x+1, y, z)],       data[I(x, y, z+1)],      data[I(x+1, y, z+1)],
                                    data[I(x, y+1, z)],   data[I(x+1, y+1, z)],  data[I(x, y+1, z+1)], data[I(x+1, y+1, z+1)],
                                    mapper);
                }
            }
        }
        data[16*16*16 + 8*8*8 + 4*4*4 + 2*2*2] =
                mip(
                        data[J(0, 0, 0)], data[J(1, 0, 0)], data[J(0, 0, 1)], data[J(1, 0, 1)],
                        data[J(0, 1, 0)], data[J(1, 1, 0)], data[J(0, 1, 1)], data[J(1, 1, 1)],
                        mapper);
    }

    private ReferenceMipper() {}
}
