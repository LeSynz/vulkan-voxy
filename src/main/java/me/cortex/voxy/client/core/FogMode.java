package me.cortex.voxy.client.core;

public enum FogMode {
    FOG_AND_FADE(false, true, true),
    FOG(false, true, false),
    FADE(true, false, true),
    OFF(true, false, false);
    public final boolean removesVanillaEnvFog;
    public final boolean hasFog;
    public final boolean hasFade;

    FogMode(boolean removesVanillaEnvFog, boolean hasFog, boolean hasFade) {
        this.removesVanillaEnvFog = removesVanillaEnvFog;
        this.hasFog = hasFog;
        this.hasFade = hasFade;
    }
}
