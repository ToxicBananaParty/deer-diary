package milkucha.ddc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * {@code /repair} — fully repair the player's main-hand item by zeroing its
 * damage component. Enchantments, custom name, lore, attribute modifiers, and
 * every other data component are left untouched — we only reset
 * {@link ItemStack#setDamageValue(int)}.
 *
 * Default access: admin only (Brigadier permission level 2). The server's
 * permission interceptor will additionally gate by {@code command.repair}.
 */
public final class RepairCommand {
    private RepairCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("repair")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> run(ctx.getSource())));
    }

    private static int run(CommandSourceStack src) throws CommandSyntaxException {
        ServerPlayer player = src.getPlayerOrException();
        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);

        if (stack.isEmpty()) {
            src.sendFailure(Component.literal("You're not holding anything."));
            return 0;
        }
        if (!stack.isDamageableItem()) {
            src.sendFailure(Component.literal(
                stack.getHoverName().getString() + " can't be damaged or repaired."));
            return 0;
        }
        int wasDamaged = stack.getDamageValue();
        if (wasDamaged == 0) {
            src.sendSuccess(() -> Component.literal(
                    stack.getHoverName().getString() + " is already at full durability.")
                .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        // Zero only the damage component. Touching ItemStack#setDamageValue
        // does NOT clear enchantments, the custom-name component, lore, the
        // attribute-modifiers component, etc. — it just writes to
        // DataComponents.DAMAGE.
        stack.setDamageValue(0);
        // Sync the change to the holding player so their tooltip/durability bar
        // updates this tick instead of waiting for inventory drift.
        player.inventoryMenu.broadcastChanges();

        src.sendSuccess(() -> Component.literal(String.format(
                "Repaired %s (-%d damage).",
                stack.getHoverName().getString(), wasDamaged))
            .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
