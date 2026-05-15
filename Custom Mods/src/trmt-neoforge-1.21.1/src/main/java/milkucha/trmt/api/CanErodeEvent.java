package milkucha.trmt.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

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
 * <pre>{@code
 * @SubscribeEvent
 * public static void onTrmtCanErode(CanErodeEvent event) {
 *     if (MyClaims.isProtected(event.getLevel(), event.getPos())) {
 *         event.setCanceled(true);
 *     }
 * }
 * }</pre>
 */
public class CanErodeEvent extends Event implements ICancellableEvent {

    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState fromState;

    public CanErodeEvent(ServerLevel level, BlockPos pos, BlockState fromState) {
        this.level = level;
        this.pos = pos;
        this.fromState = fromState;
    }

    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockState getFromState() { return fromState; }
}
