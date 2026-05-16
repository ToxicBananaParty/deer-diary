package milkucha.trmt.mixin;

import milkucha.trmt.TRMTBlocks;
import milkucha.trmt.TRMTConfig;
import milkucha.trmt.TRMTEffects;
import milkucha.trmt.erosion.BlockThresholds;
import milkucha.trmt.erosion.EntityStepHandler;
import milkucha.trmt.erosion.ErosionEntry;
import milkucha.trmt.erosion.ErosionMapManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadLocalRandom;

@Mixin(ServerPlayer.class)
public class ServerPlayerEntityMixin {

    @Unique
    private BlockPos trmt$lastGroundPos = null;

    @Inject(method = "tick", at = @At("TAIL"))
    private void trmt$onTick(CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;

        Entity vehicle = player.getVehicle();
        boolean mounted = vehicle != null;
        boolean onGround = mounted ? vehicle.onGround() : player.onGround();

        if (!onGround) {
            trmt$lastGroundPos = null;
            return;
        }

        BlockPos groundPos = (mounted ? vehicle.blockPosition() : player.blockPosition()).below();

        ServerLevel level = player.serverLevel();
        BlockState groundUpState = level.getBlockState(groundPos.above());
        if (groundUpState.is(TRMTBlocks.ERODED_SAND.get()) || groundUpState.is(Blocks.SAND)) {
            groundPos = groundPos.above();
        }

        if (groundPos.equals(trmt$lastGroundPos)) {
            return;
        }

        trmt$lastGroundPos = groundPos.immutable();

        if (!mounted && player.hasEffect(TRMTEffects.LIGHTNESS)) return;
        if (vehicle instanceof LivingEntity livingVehicle
                && livingVehicle.hasEffect(TRMTEffects.LIGHTNESS)) return;

        BlockState state = level.getBlockState(groundPos);
        Block block = state.getBlock();

        float mult = TRMTConfig.get().erosionMultipliers.player
                * (mounted ? TRMTConfig.get().erosionMultipliers.mounted : 1.0f);

        ErosionMapManager manager = ErosionMapManager.getInstance();
        long gameTime = level.getGameTime();
        TRMTConfig.ErosionToggles erosion = TRMTConfig.get().erosion;

        BlockPos vegPos = groundPos.above();
        BlockState vegState = level.getBlockState(vegPos);
        if (erosion.vegetationEnabled && BlockThresholds.isVegetation(vegState.getBlock())) {
            float effectiveVegMult = EntityStepHandler.applyStepMultiplierModifiers(1.0f * mult, level);
            manager.onStep(level, vegPos, vegState.getBlock(), effectiveVegMult, gameTime);
            trmt$tryBreakVegetation(level, manager, vegPos, vegState);
            manager.broadcastEntryUpdate(level, vegPos, vegState.getBlock());
        }

        if (!EntityStepHandler.isTrackedForErosion(state)) {
            return;
        }

        if (!EntityStepHandler.hasProtectedPlantAbove(level,groundPos)) {
            float effectiveMult = EntityStepHandler.applyStepMultiplierModifiers(1.0f * mult, level);
            manager.onStep(level, groundPos, block, effectiveMult, gameTime);
            EntityStepHandler.tryTransform(level, manager, groundPos, player);
            manager.broadcastEntryUpdate(level, groundPos, block);
        }

        Direction facing = player.getDirection();
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();

        trmt$stepAdjacent(level, manager, groundPos.relative(facing), 0.2f * mult, gameTime, player);
        trmt$stepAdjacent(level, manager, groundPos.relative(left), 0.5f * mult, gameTime, player);
        trmt$stepAdjacent(level, manager, groundPos.relative(right), 0.5f * mult, gameTime, player);
    }

    @Unique
    private static void trmt$stepAdjacent(ServerLevel level, ErosionMapManager manager,
                                           BlockPos pos, float amount, long gameTime, ServerPlayer player) {
        if (EntityStepHandler.hasProtectedPlantAbove(level,pos)) return;
        BlockState adjState = level.getBlockState(pos);
        if (EntityStepHandler.isTrackedForErosion(adjState)) {
            float effectiveAmount = EntityStepHandler.applyStepMultiplierModifiers(amount, level);
            manager.onStep(level, pos, adjState.getBlock(), effectiveAmount, gameTime);
            EntityStepHandler.tryTransform(level, manager, pos, player);
        }
    }

    @Unique
    private static void trmt$tryBreakVegetation(ServerLevel level, ErosionMapManager manager,
                                                 BlockPos pos, BlockState state) {
        ErosionEntry entry = manager.getChunkMap(level, new ChunkPos(pos)).getEntry(pos);
        if (entry == null || entry.getWalkedOnCount() < entry.getThreshold()) return;

        if (state.getBlock() instanceof DoublePlantBlock
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
            BlockPos upper = pos.above();
            if (level.getBlockState(upper).is(state.getBlock())) {
                level.removeBlock(upper, false);
            }
        }

        float dropChance = TRMTConfig.get().erosionThresholds.vegetation.dropChance;
        boolean drops = dropChance >= 1.0f || (dropChance > 0.0f && ThreadLocalRandom.current().nextFloat() < dropChance);
        level.destroyBlock(pos, drops);
        manager.removeEntry(level, pos);
    }

}
