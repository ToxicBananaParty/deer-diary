package milkucha.trmt.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Posted on {@code NeoForge.EVENT_BUS} immediately AFTER TRMT successfully
 * transitions a block to its next erosion stage (i.e. after the block has
 * been written to the world). Not cancellable — the transform already
 * happened.
 *
 * <p>Use this for analytics, custom particle effects, sound feedback,
 * achievement triggers, or any side-effect that should accompany erosion
 * without affecting it.
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void onTrmtEroded(ErodedEvent event) {
 *     myStats.recordErosion(event.getLevel(), event.getPlayer());
 * }
 * }</pre>
 *
 * <p><b>API stability:</b> see {@link CanErodeEvent} — same 1.x contract.
 */
public class ErodedEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final BlockState fromState;
    private final BlockState toState;
    private final @Nullable ServerPlayer player;

    public ErodedEvent(ServerLevel level, BlockPos pos, BlockState fromState, BlockState toState,
                       @Nullable ServerPlayer player) {
        this.level = level;
        this.pos = pos;
        this.fromState = fromState;
        this.toState = toState;
        this.player = player;
    }

    public ServerLevel getLevel() { return level; }
    public BlockPos getPos() { return pos; }
    public BlockState getFromState() { return fromState; }
    public BlockState getToState() { return toState; }

    /** Triggering player, or {@code null} for mob / automatic erosion. */
    public @Nullable ServerPlayer getPlayer() { return player; }
}
