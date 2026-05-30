package me.cortex.nvidium;

import me.cortex.nvidium.compat.dh.NvidiumDhCompat;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@Mod(value = "nvidium", dist = Dist.CLIENT)
public class NvidiumNeoForge {
    public NvidiumNeoForge(IEventBus modBus) {
        modBus.addListener((FMLClientSetupEvent e) -> e.enqueueWork(NvidiumDhCompat::init));
    }
}
