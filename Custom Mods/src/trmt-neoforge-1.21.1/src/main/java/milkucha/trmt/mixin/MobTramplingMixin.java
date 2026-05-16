package milkucha.trmt.mixin;

import milkucha.trmt.TRMTConfig;
import milkucha.trmt.TRMTEffects;
import milkucha.trmt.TRMTTags;
import milkucha.trmt.erosion.EntityStepHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mob trampling. Mixes into {@link LivingEntity#tick()} but fast-returns for
 * entities that aren't in {@link TRMTTags#TRAMPLES}, keeping the hot path
 * for unrelated entities to a single tag lookup.
 *
 * <p>Players are handled by {@link ServerPlayerEntityMixin}. Riderless mobs
 * always tick; mobs being ridden by a player do not (the player mixin's
 * {@code mounted} branch already covers that case, so this avoids double
 * counting).
 *
 * <p>Multipliers are looked up from
 * {@link TRMTConfig.Multipliers#tramples} (keyed by entity-type id), with
 * a fallback to the legacy {@code villager}/{@code horse} fields for
 * vanilla compatibility and to {@code defaultTrample} for everything else.
 */
@Mixin(LivingEntity.class)
public class MobTramplingMixin {

    @Unique
    private BlockPos trmt$lastGroundPos = null;

    @Inject(method = "tick", at = @At("TAIL"))
    private void trmt$onTick(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.getType().is(TRMTTags.TRAMPLES)) return;
        if (!(self.level() instanceof ServerLevel level)) return;
        if (!self.onGround()) {
            trmt$lastGroundPos = null;
            return;
        }
        if (!TRMTConfig.get().erosion.mobTramplingEnabled) return;

        Entity controller = self.getControllingPassenger();
        if (controller instanceof ServerPlayer) return; // player rider handled by ServerPlayerEntityMixin

        // Lightness potion makes the holder erosion-free (parity with ServerPlayerEntityMixin).
        if (self.hasEffect(TRMTEffects.LIGHTNESS)) return;

        BlockPos groundPos = self.blockPosition().below().immutable();
        if (groundPos.equals(trmt$lastGroundPos)) return;
        trmt$lastGroundPos = groundPos;

        float mult = trmt$multiplierFor(self);

        EntityStepHandler.handleGroundStep(level, groundPos, mult, level.getGameTime());
    }

    /**
     * Resolves the trampling multiplier for {@code entity} from
     * {@link TRMTConfig.Multipliers#tramples} (keyed by entity-type id),
     * falling back to {@code defaultTrample} for entries that aren't
     * explicitly configured.
     */
    @Unique
    private static float trmt$multiplierFor(LivingEntity entity) {
        TRMTConfig.Multipliers mults = TRMTConfig.get().erosionMultipliers;
        EntityType<?> type = entity.getType();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

        if (mults.tramples != null) {
            Float explicit = mults.tramples.get(id.toString());
            if (explicit != null) return explicit;
        }

        return mults.defaultTrample;
    }
}
