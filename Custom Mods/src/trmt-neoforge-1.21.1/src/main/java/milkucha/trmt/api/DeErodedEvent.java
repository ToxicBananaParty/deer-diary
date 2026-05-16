package milkucha.trmt.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;

/**
 * Posted on {@code NeoForge.EVENT_BUS} immediately AFTER TRMT successfully
 * reverts an eroded block toward a less-eroded state. Mirrors
 * {@link ErodedEvent} but for the reverse direction. Not cancellable.
 *
 * <p>Use for analytics, custom effects on path-vanishing, etc.
 *
 * <p><b>API stability:</b> see {@link CanErodeEvent} — same 1.x contract.
 */
public class DeErodedEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState fromState;
    private final BlockState toState;

    public DeErodedEvent(ServerLevel level, BlockPos pos, BlockState fromState, BlockState toState) {
        this.level = level;
        this.pos = pos;
        this.fromState = fromState;
        this.toState = toState;
    }

    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockState getFromState() { return fromState; }
    public BlockState getToState() { return toState; }
}
