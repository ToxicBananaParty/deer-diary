package milkucha.trmt.compat.luckperms;

import milkucha.trmt.TRMT;
import milkucha.trmt.api.CanErodeEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.model.user.User;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

/**
 * LuckPerms integration. Cancels player-driven erosion for any player who has
 * the {@code trmt.bypass.erosion} permission set to {@code true}.
 *
 * <p>Use case: server admins / mod team / build users who walk around without
 * leaving permanent paths everywhere. Set the permission via:
 * <pre>{@code
 * /lp user <name> permission set trmt.bypass.erosion true
 * }</pre>
 *
 * <p>This module touches LuckPerms API classes only inside methods that are
 * called after the {@code luckperms} mod is confirmed loaded — the JVM never
 * resolves them otherwise.
 */
public final class LuckPermsCompat {

    public static final String MOD_ID = "luckperms";
    public static final String BYPASS_EROSION_NODE = "trmt.bypass.erosion";

    private LuckPermsCompat() {}

    /**
     * Registers the compat module's event subscribers. Call this once during
     * mod init, AFTER checking that LuckPerms is loaded.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(LuckPermsCompat::onTrmtCanErode);
        TRMT.LOGGER.info("[TRMT] LuckPerms compat enabled — players with '{}' will not cause erosion.",
            BYPASS_EROSION_NODE);
    }

    private static void onTrmtCanErode(CanErodeEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) return; // only check for player-driven erosion

        LuckPerms api;
        try {
            api = LuckPermsProvider.get();
        } catch (IllegalStateException e) {
            // LuckPerms not yet bound (rare — usually means we hooked before LP init)
            return;
        }
        User user = api.getUserManager().getUser(player.getUUID());
        if (user == null) return; // not loaded for this player; let erosion proceed

        CachedPermissionData perms = user.getCachedData().getPermissionData();
        if (perms.checkPermission(BYPASS_EROSION_NODE).asBoolean()) {
            event.setCanceled(true);
        }
    }
}
