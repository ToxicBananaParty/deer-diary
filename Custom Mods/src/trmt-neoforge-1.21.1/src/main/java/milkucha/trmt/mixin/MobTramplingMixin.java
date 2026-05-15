package milkucha.trmt.mixin;

import milkucha.trmt.TRMTConfig;
import milkucha.trmt.erosion.EntityStepHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager and horse trampling. Mixes into {@link LivingEntity#tick()} but
 * fast-returns for everything that isn't a {@link Villager} or
 * {@link AbstractHorse}, so the hot path for unrelated entities is a single
 * instanceof + a bool check.
 *
 * <p>Players are handled by {@link ServerPlayerEntityMixin}. Riderless mobs
 * always tick; mobs being ridden by a player do not (the player mixin's
 * {@code mounted} branch already covers that case, so this avoids double
 * counting).
 */
@Mixin(LivingEntity.class)
public class MobTramplingMixin {

    @Unique
    private BlockPos trmt$lastGroundPos = null;

    @Inject(method = "tick", at = @At("TAIL"))
    private void trmt$onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof Villager) && !(self instanceof AbstractHorse)) return;
        if (!(self.level() instanceof ServerLevel level)) return;
        if (!self.onGround()) {
            trmt$lastGroundPos = null;
            return;
        }
        if (!TRMTConfig.get().erosion.mobTramplingEnabled) return;

        Entity controller = self.getControllingPassenger();
        if (controller instanceof ServerPlayer) return; // player rider handled by ServerPlayerEntityMixin

        BlockPos groundPos = self.blockPosition().below().immutable();
        if (groundPos.equals(trmt$lastGroundPos)) return;
        trmt$lastGroundPos = groundPos;

        float mult = self instanceof Villager
            ? TRMTConfig.get().erosionMultipliers.villager
            : TRMTConfig.get().erosionMultipliers.horse;

        EntityStepHandler.handleGroundStep(level, groundPos, mult, level.getGameTime());
    }
}
