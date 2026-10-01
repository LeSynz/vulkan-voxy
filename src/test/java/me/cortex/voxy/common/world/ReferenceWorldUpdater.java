package me.cortex.voxy.common.world;

import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;

//Verbatim copy of WorldUpdater.insertSectionLvlIntoWorld before optimisation (byte-identical oracle)
public final class ReferenceWorldUpdater {
    //Exposed for JMH benchmark
    public static long insertSectionLvlIntoWorld(VoxelizedSection section, WorldSection worldSection) {
        final long[] vdat = section.section;
        final int lvl = worldSection.lvl;

        final int msk = (1<<(lvl+1))-1;
        final int bx = (section.x&msk)<<(4-lvl);
        final int by = (section.y&msk)<<(4-lvl);
        final int bz = (section.z&msk)<<(4-lvl);

        int nonAirCount = 0;
        boolean didStateChange = false;


        //TODO: remove the nonAirCountDelta stuff if level != 0

        {//Do a bunch of funny math
            var secD = worldSection.data;
            int baseSec = bx | (bz << 5) | (by << 10);
            if (lvl == 0) {
                final int secMsk = 0b1100|(0xf << 5) | (0xf << 10);
                final int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;

                //TODO rotate the loop parralelization
                // i.e. instead of doing 4 consecutive blocks, which would all be in the same cache line
                // do 4 seperate rows so they are in different cache lines, should allow
                // more instruction pipelining (in theory)
                for (int i = 0; i <= 0xFFF; i+=4) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;

                    long oldId0 = secD[cSecIdx+0]; secD[cSecIdx+0] = vdat[i+0];
                    long oldId1 = secD[cSecIdx+1]; secD[cSecIdx+1] = vdat[i+1];
                    long oldId2 = secD[cSecIdx+2]; secD[cSecIdx+2] = vdat[i+2];
                    long oldId3 = secD[cSecIdx+3]; secD[cSecIdx+3] = vdat[i+3];

                    nonAirCount += Mapper.isNotAirInt(oldId0); didStateChange |= vdat[i+0] != oldId0;
                    nonAirCount += Mapper.isNotAirInt(oldId1); didStateChange |= vdat[i+1] != oldId1;
                    nonAirCount += Mapper.isNotAirInt(oldId2); didStateChange |= vdat[i+2] != oldId2;
                    nonAirCount += Mapper.isNotAirInt(oldId3); didStateChange |= vdat[i+3] != oldId3;
                }
            } else {
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);

                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;
                //TODO: manually unroll and do e.g. 4 iterations per loop
                for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;
                    long newId = vdat[i];
                    long oldId = secD[cSecIdx];
                    didStateChange |= newId != oldId;
                    secD[cSecIdx] = newId;
                }
            }
        }

        long status = 0;
        status |= didStateChange?1:0;
        status |= Integer.toUnsignedLong(nonAirCount)<<1;//VERY VERY VERY IMPORTANT NOTE: IS 13 BITS BIG NOT 12 BITS (since it can be 4096 which is 6 bits large)
        return status;
    }

    private ReferenceWorldUpdater() {}
}
