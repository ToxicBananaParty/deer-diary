package toxicbananaparty.ddp;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import toxicbananaparty.ddp.aeronautics.AviatorsGogglesCurios;

@Mod(DeerDiaryPatches.MOD_ID)
public class DeerDiaryPatches {
    public static final String MOD_ID = "deer_diary_patches";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public DeerDiaryPatches(IEventBus modBus) {
        LOGGER.info("[DDP] Initialized.");

        // Aviator's Goggles -> Curios head slot bridge. Only wire it when all
        // three mods it touches are present; referencing the bridge class is
        // gated so its curios/create imports never classload on a stack that
        // lacks them.
        if (ModList.get().isLoaded("curios")
            && ModList.get().isLoaded("create")
            && ModList.get().isLoaded("aeronautics")) {
            AviatorsGogglesCurios.register(modBus);
        } else {
            LOGGER.info("[DDP] Aviator's Goggles curios bridge skipped (curios/create/aeronautics not all present).");
        }
    }
}
