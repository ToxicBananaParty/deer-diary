package toxicbananaparty.ddp.tomsstorage.mixin;

import com.tom.storagemod.inventory.PlatformFilteredInventoryAccess;
import com.tom.storagemod.inventory.filter.IFilter;
import com.tom.storagemod.inventory.filter.PolyFilter;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fix for tom5454/Toms-Storage#459 / #572: the Polymorphic Item Filter on an
 * Inventory Cable Connector was applied uniformly to reads, extracts AND
 * inserts, hiding the target inventory's existing contents from the terminal
 * and blocking extraction. PolyFilter is supposed to gate insertion only.
 *
 * Bypass the filter check on read/extract paths when the active filter is a
 * PolyFilter; leave the insert path (insertItem / pushStack / isItemValid)
 * untouched so PolyFilter's intended "only allow items already present"
 * insertion gate keeps working. Any other filter type (simple, tag, deny,
 * multi-filter) keeps its existing behavior.
 */
@Mixin(PlatformFilteredInventoryAccess.class)
public abstract class PlatformFilteredInventoryAccessMixin {

    @Shadow @Final private IFilter filter;

    @Shadow private native IItemHandler getP();

    private boolean ddp$isPoly() {
        return filter != null && filter.getItemPred() instanceof PolyFilter;
    }

    @Inject(method = "getStackInSlot", at = @At("HEAD"), cancellable = true)
    private void ddp$bypassPolyOnGetStackInSlot(int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (!ddp$isPoly()) return;
        ItemStack is = getP().getStackInSlot(slot);
        if (filter.isKeepLast()) {
            is = is.copy();
            is.shrink(1);
        }
        cir.setReturnValue(is);
    }

    @Inject(method = "extractItem", at = @At("HEAD"), cancellable = true)
    private void ddp$bypassPolyOnExtractItem(int slot, int amount, boolean simulate, CallbackInfoReturnable<ItemStack> cir) {
        if (!ddp$isPoly()) return;
        ItemStack is = getP().getStackInSlot(slot);
        if (is.isEmpty()) {
            cir.setReturnValue(ItemStack.EMPTY);
            return;
        }
        if (filter.isKeepLast()) {
            amount = Math.min(amount, is.getCount() - 1);
        }
        cir.setReturnValue(getP().extractItem(slot, amount, simulate));
    }

    @Inject(method = "pullMatchingStack", at = @At("HEAD"), cancellable = true)
    private void ddp$bypassPolyOnPullMatchingStack(ItemStack st, long max, CallbackInfoReturnable<ItemStack> cir) {
        if (!ddp$isPoly()) return;
        // Call the default IInventoryAccess.pullMatchingStack via the
        // (now-unfiltered) IItemHandler interface methods. Since this mixin
        // also rewrites getStackInSlot/extractItem above, falling through to
        // the default implementation via the original method body would
        // already produce the right result — but the original's first line
        // (`if (!test(st)) return ItemStack.EMPTY`) short-circuits before
        // getting there. So we re-implement the default's contract here:
        // iterate slots, pull matching items up to `max`.
        IItemHandler h = getP();
        int slots = h.getSlots();
        ItemStack out = ItemStack.EMPTY;
        for (int i = 0; i < slots && (out.isEmpty() || out.getCount() < max); i++) {
            ItemStack inSlot = h.getStackInSlot(i);
            if (inSlot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(inSlot, st)) continue;
            long want = max - (out.isEmpty() ? 0 : out.getCount());
            ItemStack got = h.extractItem(i, (int) Math.min(want, Integer.MAX_VALUE), false);
            if (got.isEmpty()) continue;
            if (out.isEmpty()) out = got;
            else out.grow(got.getCount());
        }
        cir.setReturnValue(out);
    }
}
