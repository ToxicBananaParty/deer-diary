package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class DDCCommands {
    private DDCCommands() {}

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        ExtinguishCommand.register(d);
        GodCommand.register(d);
        InvseeCommand.register(d);
        JumpCommand.register(d);
        KickmeCommand.register(d);
        LeaderboardCommand.register(d);
        MuteCommand.register(d);
        NearCommand.register(d);
        RecCommand.register(d);
        RepairCommand.register(d);
        RtpCommand.register(d);
        TpOfflineCommand.register(d);
        TplCommand.register(d);
        TpxCommand.register(d);
    }
}
