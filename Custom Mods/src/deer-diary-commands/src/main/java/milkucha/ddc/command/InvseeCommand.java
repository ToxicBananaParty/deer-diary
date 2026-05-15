package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * `/invsee <player>` — text dump of another player's inventory. A simple
 * read-only summary; doesn't open a chest GUI (which is awkward because a
 * player's 41-slot inventory doesn't fit cleanly in any vanilla menu).
 */
public final class InvseeCommand {
    private InvseeCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("invsee")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("target", EntityArgument.player())
                .executes(ctx -> run(ctx.getSource(), EntityArgument.getPlayer(ctx, "target")))));
    }

    private static int run(CommandSourceStack src, ServerPlayer target) {
        Inventory inv = target.getInventory();
        String name = target.getGameProfile().getName();
        src.sendSuccess(() -> Component.literal(name + "'s inventory:")
            .withStyle(ChatFormatting.YELLOW), false);

        sendRow(src, "Armor", List.of(
            "Helmet=" + describe(inv.armor.get(3)),
            "Chestplate=" + describe(inv.armor.get(2)),
            "Leggings=" + describe(inv.armor.get(1)),
            "Boots=" + describe(inv.armor.get(0))));

        sendRow(src, "Offhand", List.of(describe(inv.offhand.get(0))));

        List<String> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) hotbar.add(describe(inv.items.get(i)));
        sendRow(src, "Hotbar", hotbar);

        List<String> main = new ArrayList<>();
        for (int i = 9; i < 36; i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty()) main.add(describe(stack));
        }
        if (main.isEmpty()) {
            sendRow(src, "Main", List.of("(empty)"));
        } else {
            sendRow(src, "Main (non-empty)", main);
        }
        return 1;
    }

    private static void sendRow(CommandSourceStack src, String label, List<String> items) {
        MutableComponent header = Component.literal("  " + label + ": ")
            .withStyle(ChatFormatting.GRAY);
        String body = String.join(", ", items);
        src.sendSuccess(() -> header.copy().append(Component.literal(body).withStyle(ChatFormatting.WHITE)), false);
    }

    private static String describe(ItemStack stack) {
        if (stack.isEmpty()) return "-";
        int count = stack.getCount();
        String id = stack.getItemHolder().unwrapKey().map(k -> k.location().toString()).orElse("?");
        return count > 1 ? id + " x" + count : id;
    }
}
