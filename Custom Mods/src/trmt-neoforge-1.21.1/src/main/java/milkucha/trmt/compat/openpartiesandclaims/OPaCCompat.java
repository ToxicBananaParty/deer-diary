package milkucha.trmt.compat.openpartiesandclaims;

import milkucha.trmt.TRMT;
import milkucha.trmt.api.CanErodeEvent;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.protection.api.IChunkProtectionAPI;

/**
 * Open Parties and Claims integration. Cancels player-driven erosion when the
 * triggering player is not authorized to modify the block at the erosion
 * position per OPaC's claim rules.
 *
 * <p>Mob-driven and random-tick (de-)erosion are NOT gated by claim rules —
 * the OPaC chunk-protection API requires an interacting entity, and protecting
 * mobs/random ticks would require a different (less granular) claim query.
 * Users who want claims completely free of erosion can disable mob trampling
 * via {@code mobTramplingEnabled} in {@code trmt.json}.
 *
 * <p>Loaded only if the {@code openpartiesandclaims} mod is present at runtime.
 * Wired from {@link TRMT#TRMT(net.neoforged.bus.api.IEventBus)} via a
 * {@link ModList#isLoaded} guard, so the class is never loaded without OPaC
 * on the classpath.
 */
public final class OPaCCompat {

    public static final String MOD_ID = "openpartiesandclaims";

    private OPaCCompat() {}

    /**
     * Registers the compat module's event subscribers. Call this once during
     * mod init, AFTER checking that OPaC is loaded.
     */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(OPaCCompat::onTrmtCanErode);
        TRMT.LOGGER.info("[TRMT] Open Parties and Claims compat enabled — player-driven erosion will respect claim protections.");
    }

    private static void onTrmtCanErode(CanErodeEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null) return; // mob-driven or random-tick erosion is not claim-gated

        MinecraftServer server = event.getLevel().getServer();
        if (server == null) return;

        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        IChunkProtectionAPI chunkProtection = api.getChunkProtection();
        // `breaking = true` (we're effectively breaking the original block in-place).
        // `messages = false` (don't spam the player chat with "you can't modify here"
        // for every step — erosion fires very frequently).
        boolean blocked = chunkProtection.onBlockInteraction(
            player,
            InteractionHand.MAIN_HAND,
            ItemStack.EMPTY,
            event.getLevel(),
            event.getPos(),
            Direction.UP,
            /* breaking = */ true,
            /* messages = */ false
        );
        if (blocked) {
            event.setCanceled(true);
        }
    }
}
