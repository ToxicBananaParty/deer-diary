package milkucha.trmt.compat.jade;

import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public class TRMTJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // Single registration on Block.class — the provider gates internally by checking
        // for either an eroded TRMT block or a cache entry. Registering against multiple
        // classes (especially Blocks.DIRT.getClass() which is just Block.class) duplicated
        // the tooltip on every hover.
        registration.registerBlockComponent(ErosionProvider.INSTANCE, Block.class);
    }
}
