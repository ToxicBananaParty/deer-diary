package toxicbananaparty.ddp.aeronautics;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import toxicbananaparty.ddp.DeerDiaryPatches;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.CuriosCapability;
import top.theillusivec4.curios.api.type.capability.ICurio;

/**
 * Lets Aeronautics' Aviator's Goggles be worn in the Curios "head" slot AND
 * still function there.
 *
 * The goggles are a vanilla-style {@code ArmorItem} (EquipmentSlot.HEAD).
 * Two problems block the curios use case, and this class fixes both:
 *
 * 1. PLACEMENT. Curios' tag-based slot assignment does not attach the ICurio
 *    capability to ArmorItems (they already own a native equipment slot), so
 *    the goggles can never be dropped into a curios slot even when tagged.
 *    Fix: explicitly attach {@link CuriosCapability#ITEM} to the goggles via
 *    {@link RegisterCapabilitiesEvent}. The ICurio is intentionally bare —
 *    no attribute modifiers — so the curios slot grants NO armor (the player
 *    gets goggle armor only from a real helmet slot; no double-dipping).
 *
 * 2. FUNCTION. The goggles' only behavior is to switch on Create's goggle HUD
 *    overlay, which Aeronautics wires up in the goggles' constructor via
 *    {@code GogglesItem.addIsWearingPredicate(p -> goggles in p.getItemBySlot(HEAD))}.
 *    That predicate only checks the vanilla head slot, so curios-slotted
 *    goggles would be inert. Fix: register an ADDITIONAL predicate (Create
 *    OR-combines them) that also returns true when the goggles sit in a
 *    curios slot. Same public hook Aeronautics uses — no mixin required.
 *
 * The whole bridge is only wired up when curios + create + aeronautics are all
 * present (see {@link DeerDiaryPatches}); this class is never loaded otherwise,
 * so its Curios/Create imports never resolve on a stack missing those mods.
 */
public final class AviatorsGogglesCurios {

    private static final ResourceLocation GOGGLES_ID =
        ResourceLocation.fromNamespaceAndPath("aeronautics", "aviators_goggles");

    private AviatorsGogglesCurios() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(AviatorsGogglesCurios::onRegisterCapabilities);
        modBus.addListener(AviatorsGogglesCurios::onCommonSetup);
    }

    private static Item goggles() {
        Item item = BuiltInRegistries.ITEM.get(GOGGLES_ID);
        return (item == null || item == Items.AIR) ? null : item;
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        Item goggles = goggles();
        if (goggles == null) {
            DeerDiaryPatches.LOGGER.warn("[DDP] aeronautics:aviators_goggles not found; skipping curios capability.");
            return;
        }
        // Bare ICurio: just enough to make the stack a valid curio. No
        // getAttributeModifiers override => Curios' empty default => no armor
        // from the curios slot.
        event.registerItem(
            CuriosCapability.ITEM,
            (stack, ctx) -> new ICurio() {
                @Override
                public net.minecraft.world.item.ItemStack getStack() {
                    return stack;
                }
            },
            goggles
        );
        DeerDiaryPatches.LOGGER.info("[DDP] Attached Curios capability to aeronautics:aviators_goggles.");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            Item goggles = goggles();
            if (goggles == null) return;
            // Create OR-combines all wearing predicates; this one fires when
            // the goggles are equipped in any curios slot (they're tag-gated
            // to "head"), so the goggle HUD lights up there too.
            GogglesItem.addIsWearingPredicate(player ->
                CuriosApi.getCuriosInventory(player)
                    .map(handler -> handler.isEquipped(goggles))
                    .orElse(false));
            DeerDiaryPatches.LOGGER.info("[DDP] Registered curios-slot goggle-wearing predicate with Create.");
        });
    }
}
