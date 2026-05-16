package milkucha.trmt.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Posted on {@code NeoForge.EVENT_BUS} immediately before TRMT transitions a
 * block to its next erosion stage. Cancel the event to leave the block
 * untouched — the walked-on count is preserved, so erosion will resume the
 * next time a step lands while the event is not cancelled.
 *
 * <p>Typical use is land-claim integration: a subscriber consults its own
 * claim API for {@link #getLevel()} / {@link #getPos()}, and cancels if the
 * block is inside a protected region.
 *
 * <p>{@link #getPlayer()} returns the triggering player when erosion was
 * caused by a player step, or {@code null} for mob-driven or automatic
 * erosion. Claim/permission mods typically want to allow erosion outside
 * claims regardless of player, and inside claims only when the player is
 * authorized (or, for mobs/automatic, never).
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onTrmtCanErode(CanErodeEvent event) {
 *     ServerPlayer player = event.getPlayer();
 *     if (MyClaims.isProtected(event.getLevel(), event.getPos(), player)) {
 *         event.setCanceled(true);
 *     }
 * }
 * }</pre>
 *
 * <p><b>API stability:</b> field/method signatures on this class are part of
 * the TRMT 1.x public API contract. Additions are backwards-compatible;
 * removals/renames require a 2.x major bump.
 */
public class CanErodeEvent extends Event implements ICancellableEvent {

    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState fromState;
    private final @Nullable ServerPlayer player;

    /** Construct for an automatic / mob-driven erosion (no triggering player). */
    public CanErodeEvent(ServerLevel level, BlockPos pos, BlockState fromState) {
        this(level, pos, fromState, null);
    }

    /** Construct for a player-driven erosion. {@code player} may be null. */
    public CanErodeEvent(ServerLevel level, BlockPos pos, BlockState fromState, @Nullable ServerPlayer player) {
        this.level = level;
        this.pos = pos;
        this.fromState = fromState;
        this.player = player;
    }

    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockState getFromState() { return fromState; }

    /**
     * @return the player whose step triggered this erosion attempt, or
     *         {@code null} if the trigger was a mob, a random tick, or any
     *         other non-player source.
     */
    public @Nullable ServerPlayer getPlayer() { return player; }
}
