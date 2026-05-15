package milkucha.ddc;

import milkucha.ddc.command.DDCCommands;
import milkucha.ddc.config.DDCConfig;
import milkucha.ddc.state.MuteState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(DeerDiaryCommands.MOD_ID)
public class DeerDiaryCommands {
    public static final String MOD_ID = "deer_diary_commands";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public DeerDiaryCommands(IEventBus modBus) {
        DDCConfig.load();
        NeoForge.EVENT_BUS.addListener(DDCCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(DeerDiaryCommands::onServerStarted);
        NeoForge.EVENT_BUS.addListener(DeerDiaryCommands::onServerStopped);
        NeoForge.EVENT_BUS.addListener(DeerDiaryCommands::onServerChat);
        LOGGER.info("[DDC] Initialized.");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        DDCConfig.load();
        MuteState.onServerStarted(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        MuteState.onServerStopped();
    }

    private static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) return;
        if (MuteState.getInstance().isMuted(player.getUUID())) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal("You are muted; your message was not sent.")
                .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }
}
