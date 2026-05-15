package milkucha.ddc.command.invsee;

import milkucha.ddc.DeerDiaryCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A 45-slot {@link Container} that proxies reads and writes to another
 * player's live {@link net.minecraft.world.entity.player.Inventory}. Opened
 * as a 5-row vanilla chest menu by {@link InvseeMenuProvider}.
 *
 * <p>Layout (matches the convention used by FTB Essentials):
 * <pre>
 *   Row 1 (0-8)   :  helmet  chest  legs  boots  -  -  -  -  offhand
 *   Row 2 (9-17)  :  target main inventory  row 1
 *   Row 3 (18-26) :  target main inventory  row 2
 *   Row 4 (27-35) :  target main inventory  row 3
 *   Row 5 (36-44) :  target hotbar  slots 0..8
 * </pre>
 *
 * <p>Edits propagate immediately. {@link #setChanged()} broadcasts the new
 * state through the target's own container menu so they see updates live
 * if they have their inventory open.
 *
 * <p>Auto-close on logout is implemented by tracking open viewers and
 * force-closing their containers from {@link #onTargetLoggedOut(UUID)}.
 */
public final class PlayerInventoryView implements Container {

    // target UUID -> set of admin players currently viewing
    private static final Map<UUID, Set<ServerPlayer>> OPEN_VIEWERS = new ConcurrentHashMap<>();

    private final ServerPlayer target;
    private final ServerPlayer viewer;

    public PlayerInventoryView(ServerPlayer target, ServerPlayer viewer) {
        this.target = target;
        this.viewer = viewer;
        OPEN_VIEWERS.computeIfAbsent(target.getUUID(), k -> ConcurrentHashMap.newKeySet()).add(viewer);
    }

    /** Convert a menu slot (0-44) to an underlying inventory slot, or -1 if blank/invalid. */
    private static int toInventorySlot(int menuSlot) {
        // Row 1: armor + padding + offhand
        if (menuSlot == 0) return 39; // helmet
        if (menuSlot == 1) return 38; // chestplate
        if (menuSlot == 2) return 37; // leggings
        if (menuSlot == 3) return 36; // boots
        if (menuSlot >= 4 && menuSlot <= 7) return -1; // padding
        if (menuSlot == 8) return 40; // offhand
        // Rows 2-4 (menu 9-35): main inventory slots 9-35 (1:1)
        if (menuSlot >= 9 && menuSlot <= 35) return menuSlot;
        // Row 5 (menu 36-44): hotbar slots 0-8
        if (menuSlot >= 36 && menuSlot <= 44) return menuSlot - 36;
        return -1;
    }

    // ----- viewer lifecycle -----

    /**
     * Called from a server-side ContainerClose handler. Removes this viewer
     * from every tracked target's set. Cheap because viewers/targets are few.
     */
    public static void onViewerClosedAnyContainer(ServerPlayer viewer) {
        for (Map.Entry<UUID, Set<ServerPlayer>> e : OPEN_VIEWERS.entrySet()) {
            e.getValue().remove(viewer);
        }
        OPEN_VIEWERS.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    /** Called when a target player logs out — force-close all admin views of them. */
    public static void onTargetLoggedOut(UUID targetId) {
        Set<ServerPlayer> viewers = OPEN_VIEWERS.remove(targetId);
        if (viewers == null) return;
        for (ServerPlayer v : viewers) {
            if (v.containerMenu != v.inventoryMenu) {
                v.closeContainer();
                v.sendSystemMessage(Component.literal(
                    "[/invsee] target logged out; closed their inventory view.")
                    .withStyle(ChatFormatting.GRAY));
            }
        }
    }

    // ----- Container -----

    @Override
    public int getContainerSize() {
        return 45;
    }

    @Override
    public boolean isEmpty() {
        return target.getInventory().isEmpty();
    }

    @Override
    public ItemStack getItem(int menuSlot) {
        int slot = toInventorySlot(menuSlot);
        return slot == -1 ? ItemStack.EMPTY : target.getInventory().getItem(slot);
    }

    @Override
    public ItemStack removeItem(int menuSlot, int amount) {
        int slot = toInventorySlot(menuSlot);
        if (slot == -1) return ItemStack.EMPTY;
        ItemStack removed = target.getInventory().removeItem(slot, amount);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int menuSlot) {
        int slot = toInventorySlot(menuSlot);
        return slot == -1 ? ItemStack.EMPTY : target.getInventory().removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int menuSlot, ItemStack stack) {
        int slot = toInventorySlot(menuSlot);
        if (slot == -1) return;
        target.getInventory().setItem(slot, stack);
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return target.getInventory().getMaxStackSize();
    }

    @Override
    public void setChanged() {
        target.getInventory().setChanged();
        // Push the update to the target so they see it in their own inv too.
        if (target.containerMenu != null) {
            try {
                target.containerMenu.broadcastChanges();
            } catch (Exception ex) {
                DeerDiaryCommands.LOGGER.warn("[DDC] /invsee broadcast failed", ex);
            }
        }
    }

    @Override
    public boolean stillValid(Player p) {
        // Keep the menu open as long as the target is still on the server.
        // Force-close from onTargetLoggedOut handles the logout edge case
        // because stillValid isn't checked every tick.
        return !target.hasDisconnected();
    }

    @Override
    public boolean canPlaceItem(int menuSlot, ItemStack stack) {
        int slot = toInventorySlot(menuSlot);
        if (slot == -1) return false;
        return target.getInventory().canPlaceItem(slot, stack);
    }

    @Override
    public void clearContent() {
        target.getInventory().clearContent();
    }
}
