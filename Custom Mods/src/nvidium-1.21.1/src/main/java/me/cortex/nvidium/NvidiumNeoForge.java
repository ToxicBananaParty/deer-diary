package me.cortex.nvidium;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Nvidium does its real initialization lazily from its
 * mixins (see MixinWindow -> {@link Nvidium#checkSystemIsCapable()}); this class
 * exists so NeoForge has a @Mod class for the "nvidium" id on the client.
 */
@Mod(value = "nvidium", dist = Dist.CLIENT)
public class NvidiumNeoForge {
    public NvidiumNeoForge() {
    }
}
