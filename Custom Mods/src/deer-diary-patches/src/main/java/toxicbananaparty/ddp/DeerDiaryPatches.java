package toxicbananaparty.ddp;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(DeerDiaryPatches.MOD_ID)
public class DeerDiaryPatches {
    public static final String MOD_ID = "deer_diary_patches";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public DeerDiaryPatches(IEventBus modBus) {
        LOGGER.info("[DDP] Initialized.");
    }
}
