package milkucha.ddc.command.invsee;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class InvseeMenuProvider implements MenuProvider {
    private final ServerPlayer target;
    private final ServerPlayer viewer;

    public InvseeMenuProvider(ServerPlayer target, ServerPlayer viewer) {
        this.target = target;
        this.viewer = viewer;
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.literal("Inventory of " + target.getGameProfile().getName());
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory playerInv, @NotNull Player viewerPlayer) {
        PlayerInventoryView wrapper = new PlayerInventoryView(target, viewer);
        return new ChestMenu(MenuType.GENERIC_9x5, containerId, playerInv, wrapper, 5);
    }
}
