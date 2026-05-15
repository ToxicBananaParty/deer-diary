package milkucha.trmt.erosion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class ErosionFx {
    private ErosionFx() {}

    public static void crumbleParticles(Level level, BlockPos pos, BlockState textureState) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        serverLevel.sendParticles(
            new BlockParticleOption(ParticleTypes.BLOCK, textureState),
            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
            8, 0.25, 0.1, 0.25, 0.02
        );
    }
}
