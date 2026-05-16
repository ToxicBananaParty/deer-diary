package milkucha.trmt.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Posted on {@code NeoForge.EVENT_BUS} immediately before TRMT reverts an
 * eroded block toward a less-eroded state ("de-erosion"). Mirrors
 * {@link CanErodeEvent} but for the reverse direction.
 *
 * <p>De-erosion happens via {@code randomTick()} on the eroded blocks once
 * the configured per-stage timeout has elapsed since the block was last
 * walked on. It has no triggering player — the trigger is an internal random
 * tick. As such, this event carries no player reference.
 *
 * <p>Cancel to leave the block in its current eroded state. The next random
 * tick will re-evaluate (and re-fire this event).
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onTrmtCanDeErode(CanDeErodeEvent event) {
 *     // Example: keep paths frozen in winter biomes.
 *     if (MyBiomes.isFrozen(event.getLevel(), event.getPos())) {
 *         event.setCanceled(true);
 *     }
 * }
 * }</pre>
 *
 * <p><b>API stability:</b> see {@link CanErodeEvent} — same 1.x contract.
 */
public class CanDeErodeEvent extends Event implements ICancellableEvent {

    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState fromState;

    public CanDeErodeEvent(ServerLevel level, BlockPos pos, BlockState fromState) {
        this.level = level;
        this.pos = pos;
        this.fromState = fromState;
    }

    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockState getFromState() { return fromState; }
}
